package com.example

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material.icons.outlined.AdminPanelSettings
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Widgets
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.core.actions.VoiceDeviceController
import com.example.core.auth.authStateFlow
import com.example.core.auth.signOutUser
import com.example.core.preferences.AssistantSettings
import com.example.core.preferences.PreferencesRepository
import com.example.core.security.configureAppCheck
import com.example.core.service.DailySummaryAlarmReceiver
import com.example.core.service.HeyShuvoAllDisplayService
import com.example.core.service.HeyShuvoVoiceCoordinator
import com.example.data.cloud.repository.GovernanceRepository
import com.example.data.local.PrivateVaultDatabase
import com.example.data.local.PrivateVaultRepository
import com.example.feature.customer.HeyShuvoViewModel
import com.example.ui.screens.AssistantCommandScreen
import com.example.ui.screens.AuthGateScreen
import com.example.ui.screens.MasterControlScreen
import com.example.ui.screens.PrivacyVaultAndArchitectureScreen
import com.example.ui.screens.SmartHubScreen
import com.example.ui.screens.VoiceSoftwareAndBusinessScreen
import com.example.ui.theme.HeyShuvoTheme
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
    private val externalVoiceTriggerCounter = MutableStateFlow(0)
    private val externalPhotoTriggerCounter = MutableStateFlow(0)
    private val externalGestureTriggerCounter = MutableStateFlow(0)
    private val externalDiaryTriggerCounter = MutableStateFlow(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val defaultExceptionHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            android.util.Log.e("HeyShuvoCrashGuard", "Uncaught exception on ${thread.name}", throwable)
            defaultExceptionHandler?.uncaughtException(thread, throwable)
        }

        // STEP 1: Mandatory App Check configuration with launch intent before any UI or Firebase calls
        configureAppCheck(this, intent)
        consumeExternalTriggerExtras(intent)

        enableEdgeToEdge()

        setContent {
            val voiceTrigger by externalVoiceTriggerCounter.collectAsStateWithLifecycle()
            val photoTrigger by externalPhotoTriggerCounter.collectAsStateWithLifecycle()
            val gestureTrigger by externalGestureTriggerCounter.collectAsStateWithLifecycle()
            val diaryTrigger by externalDiaryTriggerCounter.collectAsStateWithLifecycle()
            HeyShuvoAppRoot(
                externalVoiceTrigger = voiceTrigger,
                externalPhotoTrigger = photoTrigger,
                externalGestureTrigger = gestureTrigger,
                externalDiaryTrigger = diaryTrigger,
                onConsumeVoiceTrigger = { externalVoiceTriggerCounter.value = 0 },
                onConsumePhotoTrigger = { externalPhotoTriggerCounter.value = 0 },
                onConsumeGestureTrigger = { externalGestureTriggerCounter.value = 0 },
                onConsumeDiaryTrigger = { externalDiaryTriggerCounter.value = 0 }
            )
        }
    }

    override fun onStart() {
        super.onStart()
        HeyShuvoVoiceCoordinator.setMainAppInForeground(true)
    }

    override fun onResume() {
        super.onResume()
        HeyShuvoVoiceCoordinator.setMainAppInForeground(true)
    }

    override fun onStop() {
        HeyShuvoVoiceCoordinator.setMainAppInForeground(false)
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        configureAppCheck(this, intent)
        consumeExternalTriggerExtras(intent)
    }

    private fun consumeExternalTriggerExtras(intent: Intent?) {
        if (intent == null) return
        if (intent.getBooleanExtra(HeyShuvoAllDisplayService.EXTRA_TRIGGER_VOICE, false)) {
            externalVoiceTriggerCounter.value += 1
            intent.removeExtra(HeyShuvoAllDisplayService.EXTRA_TRIGGER_VOICE)
        }
        if (intent.getBooleanExtra(VoiceDeviceController.EXTRA_TRIGGER_PHOTO_VISION, false)) {
            externalPhotoTriggerCounter.value += 1
            intent.removeExtra(VoiceDeviceController.EXTRA_TRIGGER_PHOTO_VISION)
        }
        if (intent.getBooleanExtra(VoiceDeviceController.EXTRA_TRIGGER_GESTURE_TRANSFER, false)) {
            externalGestureTriggerCounter.value += 1
            intent.removeExtra(VoiceDeviceController.EXTRA_TRIGGER_GESTURE_TRANSFER)
        }
        if (intent.getBooleanExtra(DailySummaryAlarmReceiver.EXTRA_TRIGGER_DAILY_DIARY, false)) {
            externalDiaryTriggerCounter.value += 1
            intent.removeExtra(DailySummaryAlarmReceiver.EXTRA_TRIGGER_DAILY_DIARY)
        }
    }
}

@Composable
fun HeyShuvoAppRoot(
    externalVoiceTrigger: Int = 0,
    externalPhotoTrigger: Int = 0,
    externalGestureTrigger: Int = 0,
    externalDiaryTrigger: Int = 0,
    onConsumeVoiceTrigger: () -> Unit = {},
    onConsumePhotoTrigger: () -> Unit = {},
    onConsumeGestureTrigger: () -> Unit = {},
    onConsumeDiaryTrigger: () -> Unit = {}
) {
    val context = LocalContext.current
    val preferencesRepository = remember { PreferencesRepository(context.applicationContext) }
    val settings by preferencesRepository.settingsFlow.collectAsStateWithLifecycle(
        initialValue = AssistantSettings()
    )

    val currentUser by Firebase.auth.authStateFlow()
        .collectAsStateWithLifecycle(initialValue = Firebase.auth.currentUser)

    var showOptionalSignInModal by rememberSaveable { mutableStateOf(false) }

    HeyShuvoTheme(
        darkTheme = settings.darkHudTheme,
        palette = settings.themePalette,
        customAccentHue = settings.customAccentHue,
        textScale = settings.textScale
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            val activeUser = currentUser
            if (showOptionalSignInModal && activeUser == null) {
                AuthGateScreen(
                    onAuthSuccess = { showOptionalSignInModal = false },
                    onDismissToPreview = { showOptionalSignInModal = false }
                )
            } else {
                HeyShuvoWorkspace(
                    authenticatedCloudUid = activeUser?.uid,
                    userEmail = activeUser?.email.orEmpty(),
                    externalVoiceTrigger = externalVoiceTrigger,
                    externalPhotoTrigger = externalPhotoTrigger,
                    externalGestureTrigger = externalGestureTrigger,
                    externalDiaryTrigger = externalDiaryTrigger,
                    onConsumeVoiceTrigger = onConsumeVoiceTrigger,
                    onConsumePhotoTrigger = onConsumePhotoTrigger,
                    onConsumeGestureTrigger = onConsumeGestureTrigger,
                    onConsumeDiaryTrigger = onConsumeDiaryTrigger,
                    onOpenSignInModal = { showOptionalSignInModal = true }
                )
            }
        }
    }
}

@Composable
private fun HeyShuvoWorkspace(
    authenticatedCloudUid: String?,
    userEmail: String,
    externalVoiceTrigger: Int,
    externalPhotoTrigger: Int,
    externalGestureTrigger: Int,
    externalDiaryTrigger: Int,
    onConsumeVoiceTrigger: () -> Unit,
    onConsumePhotoTrigger: () -> Unit,
    onConsumeGestureTrigger: () -> Unit,
    onConsumeDiaryTrigger: () -> Unit,
    onOpenSignInModal: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val credentialManager = remember { CredentialManager.create(context) }
    val localOwnerKey = authenticatedCloudUid ?: "local_preview_owner"

    val viewModel: HeyShuvoViewModel = viewModel(
        key = localOwnerKey,
        factory = viewModelFactory {
            initializer {
                val app = checkNotNull(this[APPLICATION_KEY])
                val databaseId = app.getString(R.string.firestore_database_id)
                val db = FirebaseFirestore.getInstance(databaseId)
                val vaultDb = PrivateVaultDatabase.getInstance(app)
                HeyShuvoViewModel(
                    appContext = app,
                    localOwnerKey = localOwnerKey,
                    authenticatedCloudUid = authenticatedCloudUid,
                    governanceRepository = GovernanceRepository(db),
                    privateVaultRepository = PrivateVaultRepository(vaultDb.privateVaultDao()),
                    preferencesRepository = PreferencesRepository(app),
                    voiceEngine = HeyShuvoVoiceCoordinator.getEngine(app),
                    deviceController = VoiceDeviceController(app)
                )
            }
        }
    )

    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val voiceState by viewModel.voiceState.collectAsStateWithLifecycle()
    val chatTurns by viewModel.chatTurns.collectAsStateWithLifecycle()
    val isProcessing by viewModel.isProcessing.collectAsStateWithLifecycle()
    val visionOutput by viewModel.visionOutput.collectAsStateWithLifecycle()
    val statusBanner by viewModel.statusBanner.collectAsStateWithLifecycle()

    val memories by viewModel.memories.collectAsStateWithLifecycle()
    val taskHistory by viewModel.taskHistory.collectAsStateWithLifecycle()
    val ledgerEntries by viewModel.ledgerEntries.collectAsStateWithLifecycle()
    val myFeatureRequests by viewModel.myFeatureRequests.collectAsStateWithLifecycle()
    val generatedFiles by viewModel.generatedFiles.collectAsStateWithLifecycle()
    val selfScreenTime by viewModel.selfScreenTime.collectAsStateWithLifecycle()
    val smartWriterOutput by viewModel.smartWriterOutput.collectAsStateWithLifecycle()
    val isSmartWriterBusy by viewModel.isSmartWriterBusy.collectAsStateWithLifecycle()
    val birthdays by viewModel.birthdays.collectAsStateWithLifecycle()
    val gesturePairingCode by viewModel.gesturePairingCode.collectAsStateWithLifecycle()
    val gestureStatusMessage by viewModel.gestureStatusMessage.collectAsStateWithLifecycle()
    val pendingIncomingTransfers by viewModel.pendingIncomingTransfers.collectAsStateWithLifecycle()
    val receivedTransferFiles by viewModel.receivedTransferFiles.collectAsStateWithLifecycle()
    val isGestureBusy by viewModel.isGestureBusy.collectAsStateWithLifecycle()
    val allDayRecordingEnabled by viewModel.allDayRecordingEnabled.collectAsStateWithLifecycle()
    val allDaySummaryHour by viewModel.allDaySummaryHour.collectAsStateWithLifecycle()
    val allDaySummaryMinute by viewModel.allDaySummaryMinute.collectAsStateWithLifecycle()
    val allDaySummaryLanguage by viewModel.allDaySummaryLanguage.collectAsStateWithLifecycle()
    val allDaySummaryEmail by viewModel.allDaySummaryEmail.collectAsStateWithLifecycle()
    val allDayNoiseDb by viewModel.allDayNoiseDb.collectAsStateWithLifecycle()
    val allDayLogs by viewModel.allDayLogs.collectAsStateWithLifecycle()
    val dailySummaryOutput by viewModel.dailySummaryOutput.collectAsStateWithLifecycle()
    val isDailySummaryBusy by viewModel.isDailySummaryBusy.collectAsStateWithLifecycle()

    val isOwnerAdmin by viewModel.isOwnerAdmin.collectAsStateWithLifecycle()
    val canClaimMasterOwner by viewModel.canClaimMasterOwner.collectAsStateWithLifecycle()
    val isMasterBusy by viewModel.isMasterBusy.collectAsStateWithLifecycle()
    val isMasterVoiceListening by viewModel.isMasterVoiceListening.collectAsStateWithLifecycle()
    val masterAiDraft by viewModel.masterAiDraft.collectAsStateWithLifecycle()
    val allSkills by viewModel.allSkills.collectAsStateWithLifecycle()
    val allAccounts by viewModel.allAccounts.collectAsStateWithLifecycle()
    val cloudFeatureRequests by viewModel.allFeatureRequests.collectAsStateWithLifecycle()
    val localFeatureQueue by viewModel.localFeatureQueue.collectAsStateWithLifecycle()
    val auditLogs by viewModel.auditLogs.collectAsStateWithLifecycle()

    val isCloudSignedIn = viewModel.isCloudAuthenticated

    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(externalVoiceTrigger) {
        if (externalVoiceTrigger > 0) {
            selectedTab = 0
            viewModel.startVoiceCapture()
            onConsumeVoiceTrigger()
        }
    }

    LaunchedEffect(externalPhotoTrigger) {
        if (externalPhotoTrigger > 0) {
            selectedTab = 0
        }
    }

    LaunchedEffect(externalGestureTrigger) {
        if (externalGestureTrigger > 0) {
            selectedTab = 2
        }
    }

    LaunchedEffect(externalDiaryTrigger) {
        if (externalDiaryTrigger > 0) {
            selectedTab = 2
        }
    }

    LaunchedEffect(statusBanner) {
        statusBanner?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.clearBanner()
        }
    }

    if (selectedTab != 0) {
        BackHandler {
            selectedTab = 0
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useNavRail = maxWidth >= 600.dp

        Scaffold(
            contentWindowInsets = WindowInsets.safeDrawing,
            snackbarHost = {
                SnackbarHost(
                    hostState = snackbarHostState,
                    modifier = Modifier.padding(bottom = 78.dp)
                )
            },
            bottomBar = {
                if (!useNavRail) {
                    NavigationBar {
                        NavigationBarItem(
                            selected = selectedTab == 0,
                            onClick = { selectedTab = 0 },
                            icon = {
                                Icon(
                                    if (selectedTab == 0) Icons.Filled.AutoAwesome else Icons.Outlined.AutoAwesome,
                                    contentDescription = "Hey Shuvo Voice Assistant"
                                )
                            },
                            label = { Text("ভয়েস AI") }
                        )
                        NavigationBarItem(
                            selected = selectedTab == 1,
                            onClick = { selectedTab = 1 },
                            icon = {
                                Icon(
                                    if (selectedTab == 1) Icons.Filled.Devices else Icons.Outlined.Devices,
                                    contentDescription = "Phone, PC & Office Control"
                                )
                            },
                            label = { Text("কন্ট্রোল") }
                        )
                        NavigationBarItem(
                            selected = selectedTab == 2,
                            onClick = { selectedTab = 2 },
                            icon = {
                                Icon(
                                    if (selectedTab == 2) Icons.Filled.Widgets else Icons.Outlined.Widgets,
                                    contentDescription = "Smart Daily Hub"
                                )
                            },
                            label = { Text("স্মার্ট হাব") }
                        )
                        NavigationBarItem(
                            selected = selectedTab == 3,
                            onClick = { selectedTab = 3 },
                            icon = {
                                Icon(
                                    if (selectedTab == 3) Icons.Filled.Palette else Icons.Outlined.Palette,
                                    contentDescription = "Custom Theme & Vault"
                                )
                            },
                            label = { Text("থিম ও ভল্ট") }
                        )
                        NavigationBarItem(
                            selected = selectedTab == 4,
                            onClick = { selectedTab = 4 },
                            icon = {
                                Icon(
                                    if (selectedTab == 4) Icons.Filled.AdminPanelSettings else Icons.Outlined.AdminPanelSettings,
                                    contentDescription = "Master App Control"
                                )
                            },
                            label = { Text("মাস্টার") }
                        )
                    }
                }
            }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                Row(modifier = Modifier.fillMaxSize()) {
                    if (useNavRail) {
                        NavigationRail {
                            NavigationRailItem(
                                selected = selectedTab == 0,
                                onClick = { selectedTab = 0 },
                                icon = { Icon(Icons.Filled.AutoAwesome, contentDescription = "Voice AI") },
                                label = { Text("ভয়েস AI") }
                            )
                            NavigationRailItem(
                                selected = selectedTab == 1,
                                onClick = { selectedTab = 1 },
                                icon = { Icon(Icons.Filled.Devices, contentDescription = "Control") },
                                label = { Text("কন্ট্রোল") }
                            )
                            NavigationRailItem(
                                selected = selectedTab == 2,
                                onClick = { selectedTab = 2 },
                                icon = { Icon(Icons.Filled.Widgets, contentDescription = "Smart Hub") },
                                label = { Text("স্মার্ট হাব") }
                            )
                            NavigationRailItem(
                                selected = selectedTab == 3,
                                onClick = { selectedTab = 3 },
                                icon = { Icon(Icons.Filled.Palette, contentDescription = "Theme & Vault") },
                                label = { Text("থিম ও ভল্ট") }
                            )
                            NavigationRailItem(
                                selected = selectedTab == 4,
                                onClick = { selectedTab = 4 },
                                icon = { Icon(Icons.Filled.AdminPanelSettings, contentDescription = "Master App") },
                                label = { Text("মাস্টার") }
                            )
                        }
                    }

                    Box(modifier = Modifier.weight(1f)) {
                        when (selectedTab) {
                            0 -> AssistantCommandScreen(
                                settings = settings,
                                voiceState = voiceState,
                                chatTurns = chatTurns,
                                isProcessing = isProcessing,
                                visionOutput = visionOutput,
                                canDrawOverlay = viewModel.canDrawAllDisplayOverlay(),
                                externalPhotoTrigger = externalPhotoTrigger,
                                onConsumePhotoTrigger = onConsumePhotoTrigger,
                                onSetAutoAllDisplayMode = viewModel::setAutoAllDisplayMode,
                                onSetUserLocation = viewModel::setUserHomeLocation,
                                onOpenCurrentLocationOnMap = viewModel::openCurrentLocationOnMap,
                                onNavigateToDestination = viewModel::navigateToDestinationOnMap,
                                onRequestOverlayPermission = viewModel::requestAllDisplayOverlayPermission,
                                onRefreshPermissions = viewModel::refreshLocalState,
                                onSendCommand = viewModel::sendCommand,
                                onStartVoice = { viewModel.startVoiceCapture() },
                                onStartQuickManualVoice = {
                                    viewModel.startVoiceCapture(
                                        forceDirectAiMic = false,
                                        continuousOverride = false
                                    )
                                },
                                onStopVoice = viewModel::stopVoiceCapture,
                                onCancelVoice = viewModel::cancelVoiceCapture,
                                onCancelProcessing = viewModel::cancelActiveProcessing,
                                onStopSpeaking = viewModel::stopSpeaking,
                                onClearVoiceError = viewModel::clearVoiceError,
                                onConfirmTurn = viewModel::confirmAndExecutePendingTurn,
                                onOpenGeneratedFile = viewModel::openGeneratedFile,
                                onInspectImage = viewModel::inspectEquipmentImage,
                                onClearVision = viewModel::clearVisionOutput,
                                onSpeakMessage = viewModel::speakMessage,
                                onClearChat = viewModel::clearConversation,
                                onTypingStateChanged = viewModel::setTypingActive,
                                onSelectChatHeadPhoto = viewModel::selectChatHeadAvatarPhoto,
                                onSaveAvatarPhotoIndex = viewModel::saveAvatarPhotoIndex,
                                onSetUseCustomPortraitAvatar = viewModel::setUseCustomPortraitAvatar,
                                isBanglaTtsMissing = viewModel.isBanglaTtsMissing(),
                                onInstallBanglaTts = viewModel::openInstallBanglaTtsData
                            )

                            1 -> VoiceSoftwareAndBusinessScreen(
                                generatedFiles = generatedFiles,
                                selfScreenTime = selfScreenTime,
                                canDrawOverlay = viewModel.canDrawAllDisplayOverlay(),
                                hasUsagePerm = viewModel.hasUsagePermission(),
                                onRequestOverlayPerm = viewModel::requestAllDisplayOverlayPermission,
                                onRequestUsagePerm = viewModel::requestUsagePermissionSettings,
                                onOpenAccessibilityPerm = viewModel::openVoiceAccessibilitySettings,
                                onRefreshLocal = viewModel::refreshLocalState,
                                onOpenGeneratedFile = viewModel::openGeneratedFile,
                                onReadFilePreview = viewModel::readGeneratedFilePreview,
                                ledgerEntries = ledgerEntries,
                                onDeleteLedgerEntry = viewModel::deleteTallyEntry,
                                myFeatureRequests = myFeatureRequests,
                                onOpenBatteryOptimizationSettings = viewModel::openBatteryOptimizationSettings,
                                onInstallBanglaTts = viewModel::openInstallBanglaTtsData
                            )

                            2 -> SmartHubScreen(
                                externalGestureTrigger = externalGestureTrigger,
                                externalDiaryTrigger = externalDiaryTrigger,
                                onConsumeGestureTrigger = onConsumeGestureTrigger,
                                onConsumeDiaryTrigger = onConsumeDiaryTrigger,
                                isCloudSignedIn = isCloudSignedIn,
                                gesturePairingCode = gesturePairingCode,
                                gestureStatusMessage = gestureStatusMessage,
                                isGestureBusy = isGestureBusy,
                                pendingIncomingTransfers = pendingIncomingTransfers,
                                receivedTransferFiles = receivedTransferFiles,
                                generatedFiles = generatedFiles,
                                birthdays = birthdays,
                                onUpdatePairingCode = viewModel::updateGesturePairingCode,
                                onGrabFileByFist = viewModel::grabFileByFistGesture,
                                onDropFileByOpenPalm = viewModel::dropGrabbedFileByOpenPalm,
                                onOpenFile = viewModel::openGeneratedFile,
                                onOpenSignInModal = onOpenSignInModal,
                                onSaveBirthday = viewModel::saveBirthdayReminder,
                                onDeleteBirthday = viewModel::deleteBirthdayReminder,
                                onSendBirthdayWishNow = viewModel::sendBirthdayWishNow,
                                onGenerateAiWish = viewModel::generateAiBirthdayWishText,
                                allDayRecordingEnabled = allDayRecordingEnabled,
                                allDaySummaryHour = allDaySummaryHour,
                                allDaySummaryMinute = allDaySummaryMinute,
                                allDaySummaryLanguage = allDaySummaryLanguage,
                                allDaySummaryEmail = allDaySummaryEmail,
                                allDayNoiseDb = allDayNoiseDb,
                                allDayLogs = allDayLogs,
                                dailySummaryOutput = dailySummaryOutput,
                                isDailySummaryBusy = isDailySummaryBusy,
                                onToggleAllDayRecording = viewModel::toggleAllDayRecording,
                                onSaveAllDaySchedule = viewModel::saveAllDaySummarySchedule,
                                onGenerateDailySummaryNow = viewModel::generateAndSendDailySummaryNow,
                                smartWriterOutput = smartWriterOutput,
                                isSmartWriterBusy = isSmartWriterBusy,
                                onRunSmartWriter = viewModel::runSmartWriterOrTranslator,
                                onClearSmartWriter = viewModel::clearSmartWriterOutput,
                                onSpeakText = viewModel::speakMessage,
                                onDialEmergency = viewModel::dialEmergencyNumber
                            )

                            3 -> PrivacyVaultAndArchitectureScreen(
                                isCloudAuthenticated = isCloudSignedIn,
                                cloudEmail = userEmail,
                                settings = settings,
                                memories = memories,
                                taskLogs = taskHistory,
                                onDeleteMemory = viewModel::deletePersonalMemory,
                                onWipeMemories = viewModel::wipeAllPersonalMemory,
                                onUndoTask = viewModel::undoReversibleTask,
                                onSelectPersona = viewModel::selectPersonaFace,
                                onSelectMode = viewModel::selectUserMode,
                                onSelectThemePalette = viewModel::selectThemePalette,
                                onSetCustomAccentHue = viewModel::setCustomAccentHue,
                                onSetTextScale = viewModel::setTextScale,
                                onToggleDarkHud = viewModel::toggleDarkHud,
                                onToggleAutoAllDisplay = viewModel::setAutoAllDisplayMode,
                                onSelectChatHeadPhoto = viewModel::selectChatHeadAvatarPhoto,
                                onResetChatHeadPhoto = viewModel::resetChatHeadAvatarPhoto,
                                onOpenSignInSheet = onOpenSignInModal,
                                onSignOut = {
                                    signOutUser(credentialManager, onSignOutComplete = {}, scope = scope)
                                }
                            )

                            4 -> MasterControlScreen(
                                settings = settings,
                                isCloudSignedIn = isCloudSignedIn,
                                isOwnerAdmin = isOwnerAdmin,
                                canClaimMasterOwner = canClaimMasterOwner,
                                isMasterBusy = isMasterBusy,
                                isMasterVoiceListening = isMasterVoiceListening,
                                masterAiDraft = masterAiDraft,
                                allSkills = (allSkills + settings.localMasterSkills).distinctBy { it.name },
                                allFeatureRequests = cloudFeatureRequests + localFeatureQueue,
                                auditLogs = auditLogs,
                                onOpenSignInSheet = onOpenSignInModal,
                                onClaimMasterOwner = viewModel::claimMasterOwner,
                                onStartMasterVoice = viewModel::startMasterVoiceCommand,
                                onStopMasterVoice = viewModel::stopMasterVoiceCommand,
                                onRunMasterCommand = viewModel::runMasterVoiceOrTextCommand,
                                onApplyMasterDraft = viewModel::applyMasterDraftToCustomerApp,
                                onDismissMasterDraft = viewModel::dismissMasterDraft,
                                onSaveAndApplyConfig = viewModel::saveAndApplyCustomerConfig,
                                onToggleSkill = viewModel::toggleMasterSkill,
                                onDeleteSkill = viewModel::deleteMasterSkill,
                                onResolveFeatureRequest = viewModel::resolveFeatureRequestWithMasterAi,
                                onInviteCircleMember = viewModel::inviteCircleMember,
                                onSaveAutoEmailConfig = viewModel::saveMasterAutoEmailConfig,
                                onTriggerAutoEmailCheck = { viewModel.evaluateAutomaticCustomerEmails(manualTrigger = true) },
                                allAccounts = allAccounts,
                                onSavePricingConfig = viewModel::saveMasterPricingConfig,
                                onSetGlobalPaidMode = viewModel::setGlobalPaidModeEnabled,
                                onSetCurrentUserPaid = viewModel::setCurrentUserPaidStatus,
                                onUpdateMemberTier = viewModel::updateMemberSubscriptionTier,
                                onGrantBonusFreeUses = viewModel::grantBonusFreeUses,
                                onUpdateMasterPassword = viewModel::updateMasterPassword
                            )
                        }
                    }
                }
            }
        }
    }
}
