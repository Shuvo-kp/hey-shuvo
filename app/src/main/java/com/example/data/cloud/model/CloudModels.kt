package com.example.data.cloud.model

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp

@IgnoreExtraProperties
data class MasterBootstrap(
    @DocumentId val id: String = "",
    val ownerUid: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null
)

@IgnoreExtraProperties
data class CustomerRemoteConfig(
    @DocumentId val id: String = "global",
    val configVersion: Long = 1L,
    val announcementBanner: String = "",
    val forcedThemePalette: String = "",
    val defaultAutoVoice: Boolean = true,
    val customSystemPrompt: String = "",
    val enabledFeatureFlags: String = DEFAULT_FEATURE_FLAGS,
    val lastAppliedFixSummary: String = "",
    val monthlyPaidFeeBdt: Long = 299L,
    val freeDailyLimit: Long = 25L,
    val globalPaidModeEnabled: Boolean = true,
    val updatedByUid: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    companion object {
        const val DEFAULT_FEATURE_FLAGS =
            "ALL_DISPLAY_VOICE,MAPS_NAVIGATION,PHOTO_VISION,OFFICE_SUITE,AUTOCAD_PLAN,TALLY_KHATA,SMART_HUB,WINDOWS_BRIDGE,GESTURE_TRANSFER,BIRTHDAY_WISH"
    }
}

@IgnoreExtraProperties
data class UserAccount(
    @DocumentId val id: String = "",
    val userId: String = "",
    val email: String = "",
    val displayName: String = "",
    val accountRole: String = "member",
    val accessStatus: String = "pending",
    val subscriptionTier: String? = "free",
    val deviceModel: String? = null,
    val cityRegion: String? = null,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class Invitation(
    @DocumentId val id: String = "",
    val invitedEmail: String = "",
    val invitedByUid: String = "",
    val note: String = "",
    val status: String = "invited",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class FeatureRequest(
    @DocumentId val id: String = "",
    val requesterUid: String = "",
    val requesterName: String = "",
    val title: String = "",
    val description: String = "",
    val recommendedSkill: String = "",
    val status: String = "submitted",
    val requiresPlayUpdate: Boolean = false,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class SkillModule(
    @DocumentId val id: String = "",
    val name: String = "",
    val description: String = "",
    val version: String = "1.0.0",
    val instructions: String = "",
    val requiredPermissions: String = "None",
    val supportedLanguages: String = "Bangla, English, Banglish, Regional",
    val rolloutPercentage: Long = 100L,
    val enabled: Boolean = true,
    val testStatus: String = "verified",
    val updatedByUid: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class AuditEntry(
    @DocumentId val id: String = "",
    val actorUid: String = "",
    val action: String = "",
    val targetSummary: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null
)

@IgnoreExtraProperties
data class GestureTransferSession(
    @DocumentId val id: String = "",
    val senderUid: String = "",
    val pairingCode: String = "",
    val fileName: String = "",
    val mimeType: String = "application/octet-stream",
    val payloadBase64: String = "",
    val fileSizeBytes: Long = 0L,
    val status: String = "grabbed",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class BirthdayWishEntry(
    @DocumentId val id: String = "",
    val userId: String = "",
    val personName: String = "",
    val birthDay: Long = 1L,
    val birthMonth: Long = 1L,
    val phoneOrContact: String = "",
    val wishMessage: String = "",
    val lastWishedYear: Long = 0L,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)
