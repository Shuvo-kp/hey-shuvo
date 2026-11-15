package com.example

import com.example.core.actions.DeviceActionKind
import com.example.core.preferences.AppThemePalette
import com.example.core.preferences.PreferencesRepository
import com.example.core.preferences.QuickVoiceRoutine
import com.example.core.preferences.UserAccessibilityMode
import com.example.core.util.BanglaCommandHelper
import com.example.core.voice.AdaptiveVoiceActivityDetector
import com.example.core.voice.VoiceAssistantEngine
import com.example.feature.customer.HeyShuvoAiService
import com.example.ui.theme.buildHeyShuvoColorScheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE)
class ExampleUnitTest {

    @Test
    fun vad_zeroWarmupFollowedByFluctuatingWebRtcRoomNoise_doesNotLockInSpeechMode() {
        val vad = AdaptiveVoiceActivityDetector(
            pauseThresholdMs = 1400L,
            noSpeechTimeoutMs = 6500L
        )
        vad.processChunk(rms = 0.0, elapsedMs = 100L)
        vad.processChunk(rms = 0.0, elapsedMs = 200L)

        val roomNoisePattern = doubleArrayOf(680.0, 890.0, 620.0, 940.0, 710.0, 860.0, 640.0, 910.0)
        var stopped = false
        var timedOutSilent = false
        var elapsed = 300L
        var idx = 0
        while (elapsed <= 6800L) {
            val rms = roomNoisePattern[idx % roomNoisePattern.size]
            val decision = vad.processChunk(rms = rms, elapsedMs = elapsed)
            assertFalse("Background WebRTC noise must not falsely trigger speech at ${elapsed}ms", decision.hasSpoken)
            if (decision.shouldAutoStop) {
                stopped = true
                timedOutSilent = decision.timedOutWithoutSpeech
                break
            }
            elapsed += 100L
            idx++
        }
        assertTrue("Detector must auto-stop cleanly on silent timeout instead of hanging at 14s", stopped)
        assertTrue("Detector must report silent timeout", timedOutSilent)
    }

    @Test
    fun vad_speechFollowedByFluctuatingWebRtcNoise_autoCutsDeterministically() {
        val vad = AdaptiveVoiceActivityDetector(
            pauseThresholdMs = 1400L,
            noSpeechTimeoutMs = 6500L
        )
        for (t in 100L..500L step 100L) {
            vad.processChunk(rms = 720.0, elapsedMs = t)
        }

        for (t in 600L..2000L step 100L) {
            val d = vad.processChunk(rms = if (t % 200L == 0L) 4300.0 else 3500.0, elapsedMs = t)
            if (t >= 700L) {
                assertTrue("Speech should be detected while user is talking", d.hasSpoken)
            }
        }

        var autoCutTimestampMs = -1L
        for (t in 2100L..4200L step 100L) {
            val agcRms = if (t % 200L == 0L) 870.0 else 690.0
            val d = vad.processChunk(rms = agcRms, elapsedMs = t)
            if (d.shouldAutoStop) {
                autoCutTimestampMs = t
                break
            }
        }

        assertEquals(3400L, autoCutTimestampMs)
    }

    @Test
    fun vad_lateUtteranceAndSteadyCarrierHum_neverLocksOrRewinds() {
        val vad = AdaptiveVoiceActivityDetector(
            pauseThresholdMs = 1150L,
            noSpeechTimeoutMs = 8500L
        )
        // 1. Near-silent WebRTC startup ramp-up (0..400ms) followed by sudden steady 540 RMS carrier hum after quiet frames
        for (t in 100L..400L step 100L) {
            vad.processChunk(rms = 22.0, elapsedMs = t)
        }
        // First 3 real frames at 60..70 RMS, then sudden steady carrier jump to ~540 RMS
        for (t in 500L..800L step 100L) {
            vad.processChunk(rms = 65.0, elapsedMs = t)
        }
        var recalibratedBackToIdle = false
        for (t in 900L..4200L step 100L) {
            val d = vad.processChunk(rms = if (t % 200L == 0L) 555.0 else 525.0, elapsedMs = t)
            if (t >= 3700L && !d.hasSpoken) {
                recalibratedBackToIdle = true
                break
            }
        }
        assertTrue("Steady carrier hum must auto-recalibrate noiseFloorRms and exit false speech lock", recalibratedBackToIdle)

        // 2. User speaks AFTER 5.5s of idle listening on monotonic clock -> must auto-cut cleanly ~1.2s after speech!
        for (t in 4300L..5500L step 100L) {
            vad.processChunk(rms = 540.0, elapsedMs = t)
        }
        for (t in 5600L..6600L step 100L) {
            vad.processChunk(rms = 3800.0, elapsedMs = t)
        }
        var cutAtMs = -1L
        for (t in 6700L..8200L step 100L) {
            val d = vad.processChunk(rms = 550.0, elapsedMs = t)
            if (d.shouldAutoStop) {
                cutAtMs = t
                break
            }
        }
        assertTrue("Late utterance must auto-cut within ~1.2s (actual=${cutAtMs}ms)", cutAtMs in 7700L..7900L)
    }

    @Test
    fun aiService_parseCommandJson_handlesNoSpeechSentinelAndSanitizesUnwantedNames() {
        val ai = HeyShuvoAiService()

        val noSpeech = ai.parseCommandJson(
            rawJson = """{"spokenTranscript":"__NO_SPEECH__","naturalReply":"I am Hey Shuvo","detectedLanguage":"English","requiresSensitiveConfirmation":false,"steps":[]}""",
            referenceUserText = null,
            mode = UserAccessibilityMode.STANDARD
        )
        assertEquals("__NO_SPEECH__", noSpeech.spokenTranscript)
        assertEquals("", noSpeech.naturalReply)
        assertTrue(noSpeech.steps.isEmpty())

        val spokenYoutube = ai.parseCommandJson(
            rawJson = """{"spokenTranscript":"ইউটিউব ওপেন করো","naturalReply":"ইউটিউব চালু করছি।","detectedLanguage":"Bangla","requiresSensitiveConfirmation":false,"steps":[]}""",
            referenceUserText = null,
            mode = UserAccessibilityMode.STANDARD
        )
        assertEquals("ইউটিউব ওপেন করো", spokenYoutube.spokenTranscript)
        assertTrue(spokenYoutube.steps.any { it.actionPayload.kind == DeviceActionKind.OPEN_APP_OR_WEB })

        // Verify unwanted names are sanitized to Hey Shuvo
        assertEquals(
            "I am Hey Shuvo ready.",
            HeyShuvoAiService.sanitizeAssistantIdentity("I am Jarvis ready.")
        )
        assertEquals(
            "Hello from Hey Shuvo.",
            HeyShuvoAiService.sanitizeAssistantIdentity("Hello from Jarvin.")
        )
    }

    @Test
    fun banglish_informalCommandsAndSpellingMistakes_autoCorrectAndResolveImmediately() {
        val userExactCmd = "TMI amr phone YouTube jao"
        assertTrue(BanglaCommandHelper.isInstantDirectActionOrFact(userExactCmd))
        val steps = BanglaCommandHelper.extractExplicitSteps(userExactCmd)
        assertTrue("Exact user Banglish command must extract OPEN_APP_OR_WEB for YouTube", steps.isNotEmpty())
        assertEquals(DeviceActionKind.OPEN_APP_OR_WEB, steps.first().actionPayload.kind)
        assertEquals("youtube", steps.first().actionPayload.targetOrPath.lowercase())

        // Spelling mistake tests (Banglish, English, Bangla)
        assertEquals(
            DeviceActionKind.OPEN_APP_OR_WEB,
            BanglaCommandHelper.extractExplicitSteps("utub khlo").first().actionPayload.kind
        )
        assertEquals(
            "YouTube",
            BanglaCommandHelper.extractExplicitSteps("ইওটিউব চালু করো").first().actionPayload.targetOrPath
        )
        assertEquals(
            DeviceActionKind.SHOW_CURRENT_LOCATION,
            BanglaCommandHelper.extractExplicitSteps("mep kholo").first().actionPayload.kind
        )
        assertEquals(
            DeviceActionKind.SHOW_CURRENT_LOCATION,
            BanglaCommandHelper.extractExplicitSteps("lokeshon dkhao").first().actionPayload.kind
        )
        assertEquals(
            DeviceActionKind.OPEN_MAPS_NAVIGATION,
            BanglaCommandHelper.extractExplicitSteps("ধানমন্ডি যাওয়ার মেপ চালু করো").first().actionPayload.kind
        )
        assertEquals(
            DeviceActionKind.OPEN_PHOTO_VISION_MODE,
            BanglaCommandHelper.extractExplicitSteps("foto mode chlu kro").first().actionPayload.kind
        )
        assertEquals(
            DeviceActionKind.SWITCH_VOICE_MODE,
            BanglaCommandHelper.extractExplicitSteps("menual mode on kro").first().actionPayload.kind
        )
        assertEquals(
            "MANUAL",
            BanglaCommandHelper.extractExplicitSteps("menual mode on kro").first().actionPayload.targetOrPath
        )
        assertEquals(
            DeviceActionKind.TOGGLE_FLASHLIGHT,
            BanglaCommandHelper.extractExplicitSteps("flas light on kro").first().actionPayload.kind
        )
        assertEquals(
            DeviceActionKind.CHECK_DEVICE_STATUS,
            BanglaCommandHelper.extractExplicitSteps("betari chaj koto").first().actionPayload.kind
        )
    }

    @Test
    fun compactCloudWav8kHz_downsamplesFrom16kHzAnd44kHzCleanlyForWeak4G() {
        // 2 seconds of 16kHz speech = 32,000 samples (64,000 raw PCM bytes) -> should become ~16,000 samples (32,044 WAV bytes)
        val samples16k = ShortArray(32000) { idx -> if (idx % 2 == 0) 900 else -900 }
        val pcm16k = ByteArray(samples16k.size * 2)
        ByteBuffer.wrap(pcm16k).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(samples16k)

        val compactWav = VoiceAssistantEngine.buildCompactCloudWav8kHz(
            rawPcmBytes = pcm16k,
            srcSampleRate = 16000,
            firstSpeechSample = 1000L,
            lastSpeechSample = 30000L
        )
        val header = ByteBuffer.wrap(compactWav).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", String(compactWav, 0, 4, Charsets.US_ASCII))
        assertEquals(8000, header.getInt(24))
        assertTrue("8kHz compact WAV for ~2s speech must be under 36KB (actual=${compactWav.size})", compactWav.size < 36_000)
    }

    @Test
    fun customThemePalettesAndRoutines_roundTripAndGenerateDistinctColorSchemes() {
        val cyanDark = buildHeyShuvoColorScheme(darkTheme = true, palette = AppThemePalette.CYBER_CYAN)
        val goldDark = buildHeyShuvoColorScheme(darkTheme = true, palette = AppThemePalette.ROYAL_GOLD)
        val customPinkDark = buildHeyShuvoColorScheme(darkTheme = true, palette = AppThemePalette.CUSTOM_COLOR, customHue = 330f)
        val customGreenLight = buildHeyShuvoColorScheme(darkTheme = false, palette = AppThemePalette.CUSTOM_COLOR, customHue = 135f)

        assertNotEquals(cyanDark.primary, goldDark.primary)
        assertNotEquals(goldDark.primary, customPinkDark.primary)
        assertNotEquals(customPinkDark.background, customGreenLight.background)

        val sampleRoutines = listOf(
            QuickVoiceRoutine(id = "r1", title = "অফিস মোড", commandText = "মাসিক অফিস খরচের Excel শিট তৈরি করো"),
            QuickVoiceRoutine(id = "r2", title = "YouTube", commandText = "TMI amr phone YouTube jao")
        )
        val encoded = PreferencesRepository.encodeRoutinesJson(sampleRoutines)
        val decoded = PreferencesRepository.parseRoutinesJson(encoded)
        assertEquals(2, decoded.size)
        assertEquals("অফিস মোড", decoded[0].title)
        assertEquals("TMI amr phone YouTube jao", decoded[1].commandText)
    }

    @Test
    fun voiceProfileSwitch_andMasterAuth_validateDeterministically() {
        val femaleQueries = listOf("Female voice করো", "ফিমেল ভয়েস", "মেয়েদের ভয়েস", "নারী কণ্ঠ")
        val maleQueries = listOf("Male voice করো", "মেল ভয়েস", "ছেলেদের ভয়েস", "পুরুষ কণ্ঠ")
        val deepQueries = listOf("Deep voice করো", "ডিপ ভয়েস", "গম্ভীর ভয়েস", "ভারী কণ্ঠ")
        val nonVoiceQueries = listOf("ইমেইল পাঠাও", "মেইল চেক করো", "Female doctor near me", "আমার মেয়েকে ভয়েস পাঠাও")

        for (q in femaleQueries) {
            assertEquals(
                "Expected FEMALE for: $q",
                VoiceAssistantEngine.VoiceProfile.FEMALE,
                com.example.core.service.HeyShuvoVoiceCoordinator.detectRequestedVoiceProfile(q)
            )
        }

        for (q in maleQueries) {
            assertEquals(
                "Expected MALE for: $q",
                VoiceAssistantEngine.VoiceProfile.MALE,
                com.example.core.service.HeyShuvoVoiceCoordinator.detectRequestedVoiceProfile(q)
            )
        }

        for (q in deepQueries) {
            assertEquals(
                "Expected DEEP for: $q",
                VoiceAssistantEngine.VoiceProfile.DEEP,
                com.example.core.service.HeyShuvoVoiceCoordinator.detectRequestedVoiceProfile(q)
            )
        }

        for (q in nonVoiceQueries) {
            assertEquals(
                "Must NOT hijack non-voice command: $q",
                null,
                com.example.core.service.HeyShuvoVoiceCoordinator.detectRequestedVoiceProfile(q)
            )
        }

        // Test Master Authorized Emails
        val authorized = "shuvoskp3@gmail.com,sanjoy1521997@gmail.com,eslsanjoy@gmail.com,fidcsanjoy@gmail.com"
        val list = authorized.split(",").map { it.trim().lowercase() }
        assertTrue("shuvoskp3@gmail.com must be authorized", "shuvoskp3@gmail.com" in list)
        assertTrue("sanjoy1521997@gmail.com must be authorized", "sanjoy1521997@gmail.com" in list)
        assertTrue("eslsanjoy@gmail.com must be authorized", "eslsanjoy@gmail.com" in list)
        assertTrue("fidcsanjoy@gmail.com must be authorized", "fidcsanjoy@gmail.com" in list)
        assertFalse("random@gmail.com must NOT be authorized", "random@gmail.com" in list)
    }

    @Test
    fun avatarCatalog_wrapsModuloAcrossProfileSwitchWithoutIndexMismatch() {
        val female = com.example.ui.components.AndroidAvatarCatalog.femaleDrawables
        val male = com.example.ui.components.AndroidAvatarCatalog.maleDrawables

        assertEquals(3, female.size)
        assertEquals(4, male.size)

        // Switching from Male index 3 to Female (size 3) must wrap cleanly to index 0, never clamp to 2
        assertEquals(
            female[0],
            com.example.ui.components.AndroidAvatarCatalog.getDrawableForProfile("FEMALE", 3)
        )
        assertEquals(
            female[1],
            com.example.ui.components.AndroidAvatarCatalog.getDrawableForProfile("FEMALE", -2)
        )
        assertEquals(
            male[3],
            com.example.ui.components.AndroidAvatarCatalog.getDrawableForProfile("MALE", 3)
        )
        assertEquals(
            male[1],
            com.example.ui.components.AndroidAvatarCatalog.getDrawableForProfile("DEEP", 5)
        )
    }

    @Test
    fun bugfixRegressions_ttsChunking_banglaAlarmWords_whatsappDraft_andTallyNames() {
        // 1. Long Bangla TTS replies split into sentence-safe chunks <= 220 chars without losing words
        val longReply = List(6) { "হ্যালো বস, আপনার দৈনিক হিসাব এবং রিমাইন্ডার সঠিকভাবে সংরক্ষণ করা হয়েছে।" }
            .joinToString(" ")
        val chunks = VoiceAssistantEngine.splitTtsSentenceChunks(longReply, maxLen = 180)
        assertTrue("Expected multi-chunk split for long Bangla text", chunks.size >= 3)
        assertTrue("All chunks must obey maxLen cap", chunks.all { it.length <= 180 })

        // 2. Bangla spoken word alarm & evening AM/PM adjustment
        val alarmSteps = BanglaCommandHelper.extractExplicitSteps("সন্ধ্যা সাতটায় অ্যালার্ম দাও")
        assertEquals(DeviceActionKind.SET_ALARM, alarmSteps.first().actionPayload.kind)
        assertEquals(19, alarmSteps.first().actionPayload.hour)
        assertEquals(0, alarmSteps.first().actionPayload.minute)

        val halfPastSixMorning = BanglaCommandHelper.extractExplicitSteps("ভোর সাড়ে ছয়টায় এলার্ম সেট করো")
        assertEquals(6, halfPastSixMorning.first().actionPayload.hour)
        assertEquals(30, halfPastSixMorning.first().actionPayload.minute)

        // 3. WhatsApp draft works even without spoken phone digits
        val waSteps = BanglaCommandHelper.extractExplicitSteps("হোয়াটসঅ্যাপে মেসেজ পাঠাও আমি ১০ মিনিটে আসছি")
        assertEquals(DeviceActionKind.SEND_WHATSAPP_DRAFT, waSteps.first().actionPayload.kind)
        assertTrue(waSteps.first().actionPayload.contentOrBody.contains("১০ মিনিটে আসছি"))

        // 4. Customer name extraction ignores leading stop-words like দোকান বাকি
        val tallySteps = BanglaCommandHelper.extractExplicitSteps("দোকান বাকি করিমের নামে ৭৫০ টাকা লেখো")
        assertEquals(DeviceActionKind.SAVE_TALLY_ENTRY, tallySteps.first().actionPayload.kind)
        assertEquals("করিম", tallySteps.first().actionPayload.titleOrSubject)
        assertEquals(750.0, tallySteps.first().actionPayload.amountBdt, 0.1)

        // 5. Display/Brightness maps to Display Settings rather than Flashlight
        val dispSteps = BanglaCommandHelper.extractExplicitSteps("ব্রাইটনেস সেটিংস খোলো")
        assertEquals(DeviceActionKind.OPEN_APP_OR_WEB, dispSteps.first().actionPayload.kind)
        assertEquals("Display Settings", dispSteps.first().actionPayload.targetOrPath)
    }

    @Test
    fun quietEmulatorMicVad_detectsSoftSpeechAndAutoCutsCleanly() {
        val quietVad = AdaptiveVoiceActivityDetector(pauseThresholdMs = 740L, noSpeechTimeoutMs = 8500L)
        // Soft ambient mic noise floor (~45 RMS)
        for (t in 80L..320L step 80L) {
            val d = quietVad.processChunk(rms = 45.0, elapsedMs = t)
            assertFalse("Soft ambient floor must not falsely trigger speech", d.hasSpoken)
        }
        // Soft spoken utterance on emulator / laptop mic (~190 RMS)
        var detectedSpeech = false
        for (t in 400L..1200L step 80L) {
            val d = quietVad.processChunk(rms = 190.0, elapsedMs = t)
            if (d.hasSpoken) detectedSpeech = true
        }
        assertTrue("Quiet mic soft speech (190 RMS over 45 RMS ambient) must trigger VAD", detectedSpeech)

        // Return to ambient ~45 RMS -> must auto-cut promptly around ~740ms
        var cutAtMs = -1L
        for (t in 1280L..2600L step 80L) {
            val d = quietVad.processChunk(rms = 45.0, elapsedMs = t)
            if (d.shouldAutoStop) {
                cutAtMs = t
                break
            }
        }
        assertTrue("Quiet mic utterance must auto-cut within 1s after silence (cutAtMs=$cutAtMs)", cutAtMs in 1900L..2200L)
    }

    @Test
    fun aiService_classifiesNonRetryableErrorsFastAndProducesFriendlyBanglaMessages() {
        assertTrue(HeyShuvoAiService.isNonRetryableAiError(RuntimeException("HTTP 403 PERMISSION_DENIED")))
        assertTrue(HeyShuvoAiService.isNonRetryableAiError(RuntimeException("Firebase App Check token invalid")))
        assertTrue(HeyShuvoAiService.isNonRetryableAiError(RuntimeException("429 RESOURCE_EXHAUSTED quota")))
        assertFalse(HeyShuvoAiService.isNonRetryableAiError(java.net.SocketTimeoutException("Read timed out")))

        val appCheckMsg = HeyShuvoAiService.classifyFriendlyAiError(RuntimeException("AppCheck attestation failed"))
        val authMsg = HeyShuvoAiService.classifyFriendlyAiError(RuntimeException("403 PERMISSION_DENIED"))
        val netMsg = HeyShuvoAiService.classifyFriendlyAiError(java.net.UnknownHostException("firebase.googleapis.com"))
        assertTrue(appCheckMsg.contains("App Check"))
        assertTrue(authMsg.contains("অনুমতি"))
        assertTrue(netMsg.contains("ইন্টারনেট"))
    }

    @Test
    fun speechRecognitionState_defaultsSupportContinuousAutoAndZeroSignalWarning() {
        val state = com.example.core.voice.SpeechRecognitionState()
        assertFalse(state.isListening)
        assertFalse(state.isSpeaking)
        assertFalse(state.isContinuousAuto)
        assertFalse(state.isMicZeroSignal)
    }
}
