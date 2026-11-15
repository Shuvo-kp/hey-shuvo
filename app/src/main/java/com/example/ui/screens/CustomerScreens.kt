package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ColorLens
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.QueryStats
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.core.actions.SelfScreenTimeItem
import com.example.core.preferences.AiPersonaFace
import com.example.core.preferences.AppThemePalette
import com.example.core.preferences.AssistantSettings
import com.example.core.preferences.UserAccessibilityMode
import com.example.core.voice.SpeechRecognitionState
import com.example.data.cloud.model.FeatureRequest
import com.example.data.local.LedgerEntryEntity
import com.example.data.local.PrivateMemoryEntity
import com.example.data.local.TaskLogEntity
import com.example.feature.customer.ChatTurn
import androidx.compose.ui.res.painterResource
import com.example.ui.components.AndroidAvatarCatalog
import com.example.ui.components.AnimatedAiFaceCanvas
import com.example.ui.components.CyberAndroidAvatar
import com.example.ui.components.PassportAndroidAvatarCard
import com.example.ui.components.bubbleTouchEffect
import java.io.File
import java.util.Locale
import kotlin.math.abs

private val POPULAR_BD_LOCATIONS = listOf(
    "Dhaka, Bangladesh" to "ঢাকা",
    "Mirpur, Dhaka, Bangladesh" to "মিরপুর",
    "Dhanmondi, Dhaka, Bangladesh" to "ধানমন্ডি",
    "Uttara, Dhaka, Bangladesh" to "উত্তরা",
    "Gulshan, Dhaka, Bangladesh" to "গুলশান",
    "Chattogram, Bangladesh" to "চট্টগ্রাম",
    "Sylhet, Bangladesh" to "সিলেট",
    "Rajshahi, Bangladesh" to "রাজশাহী",
    "Khulna, Bangladesh" to "খুলনা",
    "Jashore, Bangladesh" to "যশোর",
    "Barishal, Bangladesh" to "বরিশাল",
    "Rangpur, Bangladesh" to "রংপুর",
    "Mymensingh, Bangladesh" to "ময়মনসিংহ",
    "Cumilla, Bangladesh" to "কুমিল্লা",
    "Gazipur, Bangladesh" to "গাজীপুর",
    "Cox's Bazar, Bangladesh" to "কক্সবাজার"
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AssistantCommandScreen(
    settings: AssistantSettings,
    voiceState: SpeechRecognitionState,
    chatTurns: List<ChatTurn>,
    isProcessing: Boolean,
    visionOutput: String,
    canDrawOverlay: Boolean,
    externalPhotoTrigger: Int = 0,
    onConsumePhotoTrigger: () -> Unit = {},
    onSetAutoAllDisplayMode: (Boolean) -> Unit,
    onSetUserLocation: (String) -> Unit = {},
    onOpenCurrentLocationOnMap: () -> Unit = {},
    onNavigateToDestination: (String) -> Unit = {},
    onRequestOverlayPermission: () -> Unit,
    onRefreshPermissions: () -> Unit,
    onSendCommand: (String) -> Unit,
    onStartVoice: () -> Unit,
    onStartQuickManualVoice: () -> Unit = onStartVoice,
    onStopVoice: () -> Unit,
    onCancelVoice: () -> Unit,
    onCancelProcessing: () -> Unit = {},
    onStopSpeaking: () -> Unit,
    onClearVoiceError: () -> Unit,
    onConfirmTurn: (String, String?) -> Unit,
    onOpenGeneratedFile: (String) -> Unit,
    onInspectImage: (android.graphics.Bitmap, String) -> Unit,
    onClearVision: () -> Unit,
    onSpeakMessage: (String) -> Unit = {},
    onClearChat: () -> Unit = {},
    onTypingStateChanged: (Boolean) -> Unit = {},
    onSelectChatHeadPhoto: (android.graphics.Bitmap) -> Unit = {},
    onSaveAvatarPhotoIndex: (Boolean, Int) -> Unit = { _, _ -> },
    onSetUseCustomPortraitAvatar: (Boolean) -> Unit = {},
    isBanglaTtsMissing: Boolean = false,
    onInstallBanglaTts: () -> Unit = {}
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val listState = rememberLazyListState()

    var commandText by rememberSaveable { mutableStateOf("") }
    var isInputFocused by remember { mutableStateOf(false) }
    var parentPinInput by remember { mutableStateOf("") }
    var previewDialogTitle by remember { mutableStateOf<String?>(null) }
    var previewDialogBody by remember { mutableStateOf<String?>(null) }
    var localMicPermNotice by remember { mutableStateOf<String?>(null) }
    var pendingEnableAutoAfterPerm by remember { mutableStateOf(false) }
    var showOverlayGuideDialog by remember { mutableStateOf(false) }
    var showQuickTextBarInAuto by rememberSaveable { mutableStateOf(false) }
    var showLocationDialog by rememberSaveable { mutableStateOf(false) }
    var customHomeInput by rememberSaveable(settings.userHomeLocation) { mutableStateOf(settings.userHomeLocation) }
    var destinationInput by rememberSaveable { mutableStateOf("") }
    val femalePhotoIndex = settings.femalePhotoIndex
    val malePhotoIndex = settings.malePhotoIndex

    var overlayAllowed by remember(canDrawOverlay) { mutableStateOf(canDrawOverlay) }

    val isActivelyTyping = isInputFocused || commandText.isNotEmpty()
    LaunchedEffect(isActivelyTyping) {
        onTypingStateChanged(isActivelyTyping)
    }

    DisposableEffect(Unit) {
        onDispose { onTypingStateChanged(false) }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                onRefreshPermissions()
                overlayAllowed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    android.provider.Settings.canDrawOverlays(context)
                } else {
                    true
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(chatTurns.size, isProcessing) {
        val totalItems = chatTurns.size + if (isProcessing) 1 else 0
        if (totalItems > 0) {
            listState.animateScrollToItem(totalItems - 1)
        }
    }

    val notificationPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ -> }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val hasAny = result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (hasAny) {
            onOpenCurrentLocationOnMap()
        } else {
            onOpenCurrentLocationOnMap()
        }
    }

    var didInitialAutoVoiceCheck by rememberSaveable { mutableStateOf(false) }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            localMicPermNotice = null
            if (pendingEnableAutoAfterPerm || settings.autoAllDisplayVoice) {
                val manualOverlayPrompt = pendingEnableAutoAfterPerm
                pendingEnableAutoAfterPerm = false
                onSetAutoAllDisplayMode(true)
                if (manualOverlayPrompt && !overlayAllowed) {
                    showOverlayGuideDialog = true
                }
            } else {
                onStartVoice()
            }
        } else {
            pendingEnableAutoAfterPerm = false
            localMicPermNotice = "মাইক্রোফোন পারমিশন (Allow Microphone) চালু করুন।"
        }
    }

    // Request mic permission once if AUTO is active and permission hasn't been granted yet;
    // when already granted, HeyShuvoViewModel starts the single persistent AudioRecord stream without race!
    LaunchedEffect(settings.autoAllDisplayVoice) {
        if (!didInitialAutoVoiceCheck && settings.autoAllDisplayVoice) {
            didInitialAutoVoiceCheck = true
            val hasMic = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            if (!hasMic) {
                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            } else {
                onSetAutoAllDisplayMode(true)
            }
        }
    }

    val chatHeadPhotoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            try {
                val bmp = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val src = ImageDecoder.createSource(context.contentResolver, uri)
                    ImageDecoder.decodeBitmap(src) { decoder, _, _ -> decoder.isMutableRequired = true }
                } else {
                    @Suppress("DEPRECATION")
                    MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
                }
                onSelectChatHeadPhoto(bmp)
            } catch (_: Exception) {
            }
        }
    }

    val customAvatarBitmap = remember(settings.chatHeadAvatarPath, settings.chatHeadAvatarUpdatedMs) {
        try {
            if (settings.chatHeadAvatarPath.isNotBlank() && File(settings.chatHeadAvatarPath).exists()) {
                BitmapFactory.decodeFile(settings.chatHeadAvatarPath)?.asImageBitmap()
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            try {
                val bmp = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val src = ImageDecoder.createSource(context.contentResolver, uri)
                    ImageDecoder.decodeBitmap(src) { decoder, _, _ -> decoder.isMutableRequired = true }
                } else {
                    @Suppress("DEPRECATION")
                    MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
                }
                onInspectImage(bmp, "Identify objects/documents, explain usage, and answer helpfully")
            } catch (_: Exception) {
            }
        }
    }

    // Automatically open Photo Picker when triggered via voice ("ফটো মোড") or Main Display overlay!
    LaunchedEffect(externalPhotoTrigger) {
        if (externalPhotoTrigger > 0) {
            photoPickerLauncher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            )
            onConsumePhotoTrigger()
        }
    }

    val triggerMicStart = {
        val hasPerm = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (hasPerm) {
            localMicPermNotice = null
            onStartVoice()
        } else {
            pendingEnableAutoAfterPerm = false
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    val activateAutoMode = {
        val hasPerm = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (hasPerm) {
            localMicPermNotice = null
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val hasNotif = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
                if (!hasNotif) {
                    notificationPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
            onSetAutoAllDisplayMode(true)
            if (!overlayAllowed) {
                showOverlayGuideDialog = true
            }
        } else {
            pendingEnableAutoAfterPerm = true
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    val requestGpsOrOpenMap = {
        val hasFine = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (hasFine || hasCoarse) {
            onOpenCurrentLocationOnMap()
        } else {
            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    if (showLocationDialog) {
        AlertDialog(
            onDismissRequest = { showLocationDialog = false },
            title = {
                Text(
                    text = "লোকেশন ও ম্যাপ নেভিগেশন",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "আপনার বর্তমান/নির্বাচিত অবস্থান নির্বাচন করুন:",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        POPULAR_BD_LOCATIONS.forEach { (engLoc, bnLabel) ->
                            val selected = settings.userHomeLocation.equals(engLoc, ignoreCase = true)
                            FilterChip(
                                selected = selected,
                                onClick = {
                                    customHomeInput = engLoc
                                    onSetUserLocation(engLoc)
                                },
                                label = { Text(bnLabel) },
                                modifier = Modifier.bubbleTouchEffect(MaterialTheme.colorScheme.primary)
                            )
                        }
                    }

                    OutlinedTextField(
                        value = customHomeInput,
                        onValueChange = { customHomeInput = it },
                        label = { Text("বর্তমান লোকেশন (শহর/এলাকা)") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("home_location_input")
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                if (customHomeInput.isNotBlank()) {
                                    onSetUserLocation(customHomeInput.trim())
                                }
                            },
                            modifier = Modifier
                                .weight(1f)
                                .bubbleTouchEffect(MaterialTheme.colorScheme.primary)
                        ) {
                            Text("লোকেশন সেভ")
                        }
                        Button(
                            onClick = {
                                if (customHomeInput.isNotBlank()) {
                                    onSetUserLocation(customHomeInput.trim())
                                }
                                showLocationDialog = false
                                requestGpsOrOpenMap()
                            },
                            modifier = Modifier
                                .weight(1f)
                                .bubbleTouchEffect(MaterialTheme.colorScheme.primary)
                        ) {
                            Icon(Icons.Filled.LocationOn, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("ম্যাপ দেখুন")
                        }
                    }

                    Text(
                        text = "যেকোনো গন্তব্যে নেভিগেশন:",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    OutlinedTextField(
                        value = destinationInput,
                        onValueChange = { destinationInput = it },
                        placeholder = { Text("যেমন: ধানমন্ডি / কক্সবাজার / উত্তরা") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("destination_navigation_input")
                    )
                    Button(
                        onClick = {
                            val dest = destinationInput.trim()
                            showLocationDialog = false
                            onNavigateToDestination(dest)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .bubbleTouchEffect(MaterialTheme.colorScheme.secondary)
                            .testTag("start_map_navigation_button")
                    ) {
                        Icon(Icons.Filled.Navigation, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("গন্তব্যে ম্যাপ নেভিগেশন চালু করুন")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showLocationDialog = false }) {
                    Text("বন্ধ করুন")
                }
            }
        )
    }

    if (showOverlayGuideDialog && !overlayAllowed) {
        AlertDialog(
            onDismissRequest = { showOverlayGuideDialog = false },
            title = {
                Text(
                    "মেইন ডিসপ্লেতে ভয়েস কন্ট্রোল (All Display)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    "হোম স্ক্রিন বা অন্য যেকোনো অ্যাপের উপর থেকে সরাসরি ভয়েস ও টাচে Auto Mode, Manual Mode, Location এবং Photo Mode ব্যবহার করতে একবার 'Display over other apps' Allow করে দিন।",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showOverlayGuideDialog = false
                        onRequestOverlayPermission()
                    },
                    modifier = Modifier
                        .bubbleTouchEffect(MaterialTheme.colorScheme.primary)
                        .testTag("dialog_grant_overlay_button")
                ) {
                    Text("এখনই Allow করুন")
                }
            },
            dismissButton = {
                TextButton(onClick = { showOverlayGuideDialog = false }) {
                    Text("পরে করবো")
                }
            }
        )
    }

    val submitTypedCommand = {
        val textToSend = commandText.trim()
        if (textToSend.isNotBlank()) {
            commandText = ""
            isInputFocused = false
            onTypingStateChanged(false)
            keyboardController?.hide()
            onSendCommand(textToSend)
        }
    }

    if (previewDialogTitle != null && previewDialogBody != null) {
        AlertDialog(
            onDismissRequest = {
                previewDialogTitle = null
                previewDialogBody = null
            },
            title = { Text(previewDialogTitle.orEmpty(), style = MaterialTheme.typography.titleMedium) },
            text = {
                SelectionContainer {
                    Column(
                        modifier = Modifier
                            .heightIn(max = 380.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text(
                            text = previewDialogBody.orEmpty(),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    previewDialogTitle = null
                    previewDialogBody = null
                }) {
                    Text("বন্ধ করুন")
                }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        // 1. Top Control Bar: Identity + Instant AUTO (All Display) vs MANUAL Selector + Location Pill
        val autoSelected = settings.autoAllDisplayVoice
        Card(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            modifier = Modifier
                .fillMaxWidth()
                .border(
                    width = 1.dp,
                    color = when {
                        voiceState.isListening -> Color(0xFF00E6B4)
                        autoSelected -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.outlineVariant
                    },
                    shape = RoundedCornerShape(18.dp)
                )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(30.dp)
                                .bubbleTouchEffect(MaterialTheme.colorScheme.primary)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surface)
                                .border(
                                    width = 1.5.dp,
                                    color = when {
                                        voiceState.isListening -> Color(0xFF00E6B4)
                                        voiceState.isSpeaking -> MaterialTheme.colorScheme.primary
                                        autoSelected -> Color(0xFF00E676)
                                        else -> MaterialTheme.colorScheme.secondary
                                    },
                                    shape = CircleShape
                                )
                                .clickable {
                                    chatHeadPhotoPickerLauncher.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                    )
                                }
                                .testTag("header_chat_head_avatar_button")
                        ) {
                            if (customAvatarBitmap != null && settings.useCustomPortraitAvatar) {
                                Image(
                                    bitmap = customAvatarBitmap,
                                    contentDescription = "Messenger Chat Head Custom Photo",
                                    contentScale = ContentScale.Crop,
                                    alignment = Alignment.TopCenter,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .clip(CircleShape)
                                )
                            } else {
                                val headerFemale = settings.voiceProfile.equals("FEMALE", ignoreCase = true)
                                val headerMax = if (headerFemale) AndroidAvatarCatalog.femaleDrawables.size else AndroidAvatarCatalog.maleDrawables.size
                                val headerRawIdx = if (headerFemale) femalePhotoIndex else malePhotoIndex
                                Image(
                                    painter = painterResource(
                                        id = AndroidAvatarCatalog.getDrawableForProfile(settings.voiceProfile, Math.floorMod(headerRawIdx, headerMax))
                                    ),
                                    contentDescription = "${AndroidAvatarCatalog.getPersonaName(settings.voiceProfile)} Avatar",
                                    contentScale = ContentScale.Crop,
                                    alignment = Alignment.TopCenter,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .clip(CircleShape)
                                )
                            }
                        }
                        Text(
                            text = "HEY SHUVO",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    var showPlanTableDialog by rememberSaveable { mutableStateOf(false) }
                    val isCustomerPaid = !settings.globalPaidModeEnabled || (
                        settings.currentUserIsPaid &&
                            (settings.currentUserPaidUntilMs == 0L || System.currentTimeMillis() <= settings.currentUserPaidUntilMs)
                        )

                    if (showPlanTableDialog) {
                        AlertDialog(
                            onDismissRequest = { showPlanTableDialog = false },
                            title = {
                                Text(
                                    text = "Free বনাম Paid প্ল্যান টেবিল",
                                    fontWeight = FontWeight.Bold
                                )
                            },
                            text = {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(
                                        text = if (isCustomerPaid) {
                                            "আপনার বর্তমান প্ল্যান: PAID (সম্পূর্ণ আনলিমিটেড)"
                                        } else {
                                            "আপনার বর্তমান প্ল্যান: FREE (আজকের ব্যবহার: ${settings.dailyUsageCount}/${settings.freeDailyLimit})"
                                        },
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isCustomerPaid) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
                                    )
                                    androidx.compose.material3.HorizontalDivider()
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text("সুবিধা", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1.2f))
                                        Text("FREE", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                                        Text("PAID", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                                    }
                                    androidx.compose.material3.HorizontalDivider()
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text("দৈনিক ব্যবহার", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1.2f))
                                        Text("${settings.freeDailyLimit} বার/দিন", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                                        Text("আনলিমিটেড", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                    }
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text("ভয়েস ও ক্যামেরা", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1.2f))
                                        Text("সীমিত", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                                        Text("আনলিমিটেড", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                    }
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text("মাসিক ফি", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1.2f))
                                        Text("৳০ (ফ্রি)", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                                        Text("৳${settings.monthlyPaidFeeBdt}/মাস", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                    }
                                }
                            },
                            confirmButton = {
                                TextButton(onClick = { showPlanTableDialog = false }) {
                                    Text("ঠিক আছে")
                                }
                            }
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        OutlinedButton(
                            onClick = { showPlanTableDialog = true },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .height(36.dp)
                                .testTag("customer_plan_badge")
                        ) {
                            Text(
                                text = if (isCustomerPaid) "PAID" else "FREE",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = if (isCustomerPaid) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
                            )
                        }
                        // AUTO (All Display) High-Contrast Pill with Touch Bubble Animation
                        Button(
                            onClick = { activateAutoMode() },
                            contentPadding = PaddingValues(horizontal = 11.dp, vertical = 6.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (autoSelected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.surface,
                                contentColor = if (autoSelected) MaterialTheme.colorScheme.onPrimary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .height(40.dp)
                                .bubbleTouchEffect(
                                    bubbleColor = MaterialTheme.colorScheme.primary,
                                    pressedScale = 0.92f
                                )
                                .border(
                                    width = if (autoSelected) 1.5.dp else 1.dp,
                                    color = if (autoSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                    shape = RoundedCornerShape(12.dp)
                                )
                                .testTag("mode_auto_all_display")
                        ) {
                            if (autoSelected) {
                                Icon(
                                    imageVector = Icons.Filled.Check,
                                    contentDescription = null,
                                    modifier = Modifier.size(15.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                            }
                            Text(
                                text = "AUTO",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        // MANUAL (In-App) High-Contrast Pill with Touch Bubble Animation
                        Button(
                            onClick = { onSetAutoAllDisplayMode(false) },
                            contentPadding = PaddingValues(horizontal = 11.dp, vertical = 6.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (!autoSelected) MaterialTheme.colorScheme.secondary
                                else MaterialTheme.colorScheme.surface,
                                contentColor = if (!autoSelected) MaterialTheme.colorScheme.onSecondary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .height(40.dp)
                                .bubbleTouchEffect(
                                    bubbleColor = MaterialTheme.colorScheme.secondary,
                                    pressedScale = 0.92f
                                )
                                .border(
                                    width = if (!autoSelected) 1.5.dp else 1.dp,
                                    color = if (!autoSelected) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outlineVariant,
                                    shape = RoundedCornerShape(12.dp)
                                )
                                .testTag("mode_manual_in_app")
                        ) {
                            if (!autoSelected) {
                                Icon(
                                    imageVector = Icons.Filled.Check,
                                    contentDescription = null,
                                    modifier = Modifier.size(15.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                            }
                            Text(
                                text = "MANUAL",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        if (voiceState.isSpeaking) {
                            IconButton(
                                onClick = onStopSpeaking,
                                modifier = Modifier
                                    .size(36.dp)
                                    .bubbleTouchEffect(MaterialTheme.colorScheme.secondary)
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.VolumeOff,
                                    contentDescription = "Mute Speech",
                                    tint = MaterialTheme.colorScheme.secondary
                                )
                            }
                        }

                        if (chatTurns.isNotEmpty() || isProcessing) {
                            IconButton(
                                onClick = onClearChat,
                                modifier = Modifier
                                    .size(36.dp)
                                    .bubbleTouchEffect(MaterialTheme.colorScheme.primary)
                                    .testTag("clear_conversation_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.DeleteSweep,
                                    contentDescription = "Clear Conversation",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                Text(
                    text = when {
                        voiceState.isMicZeroSignal -> "● মাইকে অডিও আসছে না (Chrome Mic Allow দিন)"
                        voiceState.isListening && voiceState.hasDetectedVoiceActivity -> "● কথা শোনা হচ্ছে..."
                        voiceState.isListening && autoSelected -> "● AUTO ভয়েস সক্রিয় (মাইক চালু)"
                        voiceState.isSpeaking -> "● Hey Shuvo কথা বলছে..."
                        autoSelected -> "● AUTO ভয়েস সক্রিয়"
                        else -> "● MANUAL মোড সক্রিয়"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = when {
                        voiceState.isMicZeroSignal -> MaterialTheme.colorScheme.error
                        autoSelected -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.secondary
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        // Live Master App Announcement / Applied Update Banner
        if (settings.masterAnnouncementBanner.isNotBlank()) {
            Spacer(modifier = Modifier.height(6.dp))
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("customer_master_live_banner")
            ) {
                Text(
                    text = "✦ ${settings.masterAnnouncementBanner}",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        }

        // Non-blocking All-Display Overlay Grant Strip when AUTO is active and Draw-Over-Apps isn't enabled yet
        if (autoSelected && !overlayAllowed) {
            Spacer(modifier = Modifier.height(6.dp))
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Layers,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "মেইন ডিসপ্লেতে ভাসমান ভয়েস ও কন্ট্রোল চালু করতে Allow চাপুন",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                    TextButton(
                        onClick = onRequestOverlayPermission,
                        modifier = Modifier
                            .bubbleTouchEffect(MaterialTheme.colorScheme.primary)
                            .testTag("grant_overlay_button")
                    ) {
                        Text("Allow", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        if (isBanglaTtsMissing) {
            Spacer(modifier = Modifier.height(6.dp))
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "বাংলা স্পষ্ট ভয়েসের জন্য Google Bangla TTS প্যাক ডাউনলোড করুন",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        onClick = onInstallBanglaTts,
                        modifier = Modifier
                            .bubbleTouchEffect(MaterialTheme.colorScheme.secondary)
                            .testTag("install_bangla_tts_button")
                    ) {
                        Text("ইনস্টল", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // Permission Notice Strip (only when user explicitly denied permission or mic hardware blocked)
        val activeVoiceNotice = localMicPermNotice ?: voiceState.errorMessage
        if (!activeVoiceNotice.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(6.dp))
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = activeVoiceNotice,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = {
                            localMicPermNotice = null
                            onClearVoiceError()
                        },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(Icons.Filled.Close, contentDescription = "Dismiss notice")
                    }
                }
            }
        }

        // Photo Vision Output Card (when used)
        if (visionOutput.isNotBlank()) {
            Spacer(modifier = Modifier.height(6.dp))
            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("ফটো ভিশন বিশ্লেষণ", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                        IconButton(onClick = onClearVision, modifier = Modifier.size(26.dp)) {
                            Icon(Icons.Filled.Close, contentDescription = "Close Vision")
                        }
                    }
                    Text(
                        text = visionOutput,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .heightIn(max = 120.dp)
                            .verticalScroll(rememberScrollState())
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        val activelyCapturingUtterance = voiceState.isListening && (!autoSelected || voiceState.hasDetectedVoiceActivity)

        val isFemale = settings.voiceProfile.equals("FEMALE", ignoreCase = true)
        val maxPhotos = if (isFemale) AndroidAvatarCatalog.femaleDrawables.size else AndroidAvatarCatalog.maleDrawables.size
        val activePhotoIndex = (if (isFemale) femalePhotoIndex else malePhotoIndex) % maxPhotos
        val cycleAvatarPhoto = {
            if (customAvatarBitmap != null && !settings.useCustomPortraitAvatar) {
                val nextIdx = activePhotoIndex + 1
                if (nextIdx >= maxPhotos) {
                    onSaveAvatarPhotoIndex(isFemale, 0)
                    onSetUseCustomPortraitAvatar(true)
                } else {
                    onSaveAvatarPhotoIndex(isFemale, nextIdx)
                }
            } else if (customAvatarBitmap != null && settings.useCustomPortraitAvatar) {
                onSetUseCustomPortraitAvatar(false)
                onSaveAvatarPhotoIndex(isFemale, 0)
            } else {
                onSaveAvatarPhotoIndex(isFemale, (activePhotoIndex + 1) % maxPhotos)
            }
        }
        val cycleVoiceCommand = {
            val nextVoiceCmd = when {
                settings.voiceProfile.equals("FEMALE", ignoreCase = true) -> "Male voice"
                settings.voiceProfile.equals("MALE", ignoreCase = true) -> "Deep voice"
                else -> "Female voice"
            }
            com.example.core.service.HeyShuvoVoiceCoordinator.handleVoiceProfileSwitchIfRequested(
                context,
                nextVoiceCmd
            )
        }

        // 2. Persistent Cybernetic Android Stage (Chloe for Female, Connor for Male/Deep)
        // Guaranteed to remain in full passport portrait proportions in BOTH AUTO and MANUAL modes!
        if (chatTurns.isEmpty() && !isProcessing) {
            BoxWithConstraints(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                val targetCardModifier = if (maxWidth / maxHeight.coerceAtLeast(1.dp) > 0.78f) {
                    Modifier
                        .fillMaxHeight(0.95f)
                        .aspectRatio(0.74f)
                } else {
                    Modifier
                        .fillMaxWidth(0.88f)
                        .aspectRatio(0.74f)
                }
                PassportAndroidAvatarCard(
                    voiceProfile = settings.voiceProfile,
                    isListening = voiceState.isListening,
                    isSpeaking = voiceState.isSpeaking,
                    rmsDb = voiceState.rmsDb,
                    photoIndex = activePhotoIndex,
                    autoCutProgress = voiceState.autoCutProgress,
                    customAvatarBitmap = customAvatarBitmap,
                    useCustomPortraitAvatar = settings.useCustomPortraitAvatar,
                    onPhotoCycle = { cycleAvatarPhoto() },
                    onVoiceProfileToggle = { cycleVoiceCommand() },
                    onCardClick = {
                        isInputFocused = false
                        onTypingStateChanged(false)
                        keyboardController?.hide()
                        when {
                            activelyCapturingUtterance -> onStopVoice()
                            voiceState.isSpeaking -> onStopSpeaking()
                            autoSelected -> {
                                val hasPerm = ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.RECORD_AUDIO
                                ) == PackageManager.PERMISSION_GRANTED
                                if (hasPerm) onStartQuickManualVoice()
                                else micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                            else -> triggerMicStart()
                        }
                    },
                    modifier = targetCardModifier
                )
            }
        } else {
            // Active Conversation / Manual Mode: Full passport portrait stays cleanly proportioned above chat!
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                val cardWidthModifier = if (isInputFocused) {
                    Modifier.widthIn(min = 108.dp, max = 128.dp)
                } else {
                    Modifier.widthIn(min = 148.dp, max = 180.dp)
                }
                PassportAndroidAvatarCard(
                    voiceProfile = settings.voiceProfile,
                    isListening = voiceState.isListening,
                    isSpeaking = voiceState.isSpeaking,
                    rmsDb = voiceState.rmsDb,
                    photoIndex = activePhotoIndex,
                    autoCutProgress = voiceState.autoCutProgress,
                    customAvatarBitmap = customAvatarBitmap,
                    useCustomPortraitAvatar = settings.useCustomPortraitAvatar,
                    onPhotoCycle = { cycleAvatarPhoto() },
                    onVoiceProfileToggle = { cycleVoiceCommand() },
                    onCardClick = {
                        when {
                            activelyCapturingUtterance -> onStopVoice()
                            voiceState.isSpeaking -> onStopSpeaking()
                            autoSelected -> {
                                val hasPerm = ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.RECORD_AUDIO
                                ) == PackageManager.PERMISSION_GRANTED
                                if (hasPerm) onStartQuickManualVoice()
                                else micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                            else -> triggerMicStart()
                        }
                    },
                    modifier = cardWidthModifier.aspectRatio(0.74f)
                )
            }

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(chatTurns, key = { it.id }) { turn ->
                    Card(
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = when {
                                turn.isError -> MaterialTheme.colorScheme.errorContainer
                                turn.isUser -> MaterialTheme.colorScheme.primaryContainer
                                else -> MaterialTheme.colorScheme.surfaceVariant
                            }
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = if (turn.isUser) "YOU" else "HEY SHUVO",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold
                                )
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(
                                        text = listOfNotNull(turn.languageBadge, turn.taskId).joinToString(" • "),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.secondary
                                    )
                                    if (!turn.isUser && !turn.isError) {
                                        IconButton(
                                            onClick = { onSpeakMessage(turn.message) },
                                            modifier = Modifier
                                                .size(28.dp)
                                                .bubbleTouchEffect(MaterialTheme.colorScheme.primary)
                                        ) {
                                            Icon(
                                                imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                                                contentDescription = "Read Aloud",
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            SelectionContainer {
                                Text(
                                    text = turn.message,
                                    style = MaterialTheme.typography.bodyLarge
                                )
                            }

                            if (turn.isError && !turn.retryPrompt.isNullOrBlank()) {
                                Spacer(modifier = Modifier.height(8.dp))
                                OutlinedButton(
                                    onClick = { onSendCommand(turn.retryPrompt) },
                                    modifier = Modifier.bubbleTouchEffect(MaterialTheme.colorScheme.primary)
                                ) {
                                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("আবার চেষ্টা করুন")
                                }
                            }

                            if (turn.steps.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(8.dp))
                                turn.steps.forEach { step ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 2.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = if (turn.confirmed) Icons.Filled.CheckCircle else Icons.Filled.WarningAmber,
                                            contentDescription = "Step Status",
                                            tint = if (turn.confirmed) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.secondary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = "${step.stepIndex}. ${step.actionTitle}",
                                            style = MaterialTheme.typography.bodyMedium,
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                }
                            }

                            if (turn.outcomes.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(8.dp))
                                turn.outcomes.forEach { out ->
                                    Card(
                                        shape = RoundedCornerShape(12.dp),
                                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 3.dp)
                                    ) {
                                        Column(modifier = Modifier.padding(10.dp)) {
                                            Text(
                                                text = out.summary,
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.primary,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                            if (!out.previewBody.isNullOrBlank() || !out.generatedFilePath.isNullOrBlank()) {
                                                Spacer(modifier = Modifier.height(6.dp))
                                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                    if (!out.previewBody.isNullOrBlank()) {
                                                        OutlinedButton(
                                                            onClick = {
                                                                previewDialogTitle = out.previewTitle ?: "Preview"
                                                                previewDialogBody = out.previewBody
                                                            },
                                                            modifier = Modifier.bubbleTouchEffect(MaterialTheme.colorScheme.primary)
                                                        ) {
                                                            Icon(Icons.Filled.Visibility, contentDescription = null, modifier = Modifier.size(16.dp))
                                                            Spacer(modifier = Modifier.width(4.dp))
                                                            Text("প্রিভিউ")
                                                        }
                                                    }
                                                    if (!out.generatedFilePath.isNullOrBlank()) {
                                                        Button(
                                                            onClick = { onOpenGeneratedFile(out.generatedFilePath) },
                                                            modifier = Modifier.bubbleTouchEffect(MaterialTheme.colorScheme.primary)
                                                        ) {
                                                            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                                                            Spacer(modifier = Modifier.width(4.dp))
                                                            Text("খুলুন / শেয়ার")
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            if (turn.awaitingConfirmation) {
                                Spacer(modifier = Modifier.height(10.dp))
                                if (settings.userMode == UserAccessibilityMode.CHILDREN) {
                                    OutlinedTextField(
                                        value = parentPinInput,
                                        onValueChange = { parentPinInput = it },
                                        label = { Text("Parent PIN দিন (ডিফল্ট 2580)") },
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                }
                                Button(
                                    onClick = { onConfirmTurn(turn.id, parentPinInput) },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .bubbleTouchEffect(MaterialTheme.colorScheme.primary)
                                        .testTag("confirm_sensitive_task_${turn.id}")
                                ) {
                                    Text("অনুমোদন ও সম্পন্ন করুন")
                                }
                            }
                        }
                    }
                }

                if (isProcessing) {
                    item(key = "processing_indicator") {
                        Card(
                            shape = RoundedCornerShape(14.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("উত্তর তৈরি হচ্ছে...", style = MaterialTheme.typography.bodyMedium)
                                    TextButton(
                                        onClick = onCancelProcessing,
                                        modifier = Modifier
                                            .bubbleTouchEffect(MaterialTheme.colorScheme.secondary)
                                            .testTag("cancel_processing_button")
                                    ) {
                                        Text("বাতিল", fontWeight = FontWeight.Bold)
                                    }
                                }
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // 3. Active Voice Capture Strip (Shown during Manual or 1-tap Quick Talk capture when not typing)
        if (voiceState.isListening && (!autoSelected || !voiceState.isContinuousAuto) && !isActivelyTyping) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp)
                    .border(1.dp, Color(0xFF00E6B4), RoundedCornerShape(16.dp))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = voiceState.partialTranscript.ifBlank { "কথা শোনা হচ্ছে..." },
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(
                            onClick = onCancelVoice,
                            modifier = Modifier.bubbleTouchEffect(MaterialTheme.colorScheme.secondary)
                        ) {
                            Text("বাতিল")
                        }
                        Button(
                            onClick = onStopVoice,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            modifier = Modifier
                                .height(36.dp)
                                .bubbleTouchEffect(MaterialTheme.colorScheme.primary)
                        ) {
                            Text("পাঠান")
                        }
                    }
                    LinearProgressIndicator(
                        progress = {
                            if (voiceState.autoCutProgress > 0.05f) voiceState.autoCutProgress
                            else (voiceState.rmsDb / 12f).coerceIn(0.08f, 1f)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        color = if (voiceState.autoCutProgress > 0.05f) Color(0xFFFACC15) else Color(0xFF00E6B4),
                        trackColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.45f)
                    )
                }
            }
        } else if (autoSelected && !isActivelyTyping) {
            // Live AUTO HUD Strip: shows real-time mic activity, 1-tap Hybrid "বলুন" trigger, & quick keyboard toggle
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp)
                    .border(
                        width = 1.dp,
                        color = when {
                            voiceState.isMicZeroSignal -> MaterialTheme.colorScheme.error
                            voiceState.hasDetectedVoiceActivity -> Color(0xFF00E6B4)
                            else -> MaterialTheme.colorScheme.outlineVariant
                        },
                        shape = RoundedCornerShape(16.dp)
                    )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = when {
                                voiceState.isMicZeroSignal -> "মাইকে শব্দ আসছে না — Chrome Mic Allow দিন বা লিখুন"
                                voiceState.partialTranscript.isNotBlank() -> voiceState.partialTranscript
                                voiceState.hasDetectedVoiceActivity -> "কথা শোনা হচ্ছে..."
                                voiceState.isSpeaking -> "Hey Shuvo উত্তর বলছে..."
                                voiceState.isListening -> "শুনছি — সরাসরি কথা বলুন"
                                else -> "ভয়েস প্রস্তুত — 'বলুন' চাপুন"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = if (voiceState.isMicZeroSignal) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = { showQuickTextBarInAuto = !showQuickTextBarInAuto },
                            modifier = Modifier
                                .size(38.dp)
                                .bubbleTouchEffect(MaterialTheme.colorScheme.secondary)
                                .testTag("toggle_auto_text_bar_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Keyboard,
                                contentDescription = "Toggle Text Input",
                                tint = if (showQuickTextBarInAuto) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Button(
                            onClick = {
                                val hasPerm = ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.RECORD_AUDIO
                                ) == PackageManager.PERMISSION_GRANTED
                                if (hasPerm) {
                                    onStartQuickManualVoice()
                                } else {
                                    pendingEnableAutoAfterPerm = false
                                    micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                }
                            },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .height(38.dp)
                                .bubbleTouchEffect(MaterialTheme.colorScheme.primary)
                                .testTag("auto_quick_talk_button")
                        ) {
                            Icon(Icons.Filled.Mic, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("বলুন", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                        }
                    }
                    LinearProgressIndicator(
                        progress = {
                            when {
                                voiceState.autoCutProgress > 0.05f -> voiceState.autoCutProgress
                                voiceState.isMicZeroSignal -> 0.02f
                                else -> (voiceState.rmsDb / 12f).coerceIn(0.06f, 1f)
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.dp)
                            .clip(RoundedCornerShape(2.dp)),
                        color = when {
                            voiceState.isMicZeroSignal -> MaterialTheme.colorScheme.error
                            voiceState.autoCutProgress > 0.05f -> Color(0xFFFACC15)
                            else -> Color(0xFF00E6B4)
                        },
                        trackColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.45f)
                    )
                }
            }
        }

        // 4. Clean AI-First Bottom Input Bar (shown in MANUAL mode or toggled open in AUTO mode)
        if (!autoSelected || showQuickTextBarInAuto) {
            Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(20.dp))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                OutlinedTextField(
                    value = commandText,
                    onValueChange = { commandText = it },
                    placeholder = { Text("লিখুন বা ভয়েস দিন...") },
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        imeAction = ImeAction.Send
                    ),
                    keyboardActions = KeyboardActions(
                        onSend = { submitTypedCommand() }
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .onFocusChanged { focusState ->
                            isInputFocused = focusState.isFocused
                        }
                        .testTag("command_input_field"),
                    maxLines = 3
                )

                val showManualStopIcon = !autoSelected && voiceState.isListening && !isActivelyTyping
                IconButton(
                    onClick = {
                        isInputFocused = false
                        onTypingStateChanged(false)
                        keyboardController?.hide()
                        if (activelyCapturingUtterance) {
                            onStopVoice()
                        } else {
                            triggerMicStart()
                        }
                    },
                    modifier = Modifier
                        .size(50.dp)
                        .bubbleTouchEffect(
                            bubbleColor = if (showManualStopIcon) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.primary
                        )
                        .clip(RoundedCornerShape(14.dp))
                        .background(
                            when {
                                showManualStopIcon -> MaterialTheme.colorScheme.error
                                autoSelected && voiceState.isListening -> MaterialTheme.colorScheme.primaryContainer
                                else -> MaterialTheme.colorScheme.secondaryContainer
                            }
                        )
                        .testTag("voice_mic_button")
                ) {
                    Icon(
                        imageVector = if (showManualStopIcon) Icons.Filled.Stop else Icons.Filled.Mic,
                        contentDescription = "Voice Command Mic",
                        tint = when {
                            showManualStopIcon -> MaterialTheme.colorScheme.onError
                            autoSelected && voiceState.isListening -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.onSecondaryContainer
                        }
                    )
                }

                IconButton(
                    onClick = { submitTypedCommand() },
                    enabled = commandText.isNotBlank(),
                    modifier = Modifier
                        .size(50.dp)
                        .bubbleTouchEffect(MaterialTheme.colorScheme.primary)
                        .clip(RoundedCornerShape(14.dp))
                        .background(
                            if (commandText.isNotBlank()) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceVariant
                        )
                        .testTag("send_command_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Send Typed Command",
                        tint = if (commandText.isNotBlank()) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VoiceSoftwareAndBusinessScreen(
    generatedFiles: List<File>,
    selfScreenTime: List<SelfScreenTimeItem>,
    canDrawOverlay: Boolean,
    hasUsagePerm: Boolean,
    onRequestOverlayPerm: () -> Unit,
    onRequestUsagePerm: () -> Unit,
    onOpenAccessibilityPerm: () -> Unit,
    onRefreshLocal: () -> Unit,
    onOpenGeneratedFile: (String) -> Unit,
    onReadFilePreview: (File) -> String,
    ledgerEntries: List<LedgerEntryEntity>,
    onDeleteLedgerEntry: (Long) -> Unit,
    myFeatureRequests: List<FeatureRequest>,
    onOpenBatteryOptimizationSettings: () -> Unit = {},
    onInstallBanglaTts: () -> Unit = {}
) {
    var selectedSection by rememberSaveable { mutableIntStateOf(0) }

    var previewFileTitle by remember { mutableStateOf<String?>(null) }
    var previewFileContent by remember { mutableStateOf<String?>(null) }

    if (previewFileTitle != null && previewFileContent != null) {
        AlertDialog(
            onDismissRequest = {
                previewFileTitle = null
                previewFileContent = null
            },
            title = { Text(previewFileTitle.orEmpty()) },
            text = {
                SelectionContainer {
                    Column(
                        modifier = Modifier
                            .heightIn(max = 380.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text(previewFileContent.orEmpty(), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    previewFileTitle = null
                    previewFileContent = null
                }) {
                    Text("বন্ধ করুন")
                }
            }
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                FilterChip(
                    selected = selectedSection == 0,
                    onClick = { selectedSection = 0 },
                    label = { Text("পারমিশন ও ফাইল (${generatedFiles.size})") },
                    modifier = Modifier
                        .bubbleTouchEffect(MaterialTheme.colorScheme.primary)
                        .testTag("chip_software_control")
                )
                FilterChip(
                    selected = selectedSection == 1,
                    onClick = { selectedSection = 1 },
                    label = { Text("ট্যালি খাতা (${ledgerEntries.size})") },
                    modifier = Modifier
                        .bubbleTouchEffect(MaterialTheme.colorScheme.primary)
                        .testTag("chip_tally_khata")
                )
                FilterChip(
                    selected = selectedSection == 2,
                    onClick = { selectedSection = 2 },
                    label = { Text("স্ক্রিন-টাইম") },
                    modifier = Modifier
                        .bubbleTouchEffect(MaterialTheme.colorScheme.primary)
                        .testTag("chip_wellbeing_bd")
                )
            }
        }

        if (selectedSection == 0) {
            item {
                Card(shape = RoundedCornerShape(18.dp)) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("অল-ডিসপ্লে ও সিস্টেম ভয়েস পারমিশন", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = onRequestOverlayPerm,
                                modifier = Modifier.bubbleTouchEffect(MaterialTheme.colorScheme.primary)
                            ) {
                                Text(if (canDrawOverlay) "All-Display Overlay: চালু আছে ✓" else "All-Display Overlay চালু করুন")
                            }
                            OutlinedButton(
                                onClick = onOpenAccessibilityPerm,
                                modifier = Modifier.bubbleTouchEffect(MaterialTheme.colorScheme.primary)
                            ) {
                                Text("ভয়েস অ্যাক্সেসিবিলিটি সেটিংস")
                            }
                            OutlinedButton(
                                onClick = onRequestUsagePerm,
                                modifier = Modifier.bubbleTouchEffect(MaterialTheme.colorScheme.primary)
                            ) {
                                Text(if (hasUsagePerm) "Usage Access: চালু আছে ✓" else "Usage Access চালু করুন")
                            }
                            OutlinedButton(
                                onClick = onOpenBatteryOptimizationSettings,
                                modifier = Modifier
                                    .bubbleTouchEffect(MaterialTheme.colorScheme.primary)
                                    .testTag("battery_optimization_settings_btn")
                            ) {
                                Text("ব্যাটারি Unrestricted সেট করুন")
                            }
                            OutlinedButton(
                                onClick = onInstallBanglaTts,
                                modifier = Modifier
                                    .bubbleTouchEffect(MaterialTheme.colorScheme.secondary)
                                    .testTag("control_install_bangla_tts_btn")
                            ) {
                                Text("বাংলা ভয়েস (TTS) ডাউনলোড")
                            }
                        }
                        Text(
                            "মোবাইল ও কম্পিউটারের ম্যাপ, YouTube, Word, Excel, PDF এবং AutoCAD (.lsp) সরাসরি ভয়েস কমান্ডে নিয়ন্ত্রিত হয়।",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            item {
                Text(
                    "ভয়েসে তৈরি ফাইলসমূহ (${generatedFiles.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            if (generatedFiles.isEmpty()) {
                item {
                    Card(shape = RoundedCornerShape(14.dp)) {
                        Text(
                            "ভয়েসে Word, Excel, PDF বা AutoCAD তৈরি করতে বললে ফাইলগুলো এখানে জমা হবে।",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                }
            } else {
                items(generatedFiles, key = { it.absolutePath }) { f ->
                    Card(shape = RoundedCornerShape(12.dp)) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(f.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                Text("${f.length()} bytes", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    onClick = {
                                        previewFileTitle = f.name
                                        previewFileContent = onReadFilePreview(f)
                                    },
                                    modifier = Modifier.bubbleTouchEffect(MaterialTheme.colorScheme.primary)
                                ) {
                                    Text("প্রিভিউ")
                                }
                                Button(
                                    onClick = { onOpenGeneratedFile(f.absolutePath) },
                                    modifier = Modifier.bubbleTouchEffect(MaterialTheme.colorScheme.primary)
                                ) {
                                    Text("খুলুন / শেয়ার")
                                }
                            }
                        }
                    }
                }
            }
        } else if (selectedSection == 1) {
            item {
                val totalIncome = ledgerEntries.filter { it.entryType == "INCOME" }.sumOf { it.amountBdt }
                val totalExpense = ledgerEntries.filter { it.entryType == "EXPENSE" }.sumOf { it.amountBdt }
                val totalDue = ledgerEntries.filter { it.entryType == "DUE" }.sumOf { it.amountBdt }

                Card(shape = RoundedCornerShape(18.dp)) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.AutoMirrored.Filled.ReceiptLong, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("ভয়েস ট্যালি খাতা সারসংক্ষেপ", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        }
                        Text(
                            text = String.format(
                                Locale.US,
                                "জমা/আয়: ৳%.0f  •  খরচ: ৳%.0f  •  বাকি: ৳%.0f",
                                totalIncome,
                                totalExpense,
                                totalDue
                            ),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            if (ledgerEntries.isEmpty()) {
                item {
                    Card(shape = RoundedCornerShape(14.dp)) {
                        Text(
                            "ভয়েসে জমা, খরচ বা বাকি বললে ট্যালি খাতায় স্বয়ংক্রিয়ভাবে হিসাব যুক্ত হবে।",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                }
            } else {
                items(ledgerEntries, key = { it.id }) { item ->
                    Card(shape = RoundedCornerShape(14.dp)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("${item.customerName} • ${item.entryType}", style = MaterialTheme.typography.titleMedium)
                                Text(item.itemDescription, style = MaterialTheme.typography.bodyMedium)
                            }
                            Text(
                                text = String.format(Locale.US, "৳%.0f", item.amountBdt),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                            IconButton(
                                onClick = { onDeleteLedgerEntry(item.id) },
                                modifier = Modifier.bubbleTouchEffect(MaterialTheme.colorScheme.secondary)
                            ) {
                                Icon(Icons.Filled.DeleteSweep, contentDescription = "Delete ledger entry")
                            }
                        }
                    }
                }
            }
        } else {
            item {
                Card(shape = RoundedCornerShape(18.dp)) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.QueryStats, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("আজকের অ্যাপ ব্যবহারের সময় (Screen-Time)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (!hasUsagePerm) {
                                Button(
                                    onClick = onRequestUsagePerm,
                                    modifier = Modifier.bubbleTouchEffect(MaterialTheme.colorScheme.primary)
                                ) {
                                    Text("Usage Access চালু করুন")
                                }
                            }
                            OutlinedButton(
                                onClick = onRefreshLocal,
                                modifier = Modifier.bubbleTouchEffect(MaterialTheme.colorScheme.primary)
                            ) {
                                Text("রিফ্রেশ")
                            }
                        }
                        if (selfScreenTime.isEmpty()) {
                            Text(
                                if (hasUsagePerm) "আজ ১ মিনিটের বেশি ব্যবহৃত কোনো অ্যাপ পাওয়া যায়নি।" else "স্ক্রিন-টাইম দেখতে Usage Access চালু করুন।",
                                style = MaterialTheme.typography.labelSmall
                            )
                        } else {
                            selfScreenTime.forEach { st ->
                                Text("• ${st.packageName}: ${st.minutesToday} মিনিট", style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }

            if (myFeatureRequests.isNotEmpty()) {
                item {
                    Text("আপনার পাঠানো ফিচার রিকোয়েস্ট", style = MaterialTheme.typography.titleMedium)
                }
                items(myFeatureRequests, key = { it.id }) { req ->
                    Card(shape = RoundedCornerShape(14.dp)) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text("${req.title} (${req.status.uppercase()})", style = MaterialTheme.typography.titleMedium)
                            Text(req.description, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PrivacyVaultAndArchitectureScreen(
    isCloudAuthenticated: Boolean,
    cloudEmail: String,
    settings: AssistantSettings,
    memories: List<PrivateMemoryEntity>,
    taskLogs: List<TaskLogEntity>,
    onDeleteMemory: (Long) -> Unit,
    onWipeMemories: () -> Unit,
    onUndoTask: (String) -> Unit,
    onSelectPersona: (AiPersonaFace) -> Unit,
    onSelectMode: (UserAccessibilityMode) -> Unit,
    onSelectThemePalette: (AppThemePalette) -> Unit,
    onSetCustomAccentHue: (Float) -> Unit,
    onSetTextScale: (Float) -> Unit,
    onToggleDarkHud: (Boolean) -> Unit,
    onToggleAutoAllDisplay: (Boolean) -> Unit,
    onSelectChatHeadPhoto: (android.graphics.Bitmap) -> Unit = {},
    onResetChatHeadPhoto: () -> Unit = {},
    onOpenSignInSheet: () -> Unit,
    onSignOut: () -> Unit
) {
    val context = LocalContext.current
    var subTab by rememberSaveable { mutableIntStateOf(0) }

    val vaultAvatarPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            try {
                val bmp = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val src = ImageDecoder.createSource(context.contentResolver, uri)
                    ImageDecoder.decodeBitmap(src) { decoder, _, _ -> decoder.isMutableRequired = true }
                } else {
                    @Suppress("DEPRECATION")
                    MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
                }
                onSelectChatHeadPhoto(bmp)
            } catch (_: Exception) {
            }
        }
    }

    val savedAvatarBitmap = remember(settings.chatHeadAvatarPath, settings.chatHeadAvatarUpdatedMs) {
        try {
            if (settings.chatHeadAvatarPath.isNotBlank() && File(settings.chatHeadAvatarPath).exists()) {
                BitmapFactory.decodeFile(settings.chatHeadAvatarPath)?.asImageBitmap()
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                FilterChip(
                    selected = subTab == 0,
                    onClick = { subTab = 0 },
                    label = { Text("থিম ও কাস্টমাইজ") },
                    modifier = Modifier
                        .bubbleTouchEffect(MaterialTheme.colorScheme.primary)
                        .testTag("vault_theme_studio_chip")
                )
                FilterChip(
                    selected = subTab == 1,
                    onClick = { subTab = 1 },
                    label = { Text("মেমরি ও টাস্ক Undo (${memories.size})") },
                    modifier = Modifier
                        .bubbleTouchEffect(MaterialTheme.colorScheme.primary)
                        .testTag("vault_memory_undo_chip")
                )
            }
        }

        if (subTab == 0) {
            item {
                Card(
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(18.dp))
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Filled.ColorLens,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "পছন্দমতো থিম কাস্টমাইজ করুন",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = "পছন্দের রঙ ও লেখার সাইজ সাজান (অথবা ভয়েসে বলুন 'হে শুভ, লাইট/গোল্ডেন থিম দাও')",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        // Messenger-Style Floating Circular Chat Head Custom Photo Card
                        Card(
                            shape = RoundedCornerShape(14.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier
                                            .size(58.dp)
                                            .clip(CircleShape)
                                            .background(Color(0xFF081120))
                                            .border(2.5.dp, Color(0xFF00E6B4), CircleShape)
                                    ) {
                                        if (savedAvatarBitmap != null) {
                                            Image(
                                                bitmap = savedAvatarBitmap,
                                                contentDescription = "Messenger Chat Head Preview",
                                                contentScale = ContentScale.Crop,
                                                modifier = Modifier
                                                    .fillMaxSize()
                                                    .clip(CircleShape)
                                            )
                                        } else {
                                            AnimatedAiFaceCanvas(
                                                personaFace = settings.personaFace,
                                                isListening = true,
                                                isSpeaking = false,
                                                rmsDb = 2.0f,
                                                autoCutProgress = 0f,
                                                sizeDp = 44.dp
                                            )
                                        }
                                    }
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "মেসেঞ্জার চ্যাট-হেড ছবি (Chat Head)",
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        Text(
                                            text = "হোম স্ক্রিন বা অন্য অ্যাপের উপর মেসেঞ্জারের মতো গোল ভাসমান চ্যাট-হেডে আপনার নিজের ছবি সেট করুন।",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Button(
                                        onClick = {
                                            vaultAvatarPickerLauncher.launch(
                                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                            )
                                        },
                                        modifier = Modifier
                                            .weight(1f)
                                            .bubbleTouchEffect(MaterialTheme.colorScheme.primary)
                                            .testTag("pick_chat_head_photo_button")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.CameraAlt,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(if (savedAvatarBitmap != null) "ছবি পরিবর্তন করুন" else "নিজের ছবি দিন")
                                    }
                                    if (savedAvatarBitmap != null) {
                                        OutlinedButton(
                                            onClick = onResetChatHeadPhoto,
                                            modifier = Modifier
                                                .bubbleTouchEffect(MaterialTheme.colorScheme.secondary)
                                                .testTag("reset_chat_head_photo_button")
                                        ) {
                                            Text("ডিফল্ট")
                                        }
                                    }
                                }
                            }
                        }

                        Text(
                            text = "থিম কালার প্যালেট নির্বাচন করুন",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )

                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            AppThemePalette.entries.forEach { palette ->
                                val isSelected = settings.themePalette == palette
                                val swatchColor = if (palette == AppThemePalette.CUSTOM_COLOR) {
                                    Color.hsv(settings.customAccentHue.coerceIn(0f, 360f), 0.80f, 0.96f)
                                } else {
                                    Color(palette.previewHex)
                                }
                                Row(
                                    modifier = Modifier
                                        .bubbleTouchEffect(swatchColor)
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(
                                            if (isSelected) MaterialTheme.colorScheme.primaryContainer
                                            else MaterialTheme.colorScheme.surface
                                        )
                                        .border(
                                            width = if (isSelected) 2.dp else 1.dp,
                                            color = if (isSelected) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.outlineVariant,
                                            shape = RoundedCornerShape(14.dp)
                                        )
                                        .clickable { onSelectThemePalette(palette) }
                                        .padding(horizontal = 12.dp, vertical = 10.dp)
                                        .testTag("palette_swatch_${palette.name.lowercase()}"),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(20.dp)
                                            .clip(CircleShape)
                                            .background(swatchColor)
                                            .border(1.dp, Color.White.copy(alpha = 0.6f), CircleShape)
                                    )
                                    Column {
                                        Text(
                                            text = palette.labelBn,
                                            style = MaterialTheme.typography.labelLarge,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = palette.subtitle,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }

                        // Full Custom HSV Rainbow Color Slider
                        Card(
                            shape = RoundedCornerShape(14.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "নিজের পছন্দের রঙ তৈরি করুন (Custom Color)",
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Box(
                                        modifier = Modifier
                                            .size(26.dp)
                                            .clip(CircleShape)
                                            .background(Color.hsv(settings.customAccentHue.coerceIn(0f, 360f), 0.82f, 0.98f))
                                            .border(1.5.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                                    )
                                }
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(10.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(
                                            Brush.horizontalGradient(
                                                colors = listOf(
                                                    Color.hsv(0f, 0.85f, 1f),
                                                    Color.hsv(60f, 0.85f, 1f),
                                                    Color.hsv(120f, 0.85f, 1f),
                                                    Color.hsv(180f, 0.85f, 1f),
                                                    Color.hsv(240f, 0.85f, 1f),
                                                    Color.hsv(300f, 0.85f, 1f),
                                                    Color.hsv(360f, 0.85f, 1f)
                                                )
                                            )
                                        )
                                )
                                Slider(
                                    value = settings.customAccentHue,
                                    onValueChange = { onSetCustomAccentHue(it) },
                                    valueRange = 0f..360f,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("custom_theme_hue_slider")
                                )
                            }
                        }

                        // Font / Text Size Customizer
                        Text(
                            text = "লেখার আকার (Font Size)",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            val fontSizes = listOf(
                                "ছোট (90%)" to 0.90f,
                                "স্বাভাবিক (100%)" to 1.00f,
                                "বড় (115%)" to 1.15f,
                                "অতি বড় (128%)" to 1.28f
                            )
                            fontSizes.forEach { (label, scaleVal) ->
                                FilterChip(
                                    selected = abs(settings.textScale - scaleVal) < 0.04f,
                                    onClick = { onSetTextScale(scaleVal) },
                                    label = { Text(label) },
                                    modifier = Modifier.bubbleTouchEffect(MaterialTheme.colorScheme.primary)
                                )
                            }
                        }

                        // Assistant Core Design Selector (Clean — zero "Jarvis" label!)
                        Text(
                            text = "অ্যাসিস্ট্যান্ট কোর ডিজাইন (AI Core)",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        AiPersonaFace.entries.forEach { face ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .bubbleTouchEffect(MaterialTheme.colorScheme.primary)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(
                                        if (settings.personaFace == face) MaterialTheme.colorScheme.primaryContainer
                                        else MaterialTheme.colorScheme.surface
                                    )
                                    .clickable { onSelectPersona(face) }
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                AnimatedAiFaceCanvas(
                                    personaFace = face,
                                    isListening = false,
                                    isSpeaking = false,
                                    rmsDb = 1.5f,
                                    autoCutProgress = 0f,
                                    sizeDp = 42.dp
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(face.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                    Text(face.subtitle, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }

                        Text(
                            text = "ব্যবহার মোড (User Mode)",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            UserAccessibilityMode.entries.forEach { m ->
                                FilterChip(
                                    selected = settings.userMode == m,
                                    onClick = { onSelectMode(m) },
                                    label = { Text(m.label) },
                                    modifier = Modifier.bubbleTouchEffect(MaterialTheme.colorScheme.primary)
                                )
                            }
                        }

                        Text(
                            text = "ঐচ্ছিক Google অ্যাকাউন্ট ব্যাকআপ",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (isCloudAuthenticated) {
                                "Google অ্যাকাউন্ট যুক্ত আছে ($cloudEmail)"
                            } else {
                                "অ্যাপটি লগইন ছাড়াই সম্পূর্ণ উন্মুক্ত (চাইলে পরে Google যুক্ত করতে পারবেন)।"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (isCloudAuthenticated) {
                            OutlinedButton(
                                onClick = onSignOut,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .bubbleTouchEffect(MaterialTheme.colorScheme.secondary)
                            ) {
                                Text("Google অ্যাকাউন্ট সংযোগ বিচ্ছিন্ন করুন")
                            }
                        } else {
                            Button(
                                onClick = onOpenSignInSheet,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .bubbleTouchEffect(MaterialTheme.colorScheme.primary)
                                    .testTag("optional_google_sign_in_button")
                            ) {
                                Text("Google অ্যাকাউন্ট যুক্ত করুন (ঐচ্ছিক)")
                            }
                        }
                    }
                }
            }
        } else {
            item {
                Card(shape = RoundedCornerShape(18.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Filled.Security, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("ভয়েসে সেভ করা মেমরি (${memories.size})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        }
                        if (memories.isNotEmpty()) {
                            OutlinedButton(
                                onClick = onWipeMemories,
                                modifier = Modifier.bubbleTouchEffect(MaterialTheme.colorScheme.secondary)
                            ) {
                                Text("সব মুছুন")
                            }
                        }
                    }
                }
            }

            if (memories.isEmpty()) {
                item {
                    Card(shape = RoundedCornerShape(14.dp)) {
                        Text(
                            "ভয়েসে 'মনে রাখো...' বললে তথ্য শুধুমাত্র আপনার ফোনেই সুরক্ষিত থাকবে।",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                }
            } else {
                items(memories, key = { "mem_${it.id}" }) { mem ->
                    Card(shape = RoundedCornerShape(14.dp)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(mem.topic, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                                Text(mem.content, style = MaterialTheme.typography.bodyMedium)
                            }
                            IconButton(
                                onClick = { onDeleteMemory(mem.id) },
                                modifier = Modifier.bubbleTouchEffect(MaterialTheme.colorScheme.secondary)
                            ) {
                                Icon(Icons.Filled.DeleteSweep, contentDescription = "Delete Memory")
                            }
                        }
                    }
                }
            }

            item {
                Text("কাজের ইতিহাস ও Undo", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }

            items(taskLogs, key = { it.taskId }) { log ->
                Card(shape = RoundedCornerShape(14.dp)) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("${log.taskId} • ${log.status}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                            if (log.reversible && log.status != "UNDONE") {
                                OutlinedButton(
                                    onClick = { onUndoTask(log.taskId) },
                                    modifier = Modifier.bubbleTouchEffect(MaterialTheme.colorScheme.primary)
                                ) {
                                    Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo Task", modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Undo")
                                }
                            }
                        }
                        Text(log.commandText, style = MaterialTheme.typography.titleMedium)
                        Text(log.stepsSummary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
