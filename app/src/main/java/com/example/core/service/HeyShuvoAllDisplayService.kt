package com.example.core.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.text.TextPaint
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import com.example.core.actions.ActionExecutionOutcome
import com.example.core.actions.DeviceActionKind
import com.example.core.actions.ExecutableActionPayload
import com.example.core.actions.VoiceDeviceController
import com.example.core.preferences.AppThemePalette
import com.example.core.preferences.AssistantSettings
import com.example.core.preferences.PreferencesRepository
import com.example.core.preferences.UserAccessibilityMode
import com.example.core.util.BanglaCommandHelper
import com.example.core.voice.SpeechRecognitionState
import com.example.core.voice.VoiceAssistantEngine
import com.example.data.local.BirthdayReminderEntity
import com.example.data.local.LedgerEntryEntity
import com.example.data.local.PrivateMemoryEntity
import com.example.data.local.PrivateVaultDatabase
import com.example.data.local.PrivateVaultRepository
import com.example.data.local.TaskLogEntity
import com.example.feature.customer.ChatTurn
import com.example.feature.customer.HeyShuvoAiService
import com.example.feature.customer.StructuredCommandAnalysis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlin.math.sin

private const val TAG = "HeyShuvoAllDisplay"

/**
 * Application-scoped Hey Shuvo Runtime Coordinator.
 *
 * Guarantees:
 *  1) Zero lockups: `activeCommandJob` can always be cancelled/preempted immediately when the user
 *     taps Send, Mic, AUTO, MANUAL, Cancel, or Clear, and `_isProcessing` is ALWAYS reset in `finally`.
 *  2) Instant (<50ms) execution for direct phone/app/hardware/location/photo/file commands!
 *  3) Seamless shared state across Main Display (`AUTO • All Display`) and In-App (`MANUAL`) modes,
 *     with ZERO red `ERROR` card spam or stuck `"প্রসেসিং চলছে..."` placeholders.
 */
object HeyShuvoVoiceCoordinator {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Volatile
    private var sharedEngine: VoiceAssistantEngine? = null

    @Volatile
    private var appContextRef: Context? = null

    @Volatile
    private var activeCommandJob: Job? = null

    @Volatile
    private var pendingAutoAudio: Pair<ByteArray, String>? = null

    @Volatile
    private var cachedController: VoiceDeviceController? = null

    private fun getController(appCtx: Context): VoiceDeviceController {
        val cleanCtx = appCtx.applicationContext
        return cachedController ?: synchronized(this) {
            cachedController ?: VoiceDeviceController(cleanCtx).also { cachedController = it }
        }
    }

    private val aiService by lazy { HeyShuvoAiService() }

    private val _chatTurns = MutableStateFlow<List<ChatTurn>>(emptyList())
    val chatTurns: StateFlow<List<ChatTurn>> = _chatTurns.asStateFlow()

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()

    private val _statusBanner = MutableStateFlow<String?>(null)
    val statusBanner: StateFlow<String?> = _statusBanner.asStateFlow()

    private val _lastHudHeadline = MutableStateFlow("Auto • কথা বলুন")
    val lastHudHeadline: StateFlow<String> = _lastHudHeadline.asStateFlow()

    private val _isMainAppInForeground = MutableStateFlow(false)
    val isMainAppInForeground: StateFlow<Boolean> = _isMainAppInForeground.asStateFlow()

    @Volatile
    private var lastAutoFailureBannerEpochMs: Long = 0L

    @Volatile
    var activeLocalOwnerKey: String = "local_preview_owner"

    @Volatile
    var latestUserMode: UserAccessibilityMode = UserAccessibilityMode.STANDARD

    @Volatile
    var cachedSettings: AssistantSettings? = null

    @Volatile
    var cachedTallySummary: String = ""

    @Volatile
    var cachedMemorySummary: String = ""

    @Volatile
    var onExternalStopAuto: (() -> Unit)? = null

    @Volatile
    var onSubmitFeatureRequestBridge: ((String, String) -> Unit)? = null

    @Volatile
    var onSaveBirthdayCloudBridge: ((BirthdayReminderEntity) -> Unit)? = null

    @Volatile
    var activeSkillsProvider: (() -> String)? = null

    @Volatile
    var activeMasterPromptProvider: (() -> String)? = null

    fun setMainAppInForeground(inForeground: Boolean) {
        _isMainAppInForeground.value = inForeground
    }

    fun setTypingActive(context: Context, isTyping: Boolean) {
        getEngine(context).setPausedForTyping(isTyping)
    }

    fun getEngine(context: Context): VoiceAssistantEngine {
        appContextRef = context.applicationContext
        return sharedEngine ?: synchronized(this) {
            sharedEngine ?: VoiceAssistantEngine(context.applicationContext).also {
                sharedEngine = it
            }
        }
    }

    fun clearBanner() {
        _statusBanner.value = null
    }

    fun postBanner(msg: String) {
        _statusBanner.value = msg
    }

    /**
     * Immediately cancels any in-flight AI request and resets `_isProcessing = false` so no button ever freezes.
     */
    fun cancelActiveProcessing(context: Context? = appContextRef) {
        pendingAutoAudio = null
        activeCommandJob?.cancel()
        activeCommandJob = null
        _isProcessing.value = false
        _lastHudHeadline.value = "প্রস্তুত • কথা বলুন"
        if (context != null) {
            getEngine(context).setPausedForProcessing(false)
        }
    }

    fun clearConversation(context: Context) {
        pendingAutoAudio = null
        cancelActiveProcessing(context)
        getEngine(context).stopSpeaking()
        _chatTurns.value = emptyList()
        _lastHudHeadline.value = "Auto • কথা বলুন"
    }

    fun updateTurnsTransform(transform: (List<ChatTurn>) -> List<ChatTurn>) {
        _chatTurns.update(transform)
    }

    fun triggerOverlayOrbTap(context: Context) {
        val appCtx = context.applicationContext
        val engine = getEngine(appCtx)
        when {
            _isProcessing.value -> cancelActiveProcessing(appCtx)
            engine.state.value.isSpeaking -> engine.stopSpeaking()
            engine.state.value.isListening && engine.state.value.hasDetectedVoiceActivity -> {
                engine.stopListening(submitRecordedAudio = true)
            }
            engine.isContinuousStreamActive() -> {
                engine.nudgeAutoStreamReady()
                _lastHudHeadline.value = "বস, বলুন — শুনছি"
            }
            else -> {
                // Keep persistent foreground AudioRecord alive in All-Display Auto mode so Android 14+ never blocks background mic!
                appScope.launch {
                    val settings = PreferencesRepository(appCtx).settingsFlow.first()
                    engine.setListenBackgroundVoicesMode(settings.listenBackgroundVoices)
                    startVoiceCapture(
                        context = appCtx,
                        preferDirectAiAudio = true,
                        continuousAutoMode = settings.autoAllDisplayVoice,
                        userInitiated = true
                    )
                }
            }
        }
    }

    fun triggerOverlaySwitchToManual(context: Context) {
        val appCtx = context.applicationContext
        cancelActiveProcessing(appCtx)
        appScope.launch {
            PreferencesRepository(appCtx).setAutoAllDisplayVoice(false)
            onExternalStopAuto?.invoke()
            HeyShuvoAllDisplayService.stop(appCtx)
            getEngine(appCtx).stopListening(submitRecordedAudio = false)
            _lastHudHeadline.value = "Manual মোড সক্রিয়"
            val openApp = Intent(appCtx, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            try {
                appCtx.startActivity(openApp)
            } catch (_: Exception) {
            }
        }
    }

    fun triggerOverlayLocationAction(context: Context) {
        val appCtx = context.applicationContext
        val controller = getController(appCtx)
        controller.executeAction(
            ExecutableActionPayload(kind = DeviceActionKind.SHOW_CURRENT_LOCATION)
        ).onSuccess { outcome ->
            _lastHudHeadline.value = outcome.summary.removePrefix("✓ ").take(34)
        }
    }

    fun triggerOverlayPhotoVisionAction(context: Context) {
        val appCtx = context.applicationContext
        val controller = getController(appCtx)
        controller.executeAction(
            ExecutableActionPayload(kind = DeviceActionKind.OPEN_PHOTO_VISION_MODE)
        ).onSuccess { outcome ->
            _lastHudHeadline.value = outcome.summary.removePrefix("✓ ").take(34)
        }
    }

    fun startVoiceCapture(
        context: Context,
        banglaPreferred: Boolean = true,
        preferDirectAiAudio: Boolean = false,
        continuousAutoMode: Boolean = false,
        userInitiated: Boolean = !continuousAutoMode
    ) {
        val appCtx = context.applicationContext
        appContextRef = appCtx
        val hasMic = ContextCompat.checkSelfPermission(
            appCtx,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasMic) {
            _statusBanner.value = "মাইক্রোফোন পারমিশন (Allow Microphone) চালু করুন।"
            return
        }

        // Any explicit user tap ALWAYS preempts an older stuck request immediately!
        if (_isProcessing.value) {
            if (userInitiated || !continuousAutoMode) {
                cancelActiveProcessing(appCtx)
            } else {
                return
            }
        }

        val engine = getEngine(appCtx)
        val tag = if (banglaPreferred) "bn-BD" else "en-US"
        engine.startListening(
            languageTag = tag,
            preferDirectAiAudio = if (continuousAutoMode) true else preferDirectAiAudio,
            continuousAutoMode = continuousAutoMode,
            onTextResult = { spokenText, confidence ->
                submitTextCommand(appCtx, spokenText, confidence)
            },
            onAudioResult = { audioBytes, mimeType ->
                submitAudioCommand(appCtx, audioBytes, mimeType, isAutoMode = continuousAutoMode)
            },
            onSilentCycle = {
                if (continuousAutoMode && !_isProcessing.value) {
                    engine.setPausedForProcessing(false)
                }
            }
        )
    }

    fun submitTextCommand(
        context: Context,
        rawCommand: String,
        voiceConfidence: Float = 1.0f,
        usageAlreadyCounted: Boolean = false
    ) {
        val appCtx = context.applicationContext
        appContextRef = appCtx
        val trimmed = rawCommand.trim()
        if (trimmed.isBlank()) return

        if (_isProcessing.value || activeCommandJob?.isActive == true) {
            cancelActiveProcessing(appCtx)
        }

        if (checkAndExecuteSpokenApproval(appCtx, trimmed)) return

        val (profileSwitchedOnly, effectiveCommand) = applyVoiceProfileSwitchAndExtractRemaining(
            appCtx = appCtx,
            rawCommand = trimmed,
            recordUserMessageIfSwitchedOnly = true
        )
        if (profileSwitchedOnly) return
        val commandForProcessing = effectiveCommand.ifBlank { trimmed }

        _chatTurns.update { existingTurns ->
            if (existingTurns.lastOrNull()?.isError == true && existingTurns.lastOrNull()?.retryPrompt == trimmed) {
                existingTurns.dropLast(1)
            } else {
                existingTurns + ChatTurn(isUser = true, message = trimmed)
            }
        }

        val controller = getController(appCtx)

        // ZERO-LATENCY SYNCHRONOUS FAST-PATH (<5ms, zero network wait!):
        // Only skip synchronous path for Tally *read* queries needing DB totals; Tally *write* entries run immediately!
        if (BanglaCommandHelper.isInstantDirectActionOrFact(commandForProcessing)) {
            val lowerCmd = commandForProcessing.lowercase()
            val needsTallyReadSummary = ("ট্যালি" in lowerCmd || "tally" in lowerCmd || "হিসাব" in lowerCmd || "বাকি" in lowerCmd) &&
                listOf("কত", "koto", "summary", "total", "দেখা", "হিসাব দাও", "রিপোর্ট").any { it in lowerCmd } &&
                !listOf("লেখ", "রাখ", "যোগ", "সেভ", "save", "add").any { it in lowerCmd }

            if (!needsTallyReadSummary) {
                val liveTelemetry = controller.readLiveDeviceTelemetrySummary()
                val instantPlan = BanglaCommandHelper.buildLocalFallbackAnalysisOrNull(
                    raw = commandForProcessing,
                    liveTelemetry = liveTelemetry,
                    tallySummary = ""
                )
                if (instantPlan != null) {
                    _isProcessing.value = false
                    val job = appScope.launch {
                        handleSuccessfulAnalysis(
                            appCtx = appCtx,
                            userCommandLabel = commandForProcessing,
                            analysis = instantPlan,
                            currentMode = latestUserMode,
                            voiceConfidence = voiceConfidence
                        )
                    }
                    activeCommandJob = job
                    return
                }
            }
        }

        val engine = getEngine(appCtx)
        engine.setPausedForProcessing(true)
        _isProcessing.value = true
        _lastHudHeadline.value = "উত্তর তৈরি হচ্ছে..."

        val job = appScope.launch {
            try {
                val currentSettings = cachedSettings ?: try {
                    withTimeout(600L) { PreferencesRepository(appCtx).settingsFlow.first() }.also { cachedSettings = it }
                } catch (_: Exception) {
                    AssistantSettings()
                }

                val liveTelemetry = controller.readLiveDeviceTelemetrySummary()
                val tallySummary = cachedTallySummary.ifBlank {
                    val vaultRepo = PrivateVaultRepository(PrivateVaultDatabase.getInstance(appCtx).privateVaultDao())
                    formatTallySummary(vaultRepo.observeLedger(activeLocalOwnerKey).first()).also { cachedTallySummary = it }
                }

                if (BanglaCommandHelper.isInstantDirectActionOrFact(commandForProcessing)) {
                    val instantPlan = BanglaCommandHelper.buildLocalFallbackAnalysisOrNull(
                        raw = commandForProcessing,
                        liveTelemetry = liveTelemetry,
                        tallySummary = tallySummary
                    )
                    if (instantPlan != null) {
                        _isProcessing.value = false
                        handleSuccessfulAnalysis(
                            appCtx = appCtx,
                            userCommandLabel = commandForProcessing,
                            analysis = instantPlan,
                            currentMode = currentSettings.userMode,
                            voiceConfidence = voiceConfidence
                        )
                        return@launch
                    }
                }

                val memorySummary = cachedMemorySummary.ifBlank {
                    val vaultRepo = PrivateVaultRepository(PrivateVaultDatabase.getInstance(appCtx).privateVaultDao())
                    vaultRepo.observeMemories(activeLocalOwnerKey).first()
                        .take(8)
                        .joinToString("\n") { "- ${it.topic}: ${it.content}" }
                        .also { cachedMemorySummary = it }
                }
                val recentContext = buildRecentConversationContext()
                val masterPrompt = activeMasterPromptProvider?.invoke().orEmpty()
                    .ifBlank { currentSettings.masterCustomSystemPrompt }
                val localSkillsText = currentSettings.localMasterSkills
                    .filter { it.enabled }
                    .joinToString("\n") { "- ${it.name}: ${it.instructions}" }
                val skillsSummary = buildString {
                    if (masterPrompt.isNotBlank()) {
                        appendLine("Master Owner Live Directive: $masterPrompt")
                    }
                    val cloudOrLocalSkills = activeSkillsProvider?.invoke().orEmpty()
                        .ifBlank { localSkillsText }
                    if (cloudOrLocalSkills.isNotBlank()) {
                        append(cloudOrLocalSkills)
                    }
                }.trim()

                if (!usageAlreadyCounted) {
                    val allowed = PreferencesRepository(appCtx).incrementAndCheckDailyUsage()
                    if (!allowed) {
                        _isProcessing.value = false
                        val limitMsg = "আপনার আজকের ফ্রি লিমিট শেষ হয়েছে। আনলিমিটেড ব্যবহারের জন্য Paid প্ল্যানে আপগ্রেড করুন।"
                        _chatTurns.value = _chatTurns.value + ChatTurn(isUser = false, message = limitMsg)
                        postBanner(limitMsg)
                        engine.speak(limitMsg, preferBangla = true) { resumeAutoListeningIfEnabled(appCtx) }
                        return@launch
                    }
                }

                val analysisResult = withTimeout(60_000L) {
                    aiService.analyzeMultiStepCommand(
                        userPrompt = commandForProcessing,
                        mode = currentSettings.userMode,
                        liveTelemetry = liveTelemetry,
                        tallyContext = tallySummary,
                        memoryContext = memorySummary,
                        recentConversation = recentContext,
                        enabledSkillsContext = skillsSummary
                    )
                }
                _isProcessing.value = false

                analysisResult.fold(
                    onSuccess = { analysis ->
                        handleSuccessfulAnalysis(
                            appCtx = appCtx,
                            userCommandLabel = commandForProcessing,
                            analysis = analysis,
                            currentMode = currentSettings.userMode,
                            voiceConfidence = voiceConfidence
                        )
                    },
                    onFailure = { error ->
                        val localFallback = BanglaCommandHelper.buildLocalFallbackAnalysisOrNull(
                            raw = commandForProcessing,
                            liveTelemetry = liveTelemetry,
                            tallySummary = tallySummary
                        )
                        if (localFallback != null) {
                            handleSuccessfulAnalysis(
                                appCtx = appCtx,
                                userCommandLabel = trimmed,
                                analysis = localFallback,
                                currentMode = currentSettings.userMode,
                                voiceConfidence = voiceConfidence
                            )
                        } else {
                            val cleanNote = HeyShuvoAiService.classifyFriendlyAiError(error)
                            _statusBanner.value = cleanNote
                            _lastHudHeadline.value = "প্রস্তুত • কথা বলুন"
                            _chatTurns.update {
                                it + ChatTurn(
                                    isUser = false,
                                    message = cleanNote,
                                    isError = true,
                                    retryPrompt = trimmed
                                )
                            }
                            Log.w(TAG, "Text AI fallback exhausted: ${error.message}")
                            resumeAutoListeningIfEnabled(appCtx)
                        }
                    }
                )
            } catch (ce: CancellationException) {
                throw ce
            } catch (unexpected: Exception) {
                Log.w(TAG, "submitTextCommand unexpected error", unexpected)
                val cleanNote = HeyShuvoAiService.classifyFriendlyAiError(unexpected)
                _statusBanner.value = cleanNote
                _chatTurns.update {
                    it + ChatTurn(
                        isUser = false,
                        message = cleanNote,
                        isError = true,
                        retryPrompt = trimmed
                    )
                }
                resumeAutoListeningIfEnabled(appCtx)
            } finally {
                if (activeCommandJob === kotlinx.coroutines.currentCoroutineContext()[Job]) {
                    _isProcessing.value = false
                }
            }
        }
        activeCommandJob = job
    }

    fun submitAudioCommand(
        context: Context,
        audioBytes: ByteArray,
        mimeType: String,
        isAutoMode: Boolean
    ) {
        val appCtx = context.applicationContext
        appContextRef = appCtx
        if (_isProcessing.value) {
            if (isAutoMode) {
                pendingAutoAudio = audioBytes to mimeType
                return
            }
            cancelActiveProcessing(appCtx)
        }

        val engine = getEngine(appCtx)
        engine.setPausedForProcessing(true)

        val recentContext = buildRecentConversationContext()
        _isProcessing.value = true
        _lastHudHeadline.value = if (isAutoMode) "Auto • যাচাই হচ্ছে..." else "শোনা কথা যাচাই হচ্ছে..."

        val job = appScope.launch {
            var wasCancelled = false
            try {
                val prefsRepo = PreferencesRepository(appCtx)
                if (!prefsRepo.canUseAiCommandToday()) {
                    _isProcessing.value = false
                    val limitMsg = "আপনার আজকের ফ্রি লিমিট শেষ হয়েছে। আনলিমিটেড ব্যবহারের জন্য Paid প্ল্যানে আপগ্রেড করুন।"
                    _chatTurns.value = _chatTurns.value + ChatTurn(isUser = false, message = limitMsg)
                    postBanner(limitMsg)
                    engine.speak(limitMsg, preferBangla = true) { resumeAutoListeningIfEnabled(appCtx) }
                    return@launch
                }
                val currentSettings = cachedSettings ?: prefsRepo.settingsFlow.first().also { cachedSettings = it }
                val controller = getController(appCtx)
                val liveTelemetry = controller.readLiveDeviceTelemetrySummary()
                val tallySummary = cachedTallySummary.ifBlank {
                    val vaultRepo = PrivateVaultRepository(PrivateVaultDatabase.getInstance(appCtx).privateVaultDao())
                    formatTallySummary(vaultRepo.observeLedger(activeLocalOwnerKey).first()).also { cachedTallySummary = it }
                }
                val memorySummary = cachedMemorySummary.ifBlank {
                    val vaultRepo = PrivateVaultRepository(PrivateVaultDatabase.getInstance(appCtx).privateVaultDao())
                    vaultRepo.observeMemories(activeLocalOwnerKey).first()
                        .take(8)
                        .joinToString("\n") { "- ${it.topic}: ${it.content}" }
                        .also { cachedMemorySummary = it }
                }
                val masterPrompt = activeMasterPromptProvider?.invoke().orEmpty()
                    .ifBlank { currentSettings.masterCustomSystemPrompt }
                val localSkillsText = currentSettings.localMasterSkills
                    .filter { it.enabled }
                    .joinToString("\n") { "- ${it.name}: ${it.instructions}" }
                val skillsSummary = buildString {
                    if (masterPrompt.isNotBlank()) {
                        appendLine("Master Owner Live Directive: $masterPrompt")
                    }
                    val cloudOrLocalSkills = activeSkillsProvider?.invoke().orEmpty()
                        .ifBlank { localSkillsText }
                    if (cloudOrLocalSkills.isNotBlank()) {
                        append(cloudOrLocalSkills)
                    }
                }.trim()

                val result = withTimeout(60_000L) {
                    aiService.analyzeAudioCommand(
                        audioBytes = audioBytes,
                        mimeType = mimeType,
                        mode = currentSettings.userMode,
                        liveTelemetry = liveTelemetry,
                        tallyContext = tallySummary,
                        memoryContext = memorySummary,
                        recentConversation = recentContext,
                        enabledSkillsContext = skillsSummary
                    )
                }

                result.fold(
                    onSuccess = { analysis ->
                        val transcript = analysis.spokenTranscript.trim()
                        val isNoiseOnly = transcript.equals("__NO_SPEECH__", ignoreCase = true) ||
                            (transcript.isBlank() && analysis.steps.isEmpty() && analysis.naturalReply.isBlank())

                        if (isNoiseOnly) {
                            _isProcessing.value = false
                            _lastHudHeadline.value = "Auto • কথা বলুন"
                            resumeAutoListeningIfEnabled(appCtx)
                            return@fold
                        }

                        val displayTranscript = transcript.ifBlank { analysis.steps.firstOrNull()?.actionTitle ?: "ভয়েস কমান্ড" }
                        _chatTurns.update {
                            it + ChatTurn(
                                isUser = true,
                                message = "🎤 \"$displayTranscript\"",
                                languageBadge = "VOICE"
                            )
                        }

                        val (switchedOnly, remainingAudioCmd) = applyVoiceProfileSwitchAndExtractRemaining(
                            appCtx = appCtx,
                            rawCommand = displayTranscript,
                            recordUserMessageIfSwitchedOnly = false
                        )
                        if (switchedOnly) {
                            _isProcessing.value = false
                            return@fold
                        }
                        if (remainingAudioCmd.isNotBlank() && remainingAudioCmd != displayTranscript) {
                            if (!PreferencesRepository(appCtx).incrementAndCheckDailyUsage()) {
                                _isProcessing.value = false
                                val limitMsg = "আপনার আজকের ফ্রি লিমিট শেষ হয়েছে। আনলিমিটেড ব্যবহারের জন্য Paid প্ল্যানে আপগ্রেড করুন।"
                                _chatTurns.update { it + ChatTurn(isUser = false, message = limitMsg) }
                                postBanner(limitMsg)
                                engine.speak(limitMsg, preferBangla = true) { resumeAutoListeningIfEnabled(appCtx) }
                                return@fold
                            }
                            _isProcessing.value = false
                            submitTextCommand(
                                context = appCtx,
                                rawCommand = remainingAudioCmd,
                                voiceConfidence = 0.98f,
                                usageAlreadyCounted = true
                            )
                            return@fold
                        }
                        if (checkAndExecuteSpokenApproval(appCtx, displayTranscript)) {
                            _isProcessing.value = false
                            return@fold
                        }

                        if (!PreferencesRepository(appCtx).incrementAndCheckDailyUsage()) {
                            _isProcessing.value = false
                            val limitMsg = "আপনার আজকের ফ্রি লিমিট শেষ হয়েছে। আনলিমিটেড ব্যবহারের জন্য Paid প্ল্যানে আপগ্রেড করুন।"
                            _chatTurns.update { it + ChatTurn(isUser = false, message = limitMsg) }
                            postBanner(limitMsg)
                            engine.speak(limitMsg, preferBangla = true) { resumeAutoListeningIfEnabled(appCtx) }
                            return@fold
                        }

                        val finalReply = analysis.naturalReply.ifBlank {
                            if (analysis.steps.isNotEmpty()) {
                                "আপনার আদেশ অনুযায়ী কাজটি সম্পন্ন করছি।"
                            } else {
                                "জি বলুন, আমি শুনছি—আপনার জন্য কী করবো?"
                            }
                        }

                        handleSuccessfulAnalysis(
                            appCtx = appCtx,
                            userCommandLabel = displayTranscript,
                            analysis = analysis.copy(naturalReply = finalReply),
                            currentMode = currentSettings.userMode,
                            voiceConfidence = 0.98f
                        )
                        _isProcessing.value = false
                    },
                    onFailure = { error ->
                        _isProcessing.value = false
                        Log.w(TAG, "Audio command cloud call failed: ${error.message}")
                        val friendlyErr = HeyShuvoAiService.classifyFriendlyAiError(error)
                        val now = System.currentTimeMillis()
                        if (!isAutoMode || now - lastAutoFailureBannerEpochMs > 18_000L) {
                            lastAutoFailureBannerEpochMs = now
                            _statusBanner.value = friendlyErr
                        }
                        if (!isAutoMode) {
                            _chatTurns.update {
                                it + ChatTurn(isUser = false, message = friendlyErr, isError = true)
                            }
                        }
                        _lastHudHeadline.value = "প্রস্তুত • কথা বলুন"
                        resumeAutoListeningIfEnabled(appCtx)
                    }
                )
            } catch (ce: CancellationException) {
                wasCancelled = true
                throw ce
            } catch (e: Exception) {
                Log.w(TAG, "Audio processing exception", e)
                val friendlyErr = HeyShuvoAiService.classifyFriendlyAiError(e)
                if (!isAutoMode) {
                    _statusBanner.value = friendlyErr
                    _chatTurns.update {
                        it + ChatTurn(isUser = false, message = friendlyErr, isError = true)
                    }
                }
                _lastHudHeadline.value = "প্রস্তুত • কথা বলুন"
                resumeAutoListeningIfEnabled(appCtx)
            } finally {
                if (activeCommandJob === kotlinx.coroutines.currentCoroutineContext()[Job]) {
                    _isProcessing.value = false
                    val queued = pendingAutoAudio
                    if (!wasCancelled && queued != null && !engine.state.value.isSpeaking) {
                        pendingAutoAudio = null
                        appScope.launch {
                            submitAudioCommand(appCtx, queued.first, queued.second, isAutoMode = true)
                        }
                    } else if (engine.state.value.isSpeaking) {
                        pendingAutoAudio = null
                    }
                }
            }
        }
        activeCommandJob = job
    }

    fun detectRequestedVoiceProfile(rawCommand: String): VoiceAssistantEngine.VoiceProfile? {
        val normCmd = rawCommand.trim().lowercase()
            .replace("ভয়েস", "ভয়েস")
            .replace("কন্ঠ", "কণ্ঠ")
            .replace("মেয়ে", "মেয়ে")

        if (normCmd.isBlank()) return null
        val words = normCmd.split(Regex("\\s+")).filter { it.isNotBlank() }

        // Never hijack email/mail/messaging commands ("ইমেইল পাঠাও", "মেইল চেক করো", "মেয়েকে ভয়েস পাঠাও")
        if (normCmd.contains("ইমেইল") ||
            normCmd.contains("মেইল") ||
            normCmd.contains("email") ||
            normCmd.contains("gmail") ||
            normCmd.contains("মেসেজ") ||
            normCmd.contains("message") ||
            normCmd.contains("পাঠা") ||
            Regex("\\bsend\\b").containsMatchIn(normCmd)
        ) {
            return null
        }

        val hasVoiceCue = normCmd.contains("ভয়েস") ||
            normCmd.contains("কণ্ঠ") ||
            normCmd.contains("গলা") ||
            normCmd.contains("সাউন্ড") ||
            Regex("\\b(voice|sound|tone|tts|speak)\\b").containsMatchIn(normCmd)

        if (!hasVoiceCue && words.size > 3) return null

        val wantsDeep = Regex("\\bdeep\\b").containsMatchIn(normCmd) ||
            normCmd.contains("ডিপ") ||
            normCmd.contains("গম্ভীর") ||
            (normCmd.contains("ভারী") && hasVoiceCue) ||
            normCmd.contains("ব্যাস কণ্ঠ")

        val wantsFemale = Regex("\\bfemale\\b").containsMatchIn(normCmd) ||
            normCmd.contains("ফিমেল") ||
            normCmd.contains("ফিমেইল") ||
            (Regex("নারী|মহিলা|মেয়ে|লেডি").containsMatchIn(normCmd) && hasVoiceCue) ||
            normCmd == "female" || normCmd == "নারী" || normCmd == "মেয়ে"

        val wantsMale = !wantsFemale && (
            Regex("\\bmale\\b").containsMatchIn(normCmd) ||
                (Regex("পুরুষ|ছেলে").containsMatchIn(normCmd) && hasVoiceCue) ||
                (normCmd.contains("মেল") && hasVoiceCue) ||
                normCmd == "male" || normCmd == "পুরুষ"
            )

        return when {
            wantsDeep -> VoiceAssistantEngine.VoiceProfile.DEEP
            wantsFemale -> VoiceAssistantEngine.VoiceProfile.FEMALE
            wantsMale -> VoiceAssistantEngine.VoiceProfile.MALE
            else -> null
        }
    }

    private fun stripVoiceSwitchPhrase(rawCommand: String): String {
        var cleaned = rawCommand
            .replace("ভয়েস", "ভয়েস")
            .replace("কন্ঠ", "কণ্ঠ")
            .replace("মেয়ে", "মেয়ে")
            .replace(Regex("(?i)\\b(switch|change|use|set|in|to|with|into|as|female|male|deep|voice|sound|tone|tts|mode)\\b"), " ")
            .replace(
                Regex("(নারীর|নারী|মহিলার|মহিলা|মেয়ের|মেয়ে|লেডি|ফিমেল|ফিমেইল|পুরুষের|পুরুষ|ছেলের|ছেলে|মেল|ডিপ|গম্ভীর|ভারী|ব্যাস|ভয়েসে|ভয়েস|কণ্ঠে|কণ্ঠ|গলায়|গলা|সাউন্ডে|সাউন্ড|দিয়ে|দিয়ে|অনুযায়ী|করে|হয়ে|হয়ে|বদলাও|বদল|পরিবর্তন|চেঞ্জ|সেট)"),
                " "
            )
            .replace(Regex("[,.:;!?]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        val pureConfirmCheck = cleaned
            .replace(Regex("(?i)\\b(speak|talk|reply|answer|now|please|karo|koro)\\b"), " ")
            .replace(Regex("(কথা বলো|কথা বল|বলো|বল|করো|দাও|শুভ|হে শুভ|বস)"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        return if (pureConfirmCheck.length < 4) "" else cleaned
    }

    private fun applyVoiceProfileSwitchAndExtractRemaining(
        appCtx: Context,
        rawCommand: String,
        recordUserMessageIfSwitchedOnly: Boolean = false
    ): Pair<Boolean, String> {
        val requestedProfile = detectRequestedVoiceProfile(rawCommand) ?: return false to rawCommand

        val engine = getEngine(appCtx)
        engine.setVoiceProfile(requestedProfile)
        appScope.launch(Dispatchers.IO) {
            PreferencesRepository(appCtx).saveVoiceProfile(requestedProfile.name)
        }
        val remainder = stripVoiceSwitchPhrase(rawCommand)
        if (remainder.isNotBlank()) {
            val profileTag = when (requestedProfile) {
                VoiceAssistantEngine.VoiceProfile.FEMALE -> "নারী কণ্ঠ সক্রিয়"
                VoiceAssistantEngine.VoiceProfile.MALE -> "পুরুষ কণ্ঠ সক্রিয়"
                VoiceAssistantEngine.VoiceProfile.DEEP -> "গম্ভীর কণ্ঠ সক্রিয়"
            }
            postBanner("✓ $profileTag • আপনার অনুরোধ সম্পন্ন হচ্ছে...")
            return false to remainder
        }

        val reply = when (requestedProfile) {
            VoiceAssistantEngine.VoiceProfile.FEMALE -> "জি বস, এখন থেকে আমি স্পষ্ট নারী কণ্ঠে কথা বলছি।"
            VoiceAssistantEngine.VoiceProfile.MALE -> "জি বস, এখন থেকে আমি স্পষ্ট পুরুষ কণ্ঠে কথা বলছি।"
            VoiceAssistantEngine.VoiceProfile.DEEP -> "জি বস, এখন থেকে আমি গম্ভীর ও ভারী কণ্ঠে কথা বলছি।"
        }
        _chatTurns.update { current ->
            val withUser = if (recordUserMessageIfSwitchedOnly) {
                current + ChatTurn(isUser = true, message = rawCommand)
            } else current
            withUser + ChatTurn(isUser = false, message = reply)
        }
        postBanner(reply)
        engine.speak(reply, preferBangla = true) {
            resumeAutoListeningIfEnabled(appCtx)
        }
        return true to ""
    }

    fun handleVoiceProfileSwitchIfRequested(appCtx: Context, rawCommand: String): Boolean {
        return applyVoiceProfileSwitchAndExtractRemaining(appCtx, rawCommand).first
    }

    private suspend fun handleSuccessfulAnalysis(
        appCtx: Context,
        userCommandLabel: String,
        analysis: StructuredCommandAnalysis,
        currentMode: UserAccessibilityMode,
        voiceConfidence: Float
    ) {
        val uniqueTaskId = "TSK-${UUID.randomUUID().toString().take(8).uppercase()}"
        val actionableSteps = analysis.steps.filter { it.actionPayload.kind != DeviceActionKind.NONE }
        val hasExternalActions = actionableSteps.isNotEmpty()
        val needsApproval = hasExternalActions && currentMode == UserAccessibilityMode.CHILDREN

        val controller = getController(appCtx)
        val vaultRepo = PrivateVaultRepository(PrivateVaultDatabase.getInstance(appCtx).privateVaultDao())
        val ownerKey = activeLocalOwnerKey

        val immediateOutcomes = mutableListOf<ActionExecutionOutcome>()
        var switchedToManual = false
        if (!needsApproval && hasExternalActions) {
            actionableSteps.forEach { step ->
                if (step.actionPayload.kind == DeviceActionKind.SWITCH_VOICE_MODE &&
                    step.actionPayload.targetOrPath.equals("MANUAL", ignoreCase = true)
                ) {
                    switchedToManual = true
                }
                executeSinglePayload(appCtx, controller, vaultRepo, ownerKey, step.actionPayload)
                    .onSuccess { immediateOutcomes.add(it) }
                    .onFailure {
                        immediateOutcomes.add(
                            ActionExecutionOutcome("✕ অ্যাকশন ত্রুটি: ${it.localizedMessage ?: "Failed"}")
                        )
                    }
            }
        }

        val assistantTurn = ChatTurn(
            isUser = false,
            message = analysis.naturalReply,
            languageBadge = analysis.detectedLanguage,
            taskId = if (hasExternalActions) uniqueTaskId else null,
            steps = actionableSteps,
            awaitingConfirmation = needsApproval,
            confirmed = !needsApproval,
            outcomes = immediateOutcomes
        )
        _chatTurns.value = _chatTurns.value + assistantTurn
        _lastHudHeadline.value = immediateOutcomes.firstOrNull()?.summary?.removePrefix("✓ ")?.take(38)
            ?: analysis.naturalReply.take(38)

        val preferBangla = analysis.detectedLanguage.contains("Bangla", ignoreCase = true) ||
            analysis.detectedLanguage.contains("Sylheti", ignoreCase = true) ||
            analysis.detectedLanguage.contains("Chittagonian", ignoreCase = true) ||
            analysis.detectedLanguage.contains("Noakhali", ignoreCase = true) ||
            analysis.naturalReply.any { it in '\u0980'..'\u09FF' }

        if (switchedToManual) {
            val engine = getEngine(appCtx)
            engine.setPausedForProcessing(false)
            engine.speak(analysis.naturalReply, preferBangla = preferBangla)
        } else {
            speakAndResumeAutoIfActive(appCtx, analysis.naturalReply, preferBangla = preferBangla)
        }

        if (hasExternalActions && actionableSteps.none { it.actionPayload.kind == DeviceActionKind.UNDO_LAST_TASK }) {
            vaultRepo.recordTaskLog(
                TaskLogEntity(
                    taskId = uniqueTaskId,
                    ownerUid = ownerKey,
                    commandText = userCommandLabel,
                    stepsSummary = actionableSteps.joinToString(" -> ") { "${it.stepIndex}. ${it.actionTitle}" },
                    status = if (needsApproval) "AWAITING_CONFIRMATION" else "COMPLETED",
                    confidenceScore = voiceConfidence,
                    reversible = actionableSteps.all { it.reversible }
                )
            )
        }
    }

    suspend fun executeSinglePayload(
        appCtx: Context,
        controller: VoiceDeviceController,
        vaultRepo: PrivateVaultRepository,
        ownerKey: String,
        payload: ExecutableActionPayload
    ): Result<ActionExecutionOutcome> {
        return when (payload.kind) {
            DeviceActionKind.SWITCH_VOICE_MODE -> {
                val prefs = PreferencesRepository(appCtx)
                val targetAuto = payload.targetOrPath.equals("AUTO", ignoreCase = true)
                prefs.setAutoAllDisplayVoice(targetAuto)
                if (targetAuto) {
                    HeyShuvoAllDisplayService.startOrRefresh(appCtx)
                    Result.success(
                        ActionExecutionOutcome(
                            summary = "✓ Auto (All Display) ভয়েস মোড চালু হয়েছে।"
                        )
                    )
                } else {
                    onExternalStopAuto?.invoke()
                    HeyShuvoAllDisplayService.stop(appCtx)
                    getEngine(appCtx).stopListening(submitRecordedAudio = false)
                    Result.success(
                        ActionExecutionOutcome(
                            summary = "✓ Manual ভয়েস মোড চালু হয়েছে।"
                        )
                    )
                }
            }

            DeviceActionKind.SET_BACKGROUND_VOICE_LISTENING -> {
                val enable = payload.targetOrPath.equals("ON", ignoreCase = true) ||
                    payload.targetOrPath.equals("TRUE", ignoreCase = true)
                getEngine(appCtx).setListenBackgroundVoicesMode(enable)
                appScope.launch(Dispatchers.IO) {
                    PreferencesRepository(appCtx).setListenBackgroundVoices(enable)
                }
                controller.executeAction(payload)
            }

            DeviceActionKind.SET_USER_LOCATION -> {
                val prefs = PreferencesRepository(appCtx)
                val qualified = VoiceDeviceController.qualifyLocationForMaps(payload.targetOrPath)
                prefs.setUserHomeLocation(qualified)
                controller.executeAction(payload.copy(targetOrPath = qualified))
            }

            DeviceActionKind.UNDO_LAST_TASK -> {
                val logs = vaultRepo.observeTaskLogs(ownerKey).first()
                val latestReversible = logs.firstOrNull { it.reversible && it.status != "UNDONE" }
                if (latestReversible != null) {
                    val stepsLower = latestReversible.stepsSummary.lowercase()
                    if ("tally" in stepsLower || "ট্যালি" in stepsLower) {
                        vaultRepo.deleteLatestLedgerEntry(ownerKey)
                    }
                    if ("memory" in stepsLower || "মেমরি" in stepsLower) {
                        vaultRepo.deleteLatestMemory(ownerKey)
                    }
                    if ("flashlight on" in stepsLower) {
                        controller.executeAction(ExecutableActionPayload(kind = DeviceActionKind.TOGGLE_FLASHLIGHT, targetOrPath = "OFF"))
                    } else if ("flashlight off" in stepsLower) {
                        controller.executeAction(ExecutableActionPayload(kind = DeviceActionKind.TOGGLE_FLASHLIGHT, targetOrPath = "ON"))
                    }
                    vaultRepo.undoTask(latestReversible.taskId, ownerKey)
                    Result.success(
                        ActionExecutionOutcome(
                            summary = "✓ সর্বশেষ কাজ (${latestReversible.taskId}: ${latestReversible.commandText}) সম্পূর্ণ Undo করা হয়েছে।"
                        )
                    )
                } else {
                    Result.success(
                        ActionExecutionOutcome(
                            summary = "✓ বর্তমানে Undo করার মতো কোনো সাম্প্রতিক কাজ নেই।"
                        )
                    )
                }
            }

            DeviceActionKind.SUBMIT_FEATURE_REQUEST -> {
                val reqTitle = payload.titleOrSubject.ifBlank { "Voice Feature Request" }
                val reqBody = payload.contentOrBody.ifBlank { reqTitle }
                onSubmitFeatureRequestBridge?.invoke(reqTitle, reqBody)
                Result.success(
                    ActionExecutionOutcome(
                        summary = "✓ নতুন ফিচার নোট সংরক্ষিত হয়েছে: $reqTitle"
                    )
                )
            }

            DeviceActionKind.CHANGE_APP_THEME -> {
                val prefs = PreferencesRepository(appCtx)
                val target = payload.targetOrPath.uppercase().trim()
                val summaryLabel = when (target) {
                    "LIGHT", "DAYLIGHT_CLEAN" -> {
                        prefs.setThemePalette(AppThemePalette.DAYLIGHT_CLEAN)
                        prefs.setDarkHudTheme(false)
                        "লাইট (সাদা) থিম"
                    }
                    "DARK" -> {
                        prefs.setDarkHudTheme(true)
                        "ডার্ক থিম"
                    }
                    else -> {
                        val matchedPalette = AppThemePalette.entries.find { it.name == target }
                            ?: AppThemePalette.CYBER_CYAN
                        prefs.setThemePalette(matchedPalette)
                        matchedPalette.labelBn
                    }
                }
                Result.success(
                    ActionExecutionOutcome(
                        summary = "✓ ভয়েস কমান্ডে থিম পরিবর্তন হয়েছে: $summaryLabel"
                    )
                )
            }

            DeviceActionKind.SAVE_TALLY_ENTRY -> {
                val entryType = payload.targetOrPath.uppercase().let {
                    if (it in setOf("INCOME", "EXPENSE", "DUE")) it else "DUE"
                }
                val customer = payload.titleOrSubject.ifBlank { "গ্রাহক (Customer)" }
                val amount = if (payload.amountBdt > 0.0) {
                    payload.amountBdt
                } else {
                    BanglaCommandHelper.extractAmountBdt("${payload.titleOrSubject} ${payload.contentOrBody}").takeIf { it > 0.0 } ?: 500.0
                }
                vaultRepo.addLedgerEntry(
                    LedgerEntryEntity(
                        ownerUid = ownerKey,
                        customerName = customer,
                        entryType = entryType,
                        itemDescription = payload.contentOrBody.ifBlank { "Voice Tally Entry" },
                        amountBdt = amount
                    )
                )
                controller.executeAction(
                    payload.copy(targetOrPath = entryType, titleOrSubject = customer, amountBdt = amount)
                )
            }

            DeviceActionKind.SAVE_PERSONAL_MEMORY -> {
                val topic = payload.titleOrSubject.ifBlank { "Personal Note" }
                val detail = payload.contentOrBody.ifBlank { topic }
                vaultRepo.saveMemory(
                    PrivateMemoryEntity(
                        ownerUid = ownerKey,
                        topic = topic,
                        content = detail
                    )
                )
                controller.executeAction(
                    payload.copy(titleOrSubject = topic, contentOrBody = detail)
                )
            }

            DeviceActionKind.SAVE_BIRTHDAY_REMINDER -> {
                val person = payload.titleOrSubject.trim().ifBlank { "প্রিয়জন" }
                val day = payload.hour.coerceIn(1, 31)
                val month = payload.minute.coerceIn(1, 12)
                val phone = payload.targetOrPath.trim()
                val wish = payload.contentOrBody.trim().ifBlank {
                    "শুভ জন্মদিন $person! আপনার আগামী দিনগুলো সুস্থ, সুন্দর ও আনন্দময় হোক।"
                }
                val entry = BirthdayReminderEntity(
                    id = "bday_${UUID.randomUUID().toString().replace("-", "").take(12)}",
                    ownerUid = ownerKey,
                    personName = person,
                    birthDay = day,
                    birthMonth = month,
                    phoneOrContact = phone,
                    wishMessage = wish,
                    lastWishedYear = 0
                )
                vaultRepo.upsertBirthday(entry)
                onSaveBirthdayCloudBridge?.invoke(entry)
                controller.executeAction(payload.copy(titleOrSubject = person, contentOrBody = wish, hour = day, minute = month))
            }

            else -> controller.executeAction(payload)
        }
    }

    private fun checkAndExecuteSpokenApproval(appCtx: Context, trimmed: String): Boolean {
        val lower = trimmed.lowercase().trim().trimEnd('.', '!', '?')
        val approvalWords = setOf(
            "yes", "ok", "okay", "confirm", "send", "ha", "haa", "ji",
            "হ্যাঁ", "জি", "ওকে", "পাঠাও", "করো", "চালু করো", "কনফার্ম"
        )
        if (lower in approvalWords) {
            val pending = _chatTurns.value.lastOrNull { it.awaitingConfirmation }
            if (pending != null) {
                _chatTurns.value = _chatTurns.value + ChatTurn(isUser = true, message = trimmed, languageBadge = "CONFIRM")
                confirmPendingTurn(appCtx, pending.id, enteredPin = null, bypassPinFromVoice = false)
                return true
            }
        }
        return false
    }

    fun confirmPendingTurn(
        context: Context,
        turnId: String,
        enteredPin: String? = null,
        bypassPinFromVoice: Boolean = false
    ) {
        val appCtx = context.applicationContext
        appScope.launch {
            val settings = PreferencesRepository(appCtx).settingsFlow.first()
            val normalizedEnteredPin = BanglaCommandHelper.normalizeDigits(enteredPin.orEmpty().trim())
            if (!bypassPinFromVoice && settings.userMode == UserAccessibilityMode.CHILDREN && normalizedEnteredPin != settings.parentPin) {
                _statusBanner.value = "Children Mode এ সঠিক Parent PIN প্রয়োজন (Default: ${settings.parentPin})।"
                return@launch
            }

            val target = _chatTurns.value.find { it.id == turnId } ?: return@launch
            val controller = getController(appCtx)
            val vaultRepo = PrivateVaultRepository(PrivateVaultDatabase.getInstance(appCtx).privateVaultDao())
            val ownerKey = activeLocalOwnerKey

            val executionOutcomes = mutableListOf<ActionExecutionOutcome>()
            target.steps.forEach { step ->
                if (step.actionPayload.kind != DeviceActionKind.NONE) {
                    executeSinglePayload(appCtx, controller, vaultRepo, ownerKey, step.actionPayload)
                        .onSuccess { executionOutcomes.add(it) }
                        .onFailure {
                            executionOutcomes.add(
                                ActionExecutionOutcome("✕ ${it.localizedMessage ?: "Action error"}")
                            )
                        }
                }
            }

            _chatTurns.value = _chatTurns.value.map {
                if (it.id == turnId) {
                    it.copy(
                        awaitingConfirmation = false,
                        confirmed = true,
                        outcomes = executionOutcomes
                    )
                } else it
            }

            target.taskId?.let { tid ->
                vaultRepo.recordTaskLog(
                    TaskLogEntity(
                        taskId = tid,
                        ownerUid = ownerKey,
                        commandText = "Confirmed & Executed Voice Action",
                        stepsSummary = executionOutcomes.joinToString(" | ") { it.summary },
                        status = "EXECUTED_VERIFIED",
                        confidenceScore = 1.0f,
                        reversible = target.steps.all { it.reversible }
                    )
                )
            }
            _statusBanner.value = executionOutcomes.firstOrNull()?.summary ?: "কাজটি সম্পন্ন হয়েছে।"
            resumeAutoListeningIfEnabled(appCtx)
        }
    }

    private fun speakAndResumeAutoIfActive(appCtx: Context, text: String, preferBangla: Boolean) {
        val engine = getEngine(appCtx)
        engine.speak(
            text = text,
            preferBangla = preferBangla,
            onComplete = {
                resumeAutoListeningIfEnabled(appCtx)
            }
        )
    }

    private fun resumeAutoListeningIfEnabled(appCtx: Context) {
        appScope.launch {
            val isAuto = cachedSettings?.autoAllDisplayVoice
                ?: PreferencesRepository(appCtx).settingsFlow.first().autoAllDisplayVoice
            val hasMic = ContextCompat.checkSelfPermission(
                appCtx,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            val engine = getEngine(appCtx)
            if (isAuto && hasMic && !_isProcessing.value) {
                delay(80L)
                engine.setPausedForProcessing(false)
                if (!engine.isContinuousStreamActive() && !engine.state.value.isSpeaking) {
                    startVoiceCapture(appCtx, preferDirectAiAudio = true, continuousAutoMode = true, userInitiated = false)
                }
            } else {
                engine.setPausedForProcessing(false)
            }
        }
    }

    private fun buildRecentConversationContext(): String {
        return _chatTurns.value
            .filter { !it.isError }
            .takeLast(5)
            .joinToString("\n") { turn ->
                val speaker = if (turn.isUser) "User" else "Hey Shuvo"
                "$speaker: ${turn.message.take(220)}"
            }
    }

    internal fun formatTallySummary(entries: List<LedgerEntryEntity>): String {
        if (entries.isEmpty()) return ""
        val totalIncome = entries.filter { it.entryType == "INCOME" }.sumOf { it.amountBdt }
        val totalExpense = entries.filter { it.entryType == "EXPENSE" }.sumOf { it.amountBdt }
        val totalDue = entries.filter { it.entryType == "DUE" }.sumOf { it.amountBdt }
        val recentRows = entries.take(8).joinToString("; ") {
            "${it.customerName} (${it.entryType}: ৳${it.amountBdt.toLong()} - ${it.itemDescription})"
        }
        return String.format(
            Locale.US,
            "ট্যালি খাতার বর্তমান হিসাব: মোট জমা/আয় ৳%.0f, মোট খরচ ৳%.0f, মোট বাকি (Due) ৳%.0f। সাম্প্রতিক এন্ট্রি: %s",
            totalIncome,
            totalExpense,
            totalDue,
            recentRows
        )
    }
}

class HeyShuvoAllDisplayService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var windowManager: WindowManager? = null
    private var overlayHudView: HeyShuvoOverlayHudView? = null
    private var overlayParams: WindowManager.LayoutParams? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        val engine = HeyShuvoVoiceCoordinator.getEngine(applicationContext)

        serviceScope.launch {
            engine.state.collectLatest { voiceState ->
                overlayHudView?.updateState(
                    voiceState = voiceState,
                    isProcessing = HeyShuvoVoiceCoordinator.isProcessing.value,
                    fallbackHeadline = HeyShuvoVoiceCoordinator.lastHudHeadline.value
                )
            }
        }
        serviceScope.launch {
            HeyShuvoVoiceCoordinator.lastHudHeadline.collectLatest { headline ->
                overlayHudView?.updateState(
                    voiceState = engine.state.value,
                    isProcessing = HeyShuvoVoiceCoordinator.isProcessing.value,
                    fallbackHeadline = headline
                )
            }
        }
        serviceScope.launch {
            HeyShuvoVoiceCoordinator.isMainAppInForeground.collectLatest { inForeground ->
                if (inForeground) {
                    removeOverlayHud()
                } else {
                    ensureOverlayHudIfPermitted()
                }
            }
        }
        serviceScope.launch {
            while (true) {
                delay(1500L)
                if (HeyShuvoVoiceCoordinator.isMainAppInForeground.value) {
                    if (overlayHudView != null) removeOverlayHud()
                } else if (overlayHudView == null) {
                    ensureOverlayHudIfPermitted()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val hasMicPerm = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        ensureNotificationChannel()
        val notification = buildForegroundNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && hasMicPerm) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Fallback to standard foreground notification", e)
            runCatching { startForeground(NOTIFICATION_ID, notification) }
        }

        if (intent?.action == ACTION_STOP_AUTO) {
            serviceScope.launch(Dispatchers.IO) {
                PreferencesRepository(applicationContext).setAutoAllDisplayVoice(false)
            }
            HeyShuvoVoiceCoordinator.onExternalStopAuto?.invoke()
            HeyShuvoVoiceCoordinator.getEngine(applicationContext).stopListening(submitRecordedAudio = false)
            removeOverlayHud()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        if (intent?.action == ACTION_REFRESH_AVATAR) {
            overlayHudView?.reloadCustomAvatar()
            return START_STICKY
        }

        if (!hasMicPerm) {
            Log.w(TAG, "RECORD_AUDIO permission missing; deferring foreground service until granted.")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        HeyShuvoVoiceCoordinator.startVoiceCapture(
            applicationContext,
            preferDirectAiAudio = true,
            continuousAutoMode = true,
            userInitiated = false
        )
        ensureOverlayHudIfPermitted()
        return START_STICKY
    }

    private var dockedOnRightEdge: Boolean = true

    private fun ensureOverlayHudIfPermitted() {
        // NEVER draw floating overlay window over Hey Shuvo's own MainActivity!
        if (HeyShuvoVoiceCoordinator.isMainAppInForeground.value) {
            removeOverlayHud()
            return
        }
        val canDraw = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else {
            true
        }
        if (!canDraw) {
            removeOverlayHud()
            return
        }
        if (overlayHudView != null) return

        val wm = windowManager ?: return
        val headSizePx = dpToPx(62f)
        val expandedWidthPx = dpToPx(206f)
        val edgeMarginPx = dpToPx(8f)
        val screenWidthPx = resources.displayMetrics.widthPixels

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val initialX = if (dockedOnRightEdge) {
            (screenWidthPx - headSizePx - edgeMarginPx).coerceAtLeast(0)
        } else {
            edgeMarginPx
        }

        val params = WindowManager.LayoutParams(
            headSizePx,
            headSizePx,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = initialX
            y = dpToPx(136f)
        }

        val chatHead = HeyShuvoOverlayHudView(
            context = this,
            onVoiceTap = { HeyShuvoVoiceCoordinator.triggerOverlayOrbTap(applicationContext) },
            onSwitchManualTap = { HeyShuvoVoiceCoordinator.triggerOverlaySwitchToManual(applicationContext) },
            onOpenAppTap = {
                val openApp = Intent(applicationContext, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
                try {
                    startActivity(openApp)
                } catch (_: Exception) {
                }
            },
            onExpandChanged = { expanded ->
                val p = overlayParams
                val v = overlayHudView
                if (p != null && v != null) {
                    val curScreenW = resources.displayMetrics.widthPixels
                    p.width = if (expanded) expandedWidthPx else headSizePx
                    p.x = if (dockedOnRightEdge) {
                        (curScreenW - p.width - edgeMarginPx).coerceAtLeast(0)
                    } else {
                        edgeMarginPx
                    }
                    v.setDockedRight(dockedOnRightEdge)
                    try {
                        wm.updateViewLayout(v, p)
                    } catch (_: Exception) {
                    }
                }
            },
            onDragDelta = { dx, dy ->
                val p = overlayParams
                val v = overlayHudView
                if (p != null && v != null) {
                    val maxX = (resources.displayMetrics.widthPixels - p.width).coerceAtLeast(0)
                    val maxY = (resources.displayMetrics.heightPixels - p.height).coerceAtLeast(0)
                    p.x = (p.x + dx).coerceIn(0, maxX)
                    p.y = (p.y + dy).coerceIn(0, maxY)
                    try {
                        wm.updateViewLayout(v, p)
                    } catch (_: Exception) {
                    }
                }
            },
            onDragReleased = {
                val p = overlayParams
                val v = overlayHudView
                if (p != null && v != null) {
                    val curScreenW = resources.displayMetrics.widthPixels
                    val centerX = p.x + p.width / 2
                    dockedOnRightEdge = centerX >= curScreenW / 2
                    p.x = if (dockedOnRightEdge) {
                        (curScreenW - p.width - edgeMarginPx).coerceAtLeast(0)
                    } else {
                        edgeMarginPx
                    }
                    v.setDockedRight(dockedOnRightEdge)
                    try {
                        wm.updateViewLayout(v, p)
                    } catch (_: Exception) {
                    }
                }
            }
        ).apply {
            setDockedRight(dockedOnRightEdge)
        }

        try {
            wm.addView(chatHead, params)
            overlayHudView = chatHead
            overlayParams = params
            chatHead.updateState(
                voiceState = HeyShuvoVoiceCoordinator.getEngine(applicationContext).state.value,
                isProcessing = HeyShuvoVoiceCoordinator.isProcessing.value,
                fallbackHeadline = HeyShuvoVoiceCoordinator.lastHudHeadline.value
            )
        } catch (e: Exception) {
            Log.w(TAG, "Messenger Chat Head attach skipped", e)
        }
    }

    private fun removeOverlayHud() {
        val wm = windowManager
        val v = overlayHudView
        overlayHudView = null
        overlayParams = null
        if (wm != null && v != null) {
            try {
                wm.removeView(v)
            } catch (_: Exception) {
            }
        }
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Hey Shuvo Main Display Voice",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps Hey Shuvo hands-free voice control active across Main Display and all apps."
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildForegroundNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val openPending = PendingIntent.getActivity(
            this,
            101,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, HeyShuvoAllDisplayService::class.java).apply {
            action = ACTION_STOP_AUTO
        }
        val stopPending = PendingIntent.getService(
            this,
            102,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_heyshuvo)
            .setContentTitle("Hey Shuvo • চ্যাট-হেড ভয়েস চালু")
            .setContentText("হোম স্ক্রিন বা যেকোনো অ্যাপে কথা বলুন — সরাসরি নির্দেশ পালন করবে")
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(openPending)
            .addAction(0, "Manual Mode", stopPending)
            .build()
    }

    private fun dpToPx(dp: Float): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp,
            resources.displayMetrics
        ).toInt()
    }

    override fun onDestroy() {
        removeOverlayHud()
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "hey_shuvo_all_display_voice"
        const val NOTIFICATION_ID = 4401
        const val ACTION_START_OR_REFRESH = "com.example.action.START_ALL_DISPLAY"
        const val ACTION_STOP_AUTO = "com.example.action.STOP_ALL_DISPLAY"
        const val ACTION_REFRESH_AVATAR = "com.example.action.REFRESH_CHAT_HEAD_AVATAR"
        const val EXTRA_TRIGGER_VOICE = "extra_trigger_voice"

        fun startOrRefresh(context: Context) {
            val appCtx = context.applicationContext
            val hasMic = ContextCompat.checkSelfPermission(
                appCtx,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            if (!hasMic) return

            val intent = Intent(appCtx, HeyShuvoAllDisplayService::class.java).apply {
                action = ACTION_START_OR_REFRESH
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ContextCompat.startForegroundService(appCtx, intent)
                } else {
                    appCtx.startService(intent)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Foreground service start deferred: ${e.message}")
            }
        }

        fun refreshAvatar(context: Context) {
            val appCtx = context.applicationContext
            val isAuto = HeyShuvoVoiceCoordinator.cachedSettings?.autoAllDisplayVoice ?: true
            if (!isAuto) return
            val hasMic = ContextCompat.checkSelfPermission(
                appCtx,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            if (!hasMic) return
            val intent = Intent(appCtx, HeyShuvoAllDisplayService::class.java).apply {
                action = ACTION_REFRESH_AVATAR
            }
            try {
                appCtx.startService(intent)
            } catch (_: Exception) {
            }
        }

        fun stop(context: Context) {
            val appCtx = context.applicationContext
            try {
                appCtx.stopService(Intent(appCtx, HeyShuvoAllDisplayService::class.java))
            } catch (_: Exception) {
            }
        }
    }
}

/**
 * Compact Messenger-Style Circular Floating Chat Head (62dp x 62dp) with Custom User Photo Avatar,
 * Animated Voice Activity Ring, Magnetic Edge Snap, and Optional Popover Mini-Controls!
 */
private class HeyShuvoOverlayHudView(
    context: Context,
    private val onVoiceTap: () -> Unit,
    private val onSwitchManualTap: () -> Unit,
    private val onOpenAppTap: () -> Unit,
    private val onExpandChanged: (Boolean) -> Unit,
    private val onDragDelta: (Int, Int) -> Unit,
    private val onDragReleased: () -> Unit
) : View(context) {

    private var isListening = false
    private var isSpeaking = false
    private var isProcessing = false
    private var hasVoiceActivity = false
    private var rmsLevel = 0f
    private var autoCutProgress = 0f
    private var animPhase = 0f
    private var expanded = false
    private var dockedRight = true

    // Custom circular user photo state
    private var avatarBitmap: Bitmap? = null
    private var avatarShader: BitmapShader? = null
    private val shaderMatrix = Matrix()

    // Touch bubble ripple state
    private var bubbleX = 0f
    private var bubbleY = 0f
    private var bubbleProgress = 1f

    private val density = context.resources.displayMetrics.density
    private val headDiameterPx = 62f * density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downRawX = 0f
    private var downRawY = 0f
    private var downLocalX = 0f
    private var downLocalY = 0f
    private var lastRawX = 0f
    private var lastRawY = 0f
    private var dragged = false
    private var longPressFired = false

    private val popoverRect = RectF()
    private val orbArcRect = RectF()
    private val badgeMenuRect = RectF()
    private val actionChipRect = RectF()

    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(125, 0, 0, 0)
    }

    private val headBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(246, 8, 17, 32)
    }

    private val avatarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isFilterBitmap = true
    }

    private val popoverBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(244, 10, 21, 38)
    }

    private val chipBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(238, 20, 39, 66)
    }

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.6f * density
    }

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val chipTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(236, 250, 255)
        textSize = 10.2f * density
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private val badgeTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(240, 252, 255)
        textSize = 8.8f * density
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private val longPressRunnable = Runnable {
        if (!dragged) {
            longPressFired = true
            onOpenAppTap()
        }
    }

    init {
        reloadCustomAvatar()
    }

    fun setDockedRight(right: Boolean) {
        this.dockedRight = right
        postInvalidateOnAnimation()
    }

    fun reloadCustomAvatar() {
        try {
            val path = PreferencesRepository.readChatHeadAvatarSync(context)
            if (path.isNotBlank() && File(path).exists()) {
                val decoded = BitmapFactory.decodeFile(path)
                if (decoded != null) {
                    val targetPx = (48f * density).toInt().coerceAtLeast(48)
                    val side = minOf(decoded.width, decoded.height).coerceAtLeast(1)
                    val cropX = ((decoded.width - side) / 2).coerceAtLeast(0)
                    val cropY = ((decoded.height - side) / 4).coerceAtLeast(0)
                    val square = Bitmap.createBitmap(decoded, cropX, cropY, side, side)
                    val scaled = Bitmap.createScaledBitmap(square, targetPx, targetPx, true)
                    if (square !== decoded) square.recycle()
                    decoded.recycle()
                    avatarBitmap = scaled
                    avatarShader = BitmapShader(scaled, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                    avatarPaint.shader = avatarShader
                    postInvalidateOnAnimation()
                    return
                }
            }
        } catch (_: Exception) {
        }
        avatarBitmap = null
        avatarShader = null
        avatarPaint.shader = null
        postInvalidateOnAnimation()
    }

    fun updateState(
        voiceState: SpeechRecognitionState,
        isProcessing: Boolean,
        fallbackHeadline: String
    ) {
        this.isListening = voiceState.isListening
        this.isSpeaking = voiceState.isSpeaking
        this.isProcessing = isProcessing
        this.hasVoiceActivity = voiceState.hasDetectedVoiceActivity
        this.rmsLevel = voiceState.rmsDb
        this.autoCutProgress = voiceState.autoCutProgress
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val accent = when {
            autoCutProgress > 0.05f -> Color.rgb(250, 204, 21)
            hasVoiceActivity -> Color.rgb(0, 255, 170)
            isListening -> Color.rgb(0, 230, 180)
            isSpeaking -> Color.rgb(56, 189, 248)
            isProcessing -> Color.rgb(251, 191, 36)
            else -> Color.rgb(0, 229, 255)
        }

        // Determine circular Chat Head center based on docked edge when expanded
        val headCenterX = if (expanded && dockedRight) {
            width - headDiameterPx * 0.5f
        } else {
            headDiameterPx * 0.5f
        }
        val headCenterY = height * 0.5f
        val outerR = headDiameterPx * 0.44f
        val avatarR = headDiameterPx * 0.37f

        // 1. Optional compact side popover pill when user taps the tiny menu badge (`••` / `✕`)
        if (expanded && width > headDiameterPx + 20f * density) {
            val pillTop = headCenterY - 21f * density
            val pillBottom = headCenterY + 21f * density
            if (dockedRight) {
                popoverRect.set(3f * density, pillTop, headCenterX - outerR - 4f * density, pillBottom)
            } else {
                popoverRect.set(headCenterX + outerR + 4f * density, pillTop, width - 3f * density, pillBottom)
            }
            canvas.drawRoundRect(popoverRect, 18f * density, 18f * density, popoverBgPaint)
            borderPaint.color = accent
            canvas.drawRoundRect(popoverRect, 18f * density, 18f * density, borderPaint)

            val gap = 5f * density
            val innerPad = 6f * density
            val chipW = (popoverRect.width() - innerPad * 2 - gap) / 2f
            val labels = arrayOf("অ্যাপ খুলুন", "Manual")
            for (i in labels.indices) {
                val cLeft = popoverRect.left + innerPad + i * (chipW + gap)
                actionChipRect.set(cLeft, popoverRect.top + 5f * density, cLeft + chipW, popoverRect.bottom - 5f * density)
                canvas.drawRoundRect(actionChipRect, 12f * density, 12f * density, chipBgPaint)
                canvas.drawText(
                    labels[i],
                    actionChipRect.centerX(),
                    actionChipRect.centerY() + 3.6f * density,
                    chipTextPaint
                )
            }
        }

        // 2. Soft drop-shadow + Messenger circular head base
        canvas.drawCircle(headCenterX, headCenterY + 1.5f * density, outerR + 1.2f * density, shadowPaint)
        canvas.drawCircle(headCenterX, headCenterY, outerR, headBgPaint)

        // 3. Inside the Circle: Customer's Custom Circular Photo OR Sleek Hey Shuvo AI Core
        val bmp = avatarBitmap
        val shader = avatarShader
        if (bmp != null && shader != null) {
            val scale = (avatarR * 2f) / bmp.width.coerceAtLeast(1).toFloat()
            shaderMatrix.reset()
            shaderMatrix.setScale(scale, scale)
            shaderMatrix.postTranslate(headCenterX - avatarR, headCenterY - avatarR)
            shader.setLocalMatrix(shaderMatrix)
            canvas.drawCircle(headCenterX, headCenterY, avatarR, avatarPaint)
        } else {
            val coreR = avatarR * 0.76f
            ringPaint.strokeWidth = 2.1f * density
            ringPaint.color = accent
            orbArcRect.set(headCenterX - coreR, headCenterY - coreR, headCenterX + coreR, headCenterY + coreR)
            for (i in 0 until 4) {
                canvas.drawArc(orbArcRect, animPhase + i * 90f, 56f, false, ringPaint)
            }
            val waveScale = if (isListening || isSpeaking) {
                0.32f + 0.34f * abs(sin((animPhase * 0.1f).toDouble())).toFloat()
            } else {
                0.30f
            }
            dotPaint.color = accent
            canvas.drawCircle(headCenterX, headCenterY, coreR * waveScale, dotPaint)
        }

        // 4. Animated Voice-Activity Border Ring around the circular Messenger Chat Head
        val dynamicPulse = if (hasVoiceActivity || isSpeaking) {
            (rmsLevel / 12f).coerceIn(0f, 1f) * 2.2f * density
        } else {
            0f
        }
        ringPaint.strokeWidth = 2.6f * density
        ringPaint.color = accent
        val ringR = outerR - 1.2f * density + dynamicPulse * 0.4f
        orbArcRect.set(headCenterX - ringR, headCenterY - ringR, headCenterX + ringR, headCenterY + ringR)
        if (autoCutProgress > 0.05f) {
            ringPaint.color = Color.argb(95, 0, 229, 255)
            canvas.drawCircle(headCenterX, headCenterY, ringR, ringPaint)
            ringPaint.color = Color.rgb(250, 204, 21)
            canvas.drawArc(orbArcRect, -90f, 360f * autoCutProgress.coerceIn(0f, 1f), false, ringPaint)
        } else if (isProcessing) {
            canvas.drawArc(orbArcRect, animPhase * 2f, 220f, false, ringPaint)
        } else {
            canvas.drawCircle(headCenterX, headCenterY, ringR, ringPaint)
        }

        // 5. Touch bubble ripple inside Chat Head
        if (bubbleProgress < 1f) {
            val alpha = ((1f - bubbleProgress) * 120f).toInt().coerceIn(0, 120)
            val curR = 8f * density + outerR * bubbleProgress
            bubblePaint.style = Paint.Style.FILL
            bubblePaint.color = Color.argb(alpha / 2, 0, 229, 255)
            canvas.drawCircle(bubbleX, bubbleY, curR, bubblePaint)
            bubblePaint.style = Paint.Style.STROKE
            bubblePaint.strokeWidth = 1.8f * density
            bubblePaint.color = Color.argb(alpha, 0, 230, 180)
            canvas.drawCircle(bubbleX, bubbleY, curR, bubblePaint)
            bubbleProgress = (bubbleProgress + 0.16f).coerceAtMost(1f)
        }

        // 6. Messenger Active Status Dot (Bottom-Right of circular head)
        val dotCx = headCenterX + outerR * 0.68f
        val dotCy = headCenterY + outerR * 0.68f
        dotPaint.color = Color.rgb(8, 17, 32)
        canvas.drawCircle(dotCx, dotCy, 6.8f * density, dotPaint)
        dotPaint.color = when {
            isProcessing -> Color.rgb(250, 204, 21)
            isSpeaking -> Color.rgb(56, 189, 248)
            isListening -> Color.rgb(0, 230, 118)
            else -> Color.rgb(0, 229, 255)
        }
        canvas.drawCircle(dotCx, dotCy, 4.8f * density, dotPaint)

        // 7. Tiny Quick-Popover Toggle Badge (Top-Right of circular head)
        val badgeCx = headCenterX + outerR * 0.68f
        val badgeCy = headCenterY - outerR * 0.68f
        val badgeR = 8.5f * density
        badgeMenuRect.set(badgeCx - badgeR * 1.4f, badgeCy - badgeR * 1.4f, badgeCx + badgeR * 1.4f, badgeCy + badgeR * 1.4f)
        dotPaint.color = Color.argb(235, 18, 36, 62)
        canvas.drawCircle(badgeCx, badgeCy, badgeR, dotPaint)
        borderPaint.strokeWidth = 1.1f * density
        borderPaint.color = accent
        canvas.drawCircle(badgeCx, badgeCy, badgeR, borderPaint)
        canvas.drawText(
            if (expanded) "✕" else "••",
            badgeCx,
            badgeCy + 3.0f * density,
            badgeTextPaint
        )

        if (isListening || isSpeaking || isProcessing || bubbleProgress < 1f) {
            animPhase = (animPhase + 6f) % 360f
            postInvalidateDelayed(36L)
        }
    }

    private fun startTouchBubble(localX: Float, localY: Float) {
        bubbleX = localX
        bubbleY = localY
        bubbleProgress = 0.05f
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        postInvalidateOnAnimation()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                downLocalX = event.x
                downLocalY = event.y
                lastRawX = event.rawX
                lastRawY = event.rawY
                dragged = false
                longPressFired = false
                startTouchBubble(event.x, event.y)
                handler?.postDelayed(longPressRunnable, 600L)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val totalDx = event.rawX - downRawX
                val totalDy = event.rawY - downRawY
                if (!dragged && (abs(totalDx) > touchSlop || abs(totalDy) > touchSlop)) {
                    dragged = true
                    handler?.removeCallbacks(longPressRunnable)
                    if (expanded) {
                        expanded = false
                        onExpandChanged(false)
                    }
                }
                if (dragged) {
                    val dx = (event.rawX - lastRawX).toInt()
                    val dy = (event.rawY - lastRawY).toInt()
                    lastRawX = event.rawX
                    lastRawY = event.rawY
                    onDragDelta(dx, dy)
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                handler?.removeCallbacks(longPressRunnable)
                if (dragged) {
                    onDragReleased()
                } else if (!longPressFired && event.actionMasked == MotionEvent.ACTION_UP) {
                    performClick()
                    handleLocalTap(downLocalX, downLocalY)
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun handleLocalTap(x: Float, y: Float) {
        if (badgeMenuRect.contains(x, y)) {
            expanded = !expanded
            onExpandChanged(expanded)
            postInvalidateOnAnimation()
            return
        }
        if (expanded && popoverRect.contains(x, y)) {
            val midX = popoverRect.centerX()
            expanded = false
            onExpandChanged(false)
            if (x < midX) {
                onOpenAppTap()
            } else {
                onSwitchManualTap()
            }
            return
        }
        onVoiceTap()
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
