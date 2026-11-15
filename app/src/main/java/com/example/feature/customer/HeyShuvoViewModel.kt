package com.example.feature.customer

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.provider.Settings
import android.speech.tts.TextToSpeech
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.core.actions.ActionExecutionOutcome
import com.example.core.actions.DeviceActionKind
import com.example.core.actions.ExecutableActionPayload
import com.example.core.actions.SelfScreenTimeItem
import com.example.core.actions.VoiceDeviceController
import com.example.core.preferences.AiPersonaFace
import com.example.core.preferences.AppThemePalette
import com.example.core.preferences.AssistantSettings
import com.example.core.preferences.PreferencesRepository
import com.example.core.preferences.UserAccessibilityMode
import com.example.core.service.HeyShuvoAllDisplayService
import com.example.core.service.HeyShuvoVoiceCoordinator
import com.example.core.util.BanglaCommandHelper
import com.example.core.voice.SpeechRecognitionState
import com.example.core.voice.VoiceAssistantEngine
import android.util.Base64
import com.example.data.cloud.model.AuditEntry
import com.example.data.cloud.model.BirthdayWishEntry
import com.example.data.cloud.model.CustomerRemoteConfig
import com.example.data.cloud.model.FeatureRequest
import com.example.data.cloud.model.GestureTransferSession
import com.example.data.cloud.model.Invitation
import com.example.data.cloud.model.SkillModule
import com.example.data.cloud.model.UserAccount
import com.example.data.cloud.repository.GovernanceRepository
import com.example.data.local.BirthdayReminderEntity
import com.example.data.local.LedgerEntryEntity
import com.example.data.local.PrivateMemoryEntity
import com.example.data.local.PrivateVaultRepository
import com.example.data.local.TaskLogEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.util.Calendar
import java.util.UUID

data class ChatTurn(
    val id: String = UUID.randomUUID().toString(),
    val isUser: Boolean,
    val message: String,
    val languageBadge: String = "BN/EN",
    val taskId: String? = null,
    val steps: List<SequentialStepPlan> = emptyList(),
    val awaitingConfirmation: Boolean = false,
    val confirmed: Boolean = false,
    val outcomes: List<ActionExecutionOutcome> = emptyList(),
    val isError: Boolean = false,
    val retryPrompt: String? = null
)

class HeyShuvoViewModel(
    private val appContext: Context,
    private val localOwnerKey: String,
    private val authenticatedCloudUid: String?,
    private val governanceRepository: GovernanceRepository,
    private val privateVaultRepository: PrivateVaultRepository,
    private val preferencesRepository: PreferencesRepository,
    private val voiceEngine: VoiceAssistantEngine,
    private val deviceController: VoiceDeviceController,
    private val aiService: HeyShuvoAiService = HeyShuvoAiService()
) : ViewModel() {

    val isCloudAuthenticated: Boolean = !authenticatedCloudUid.isNullOrBlank()

    val settings: StateFlow<AssistantSettings> = preferencesRepository.settingsFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, AssistantSettings())

    val voiceState: StateFlow<SpeechRecognitionState> = voiceEngine.state

    val memories: StateFlow<List<PrivateMemoryEntity>> = privateVaultRepository.observeMemories(localOwnerKey)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    val taskHistory: StateFlow<List<TaskLogEntity>> = privateVaultRepository.observeTaskLogs(localOwnerKey)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    val ledgerEntries: StateFlow<List<LedgerEntryEntity>> = privateVaultRepository.observeLedger(localOwnerKey)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    private val localBirthdays: StateFlow<List<BirthdayReminderEntity>> =
        privateVaultRepository.observeBirthdays(localOwnerKey)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    private val cloudBirthdays: StateFlow<List<BirthdayWishEntry>> = (
        if (authenticatedCloudUid != null) governanceRepository.observeUserBirthdays()
        else flowOf(emptyList())
        ).catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    val birthdays: StateFlow<List<BirthdayReminderEntity>> = combine(localBirthdays, cloudBirthdays) { local, cloud ->
        val cloudMapped = cloud.map { c ->
            BirthdayReminderEntity(
                id = c.id.ifBlank { "cloud_${c.personName}_${c.birthDay}_${c.birthMonth}" },
                ownerUid = localOwnerKey,
                personName = c.personName,
                birthDay = c.birthDay.toInt(),
                birthMonth = c.birthMonth.toInt(),
                phoneOrContact = c.phoneOrContact,
                wishMessage = c.wishMessage,
                lastWishedYear = c.lastWishedYear.toInt()
            )
        }
        (cloudMapped + local).distinctBy { it.id }.sortedWith(compareBy({ it.birthMonth }, { it.birthDay }))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    private val defaultSkillModules = listOf(
        SkillModule(
            id = "skill_hey_shuvo_all_control",
            name = "Hey Shuvo Full Voice Control",
            description = "Controls Auto/Manual Mode, Cross-Display Navigation, Location & Maps, Photo Vision, Flashlight, Volume, Battery, Apps, YouTube, Word (.doc), Excel (.csv formulas), PDF, AutoCAD (.lsp), Tally Khata, and Windows PC via voice.",
            version = "2.6.0",
            instructions = "Execute user voice commands immediately and accurately across phone hardware, Bangladesh location/Maps, apps, cross-display navigation, Office, AutoCAD, and Tally Khata.",
            requiredPermissions = "RECORD_AUDIO, ACCESS_FINE_LOCATION, SYSTEM_ALERT_WINDOW, SET_ALARM",
            supportedLanguages = "Bangla, English, Banglish, Sylheti, Chittagonian, Noakhali",
            rolloutPercentage = 100L,
            enabled = true,
            testStatus = "verified"
        )
    )

    private val cloudEnabledSkills: StateFlow<List<SkillModule>> = (
        if (authenticatedCloudUid != null) governanceRepository.observeEnabledSkills()
        else flowOf(emptyList())
        ).catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    val myFeatureRequests: StateFlow<List<FeatureRequest>> = (
        if (authenticatedCloudUid != null) governanceRepository.observeMyFeatureRequests(authenticatedCloudUid)
        else flowOf(emptyList())
        ).catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    // Master App Governance State
    private val _isOwnerAdmin = MutableStateFlow(false)
    val isOwnerAdmin: StateFlow<Boolean> = _isOwnerAdmin.asStateFlow()

    private val _canClaimMasterOwner = MutableStateFlow(false)
    val canClaimMasterOwner: StateFlow<Boolean> = _canClaimMasterOwner.asStateFlow()

    val allSkills: StateFlow<List<SkillModule>> = (
        if (authenticatedCloudUid != null) governanceRepository.observeAllSkillsForOwner()
        else flowOf(emptyList())
        ).catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    val allFeatureRequests: StateFlow<List<FeatureRequest>> = (
        if (authenticatedCloudUid != null) governanceRepository.observeAllFeatureRequestsForOwner()
        else flowOf(emptyList())
        ).catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    val allAccounts: StateFlow<List<UserAccount>> = (
        if (authenticatedCloudUid != null) governanceRepository.observeAllAccountsForOwner()
        else flowOf(emptyList())
        ).catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    val invitations: StateFlow<List<Invitation>> = (
        if (authenticatedCloudUid != null) governanceRepository.observeInvitationsForOwner()
        else flowOf(emptyList())
        ).catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    val auditLogs: StateFlow<List<AuditEntry>> = (
        if (authenticatedCloudUid != null) governanceRepository.observeAuditLogsForOwner()
        else flowOf(emptyList())
        ).catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    private val _localFeatureQueue = MutableStateFlow<List<FeatureRequest>>(emptyList())
    val localFeatureQueue: StateFlow<List<FeatureRequest>> = _localFeatureQueue.asStateFlow()

    private val _masterAiDraft = MutableStateFlow<MasterAiDraft?>(null)
    val masterAiDraft: StateFlow<MasterAiDraft?> = _masterAiDraft.asStateFlow()

    private val _isMasterBusy = MutableStateFlow(false)
    val isMasterBusy: StateFlow<Boolean> = _isMasterBusy.asStateFlow()

    private val _isMasterVoiceListening = MutableStateFlow(false)
    val isMasterVoiceListening: StateFlow<Boolean> = _isMasterVoiceListening.asStateFlow()

    val chatTurns: StateFlow<List<ChatTurn>> = HeyShuvoVoiceCoordinator.chatTurns
    val isProcessing: StateFlow<Boolean> = HeyShuvoVoiceCoordinator.isProcessing
    val statusBanner: StateFlow<String?> = HeyShuvoVoiceCoordinator.statusBanner

    private val _visionOutput = MutableStateFlow("")
    val visionOutput: StateFlow<String> = _visionOutput.asStateFlow()

    private val _smartWriterOutput = MutableStateFlow("")
    val smartWriterOutput: StateFlow<String> = _smartWriterOutput.asStateFlow()

    private val _isSmartWriterBusy = MutableStateFlow(false)
    val isSmartWriterBusy: StateFlow<Boolean> = _isSmartWriterBusy.asStateFlow()

    private val _selfScreenTime = MutableStateFlow<List<SelfScreenTimeItem>>(emptyList())
    val selfScreenTime: StateFlow<List<SelfScreenTimeItem>> = _selfScreenTime.asStateFlow()

    private val _generatedFiles = MutableStateFlow<List<File>>(emptyList())
    val generatedFiles: StateFlow<List<File>> = _generatedFiles.asStateFlow()

    // Hand-Gesture Cross-Device File Transfer State
    private val _gesturePairingCode = MutableStateFlow("SHUVO26")
    val gesturePairingCode: StateFlow<String> = _gesturePairingCode.asStateFlow()

    private val _gestureStatusMessage = MutableStateFlow("ফাইল বেছে ক্যামেরার সামনে হাত মুঠো (✊) করুন")
    val gestureStatusMessage: StateFlow<String> = _gestureStatusMessage.asStateFlow()

    private val _pendingIncomingTransfers = MutableStateFlow<List<GestureTransferSession>>(emptyList())
    val pendingIncomingTransfers: StateFlow<List<GestureTransferSession>> = _pendingIncomingTransfers.asStateFlow()

    private val _receivedTransferFiles = MutableStateFlow<List<File>>(emptyList())
    val receivedTransferFiles: StateFlow<List<File>> = _receivedTransferFiles.asStateFlow()

    private val _isGestureBusy = MutableStateFlow(false)
    val isGestureBusy: StateFlow<Boolean> = _isGestureBusy.asStateFlow()

    private var pairingObserverJob: Job? = null
    private var localGrabbedSessionCache: GestureTransferSession? = null
    private var didAutoBirthdayCheckThisSession = false

    // Permission-Based All-Day Voice Diary & Scheduled End-of-Day Summary
    private val _allDayRecordingEnabled = MutableStateFlow(false)
    val allDayRecordingEnabled: StateFlow<Boolean> = _allDayRecordingEnabled.asStateFlow()

    private val _allDaySummaryHour = MutableStateFlow(21)
    val allDaySummaryHour: StateFlow<Int> = _allDaySummaryHour.asStateFlow()

    private val _allDaySummaryMinute = MutableStateFlow(0)
    val allDaySummaryMinute: StateFlow<Int> = _allDaySummaryMinute.asStateFlow()

    private val _allDaySummaryLanguage = MutableStateFlow("BN")
    val allDaySummaryLanguage: StateFlow<String> = _allDaySummaryLanguage.asStateFlow()

    private val _allDaySummaryEmail = MutableStateFlow("")
    val allDaySummaryEmail: StateFlow<String> = _allDaySummaryEmail.asStateFlow()

    private val _allDayNoiseDb = MutableStateFlow(34)
    val allDayNoiseDb: StateFlow<Int> = _allDayNoiseDb.asStateFlow()

    private val _allDayLogs = MutableStateFlow<List<String>>(emptyList())
    val allDayLogs: StateFlow<List<String>> = _allDayLogs.asStateFlow()

    private val _dailySummaryOutput = MutableStateFlow("")
    val dailySummaryOutput: StateFlow<String> = _dailySummaryOutput.asStateFlow()

    private val _isDailySummaryBusy = MutableStateFlow(false)
    val isDailySummaryBusy: StateFlow<Boolean> = _isDailySummaryBusy.asStateFlow()

    init {
        HeyShuvoVoiceCoordinator.activeLocalOwnerKey = localOwnerKey
        HeyShuvoVoiceCoordinator.activeMasterPromptProvider = {
            settings.value.masterCustomSystemPrompt
        }
        HeyShuvoVoiceCoordinator.activeSkillsProvider = {
            val cloudList = cloudEnabledSkills.value.ifEmpty { defaultSkillModules }
            val localMasterList = settings.value.localMasterSkills.filter { it.enabled }
            val merged = (localMasterList + cloudList).distinctBy { it.name }
            merged.take(8).joinToString("\n") { sk -> "- ${sk.name}: ${sk.instructions}" }
        }
        HeyShuvoVoiceCoordinator.onSubmitFeatureRequestBridge = { title, desc ->
            submitCustomerFeatureOrIssue(title, desc)
        }
        HeyShuvoVoiceCoordinator.onSaveBirthdayCloudBridge = { entry ->
            if (authenticatedCloudUid != null) {
                viewModelScope.launch {
                    governanceRepository.saveUserBirthday(
                        birthdayId = entry.id,
                        personName = entry.personName,
                        birthDay = entry.birthDay,
                        birthMonth = entry.birthMonth,
                        phoneOrContact = entry.phoneOrContact,
                        wishMessage = entry.wishMessage,
                        lastWishedYear = entry.lastWishedYear
                    )
                }
            }
        }

        if (authenticatedCloudUid != null) {
            viewModelScope.launch {
                governanceRepository.ensureUserAccountRegistered()
                refreshMasterCloudOwnershipState()
            }
            // Live Cloud Sync: any Master App Save & Apply across devices updates Customer App immediately!
            viewModelScope.launch {
                governanceRepository.observeCustomerRemoteConfig()
                    .catch { emit(null) }
                    .collect { remoteCfg ->
                        if (remoteCfg != null && remoteCfg.configVersion > settings.value.masterConfigVersion) {
                            preferencesRepository.applyMasterCustomerUpdateLocally(
                                announcementBanner = remoteCfg.announcementBanner,
                                forcedThemePalette = remoteCfg.forcedThemePalette,
                                defaultAutoVoice = remoteCfg.defaultAutoVoice,
                                customSystemPrompt = remoteCfg.customSystemPrompt,
                                enabledFeatureFlags = remoteCfg.enabledFeatureFlags,
                                lastAppliedSummary = remoteCfg.lastAppliedFixSummary,
                                remoteVersion = remoteCfg.configVersion,
                                monthlyPaidFeeBdt = remoteCfg.monthlyPaidFeeBdt,
                                freeDailyLimit = remoteCfg.freeDailyLimit,
                                globalPaidModeEnabled = remoteCfg.globalPaidModeEnabled
                            )
                        }
                    }
            }
            // Live Cloud Sync: sync own subscription tier from /accounts/{uid}
            viewModelScope.launch {
                governanceRepository.observeOwnAccount(authenticatedCloudUid)
                    .catch { emit(null) }
                    .collect { account ->
                        if (account != null && !account.subscriptionTier.isNullOrBlank()) {
                            val cloudPaid = account.subscriptionTier.equals("paid", ignoreCase = true)
                            if (cloudPaid != settings.value.currentUserIsPaid) {
                                preferencesRepository.setCurrentUserPaidStatus(cloudPaid, validityDays = 0)
                            }
                        }
                    }
            }
        }
        refreshLocalState()

        HeyShuvoVoiceCoordinator.onExternalStopAuto = {
            viewModelScope.launch {
                preferencesRepository.setAutoAllDisplayVoice(false)
            }
        }

        viewModelScope.launch {
            var previousAuto: Boolean? = null
            settings.collect { s ->
                HeyShuvoVoiceCoordinator.cachedSettings = s
                HeyShuvoVoiceCoordinator.latestUserMode = s.userMode
                voiceEngine.setElderlySlowClarity(s.userMode == UserAccessibilityMode.ELDERLY)
                voiceEngine.setListenBackgroundVoicesMode(s.listenBackgroundVoices)
                if (s.autoAllDisplayVoice && previousAuto != true) {
                    previousAuto = true
                    if (hasMicPermission()) {
                        kotlinx.coroutines.delay(500L)
                        ensureAutoVoiceActive()
                    }
                } else if (!s.autoAllDisplayVoice && previousAuto == true) {
                    previousAuto = false
                    HeyShuvoAllDisplayService.stop(appContext)
                    voiceEngine.stopListening(submitRecordedAudio = false)
                }
            }
        }

        viewModelScope.launch {
            ledgerEntries.collect { list ->
                HeyShuvoVoiceCoordinator.cachedTallySummary = HeyShuvoVoiceCoordinator.formatTallySummary(list)
            }
        }

        viewModelScope.launch {
            memories.collect { list ->
                HeyShuvoVoiceCoordinator.cachedMemorySummary = list.take(8).joinToString("\n") { "- ${it.topic}: ${it.content}" }
            }
        }

        viewModelScope.launch(Dispatchers.IO) {
            var lastLoggedUserTurnId: String? = null
            var lastLoggedAssistantTurnId: String? = null
            chatTurns.collect { turns ->
                _generatedFiles.value = deviceController.listGeneratedLocalDocs()
                _selfScreenTime.value = deviceController.querySelfTodayScreenTime()
                if (_allDayRecordingEnabled.value) {
                    val lastUserTurn = turns.lastOrNull { it.isUser }
                    val cleanSpeech = lastUserTurn?.message?.removePrefix("🎤 ")?.trim()
                    if (lastUserTurn != null && lastUserTurn.id != lastLoggedUserTurnId && !cleanSpeech.isNullOrBlank()) {
                        lastLoggedUserTurnId = lastUserTurn.id
                        appendAllDayVoiceEntry("User: $cleanSpeech")
                    }
                    val lastAssistantTurn = turns.lastOrNull { !it.isUser && !it.isError }
                    val cleanReply = lastAssistantTurn?.message?.trim()
                    if (lastAssistantTurn != null && lastAssistantTurn.id != lastLoggedAssistantTurnId && !cleanReply.isNullOrBlank()) {
                        lastLoggedAssistantTurnId = lastAssistantTurn.id
                        appendAllDayVoiceEntry("Hey Shuvo: $cleanReply")
                    }
                }
            }
        }

        viewModelScope.launch {
            birthdays.collect { list ->
                checkAndTriggerTodayBirthdays(list)
            }
        }

        viewModelScope.launch(Dispatchers.IO) {
            loadAllDayDiaryPrefs()
            refreshReceivedGestureFiles()
        }
        observeGesturePairingCode(_gesturePairingCode.value)
        viewModelScope.launch {
            // Wait briefly for DataStore & Room initial emissions before evaluating daily reminders
            withTimeoutOrNull(1800L) {
                birthdays.first { it.isNotEmpty() }
            }
            evaluateAutomaticCustomerEmails(manualTrigger = false)
        }
    }

    suspend fun refreshMasterCloudOwnershipState() {
        if (authenticatedCloudUid == null) {
            _isOwnerAdmin.value = false
            _canClaimMasterOwner.value = false
            return
        }
        val admin = governanceRepository.checkIsAdminAccount()
        _isOwnerAdmin.value = admin
        _canClaimMasterOwner.value = if (!admin) governanceRepository.isMasterOwnerUnclaimed() else false
    }

    fun claimMasterOwner() {
        if (authenticatedCloudUid == null) {
            HeyShuvoVoiceCoordinator.postBanner("ক্লাউড মাস্টার সিঙ্ক সক্রিয় করতে প্রথমে Google Sign-In করুন।")
            return
        }
        _isMasterBusy.value = true
        viewModelScope.launch {
            val res = governanceRepository.claimMasterOwnerIfAvailable()
            refreshMasterCloudOwnershipState()
            _isMasterBusy.value = false
            res.fold(
                onSuccess = { claimed ->
                    if (claimed) {
                        HeyShuvoVoiceCoordinator.postBanner("✓ এই অ্যাকাউন্টটি এখন Cloud Master Owner হিসেবে সক্রিয় হয়েছে!")
                    } else {
                        HeyShuvoVoiceCoordinator.postBanner("অন্য একটি অ্যাকাউন্ট ইতিমধ্যে Cloud Master Owner হিসেবে নিবন্ধিত।")
                    }
                },
                onFailure = {
                    HeyShuvoVoiceCoordinator.postBanner("মাস্টার অ্যাকাউন্ট তথ্য: ${it.localizedMessage ?: "ইতিমধ্যে নির্ধারিত"}")
                }
            )
        }
    }

    fun submitCustomerFeatureOrIssue(title: String, description: String) {
        val cleanTitle = title.trim().ifBlank { "Customer Request" }
        val cleanDesc = description.trim().ifBlank { cleanTitle }
        viewModelScope.launch {
            if (authenticatedCloudUid != null) {
                governanceRepository.submitFeatureRequest(
                    requesterName = "Customer App User",
                    title = cleanTitle,
                    description = cleanDesc
                )
            } else {
                val localReq = FeatureRequest(
                    id = "local_req_${System.currentTimeMillis()}",
                    requesterUid = localOwnerKey,
                    requesterName = "Local Customer",
                    title = cleanTitle,
                    description = cleanDesc,
                    status = "submitted"
                )
                _localFeatureQueue.value = listOf(localReq) + _localFeatureQueue.value
            }
            HeyShuvoVoiceCoordinator.postBanner("✓ আপনার অনুরোধটি মাস্টার কন্ট্রোলে পাঠানো হয়েছে।")
        }
    }

    /**
     * Voice capture dedicated to Master App commands ("এই সমস্যাটা ঠিক করে দাও", "এই featureটা Customer App-এ যোগ করে দাও",
     * OR voice-only background sound listening mode!).
     */
    fun startMasterVoiceCommand() {
        if (!hasMicPermission()) {
            HeyShuvoVoiceCoordinator.postBanner("মাইক্রোফোন পারমিশন (Allow Microphone) চালু করুন।")
            return
        }
        _isMasterVoiceListening.value = true
        voiceEngine.startListening(
            languageTag = "bn-BD",
            preferDirectAiAudio = false,
            continuousAutoMode = false,
            onTextResult = { spokenText, _ ->
                _isMasterVoiceListening.value = false
                if (spokenText.isNotBlank()) {
                    runMasterVoiceOrTextCommand(spokenText)
                }
                resumeCustomerAutoStreamIfNeeded()
            },
            onAudioResult = { audioBytes, mimeType ->
                _isMasterVoiceListening.value = false
                _isMasterBusy.value = true
                viewModelScope.launch {
                    val audioRes = aiService.analyzeAudioCommand(
                        audioBytes = audioBytes,
                        mimeType = mimeType,
                        mode = UserAccessibilityMode.STANDARD
                    )
                    _isMasterBusy.value = false
                    audioRes.onSuccess { parsed ->
                        val spoken = parsed.spokenTranscript.trim()
                        if (spoken.isNotBlank() && !spoken.equals("__NO_SPEECH__", ignoreCase = true)) {
                            runMasterVoiceOrTextCommand(spoken)
                        } else {
                            HeyShuvoVoiceCoordinator.postBanner("স্পষ্ট কথা শোনা যায়নি, আবার বলুন বস।")
                        }
                    }.onFailure { err ->
                        HeyShuvoVoiceCoordinator.postBanner(HeyShuvoAiService.classifyFriendlyAiError(err))
                    }
                    resumeCustomerAutoStreamIfNeeded()
                }
            },
            onSilentCycle = {
                _isMasterVoiceListening.value = false
                HeyShuvoVoiceCoordinator.postBanner("স্পষ্ট কথা শোনা যায়নি, আবার বলুন বস।")
                resumeCustomerAutoStreamIfNeeded()
            }
        )
    }

    fun stopMasterVoiceCommand() {
        _isMasterVoiceListening.value = false
        voiceEngine.stopListening(submitRecordedAudio = true)
    }

    private fun resumeCustomerAutoStreamIfNeeded() {
        if (settings.value.autoAllDisplayVoice && hasMicPermission() && !voiceEngine.isContinuousStreamActive()) {
            HeyShuvoVoiceCoordinator.startVoiceCapture(
                context = appContext,
                preferDirectAiAudio = true,
                continuousAutoMode = true,
                userInitiated = false
            )
        }
    }

    fun runMasterVoiceOrTextCommand(rawCommand: String) {
        val clean = rawCommand.trim()
        if (clean.isBlank()) return

        // 1. Check pure wake word ("Hey Shuvo" / "Shuvo" / "হে শুভ" / "শুভ") in Master App too!
        val localCheck = BanglaCommandHelper.buildLocalFallbackAnalysisOrNull(clean)
        if (localCheck != null && localCheck.naturalReply.contains("শুনতে পাচ্ছি বস")) {
            HeyShuvoVoiceCoordinator.postBanner(localCheck.naturalReply)
            speakMessage(localCheck.naturalReply)
            return
        }

        // 2. Check Voice-Only Background Voice / Sound Listening command in Master App too!
        val explicitSteps = BanglaCommandHelper.extractExplicitSteps(clean)
        val bgStep = explicitSteps.firstOrNull { it.actionPayload.kind == DeviceActionKind.SET_BACKGROUND_VOICE_LISTENING }
        if (bgStep != null) {
            val enable = bgStep.actionPayload.targetOrPath.equals("ON", ignoreCase = true)
            viewModelScope.launch {
                preferencesRepository.setListenBackgroundVoices(enable)
                voiceEngine.setListenBackgroundVoicesMode(enable)
                val msg = if (enable) {
                    "জি বস, এখন থেকে আমি ব্যাকগ্রাউন্ডের কথা ও শব্দও মনোযোগ দিয়ে শুনছি।"
                } else {
                    "জি বস, ব্যাকগ্রাউন্ডের শব্দ শোনা বন্ধ করেছি—এখন থেকে শুধু আপনার স্পষ্ট কমান্ড শুনব।"
                }
                HeyShuvoVoiceCoordinator.postBanner(msg)
                speakMessage(msg)
            }
            return
        }

        // 3. Draft Customer App Fix / New Feature / Config Update via AI
        _isMasterBusy.value = true
        viewModelScope.launch {
            val cur = settings.value
            val summary = "Banner='${cur.masterAnnouncementBanner}', Rule='${cur.masterCustomSystemPrompt}', " +
                "Flags='${cur.masterEnabledFeatureFlags}', Theme=${cur.themePalette.name}, AutoVoice=${cur.autoAllDisplayVoice}"
            val res = aiService.draftMasterActionFromCommand(clean, summary)
            _isMasterBusy.value = false
            res.fold(
                onSuccess = { draft ->
                    _masterAiDraft.value = draft
                    speakMessage(draft.summaryReplyBn)
                },
                onFailure = { err ->
                    HeyShuvoVoiceCoordinator.postBanner("ত্রুটি: ${err.localizedMessage ?: "আবার চেষ্টা করুন"}")
                }
            )
        }
    }

    fun dismissMasterDraft() {
        _masterAiDraft.value = null
    }

    private fun buildFeatureFlagsString(
        enableOfficeDocs: Boolean,
        enableTallyKhata: Boolean,
        enablePhotoVision: Boolean,
        enableWindowsBridge: Boolean,
        enableGestureTransfer: Boolean = true,
        enableBirthdayWish: Boolean = true
    ): String {
        val list = mutableListOf("ALL_DISPLAY_VOICE", "MAPS_NAVIGATION", "SMART_HUB")
        if (enablePhotoVision) list.add("PHOTO_VISION")
        if (enableOfficeDocs) {
            list.add("OFFICE_SUITE")
            list.add("AUTOCAD_PLAN")
        }
        if (enableTallyKhata) list.add("TALLY_KHATA")
        if (enableWindowsBridge) list.add("WINDOWS_BRIDGE")
        if (enableGestureTransfer) list.add("GESTURE_TRANSFER")
        if (enableBirthdayWish) list.add("BIRTHDAY_WISH")
        return list.joinToString(",")
    }

    /**
     * Save & Apply button for AI-generated Master fix/feature draft -> updates Customer App immediately
     * both locally (0ms) and across Cloud Firestore!
     */
    fun applyMasterDraftToCustomerApp() {
        val draft = _masterAiDraft.value ?: return
        _isMasterBusy.value = true
        viewModelScope.launch {
            val cur = settings.value
            val mergedRule = if (draft.proposedSystemRule.isNotBlank()) {
                if (cur.masterCustomSystemPrompt.isBlank()) draft.proposedSystemRule
                else "${cur.masterCustomSystemPrompt}\n• ${draft.proposedSystemRule}"
            } else {
                cur.masterCustomSystemPrompt
            }
            val banner = draft.announcementBanner.ifBlank { cur.masterAnnouncementBanner }
            val flags = buildFeatureFlagsString(
                enableOfficeDocs = draft.enableOfficeDocs,
                enableTallyKhata = draft.enableTallyKhata,
                enablePhotoVision = draft.enablePhotoVision,
                enableWindowsBridge = draft.enableWindowsBridge
            )

            if (draft.skillTitle.isNotBlank()) {
                preferencesRepository.upsertLocalMasterSkill(
                    SkillModule(
                        name = draft.skillTitle,
                        description = draft.skillDescription.ifBlank { draft.summaryReplyBn },
                        version = "1.0.0",
                        instructions = draft.skillInstructions.ifBlank { draft.proposedSystemRule },
                        enabled = true
                    )
                )
            }

            preferencesRepository.applyMasterCustomerUpdateLocally(
                announcementBanner = banner,
                forcedThemePalette = draft.recommendedPalette,
                defaultAutoVoice = cur.autoAllDisplayVoice,
                customSystemPrompt = mergedRule,
                enabledFeatureFlags = flags,
                lastAppliedSummary = draft.summaryReplyBn
            )

            if (_isOwnerAdmin.value && authenticatedCloudUid != null) {
                if (draft.skillTitle.isNotBlank()) {
                    governanceRepository.saveSkillModule(
                        skillId = null,
                        name = draft.skillTitle,
                        description = draft.skillDescription.ifBlank { draft.summaryReplyBn },
                        version = "1.0.0",
                        instructions = draft.skillInstructions.ifBlank { draft.proposedSystemRule },
                        requiredPermissions = "None",
                        supportedLanguages = "Bangla, English, Banglish",
                        rolloutPercentage = 100L,
                        enabled = true,
                        testStatus = "verified"
                    )
                }
                governanceRepository.publishCustomerRemoteConfig(
                    announcementBanner = banner,
                    forcedThemePalette = draft.recommendedPalette.ifBlank { cur.themePalette.name },
                    defaultAutoVoice = cur.autoAllDisplayVoice,
                    customSystemPrompt = mergedRule,
                    enabledFeatureFlags = flags,
                    lastAppliedFixSummary = draft.summaryReplyBn,
                    monthlyPaidFeeBdt = cur.monthlyPaidFeeBdt,
                    freeDailyLimit = cur.freeDailyLimit,
                    globalPaidModeEnabled = cur.globalPaidModeEnabled
                )
            }

            _masterAiDraft.value = null
            _isMasterBusy.value = false
            val confirmMsg = "জি বস, পরিবর্তনটি Save ও Apply করা হয়েছে এবং সরাসরি Customer App-এ যুক্ত হয়ে গেছে।"
            HeyShuvoVoiceCoordinator.postBanner("✓ $confirmMsg")
            speakMessage(confirmMsg)
        }
    }

    /**
     * Direct manual Save & Apply from Master Control Panel -> immediately updates Customer App locally + Cloud!
     */
    fun saveAndApplyCustomerConfig(
        announcementBanner: String,
        customSystemPrompt: String,
        enableOfficeDocs: Boolean,
        enableTallyKhata: Boolean,
        enablePhotoVision: Boolean,
        enableWindowsBridge: Boolean,
        defaultAutoVoice: Boolean,
        themePaletteName: String
    ) {
        _isMasterBusy.value = true
        viewModelScope.launch {
            val cur = settings.value
            val flags = buildFeatureFlagsString(
                enableOfficeDocs = enableOfficeDocs,
                enableTallyKhata = enableTallyKhata,
                enablePhotoVision = enablePhotoVision,
                enableWindowsBridge = enableWindowsBridge
            )
            val summary = "Master Control Panel Update (${themePaletteName})"

            preferencesRepository.applyMasterCustomerUpdateLocally(
                announcementBanner = announcementBanner.trim(),
                forcedThemePalette = themePaletteName,
                defaultAutoVoice = defaultAutoVoice,
                customSystemPrompt = customSystemPrompt.trim(),
                enabledFeatureFlags = flags,
                lastAppliedSummary = summary
            )

            if (_isOwnerAdmin.value && authenticatedCloudUid != null) {
                governanceRepository.publishCustomerRemoteConfig(
                    announcementBanner = announcementBanner.trim(),
                    forcedThemePalette = themePaletteName,
                    defaultAutoVoice = defaultAutoVoice,
                    customSystemPrompt = customSystemPrompt.trim(),
                    enabledFeatureFlags = flags,
                    lastAppliedFixSummary = summary,
                    monthlyPaidFeeBdt = cur.monthlyPaidFeeBdt,
                    freeDailyLimit = cur.freeDailyLimit,
                    globalPaidModeEnabled = cur.globalPaidModeEnabled
                )
            }

            _isMasterBusy.value = false
            val msg = "✓ Save & Apply সম্পন্ন: সব পরিবর্তন Customer App-এ সাথে সাথে যুক্ত হয়েছে।"
            HeyShuvoVoiceCoordinator.postBanner(msg)
            speakMessage("জি বস, Customer App সফলভাবে আপডেট হয়েছে।")
        }
    }

    fun toggleMasterSkill(skill: SkillModule, enabled: Boolean) {
        viewModelScope.launch {
            if (_isOwnerAdmin.value && authenticatedCloudUid != null && !skill.id.startsWith("local_")) {
                governanceRepository.saveSkillModule(
                    skillId = skill.id.takeIf { it.isNotBlank() },
                    name = skill.name,
                    description = skill.description,
                    version = skill.version,
                    instructions = skill.instructions,
                    requiredPermissions = skill.requiredPermissions,
                    supportedLanguages = skill.supportedLanguages,
                    rolloutPercentage = skill.rolloutPercentage,
                    enabled = enabled,
                    testStatus = skill.testStatus
                )
            }
            preferencesRepository.upsertLocalMasterSkill(skill.copy(enabled = enabled))
            HeyShuvoVoiceCoordinator.postBanner("✓ মডিউল '${skill.name}' ${if (enabled) "চালু" else "বন্ধ"} করা হয়েছে।")
        }
    }

    fun deleteMasterSkill(skill: SkillModule) {
        viewModelScope.launch {
            if (_isOwnerAdmin.value && authenticatedCloudUid != null && skill.id.isNotBlank() && !skill.id.startsWith("local_")) {
                governanceRepository.deleteSkillModule(skill.id)
            }
            if (skill.id.isNotBlank()) {
                preferencesRepository.removeLocalMasterSkill(skill.id)
            }
            HeyShuvoVoiceCoordinator.postBanner("✓ মডিউল '${skill.name}' মুছে ফেলা হয়েছে।")
        }
    }

    fun resolveFeatureRequestWithMasterAi(req: FeatureRequest) {
        runMasterVoiceOrTextCommand("এই অনুরোধ/সমস্যাটা সমাধান করে Customer App-এ যোগ করে দাও: ${req.title} — ${req.description}")
        if (_isOwnerAdmin.value && authenticatedCloudUid != null && req.id.isNotBlank() && !req.id.startsWith("local_")) {
            viewModelScope.launch {
                governanceRepository.updateFeatureRequestStatus(req, "approved")
            }
        } else {
            _localFeatureQueue.value = _localFeatureQueue.value.map {
                if (it.id == req.id) it.copy(status = "approved") else it
            }
        }
    }

    fun inviteCircleMember(email: String, note: String) {
        if (!_isOwnerAdmin.value) {
            HeyShuvoVoiceCoordinator.postBanner("ক্লাউড মেম্বার ইনভাইট করতে প্রথমে Master Owner হিসেবে সক্রিয় করুন।")
            return
        }
        viewModelScope.launch {
            governanceRepository.createInvitation(email, note).fold(
                onSuccess = { HeyShuvoVoiceCoordinator.postBanner("✓ সার্কেল মেম্বার আমন্ত্রণ সংরক্ষিত হয়েছে: $email") },
                onFailure = { HeyShuvoVoiceCoordinator.postBanner("আমন্ত্রণ ত্রুটি: ${it.localizedMessage}") }
            )
        }
    }

    fun updateMemberStatus(uid: String, status: String) {
        if (!_isOwnerAdmin.value) return
        viewModelScope.launch {
            governanceRepository.updateAccountAccessStatus(uid, status)
        }
    }

    fun updateMemberSubscriptionTier(uid: String, isPaid: Boolean) {
        if (!_isOwnerAdmin.value) return
        val tier = if (isPaid) "paid" else "free"
        viewModelScope.launch {
            governanceRepository.updateAccountSubscriptionTier(uid, tier).fold(
                onSuccess = { HeyShuvoVoiceCoordinator.postBanner("✓ মেম্বারের প্ল্যান '${tier.uppercase()}' এ আপডেট হয়েছে!") },
                onFailure = { HeyShuvoVoiceCoordinator.postBanner("প্ল্যান আপডেট ত্রুটি: ${it.localizedMessage}") }
            )
        }
    }

    private fun hasMicPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun ensureAutoVoiceActive() {
        if (!settings.value.autoAllDisplayVoice || !hasMicPermission() || _isMasterVoiceListening.value) return
        HeyShuvoAllDisplayService.startOrRefresh(appContext)
        val vs = voiceEngine.state.value
        if (!voiceEngine.isContinuousStreamActive() && !vs.isListening && !vs.isSpeaking) {
            HeyShuvoVoiceCoordinator.startVoiceCapture(
                context = appContext,
                preferDirectAiAudio = true,
                continuousAutoMode = true,
                userInitiated = false
            )
        }
    }

    fun refreshLocalState() {
        _selfScreenTime.value = deviceController.querySelfTodayScreenTime()
        _generatedFiles.value = deviceController.listGeneratedLocalDocs()
        ensureAutoVoiceActive()
    }

    fun canDrawAllDisplayOverlay(): Boolean = deviceController.canDrawAllDisplayOverlay()
    fun requestAllDisplayOverlayPermission() = deviceController.openOverlayPermissionSettings()
    fun hasUsagePermission(): Boolean = deviceController.hasSelfUsageStatsPermission()
    fun requestUsagePermissionSettings() = deviceController.openUsageAccessSettings()
    fun isVoiceAccessibilityEnabled(): Boolean = deviceController.isVoiceAccessibilityEnabled()
    fun openVoiceAccessibilitySettings() = deviceController.openAccessibilitySettings()

    fun setAutoAllDisplayMode(enabled: Boolean) {
        if (settings.value.autoAllDisplayVoice == enabled) {
            if (enabled) {
                ensureAutoVoiceActive()
            }
            return
        }
        HeyShuvoVoiceCoordinator.cancelActiveProcessing(appContext)
        viewModelScope.launch {
            preferencesRepository.setAutoAllDisplayVoice(enabled)
            if (enabled) {
                if (hasMicPermission()) {
                    HeyShuvoAllDisplayService.startOrRefresh(appContext)
                    HeyShuvoVoiceCoordinator.startVoiceCapture(
                        appContext,
                        preferDirectAiAudio = true,
                        continuousAutoMode = true,
                        userInitiated = true
                    )
                    HeyShuvoVoiceCoordinator.postBanner("AUTO (All Display) ভয়েস চালু হয়েছে — যেকোনো স্ক্রিনে কথা বলুন।")
                }
                speakMessage("এখন অটো মোড চালু হয়েছে, আপনি কথা বলুন।")
            } else {
                HeyShuvoAllDisplayService.stop(appContext)
                voiceEngine.stopListening(submitRecordedAudio = false)
                HeyShuvoVoiceCoordinator.postBanner("MANUAL ভয়েস মোড চালু হয়েছে — মাইকে ট্যাপ করে বলুন।")
                speakMessage("এখন ম্যানুয়াল মোড চালু হয়েছে।")
            }
        }
    }

    fun setUserHomeLocation(location: String) {
        val qualified = VoiceDeviceController.qualifyLocationForMaps(location)
        viewModelScope.launch {
            preferencesRepository.setUserHomeLocation(qualified)
            HeyShuvoVoiceCoordinator.postBanner("বর্তমান লোকেশন সেট হয়েছে: $qualified")
        }
    }

    fun openCurrentLocationOnMap() {
        deviceController.executeAction(
            ExecutableActionPayload(kind = DeviceActionKind.SHOW_CURRENT_LOCATION)
        ).onSuccess { out ->
            HeyShuvoVoiceCoordinator.postBanner(out.summary.removePrefix("✓ "))
        }
    }

    fun navigateToDestinationOnMap(destination: String) {
        val cleanDest = destination.trim()
        if (cleanDest.isBlank()) {
            openCurrentLocationOnMap()
            return
        }
        sendCommand("$cleanDest যাওয়ার ম্যাপ নেভিগেশন চালু করো")
    }

    fun openGeneratedFile(path: String) {
        deviceController.openOrShareGeneratedFile(path)
            .onFailure { HeyShuvoVoiceCoordinator.postBanner("ফাইল ওপেন ত্রুটি: ${it.localizedMessage}") }
    }

    fun readGeneratedFilePreview(file: File): String = deviceController.readFileReadablePreview(file)

    fun clearBanner() {
        HeyShuvoVoiceCoordinator.clearBanner()
    }

    fun clearVoiceError() {
        voiceEngine.clearError()
    }

    fun stopSpeaking() {
        voiceEngine.stopSpeaking()
    }

    fun speakMessage(text: String) {
        val isBn = text.any { it in '\u0980'..'\u09FF' }
        voiceEngine.speak(text, preferBangla = isBn)
    }

    fun clearConversation() {
        HeyShuvoVoiceCoordinator.clearConversation(appContext)
    }

    fun cancelActiveProcessing() {
        HeyShuvoVoiceCoordinator.cancelActiveProcessing(appContext)
    }

    fun startVoiceCapture(
        banglaPreferred: Boolean = true,
        forceDirectAiMic: Boolean = false,
        continuousOverride: Boolean? = null
    ) {
        if (!hasMicPermission()) {
            HeyShuvoVoiceCoordinator.postBanner("মাইক্রোফোন পারমিশন (Allow Microphone) দিন।")
            return
        }
        val isContinuous = continuousOverride ?: settings.value.autoAllDisplayVoice
        if (isContinuous && voiceEngine.isContinuousStreamActive() && !voiceState.value.hasDetectedVoiceActivity) {
            voiceEngine.nudgeAutoStreamReady()
            return
        }
        if (isContinuous) {
            HeyShuvoAllDisplayService.startOrRefresh(appContext)
        }
        HeyShuvoVoiceCoordinator.startVoiceCapture(
            context = appContext,
            banglaPreferred = banglaPreferred,
            preferDirectAiAudio = if (isContinuous) true else forceDirectAiMic,
            continuousAutoMode = isContinuous,
            userInitiated = true
        )
    }

    fun stopVoiceCapture() {
        if (voiceEngine.isContinuousStreamActive() && !voiceState.value.hasDetectedVoiceActivity) {
            voiceEngine.nudgeAutoStreamReady()
            return
        }
        voiceEngine.stopListening(submitRecordedAudio = true)
    }

    fun cancelVoiceCapture() {
        if (voiceEngine.isContinuousStreamActive()) {
            voiceEngine.discardCurrentAutoUtterance()
            return
        }
        voiceEngine.stopListening(submitRecordedAudio = false)
        if (settings.value.autoAllDisplayVoice && hasMicPermission()) {
            HeyShuvoVoiceCoordinator.startVoiceCapture(
                context = appContext,
                preferDirectAiAudio = true,
                continuousAutoMode = true,
                userInitiated = false
            )
        }
    }

    fun setTypingActive(isTyping: Boolean) {
        HeyShuvoVoiceCoordinator.setTypingActive(appContext, isTyping)
    }

    fun sendCommand(rawCommand: String, voiceConfidence: Float = 1.0f) {
        HeyShuvoVoiceCoordinator.setTypingActive(appContext, false)
        viewModelScope.launch {
            if (!preferencesRepository.incrementAndCheckDailyUsage()) {
                val limitMsg = "আপনার আজকের ফ্রি লিমিট শেষ হয়েছে। আনলিমিটেড ব্যবহারের জন্য Paid প্ল্যানে আপগ্রেড করুন।"
                HeyShuvoVoiceCoordinator.postBanner(limitMsg)
                speakMessage(limitMsg)
                return@launch
            }
            HeyShuvoVoiceCoordinator.submitTextCommand(
                context = appContext,
                rawCommand = rawCommand,
                voiceConfidence = voiceConfidence,
                usageAlreadyCounted = true
            )
        }
    }

    fun dialEmergencyNumber(number: String) {
        deviceController.executeAction(
            ExecutableActionPayload(
                kind = DeviceActionKind.DIAL_PHONE,
                targetOrPath = number
            )
        )
    }

    fun runSmartWriterOrTranslator(inputText: String, targetStyle: String) {
        if (inputText.isBlank()) {
            HeyShuvoVoiceCoordinator.postBanner("অনুবাদ বা লেখার জন্য কিছু লিখুন।")
            return
        }
        _isSmartWriterBusy.value = true
        viewModelScope.launch {
            if (!preferencesRepository.incrementAndCheckDailyUsage()) {
                _isSmartWriterBusy.value = false
                val limitMsg = "আপনার আজকের ফ্রি লিমিট শেষ হয়েছে। আনলিমিটেড ব্যবহারের জন্য Paid প্ল্যানে আপগ্রেড করুন।"
                _smartWriterOutput.value = limitMsg
                speakMessage(limitMsg)
                return@launch
            }
            val res = aiService.translateOrDraftSmartText(inputText, targetStyle)
            _isSmartWriterBusy.value = false
            res.fold(
                onSuccess = { out ->
                    _smartWriterOutput.value = out
                },
                onFailure = { err ->
                    _smartWriterOutput.value = "নেটওয়ার্ক ত্রুটি: ${err.localizedMessage ?: "আবার চেষ্টা করুন।"}"
                }
            )
        }
    }

    fun clearSmartWriterOutput() {
        _smartWriterOutput.value = ""
    }

    fun addCustomRoutine(title: String, commandText: String) {
        viewModelScope.launch {
            preferencesRepository.addCustomRoutine(title, commandText)
            HeyShuvoVoiceCoordinator.postBanner("নতুন শর্টকাট যুক্ত হয়েছে: $title")
        }
    }

    fun deleteCustomRoutine(id: String) {
        viewModelScope.launch {
            preferencesRepository.deleteCustomRoutine(id)
        }
    }

    fun confirmAndExecutePendingTurn(turnId: String, enteredPin: String? = null) {
        HeyShuvoVoiceCoordinator.confirmPendingTurn(appContext, turnId, enteredPin)
    }

    fun undoReversibleTask(taskId: String) {
        viewModelScope.launch {
            val targetLog = taskHistory.value.find { it.taskId == taskId }
            val stepsLower = targetLog?.stepsSummary?.lowercase().orEmpty()
            if ("tally" in stepsLower || "ট্যালি" in stepsLower) {
                privateVaultRepository.deleteLatestLedgerEntry(localOwnerKey)
            }
            if ("memory" in stepsLower || "মেমরি" in stepsLower) {
                privateVaultRepository.deleteLatestMemory(localOwnerKey)
            }
            privateVaultRepository.undoTask(taskId, localOwnerKey)
            HeyShuvoVoiceCoordinator.postBanner("Task $taskId সম্পূর্ণ Undo করা হয়েছে।")
        }
    }

    fun inspectEquipmentImage(bitmap: Bitmap, query: String) {
        _visionOutput.value = "Hey Shuvo Photo Vision ছবিটি বিশ্লেষণ করছে..."
        viewModelScope.launch {
            if (!preferencesRepository.incrementAndCheckDailyUsage()) {
                val limitMsg = "আপনার আজকের ফ্রি লিমিট শেষ হয়েছে। আনলিমিটেড ব্যবহারের জন্য Paid প্ল্যানে আপগ্রেড করুন।"
                _visionOutput.value = limitMsg
                speakMessage(limitMsg)
                return@launch
            }
            try {
                var finalResult = ""
                aiService.streamVisionAnalysis(bitmap, query).collect { partial ->
                    finalResult = partial
                    _visionOutput.value = partial
                }
                if (finalResult.isNotBlank()) {
                    speakMessage(finalResult)
                }
            } catch (e: Exception) {
                val errMsg = "Photo Vision ত্রুটি: ${e.localizedMessage ?: "আবার চেষ্টা করুন।"}"
                _visionOutput.value = errMsg
                speakMessage(errMsg)
            }
        }
    }

    fun clearVisionOutput() {
        _visionOutput.value = ""
    }

    fun deletePersonalMemory(id: Long) {
        viewModelScope.launch {
            privateVaultRepository.deleteMemory(id, localOwnerKey)
        }
    }

    fun wipeAllPersonalMemory() {
        viewModelScope.launch {
            privateVaultRepository.clearMemories(localOwnerKey)
            HeyShuvoVoiceCoordinator.postBanner("সব ব্যক্তিগত মেমরি মুছে ফেলা হয়েছে।")
        }
    }

    fun deleteTallyEntry(id: Long) {
        viewModelScope.launch {
            privateVaultRepository.deleteLedgerEntry(id, localOwnerKey)
        }
    }

    fun selectPersonaFace(face: AiPersonaFace) {
        viewModelScope.launch { preferencesRepository.setPersonaFace(face) }
    }

    fun selectUserMode(mode: UserAccessibilityMode) {
        viewModelScope.launch { preferencesRepository.setUserMode(mode) }
    }

    fun selectThemePalette(palette: AppThemePalette) {
        viewModelScope.launch { preferencesRepository.setThemePalette(palette) }
    }

    fun setCustomAccentHue(hue: Float) {
        viewModelScope.launch { preferencesRepository.setCustomAccentHue(hue) }
    }

    fun setTextScale(scale: Float) {
        viewModelScope.launch { preferencesRepository.setTextScale(scale) }
    }

    fun toggleDarkHud(dark: Boolean) {
        viewModelScope.launch { preferencesRepository.setDarkHudTheme(dark) }
    }

    fun setVoiceProfile(profile: String, context: Context? = null) {
        viewModelScope.launch {
            preferencesRepository.saveVoiceProfile(profile)
            val ctx = context ?: appContext
            val voiceCommand = when {
                profile.equals("FEMALE", ignoreCase = true) -> "Female voice"
                profile.equals("DEEP", ignoreCase = true) -> "Deep voice"
                else -> "Male voice"
            }
            HeyShuvoVoiceCoordinator.handleVoiceProfileSwitchIfRequested(ctx, voiceCommand)
        }
    }

    fun selectChatHeadAvatarPhoto(bitmap: Bitmap) {
        viewModelScope.launch {
            preferencesRepository.saveCustomChatHeadPhoto(bitmap)
            HeyShuvoAllDisplayService.refreshAvatar(appContext)
            HeyShuvoVoiceCoordinator.postBanner("✓ আপনার পছন্দের ছবি পোর্ট্রেট ও চ্যাট-হেডে সেট হয়েছে!")
        }
    }

    fun resetChatHeadAvatarPhoto() {
        viewModelScope.launch {
            preferencesRepository.clearCustomChatHeadPhoto()
            HeyShuvoAllDisplayService.refreshAvatar(appContext)
            HeyShuvoVoiceCoordinator.postBanner("✓ চ্যাট-হেড ও পোর্ট্রেট ডিফল্ট রোবট অবতারে ফিরে গেছে।")
        }
    }

    fun saveAvatarPhotoIndex(isFemale: Boolean, index: Int) {
        viewModelScope.launch {
            preferencesRepository.saveAvatarPhotoIndex(isFemale, index)
        }
    }

    fun setUseCustomPortraitAvatar(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setUseCustomPortraitAvatar(enabled)
        }
    }

    fun isBanglaTtsMissing(): Boolean {
        return voiceEngine.isTtsInitialized() && !voiceEngine.isBanglaTtsAvailable()
    }

    fun openInstallBanglaTtsData() {
        try {
            val intent = Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            appContext.startActivity(intent)
        } catch (_: Exception) {
            try {
                appContext.startActivity(
                    Intent("com.android.settings.TTS_SETTINGS").apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                )
            } catch (_: Exception) {
                HeyShuvoVoiceCoordinator.postBanner("Settings > Text-to-Speech থেকে Google বাংলা ভয়েস ডাউনলোড করুন।")
            }
        }
    }

    fun openBatteryOptimizationSettings() {
        try {
            val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            appContext.startActivity(intent)
        } catch (_: Exception) {
            try {
                appContext.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", appContext.packageName, null)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                )
            } catch (_: Exception) {
                HeyShuvoVoiceCoordinator.postBanner("Settings > Battery > Unrestricted নির্বাচন করুন।")
            }
        }
    }

    // --- Hand-Gesture Cross-Device File Transfer (Fist Grab -> Open Palm Drop) ---

    fun updateGesturePairingCode(newCode: String) {
        val cleaned = newCode.uppercase().replace(Regex("[^A-Z0-9_\\-]"), "").take(12)
        _gesturePairingCode.value = cleaned
        if (cleaned.length >= 4) {
            observeGesturePairingCode(cleaned)
        }
    }

    private fun observeGesturePairingCode(code: String) {
        pairingObserverJob?.cancel()
        if (authenticatedCloudUid == null || code.length < 4) {
            _pendingIncomingTransfers.value = listOfNotNull(localGrabbedSessionCache?.takeIf { it.pairingCode == code })
            return
        }
        pairingObserverJob = viewModelScope.launch {
            governanceRepository.observeGrabbedTransfersForCode(code)
                .catch { emit(emptyList()) }
                .collect { sessions ->
                    _pendingIncomingTransfers.value = sessions
                    if (sessions.isNotEmpty()) {
                        val latest = sessions.first()
                        _gestureStatusMessage.value = "📥 '${latest.fileName}' রিসিভের জন্য প্রস্তুত — ক্যামেরার সামনে মুঠো খুলুন (🖐️)"
                    }
                }
        }
    }

    fun grabFileByFistGesture(
        fileName: String,
        mimeType: String,
        fileBytes: ByteArray,
        qualityMode: String = "HIGH"
    ) {
        if (fileBytes.isEmpty()) {
            _gestureStatusMessage.value = "ফাইলটি খালি, অন্য ফাইল নির্বাচন করুন।"
            return
        }
        val isImage = mimeType.startsWith("image/", ignoreCase = true)
        var processedBytes = if (isImage && qualityMode != "ORIGINAL") {
            compressImageBytesForTransfer(fileBytes, lowMbMode = (qualityMode == "LOW_MB"))
        } else {
            fileBytes
        }
        var effectiveMime = if (isImage && qualityMode != "ORIGINAL") "image/jpeg" else mimeType
        if (processedBytes.size > 550_000 && isImage) {
            processedBytes = compressImageBytesForTransfer(fileBytes, lowMbMode = true)
            effectiveMime = "image/jpeg"
        }
        val encoded = Base64.encodeToString(processedBytes, Base64.NO_WRAP)
        if (processedBytes.size > 550_000 || encoded.length > 740_000) {
            _gestureStatusMessage.value = "ক্লাউড রিলের জন্য ফাইলটি সর্বোচ্চ ৫৫০ KB-এর মধ্যে হতে হবে (বর্তমান: ${processedBytes.size / 1024} KB)।"
            HeyShuvoVoiceCoordinator.postBanner("ফাইল বড় হলে 'লো রেজুলেশন / ফ্রি এমবি' মোড বা ছোট ফাইল ব্যবহার করুন (সর্বোচ্চ ৫৫০ KB)।")
            return
        }
        val code = _gesturePairingCode.value.ifBlank { "SHUVO26" }
        _isGestureBusy.value = true
        _gestureStatusMessage.value = "✊ হাত মুঠো শনাক্ত হয়েছে — '$fileName' (${(processedBytes.size / 1024).coerceAtLeast(1)} KB) মুঠোয় নেওয়া হচ্ছে..."

        viewModelScope.launch {
            if (authenticatedCloudUid != null) {
                val res = governanceRepository.uploadGrabbedGestureFile(
                    pairingCode = code,
                    fileName = fileName,
                    mimeType = effectiveMime,
                    payloadBase64 = encoded,
                    fileSizeBytes = processedBytes.size.toLong()
                )
                _isGestureBusy.value = false
                res.fold(
                    onSuccess = {
                        val msg = "✊ '$fileName' হাতের মুঠোয় নেওয়া হয়েছে (কোড: $code)। টার্গেট মোবাইল/ল্যাপটপের সামনে হাত খুলে দিন (🖐️)!"
                        _gestureStatusMessage.value = msg
                        HeyShuvoVoiceCoordinator.postBanner("✓ ফাইলটি মুঠোয় নেওয়া হয়েছে — অন্য ডিভাইসে হাত খুলুন!")
                        speakMessage("ফাইলটি মুঠোয় নেওয়া হয়েছে বস। এবার অন্য ডিভাইসের সামনে হাত খুলে দিন।")
                    },
                    onFailure = { err ->
                        _gestureStatusMessage.value = "ট্রান্সফার প্রস্তুত ত্রুটি: ${err.localizedMessage ?: "আবার চেষ্টা করুন"}"
                    }
                )
            } else {
                val localSession = GestureTransferSession(
                    id = "local_gtr_${System.currentTimeMillis()}",
                    senderUid = localOwnerKey,
                    pairingCode = code,
                    fileName = fileName,
                    mimeType = effectiveMime,
                    payloadBase64 = encoded,
                    fileSizeBytes = processedBytes.size.toLong(),
                    status = "grabbed"
                )
                localGrabbedSessionCache = localSession
                _pendingIncomingTransfers.value = listOf(localSession)
                _isGestureBusy.value = false
                _gestureStatusMessage.value = "✊ '$fileName' মুঠোয় নেওয়া হয়েছে (অন্য ডিভাইসে পাঠাতে Google Sign-In যুক্ত করুন, অথবা এখানেই হাত খুলে টেস্ট করুন)।"
                speakMessage("ফাইলটি মুঠোয় নেওয়া হয়েছে বস।")
            }
        }
    }

    fun dropGrabbedFileByOpenPalm() {
        val target = _pendingIncomingTransfers.value.firstOrNull()
        if (target == null) {
            _gestureStatusMessage.value = "🖐️ হাত খোলা শনাক্ত হয়েছে — বর্তমানে কোনো ফাইল মুঠোয় নেই (পেয়ারিং কোড মেলান)।"
            return
        }
        _isGestureBusy.value = true
        viewModelScope.launch {
            try {
                val rawBytes = Base64.decode(target.payloadBase64, Base64.DEFAULT)
                val outDir = File(appContext.filesDir, "docs").apply { mkdirs() }
                val safeName = target.fileName.replace(Regex("[^A-Za-z0-9._\\-\\u0980-\\u09FF ]"), "_").ifBlank { "received_file.bin" }
                val outFile = File(outDir, "Gesture_${System.currentTimeMillis() % 10000}_$safeName")
                FileOutputStream(outFile).use { it.write(rawBytes) }

                if (authenticatedCloudUid != null && !target.id.startsWith("local_")) {
                    governanceRepository.markGestureTransferReceived(target.id)
                } else {
                    localGrabbedSessionCache = null
                    _pendingIncomingTransfers.value = emptyList()
                }
                refreshReceivedGestureFiles()
                _generatedFiles.value = deviceController.listGeneratedLocalDocs()
                _isGestureBusy.value = false
                val msg = "🖐️ সফলভাবে রিসিভ হয়েছে: ${target.fileName}"
                _gestureStatusMessage.value = msg
                HeyShuvoVoiceCoordinator.postBanner("✓ হাতের ইশারায় '${target.fileName}' রিসিভ হয়েছে!")
                speakMessage("জি বস, হাতের ইশারায় ফাইলটি এই ডিভাইসে চলে এসেছে।")
            } catch (e: Exception) {
                _isGestureBusy.value = false
                _gestureStatusMessage.value = "রিসিভ ত্রুটি: ${e.localizedMessage ?: "আবার চেষ্টা করুন"}"
            }
        }
    }

    private fun refreshReceivedGestureFiles() {
        val dir = File(appContext.filesDir, "docs")
        val files = dir.listFiles()?.filter { it.isFile && it.name.startsWith("Gesture_") }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()
        _receivedTransferFiles.value = files
    }

    // --- Birthday Reminders & Automatic Birthday Wishes ---

    fun saveBirthdayReminder(
        personName: String,
        birthDay: Int,
        birthMonth: Int,
        phoneOrContact: String,
        wishMessage: String
    ) {
        val cleanName = personName.trim()
        if (cleanName.isBlank()) {
            HeyShuvoVoiceCoordinator.postBanner("যার জন্মদিন তার নাম লিখুন।")
            return
        }
        val day = birthDay.coerceIn(1, 31)
        val month = birthMonth.coerceIn(1, 12)
        val cleanWish = wishMessage.trim().ifBlank {
            "শুভ জন্মদিন $cleanName! আপনার আগামী দিনগুলো সুস্থ, সুন্দর ও আনন্দময় হোক।"
        }
        val id = "bday_${UUID.randomUUID().toString().replace("-", "").take(12)}"
        val entity = BirthdayReminderEntity(
            id = id,
            ownerUid = localOwnerKey,
            personName = cleanName,
            birthDay = day,
            birthMonth = month,
            phoneOrContact = phoneOrContact.trim(),
            wishMessage = cleanWish,
            lastWishedYear = 0
        )
        viewModelScope.launch {
            privateVaultRepository.upsertBirthday(entity)
            if (authenticatedCloudUid != null) {
                governanceRepository.saveUserBirthday(
                    birthdayId = id,
                    personName = cleanName,
                    birthDay = day,
                    birthMonth = month,
                    phoneOrContact = phoneOrContact.trim(),
                    wishMessage = cleanWish,
                    lastWishedYear = 0
                )
            }
            HeyShuvoVoiceCoordinator.postBanner("🎂 $cleanName-এর জন্মদিন ($day/$month) সেভ হয়েছে!")
        }
    }

    fun deleteBirthdayReminder(id: String) {
        viewModelScope.launch {
            privateVaultRepository.deleteBirthday(id, localOwnerKey)
            if (authenticatedCloudUid != null) {
                runCatching { governanceRepository.deleteUserBirthday(id) }
            }
            HeyShuvoVoiceCoordinator.postBanner("জন্মদিনের এন্ট্রি মুছে ফেলা হয়েছে।")
        }
    }

    fun sendBirthdayWishNow(entry: BirthdayReminderEntity) {
        val yearNow = Calendar.getInstance().get(Calendar.YEAR)
        deviceController.executeAction(
            ExecutableActionPayload(
                kind = DeviceActionKind.SEND_BIRTHDAY_WISH,
                targetOrPath = entry.phoneOrContact,
                titleOrSubject = entry.personName,
                contentOrBody = entry.wishMessage
            )
        )
        viewModelScope.launch {
            privateVaultRepository.markBirthdayWished(entry.id, localOwnerKey, yearNow)
            if (authenticatedCloudUid != null) {
                runCatching { governanceRepository.markCloudBirthdayWished(entry.id, yearNow) }
            }
        }
    }

    fun generateAiBirthdayWishText(personName: String, relationHint: String, onGenerated: (String) -> Unit) {
        val targetName = personName.trim().ifBlank { "প্রিয় মানুষ" }
        _isSmartWriterBusy.value = true
        viewModelScope.launch {
            val res = aiService.translateOrDraftSmartText(
                inputText = "নাম: $targetName, সম্পর্ক: ${relationHint.ifBlank { "প্রিয়জন" }}",
                targetStyle = "Write a warm, heartfelt, concise Bangla birthday wish message (2-3 sentences) suitable for WhatsApp/SMS."
            )
            _isSmartWriterBusy.value = false
            res.onSuccess { text ->
                onGenerated(text.trim())
            }.onFailure {
                onGenerated("শুভ জন্মদিন $targetName! আপনার জীবনের প্রতিটি মুহূর্ত আনন্দ, সুস্বাস্থ্য ও সাফল্যে ভরে উঠুক।")
            }
        }
    }

    private fun checkAndTriggerTodayBirthdays(list: List<BirthdayReminderEntity>) {
        if (didAutoBirthdayCheckThisSession || list.isEmpty()) return
        val cal = Calendar.getInstance()
        val todayDay = cal.get(Calendar.DAY_OF_MONTH)
        val todayMonth = cal.get(Calendar.MONTH) + 1
        val currentYear = cal.get(Calendar.YEAR)

        val dueToday = list.filter {
            it.birthDay == todayDay && it.birthMonth == todayMonth && it.lastWishedYear < currentYear
        }
        if (dueToday.isEmpty()) return
        didAutoBirthdayCheckThisSession = true

        val names = dueToday.joinToString(", ") { it.personName }
        val banner = "🎂 আজ $names-এর জন্মদিন! স্মার্ট হাব থেকে এক ট্যাপে বার্থডে উইশ পাঠান।"
        HeyShuvoVoiceCoordinator.postBanner(banner)
        speakMessage("বস, আজ $names এর জন্মদিন। বার্থডে উইশ প্রস্তুত আছে।")
    }

    // --- Master App 7-Type Automatic Email Automation Engine ---

    fun saveMasterAutoEmailConfig(senderEmail: String, enabledFlags: String) {
        viewModelScope.launch {
            preferencesRepository.saveAutoEmailAutomationConfig(senderEmail, enabledFlags)
            if (authenticatedCloudUid != null) {
                governanceRepository.recordAuditLog(
                    authenticatedCloudUid,
                    "MASTER_AUTO_EMAIL_SAVED",
                    "Sender=${senderEmail.ifBlank { "shuvoskp3@gmail.com" }}, Flags=$enabledFlags"
                )
            }
            HeyShuvoVoiceCoordinator.postBanner("✓ মাস্টার অটো-ইমেইল সেটিংস সেভ ও সক্রিয় হয়েছে!")
        }
    }

    fun evaluateAutomaticCustomerEmails(manualTrigger: Boolean = false) {
        viewModelScope.launch {
            val s = preferencesRepository.settingsFlow.first()
            val flags = s.autoEmailFlags.uppercase()
            val sender = s.masterSenderEmail.ifBlank { "shuvoskp3@gmail.com" }
            val prefs = appContext.getSharedPreferences("hey_shuvo_auto_email_prefs", Context.MODE_PRIVATE)
            val cal = Calendar.getInstance()
            val day = cal.get(Calendar.DAY_OF_MONTH)
            val month = cal.get(Calendar.MONTH) + 1
            val year = cal.get(Calendar.YEAR)
            val todayKey = "$year-$month-$day"
            val triggeredList = mutableListOf<String>()

            // 1. স্বাগত রিমাইন্ডার (নতুন গ্রাহকের জন্য)
            if ("WELCOME" in flags && !prefs.getBoolean("sent_welcome_$localOwnerKey", false)) {
                prefs.edit().putBoolean("sent_welcome_$localOwnerKey", true).apply()
                triggeredList.add("স্বাগত বার্তা (Welcome)")
            }

            // 2. নতুন ডিভাইসে লগইন হলে নিরাপত্তা রিমাইন্ডার
            val deviceSig = "${android.os.Build.MANUFACTURER}_${android.os.Build.MODEL}"
            val lastDevice = prefs.getString("last_device_$localOwnerKey", null)
            if ("SECURITY_LOGIN" in flags && lastDevice != null && lastDevice != deviceSig) {
                triggeredList.add("নিরাপত্তা বার্তা (নতুন ডিভাইস: $deviceSig)")
            }
            prefs.edit().putString("last_device_$localOwnerKey", deviceSig).apply()

            // 3. ১ সপ্তাহ না এলে উৎসাহের রিমাইন্ডার
            val nowMs = System.currentTimeMillis()
            val lastActiveMs = prefs.getLong("last_active_ms_$localOwnerKey", nowMs)
            val sevenDaysMs = 7L * 24L * 60L * 60L * 1000L
            if ("INACTIVITY_7D" in flags && (nowMs - lastActiveMs) >= sevenDaysMs) {
                triggeredList.add("১ সপ্তাহ পর উৎসাহের বার্তা")
            }
            prefs.edit().putLong("last_active_ms_$localOwnerKey", nowMs).apply()

            // 4. জন্মদিনের শুভেচ্ছা রিমাইন্ডার
            if ("BIRTHDAY" in flags && prefs.getString("last_bday_email_$localOwnerKey", "") != todayKey) {
                val dueToday = birthdays.value.filter { it.birthDay == day && it.birthMonth == month }
                if (dueToday.isNotEmpty()) {
                    prefs.edit().putString("last_bday_email_$localOwnerKey", todayKey).apply()
                    triggeredList.add("জন্মদিনের শুভেচ্ছা (${dueToday.joinToString { it.personName }})")
                }
            }

            // 5. পহেলা বৈশাখ ও বিজয় দিবসের শুভেচ্ছা
            if ("FESTIVAL" in flags && prefs.getString("last_fest_email_$localOwnerKey", "") != todayKey) {
                val festivalName = when {
                    day == 14 && month == 4 -> "পহেলা বৈশাখ"
                    day == 16 && month == 12 -> "বিজয় দিবস"
                    else -> null
                }
                if (festivalName != null) {
                    prefs.edit().putString("last_fest_email_$localOwnerKey", todayKey).apply()
                    triggeredList.add("$festivalName শুভেচ্ছা")
                }
            }

            // 6. নতুন ফিচার এলে জানানো
            val lastNotifiedVer = prefs.getLong("last_feature_ver_$localOwnerKey", 1L)
            if ("NEW_FEATURE" in flags && s.masterConfigVersion > lastNotifiedVer) {
                prefs.edit().putLong("last_feature_ver_$localOwnerKey", s.masterConfigVersion).apply()
                triggeredList.add("নতুন ফিচার আপডেট (v${s.masterConfigVersion})")
            }

            // 7. মাসের শেষে সারাংশ
            val maxDayOfMonth = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
            val monthKey = "$year-$month"
            if ("MONTHLY_SUMMARY" in flags && day >= maxDayOfMonth - 1 &&
                prefs.getString("last_monthly_email_$localOwnerKey", "") != monthKey
            ) {
                prefs.edit().putString("last_monthly_email_$localOwnerKey", monthKey).apply()
                triggeredList.add("মাসিক কাজের সারাংশ (${taskHistory.value.size}টি কাজ)")
            }

            if (triggeredList.isNotEmpty()) {
                val summaryText = triggeredList.joinToString(", ")
                val summary = "অটো-ইমেইল ড্রাফট প্রস্তুত ($sender): $summaryText"
                if (authenticatedCloudUid != null) {
                    governanceRepository.recordAuditLog(authenticatedCloudUid, "AUTO_EMAIL_DRAFTED", summary)
                }
                HeyShuvoVoiceCoordinator.postBanner("✉️ $summary")
                if (manualTrigger) {
                    deviceController.executeAction(
                        ExecutableActionPayload(
                            kind = DeviceActionKind.SEND_EMAIL_DRAFT,
                            targetOrPath = sender,
                            titleOrSubject = "Hey Shuvo অটো-ইমেইল সারাংশ",
                            contentOrBody = "প্রস্তুতকৃত ইমেইল বিষয়সমূহ:\n- ${triggeredList.joinToString("\n- ")}"
                        )
                    )
                }
            } else if (manualTrigger) {
                HeyShuvoVoiceCoordinator.postBanner("✓ সব ৭টি অটো-ইমেইল নিয়ম সক্রিয় আছে (প্রেরক: $sender)। আজ নতুন কোনো ট্রিগার বাকি নেই।")
            }
        }
    }

    private fun compressImageBytesForTransfer(rawBytes: ByteArray, lowMbMode: Boolean): ByteArray {
        return try {
            val decoded = android.graphics.BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size)
                ?: return rawBytes
            val maxDim = if (lowMbMode) 720 else 1440
            val quality = if (lowMbMode) 62 else 85
            val scale = minOf(1f, maxDim.toFloat() / maxOf(decoded.width, decoded.height).coerceAtLeast(1))
            val targetBmp = if (scale < 1f) {
                android.graphics.Bitmap.createScaledBitmap(
                    decoded,
                    (decoded.width * scale).toInt().coerceAtLeast(1),
                    (decoded.height * scale).toInt().coerceAtLeast(1),
                    true
                )
            } else {
                decoded
            }
            val out = java.io.ByteArrayOutputStream()
            targetBmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, quality, out)
            out.toByteArray()
        } catch (_: Exception) {
            rawBytes
        }
    }

    // --- Permission-Based All-Day Voice Diary & Scheduled End-of-Day Summary ---

    private fun loadAllDayDiaryPrefs() {
        val prefs = appContext.getSharedPreferences("hey_shuvo_allday_diary", Context.MODE_PRIVATE)
        _allDayRecordingEnabled.value = prefs.getBoolean("enabled_$localOwnerKey", false)
        _allDaySummaryHour.value = prefs.getInt("hour_$localOwnerKey", 21)
        _allDaySummaryMinute.value = prefs.getInt("minute_$localOwnerKey", 0)
        _allDaySummaryLanguage.value = prefs.getString("lang_$localOwnerKey", "BN") ?: "BN"
        _allDaySummaryEmail.value = prefs.getString("email_$localOwnerKey", "") ?: ""
        val rawLogs = prefs.getString("logs_$localOwnerKey", "") ?: ""
        _allDayLogs.value = rawLogs.lines().filter { it.isNotBlank() }
    }

    fun toggleAllDayRecording(enabled: Boolean) {
        if (enabled && !hasMicPermission()) {
            HeyShuvoVoiceCoordinator.postBanner("সারাদিনের ভয়েস ডায়েরির জন্য মাইক্রোফোন পারমিশন দিন।")
            return
        }
        _allDayRecordingEnabled.value = enabled
        appContext.getSharedPreferences("hey_shuvo_allday_diary", Context.MODE_PRIVATE)
            .edit().putBoolean("enabled_$localOwnerKey", enabled).apply()
        if (enabled) {
            val noiseDb = (voiceState.value.rmsDb.toInt().takeIf { it > 0 } ?: 34).coerceIn(22, 75)
            _allDayNoiseDb.value = noiseDb
            com.example.core.service.DailySummaryAlarmReceiver.scheduleDailySummaryAlarm(
                context = appContext,
                hour = _allDaySummaryHour.value,
                minute = _allDaySummaryMinute.value,
                ownerKey = localOwnerKey
            )
            HeyShuvoVoiceCoordinator.postBanner("✓ ভয়েস ডায়েরি চালু হয়েছে (সক্রিয় কথোপকথন লগ হবে • নয়েজ ফ্লোর: ${noiseDb} dB)।")
        } else {
            com.example.core.service.DailySummaryAlarmReceiver.cancelDailySummaryAlarm(
                context = appContext,
                ownerKey = localOwnerKey
            )
            HeyShuvoVoiceCoordinator.postBanner("সারাদিনের ভয়েস ডায়েরি ও নির্ধারিত অ্যালার্ম বন্ধ করা হয়েছে।")
        }
    }

    fun saveAllDaySummarySchedule(hour: Int, minute: Int, language: String, email: String) {
        val h = hour.coerceIn(0, 23)
        val m = minute.coerceIn(0, 59)
        val lang = if (language.uppercase() == "EN") "EN" else "BN"
        val cleanEmail = email.trim()
        _allDaySummaryHour.value = h
        _allDaySummaryMinute.value = m
        _allDaySummaryLanguage.value = lang
        _allDaySummaryEmail.value = cleanEmail
        appContext.getSharedPreferences("hey_shuvo_allday_diary", Context.MODE_PRIVATE)
            .edit()
            .putInt("hour_$localOwnerKey", h)
            .putInt("minute_$localOwnerKey", m)
            .putString("lang_$localOwnerKey", lang)
            .putString("email_$localOwnerKey", cleanEmail)
            .apply()
        com.example.core.service.DailySummaryAlarmReceiver.scheduleDailySummaryAlarm(
            context = appContext,
            hour = h,
            minute = m,
            ownerKey = localOwnerKey
        )
        HeyShuvoVoiceCoordinator.postBanner("✓ সামারি সময় (${String.format("%02d:%02d", h, m)}) ও ভাষা ($lang) সেভ হয়েছে!")
    }

    fun saveMasterPricingConfig(monthlyFeeBdt: Long, freeLimit: Long) {
        viewModelScope.launch {
            preferencesRepository.saveMasterPricingConfig(monthlyFeeBdt, freeLimit)
            val cur = settings.value
            if (_isOwnerAdmin.value && authenticatedCloudUid != null) {
                governanceRepository.publishCustomerRemoteConfig(
                    announcementBanner = cur.masterAnnouncementBanner,
                    forcedThemePalette = cur.themePalette.name,
                    defaultAutoVoice = cur.autoAllDisplayVoice,
                    customSystemPrompt = cur.masterCustomSystemPrompt,
                    enabledFeatureFlags = cur.masterEnabledFeatureFlags,
                    lastAppliedFixSummary = "Pricing updated: ৳$monthlyFeeBdt/month, $freeLimit free/day",
                    monthlyPaidFeeBdt = monthlyFeeBdt,
                    freeDailyLimit = freeLimit,
                    globalPaidModeEnabled = cur.globalPaidModeEnabled
                )
            }
            HeyShuvoVoiceCoordinator.postBanner("✓ মাসিক পেইড ফি (৳$monthlyFeeBdt) ও ফ্রি লিমিট ($freeLimit) আপডেট হয়েছে!")
        }
    }

    fun setGlobalPaidModeEnabled(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setGlobalPaidModeEnabled(enabled)
            val cur = settings.value
            if (_isOwnerAdmin.value && authenticatedCloudUid != null) {
                governanceRepository.publishCustomerRemoteConfig(
                    announcementBanner = cur.masterAnnouncementBanner,
                    forcedThemePalette = cur.themePalette.name,
                    defaultAutoVoice = cur.autoAllDisplayVoice,
                    customSystemPrompt = cur.masterCustomSystemPrompt,
                    enabledFeatureFlags = cur.masterEnabledFeatureFlags,
                    lastAppliedFixSummary = if (enabled) "Global Paid Mode Enabled" else "Global Unlimited Free Mode Enabled",
                    monthlyPaidFeeBdt = cur.monthlyPaidFeeBdt,
                    freeDailyLimit = cur.freeDailyLimit,
                    globalPaidModeEnabled = enabled
                )
            }
            val msg = if (enabled) {
                "✓ গ্লোবাল পেইড সিস্টেম চালু হয়েছে (ফ্রি লিমিট + পেইড আনলিমিটেড)।"
            } else {
                "✓ গ্লোবাল ফ্রি মোড চালু হয়েছে (সবাই আনলিমিটেড ফ্রি পাবে)।"
            }
            HeyShuvoVoiceCoordinator.postBanner(msg)
        }
    }

    fun setCurrentUserPaidStatus(isPaid: Boolean, validityDays: Int = 30) {
        viewModelScope.launch {
            preferencesRepository.setCurrentUserPaidStatus(isPaid, validityDays)
            if (_isOwnerAdmin.value && authenticatedCloudUid != null) {
                governanceRepository.updateAccountSubscriptionTier(
                    targetUserId = authenticatedCloudUid,
                    tier = if (isPaid) "paid" else "free"
                )
            }
            val msg = if (isPaid) {
                val daysLabel = if (validityDays <= 0) "লাইফটাইম" else "$validityDays দিন"
                "✓ ইউজারকে PAID ($daysLabel আনলিমিটেড) করা হয়েছে!"
            } else {
                "✓ ইউজারকে FREE (লিমিটেড) প্ল্যানে নেওয়া হয়েছে।"
            }
            HeyShuvoVoiceCoordinator.postBanner(msg)
        }
    }

    fun grantBonusFreeUses(bonusCount: Long = 10L) {
        viewModelScope.launch {
            preferencesRepository.grantBonusFreeUses(bonusCount)
            HeyShuvoVoiceCoordinator.postBanner("✓ ইউজারকে অতিরিক্ত +$bonusCount টি ফ্রি ব্যবহার বোনাস দেওয়া হয়েছে!")
        }
    }

    fun updateMasterPassword(oldPass: String, newPass: String, onResult: (Boolean) -> Unit) {
        if (newPass.length < 6) {
            HeyShuvoVoiceCoordinator.postBanner("❌ নতুন পাসওয়ার্ড কমপক্ষে ৬ অক্ষরের হতে হবে!")
            onResult(false)
            return
        }
        viewModelScope.launch {
            val success = preferencesRepository.updateMasterPassword(oldPass, newPass)
            if (success) {
                HeyShuvoVoiceCoordinator.postBanner("✓ মাস্টার পাসওয়ার্ড সফলভাবে আপডেট হয়েছে!")
            } else {
                HeyShuvoVoiceCoordinator.postBanner("❌ পূর্ববর্তী পাসওয়ার্ডটি সঠিক নয়!")
            }
            onResult(success)
        }
    }

    private fun appendAllDayVoiceEntry(spokenText: String) {
        val cal = Calendar.getInstance()
        val timeStr = String.format("%02d:%02d", cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
        val noiseDb = (voiceState.value.rmsDb.toInt().takeIf { it > 0 } ?: _allDayNoiseDb.value).coerceIn(22, 75)
        _allDayNoiseDb.value = noiseDb
        val entry = "[$timeStr | Noise: ${noiseDb}dB] $spokenText"
        val updated = (_allDayLogs.value + entry).takeLast(200)
        _allDayLogs.value = updated
        appContext.getSharedPreferences("hey_shuvo_allday_diary", Context.MODE_PRIVATE)
            .edit().putString("logs_$localOwnerKey", updated.joinToString("\n")).apply()
    }

    fun generateAndSendDailySummaryNow() {
        _isDailySummaryBusy.value = true
        viewModelScope.launch {
            val lang = _allDaySummaryLanguage.value
            val logs = _allDayLogs.value
            val tasks = taskHistory.value.take(15).map { it.commandText }
            val combined = (logs + tasks).distinct().joinToString("\n")
            val promptStyle = if (lang == "EN") {
                "Summarize today's voice logs, meetings, and tasks in clear English. Separate into: 1) Important & Urgent, 2) Meetings & Conversations, 3) Routine Tasks."
            } else {
                "আজকের সারাদিনের কথা, মিটিং ও কাজের লগ থেকে একটি সুন্দর বাংলা সারাংশ তৈরি করুন। আলাদা করুন: ১) গুরুত্বপূর্ণ কাজ, ২) মিটিং ও কথোপকথন, ৩) সাধারণ কাজ।"
            }
            val input = combined.ifBlank {
                if (lang == "EN") "No voice logs recorded today yet." else "আজ এখনো কোনো ভয়েস লগ রেকর্ড হয়নি।"
            }
            val res = aiService.translateOrDraftSmartText(input, promptStyle)
            _isDailySummaryBusy.value = false
            val summaryText = res.getOrElse {
                if (lang == "EN") {
                    "Daily Summary (${logs.size} voice logs, ${tasks.size} tasks):\n$input"
                } else {
                    "আজকের সারাংশ (${logs.size}টি ভয়েস লগ, ${tasks.size}টি কাজ):\n$input"
                }
            }
            _dailySummaryOutput.value = summaryText

            val targetEmail = _allDaySummaryEmail.value
            if (targetEmail.contains("@")) {
                deviceController.executeAction(
                    ExecutableActionPayload(
                        kind = DeviceActionKind.SEND_EMAIL_DRAFT,
                        targetOrPath = targetEmail,
                        titleOrSubject = if (lang == "EN") "Hey Shuvo - Daily Voice & Work Summary" else "Hey Shuvo - সারাদিনের কাজ ও মিটিংয়ের সারাংশ",
                        contentOrBody = summaryText
                    )
                )
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        pairingObserverJob?.cancel()
        if (!settings.value.autoAllDisplayVoice) {
            voiceEngine.stopListening(submitRecordedAudio = false)
            voiceEngine.stopSpeaking()
        }
    }
}
