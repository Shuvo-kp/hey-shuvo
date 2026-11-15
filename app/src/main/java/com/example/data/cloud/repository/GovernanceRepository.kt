package com.example.data.cloud.repository

import android.content.Context
import android.os.Build
import com.example.R
import com.example.core.security.OperationType
import com.example.core.security.handleFirestoreError
import com.example.data.cloud.model.AuditEntry
import com.example.data.cloud.model.BirthdayWishEntry
import com.example.data.cloud.model.CustomerRemoteConfig
import com.example.data.cloud.model.FeatureRequest
import com.example.data.cloud.model.GestureTransferSession
import com.example.data.cloud.model.Invitation
import com.example.data.cloud.model.SkillModule
import com.example.data.cloud.model.UserAccount
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import java.util.UUID

class GovernanceRepository(
    private val db: FirebaseFirestore,
    private val auth: FirebaseAuth = Firebase.auth
) {
    constructor(context: Context) : this(
        FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        )
    )

    private fun requireUserId(): String {
        return auth.currentUser?.uid
            ?: throw IllegalStateException("User must be signed in with Google before accessing Firestore.")
    }

    suspend fun checkIsAdminAccount(): Boolean {
        val uid = auth.currentUser?.uid ?: return false
        return try {
            val adminExists = db.collection("admins").document(uid).get().await().exists()
            if (adminExists) return true
            val bootSnap = db.collection("system_bootstrap").document("master_owner").get().await()
            bootSnap.exists() && bootSnap.getString("ownerUid") == uid
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    suspend fun isMasterOwnerUnclaimed(): Boolean {
        if (auth.currentUser == null) return false
        return try {
            val bootSnap = db.collection("system_bootstrap").document("master_owner").get().await()
            !bootSnap.exists()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Claims primary Master Owner authority if `/system_bootstrap/master_owner` has not been claimed yet,
     * and upgrades the caller's `/accounts/{uid}` record to `owner` / `approved`.
     */
    suspend fun claimMasterOwnerIfAvailable(): Result<Boolean> {
        val user = auth.currentUser
            ?: return Result.failure(IllegalStateException("Google Sign-In required for Cloud Master sync."))
        val uid = user.uid
        val bootRef = db.collection("system_bootstrap").document("master_owner")
        return try {
            if (checkIsAdminAccount()) {
                return Result.success(true)
            }
            val existingBoot = bootRef.get().await()
            if (!existingBoot.exists()) {
                bootRef.set(
                    mapOf(
                        "ownerUid" to uid,
                        "createdAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            } else if (existingBoot.getString("ownerUid") != uid) {
                return Result.success(false)
            }

            val accRef = db.collection("accounts").document(uid)
            val accSnap = accRef.get().await()
            if (accSnap.exists()) {
                accRef.update(
                    mapOf(
                        "accountRole" to "owner",
                        "accessStatus" to "approved",
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            } else {
                accRef.set(
                    mapOf(
                        "userId" to uid,
                        "email" to (user.email ?: "owner@heyshuvo.local"),
                        "displayName" to (user.displayName?.takeIf { it.isNotBlank() } ?: "Master Owner"),
                        "accountRole" to "owner",
                        "accessStatus" to "approved",
                        "deviceModel" to "${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE})".take(110),
                        "createdAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            }
            recordAuditLog(uid, "MASTER_OWNER_ACTIVATED", "Activated Master App cloud governance authority")
            Result.success(true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, bootRef.path)
            Result.failure(e)
        }
    }

    fun observeCustomerRemoteConfig(configId: String = "global"): Flow<CustomerRemoteConfig?> = flow {
        val docRef = db.collection("customer_configs").document(configId)
        emitAll(
            docRef.snapshots()
                .map { snap ->
                    if (snap.exists()) {
                        snap.toObject(CustomerRemoteConfig::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                    } else {
                        null
                    }
                }
                .catch { error ->
                    if (error is Exception) handleFirestoreError(error, OperationType.GET, docRef.path)
                    throw error
                }
        )
    }

    suspend fun publishCustomerRemoteConfig(
        announcementBanner: String,
        forcedThemePalette: String,
        defaultAutoVoice: Boolean,
        customSystemPrompt: String,
        enabledFeatureFlags: String,
        lastAppliedFixSummary: String,
        monthlyPaidFeeBdt: Long = 299L,
        freeDailyLimit: Long = 25L,
        globalPaidModeEnabled: Boolean = true,
        configId: String = "global"
    ): Result<CustomerRemoteConfig> {
        val uid = requireUserId()
        val docRef = db.collection("customer_configs").document(configId)
        return try {
            val snap = docRef.get().await()
            val nextVersion = if (snap.exists()) {
                ((snap.getLong("configVersion") ?: 1L) + 1L).coerceAtMost(999_999L)
            } else {
                1L
            }
            val cleanBanner = announcementBanner.trim().take(480)
            val cleanPalette = forcedThemePalette.trim().take(36)
            val cleanPrompt = customSystemPrompt.trim().take(2900)
            val cleanFlags = enabledFeatureFlags.trim().ifBlank { CustomerRemoteConfig.DEFAULT_FEATURE_FLAGS }.take(580)
            val cleanSummary = lastAppliedFixSummary.trim().ifBlank { "Updated Customer App configuration" }.take(480)
            val safeFee = monthlyPaidFeeBdt.coerceIn(0L, 1_000_000L)
            val safeLimit = freeDailyLimit.coerceIn(1L, 100_000L)

            if (!snap.exists()) {
                docRef.set(
                    mapOf(
                        "configVersion" to nextVersion,
                        "announcementBanner" to cleanBanner,
                        "forcedThemePalette" to cleanPalette,
                        "defaultAutoVoice" to defaultAutoVoice,
                        "customSystemPrompt" to cleanPrompt,
                        "enabledFeatureFlags" to cleanFlags,
                        "lastAppliedFixSummary" to cleanSummary,
                        "monthlyPaidFeeBdt" to safeFee,
                        "freeDailyLimit" to safeLimit,
                        "globalPaidModeEnabled" to globalPaidModeEnabled,
                        "updatedByUid" to uid,
                        "createdAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            } else {
                docRef.update(
                    mapOf(
                        "configVersion" to nextVersion,
                        "announcementBanner" to cleanBanner,
                        "forcedThemePalette" to cleanPalette,
                        "defaultAutoVoice" to defaultAutoVoice,
                        "customSystemPrompt" to cleanPrompt,
                        "enabledFeatureFlags" to cleanFlags,
                        "lastAppliedFixSummary" to cleanSummary,
                        "monthlyPaidFeeBdt" to safeFee,
                        "freeDailyLimit" to safeLimit,
                        "globalPaidModeEnabled" to globalPaidModeEnabled,
                        "updatedByUid" to uid,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            }
            recordAuditLog(uid, "CUSTOMER_CONFIG_PUSHED", "v$nextVersion: $cleanSummary")
            val updated = docRef.get().await().toObject(
                CustomerRemoteConfig::class.java,
                DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
            ) ?: CustomerRemoteConfig(
                id = configId,
                configVersion = nextVersion,
                announcementBanner = cleanBanner,
                forcedThemePalette = cleanPalette,
                defaultAutoVoice = defaultAutoVoice,
                customSystemPrompt = cleanPrompt,
                enabledFeatureFlags = cleanFlags,
                lastAppliedFixSummary = cleanSummary,
                monthlyPaidFeeBdt = safeFee,
                freeDailyLimit = safeLimit,
                globalPaidModeEnabled = globalPaidModeEnabled,
                updatedByUid = uid
            )
            Result.success(updated)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun ensureUserAccountRegistered(cityConsent: String? = null): Result<UserAccount> {
        val user = auth.currentUser
            ?: return Result.failure(IllegalStateException("Unauthenticated session"))
        val uid = user.uid
        val docRef = db.collection("accounts").document(uid)
        return try {
            val existingSnap = docRef.get().await()
            if (existingSnap.exists()) {
                val parsed = existingSnap.toObject(
                    UserAccount::class.java,
                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                )
                if (parsed != null) {
                    return Result.success(parsed)
                }
            }

            val isOwner = checkIsAdminAccount()
            val payload = mapOf(
                "userId" to uid,
                "email" to (user.email ?: "private@member.local"),
                "displayName" to (user.displayName?.takeIf { it.isNotBlank() } ?: "Circle Member"),
                "accountRole" to if (isOwner) "owner" else "member",
                "accessStatus" to if (isOwner) "approved" else "pending",
                "deviceModel" to "${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE})".take(110),
                "cityRegion" to cityConsent?.take(90),
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            ).filterValues { it != null }

            docRef.set(payload).await()
            val fresh = docRef.get().await().toObject(
                UserAccount::class.java,
                DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
            ) ?: UserAccount(
                id = uid,
                userId = uid,
                email = user.email.orEmpty(),
                displayName = user.displayName ?: "Circle Member",
                accountRole = if (isOwner) "owner" else "member",
                accessStatus = if (isOwner) "approved" else "pending"
            )
            Result.success(fresh)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docRef.path)
            Result.failure(e)
        }
    }

    fun observeOwnAccount(userId: String): Flow<UserAccount?> {
        val docRef = db.collection("accounts").document(userId)
        return docRef.snapshots()
            .map { snap ->
                if (snap.exists()) {
                    snap.toObject(UserAccount::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                } else {
                    null
                }
            }
            .catch { error ->
                if (error is Exception) handleFirestoreError(error, OperationType.GET, docRef.path)
                throw error
            }
    }

    suspend fun getAccountById(targetUserId: String): Result<UserAccount?> {
        val docRef = db.collection("accounts").document(targetUserId)
        return try {
            val snap = docRef.get().await()
            Result.success(
                snap.toObject(UserAccount::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.GET, docRef.path)
            Result.failure(e)
        }
    }

    fun observeAllAccountsForOwner(): Flow<List<UserAccount>> = flow {
        val path = "accounts"
        emitAll(
            db.collection(path)
                .snapshots()
                .map { snap ->
                    snap.toObjects(UserAccount::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                }
                .catch { error ->
                    if (error is Exception) handleFirestoreError(error, OperationType.LIST, path)
                    throw error
                }
        )
    }

    suspend fun updateAccountAccessStatus(targetUserId: String, newStatus: String): Result<Unit> {
        val actorUid = requireUserId()
        val docRef = db.collection("accounts").document(targetUserId)
        return try {
            docRef.update(
                mapOf(
                    "accessStatus" to newStatus,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            recordAuditLog(actorUid, "USER_STATUS_${newStatus.uppercase()}", "Updated member $targetUserId status to $newStatus")
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun updateAccountSubscriptionTier(targetUserId: String, tier: String): Result<Unit> {
        val actorUid = requireUserId()
        val cleanTier = if (tier.equals("paid", ignoreCase = true)) "paid" else "free"
        val docRef = db.collection("accounts").document(targetUserId)
        return try {
            docRef.update(
                mapOf(
                    "subscriptionTier" to cleanTier,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            recordAuditLog(actorUid, "USER_TIER_${cleanTier.uppercase()}", "Updated member $targetUserId tier to $cleanTier")
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docRef.path)
            Result.failure(e)
        }
    }

    fun observeInvitationsForOwner(): Flow<List<Invitation>> = flow {
        val path = "invitations"
        emitAll(
            db.collection(path)
                .snapshots()
                .map { snap ->
                    snap.toObjects(Invitation::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                }
                .catch { error ->
                    if (error is Exception) handleFirestoreError(error, OperationType.LIST, path)
                    throw error
                }
        )
    }

    suspend fun createInvitation(email: String, note: String): Result<String> {
        val uid = requireUserId()
        val inviteId = "inv_${UUID.randomUUID().toString().replace("-", "").take(16)}"
        val docRef = db.collection("invitations").document(inviteId)
        return try {
            val payload = mapOf(
                "invitedEmail" to email.trim(),
                "invitedByUid" to uid,
                "note" to note.trim().ifBlank { "Trusted Circle Invite" }.take(180),
                "status" to "invited",
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            docRef.set(payload).await()
            recordAuditLog(uid, "INVITE_CREATED", "Invited ${email.trim()} (${note.take(40)})")
            Result.success(inviteId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun submitFeatureRequest(
        requesterName: String,
        title: String,
        description: String,
        recommendedSkill: String = "Core Assistant Module",
        requiresPlayUpdate: Boolean = false
    ): Result<String> {
        val uid = requireUserId()
        val requestId = "req_${UUID.randomUUID().toString().replace("-", "").take(16)}"
        val docRef = db.collection("feature_requests").document(requestId)
        return try {
            val payload = mapOf(
                "requesterUid" to uid,
                "requesterName" to requesterName.trim().ifBlank { "Circle Member" }.take(80),
                "title" to title.trim().take(130),
                "description" to description.trim().take(1800),
                "recommendedSkill" to recommendedSkill.trim().take(120),
                "status" to "submitted",
                "requiresPlayUpdate" to requiresPlayUpdate,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            docRef.set(payload).await()
            Result.success(requestId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docRef.path)
            Result.failure(e)
        }
    }

    fun observeMyFeatureRequests(userId: String): Flow<List<FeatureRequest>> = flow {
        val path = "feature_requests"
        emitAll(
            db.collection(path)
                .whereEqualTo("requesterUid", userId)
                .snapshots()
                .map { snap ->
                    snap.toObjects(FeatureRequest::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                }
                .catch { error ->
                    if (error is Exception) handleFirestoreError(error, OperationType.LIST, path)
                    throw error
                }
        )
    }

    fun observeAllFeatureRequestsForOwner(): Flow<List<FeatureRequest>> = flow {
        val path = "feature_requests"
        emitAll(
            db.collection(path)
                .snapshots()
                .map { snap ->
                    snap.toObjects(FeatureRequest::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                }
                .catch { error ->
                    if (error is Exception) handleFirestoreError(error, OperationType.LIST, path)
                    throw error
                }
        )
    }

    suspend fun updateFeatureRequestStatus(request: FeatureRequest, newStatus: String): Result<Unit> {
        val uid = requireUserId()
        require(request.id.isNotBlank()) { "FeatureRequest id must not be blank" }
        val docRef = db.collection("feature_requests").document(request.id)
        return try {
            docRef.update(
                mapOf(
                    "title" to request.title,
                    "description" to request.description,
                    "recommendedSkill" to request.recommendedSkill,
                    "status" to newStatus,
                    "requiresPlayUpdate" to request.requiresPlayUpdate,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            recordAuditLog(uid, "FEATURE_${newStatus.uppercase()}", "Marked feature '${request.title.take(50)}' as $newStatus")
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docRef.path)
            Result.failure(e)
        }
    }

    fun observeEnabledSkills(): Flow<List<SkillModule>> = flow {
        val path = "skills"
        emitAll(
            db.collection(path)
                .whereEqualTo("enabled", true)
                .snapshots()
                .map { snap ->
                    snap.toObjects(SkillModule::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                }
                .catch { error ->
                    if (error is Exception) handleFirestoreError(error, OperationType.LIST, path)
                    throw error
                }
        )
    }

    fun observeAllSkillsForOwner(): Flow<List<SkillModule>> = flow {
        val path = "skills"
        emitAll(
            db.collection(path)
                .snapshots()
                .map { snap ->
                    snap.toObjects(SkillModule::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                }
                .catch { error ->
                    if (error is Exception) handleFirestoreError(error, OperationType.LIST, path)
                    throw error
                }
        )
    }

    suspend fun saveSkillModule(
        skillId: String? = null,
        name: String,
        description: String,
        version: String,
        instructions: String,
        requiredPermissions: String,
        supportedLanguages: String,
        rolloutPercentage: Long,
        enabled: Boolean,
        testStatus: String
    ): Result<String> {
        val uid = requireUserId()
        val resolvedId = skillId?.takeIf { it.isNotBlank() }
            ?: "skill_${UUID.randomUUID().toString().replace("-", "").take(14)}"
        val docRef = db.collection("skills").document(resolvedId)
        return try {
            if (skillId.isNullOrBlank()) {
                val payload = mapOf(
                    "name" to name.trim().take(90),
                    "description" to description.trim().take(900),
                    "version" to version.trim().ifBlank { "1.0.0" }.take(24),
                    "instructions" to instructions.trim().take(3800),
                    "requiredPermissions" to requiredPermissions.trim().ifBlank { "None" }.take(250),
                    "supportedLanguages" to supportedLanguages.trim().ifBlank { "Bangla, English, Banglish" }.take(180),
                    "rolloutPercentage" to rolloutPercentage,
                    "enabled" to enabled,
                    "testStatus" to testStatus,
                    "updatedByUid" to uid,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                docRef.set(payload).await()
                recordAuditLog(uid, "SKILL_CREATED", "Published Skill '${name.take(40)}' v$version ($rolloutPercentage%)")
            } else {
                docRef.update(
                    mapOf(
                        "name" to name.trim().take(90),
                        "description" to description.trim().take(900),
                        "version" to version.trim().ifBlank { "1.0.0" }.take(24),
                        "instructions" to instructions.trim().take(3800),
                        "requiredPermissions" to requiredPermissions.trim().ifBlank { "None" }.take(250),
                        "supportedLanguages" to supportedLanguages.trim().ifBlank { "Bangla, English, Banglish" }.take(180),
                        "rolloutPercentage" to rolloutPercentage,
                        "enabled" to enabled,
                        "testStatus" to testStatus,
                        "updatedByUid" to uid,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
                recordAuditLog(uid, "SKILL_UPDATED", "Updated Skill '${name.take(40)}' enabled=$enabled ($rolloutPercentage%)")
            }
            Result.success(resolvedId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun deleteSkillModule(skillId: String): Result<Unit> {
        val uid = requireUserId()
        val docRef = db.collection("skills").document(skillId)
        return try {
            docRef.delete().await()
            recordAuditLog(uid, "SKILL_DELETED", "Removed Skill $skillId")
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, docRef.path)
            Result.failure(e)
        }
    }

    fun observeAuditLogsForOwner(): Flow<List<AuditEntry>> = flow {
        val path = "audit_logs"
        emitAll(
            db.collection(path)
                .snapshots()
                .map { snap ->
                    snap.toObjects(AuditEntry::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                }
                .catch { error ->
                    if (error is Exception) handleFirestoreError(error, OperationType.LIST, path)
                    throw error
                }
        )
    }

    suspend fun recordAuditLog(actorUid: String, action: String, summary: String) {
        val logId = "audit_${UUID.randomUUID().toString().replace("-", "").take(16)}"
        val docRef = db.collection("audit_logs").document(logId)
        try {
            docRef.set(
                mapOf(
                    "actorUid" to actorUid,
                    "action" to action.take(75),
                    "targetSummary" to summary.take(450),
                    "createdAt" to FieldValue.serverTimestamp()
                )
            ).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docRef.path)
        }
    }

    // --- Hand-Gesture Cross-Device File Transfer Relay ---

    suspend fun uploadGrabbedGestureFile(
        pairingCode: String,
        fileName: String,
        mimeType: String,
        payloadBase64: String,
        fileSizeBytes: Long
    ): Result<String> {
        val uid = requireUserId()
        val cleanCode = pairingCode.trim().uppercase().replace(Regex("[^A-Z0-9_\\-]"), "")
            .takeIf { it.length in 4..16 }
            ?: return Result.failure(IllegalArgumentException("পেয়ারিং কোড ৪ থেকে ১৬ অক্ষরের হতে হবে।"))
        val cleanName = fileName.trim().ifBlank { "transferred_file.bin" }.take(180)
        val cleanMime = mimeType.trim().ifBlank { "application/octet-stream" }.take(110)
        if (payloadBase64.isBlank() || payloadBase64.length > 740_000 || fileSizeBytes <= 0L || fileSizeBytes > 580_000L) {
            return Result.failure(IllegalArgumentException("ফাইল সাইজ সীমার মধ্যে রাখুন (সর্বোচ্চ ৫৫০ KB)।"))
        }

        val transferId = "gtr_${UUID.randomUUID().toString().replace("-", "").take(16)}"
        val docRef = db.collection("gesture_transfers").document(transferId)
        return try {
            docRef.set(
                mapOf(
                    "senderUid" to uid,
                    "pairingCode" to cleanCode,
                    "fileName" to cleanName,
                    "mimeType" to cleanMime,
                    "payloadBase64" to payloadBase64,
                    "fileSizeBytes" to fileSizeBytes,
                    "status" to "grabbed",
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(transferId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docRef.path)
            Result.failure(e)
        }
    }

    fun observeGrabbedTransfersForCode(pairingCode: String): Flow<List<GestureTransferSession>> = flow {
        requireUserId()
        val cleanCode = pairingCode.trim().uppercase().replace(Regex("[^A-Z0-9_\\-]"), "")
        if (cleanCode.length !in 4..16) {
            emit(emptyList())
            return@flow
        }
        val path = "gesture_transfers"
        emitAll(
            db.collection(path)
                .whereEqualTo("pairingCode", cleanCode)
                .whereEqualTo("status", "grabbed")
                .snapshots()
                .map { snap ->
                    snap.toObjects(GestureTransferSession::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                }
                .catch { error ->
                    if (error is Exception) handleFirestoreError(error, OperationType.LIST, path)
                    throw error
                }
        )
    }

    suspend fun markGestureTransferReceived(transferId: String): Result<Unit> {
        requireUserId()
        val docRef = db.collection("gesture_transfers").document(transferId)
        return try {
            docRef.update(
                mapOf(
                    "status" to "received",
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docRef.path)
            Result.failure(e)
        }
    }

    // --- Per-User Birthday Wishes & Automatic Reminders ---

    fun observeUserBirthdays(): Flow<List<BirthdayWishEntry>> = flow {
        val uid = requireUserId()
        val path = "users/$uid/birthdays"
        emitAll(
            db.collection("users").document(uid).collection("birthdays")
                .snapshots()
                .map { snap ->
                    snap.toObjects(BirthdayWishEntry::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                }
                .catch { error ->
                    if (error is Exception) handleFirestoreError(error, OperationType.LIST, path)
                    throw error
                }
        )
    }

    suspend fun saveUserBirthday(
        birthdayId: String? = null,
        personName: String,
        birthDay: Int,
        birthMonth: Int,
        phoneOrContact: String,
        wishMessage: String,
        lastWishedYear: Int = 0
    ): Result<String> {
        val uid = requireUserId()
        val resolvedId = birthdayId?.takeIf { it.isNotBlank() }
            ?: "bday_${UUID.randomUUID().toString().replace("-", "").take(14)}"
        val docRef = db.collection("users").document(uid).collection("birthdays").document(resolvedId)
        return try {
            val existing = docRef.get().await()
            val cleanName = personName.trim().ifBlank { "প্রিয়জন" }.take(90)
            val day = birthDay.coerceIn(1, 31).toLong()
            val month = birthMonth.coerceIn(1, 12).toLong()
            val cleanContact = phoneOrContact.trim().take(75)
            val cleanWish = wishMessage.trim().ifBlank { "শুভ জন্মদিন $cleanName! আপনার আগামী দিনগুলো আনন্দময় ও সুন্দর হোক।" }.take(550)
            val cleanYear = lastWishedYear.coerceIn(0, 2200).toLong()

            if (!existing.exists()) {
                docRef.set(
                    mapOf(
                        "userId" to uid,
                        "personName" to cleanName,
                        "birthDay" to day,
                        "birthMonth" to month,
                        "phoneOrContact" to cleanContact,
                        "wishMessage" to cleanWish,
                        "lastWishedYear" to cleanYear,
                        "createdAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            } else {
                docRef.update(
                    mapOf(
                        "personName" to cleanName,
                        "birthDay" to day,
                        "birthMonth" to month,
                        "phoneOrContact" to cleanContact,
                        "wishMessage" to cleanWish,
                        "lastWishedYear" to cleanYear,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            }
            Result.success(resolvedId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun markCloudBirthdayWished(birthdayId: String, year: Int): Result<Unit> {
        val uid = requireUserId()
        val docRef = db.collection("users").document(uid).collection("birthdays").document(birthdayId)
        return try {
            docRef.update(
                mapOf(
                    "lastWishedYear" to year.coerceIn(0, 2200).toLong(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun deleteUserBirthday(birthdayId: String): Result<Unit> {
        val uid = requireUserId()
        val docRef = db.collection("users").document(uid).collection("birthdays").document(birthdayId)
        return try {
            docRef.delete().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, docRef.path)
            Result.failure(e)
        }
    }
}
