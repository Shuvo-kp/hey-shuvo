package com.example.ui.screens

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Cake
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.FrontHand
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.data.cloud.model.GestureTransferSession
import com.example.data.local.BirthdayReminderEntity
import com.example.feature.customer.HandGestureCameraAnalyzer
import com.example.feature.customer.HandGestureState
import com.example.ui.components.bubbleTouchEffect
import java.io.File
import java.util.Calendar
import java.util.concurrent.Executors

private data class PickedTransferPayload(
    val fileName: String,
    val mimeType: String,
    val bytes: ByteArray
)

private fun readPickedUriPayload(context: Context, uri: Uri): PickedTransferPayload? {
    return try {
        val resolver = context.contentResolver
        val mime = resolver.getType(uri) ?: "application/octet-stream"
        var displayName = "Selected_File"
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) {
                    displayName = cursor.getString(idx) ?: displayName
                }
            }
        }
        val bytes = resolver.openInputStream(uri)?.use { stream ->
            stream.readBytes()
        } ?: return null
        PickedTransferPayload(fileName = displayName, mimeType = mime, bytes = bytes)
    } catch (_: Exception) {
        null
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GestureAirTransferPanel(
    isCloudSignedIn: Boolean,
    gesturePairingCode: String,
    gestureStatusMessage: String,
    isGestureBusy: Boolean,
    pendingIncomingTransfers: List<GestureTransferSession>,
    receivedTransferFiles: List<File>,
    generatedFiles: List<File>,
    onUpdatePairingCode: (String) -> Unit,
    onGrabFileByFist: (String, String, ByteArray, String) -> Unit,
    onDropFileByOpenPalm: () -> Unit,
    onOpenFile: (String) -> Unit,
    onOpenSignInModal: () -> Unit
) {
    val context = LocalContext.current
    var selectedPayload by remember { mutableStateOf<PickedTransferPayload?>(null) }
    var filePickerNote by rememberSaveable { mutableStateOf("") }
    var qualityMode by rememberSaveable { mutableStateOf("HIGH") }
    var isCameraGestureActive by rememberSaveable { mutableStateOf(false) }
    var liveGestureState by remember { mutableStateOf(HandGestureState.NO_HAND) }
    var lastAutoGrabbedFileName by remember { mutableStateOf("") }

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
        isCameraGestureActive = granted
    }

    val documentPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            val payload = readPickedUriPayload(context, uri)
            if (payload != null) {
                selectedPayload = payload
                lastAutoGrabbedFileName = ""
                filePickerNote = "নির্বাচিত ফাইল: ${payload.fileName} (${payload.bytes.size / 1024} KB)"
            } else {
                filePickerNote = "ফাইল পড়া যায়নি, অন্য ফাইল নির্বাচন করুন।"
            }
        }
    }

    val currentPayload by rememberUpdatedState(selectedPayload)
    val currentPending by rememberUpdatedState(pendingIncomingTransfers)
    val currentBusy by rememberUpdatedState(isGestureBusy)

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.FrontHand,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "হাতের ইশারায় ফাইল ট্রান্সফার (মোবাইল ⇄ মোবাইল/ল্যাপটপ)",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "১) ফাইল নির্বাচন করুন  ২) ডিসপ্লের সামনে হাত মুঠো (✊) করুন  ৩) অন্য ডিভাইসের সামনে হাত খুলে দিন (🖐️)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (!isCloudSignedIn) {
                    OutlinedButton(
                        onClick = onOpenSignInModal,
                        modifier = Modifier
                            .fillMaxWidth()
                            .bubbleTouchEffect()
                            .testTag("gesture_cloud_signin_button")
                    ) {
                        Icon(Icons.Filled.CloudSync, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("অন্য ডিভাইসে পাঠাতে Google Sign-In যুক্ত করুন")
                    }
                }

                OutlinedTextField(
                    value = gesturePairingCode,
                    onValueChange = onUpdatePairingCode,
                    label = { Text("দুই ডিভাইসের পেয়ারিং কোড (যেমন: SHUVO26)") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("gesture_pairing_code_input")
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Button(
                        onClick = { documentPickerLauncher.launch(arrayOf("*/*")) },
                        modifier = Modifier
                            .weight(1f)
                            .bubbleTouchEffect()
                            .testTag("gesture_pick_file_button")
                    ) {
                        Icon(Icons.Filled.AttachFile, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("ফাইল নির্বাচন করুন")
                    }

                    OutlinedButton(
                        onClick = {
                            if (isCameraGestureActive) {
                                isCameraGestureActive = false
                            } else if (hasCameraPermission) {
                                isCameraGestureActive = true
                            } else {
                                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .bubbleTouchEffect()
                            .testTag("gesture_toggle_camera_button")
                    ) {
                        Icon(
                            imageVector = if (isCameraGestureActive) Icons.Filled.VideocamOff else Icons.Filled.Videocam,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (isCameraGestureActive) "ক্যামেরা বন্ধ" else "ক্যামেরা ইশারা")
                    }
                }

                if (generatedFiles.isNotEmpty()) {
                    Text(
                        text = "অথবা অ্যাপে তৈরি ডকুমেন্ট বেছে নিন:",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        generatedFiles.take(4).forEach { doc ->
                            AssistChip(
                                onClick = {
                                    runCatching {
                                        val bytes = doc.readBytes()
                                        selectedPayload = PickedTransferPayload(
                                            fileName = doc.name,
                                            mimeType = "application/octet-stream",
                                            bytes = bytes
                                        )
                                        lastAutoGrabbedFileName = ""
                                        filePickerNote = "নির্বাচিত ডকুমেন্ট: ${doc.name} (${bytes.size / 1024} KB)"
                                    }
                                },
                                label = { Text(doc.name.take(22)) }
                            )
                        }
                    }
                }

                if (filePickerNote.isNotBlank()) {
                    Text(
                        text = filePickerNote,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    AssistChip(
                        onClick = { qualityMode = "HIGH" },
                        label = { Text(if (qualityMode == "HIGH") "✓ হাই রেজুলেশন" else "হাই রেজুলেশন") }
                    )
                    AssistChip(
                        onClick = { qualityMode = "LOW_MB" },
                        label = { Text(if (qualityMode == "LOW_MB") "✓ লো রেজুলেশন / ফ্রি এমবি" else "লো রেজুলেশন / ফ্রি এমবি") }
                    )
                    AssistChip(
                        onClick = { qualityMode = "ORIGINAL" },
                        label = { Text(if (qualityMode == "ORIGINAL") "✓ অরিজিনাল" else "অরিজিনাল") }
                    )
                }

                if (isCameraGestureActive && hasCameraPermission) {
                    FrontCameraGestureScannerBox(
                        liveGestureState = liveGestureState,
                        onGestureDetected = { state ->
                            liveGestureState = state
                            if (!currentBusy) {
                                val picked = currentPayload
                                if (state == HandGestureState.CLOSED_FIST && picked != null && lastAutoGrabbedFileName != picked.fileName) {
                                    lastAutoGrabbedFileName = picked.fileName
                                    onGrabFileByFist(picked.fileName, picked.mimeType, picked.bytes, qualityMode)
                                } else if (state == HandGestureState.OPEN_PALM && currentPending.isNotEmpty()) {
                                    onDropFileByOpenPalm()
                                }
                            }
                        }
                    )
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Button(
                        onClick = {
                            val picked = selectedPayload
                            if (picked != null) {
                                onGrabFileByFist(picked.fileName, picked.mimeType, picked.bytes, qualityMode)
                            }
                        },
                        enabled = selectedPayload != null && !isGestureBusy,
                        modifier = Modifier
                            .weight(1f)
                            .bubbleTouchEffect()
                            .testTag("gesture_fist_grab_button")
                    ) {
                        Icon(Icons.Filled.PanTool, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("✊ মুঠো করুন (Grab)")
                    }

                    Button(
                        onClick = onDropFileByOpenPalm,
                        enabled = pendingIncomingTransfers.isNotEmpty() && !isGestureBusy,
                        modifier = Modifier
                            .weight(1f)
                            .bubbleTouchEffect()
                            .testTag("gesture_palm_drop_button")
                    ) {
                        Icon(Icons.Filled.FrontHand, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("🖐️ মুঠো খুলুন (Drop)")
                    }
                }

                if (isGestureBusy) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }

                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = gestureStatusMessage,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                        if (pendingIncomingTransfers.isNotEmpty()) {
                            Text(
                                text = "অপেক্ষমাণ ফাইল (${pendingIncomingTransfers.size}): ${pendingIncomingTransfers.first().fileName}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }

        if (receivedTransferFiles.isNotEmpty()) {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "হাতের ইশারায় রিসিভ করা ফাইল (${receivedTransferFiles.size})",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    receivedTransferFiles.take(6).forEach { file ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = file.name,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedButton(
                                onClick = { onOpenFile(file.absolutePath) },
                                modifier = Modifier.bubbleTouchEffect()
                            ) {
                                Icon(Icons.Filled.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("ওপেন")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FrontCameraGestureScannerBox(
    liveGestureState: HandGestureState,
    onGestureDetected: (HandGestureState) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentCallback by rememberUpdatedState(onGestureDetected)
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    var useFrontCamera by rememberSaveable { mutableStateOf(true) }
    var cameraErrorText by remember { mutableStateOf<String?>(null) }
    var previewViewRef by remember { mutableStateOf<PreviewView?>(null) }

    DisposableEffect(lifecycleOwner, useFrontCamera, previewViewRef) {
        val previewView = previewViewRef
        var activeProvider: ProcessCameraProvider? = null
        var disposed = false

        if (previewView != null) {
            val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
            cameraProviderFuture.addListener({
                if (disposed) return@addListener
                runCatching {
                    val cameraProvider = cameraProviderFuture.get()
                    activeProvider = cameraProvider
                    val preview = Preview.Builder().build().also {
                        it.surfaceProvider = previewView.surfaceProvider
                    }
                    val imageAnalysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                        .also { analysis ->
                            analysis.setAnalyzer(
                                analysisExecutor,
                                HandGestureCameraAnalyzer { state, _ ->
                                    previewView.post {
                                        currentCallback(state)
                                    }
                                }
                            )
                        }
                    val selector = when {
                        useFrontCamera && cameraProvider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) ->
                            CameraSelector.DEFAULT_FRONT_CAMERA
                        cameraProvider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) ->
                            CameraSelector.DEFAULT_BACK_CAMERA
                        else -> CameraSelector.DEFAULT_FRONT_CAMERA
                    }
                    cameraProvider.unbindAll()
                    try {
                        cameraProvider.bindToLifecycle(lifecycleOwner, selector, preview, imageAnalysis)
                        cameraErrorText = null
                    } catch (_: Exception) {
                        cameraProvider.unbindAll()
                        cameraProvider.bindToLifecycle(lifecycleOwner, selector, preview)
                        cameraErrorText = null
                    }
                }.onFailure { err ->
                    cameraErrorText = "ক্যামেরা চালু হয়নি: ${err.localizedMessage ?: "ডিভাইস ক্যামেরা চেক করুন"}"
                }
            }, ContextCompat.getMainExecutor(context))
        }

        onDispose {
            disposed = true
            runCatching { activeProvider?.unbindAll() }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            runCatching { analysisExecutor.shutdown() }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(210.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(14.dp))
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PreviewView(ctx).apply {
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                }.also { created ->
                    previewViewRef = created
                }
            }
        )

        OutlinedButton(
            onClick = { useFrontCamera = !useFrontCamera },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp)
                .bubbleTouchEffect()
        ) {
            Text(if (useFrontCamera) "ব্যাক ক্যামেরা" else "ফ্রন্ট ক্যামেরা")
        }

        val badgeLabel = cameraErrorText ?: when (liveGestureState) {
            HandGestureState.CLOSED_FIST -> "✊ হাত মুঠো (Grab)"
            HandGestureState.OPEN_PALM -> "🖐️ হাত খোলা (Drop)"
            HandGestureState.NO_HAND -> "ক্যামেরার সামনে হাত দেখান (✊ / 🖐️)"
        }
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(8.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.88f))
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Text(
                text = badgeLabel,
                style = MaterialTheme.typography.labelLarge,
                color = if (cameraErrorText != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun BirthdayWishManagerPanel(
    birthdays: List<BirthdayReminderEntity>,
    isBusy: Boolean,
    onSaveBirthday: (String, Int, Int, String, String) -> Unit,
    onDeleteBirthday: (String) -> Unit,
    onSendBirthdayWishNow: (BirthdayReminderEntity) -> Unit,
    onGenerateAiWish: (String, String, (String) -> Unit) -> Unit,
    onSpeakText: (String) -> Unit
) {
    val cal = remember { Calendar.getInstance() }
    val todayDay = cal.get(Calendar.DAY_OF_MONTH)
    val todayMonth = cal.get(Calendar.MONTH) + 1

    var personName by rememberSaveable { mutableStateOf("") }
    var dayInput by rememberSaveable { mutableStateOf(todayDay.toString()) }
    var monthInput by rememberSaveable { mutableStateOf(todayMonth.toString()) }
    var phoneInput by rememberSaveable { mutableStateOf("") }
    var wishInput by rememberSaveable { mutableStateOf("") }
    var selectedTabCount by rememberSaveable { mutableIntStateOf(0) }

    val todaysBirthdays = remember(birthdays, todayDay, todayMonth) {
        birthdays.filter { it.birthDay == todayDay && it.birthMonth == todayMonth }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (todaysBirthdays.isNotEmpty()) {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "🎂 আজ জন্মদিন (${todaysBirthdays.size} জন)!",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    todaysBirthdays.forEach { entry ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "${entry.personName} (${entry.birthDay}/${entry.birthMonth})",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f)
                            )
                            Button(
                                onClick = { onSendBirthdayWishNow(entry) },
                                modifier = Modifier.bubbleTouchEffect()
                            ) {
                                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("উইশ পাঠান")
                            }
                        }
                    }
                }
            }
        }

        Card(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Filled.Cake, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Column {
                        Text(
                            text = "বার্থডে উইশ ও অটো রিমাইন্ডার",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "ভয়েসেও বলতে পারেন: 'রহিমের জন্মদিন ১৫ নভেম্বর সেভ করো' বা 'রহিমকে বার্থডে উইশ পাঠাও'",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                OutlinedTextField(
                    value = personName,
                    onValueChange = { personName = it },
                    label = { Text("প্রিয়জনের নাম (যেমন: রহিম)") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("birthday_name_input")
                )

                val dayParsed = dayInput.toIntOrNull()
                val monthParsed = monthInput.toIntOrNull()
                val dayError = dayInput.isNotBlank() && (dayParsed == null || dayParsed !in 1..31)
                val monthError = monthInput.isNotBlank() && (monthParsed == null || monthParsed !in 1..12)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = dayInput,
                        onValueChange = { dayInput = it.filter { ch -> ch.isDigit() }.take(2) },
                        label = { Text("তারিখ (১-৩১)") },
                        isError = dayError,
                        supportingText = if (dayError) { { Text("১ থেকে ৩১ তারিখ লিখুন") } } else null,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("birthday_day_input")
                    )
                    OutlinedTextField(
                        value = monthInput,
                        onValueChange = { monthInput = it.filter { ch -> ch.isDigit() }.take(2) },
                        label = { Text("মাস (১-১২)") },
                        isError = monthError,
                        supportingText = if (monthError) { { Text("১ থেকে ১২ মাস লিখুন") } } else null,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("birthday_month_input")
                    )
                }

                OutlinedTextField(
                    value = phoneInput,
                    onValueChange = { phoneInput = it },
                    label = { Text("ফোন / WhatsApp নম্বর (ঐচ্ছিক)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("birthday_phone_input")
                )

                OutlinedTextField(
                    value = wishInput,
                    onValueChange = { wishInput = it },
                    label = { Text("শুভেচ্ছা বার্তা (খালি রাখলে সুন্দর বাংলা উইশ তৈরি হবে)") },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("birthday_wish_input")
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedButton(
                        onClick = {
                            onGenerateAiWish(personName, "প্রিয়জন") { generated ->
                                wishInput = generated
                            }
                        },
                        enabled = !isBusy,
                        modifier = Modifier
                            .weight(1f)
                            .bubbleTouchEffect()
                            .testTag("birthday_ai_generate_button")
                    ) {
                        Icon(Icons.Filled.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("AI উইশ লিখুন")
                    }

                    Button(
                        onClick = {
                            val d = (dayParsed ?: todayDay).coerceIn(1, 31)
                            val m = (monthParsed ?: todayMonth).coerceIn(1, 12)
                            onSaveBirthday(personName, d, m, phoneInput, wishInput)
                            personName = ""
                            phoneInput = ""
                            wishInput = ""
                            selectedTabCount++
                        },
                        enabled = personName.isNotBlank() && !dayError && !monthError,
                        modifier = Modifier
                            .weight(1f)
                            .bubbleTouchEffect()
                            .testTag("save_birthday_button")
                    ) {
                        Icon(Icons.Filled.Cake, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("সেভ করুন")
                    }
                }

                if (isBusy) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        }

        if (birthdays.isNotEmpty()) {
            birthdays.forEach { item ->
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "🎂 ${item.personName} — ${item.birthDay}/${item.birthMonth}",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                if (item.phoneOrContact.isNotBlank()) {
                                    Text(
                                        text = "নম্বর: ${item.phoneOrContact}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Row {
                                IconButton(onClick = { onSpeakText(item.wishMessage) }) {
                                    Icon(
                                        Icons.AutoMirrored.Filled.VolumeUp,
                                        contentDescription = "Read Birthday Wish",
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                                IconButton(onClick = { onDeleteBirthday(item.id) }) {
                                    Icon(
                                        Icons.Filled.Delete,
                                        contentDescription = "Delete Birthday"
                                    )
                                }
                            }
                        }

                        Text(
                            text = item.wishMessage,
                            style = MaterialTheme.typography.bodyMedium
                        )

                        Button(
                            onClick = { onSendBirthdayWishNow(item) },
                            modifier = Modifier
                                .align(Alignment.End)
                                .bubbleTouchEffect()
                                .testTag("send_birthday_wish_${item.id}")
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("উইশ পাঠান (WhatsApp / SMS)")
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AllDayVoiceDiaryPanel(
    allDayRecordingEnabled: Boolean,
    summaryHour: Int,
    summaryMinute: Int,
    summaryLanguage: String,
    summaryEmail: String,
    noiseDb: Int,
    logs: List<String>,
    summaryOutput: String,
    isBusy: Boolean,
    onToggleRecording: (Boolean) -> Unit,
    onSaveSchedule: (Int, Int, String, String) -> Unit,
    onGenerateSummaryNow: () -> Unit,
    onSpeakText: (String) -> Unit
) {
    val context = LocalContext.current
    var hourText by rememberSaveable(summaryHour) { mutableStateOf(summaryHour.toString()) }
    var minuteText by rememberSaveable(summaryMinute) { mutableStateOf(summaryMinute.toString().padStart(2, '0')) }
    var selectedLang by rememberSaveable(summaryLanguage) { mutableStateOf(summaryLanguage) }
    var emailInput by rememberSaveable(summaryEmail) { mutableStateOf(summaryEmail) }

    val micPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            onToggleRecording(true)
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "সারাদিনের ভয়েস ডায়েরি ও মিটিং সামারি (আনলিমিটেড)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "আপনার অনুমতিতে সারাদিনের কথা ও মিটিং ব্যাকগ্রাউন্ড নয়েজ ফিল্টারসহ রেকর্ড থাকবে এবং দিনশেষে নির্ধারিত সময়ে বাংলা বা ইংরেজিতে সারাংশ ও ইমেইল পাঠাবে।",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (allDayRecordingEnabled) "🎙️ অনুমতি সাপেক্ষে রেকর্ডিং চালু আছে" else "রেকর্ডিং বন্ধ আছে (অনুমতি দিন)",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "ব্যাকগ্রাউন্ড নয়েজ লেভেল: ${noiseDb} dB • মোট লগ: ${logs.size}টি",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Button(
                        onClick = {
                            if (allDayRecordingEnabled) {
                                onToggleRecording(false)
                            } else {
                                val hasMic = ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.RECORD_AUDIO
                                ) == PackageManager.PERMISSION_GRANTED
                                if (hasMic) onToggleRecording(true)
                                else micPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        },
                        modifier = Modifier
                            .bubbleTouchEffect()
                            .testTag("toggle_allday_recording_button")
                    ) {
                        Text(if (allDayRecordingEnabled) "বন্ধ করুন" else "অনুমতি দিন ও চালু করুন")
                    }
                }

                val hourParsed = hourText.toIntOrNull()
                val minuteParsed = minuteText.toIntOrNull()
                val hourError = hourText.isNotBlank() && (hourParsed == null || hourParsed !in 0..23)
                val minuteError = minuteText.isNotBlank() && (minuteParsed == null || minuteParsed !in 0..59)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = hourText,
                        onValueChange = { hourText = it.filter { ch -> ch.isDigit() }.take(2) },
                        label = { Text("ঘণ্টা (০-২৩)") },
                        isError = hourError,
                        supportingText = if (hourError) { { Text("০–২৩ দিন") } } else null,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = minuteText,
                        onValueChange = { minuteText = it.filter { ch -> ch.isDigit() }.take(2) },
                        label = { Text("মিনিট (০-৫৯)") },
                        isError = minuteError,
                        supportingText = if (minuteError) { { Text("০–৫৯ দিন") } } else null,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                }

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    AssistChip(
                        onClick = { selectedLang = "BN" },
                        label = { Text(if (selectedLang == "BN") "✓ বাংলা সামারি" else "বাংলা সামারি") }
                    )
                    AssistChip(
                        onClick = { selectedLang = "EN" },
                        label = { Text(if (selectedLang == "EN") "✓ English Summary" else "English Summary") }
                    )
                }

                OutlinedTextField(
                    value = emailInput,
                    onValueChange = { emailInput = it },
                    label = { Text("সামারি পাঠানোর ইমেইল (ঐচ্ছিক)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("allday_summary_email_input")
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedButton(
                        onClick = {
                            val h = hourText.toIntOrNull() ?: 21
                            val m = minuteText.toIntOrNull() ?: 0
                            onSaveSchedule(h, m, selectedLang, emailInput)
                        },
                        modifier = Modifier
                            .weight(1f)
                            .bubbleTouchEffect()
                            .testTag("save_allday_schedule_button")
                    ) {
                        Text("সময় ও ইমেইল সেভ")
                    }

                    Button(
                        onClick = onGenerateSummaryNow,
                        enabled = !isBusy,
                        modifier = Modifier
                            .weight(1f)
                            .bubbleTouchEffect()
                            .testTag("generate_allday_summary_button")
                    ) {
                        Text("এখনই সামারি ও ইমেইল")
                    }
                }

                if (isBusy) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        }

        if (summaryOutput.isNotBlank()) {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "আজকের কাজের ও মিটিংয়ের সারাংশ",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        IconButton(onClick = { onSpeakText(summaryOutput) }) {
                            Icon(
                                Icons.AutoMirrored.Filled.VolumeUp,
                                contentDescription = "Read Summary",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    Text(
                        text = summaryOutput,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}
