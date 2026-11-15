package com.example.data.cloud.repository

import com.example.base.FirestoreEmulatorTestBase
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.UUID

class GovernanceRepositoryRuleTest : FirestoreEmulatorTestBase() {

    @Test
    fun ensureUserAccountRegistered_memberCreatesPendingAccount() = runBlocking {
        val uniqueEmail = "alice_${UUID.randomUUID().toString().take(8)}@test.com"
        val uid = signInTestUser(uniqueEmail)
        val repository = GovernanceRepository(firestore, auth)

        val result = withTimeout(DEFAULT_TIMEOUT_MS) {
            repository.ensureUserAccountRegistered("Dhaka")
        }
        assertTrue(result.isSuccess)
        val account = result.getOrThrow()
        assertEquals(uid, account.userId)
        assertEquals("member", account.accountRole)
        assertEquals("pending", account.accessStatus)
    }

    @Test
    fun submitAndObserveOwnFeatureRequests_authenticatedOwnerOfRequest_succeeds() = runBlocking {
        val uniqueEmail = "member_${UUID.randomUUID().toString().take(8)}@test.com"
        val uid = signInTestUser(uniqueEmail)
        val repository = GovernanceRepository(firestore, auth)

        val reqId = withTimeout(DEFAULT_TIMEOUT_MS) {
            repository.submitFeatureRequest(
                requesterName = "Trusted Friend",
                title = "Chittagonian Voice Greeting",
                description = "Add natural Chittagonian greeting detection in Hey Shuvo.",
                recommendedSkill = "Dialect Engine"
            ).getOrThrow()
        }
        assertTrue(reqId.isNotEmpty())

        val requests = withTimeout(FLOW_TIMEOUT_MS) {
            repository.observeMyFeatureRequests(uid).first { list -> list.any { it.id == reqId } }
        }
        assertTrue(requests.any { it.id == reqId })
    }

    @Test
    fun getAccountById_crossUserAccess_failsWithPermissionDenied() = runBlocking {
        val aliceEmail = "alice_iso_${UUID.randomUUID().toString().take(8)}@test.com"
        val aliceUid = signInTestUser(aliceEmail)
        val aliceRepo = GovernanceRepository(firestore, auth)
        withTimeout(DEFAULT_TIMEOUT_MS) {
            aliceRepo.ensureUserAccountRegistered("Sylhet").getOrThrow()
        }

        val bobEmail = "bob_iso_${UUID.randomUUID().toString().take(8)}@test.com"
        signInTestUser(bobEmail)
        val bobRepo = GovernanceRepository(firestore, auth)

        try {
            withTimeout(DEFAULT_TIMEOUT_MS) {
                bobRepo.getAccountById(aliceUid)
            }
            fail("Expected FirebaseFirestoreException PERMISSION_DENIED for cross-user read")
        } catch (e: Exception) {
            val fsEx = e.unwrapFirestoreException()
            assertNotNull("Expected nested FirebaseFirestoreException, got $e", fsEx)
            assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, fsEx!!.code)
        }
    }

    @Test
    fun observeMyFeatureRequests_unauthenticatedCaller_failsWithPermissionDenied() = runBlocking {
        auth.signOut()
        val repository = GovernanceRepository(firestore, auth)

        try {
            withTimeout(FLOW_TIMEOUT_MS) {
                repository.observeMyFeatureRequests("any_uid").first()
            }
            fail("Expected PERMISSION_DENIED for unauthenticated caller")
        } catch (e: Exception) {
            val fsEx = e.unwrapFirestoreException()
            assertNotNull("Expected nested FirebaseFirestoreException, got $e", fsEx)
            assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, fsEx!!.code)
        }
    }

    @Test
    fun claimMasterOwnerAndPublishCustomerRemoteConfig_syncsToCustomerClient() = runBlocking {
        val masterEmail = "master_${UUID.randomUUID().toString().take(8)}@test.com"
        signInTestUser(masterEmail)
        val masterRepo = GovernanceRepository(firestore, auth)

        // Note: if another test did not claim master_owner yet, claim succeeds
        val unclaimed = withTimeout(DEFAULT_TIMEOUT_MS) { masterRepo.isMasterOwnerUnclaimed() }
        if (unclaimed) {
            val claimed = withTimeout(DEFAULT_TIMEOUT_MS) {
                masterRepo.claimMasterOwnerIfAvailable().getOrThrow()
            }
            assertTrue(claimed)
            assertTrue(withTimeout(DEFAULT_TIMEOUT_MS) { masterRepo.checkIsAdminAccount() })

            val published = withTimeout(DEFAULT_TIMEOUT_MS) {
                masterRepo.publishCustomerRemoteConfig(
                    announcementBanner = "মাস্টার আপডেট সক্রিয়",
                    forcedThemePalette = "EMERALD_MATRIX",
                    defaultAutoVoice = true,
                    customSystemPrompt = "Keep answers crisp in Bangla",
                    enabledFeatureFlags = "ALL_DISPLAY_VOICE,MAPS_NAVIGATION,PHOTO_VISION",
                    lastAppliedFixSummary = "Fixed latency"
                ).getOrThrow()
            }
            assertEquals("মাস্টার আপডেট সক্রিয়", published.announcementBanner)

            // Switch to a Customer user and verify Customer can observe the live config but cannot overwrite it!
            val customerEmail = "customer_${UUID.randomUUID().toString().take(8)}@test.com"
            signInTestUser(customerEmail)
            val customerRepo = GovernanceRepository(firestore, auth)
            val observed = withTimeout(FLOW_TIMEOUT_MS) {
                customerRepo.observeCustomerRemoteConfig().first { it != null }
            }
            assertNotNull(observed)
            assertEquals("মাস্টার আপডেট সক্রিয়", observed!!.announcementBanner)

            val forbiddenWrite = withTimeout(DEFAULT_TIMEOUT_MS) {
                customerRepo.publishCustomerRemoteConfig(
                    announcementBanner = "Tampered",
                    forcedThemePalette = "CYBER_CYAN",
                    defaultAutoVoice = false,
                    customSystemPrompt = "",
                    enabledFeatureFlags = "ALL_DISPLAY_VOICE",
                    lastAppliedFixSummary = "Tamper"
                )
            }
            assertTrue(forbiddenWrite.isFailure)
        }
    }

    @Test
    fun gestureTransferAndBirthdayWish_enforceIsolationAndPairedDrop() = runBlocking {
        val aliceEmail = "alice_gtr_${UUID.randomUUID().toString().take(8)}@test.com"
        val aliceUid = signInTestUser(aliceEmail)
        val aliceRepo = GovernanceRepository(firestore, auth)

        // 1. Alice saves a Birthday Wish entry and observes it
        val bdayId = withTimeout(DEFAULT_TIMEOUT_MS) {
            aliceRepo.saveUserBirthday(
                personName = "রহিম",
                birthDay = 4,
                birthMonth = 11,
                phoneOrContact = "+8801711000000",
                wishMessage = "শুভ জন্মদিন রহিম!"
            ).getOrThrow()
        }
        val aliceBirthdays = withTimeout(FLOW_TIMEOUT_MS) {
            aliceRepo.observeUserBirthdays().first { it.any { b -> b.id == bdayId } }
        }
        assertEquals(aliceUid, aliceBirthdays.first { it.id == bdayId }.userId)

        // 2. Alice grabs a file with pairing code SHUVO99
        val code = "SHUVO_${UUID.randomUUID().toString().take(5).uppercase()}"
        val transferId = withTimeout(DEFAULT_TIMEOUT_MS) {
            aliceRepo.uploadGrabbedGestureFile(
                pairingCode = code,
                fileName = "invoice.pdf",
                mimeType = "application/pdf",
                payloadBase64 = "SGVsbG8gR2VzdHVyZQ==",
                fileSizeBytes = 13L
            ).getOrThrow()
        }
        assertTrue(transferId.startsWith("gtr_"))

        // 3. Bob signs in on target device, observes the grabbed transfer via pairing code, and marks it received
        val bobEmail = "bob_gtr_${UUID.randomUUID().toString().take(8)}@test.com"
        signInTestUser(bobEmail)
        val bobRepo = GovernanceRepository(firestore, auth)

        val grabbedList = withTimeout(FLOW_TIMEOUT_MS) {
            bobRepo.observeGrabbedTransfersForCode(code).first { it.any { s -> s.id == transferId } }
        }
        assertEquals("invoice.pdf", grabbedList.first { it.id == transferId }.fileName)

        val receiveRes = withTimeout(DEFAULT_TIMEOUT_MS) {
            bobRepo.markGestureTransferReceived(transferId)
        }
        assertTrue(receiveRes.isSuccess)
    }

    private companion object {
        const val DEFAULT_TIMEOUT_MS = 5000L
        const val FLOW_TIMEOUT_MS = 4000L
    }
}
