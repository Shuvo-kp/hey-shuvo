package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Publish
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.core.preferences.AppThemePalette
import com.example.core.preferences.AssistantSettings
import com.example.data.cloud.model.AuditEntry
import com.example.data.cloud.model.FeatureRequest
import com.example.data.cloud.model.SkillModule
import com.example.feature.customer.MasterAiDraft
import com.example.ui.components.bubbleTouchEffect

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MasterControlScreen(
    settings: AssistantSettings,
    isCloudSignedIn: Boolean,
    isOwnerAdmin: Boolean,
    canClaimMasterOwner: Boolean,
    isMasterBusy: Boolean,
    isMasterVoiceListening: Boolean,
    masterAiDraft: MasterAiDraft?,
    allSkills: List<SkillModule>,
    allFeatureRequests: List<FeatureRequest>,
    auditLogs: List<AuditEntry>,
    onOpenSignInSheet: () -> Unit,
    onClaimMasterOwner: () -> Unit,
    onStartMasterVoice: () -> Unit,
    onStopMasterVoice: () -> Unit,
    onRunMasterCommand: (String) -> Unit,
    onApplyMasterDraft: () -> Unit,
    onDismissMasterDraft: () -> Unit,
    onSaveAndApplyConfig: (
        announcementBanner: String,
        customSystemPrompt: String,
        enableOfficeDocs: Boolean,
        enableTallyKhata: Boolean,
        enablePhotoVision: Boolean,
        enableWindowsBridge: Boolean,
        defaultAutoVoice: Boolean,
        themePaletteName: String
    ) -> Unit,
    onToggleSkill: (SkillModule, Boolean) -> Unit,
    onDeleteSkill: (SkillModule) -> Unit,
    onResolveFeatureRequest: (FeatureRequest) -> Unit,
    onInviteCircleMember: (String, String) -> Unit,
    onSaveAutoEmailConfig: (String, String) -> Unit = { _, _ -> },
    onTriggerAutoEmailCheck: () -> Unit = {},
    allAccounts: List<com.example.data.cloud.model.UserAccount> = emptyList(),
    onSavePricingConfig: (Long, Long) -> Unit = { _, _ -> },
    onSetGlobalPaidMode: (Boolean) -> Unit = {},
    onSetCurrentUserPaid: (Boolean, Int) -> Unit = { _, _ -> },
    onUpdateMemberTier: (String, Boolean) -> Unit = { _, _ -> },
    onGrantBonusFreeUses: (Long) -> Unit = {},
    onUpdateMasterPassword: (String, String, (Boolean) -> Unit) -> Unit = { _, _, _ -> }
) {
    val context = LocalContext.current
    var isMasterUnlocked by rememberSaveable { mutableStateOf(false) }
    var loginEmail by rememberSaveable { mutableStateOf("") }
    var loginPassword by rememberSaveable { mutableStateOf("") }
    var passwordVisible by rememberSaveable { mutableStateOf(false) }
    var authError by rememberSaveable { mutableStateOf("") }

    var oldPassInput by rememberSaveable { mutableStateOf("") }
    var newPassInput by rememberSaveable { mutableStateOf("") }
    var confirmPassInput by rememberSaveable { mutableStateOf("") }
    var passChangeStatus by rememberSaveable { mutableStateOf("") }

    var masterPromptText by rememberSaveable { mutableStateOf("") }
    var bannerInput by remember(settings.masterAnnouncementBanner) { mutableStateOf(settings.masterAnnouncementBanner) }
    var systemRuleInput by remember(settings.masterCustomSystemPrompt) { mutableStateOf(settings.masterCustomSystemPrompt) }
    val flagsUpper = settings.masterEnabledFeatureFlags.uppercase()
    var officeEnabled by remember(settings.masterEnabledFeatureFlags) { mutableStateOf("OFFICE_SUITE" in flagsUpper) }
    var tallyEnabled by remember(settings.masterEnabledFeatureFlags) { mutableStateOf("TALLY_KHATA" in flagsUpper) }
    var photoEnabled by remember(settings.masterEnabledFeatureFlags) { mutableStateOf("PHOTO_VISION" in flagsUpper) }
    var windowsEnabled by remember(settings.masterEnabledFeatureFlags) { mutableStateOf("WINDOWS_BRIDGE" in flagsUpper) }
    var defaultAutoVoice by remember(settings.autoAllDisplayVoice) { mutableStateOf(settings.autoAllDisplayVoice) }
    var chosenPalette by remember(settings.themePalette) { mutableStateOf(settings.themePalette.name) }

    var inviteEmail by rememberSaveable { mutableStateOf("") }
    var inviteName by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(settings.masterAnnouncementBanner, settings.masterCustomSystemPrompt) {
        bannerInput = settings.masterAnnouncementBanner
        systemRuleInput = settings.masterCustomSystemPrompt
    }

    if (!isMasterUnlocked) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp),
            contentPadding = PaddingValues(vertical = 24.dp),
            verticalArrangement = Arrangement.Center
        ) {
            item {
                Card(
                    shape = RoundedCornerShape(22.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Lock,
                            contentDescription = "Master Security Lock",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(48.dp)
                        )
                        Text(
                            text = "মাস্টার কন্ট্রোল সিকিউরিটি লগইন",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "মাস্টার ফাইল সম্পূর্ণ সুরক্ষিত। প্রবেশ করতে নির্ধারিত অ্যাডমিন ইমেইল এবং মাস্টার পাসওয়ার্ড দিন।",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (authError.isNotBlank()) {
                            Text(
                                text = authError,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.error,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        OutlinedTextField(
                            value = loginEmail,
                            onValueChange = {
                                loginEmail = it
                                authError = ""
                            },
                            label = { Text("মাস্টার অনুমোদিত ইমেইল") },
                            placeholder = { Text("shuvoskp3@gmail.com") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("master_login_email_input")
                        )
                        OutlinedTextField(
                            value = loginPassword,
                            onValueChange = {
                                loginPassword = it
                                authError = ""
                            },
                            label = { Text("মাস্টার পাসওয়ার্ড") },
                            singleLine = true,
                            visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                    Icon(
                                        imageVector = if (passwordVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                        contentDescription = null
                                    )
                                }
                            },
                            modifier = Modifier.fillMaxWidth().testTag("master_login_password_input")
                        )
                        Button(
                            onClick = {
                                val cleanEmail = loginEmail.trim().lowercase()
                                val authorizedEmails = settings.masterAuthorizedEmails.split(",").map { it.trim().lowercase() }.filter { it.isNotBlank() }
                                val isEmailValid = cleanEmail in authorizedEmails
                                val isPassValid = loginPassword == settings.masterPassword
                                if (isEmailValid && isPassValid) {
                                    isMasterUnlocked = true
                                    loginPassword = ""
                                    authError = ""
                                } else {
                                    authError = "❌ অননুমোদিত ইমেইল অথবা ভুল পাসওয়ার্ড! সঠিক তথ্য দিন।"
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .bubbleTouchEffect(MaterialTheme.colorScheme.primary)
                                .testTag("master_unlock_button")
                        ) {
                            Text("মাস্টার ফাইলে প্রবেশ করুন", fontWeight = FontWeight.Bold)
                        }
                        if (isOwnerAdmin && isCloudSignedIn) {
                            OutlinedButton(
                                onClick = {
                                    isMasterUnlocked = true
                                    loginPassword = ""
                                    authError = ""
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .testTag("master_cloud_owner_unlock_button")
                            ) {
                                Text("✓ ক্লাউড Owner অ্যাকাউন্ট দিয়ে আনলক করুন", fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }
        }
        return
    }

    val micPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) onStartMasterVoice()
    }

    val handleMasterMicTap = {
        if (isMasterVoiceListening) {
            onStopMasterVoice()
        } else {
            val hasPerm = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            if (hasPerm) onStartMasterVoice()
            else micPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp),
        contentPadding = PaddingValues(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 1. Master App Header & Sync Status
        item {
            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.AdminPanelSettings,
                            contentDescription = "Master App Control",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(26.dp)
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Master App • কাস্টমার অ্যাপ কন্ট্রোল স্টুডিও",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = when {
                                    isOwnerAdmin -> "লাইভ লোকাল ও ক্লাউড রিয়েল-টাইম সিঙ্ক সক্রিয়"
                                    isCloudSignedIn && canClaimMasterOwner -> "এই ডিভাইসে তাৎক্ষণিক সিঙ্ক সক্রিয় • ক্লাউড মাস্টার সক্রিয় করতে নিচে ট্যাপ করুন"
                                    else -> "এই ডিভাইসে Customer App-এ তাৎক্ষণিক সিঙ্ক সক্রিয় (রিমোট ডিভাইস সিঙ্কের জন্য Google যুক্ত করুন)"
                                },
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        IconButton(
                            onClick = { isMasterUnlocked = false },
                            modifier = Modifier.testTag("master_lock_header_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Lock,
                                contentDescription = "লক করুন",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }

                    if (!isCloudSignedIn) {
                        OutlinedButton(
                            onClick = onOpenSignInSheet,
                            modifier = Modifier
                                .bubbleTouchEffect(MaterialTheme.colorScheme.primary)
                                .testTag("master_connect_google_button")
                        ) {
                            Icon(Icons.Filled.CloudDone, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("ঐচ্ছিক ক্লাউড মাস্টার সিঙ্ক যুক্ত করুন")
                        }
                    } else if (!isOwnerAdmin && canClaimMasterOwner) {
                        Button(
                            onClick = onClaimMasterOwner,
                            modifier = Modifier
                                .bubbleTouchEffect(MaterialTheme.colorScheme.primary)
                                .testTag("master_claim_owner_button")
                        ) {
                            Icon(Icons.Filled.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Cloud Master Owner হিসেবে সক্রিয় করুন")
                        }
                    }
                }
            }
        }

        // 2. Voice & Text AI Command Studio ("এই সমস্যাটা ঠিক করে দাও" / "এই featureটা যোগ করে দাও")
        item {
            Card(
                shape = RoundedCornerShape(18.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "ভয়েস বা টেক্সট দিয়ে সমস্যা সমাধান ও নতুন ফিচার তৈরি",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "মাইকে ট্যাপ করে বা লিখে বলুন—যেমন: “এই সমস্যাটা ঠিক করে দাও” অথবা “এই featureটা Customer App-এ যোগ করে দাও”।",
                        style = MaterialTheme.typography.bodySmall
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = masterPromptText,
                            onValueChange = { masterPromptText = it },
                            placeholder = {
                                Text(
                                    if (isMasterVoiceListening) "বস, আপনার নির্দেশ শুনছি..."
                                    else "মাস্টার নির্দেশ লিখুন বা মাইকে বলুন..."
                                )
                            },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("master_command_input")
                        )

                        IconButton(
                            onClick = handleMasterMicTap,
                            modifier = Modifier
                                .size(48.dp)
                                .bubbleTouchEffect(
                                    bubbleColor = if (isMasterVoiceListening) MaterialTheme.colorScheme.error
                                    else MaterialTheme.colorScheme.primary
                                )
                                .testTag("master_voice_mic_button")
                        ) {
                            Icon(
                                imageVector = if (isMasterVoiceListening) Icons.Filled.Stop else Icons.Filled.Mic,
                                contentDescription = "Master Voice Input",
                                tint = if (isMasterVoiceListening) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                            )
                        }

                        Button(
                            onClick = {
                                val cmd = masterPromptText.trim()
                                if (cmd.isNotBlank()) {
                                    masterPromptText = ""
                                    onRunMasterCommand(cmd)
                                }
                            },
                            enabled = !isMasterBusy && masterPromptText.isNotBlank(),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                            modifier = Modifier
                                .bubbleTouchEffect(MaterialTheme.colorScheme.primary)
                                .testTag("master_command_run_button")
                        ) {
                            if (isMasterBusy) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                            } else {
                                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Execute Master Command")
                            }
                        }
                    }

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        AssistChip(
                            onClick = {
                                onRunMasterCommand("এই সমস্যাটা ঠিক করে দাও: Customer App-এ সব উত্তর সংক্ষিপ্ত, স্পষ্ট ও দ্রুত বাংলায় দেবে।")
                            },
                            label = { Text("উদাহরণ: সমস্যা সমাধান") },
                            leadingIcon = { Icon(Icons.Filled.AutoFixHigh, contentDescription = null, modifier = Modifier.size(16.dp)) },
                            modifier = Modifier.bubbleTouchEffect(MaterialTheme.colorScheme.primary)
                        )
                        AssistChip(
                            onClick = {
                                onRunMasterCommand("এই featureটা Customer App-এ যোগ করে দাও: প্রতিদিনের বাজার ও খরচের দ্রুত ভয়েস হিসাব ও পরামর্শ।")
                            },
                            label = { Text("উদাহরণ: নতুন ফিচার যোগ") },
                            leadingIcon = { Icon(Icons.Filled.Publish, contentDescription = null, modifier = Modifier.size(16.dp)) },
                            modifier = Modifier.bubbleTouchEffect(MaterialTheme.colorScheme.secondary)
                        )
                    }

                    if (masterAiDraft != null) {
                        HorizontalDivider()
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = "প্রস্তুতকৃত সমাধান / নতুন ফিচার (${masterAiDraft.actionType})",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(text = masterAiDraft.summaryReplyBn, style = MaterialTheme.typography.bodyMedium)
                                if (masterAiDraft.skillTitle.isNotBlank()) {
                                    Text(
                                        text = "নতুন মডিউল: ${masterAiDraft.skillTitle}",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                                if (masterAiDraft.proposedSystemRule.isNotBlank()) {
                                    Text(
                                        text = "রুল: ${masterAiDraft.proposedSystemRule}",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Button(
                                        onClick = onApplyMasterDraft,
                                        modifier = Modifier
                                            .weight(1f)
                                            .bubbleTouchEffect(MaterialTheme.colorScheme.primary)
                                            .testTag("master_apply_draft_button")
                                    ) {
                                        Icon(Icons.Filled.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Save & Apply (Customer App)")
                                    }
                                    OutlinedButton(
                                        onClick = onDismissMasterDraft,
                                        modifier = Modifier.bubbleTouchEffect(MaterialTheme.colorScheme.secondary)
                                    ) {
                                        Text("বাতিল")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // 3. Direct Customer App Modules, Banner & Theme Control + Save & Apply
        item {
            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "Customer App লাইভ মডিউল ও আপডেট কন্ট্রোল",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )

                    OutlinedTextField(
                        value = bannerInput,
                        onValueChange = { bannerInput = it },
                        label = { Text("Customer App লাইভ নোটিশ / ব্যানার") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("master_announcement_input")
                    )

                    OutlinedTextField(
                        value = systemRuleInput,
                        onValueChange = { systemRuleInput = it },
                        label = { Text("Customer AI নির্দেশনা / ফিক্স রুল") },
                        minLines = 2,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("master_system_rule_input")
                    )

                    MasterModuleToggleRow("অ্যাপ চালু হলেই Auto Voice সক্রিয়", defaultAutoVoice) { defaultAutoVoice = it }
                    MasterModuleToggleRow("Photo / Camera Vision মডিউল", photoEnabled) { photoEnabled = it }
                    MasterModuleToggleRow("Word, Excel, PDF ও AutoCAD মডিউল", officeEnabled) { officeEnabled = it }
                    MasterModuleToggleRow("দোকান ও ব্যবসার ট্যালি খাতা", tallyEnabled) { tallyEnabled = it }
                    MasterModuleToggleRow("Windows PC ব্রিজ মডিউল", windowsEnabled) { windowsEnabled = it }

                    Text(
                        text = "Customer App থিম নির্বাচন:",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        AppThemePalette.entries.filter { it != AppThemePalette.CUSTOM_COLOR }.forEach { pal ->
                            FilterChip(
                                selected = chosenPalette == pal.name,
                                onClick = { chosenPalette = pal.name },
                                label = { Text(pal.labelBn) },
                                modifier = Modifier.bubbleTouchEffect(MaterialTheme.colorScheme.primary)
                            )
                        }
                    }

                    Button(
                        onClick = {
                            onSaveAndApplyConfig(
                                bannerInput,
                                systemRuleInput,
                                officeEnabled,
                                tallyEnabled,
                                photoEnabled,
                                windowsEnabled,
                                defaultAutoVoice,
                                chosenPalette
                            )
                        },
                        enabled = !isMasterBusy,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .bubbleTouchEffect(MaterialTheme.colorScheme.primary)
                            .testTag("master_save_config_button")
                    ) {
                        Icon(Icons.Filled.Publish, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Save & Apply • Customer App-এ পাঠান", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // 4. Customer Issues & Feature Requests Queue
        if (allFeatureRequests.isNotEmpty()) {
            item {
                Text(
                    text = "কাস্টমারের সমস্যা ও নতুন ফিচারের অনুরোধ (${allFeatureRequests.size})",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
            }
            itemsIndexed(allFeatureRequests, key = { idx, item -> "req_${idx}_${item.id}" }) { _, req ->
                Card(
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(text = req.title, fontWeight = FontWeight.Bold)
                        Text(text = req.description, style = MaterialTheme.typography.bodySmall)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "স্ট্যাটাস: ${req.status.uppercase()}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Button(
                                onClick = { onResolveFeatureRequest(req) },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                modifier = Modifier.bubbleTouchEffect(MaterialTheme.colorScheme.primary)
                            ) {
                                Text("AI দিয়ে সমাধান ও প্রস্তুত করুন")
                            }
                        }
                    }
                }
            }
        }

        // 5. Modular Skills List
        if (allSkills.isNotEmpty()) {
            item {
                Text(
                    text = "সক্রিয় কাস্টমার স্কিল মডিউল (${allSkills.size})",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
            }
            itemsIndexed(allSkills, key = { idx, item -> "skill_${idx}_${item.id}" }) { _, sk ->
                Card(
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = sk.name, fontWeight = FontWeight.Bold)
                            Text(text = sk.description, style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(
                            checked = sk.enabled,
                            onCheckedChange = { onToggleSkill(sk, it) }
                        )
                        IconButton(onClick = { onDeleteSkill(sk) }) {
                            Icon(Icons.Filled.DeleteOutline, contentDescription = "Delete Skill")
                        }
                    }
                }
            }
        }

        // 6. Cloud Circle Member Invite (Only when Cloud Owner is active)
        if (isOwnerAdmin) {
            item {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("কাস্টমার সার্কেল মেম্বার আমন্ত্রণ", fontWeight = FontWeight.Bold)
                        OutlinedTextField(
                            value = inviteName,
                            onValueChange = { inviteName = it },
                            label = { Text("সদস্যের নাম") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = inviteEmail,
                            onValueChange = { inviteEmail = it },
                            label = { Text("সদস্যের Google Email") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Button(
                            onClick = {
                                if (inviteEmail.contains("@")) {
                                    onInviteCircleMember(inviteEmail.trim(), inviteName.trim())
                                    inviteEmail = ""
                                    inviteName = ""
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .bubbleTouchEffect(MaterialTheme.colorScheme.primary)
                        ) {
                            Icon(Icons.Filled.PersonAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("আমন্ত্রণ পাঠান")
                        }

                        if (auditLogs.isNotEmpty()) {
                            HorizontalDivider()
                            Text(
                                text = "সাম্প্রতিক মাস্টার অডিট লগ (${auditLogs.take(3).size})",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold
                            )
                            auditLogs.take(3).forEach { log ->
                                Text(
                                    text = "• ${log.action}: ${log.targetSummary}",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }
            }

            // 6. Master App 7-Type Automatic Email Automation
            item {
                val emailFlagsUpper = settings.autoEmailFlags.uppercase()
                var senderEmailInput by rememberSaveable(settings.masterSenderEmail) { mutableStateOf(settings.masterSenderEmail) }
                var welcomeMail by rememberSaveable(settings.autoEmailFlags) { mutableStateOf("WELCOME" in emailFlagsUpper) }
                var birthdayMail by rememberSaveable(settings.autoEmailFlags) { mutableStateOf("BIRTHDAY" in emailFlagsUpper) }
                var inactivityMail by rememberSaveable(settings.autoEmailFlags) { mutableStateOf("INACTIVITY_7D" in emailFlagsUpper) }
                var festivalMail by rememberSaveable(settings.autoEmailFlags) { mutableStateOf("FESTIVAL" in emailFlagsUpper) }
                var newFeatureMail by rememberSaveable(settings.autoEmailFlags) { mutableStateOf("NEW_FEATURE" in emailFlagsUpper) }
                var monthlySummaryMail by rememberSaveable(settings.autoEmailFlags) { mutableStateOf("MONTHLY_SUMMARY" in emailFlagsUpper) }
                var securityLoginMail by rememberSaveable(settings.autoEmailFlags) { mutableStateOf("SECURITY_LOGIN" in emailFlagsUpper) }

                Card(
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "অটোমেটিক ইমেইল অটোমেশন (৭টি স্মার্ট নিয়ম)",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        OutlinedTextField(
                            value = senderEmailInput,
                            onValueChange = { senderEmailInput = it },
                            label = { Text("প্রেরক ইমেইল (Master Sender Email)") },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("master_sender_email_input")
                        )
                        MasterModuleToggleRow("১. স্বাগত মেইল (নতুন গ্রাহকের জন্য)", welcomeMail) { welcomeMail = it }
                        MasterModuleToggleRow("২. জন্মদিনের শুভেচ্ছা মেইল", birthdayMail) { birthdayMail = it }
                        MasterModuleToggleRow("৩. ১ সপ্তাহ না এলে উৎসাহের মেইল", inactivityMail) { inactivityMail = it }
                        MasterModuleToggleRow("৪. ঈদ, পহেলা বৈশাখ ও বিজয় দিবসের শুভেচ্ছা", festivalMail) { festivalMail = it }
                        MasterModuleToggleRow("৫. নতুন ফিচার এলে জানানো", newFeatureMail) { newFeatureMail = it }
                        MasterModuleToggleRow("৬. মাসের শেষে 'এ মাসে কী কী করেছেন' সারাংশ", monthlySummaryMail) { monthlySummaryMail = it }
                        MasterModuleToggleRow("৭. নতুন ডিভাইসে লগইন হলে নিরাপত্তা মেইল", securityLoginMail) { securityLoginMail = it }

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Button(
                                onClick = {
                                    val flags = buildList {
                                        if (welcomeMail) add("WELCOME")
                                        if (birthdayMail) add("BIRTHDAY")
                                        if (inactivityMail) add("INACTIVITY_7D")
                                        if (festivalMail) add("FESTIVAL")
                                        if (newFeatureMail) add("NEW_FEATURE")
                                        if (monthlySummaryMail) add("MONTHLY_SUMMARY")
                                        if (securityLoginMail) add("SECURITY_LOGIN")
                                    }.joinToString(",")
                                    onSaveAutoEmailConfig(senderEmailInput, flags)
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .bubbleTouchEffect(MaterialTheme.colorScheme.primary)
                                    .testTag("save_master_auto_email_button")
                            ) {
                                Text("সেভ ও চালু করুন")
                            }
                            OutlinedButton(
                                onClick = onTriggerAutoEmailCheck,
                                modifier = Modifier
                                    .weight(1f)
                                    .bubbleTouchEffect(MaterialTheme.colorScheme.primary)
                                    .testTag("trigger_master_auto_email_button")
                            ) {
                                Text("এখনই চেক করুন")
                            }
                        }
                    }
                }
            }

            // 7. Monthly Paid Fee Editor & Customer Analytics Table
            item {
                var feeInput by rememberSaveable(settings.monthlyPaidFeeBdt) { mutableStateOf(settings.monthlyPaidFeeBdt.toString()) }
                var limitInput by rememberSaveable(settings.freeDailyLimit) { mutableStateOf(settings.freeDailyLimit.toString()) }

                Card(
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "মাসিক পেইড ফি ও কাস্টমার অ্যানালিটিক্স টেবিল",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = feeInput,
                                onValueChange = { feeInput = it.filter { ch -> ch.isDigit() }.take(5) },
                                label = { Text("মাসিক পেইড ফি (৳)") },
                                singleLine = true,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("master_monthly_fee_input")
                            )
                            OutlinedTextField(
                                value = limitInput,
                                onValueChange = { limitInput = it.filter { ch -> ch.isDigit() }.take(4) },
                                label = { Text("দৈনিক ফ্রি লিমিট") },
                                singleLine = true,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("master_free_limit_input")
                            )
                        }
                        Button(
                            onClick = {
                                val fee = feeInput.toLongOrNull() ?: 299L
                                val limit = limitInput.toLongOrNull() ?: 25L
                                onSavePricingConfig(fee, limit)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .bubbleTouchEffect(MaterialTheme.colorScheme.primary)
                                .testTag("save_master_pricing_button")
                        ) {
                            Text("মাসিক ফি ও ফ্রি লিমিট সেভ করুন")
                        }

                        HorizontalDivider()
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "গ্লোবাল পেইড সিস্টেম",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = if (settings.globalPaidModeEnabled) {
                                        "চালু: ফ্রি ইউজারদের লিমিট থাকবে, পেইড আনলিমিটেড"
                                    } else {
                                        "বন্ধ: সবাই সম্পূর্ণ আনলিমিটেড ফ্রি ব্যবহার করবে"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = settings.globalPaidModeEnabled,
                                onCheckedChange = onSetGlobalPaidMode,
                                modifier = Modifier.testTag("global_paid_mode_switch")
                            )
                        }

                        val isPaidNow = settings.currentUserIsPaid &&
                            (settings.currentUserPaidUntilMs == 0L || System.currentTimeMillis() <= settings.currentUserPaidUntilMs)
                        Text(
                            text = if (isPaidNow) {
                                val validityText = if (settings.currentUserPaidUntilMs == 0L) {
                                    "লাইফটাইম"
                                } else {
                                    val daysLeft = ((settings.currentUserPaidUntilMs - System.currentTimeMillis()) / (24L * 60L * 60L * 1000L)).coerceAtLeast(1L)
                                    "$daysLeft দিন বাকি"
                                }
                                "বর্তমান প্ল্যান: PAID (আনলিমিটেড • $validityText)"
                            } else {
                                "বর্তমান প্ল্যান: FREE (আজকের ব্যবহার: ${settings.dailyUsageCount} / ${settings.freeDailyLimit})"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = if (isPaidNow) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            OutlinedButton(
                                onClick = { onSetCurrentUserPaid(true, 30) },
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp)
                            ) {
                                Text("৩০ দিন Paid", style = MaterialTheme.typography.labelSmall)
                            }
                            OutlinedButton(
                                onClick = { onSetCurrentUserPaid(true, 0) },
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp)
                            ) {
                                Text("লাইফটাইম", style = MaterialTheme.typography.labelSmall)
                            }
                            OutlinedButton(
                                onClick = { onSetCurrentUserPaid(false, 0) },
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp)
                            ) {
                                Text("Free করুন", style = MaterialTheme.typography.labelSmall)
                            }
                            OutlinedButton(
                                onClick = { onGrantBonusFreeUses(10L) },
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp)
                            ) {
                                Text("+১০ বোনাস", style = MaterialTheme.typography.labelSmall)
                            }
                        }

                        HorizontalDivider()
                        Text(
                            text = "কাস্টমার ব্যবহার ও লিমিট টেবিল (${allAccounts.size} জন)",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold
                        )
                        if (allAccounts.isEmpty()) {
                            Text(
                                text = "• বর্তমান ডিভাইস: সক্রিয় (ফ্রি লিমিট: ${settings.freeDailyLimit}/দিন • পেইড: ৳${settings.monthlyPaidFeeBdt}/মাস)",
                                style = MaterialTheme.typography.bodySmall
                            )
                        } else {
                            allAccounts.take(15).forEachIndexed { idx, acc ->
                                val isOwnerAcc = acc.accountRole.equals("owner", ignoreCase = true)
                                val isPaidAcc = isOwnerAcc || acc.subscriptionTier.equals("paid", ignoreCase = true)
                                val statusLabel = if (isPaidAcc) {
                                    "পেইড আনলিমিটেড"
                                } else {
                                    "ফ্রি (${settings.freeDailyLimit}/দিন)"
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "${idx + 1}. ${acc.displayName.ifBlank { acc.email.ifBlank { acc.userId.take(8) } }}",
                                            style = MaterialTheme.typography.bodySmall,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        Text(
                                            text = statusLabel,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (isPaidAcc) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
                                        )
                                    }
                                    if (!isOwnerAcc) {
                                        OutlinedButton(
                                            onClick = { onUpdateMemberTier(acc.userId, !isPaidAcc) },
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                            modifier = Modifier.testTag("toggle_member_tier_${acc.userId}")
                                        ) {
                                            Text(
                                                text = if (isPaidAcc) "Free করুন" else "Paid দিন",
                                                style = MaterialTheme.typography.labelSmall
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Master Password Change Security Card
        item {
            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Key,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "🔐 মাস্টার পাসওয়ার্ড পরিবর্তন",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Text(
                        text = "আপনার মাস্টার ফাইলের নিরাপত্তা বজায় রাখতে যেকোনো সময় এখান থেকে নতুন পাসওয়ার্ড সেট করতে পারবেন।",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (passChangeStatus.isNotBlank()) {
                        Text(
                            text = passChangeStatus,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (passChangeStatus.startsWith("✓")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                        )
                    }
                    OutlinedTextField(
                        value = oldPassInput,
                        onValueChange = { oldPassInput = it },
                        label = { Text("বর্তমান পাসওয়ার্ড") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = newPassInput,
                        onValueChange = { newPassInput = it },
                        label = { Text("নতুন পাসওয়ার্ড") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = confirmPassInput,
                        onValueChange = { confirmPassInput = it },
                        label = { Text("নতুন পাসওয়ার্ড নিশ্চিত করুন") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        onClick = {
                            if (newPassInput.isBlank() || newPassInput.length < 6) {
                                passChangeStatus = "❌ নতুন পাসওয়ার্ড কমপক্ষে ৬ অক্ষরের হতে হবে!"
                                return@Button
                            }
                            if (newPassInput != confirmPassInput) {
                                passChangeStatus = "❌ নতুন পাসওয়ার্ড দুটি মিলছে না!"
                                return@Button
                            }
                            onUpdateMasterPassword(oldPassInput, newPassInput) { success ->
                                if (success) {
                                    passChangeStatus = "✓ মাস্টার পাসওয়ার্ড সফলভাবে আপডেট হয়েছে!"
                                    oldPassInput = ""
                                    newPassInput = ""
                                    confirmPassInput = ""
                                } else {
                                    passChangeStatus = "❌ বর্তমান পাসওয়ার্ড সঠিক নয়!"
                                }
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .bubbleTouchEffect(MaterialTheme.colorScheme.primary)
                    ) {
                        Text("পাসওয়ার্ড আপডেট করুন", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun MasterModuleToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
