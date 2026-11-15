package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Emergency
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.cloud.model.GestureTransferSession
import com.example.data.local.BirthdayReminderEntity
import java.io.File

private data class EmergencyHelplineItem(
    val number: String,
    val titleBn: String,
    val subtitleBn: String
)

private val BANGLADESH_HELPLINES = listOf(
    EmergencyHelplineItem(
        number = "999",
        titleBn = "জাতীয় জরুরি সেবা (৯৯৯)",
        subtitleBn = "পুলিশ, ফায়ার সার্ভিস ও অ্যাম্বুলেন্স সহায়তা (২৪ ঘণ্টা টোল-ফ্রি)"
    ),
    EmergencyHelplineItem(
        number = "333",
        titleBn = "জাতীয় তথ্য ও সেবা (৩৩৩)",
        subtitleBn = "সরকারি সেবা, নাগরিক সমস্যা ও জরুরি তথ্য সহায়তা"
    ),
    EmergencyHelplineItem(
        number = "16263",
        titleBn = "স্বাস্থ্য বাতায়ন (১৬২৬৩)",
        subtitleBn = "২৪ ঘণ্টা সরকারি ডাক্তারের পরামর্শ ও স্বাস্থ্যসেবা"
    ),
    EmergencyHelplineItem(
        number = "109",
        titleBn = "নারী ও শিশু সহায়তা (১০৯)",
        subtitleBn = "নারী ও শিশু সুরক্ষা এবং জরুরি সহায়তা হেল্পলাইন"
    ),
    EmergencyHelplineItem(
        number = "105",
        titleBn = "জাতীয় পরিচয়পত্র সেবা (১০৫)",
        subtitleBn = "NID কার্ড সংশোধন, নতুন নিবন্ধন ও নির্বাচন কমিশন তথ্য"
    ),
    EmergencyHelplineItem(
        number = "16430",
        titleBn = "সরকারি আইনি সহায়তা (১৬৪৩০)",
        subtitleBn = "বিনামূল্যে সরকারি আইনগত পরামর্শ ও সহায়তা"
    )
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SmartHubScreen(
    externalGestureTrigger: Int = 0,
    externalDiaryTrigger: Int = 0,
    onConsumeGestureTrigger: () -> Unit = {},
    onConsumeDiaryTrigger: () -> Unit = {},
    isCloudSignedIn: Boolean = false,
    gesturePairingCode: String = "SHUVO26",
    gestureStatusMessage: String = "",
    isGestureBusy: Boolean = false,
    pendingIncomingTransfers: List<GestureTransferSession> = emptyList(),
    receivedTransferFiles: List<File> = emptyList(),
    generatedFiles: List<File> = emptyList(),
    birthdays: List<BirthdayReminderEntity> = emptyList(),
    onUpdatePairingCode: (String) -> Unit = {},
    onGrabFileByFist: (String, String, ByteArray, String) -> Unit = { _, _, _, _ -> },
    onDropFileByOpenPalm: () -> Unit = {},
    onOpenFile: (String) -> Unit = {},
    onOpenSignInModal: () -> Unit = {},
    onSaveBirthday: (String, Int, Int, String, String) -> Unit = { _, _, _, _, _ -> },
    onDeleteBirthday: (String) -> Unit = {},
    onSendBirthdayWishNow: (BirthdayReminderEntity) -> Unit = {},
    onGenerateAiWish: (String, String, (String) -> Unit) -> Unit = { _, _, _ -> },
    allDayRecordingEnabled: Boolean = false,
    allDaySummaryHour: Int = 21,
    allDaySummaryMinute: Int = 0,
    allDaySummaryLanguage: String = "BN",
    allDaySummaryEmail: String = "",
    allDayNoiseDb: Int = 34,
    allDayLogs: List<String> = emptyList(),
    dailySummaryOutput: String = "",
    isDailySummaryBusy: Boolean = false,
    onToggleAllDayRecording: (Boolean) -> Unit = {},
    onSaveAllDaySchedule: (Int, Int, String, String) -> Unit = { _, _, _, _ -> },
    onGenerateDailySummaryNow: () -> Unit = {},
    smartWriterOutput: String,
    isSmartWriterBusy: Boolean,
    onRunSmartWriter: (String, String) -> Unit,
    onClearSmartWriter: () -> Unit,
    onSpeakText: (String) -> Unit,
    onDialEmergency: (String) -> Unit
) {
    val clipboard = LocalClipboardManager.current
    // 0 = Gesture Transfer, 1 = Birthday Wish, 2 = Translator & Writer, 3 = Emergency Helplines, 4 = All-Day Diary
    var selectedSection by rememberSaveable { mutableIntStateOf(0) }
    var writerInput by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(externalGestureTrigger) {
        if (externalGestureTrigger > 0) {
            selectedSection = 0
            onConsumeGestureTrigger()
        }
    }

    LaunchedEffect(externalDiaryTrigger) {
        if (externalDiaryTrigger > 0) {
            selectedSection = 4
            onGenerateDailySummaryNow()
            onConsumeDiaryTrigger()
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(18.dp))
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Widgets,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "স্মার্ট হাব (Smart Utility Hub)",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "হাতের ইশারায় ফাইল ট্রান্সফার, বার্থডে উইশ, অনুবাদক ও জরুরি সেবা",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        item {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                FilterChip(
                    selected = selectedSection == 0,
                    onClick = { selectedSection = 0 },
                    label = { Text("ইশারায় ট্রান্সফার") },
                    modifier = Modifier.testTag("smart_hub_gesture_chip")
                )
                FilterChip(
                    selected = selectedSection == 1,
                    onClick = { selectedSection = 1 },
                    label = { Text("বার্থডে উইশ") },
                    modifier = Modifier.testTag("smart_hub_birthday_chip")
                )
                FilterChip(
                    selected = selectedSection == 2,
                    onClick = { selectedSection = 2 },
                    label = { Text("অনুবাদক ও লেখক") },
                    modifier = Modifier.testTag("smart_hub_translator_chip")
                )
                FilterChip(
                    selected = selectedSection == 3,
                    onClick = { selectedSection = 3 },
                    label = { Text("জরুরি হেল্পলাইন") },
                    modifier = Modifier.testTag("smart_hub_emergency_chip")
                )
                FilterChip(
                    selected = selectedSection == 4,
                    onClick = { selectedSection = 4 },
                    label = { Text("সারাদিনের ভয়েস ডায়েরি") },
                    modifier = Modifier.testTag("smart_hub_allday_voice_chip")
                )
            }
        }

        when (selectedSection) {
            0 -> {
                item {
                    GestureAirTransferPanel(
                        isCloudSignedIn = isCloudSignedIn,
                        gesturePairingCode = gesturePairingCode,
                        gestureStatusMessage = gestureStatusMessage,
                        isGestureBusy = isGestureBusy,
                        pendingIncomingTransfers = pendingIncomingTransfers,
                        receivedTransferFiles = receivedTransferFiles,
                        generatedFiles = generatedFiles,
                        onUpdatePairingCode = onUpdatePairingCode,
                        onGrabFileByFist = onGrabFileByFist,
                        onDropFileByOpenPalm = onDropFileByOpenPalm,
                        onOpenFile = onOpenFile,
                        onOpenSignInModal = onOpenSignInModal
                    )
                }
            }

            1 -> {
                item {
                    BirthdayWishManagerPanel(
                        birthdays = birthdays,
                        isBusy = isSmartWriterBusy,
                        onSaveBirthday = onSaveBirthday,
                        onDeleteBirthday = onDeleteBirthday,
                        onSendBirthdayWishNow = onSendBirthdayWishNow,
                        onGenerateAiWish = onGenerateAiWish,
                        onSpeakText = onSpeakText
                    )
                }
            }

            4 -> {
                item {
                    AllDayVoiceDiaryPanel(
                        allDayRecordingEnabled = allDayRecordingEnabled,
                        summaryHour = allDaySummaryHour,
                        summaryMinute = allDaySummaryMinute,
                        summaryLanguage = allDaySummaryLanguage,
                        summaryEmail = allDaySummaryEmail,
                        noiseDb = allDayNoiseDb,
                        logs = allDayLogs,
                        summaryOutput = dailySummaryOutput,
                        isBusy = isDailySummaryBusy,
                        onToggleRecording = onToggleAllDayRecording,
                        onSaveSchedule = onSaveAllDaySchedule,
                        onGenerateSummaryNow = onGenerateDailySummaryNow,
                        onSpeakText = onSpeakText
                    )
                }
            }

            2 -> {
                item {
                    Card(
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Filled.Translate,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "স্মার্ট অনুবাদক ও চিঠি/ইমেইল লেখক",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            OutlinedTextField(
                                value = writerInput,
                                onValueChange = { writerInput = it },
                                label = { Text("যেকোনো বাংলা, Banglish বা ইংরেজি লেখা দিন...") },
                                minLines = 3,
                                maxLines = 6,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("smart_writer_input")
                            )

                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = {
                                        onRunSmartWriter(
                                            writerInput,
                                            "Translate accurately into clear, natural English."
                                        )
                                    },
                                    enabled = writerInput.isNotBlank() && !isSmartWriterBusy
                                ) {
                                    Text("বাংলা → English")
                                }
                                Button(
                                    onClick = {
                                        onRunSmartWriter(
                                            writerInput,
                                            "Translate accurately into natural, standard Bangla (প্রমিত বাংলা)."
                                        )
                                    },
                                    enabled = writerInput.isNotBlank() && !isSmartWriterBusy
                                ) {
                                    Text("English → বাংলা")
                                }
                                OutlinedButton(
                                    onClick = {
                                        onRunSmartWriter(
                                            writerInput,
                                            "Fix all spelling/grammar mistakes and rewrite neatly in the same language (Bangla or English)."
                                        )
                                    },
                                    enabled = writerInput.isNotBlank() && !isSmartWriterBusy
                                ) {
                                    Icon(Icons.Filled.AutoFixHigh, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("বানান ও বাক্য শুদ্ধ করুন")
                                }
                                OutlinedButton(
                                    onClick = {
                                        onRunSmartWriter(
                                            writerInput,
                                            "Draft a polite, professional official email or application based on this request in both Bangla and English."
                                        )
                                    },
                                    enabled = writerInput.isNotBlank() && !isSmartWriterBusy
                                ) {
                                    Text("অফিসিয়াল চিঠি/ইমেইল")
                                }
                            }

                            if (isSmartWriterBusy) {
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            }
                        }
                    }
                }

                if (smartWriterOutput.isNotBlank()) {
                    item {
                        Card(
                            shape = RoundedCornerShape(18.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "ফলাফল (Result)",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Row {
                                        IconButton(onClick = { onSpeakText(smartWriterOutput) }) {
                                            Icon(
                                                imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                                                contentDescription = "Read Output Aloud",
                                                tint = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                        IconButton(
                                            onClick = {
                                                clipboard.setText(AnnotatedString(smartWriterOutput))
                                            }
                                        ) {
                                            Icon(
                                                imageVector = Icons.Filled.ContentCopy,
                                                contentDescription = "Copy Output"
                                            )
                                        }
                                        IconButton(onClick = onClearSmartWriter) {
                                            Icon(
                                                imageVector = Icons.Filled.Clear,
                                                contentDescription = "Clear Output"
                                            )
                                        }
                                    }
                                }
                                SelectionContainer {
                                    Column(
                                        modifier = Modifier
                                            .heightIn(max = 320.dp)
                                            .verticalScroll(rememberScrollState())
                                    ) {
                                        Text(
                                            text = smartWriterOutput,
                                            style = MaterialTheme.typography.bodyLarge
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            else -> {
                item {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Emergency,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "জরুরি প্রয়োজনে ট্যাপ করুন অথবা সরাসরি ভয়েসে বলুন 'হে শুভ, ৯৯৯ এ কল দাও'",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }

                items(BANGLADESH_HELPLINES, key = { it.number }) { item ->
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = item.titleBn,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = item.subtitleBn,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Button(
                                onClick = { onDialEmergency(item.number) },
                                modifier = Modifier.testTag("dial_helpline_${item.number}")
                            ) {
                                Icon(Icons.Filled.Call, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(item.number, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}
