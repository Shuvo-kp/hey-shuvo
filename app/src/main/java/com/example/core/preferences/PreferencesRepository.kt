package com.example.core.preferences

import android.content.Context
import android.graphics.Bitmap
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.data.cloud.model.CustomerRemoteConfig
import com.example.data.cloud.model.SkillModule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.math.min

private val Context.heyShuvoDataStore by preferencesDataStore(name = "hey_shuvo_preferences")

enum class AiPersonaFace(val label: String, val subtitle: String) {
    ORBITAL_HUD("Orbital Core", "Concentric neural telemetry rings"),
    PULSE_MATRIX("Pulse Matrix", "Symmetric acoustic vector visor"),
    SENTINEL_ARC("Sentinel Arc", "Precision geometric aperture with eye tracking")
}

enum class UserAccessibilityMode(val label: String) {
    STANDARD("Standard HUD"),
    CHILDREN("Children Safe Mode"),
    ELDERLY("Elderly Voice-First")
}

enum class AppThemePalette(val labelBn: String, val subtitle: String, val previewHex: Long) {
    CYBER_CYAN("Cyber Cyan", "ডিফল্ট সায়ান থিম", 0xFF00E5FF),
    EMERALD_MATRIX("Emerald Neo", "পান্না সবুজ", 0xFF00E676),
    ROYAL_GOLD("Royal Gold", "রাজকীয় গোল্ড", 0xFFFFB300),
    VIOLET_PULSE("Cosmic Violet", "নিওন ভায়োলেট", 0xFFB388FF),
    CRIMSON_BLAZE("Crimson Blaze", "রুবী লাল", 0xFFFF5252),
    OCEAN_SAPPHIRE("Ocean Blue", "নীল সমুদ্র", 0xFF448AFF),
    DAYLIGHT_CLEAN("Daylight Clean", "সাদা উজ্জ্বল থিম", 0xFFE3F2FD),
    CUSTOM_COLOR("Custom Color", "নিজের পছন্দের রঙ", 0xFFFF4081)
}

data class QuickVoiceRoutine(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val commandText: String,
    val category: String = "Custom"
)

data class AssistantSettings(
    val personaFace: AiPersonaFace = AiPersonaFace.ORBITAL_HUD,
    val userMode: UserAccessibilityMode = UserAccessibilityMode.STANDARD,
    val themePalette: AppThemePalette = AppThemePalette.CYBER_CYAN,
    val customAccentHue: Float = 195f,
    val textScale: Float = 1.0f,
    val darkHudTheme: Boolean = true,
    val autoAllDisplayVoice: Boolean = true,
    val floatingBubbleEnabled: Boolean = true,
    val wakeWordActive: Boolean = true,
    val listenBackgroundVoices: Boolean = false,
    val lowDataMode: Boolean = false,
    val parentPin: String = "2580",
    val userHomeLocation: String = DEFAULT_BD_LOCATION,
    val chatHeadAvatarPath: String = "",
    val chatHeadAvatarUpdatedMs: Long = 0L,
    val customRoutines: List<QuickVoiceRoutine> = emptyList(),
    // Master-to-Customer live sync mirror (instant local + cloud Firestore bridge)
    val masterConfigVersion: Long = 1L,
    val masterAnnouncementBanner: String = "",
    val masterCustomSystemPrompt: String = "",
    val masterEnabledFeatureFlags: String = CustomerRemoteConfig.DEFAULT_FEATURE_FLAGS,
    val masterLastAppliedSummary: String = "",
    val localMasterSkills: List<SkillModule> = emptyList(),
    val autoEmailFlags: String = DEFAULT_AUTO_EMAIL_FLAGS,
    val masterSenderEmail: String = "shuvoskp3@gmail.com",
    val monthlyPaidFeeBdt: Long = 299L,
    val freeDailyLimit: Long = 25L,
    val globalPaidModeEnabled: Boolean = true,
    val currentUserIsPaid: Boolean = false,
    val currentUserPaidUntilMs: Long = 0L,
    val dailyUsageCount: Long = 0L,
    val dailyUsageDate: String = "",
    val masterPassword: String = DEFAULT_MASTER_PASSWORD,
    val masterAuthorizedEmails: String = DEFAULT_MASTER_AUTHORIZED_EMAILS,
    val voiceProfile: String = "FEMALE",
    val femalePhotoIndex: Int = 0,
    val malePhotoIndex: Int = 0,
    val useCustomPortraitAvatar: Boolean = false
) {
    companion object {
        const val DEFAULT_BD_LOCATION = "Dhaka, Bangladesh"
        const val DEFAULT_AUTO_EMAIL_FLAGS =
            "WELCOME,BIRTHDAY,INACTIVITY_7D,FESTIVAL,NEW_FEATURE,MONTHLY_SUMMARY,SECURITY_LOGIN"
        const val DEFAULT_MASTER_PASSWORD = "Sanjoy207307paul@"
        const val DEFAULT_MASTER_AUTHORIZED_EMAILS =
            "shuvoskp3@gmail.com,sanjoy1521997@gmail.com,eslsanjoy@gmail.com,fidcsanjoy@gmail.com"
    }
}

class PreferencesRepository(private val context: Context) {
    private object Keys {
        val PERSONA_FACE = stringPreferencesKey("persona_face")
        val USER_MODE = stringPreferencesKey("user_mode")
        val THEME_PALETTE = stringPreferencesKey("theme_palette")
        val CUSTOM_ACCENT_HUE = floatPreferencesKey("custom_accent_hue")
        val TEXT_SCALE = floatPreferencesKey("text_scale")
        val DARK_HUD = booleanPreferencesKey("dark_hud")
        val AUTO_ALL_DISPLAY_VOICE = booleanPreferencesKey("auto_all_display_voice")
        val FLOATING_BUBBLE = booleanPreferencesKey("floating_bubble")
        val WAKE_WORD = booleanPreferencesKey("wake_word")
        val LISTEN_BACKGROUND_VOICES = booleanPreferencesKey("listen_background_voices")
        val LOW_DATA = booleanPreferencesKey("low_data")
        val PARENT_PIN = stringPreferencesKey("parent_pin")
        val USER_HOME_LOCATION = stringPreferencesKey("user_home_location")
        val CHAT_HEAD_AVATAR_PATH = stringPreferencesKey("chat_head_avatar_path")
        val CHAT_HEAD_AVATAR_UPDATED_MS = longPreferencesKey("chat_head_avatar_updated_ms")
        val CUSTOM_ROUTINES_JSON = stringPreferencesKey("custom_routines_json")

        // Master App -> Customer App instant sync keys
        val MASTER_CONFIG_VERSION = longPreferencesKey("master_config_version")
        val MASTER_ANNOUNCEMENT = stringPreferencesKey("master_announcement")
        val MASTER_CUSTOM_PROMPT = stringPreferencesKey("master_custom_prompt")
        val MASTER_FEATURE_FLAGS = stringPreferencesKey("master_feature_flags")
        val MASTER_LAST_SUMMARY = stringPreferencesKey("master_last_summary")
        val LOCAL_MASTER_SKILLS_JSON = stringPreferencesKey("local_master_skills_json")
        val AUTO_EMAIL_FLAGS = stringPreferencesKey("auto_email_flags")
        val MASTER_SENDER_EMAIL = stringPreferencesKey("master_sender_email")
        val MONTHLY_PAID_FEE_BDT = longPreferencesKey("monthly_paid_fee_bdt")
        val FREE_DAILY_LIMIT = longPreferencesKey("free_daily_limit")
        val GLOBAL_PAID_MODE_ENABLED = booleanPreferencesKey("global_paid_mode_enabled")
        val CURRENT_USER_IS_PAID = booleanPreferencesKey("current_user_is_paid")
        val CURRENT_USER_PAID_UNTIL_MS = longPreferencesKey("current_user_paid_until_ms")
        val DAILY_USAGE_COUNT = longPreferencesKey("daily_usage_count")
        val DAILY_USAGE_DATE = stringPreferencesKey("daily_usage_date")
        val MASTER_PASSWORD = stringPreferencesKey("master_password")
        val MASTER_AUTHORIZED_EMAILS = stringPreferencesKey("master_authorized_emails")
        val VOICE_PROFILE = stringPreferencesKey("voice_profile")
        val FEMALE_PHOTO_INDEX = intPreferencesKey("female_photo_index")
        val MALE_PHOTO_INDEX = intPreferencesKey("male_photo_index")
        val USE_CUSTOM_PORTRAIT_AVATAR = booleanPreferencesKey("use_custom_portrait_avatar")
    }

    val settingsFlow: Flow<AssistantSettings> = context.heyShuvoDataStore.data.map { prefs: Preferences ->
        val faceName = prefs[Keys.PERSONA_FACE] ?: AiPersonaFace.ORBITAL_HUD.name
        val modeName = prefs[Keys.USER_MODE] ?: UserAccessibilityMode.STANDARD.name
        val paletteName = prefs[Keys.THEME_PALETTE] ?: AppThemePalette.CYBER_CYAN.name
        val parsedPalette = AppThemePalette.entries.find { it.name == paletteName } ?: AppThemePalette.CYBER_CYAN
        val routinesRaw = prefs[Keys.CUSTOM_ROUTINES_JSON].orEmpty()
        val masterSkillsRaw = prefs[Keys.LOCAL_MASTER_SKILLS_JSON].orEmpty()
        val savedLoc = prefs[Keys.USER_HOME_LOCATION]?.takeIf { it.isNotBlank() }
            ?: readCachedLocationSync(context)
        val avatarPath = prefs[Keys.CHAT_HEAD_AVATAR_PATH]?.takeIf { it.isNotBlank() && File(it).exists() }
            ?: readChatHeadAvatarSync(context)
        AssistantSettings(
            personaFace = AiPersonaFace.entries.find { it.name == faceName } ?: AiPersonaFace.ORBITAL_HUD,
            userMode = UserAccessibilityMode.entries.find { it.name == modeName } ?: UserAccessibilityMode.STANDARD,
            themePalette = parsedPalette,
            customAccentHue = (prefs[Keys.CUSTOM_ACCENT_HUE] ?: 195f).coerceIn(0f, 360f),
            textScale = (prefs[Keys.TEXT_SCALE] ?: 1.0f).coerceIn(0.85f, 1.35f),
            darkHudTheme = prefs[Keys.DARK_HUD] ?: (parsedPalette != AppThemePalette.DAYLIGHT_CLEAN),
            autoAllDisplayVoice = prefs[Keys.AUTO_ALL_DISPLAY_VOICE] ?: true,
            floatingBubbleEnabled = prefs[Keys.FLOATING_BUBBLE] ?: true,
            wakeWordActive = prefs[Keys.WAKE_WORD] ?: true,
            listenBackgroundVoices = prefs[Keys.LISTEN_BACKGROUND_VOICES] ?: false,
            lowDataMode = prefs[Keys.LOW_DATA] ?: false,
            parentPin = prefs[Keys.PARENT_PIN] ?: "2580",
            userHomeLocation = savedLoc,
            chatHeadAvatarPath = avatarPath,
            chatHeadAvatarUpdatedMs = prefs[Keys.CHAT_HEAD_AVATAR_UPDATED_MS] ?: 0L,
            customRoutines = parseRoutinesJson(routinesRaw),
            masterConfigVersion = prefs[Keys.MASTER_CONFIG_VERSION] ?: 1L,
            masterAnnouncementBanner = prefs[Keys.MASTER_ANNOUNCEMENT].orEmpty(),
            masterCustomSystemPrompt = prefs[Keys.MASTER_CUSTOM_PROMPT].orEmpty(),
            masterEnabledFeatureFlags = prefs[Keys.MASTER_FEATURE_FLAGS]
                ?.takeIf { it.isNotBlank() }
                ?: CustomerRemoteConfig.DEFAULT_FEATURE_FLAGS,
            masterLastAppliedSummary = prefs[Keys.MASTER_LAST_SUMMARY].orEmpty(),
            localMasterSkills = parseLocalSkillsJson(masterSkillsRaw),
            autoEmailFlags = prefs[Keys.AUTO_EMAIL_FLAGS]?.takeIf { it.isNotBlank() }
                ?: AssistantSettings.DEFAULT_AUTO_EMAIL_FLAGS,
            masterSenderEmail = prefs[Keys.MASTER_SENDER_EMAIL]?.takeIf { it.isNotBlank() }
                ?: "shuvoskp3@gmail.com",
            monthlyPaidFeeBdt = prefs[Keys.MONTHLY_PAID_FEE_BDT] ?: 299L,
            freeDailyLimit = prefs[Keys.FREE_DAILY_LIMIT] ?: 25L,
            globalPaidModeEnabled = prefs[Keys.GLOBAL_PAID_MODE_ENABLED] ?: true,
            currentUserIsPaid = prefs[Keys.CURRENT_USER_IS_PAID] ?: false,
            currentUserPaidUntilMs = prefs[Keys.CURRENT_USER_PAID_UNTIL_MS] ?: 0L,
            dailyUsageCount = run {
                val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
                val savedDate = prefs[Keys.DAILY_USAGE_DATE].orEmpty()
                if (savedDate == today) (prefs[Keys.DAILY_USAGE_COUNT] ?: 0L) else 0L
            },
            dailyUsageDate = prefs[Keys.DAILY_USAGE_DATE].orEmpty(),
            masterPassword = prefs[Keys.MASTER_PASSWORD]?.takeIf { it.isNotBlank() }
                ?: AssistantSettings.DEFAULT_MASTER_PASSWORD,
            masterAuthorizedEmails = prefs[Keys.MASTER_AUTHORIZED_EMAILS]?.takeIf { it.isNotBlank() }
                ?: AssistantSettings.DEFAULT_MASTER_AUTHORIZED_EMAILS,
            voiceProfile = prefs[Keys.VOICE_PROFILE]?.takeIf { it.isNotBlank() } ?: "FEMALE",
            femalePhotoIndex = (prefs[Keys.FEMALE_PHOTO_INDEX] ?: 0).coerceAtLeast(0),
            malePhotoIndex = (prefs[Keys.MALE_PHOTO_INDEX] ?: 0).coerceAtLeast(0),
            useCustomPortraitAvatar = prefs[Keys.USE_CUSTOM_PORTRAIT_AVATAR] ?: false
        )
    }

    suspend fun saveVoiceProfile(profile: String) {
        writeCachedVoiceProfileSync(context, profile)
        context.heyShuvoDataStore.edit { prefs ->
            prefs[Keys.VOICE_PROFILE] = profile
        }
    }

    suspend fun saveAvatarPhotoIndex(isFemale: Boolean, index: Int) {
        val safeIdx = index.coerceAtLeast(0)
        context.heyShuvoDataStore.edit { prefs ->
            if (isFemale) {
                prefs[Keys.FEMALE_PHOTO_INDEX] = safeIdx
            } else {
                prefs[Keys.MALE_PHOTO_INDEX] = safeIdx
            }
        }
    }

    suspend fun setUseCustomPortraitAvatar(enabled: Boolean) {
        context.heyShuvoDataStore.edit { prefs ->
            prefs[Keys.USE_CUSTOM_PORTRAIT_AVATAR] = enabled
        }
    }

    suspend fun saveMasterPricingConfig(monthlyFeeBdt: Long, freeLimit: Long) {
        context.heyShuvoDataStore.edit { prefs ->
            prefs[Keys.MONTHLY_PAID_FEE_BDT] = monthlyFeeBdt.coerceAtLeast(0L)
            prefs[Keys.FREE_DAILY_LIMIT] = freeLimit.coerceAtLeast(1L)
        }
    }

    suspend fun updateMasterPassword(oldPassword: String, newPassword: String): Boolean {
        if (newPassword.isBlank() || newPassword.length < 6) return false
        var success = false
        context.heyShuvoDataStore.edit { prefs ->
            val current = prefs[Keys.MASTER_PASSWORD]?.takeIf { it.isNotBlank() }
                ?: AssistantSettings.DEFAULT_MASTER_PASSWORD
            if (current == oldPassword) {
                prefs[Keys.MASTER_PASSWORD] = newPassword
                success = true
            }
        }
        return success
    }

    fun isMasterAuthorizedEmail(email: String, authorizedListStr: String = AssistantSettings.DEFAULT_MASTER_AUTHORIZED_EMAILS): Boolean {
        val cleanEmail = email.trim().lowercase()
        if (cleanEmail.isBlank()) return false
        val list = authorizedListStr.split(",").map { it.trim().lowercase() }
        return cleanEmail in list
    }

    suspend fun setGlobalPaidModeEnabled(enabled: Boolean) {
        context.heyShuvoDataStore.edit { prefs ->
            prefs[Keys.GLOBAL_PAID_MODE_ENABLED] = enabled
        }
    }

    suspend fun setCurrentUserPaidStatus(isPaid: Boolean, validityDays: Int = 30) {
        val untilMs = if (!isPaid || validityDays <= 0) {
            0L
        } else {
            System.currentTimeMillis() + validityDays.toLong() * 24L * 60L * 60L * 1000L
        }
        context.heyShuvoDataStore.edit { prefs ->
            prefs[Keys.CURRENT_USER_IS_PAID] = isPaid
            prefs[Keys.CURRENT_USER_PAID_UNTIL_MS] = untilMs
        }
    }

    suspend fun grantBonusFreeUses(bonusCount: Long = 10L) {
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
        context.heyShuvoDataStore.edit { prefs ->
            val savedDate = prefs[Keys.DAILY_USAGE_DATE].orEmpty()
            val current = if (savedDate == today) (prefs[Keys.DAILY_USAGE_COUNT] ?: 0L) else 0L
            prefs[Keys.DAILY_USAGE_DATE] = today
            prefs[Keys.DAILY_USAGE_COUNT] = (current - bonusCount).coerceAtLeast(0L)
        }
    }

    suspend fun canUseAiCommandToday(): Boolean {
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
        var allowed = true
        context.heyShuvoDataStore.edit { prefs ->
            val globalPaid = prefs[Keys.GLOBAL_PAID_MODE_ENABLED] ?: true
            val isPaid = prefs[Keys.CURRENT_USER_IS_PAID] ?: false
            val paidUntil = prefs[Keys.CURRENT_USER_PAID_UNTIL_MS] ?: 0L
            val isPaidActive = isPaid && (paidUntil == 0L || System.currentTimeMillis() <= paidUntil)
            if (!globalPaid || isPaidActive) {
                allowed = true
                return@edit
            }
            val limit = (prefs[Keys.FREE_DAILY_LIMIT] ?: 25L).coerceAtLeast(1L)
            val savedDate = prefs[Keys.DAILY_USAGE_DATE].orEmpty()
            val currentCount = if (savedDate == today) (prefs[Keys.DAILY_USAGE_COUNT] ?: 0L) else 0L
            allowed = currentCount < limit
        }
        return allowed
    }

    suspend fun incrementAndCheckDailyUsage(): Boolean {
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
        var allowed = true
        context.heyShuvoDataStore.edit { prefs ->
            val globalPaid = prefs[Keys.GLOBAL_PAID_MODE_ENABLED] ?: true
            val isPaid = prefs[Keys.CURRENT_USER_IS_PAID] ?: false
            val paidUntil = prefs[Keys.CURRENT_USER_PAID_UNTIL_MS] ?: 0L
            val isPaidActive = isPaid && (paidUntil == 0L || System.currentTimeMillis() <= paidUntil)
            if (isPaid && paidUntil > 0L && System.currentTimeMillis() > paidUntil) {
                prefs[Keys.CURRENT_USER_IS_PAID] = false
                prefs[Keys.CURRENT_USER_PAID_UNTIL_MS] = 0L
            }
            if (!globalPaid || isPaidActive) {
                allowed = true
                return@edit
            }
            val limit = (prefs[Keys.FREE_DAILY_LIMIT] ?: 25L).coerceAtLeast(1L)
            val savedDate = prefs[Keys.DAILY_USAGE_DATE].orEmpty()
            val currentCount = if (savedDate == today) (prefs[Keys.DAILY_USAGE_COUNT] ?: 0L) else 0L
            if (currentCount >= limit) {
                allowed = false
            } else {
                prefs[Keys.DAILY_USAGE_DATE] = today
                prefs[Keys.DAILY_USAGE_COUNT] = currentCount + 1L
                allowed = true
            }
        }
        return allowed
    }

    suspend fun saveAutoEmailAutomationConfig(senderEmail: String, flags: String) {
        context.heyShuvoDataStore.edit { prefs ->
            prefs[Keys.MASTER_SENDER_EMAIL] = senderEmail.trim().ifBlank { "shuvoskp3@gmail.com" }
            prefs[Keys.AUTO_EMAIL_FLAGS] = flags
        }
    }

    suspend fun setPersonaFace(face: AiPersonaFace) {
        context.heyShuvoDataStore.edit { it[Keys.PERSONA_FACE] = face.name }
    }

    suspend fun setUserMode(mode: UserAccessibilityMode) {
        context.heyShuvoDataStore.edit {
            it[Keys.USER_MODE] = mode.name
            if (mode == UserAccessibilityMode.ELDERLY) {
                it[Keys.TEXT_SCALE] = 1.22f
            } else if (mode == UserAccessibilityMode.STANDARD) {
                it[Keys.TEXT_SCALE] = 1.0f
            }
        }
    }

    suspend fun setThemePalette(palette: AppThemePalette) {
        context.heyShuvoDataStore.edit {
            it[Keys.THEME_PALETTE] = palette.name
            it[Keys.DARK_HUD] = palette != AppThemePalette.DAYLIGHT_CLEAN
        }
    }

    suspend fun setCustomAccentHue(hue: Float) {
        val clamped = hue.coerceIn(0f, 360f)
        context.heyShuvoDataStore.edit {
            it[Keys.THEME_PALETTE] = AppThemePalette.CUSTOM_COLOR.name
            it[Keys.CUSTOM_ACCENT_HUE] = clamped
        }
    }

    suspend fun setTextScale(scale: Float) {
        context.heyShuvoDataStore.edit {
            it[Keys.TEXT_SCALE] = scale.coerceIn(0.85f, 1.35f)
        }
    }

    suspend fun setDarkHudTheme(dark: Boolean) {
        context.heyShuvoDataStore.edit { it[Keys.DARK_HUD] = dark }
    }

    suspend fun setAutoAllDisplayVoice(enabled: Boolean) {
        context.heyShuvoDataStore.edit { it[Keys.AUTO_ALL_DISPLAY_VOICE] = enabled }
    }

    suspend fun setFloatingBubble(enabled: Boolean) {
        context.heyShuvoDataStore.edit { it[Keys.FLOATING_BUBBLE] = enabled }
    }

    suspend fun setWakeWordActive(active: Boolean) {
        context.heyShuvoDataStore.edit { it[Keys.WAKE_WORD] = active }
    }

    suspend fun setListenBackgroundVoices(enabled: Boolean) {
        context.heyShuvoDataStore.edit { it[Keys.LISTEN_BACKGROUND_VOICES] = enabled }
    }

    suspend fun setLowDataMode(enabled: Boolean) {
        context.heyShuvoDataStore.edit { it[Keys.LOW_DATA] = enabled }
    }

    suspend fun setUserHomeLocation(location: String) {
        val clean = location.trim().ifBlank { AssistantSettings.DEFAULT_BD_LOCATION }
        writeCachedLocationSync(context, clean)
        context.heyShuvoDataStore.edit { it[Keys.USER_HOME_LOCATION] = clean }
    }

    suspend fun saveCustomChatHeadPhoto(bitmap: Bitmap): String {
        val savedPath = withContext(Dispatchers.IO) {
            val avatarDir = File(context.applicationContext.filesDir, "avatar").apply { mkdirs() }
            val outFile = File(avatarDir, "chat_head_user.jpg")
            val side = min(bitmap.width, bitmap.height).coerceAtLeast(1)
            val cropX = ((bitmap.width - side) / 2).coerceAtLeast(0)
            val cropY = ((bitmap.height - side) / 4).coerceAtLeast(0)
            val square = Bitmap.createBitmap(bitmap, cropX, cropY, side, side)
            val scaled = if (side != 256) {
                Bitmap.createScaledBitmap(square, 256, 256, true).also {
                    if (square !== bitmap) square.recycle()
                }
            } else {
                square
            }
            try {
                FileOutputStream(outFile).use { fos ->
                    scaled.compress(Bitmap.CompressFormat.JPEG, 92, fos)
                    fos.flush()
                }
            } finally {
                if (scaled !== bitmap) scaled.recycle()
            }
            outFile.absolutePath
        }
        val now = System.currentTimeMillis()
        writeChatHeadAvatarSync(context, savedPath)
        context.heyShuvoDataStore.edit {
            it[Keys.CHAT_HEAD_AVATAR_PATH] = savedPath
            it[Keys.CHAT_HEAD_AVATAR_UPDATED_MS] = now
            it[Keys.USE_CUSTOM_PORTRAIT_AVATAR] = true
        }
        return savedPath
    }

    suspend fun clearCustomChatHeadPhoto() {
        withContext(Dispatchers.IO) {
            try {
                File(File(context.applicationContext.filesDir, "avatar"), "chat_head_user.jpg").delete()
            } catch (_: Exception) {
            }
        }
        writeChatHeadAvatarSync(context, "")
        context.heyShuvoDataStore.edit {
            it[Keys.CHAT_HEAD_AVATAR_PATH] = ""
            it[Keys.CHAT_HEAD_AVATAR_UPDATED_MS] = System.currentTimeMillis()
            it[Keys.USE_CUSTOM_PORTRAIT_AVATAR] = false
        }
    }

    suspend fun applyMasterCustomerUpdateLocally(
        announcementBanner: String,
        forcedThemePalette: String,
        defaultAutoVoice: Boolean,
        customSystemPrompt: String,
        enabledFeatureFlags: String,
        lastAppliedSummary: String,
        remoteVersion: Long? = null,
        monthlyPaidFeeBdt: Long? = null,
        freeDailyLimit: Long? = null,
        globalPaidModeEnabled: Boolean? = null
    ) {
        context.heyShuvoDataStore.edit { prefs ->
            if (remoteVersion != null) {
                prefs[Keys.MASTER_CONFIG_VERSION] = remoteVersion
            }
            prefs[Keys.MASTER_ANNOUNCEMENT] = announcementBanner.trim()
            prefs[Keys.MASTER_CUSTOM_PROMPT] = customSystemPrompt.trim()
            prefs[Keys.MASTER_FEATURE_FLAGS] = enabledFeatureFlags.trim()
                .ifBlank { CustomerRemoteConfig.DEFAULT_FEATURE_FLAGS }
            prefs[Keys.MASTER_LAST_SUMMARY] = lastAppliedSummary.trim()
            prefs[Keys.AUTO_ALL_DISPLAY_VOICE] = defaultAutoVoice
            if (monthlyPaidFeeBdt != null) {
                prefs[Keys.MONTHLY_PAID_FEE_BDT] = monthlyPaidFeeBdt.coerceAtLeast(0L)
            }
            if (freeDailyLimit != null) {
                prefs[Keys.FREE_DAILY_LIMIT] = freeDailyLimit.coerceAtLeast(1L)
            }
            if (globalPaidModeEnabled != null) {
                prefs[Keys.GLOBAL_PAID_MODE_ENABLED] = globalPaidModeEnabled
            }
            if (forcedThemePalette.isNotBlank()) {
                val matched = AppThemePalette.entries.find { it.name.equals(forcedThemePalette.trim(), ignoreCase = true) }
                if (matched != null) {
                    prefs[Keys.THEME_PALETTE] = matched.name
                    prefs[Keys.DARK_HUD] = matched != AppThemePalette.DAYLIGHT_CLEAN
                }
            }
        }
    }

    suspend fun upsertLocalMasterSkill(skill: SkillModule) {
        context.heyShuvoDataStore.edit { prefs ->
            val current = parseLocalSkillsJson(prefs[Keys.LOCAL_MASTER_SKILLS_JSON].orEmpty())
            val resolvedId = skill.id.ifBlank { "skill_${UUID.randomUUID().toString().replace("-", "").take(12)}" }
            val updatedItem = skill.copy(id = resolvedId)
            val filtered = current.filterNot { it.id == resolvedId }
            prefs[Keys.LOCAL_MASTER_SKILLS_JSON] = encodeLocalSkillsJson(listOf(updatedItem) + filtered)
        }
    }

    suspend fun removeLocalMasterSkill(skillId: String) {
        context.heyShuvoDataStore.edit { prefs ->
            val current = parseLocalSkillsJson(prefs[Keys.LOCAL_MASTER_SKILLS_JSON].orEmpty())
            prefs[Keys.LOCAL_MASTER_SKILLS_JSON] = encodeLocalSkillsJson(current.filterNot { it.id == skillId })
        }
    }

    suspend fun addCustomRoutine(title: String, commandText: String) {
        val cleanTitle = title.trim()
        val cleanCmd = commandText.trim()
        if (cleanTitle.isBlank() || cleanCmd.isBlank()) return
        context.heyShuvoDataStore.edit { prefs ->
            val current = parseRoutinesJson(prefs[Keys.CUSTOM_ROUTINES_JSON].orEmpty())
            val updated = listOf(
                QuickVoiceRoutine(
                    id = UUID.randomUUID().toString(),
                    title = cleanTitle,
                    commandText = cleanCmd
                )
            ) + current
            prefs[Keys.CUSTOM_ROUTINES_JSON] = encodeRoutinesJson(updated.take(25))
        }
    }

    suspend fun deleteCustomRoutine(id: String) {
        context.heyShuvoDataStore.edit { prefs ->
            val current = parseRoutinesJson(prefs[Keys.CUSTOM_ROUTINES_JSON].orEmpty())
            prefs[Keys.CUSTOM_ROUTINES_JSON] = encodeRoutinesJson(current.filterNot { it.id == id })
        }
    }

    companion object {
        private const val LOCATION_SYNC_PREFS = "hey_shuvo_location_sync"
        private const val KEY_CACHED_LOCATION = "cached_user_location"
        private const val KEY_CACHED_VOICE_PROFILE = "cached_voice_profile"
        private const val KEY_CHAT_HEAD_AVATAR = "cached_chat_head_avatar_path"

        fun readCachedVoiceProfileSync(context: Context): String {
            return try {
                context.applicationContext
                    .getSharedPreferences(LOCATION_SYNC_PREFS, Context.MODE_PRIVATE)
                    .getString(KEY_CACHED_VOICE_PROFILE, "FEMALE")
                    ?: "FEMALE"
            } catch (_: Exception) {
                "FEMALE"
            }
        }

        fun writeCachedVoiceProfileSync(context: Context, profile: String) {
            try {
                context.applicationContext
                    .getSharedPreferences(LOCATION_SYNC_PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_CACHED_VOICE_PROFILE, profile)
                    .apply()
            } catch (_: Exception) {
            }
        }

        fun readCachedLocationSync(context: Context): String {
            return try {
                context.applicationContext
                    .getSharedPreferences(LOCATION_SYNC_PREFS, Context.MODE_PRIVATE)
                    .getString(KEY_CACHED_LOCATION, AssistantSettings.DEFAULT_BD_LOCATION)
                    ?.takeIf { it.isNotBlank() }
                    ?: AssistantSettings.DEFAULT_BD_LOCATION
            } catch (_: Exception) {
                AssistantSettings.DEFAULT_BD_LOCATION
            }
        }

        fun writeCachedLocationSync(context: Context, location: String) {
            try {
                context.applicationContext
                    .getSharedPreferences(LOCATION_SYNC_PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_CACHED_LOCATION, location.trim().ifBlank { AssistantSettings.DEFAULT_BD_LOCATION })
                    .apply()
            } catch (_: Exception) {
            }
        }

        fun readChatHeadAvatarSync(context: Context): String {
            return try {
                val p = context.applicationContext
                    .getSharedPreferences(LOCATION_SYNC_PREFS, Context.MODE_PRIVATE)
                    .getString(KEY_CHAT_HEAD_AVATAR, "")
                    .orEmpty()
                if (p.isNotBlank() && File(p).exists()) p else ""
            } catch (_: Exception) {
                ""
            }
        }

        fun writeChatHeadAvatarSync(context: Context, path: String) {
            try {
                context.applicationContext
                    .getSharedPreferences(LOCATION_SYNC_PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_CHAT_HEAD_AVATAR, path.trim())
                    .apply()
            } catch (_: Exception) {
            }
        }

        internal fun parseRoutinesJson(raw: String): List<QuickVoiceRoutine> {
            if (raw.isBlank()) return emptyList()
            return try {
                val arr = JSONArray(raw)
                (0 until arr.length()).mapNotNull { idx ->
                    val obj = arr.optJSONObject(idx) ?: return@mapNotNull null
                    val title = obj.optString("title").trim()
                    val cmd = obj.optString("commandText").trim()
                    if (title.isBlank() || cmd.isBlank()) return@mapNotNull null
                    QuickVoiceRoutine(
                        id = obj.optString("id").ifBlank { "rt_$idx" },
                        title = title,
                        commandText = cmd,
                        category = obj.optString("category", "Custom")
                    )
                }
            } catch (_: Exception) {
                emptyList()
            }
        }

        internal fun encodeRoutinesJson(items: List<QuickVoiceRoutine>): String {
            val arr = JSONArray()
            items.forEach { item ->
                arr.put(
                    JSONObject().apply {
                        put("id", item.id)
                        put("title", item.title)
                        put("commandText", item.commandText)
                        put("category", item.category)
                    }
                )
            }
            return arr.toString()
        }

        internal fun parseLocalSkillsJson(raw: String): List<SkillModule> {
            if (raw.isBlank()) return emptyList()
            return try {
                val arr = JSONArray(raw)
                (0 until arr.length()).mapNotNull { idx ->
                    val obj = arr.optJSONObject(idx) ?: return@mapNotNull null
                    val name = obj.optString("name").trim()
                    if (name.isBlank()) return@mapNotNull null
                    SkillModule(
                        id = obj.optString("id").ifBlank { "local_skill_$idx" },
                        name = name,
                        description = obj.optString("description"),
                        version = obj.optString("version", "1.0.0"),
                        instructions = obj.optString("instructions"),
                        requiredPermissions = obj.optString("requiredPermissions", "None"),
                        supportedLanguages = obj.optString("supportedLanguages", "Bangla, English, Banglish"),
                        rolloutPercentage = obj.optLong("rolloutPercentage", 100L),
                        enabled = obj.optBoolean("enabled", true),
                        testStatus = obj.optString("testStatus", "verified")
                    )
                }
            } catch (_: Exception) {
                emptyList()
            }
        }

        internal fun encodeLocalSkillsJson(items: List<SkillModule>): String {
            val arr = JSONArray()
            items.forEach { s ->
                arr.put(
                    JSONObject().apply {
                        put("id", s.id)
                        put("name", s.name)
                        put("description", s.description)
                        put("version", s.version)
                        put("instructions", s.instructions)
                        put("requiredPermissions", s.requiredPermissions)
                        put("supportedLanguages", s.supportedLanguages)
                        put("rolloutPercentage", s.rolloutPercentage)
                        put("enabled", s.enabled)
                        put("testStatus", s.testStatus)
                    }
                )
            }
            return arr.toString()
        }
    }
}
