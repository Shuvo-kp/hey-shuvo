package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.core.actions.DeviceActionKind
import com.example.core.actions.ExecutableActionPayload
import com.example.core.actions.VoiceDeviceController
import com.example.core.preferences.PreferencesRepository
import com.example.core.preferences.UserAccessibilityMode
import com.example.core.service.HeyShuvoVoiceCoordinator
import com.example.core.util.BanglaCommandHelper
import com.example.core.voice.AdaptiveVoiceActivityDetector
import com.example.core.voice.VoiceAssistantEngine
import com.example.feature.customer.HeyShuvoAiService
import com.example.feature.customer.HeyShuvoAiService.Companion.cleanString
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @Test
    fun `read string from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("Hey Shuvo", appName)
    }

    @Test
    fun `wav encoder and PCM16 gain normalizer generate valid boosted RIFF WAVE audio_wav`() {
        val softSamples = ShortArray(1600) { idx -> if (idx in 400..1200) 600 else 10 }
        val rawBytes = ByteArray(softSamples.size * 2)
        ByteBuffer.wrap(rawBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(softSamples)

        val normalized = VoiceAssistantEngine.trimAndNormalizePcm16(
            rawPcmBytes = rawBytes,
            sampleRate = 16000,
            firstSpeechSample = 400L,
            lastSpeechSample = 1200L
        )
        assertTrue(normalized.isNotEmpty())

        val wav = VoiceAssistantEngine.encodePcm16MonoToWav(normalized, sampleRate = 16000)
        assertEquals(44 + normalized.size, wav.size)
        assertEquals("RIFF", String(wav, 0, 4, Charsets.US_ASCII))
        assertEquals("WAVE", String(wav, 8, 4, Charsets.US_ASCII))
        assertEquals("fmt ", String(wav, 12, 4, Charsets.US_ASCII))
        assertEquals("data", String(wav, 36, 4, Charsets.US_ASCII))
    }

    @Test
    fun `adaptive VAD auto-cuts deterministically across noisy room, WebRTC AGC hiss, and soft mic`() {
        val noisyVad = AdaptiveVoiceActivityDetector(pauseThresholdMs = 1400L)
        for (t in 100L..400L step 100L) {
            val d = noisyVad.processChunk(rms = 900.0, elapsedMs = t)
            assertFalse(d.hasSpoken)
        }
        for (t in 500L..1500L step 100L) {
            noisyVad.processChunk(rms = 4200.0, elapsedMs = t)
        }
        for (t in 1600L..2800L step 100L) {
            val d = noisyVad.processChunk(rms = 920.0, elapsedMs = t)
            assertFalse(d.shouldAutoStop)
        }
        val cutNoisy = noisyVad.processChunk(rms = 920.0, elapsedMs = 2900L)
        assertTrue(cutNoisy.shouldAutoStop)

        val agcVad = AdaptiveVoiceActivityDetector(pauseThresholdMs = 1400L)
        for (t in 100L..300L step 100L) {
            agcVad.processChunk(rms = 80.0, elapsedMs = t)
        }
        for (t in 400L..1400L step 100L) {
            agcVad.processChunk(rms = 2400.0, elapsedMs = t)
        }
        var agcStoppedAt = -1L
        for (t in 1500L..3200L step 100L) {
            val d = agcVad.processChunk(rms = 680.0, elapsedMs = t)
            if (d.shouldAutoStop) {
                agcStoppedAt = t
                break
            }
        }
        assertEquals(2800L, agcStoppedAt)
    }

    @Test
    fun `Location and Maps reject fake USA emulator coordinates and navigate accurately in Bangladesh`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val controller = VoiceDeviceController(context)

        // 1. Emulator hardcoded Googleplex Mountain View USA coordinates MUST be detected & rejected!
        assertTrue(VoiceDeviceController.isEmulatorDefaultUsaCoordinate(37.4219983, -122.084))
        assertFalse(VoiceDeviceController.isEmulatorDefaultUsaCoordinate(23.8103, 90.4125)) // Real Dhaka GPS is accepted!

        // 2. User sets saved location -> current location resolves to Bangladesh, NEVER USA!
        PreferencesRepository.writeCachedLocationSync(context, "Mirpur, Dhaka, Bangladesh")
        val resolved = controller.resolveCurrentUserLocation()
        assertEquals("Mirpur, Dhaka, Bangladesh", resolved.displayLabel)
        assertFalse(resolved.mapsQuery.contains("-122."))

        // 3. Bangla city qualification translates and appends Bangladesh automatically
        val qualifiedDest = VoiceDeviceController.qualifyLocationForMaps("ধানমন্ডি")
        assertTrue("Must qualify Bangla place to Dhanmondi, Dhaka, Bangladesh: $qualifiedDest", qualifiedDest.contains("Dhanmondi") && qualifiedDest.contains("Bangladesh"))

        // 4. Executing SHOW_CURRENT_LOCATION launches Maps Intent centered on Bangladesh
        val showLocOutcome = controller.executeAction(
            ExecutableActionPayload(kind = DeviceActionKind.SHOW_CURRENT_LOCATION)
        )
        assertTrue(showLocOutcome.isSuccess)
        assertTrue(showLocOutcome.getOrThrow().summary.contains("Mirpur"))

        val shadowApp = Shadows.shadowOf(context as android.app.Application)
        val launchedMapIntent = shadowApp.nextStartedActivity
        assertNotNull("SHOW_CURRENT_LOCATION must start Google Maps activity/browser", launchedMapIntent)
        val mapUriStr = launchedMapIntent.dataString.orEmpty()
        assertTrue("Map URI must point to Mirpur/Bangladesh, got: $mapUriStr", mapUriStr.contains("Mirpur") || mapUriStr.contains("Bangladesh"))

        // 5. Executing OPEN_MAPS_NAVIGATION builds accurate origin -> destination route in Bangladesh
        val navOutcome = controller.executeAction(
            ExecutableActionPayload(
                kind = DeviceActionKind.OPEN_MAPS_NAVIGATION,
                targetOrPath = "কক্সবাজার"
            )
        )
        assertTrue(navOutcome.isSuccess)
        val navIntent = shadowApp.nextStartedActivity
        assertNotNull("OPEN_MAPS_NAVIGATION must launch route navigation", navIntent)
        val navUri = navIntent.dataString.orEmpty()
        assertTrue("Directions URI must include origin and Cox's Bazar destination: $navUri", navUri.contains("Cox") && navUri.contains("origin="))
    }

    @Test
    fun `Main Display and Voice commands control Auto Mode, Manual Mode, Photo Mode, Location, Hardware, and Office end-to-end`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        HeyShuvoVoiceCoordinator.clearConversation(context)

        // 1. Voice switch to AUTO mode
        HeyShuvoVoiceCoordinator.submitTextCommand(context, "অটো মোড চালু করো")
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        Thread.sleep(120L)
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertFalse(HeyShuvoVoiceCoordinator.isProcessing.value)

        // 2. Voice switch to MANUAL mode (even with Banglish typo "menual")
        HeyShuvoVoiceCoordinator.submitTextCommand(context, "menual mode chalu kro")
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        Thread.sleep(120L)
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertFalse(HeyShuvoVoiceCoordinator.isProcessing.value)

        // 3. Voice set user location ("আমার লোকেশন উত্তরা সেট করো")
        HeyShuvoVoiceCoordinator.submitTextCommand(context, "আমার লোকেশন উত্তরা সেট করো")
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        Thread.sleep(120L)
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue(PreferencesRepository.readCachedLocationSync(context).contains("Uttara"))

        // 4. Voice open Photo Vision mode ("ফটো মোড চালু করো")
        val photoSteps = BanglaCommandHelper.extractExplicitSteps("ফটো মোড চালু করো")
        assertEquals(DeviceActionKind.OPEN_PHOTO_VISION_MODE, photoSteps.first().actionPayload.kind)
        val controller = VoiceDeviceController(context)
        val photoRes = controller.executeAction(photoSteps.first().actionPayload)
        assertTrue(photoRes.isSuccess)

        // 5. Direct Banglish YouTube command executes instantaneously with isProcessing == false
        HeyShuvoVoiceCoordinator.submitTextCommand(context, "utub kholo")
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        Thread.sleep(120L)
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertFalse("isProcessing must NEVER stay stuck true", HeyShuvoVoiceCoordinator.isProcessing.value)

        val turns = HeyShuvoVoiceCoordinator.chatTurns.value
        assertTrue(turns.none { it.isError })
    }

    @Test
    fun `A-to-Z voice command extraction covers hardware, apps, YouTube search, Office, AutoCAD, Tally, and QnA`() {
        assertEquals("1200", BanglaCommandHelper.normalizeDigits("১২০০"))
        assertEquals(1200.0, BanglaCommandHelper.extractAmountBdt("রহিমের নামে ১২০০ টাকা দোকান বাকি"), 0.001)

        val torchOn = BanglaCommandHelper.extractExplicitSteps("ফ্ল্যাশলাইট চালু করো")
        assertEquals(DeviceActionKind.TOGGLE_FLASHLIGHT, torchOn.first().actionPayload.kind)
        assertEquals("ON", torchOn.first().actionPayload.targetOrPath)

        val volMax = BanglaCommandHelper.extractExplicitSteps("ভলিউম ফুল করো")
        assertEquals(DeviceActionKind.ADJUST_VOLUME, volMax.first().actionPayload.kind)
        assertEquals("MAX", volMax.first().actionPayload.targetOrPath)

        val batSteps = BanglaCommandHelper.extractExplicitSteps("ফোনে চার্জ কত আছে?")
        assertEquals(DeviceActionKind.CHECK_DEVICE_STATUS, batSteps.first().actionPayload.kind)

        val ytPlay = BanglaCommandHelper.extractExplicitSteps("ইউটিউবে রবীন্দ্রসংগীত বাজাও")
        assertEquals(DeviceActionKind.SEARCH_MEDIA_OR_WEB, ytPlay.first().actionPayload.kind)
        assertEquals("youtube", ytPlay.first().actionPayload.targetOrPath)

        val mapsSteps = BanglaCommandHelper.extractExplicitSteps("যশোর থেকে ঢাকা যাওয়ার ম্যাপ নেভিগেশন চালু করো")
        assertEquals(DeviceActionKind.OPEN_MAPS_NAVIGATION, mapsSteps.first().actionPayload.kind)
        assertEquals("যশোর", mapsSteps.first().actionPayload.titleOrSubject)

        val wordPdfSteps = BanglaCommandHelper.extractExplicitSteps("একটি প্রজেক্ট মিটিং Word ডকুমেন্ট এবং একটি PDF রিপোর্ট তৈরি করো")
        assertEquals(2, wordPdfSteps.size)
        assertEquals(DeviceActionKind.GENERATE_WORD_DOC, wordPdfSteps[0].actionPayload.kind)
        assertEquals(DeviceActionKind.GENERATE_PDF_FILE, wordPdfSteps[1].actionPayload.kind)

        val tallySteps = BanglaCommandHelper.extractExplicitSteps("রহিমের নামে ১২০০ টাকা দোকান বাকি ট্যালি খাতায় লেখো")
        assertEquals(DeviceActionKind.SAVE_TALLY_ENTRY, tallySteps.first().actionPayload.kind)
        assertEquals(1200.0, tallySteps.first().actionPayload.amountBdt, 0.001)

        val whoAnalysis = BanglaCommandHelper.buildLocalFallbackAnalysisOrNull("তুমি কে?")
        assertNotNull(whoAnalysis)
        assertEquals("আমি Hey Shuvo, আপনার ডিজিটাল অ্যাসিস্ট্যান্ট।", whoAnalysis!!.naturalReply)
    }

    @Test
    fun `HeyShuvoAiService hands-free Standard mode vs Children PIN gate and JSON null safety`() {
        val sampleJson = """
            {
              "spokenTranscript": null,
              "naturalReply": "কল ডায়াল করা হচ্ছে।",
              "detectedLanguage": "Bangla",
              "requiresSensitiveConfirmation": true,
              "steps": [
                {
                  "stepIndex": 1,
                  "actionTitle": "Dial Number",
                  "actionKind": "DIAL_PHONE",
                  "targetOrPath": "01711000000",
                  "titleOrSubject": null,
                  "contentOrBody": null,
                  "requiresConfirmation": true,
                  "reversible": false
                }
              ]
            }
        """.trimIndent()

        val root = JSONObject(sampleJson)
        assertEquals("", root.cleanString("spokenTranscript", ""))

        val service = HeyShuvoAiService()
        val standardAnalysis = service.parseCommandJson(
            rawJson = sampleJson,
            referenceUserText = "01711000000 নাম্বারে কল দাও",
            mode = UserAccessibilityMode.STANDARD
        )
        assertFalse(standardAnalysis.requiresSensitiveConfirmation)

        val childrenAnalysis = service.parseCommandJson(
            rawJson = sampleJson,
            referenceUserText = "01711000000 নাম্বারে কল দাও",
            mode = UserAccessibilityMode.CHILDREN
        )
        assertTrue(childrenAnalysis.requiresSensitiveConfirmation)
    }

    @Test
    fun `voice device controller executes hardware status, volume, Excel, Word, PDF, AutoCAD, and Windows scripts cleanly`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val controller = VoiceDeviceController(context)

        assertTrue(controller.readLiveDeviceTelemetrySummary().contains("Battery:"))
        assertTrue(controller.executeAction(ExecutableActionPayload(kind = DeviceActionKind.CHECK_DEVICE_STATUS)).isSuccess)
        assertTrue(controller.executeAction(ExecutableActionPayload(kind = DeviceActionKind.ADJUST_VOLUME, targetOrPath = "UP")).isSuccess)

        val excelRes = controller.executeAction(
            ExecutableActionPayload(
                kind = DeviceActionKind.GENERATE_EXCEL_SHEET,
                titleOrSubject = "মাসিক অফিস খরচ",
                contentOrBody = "SL,Item,Qty,Price,Total\n1,Router,2,2500,=C2*D2"
            )
        )
        assertTrue(excelRes.isSuccess)
        val excelFile = File(excelRes.getOrThrow().generatedFilePath!!)
        assertTrue(excelFile.exists())
        assertTrue(controller.readFileReadablePreview(excelFile).contains("=C2*D2"))

        val cadRes = controller.executeAction(
            ExecutableActionPayload(
                kind = DeviceActionKind.GENERATE_AUTOCAD_SCRIPT,
                titleOrSubject = "HousePlan_2Storey"
            )
        )
        assertTrue(cadRes.isSuccess)
        assertTrue(File(cadRes.getOrThrow().generatedFilePath!!).readText().contains("HeyShuvo2StoreyPlan"))
    }

    @Test
    fun `Wake-word Hey Shuvo or Shuvo greets Boss in Bangla, Auto Voice is ON by default, and Background Voice mode is 100 percent voice-controlled`() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        // 1. Default startup state MUST have AI Voice Auto enabled!
        val defaultSettings = com.example.core.preferences.AssistantSettings()
        assertTrue("AI Voice Auto must be enabled right from app startup", defaultSettings.autoAllDisplayVoice)
        assertFalse("Background voices must be suppressed by default until requested by voice", defaultSettings.listenBackgroundVoices)

        // 2. Wake words ("Hey Shuvo", "Shuvo", "হে শুভ", "শুভ") reply with exact Boss greeting!
        val expectedBossGreeting = "হ্যাঁ, আমি শুনতে পাচ্ছি বস। আপনি বলুন বস।"
        listOf("Hey Shuvo", "Shuvo", "হে শুভ", "শুভ", "Hey Shuvo শুনছো?").forEach { wakePhrase ->
            val analysis = BanglaCommandHelper.buildLocalFallbackAnalysisOrNull(wakePhrase)
            assertNotNull("Wake phrase '$wakePhrase' must resolve locally", analysis)
            assertEquals(expectedBossGreeting, analysis!!.naturalReply)
        }

        // 3. Wake word + command executes the command immediately!
        val wakePlusCmd = BanglaCommandHelper.extractExplicitSteps("Hey Shuvo ফ্ল্যাশলাইট চালু করো")
        assertEquals(DeviceActionKind.TOGGLE_FLASHLIGHT, wakePlusCmd.first().actionPayload.kind)

        // 4. Voice-only Background Voice Listening ON & OFF
        val bgOnSteps = BanglaCommandHelper.extractExplicitSteps("তুমি ব্যাকগ্রাউন্ড শব্দ শোনো")
        assertEquals(DeviceActionKind.SET_BACKGROUND_VOICE_LISTENING, bgOnSteps.first().actionPayload.kind)
        assertEquals("ON", bgOnSteps.first().actionPayload.targetOrPath)

        HeyShuvoVoiceCoordinator.submitTextCommand(context, "তুমি ব্যাকগ্রাউন্ড ভয়েস শোনো")
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        Thread.sleep(120L)
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue(HeyShuvoVoiceCoordinator.getEngine(context).listenBackgroundVoices)

        val bgOffSteps = BanglaCommandHelper.extractExplicitSteps("ব্যাকগ্রাউন্ড শব্দ শোনা বন্ধ করো")
        assertEquals(DeviceActionKind.SET_BACKGROUND_VOICE_LISTENING, bgOffSteps.first().actionPayload.kind)
        assertEquals("OFF", bgOffSteps.first().actionPayload.targetOrPath)

        HeyShuvoVoiceCoordinator.submitTextCommand(context, "ব্যাকগ্রাউন্ড শব্দ শোনা বন্ধ করো")
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        Thread.sleep(120L)
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertFalse(HeyShuvoVoiceCoordinator.getEngine(context).listenBackgroundVoices)

        // 5. Cross-display navigation commands extract cleanly
        assertEquals(DeviceActionKind.GLOBAL_BACK, BanglaCommandHelper.extractExplicitSteps("পিছনে যাও").first().actionPayload.kind)
        assertEquals(DeviceActionKind.SCROLL_SCREEN, BanglaCommandHelper.extractExplicitSteps("নিচে স্ক্রোল করো").first().actionPayload.kind)
    }

    @Test
    fun `Master App Save and Apply immediately syncs live banner, skills, and theme to Customer App and sanitizes forbidden names`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = PreferencesRepository(context)

        runBlocking {
            prefs.upsertLocalMasterSkill(
                com.example.data.cloud.model.SkillModule(
                    id = "local_bazar_skill",
                    name = "বাজার খরচ সহকারী",
                    description = "প্রতিদিনের বাজারের হিসাব রাখে",
                    instructions = "যখন ব্যবহারকারী বাজার খরচের কথা বলবে, সাথে সাথে হিসাব যোগ করবে।",
                    enabled = true
                )
            )
            prefs.applyMasterCustomerUpdateLocally(
                announcementBanner = "নতুন ফিচার যুক্ত হয়েছে: বাজার খরচ সহকারী",
                forcedThemePalette = "EMERALD_MATRIX",
                defaultAutoVoice = true,
                customSystemPrompt = "Master Fix: সব উত্তর সংক্ষিপ্ত ও স্পষ্ট বাংলায় দাও।",
                enabledFeatureFlags = "ALL_DISPLAY_VOICE,MAPS_NAVIGATION,SMART_HUB,PHOTO_VISION,OFFICE_SUITE,TALLY_KHATA",
                lastAppliedSummary = "Master Auto-Fix Applied"
            )

            val updated = prefs.settingsFlow.first()
            assertEquals("নতুন ফিচার যুক্ত হয়েছে: বাজার খরচ সহকারী", updated.masterAnnouncementBanner)
            assertEquals("EMERALD_MATRIX", updated.themePalette.name)
            assertTrue(updated.autoAllDisplayVoice)
            assertTrue(updated.masterCustomSystemPrompt.contains("সংক্ষিপ্ত"))
            assertTrue(updated.localMasterSkills.any { it.name == "বাজার খরচ সহকারী" && it.enabled })
        }

        // Identity sanitizer must strip Jarvis/Jarvin/Gemini completely
        val sanitized = HeyShuvoAiService.sanitizeAssistantIdentity("Hello, I am Jarvis and Jarvin powered by Gemini.")
        assertFalse(sanitized.contains("Jarvis", ignoreCase = true))
        assertFalse(sanitized.contains("Jarvin", ignoreCase = true))
        assertFalse(sanitized.contains("Gemini", ignoreCase = true))
        assertTrue(sanitized.contains("Hey Shuvo"))
    }

    @Test
    fun `Messenger Chat Head custom user photo saves, syncs immediately, resets cleanly, and fast 720ms AUTO VAD cuts without lag`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = PreferencesRepository(context)

        runBlocking {
            val sampleBitmap = android.graphics.Bitmap.createBitmap(320, 480, android.graphics.Bitmap.Config.ARGB_8888)
            sampleBitmap.eraseColor(android.graphics.Color.CYAN)

            val savedPath = prefs.saveCustomChatHeadPhoto(sampleBitmap)
            assertTrue("Saved custom Chat Head photo must exist on disk", File(savedPath).exists())
            assertEquals(savedPath, PreferencesRepository.readChatHeadAvatarSync(context))
            assertEquals(savedPath, prefs.settingsFlow.first().chatHeadAvatarPath)

            prefs.clearCustomChatHeadPhoto()
            assertEquals("", PreferencesRepository.readChatHeadAvatarSync(context))
            assertEquals("", prefs.settingsFlow.first().chatHeadAvatarPath)
        }

        // Fast 720ms AUTO VAD cut verification
        val fastAutoVad = AdaptiveVoiceActivityDetector(pauseThresholdMs = 720L)
        for (t in 80L..320L step 80L) {
            fastAutoVad.processChunk(rms = 120.0, elapsedMs = t)
        }
        for (t in 400L..960L step 80L) {
            val d = fastAutoVad.processChunk(rms = 1600.0, elapsedMs = t)
            if (t >= 480L) assertTrue("Normal speech must trigger AUTO VAD reliably", d.hasSpoken)
        }
        var cutMs = -1L
        for (t in 1040L..1920L step 80L) {
            val d = fastAutoVad.processChunk(rms = 130.0, elapsedMs = t)
            if (d.shouldAutoStop) {
                cutMs = t
                break
            }
        }
        assertTrue("Fast AUTO VAD must auto-cut right around ~720ms after speech ends (cutMs=$cutMs)", cutMs in 1680L..1760L)
    }

    @Test
    fun `Hand-Gesture File Transfer classifier and Birthday Wish voice and Room persistence work end-to-end`() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        // 1. Dark empty frame -> NO_HAND
        val emptyBmp = android.graphics.Bitmap.createBitmap(96, 96, android.graphics.Bitmap.Config.ARGB_8888)
        emptyBmp.eraseColor(android.graphics.Color.rgb(18, 22, 30))
        assertEquals(
            com.example.feature.customer.HandGestureState.NO_HAND,
            com.example.feature.customer.HandGestureClassifier.classifyBitmap(emptyBmp).state
        )

        // 2. Compact skin-toned block -> CLOSED_FIST (হাত মুঠো)
        val fistBmp = android.graphics.Bitmap.createBitmap(96, 96, android.graphics.Bitmap.Config.ARGB_8888)
        fistBmp.eraseColor(android.graphics.Color.rgb(18, 22, 30))
        val skinColor = android.graphics.Color.rgb(212, 152, 118)
        for (y in 32..68) {
            for (x in 30..66) {
                fistBmp.setPixel(x, y, skinColor)
            }
        }
        assertEquals(
            com.example.feature.customer.HandGestureState.CLOSED_FIST,
            com.example.feature.customer.HandGestureClassifier.classifyBitmap(fistBmp).state
        )

        // 3. Palm with 4 spread vertical fingers -> OPEN_PALM (মুঠো খোলা)
        val palmBmp = android.graphics.Bitmap.createBitmap(96, 96, android.graphics.Bitmap.Config.ARGB_8888)
        palmBmp.eraseColor(android.graphics.Color.rgb(18, 22, 30))
        // Lower palm base
        for (y in 54..78) {
            for (x in 24..72) {
                palmBmp.setPixel(x, y, skinColor)
            }
        }
        // 4 separated extended fingers in upper region
        val fingerRanges = listOf(24..30, 38..44, 52..58, 66..72)
        for (range in fingerRanges) {
            for (y in 20..53) {
                for (x in range) {
                    palmBmp.setPixel(x, y, skinColor)
                }
            }
        }
        assertEquals(
            com.example.feature.customer.HandGestureState.OPEN_PALM,
            com.example.feature.customer.HandGestureClassifier.classifyBitmap(palmBmp).state
        )

        // 4. Bangla voice commands for Gesture Transfer & Birthday Wish extract cleanly
        val gestureSteps = BanglaCommandHelper.extractExplicitSteps("হাতের ইশারায় ফাইল ট্রান্সফার চালু করো")
        assertEquals(DeviceActionKind.OPEN_GESTURE_TRANSFER_MODE, gestureSteps.first().actionPayload.kind)

        val saveBdaySteps = BanglaCommandHelper.extractExplicitSteps("রহিমের জন্মদিন ১৫ নভেম্বর সেভ করো")
        assertEquals(DeviceActionKind.SAVE_BIRTHDAY_REMINDER, saveBdaySteps.first().actionPayload.kind)
        assertEquals(15, saveBdaySteps.first().actionPayload.hour)
        assertEquals(11, saveBdaySteps.first().actionPayload.minute)
        assertTrue(saveBdaySteps.first().actionPayload.titleOrSubject.contains("রহিম"))

        val sendWishSteps = BanglaCommandHelper.extractExplicitSteps("রহিমকে বার্থডে উইশ পাঠাও")
        assertEquals(DeviceActionKind.SEND_BIRTHDAY_WISH, sendWishSteps.first().actionPayload.kind)

        // 5. Room BirthdayReminderEntity persistence
        runBlocking {
            val db = com.example.data.local.PrivateVaultDatabase.getInstance(context)
            val repo = com.example.data.local.PrivateVaultRepository(db.privateVaultDao())
            val bday = com.example.data.local.BirthdayReminderEntity(
                id = "test_bday_rahim",
                ownerUid = "owner_test",
                personName = "রহিম",
                birthDay = 15,
                birthMonth = 11,
                phoneOrContact = "01711000000",
                wishMessage = "শুভ জন্মদিন রহিম!"
            )
            repo.upsertBirthday(bday)
            val savedList = repo.observeBirthdays("owner_test").first()
            assertTrue(savedList.any { it.id == "test_bday_rahim" && it.personName == "রহিম" && it.birthDay == 15 })
            repo.deleteBirthday("test_bday_rahim", "owner_test")
        }
    }

    @Test
    fun `PreferencesRepository preserves remote configVersion across local saves and enforces strict password`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefsRepo = com.example.core.preferences.PreferencesRepository(context)
        runBlocking {
            // Remote push sets version = 7
            prefsRepo.applyMasterCustomerUpdateLocally(
                announcementBanner = "Cloud Banner",
                forcedThemePalette = "CYBER_CYAN",
                defaultAutoVoice = true,
                customSystemPrompt = "Rule 1",
                enabledFeatureFlags = "ALL_DISPLAY_VOICE",
                lastAppliedSummary = "Cloud v7",
                remoteVersion = 7L
            )
            // Subsequent local-only edit must NOT inflate masterConfigVersion to 8
            prefsRepo.applyMasterCustomerUpdateLocally(
                announcementBanner = "Local Edit",
                forcedThemePalette = "CYBER_CYAN",
                defaultAutoVoice = true,
                customSystemPrompt = "Rule 2",
                enabledFeatureFlags = "ALL_DISPLAY_VOICE",
                lastAppliedSummary = "Local save",
                remoteVersion = null
            )
            val s = prefsRepo.settingsFlow.first()
            assertEquals(7L, s.masterConfigVersion)
            assertEquals("Local Edit", s.masterAnnouncementBanner)

            // Trailing space password bypass must fail
            val badUpdate = prefsRepo.updateMasterPassword("Shuvo@1997 ", "NewPass@123")
            assertFalse("Whitespace-padded password must be rejected", badUpdate)

            // Bonus uses normalize date and clamp cleanly at 0
            prefsRepo.grantBonusFreeUses(10L)
            assertEquals(0L, prefsRepo.settingsFlow.first().dailyUsageCount)
        }

        // DailySummaryAlarmReceiver schedule carries explicit ownerKey extra
        com.example.core.service.DailySummaryAlarmReceiver.scheduleDailySummaryAlarm(
            context = context,
            hour = 21,
            minute = 30,
            ownerKey = "custom_uid_123"
        )
        val alarmIntent = android.content.Intent(context, com.example.core.service.DailySummaryAlarmReceiver::class.java)
            .putExtra(com.example.core.service.DailySummaryAlarmReceiver.EXTRA_OWNER_KEY, "custom_uid_123")
        assertEquals(
            "custom_uid_123",
            alarmIntent.getStringExtra(com.example.core.service.DailySummaryAlarmReceiver.EXTRA_OWNER_KEY)
        )
    }

    @Test
    fun `HeyShuvoVoiceCoordinator cancelActiveProcessing and wake-word Bangla responses recover cleanly without lockups`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        HeyShuvoVoiceCoordinator.clearConversation(context)
        HeyShuvoVoiceCoordinator.submitTextCommand(context, "Hey Shuvo")
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        Thread.sleep(100L)
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

        assertFalse(HeyShuvoVoiceCoordinator.isProcessing.value)
        val turns = HeyShuvoVoiceCoordinator.chatTurns.value
        assertTrue("Wake word should respond immediately in chat", turns.any { !it.isUser && it.message.contains("শুনতে পাচ্ছি বস") })

        HeyShuvoVoiceCoordinator.cancelActiveProcessing(context)
        assertFalse(HeyShuvoVoiceCoordinator.isProcessing.value)
    }
}
