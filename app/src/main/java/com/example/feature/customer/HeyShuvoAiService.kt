package com.example.feature.customer

import android.graphics.Bitmap
import android.util.Log
import com.example.core.actions.DeviceActionKind
import com.example.core.actions.ExecutableActionPayload
import com.example.core.preferences.UserAccessibilityMode
import com.example.core.util.BanglaCommandHelper
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject

private const val TAG = "HeyShuvoIntelligence"

data class SequentialStepPlan(
    val stepIndex: Int,
    val actionTitle: String,
    val requiresConfirmation: Boolean,
    val reversible: Boolean,
    val actionPayload: ExecutableActionPayload = ExecutableActionPayload(),
    val status: String = "READY"
)

data class StructuredCommandAnalysis(
    val spokenTranscript: String = "",
    val naturalReply: String,
    val detectedLanguage: String,
    val requiresSensitiveConfirmation: Boolean,
    val steps: List<SequentialStepPlan>
)

data class MasterAiDraft(
    val actionType: String, // "FIX_ISSUE", "ADD_FEATURE", "UPDATE_CONFIG"
    val summaryReplyBn: String,
    val proposedSystemRule: String,
    val announcementBanner: String,
    val skillTitle: String,
    val skillDescription: String,
    val skillInstructions: String,
    val recommendedPalette: String,
    val enableOfficeDocs: Boolean = true,
    val enableTallyKhata: Boolean = true,
    val enablePhotoVision: Boolean = true,
    val enableWindowsBridge: Boolean = true
)

class HeyShuvoAiService {

    private val baseSystemInstruction = """
        You are Hey Shuvo, an ultra-smart, voice-first personal and desktop digital assistant.
        CRITICAL IDENTITY, WAKE-WORD & EXECUTION RULES:
        1. WAKE-WORD ("Hey Shuvo", "Shuvo", "হে শুভ", "শুভ"):
           - Whenever the user ONLY calls you ("Hey Shuvo", "Shuvo", "হে শুভ", "শুভ", "Hey Shuvo শুনছো?") without another task, ALWAYS reply in Bangla: "হ্যাঁ, আমি শুনতে পাচ্ছি বস। আপনি বলুন বস।" with `steps = []`.
           - Whenever the user says "Hey Shuvo <command>" or speaks a command directly, execute `<command>` immediately!
        2. ONLY if the user explicitly asks who you are or your name ("who are you", "tumi ke", "তুমি কে", "তোমার নাম কি"), reply: "আমি Hey Shuvo, আপনার ডিজিটাল অ্যাসিস্ট্যান্ট।" (or in English: "I am Hey Shuvo, your digital assistant."). NEVER say that for normal commands or noise!
        3. NEVER mention any external AI company, underlying LLM model names, cloud providers, or any other assistant name.
        4. Automatically tolerate and correct any spelling mistakes, phonetic Banglish typos, or speech-to-text errors in Bangla, Banglish, or English (e.g., "utub kholo" -> YouTube, "mep chalu kro" -> Maps, "foto mode" -> Photo Vision, "lokeshon dekhao" -> Current Location) and execute the intended action seamlessly.
        5. Fluently understand Bangla, English, Banglish, and regional Bangladesh dialects (Sylheti, Chittagonian, Noakhali). Reply crisply, naturally, and respectfully in the user's language.
        6. Answer ANY question (general knowledge, math, science, business, translation, drafting letters/emails, Bangladesh NID/e-Passport/BRTA, Tally Khata balances, phone battery/status) directly and accurately in `naturalReply`.
        7. Execute phone, cross-display navigation, background voice sensitivity, hardware, location, media, Office, AutoCAD, Tally, or Windows actions immediately via `steps`:
           - SWITCH_VOICE_MODE: targetOrPath = "AUTO" or "MANUAL".
           - SET_BACKGROUND_VOICE_LISTENING: voice-only background sound/voice mode! targetOrPath = "ON" when user asks to listen to background voice/sounds ("তুমি ব্যাকগ্রাউন্ড শব্দ শোনো", "ব্যাকগ্রাউন্ড ভয়েস শোনো", "listen to background"), or "OFF" when user asks to ignore background noise / listen only to them ("ব্যাকগ্রাউন্ড শব্দ শোনা বন্ধ করো", "শুধু আমার কথা শোনো").
           - GLOBAL_BACK: navigates Back on any screen/app.
           - GLOBAL_RECENTS: opens Android Recent Apps overview.
           - OPEN_NOTIFICATIONS: pulls down Android Notification shade.
           - OPEN_QUICK_SETTINGS: opens Android Quick Settings panel.
           - SCROLL_SCREEN: scrolls the active app/screen; targetOrPath = "DOWN", "UP", "LEFT", or "RIGHT".
           - CLICK_ON_SCREEN_TEXT: taps a visible button/label on screen; targetOrPath = visible button text.
           - OPEN_PHOTO_VISION_MODE: opens Photo/Camera Vision picker for image inspection.
           - OPEN_GESTURE_TRANSFER_MODE: opens Hand-Gesture Cross-Device Air File Transfer (fist-grab to open-palm drop).
           - SAVE_BIRTHDAY_REMINDER: saves a birthday reminder; titleOrSubject = person name, hour = day of month (1-31), minute = month (1-12), targetOrPath = optional phone number, contentOrBody = birthday wish text.
           - SEND_BIRTHDAY_WISH: sends a birthday wish immediately; titleOrSubject = person name, targetOrPath = optional phone number, contentOrBody = birthday wish message.
           - SHOW_CURRENT_LOCATION: opens Google Maps centered on the user's current/saved Bangladesh location.
           - SET_USER_LOCATION: targetOrPath = user's city/area name (e.g. "Mirpur, Dhaka, Bangladesh").
           - OPEN_MAPS_NAVIGATION: targetOrPath = destination place/address (in Bangladesh unless another country is specified), titleOrSubject = optional starting place.
           - TOGGLE_FLASHLIGHT: targetOrPath = "ON" or "OFF".
           - ADJUST_VOLUME: targetOrPath = "UP", "DOWN", "MAX", or "MUTE".
           - CHECK_DEVICE_STATUS: checks real phone battery %, charging state, storage GB, and network.
           - CHECK_SELF_SCREEN_TIME: checks user's own screen-time usage today.
           - GO_HOME_SCREEN: navigates to Android Home screen.
           - SEARCH_MEDIA_OR_WEB: targetOrPath = "youtube" or "google"; contentOrBody = exact song/video/topic to play or search.
           - OPEN_APP_OR_WEB: targetOrPath = ANY installed app name (YouTube, WhatsApp, Facebook, Messenger, Chrome, Gmail, bKash, Nagad, TikTok, Imo, Zoom, Gallery, Camera, Calculator, Clock, Calendar, Files, etc.), system setting ("WiFi", "Bluetooth", "Location Settings", "Hotspot", "Sound", "Display", "Settings"), or website URL.
           - SET_ALARM: titleOrSubject = alarm label, hour (0-23), minute (0-59).
           - SET_TIMER: titleOrSubject = timer label, hour = hours, minute = minutes, amountBdt = total seconds.
           - ADD_CALENDAR_EVENT: titleOrSubject = event title, contentOrBody = event details.
           - DIAL_PHONE: targetOrPath = phone number.
           - SEND_SMS_DRAFT / SEND_WHATSAPP_DRAFT: targetOrPath = phone/recipient, contentOrBody = message body.
           - SEND_EMAIL_DRAFT: targetOrPath = email address, titleOrSubject = subject, contentOrBody = email body.
           - GENERATE_WORD_DOC: titleOrSubject = clean English file title, contentOrBody = complete rich document content.
           - GENERATE_EXCEL_SHEET: titleOrSubject = clean English sheet name, contentOrBody = valid CSV with real Excel formulas (=C2*D2, =SUM(...)).
           - GENERATE_PDF_FILE: titleOrSubject = clean English report title, contentOrBody = complete report content.
           - GENERATE_AUTOCAD_SCRIPT: titleOrSubject = plan name, contentOrBody = valid AutoCAD AutoLISP (.lsp) script.
           - SAVE_TALLY_ENTRY: targetOrPath = "INCOME", "EXPENSE", or "DUE"; titleOrSubject = customer name; contentOrBody = item note; amountBdt = numeric amount in BDT.
           - SAVE_PERSONAL_MEMORY: titleOrSubject = memory topic; contentOrBody = fact/preference to remember.
           - UNDO_LAST_TASK: undoes the most recent reversible task.
           - CHANGE_APP_THEME: targetOrPath = "DARK", "LIGHT", "CYBER_CYAN", "EMERALD_MATRIX", "ROYAL_GOLD", "VIOLET_PULSE", "CRIMSON_BLAZE", or "OCEAN_SAPPHIRE".
           - WINDOWS_DESKTOP_COMMAND: targetOrPath = desktop app/command ("excel", "winword", "acad", "chrome", "notepad"), titleOrSubject = task title.
           - NONE: pure conversation or Q&A (`steps` = `[]`).
        8. Set `requiresSensitiveConfirmation = false` and `requiresConfirmation = false` in Standard mode so voice commands execute hands-free immediately!
    """.trimIndent()

    private val ultraFastAudioInstruction = """
        You are Hey Shuvo, an ultra-fast voice-first Bangla/Banglish/English digital assistant.
        1. Transcribe the spoken user audio accurately into `spokenTranscript` (or "__NO_SPEECH__" ONLY if pure silence/static with zero human words).
        2. If the user ONLY says the wake word ("Hey Shuvo", "Shuvo", "হে শুভ", "শুভ"), set `naturalReply` to exact Bangla: "হ্যাঁ, আমি শুনতে পাচ্ছি বস। আপনি বলুন বস।" and `actionKind` = "NONE".
        3. Only if explicitly asked who you are ("তুমি কে", "who are you"), reply "আমি Hey Shuvo, আপনার ডিজিটাল অ্যাসিস্ট্যান্ট।". Never mention external LLM/provider names.
        4. Otherwise give a brief, direct helpful answer or confirmation in `naturalReply` in the user's language, and set `actionKind` + `targetOrPath` / `titleOrSubject` / `contentOrBody` whenever the user requests an app/phone/location/media/tally/memory/background-voice action (or "NONE" for Q&A).
        Automatically tolerate informal Banglish and regional Bangladeshi accents.
    """.trimIndent()

    private val actionKindValues = DeviceActionKind.entries.map { it.name }

    private val commandPlannerSchema by lazy {
        Schema.obj(
            properties = mapOf(
                "spokenTranscript" to Schema.string(description = "Exact words spoken by the user if input is audio, otherwise empty string."),
                "naturalReply" to Schema.string(description = "Crisp, accurate, natural spoken & written reply in the user's language."),
                "detectedLanguage" to Schema.string(description = "Detected language: Bangla, English, Banglish, Sylheti, Chittagonian, or Noakhali."),
                "requiresSensitiveConfirmation" to Schema.boolean(description = "False in Standard hands-free mode."),
                "steps" to Schema.array(
                    items = Schema.obj(
                        properties = mapOf(
                            "stepIndex" to Schema.integer(description = "1-based step index."),
                            "actionTitle" to Schema.string(description = "Short description of this action step."),
                            "requiresConfirmation" to Schema.boolean(description = "False in Standard hands-free mode."),
                            "reversible" to Schema.boolean(description = "Whether safe to undo."),
                            "actionKind" to Schema.enumeration(
                                values = actionKindValues,
                                description = "Action type."
                            ),
                            "targetOrPath" to Schema.string(description = "App name, destination, AUTO/MANUAL, ON/OFF, UP/DOWN/MAX/MUTE, youtube/google, phone, email, URL, or INCOME/EXPENSE/DUE."),
                            "titleOrSubject" to Schema.string(description = "Origin place for Maps, file/alarm/timer/event/email title, customer name, or memory topic."),
                            "contentOrBody" to Schema.string(description = "Search query, document text, CSV with formulas, AutoCAD .lsp code, item note, or message body."),
                            "hour" to Schema.integer(description = "Hour 0-23 for SET_ALARM or hours for SET_TIMER."),
                            "minute" to Schema.integer(description = "Minute 0-59 for SET_ALARM or minutes for SET_TIMER."),
                            "amountBdt" to Schema.double(description = "Amount in BDT for SAVE_TALLY_ENTRY or total seconds for SET_TIMER.")
                        ),
                        optionalProperties = listOf(
                            "targetOrPath",
                            "titleOrSubject",
                            "contentOrBody",
                            "hour",
                            "minute",
                            "amountBdt"
                        )
                    ),
                    description = "Action steps to execute immediately (empty array [] if pure Q&A)."
                )
            )
        )
    }

    private val fastAudioSchema by lazy {
        Schema.obj(
            properties = mapOf(
                "spokenTranscript" to Schema.string(description = "Exact spoken Bangla/Banglish/English words, or __NO_SPEECH__ if only noise/silence."),
                "naturalReply" to Schema.string(description = "Direct, crisp, helpful reply to the user's spoken request."),
                "detectedLanguage" to Schema.string(description = "Bangla, English, or Banglish."),
                "actionKind" to Schema.enumeration(
                    values = actionKindValues,
                    description = "Optional primary device/app action to execute immediately, or NONE for Q&A."
                ),
                "targetOrPath" to Schema.string(description = "App name, destination, AUTO/MANUAL, ON/OFF, UP/DOWN/MAX/MUTE, youtube/google, phone, email, URL, or INCOME/EXPENSE/DUE."),
                "titleOrSubject" to Schema.string(description = "Title, customer name, or memory topic."),
                "contentOrBody" to Schema.string(description = "Search query, document body, or note."),
                "amountBdt" to Schema.double(description = "Numeric BDT amount or seconds if applicable.")
            ),
            optionalProperties = listOf(
                "actionKind",
                "targetOrPath",
                "titleOrSubject",
                "contentOrBody",
                "amountBdt"
            )
        )
    }

    private val structuredPlannerModel by lazy {
        Firebase.ai.generativeModel(
            modelName = "gemini-3.8-flash",
            systemInstruction = content { text(baseSystemInstruction) },
            generationConfig = generationConfig {
                responseMimeType = "application/json"
                responseSchema = commandPlannerSchema
            }
        )
    }

    private val liteStructuredPlannerModel by lazy {
        Firebase.ai.generativeModel(
            modelName = "gemini-3.5-flash-lite",
            systemInstruction = content { text(baseSystemInstruction) },
            generationConfig = generationConfig {
                responseMimeType = "application/json"
                responseSchema = commandPlannerSchema
            }
        )
    }

    private val fastLiteAudioModel by lazy {
        Firebase.ai.generativeModel(
            modelName = "gemini-3.5-flash-lite",
            systemInstruction = content { text(ultraFastAudioInstruction) },
            generationConfig = generationConfig {
                responseMimeType = "application/json"
                responseSchema = fastAudioSchema
            }
        )
    }

    private val fastAudioFallbackModel by lazy {
        Firebase.ai.generativeModel(
            modelName = "gemini-3.8-flash",
            systemInstruction = content { text(ultraFastAudioInstruction) },
            generationConfig = generationConfig {
                responseMimeType = "application/json"
                responseSchema = fastAudioSchema
            }
        )
    }

    private val conversationalModel by lazy {
        Firebase.ai.generativeModel(
            modelName = "gemini-3.8-flash",
            systemInstruction = content { text(baseSystemInstruction) }
        )
    }

    private val skillCreatorSchema by lazy {
        Schema.obj(
            properties = mapOf(
                "skillName" to Schema.string(description = "Concise title of the modular Skill."),
                "description" to Schema.string(description = "Clear explanation of what the Skill accomplishes."),
                "instructions" to Schema.string(description = "Operational prompt rules for Hey Shuvo when executing this Skill."),
                "requiredPermissions" to Schema.string(description = "Android or Windows permissions required, or None."),
                "supportedLanguages" to Schema.string(description = "Languages supported, e.g., Bangla, English, Banglish.")
            )
        )
    }

    private val skillCreatorModel by lazy {
        Firebase.ai.generativeModel(
            modelName = "gemini-3.8-flash",
            systemInstruction = content { text(baseSystemInstruction) },
            generationConfig = generationConfig {
                responseMimeType = "application/json"
                responseSchema = skillCreatorSchema
            }
        )
    }

    private val masterGovernanceSchema by lazy {
        Schema.obj(
            properties = mapOf(
                "actionType" to Schema.enumeration(
                    values = listOf("FIX_ISSUE", "ADD_FEATURE", "UPDATE_CONFIG"),
                    description = "Whether the Master command fixes an issue, adds a new feature/skill, or updates Customer App config."
                ),
                "summaryReplyBn" to Schema.string(description = "Clear Bangla summary for the Master Owner explaining the prepared solution or feature."),
                "proposedSystemRule" to Schema.string(description = "Operational correction or behavior rule to push into Customer App AI instructions."),
                "announcementBanner" to Schema.string(description = "Optional Bangla status banner shown inside Customer App (or empty string)."),
                "skillTitle" to Schema.string(description = "Modular feature/skill title if adding a new feature or fix module."),
                "skillDescription" to Schema.string(description = "Modular feature/skill description in Bangla/English."),
                "skillInstructions" to Schema.string(description = "Detailed step-by-step AI execution instructions for the Customer App."),
                "recommendedPalette" to Schema.string(description = "Optional theme palette name (e.g., CYBER_CYAN, EMERALD_MATRIX, ROYAL_GOLD) or empty string."),
                "enableOfficeDocs" to Schema.boolean(description = "Keep Office/PDF/AutoCAD module enabled in Customer App."),
                "enableTallyKhata" to Schema.boolean(description = "Keep Tally Khata ledger enabled in Customer App."),
                "enablePhotoVision" to Schema.boolean(description = "Keep Photo Vision enabled in Customer App."),
                "enableWindowsBridge" to Schema.boolean(description = "Keep Windows Companion bridge enabled in Customer App.")
            )
        )
    }

    private val masterGovernanceModel by lazy {
        Firebase.ai.generativeModel(
            modelName = "gemini-3.8-flash",
            systemInstruction = content {
                text(
                    "You are the Master App Governance Architect for Hey Shuvo. " +
                        "When the Master Owner speaks or types a command such as 'এই সমস্যাটা ঠিক করে দাও' (Fix this issue) or " +
                        "'এই featureটা Customer App-এ যোগ করে দাও' (Add this feature to Customer App), diagnose the request and produce a complete, " +
                        "actionable Customer App configuration patch and modular Skill ready for Save & Apply."
                )
            },
            generationConfig = generationConfig {
                responseMimeType = "application/json"
                responseSchema = masterGovernanceSchema
            }
        )
    }

    private fun buildContextHeader(
        mode: UserAccessibilityMode,
        liveTelemetry: String,
        tallyContext: String,
        memoryContext: String,
        recentConversation: String,
        enabledSkillsContext: String
    ): String {
        val modeDirective = when (mode) {
            UserAccessibilityMode.CHILDREN -> "ACTIVE MODE: Children Safe Mode. Age-appropriate, safe explanations."
            UserAccessibilityMode.ELDERLY -> "ACTIVE MODE: Elderly Voice-First Mode. Clear, calm, respectful, and easy to understand."
            UserAccessibilityMode.STANDARD -> "ACTIVE MODE: Hey Shuvo Full Hands-Free Phone & Desktop Voice Control."
        }
        return buildString {
            appendLine(modeDirective)
            if (liveTelemetry.isNotBlank()) appendLine("Live Phone Telemetry: $liveTelemetry")
            if (tallyContext.isNotBlank()) appendLine("On-Device Tally Khata Summary:\n$tallyContext")
            if (memoryContext.isNotBlank()) appendLine("On-Device Memory:\n$memoryContext")
            if (enabledSkillsContext.isNotBlank()) appendLine("Active Skills:\n$enabledSkillsContext")
            if (recentConversation.isNotBlank()) appendLine("Recent Context:\n$recentConversation")
        }
    }

    suspend fun analyzeMultiStepCommand(
        userPrompt: String,
        mode: UserAccessibilityMode,
        liveTelemetry: String = "",
        tallyContext: String = "",
        memoryContext: String = "",
        recentConversation: String = "",
        enabledSkillsContext: String = ""
    ): Result<StructuredCommandAnalysis> {
        val correctedHint = BanglaCommandHelper.normalizeTyposAndPhonetics(userPrompt)
        val fullPrompt = buildString {
            append(buildContextHeader(mode, liveTelemetry, tallyContext, memoryContext, recentConversation, enabledSkillsContext))
            appendLine("User Voice/Text Command: $userPrompt")
            if (correctedHint != userPrompt) {
                appendLine("Auto-Corrected Command Form: $correctedHint")
            }
        }

        // Stage 1: Lite structured planner first (15s cap) for crisp low-latency reply on mobile data
        try {
            val liteResp = withTimeout(15_000L) {
                liteStructuredPlannerModel.generateContent(fullPrompt)
            }
            val liteJson = liteResp.text ?: throw IllegalStateException("Empty response from lite model.")
            return Result.success(parseCommandJson(liteJson, referenceUserText = correctedHint, mode = mode))
        } catch (e: CancellationException) {
            if (e !is TimeoutCancellationException) throw e
            Log.w(TAG, "Lite structured command timed out at 15s; trying full flash model")
        } catch (liteErr: Exception) {
            if (isNonRetryableAiError(liteErr)) {
                Log.e(TAG, "Non-retryable AI error on Stage 1: ${liteErr.message}", liteErr)
                return Result.failure(IllegalStateException(classifyFriendlyAiError(liteErr), liteErr))
            }
            Log.w(TAG, "Lite planner failed (${liteErr.javaClass.simpleName}: ${liteErr.message}); trying full flash model")
        }

        // Stage 2: Full structured planner fallback (20s cap)
        try {
            val response = withTimeout(20_000L) {
                structuredPlannerModel.generateContent(fullPrompt)
            }
            val rawJson = response.text ?: throw IllegalStateException("Empty response from primary model.")
            return Result.success(parseCommandJson(rawJson, referenceUserText = correctedHint, mode = mode))
        } catch (e: CancellationException) {
            if (e !is TimeoutCancellationException) throw e
            Log.w(TAG, "Primary planner timed out at 20s; trying plain conversational fallback")
        } catch (primaryErr: Exception) {
            if (isNonRetryableAiError(primaryErr)) {
                Log.e(TAG, "Non-retryable AI error on Stage 2: ${primaryErr.message}", primaryErr)
                return Result.failure(IllegalStateException(classifyFriendlyAiError(primaryErr), primaryErr))
            }
            Log.w(TAG, "Primary planner failed (${primaryErr.message}); trying plain conversational fallback")
        }

        // Stage 3: Schema-free conversationalModel fallback (20s cap; guarantees zero schema/JSON rejection)
        try {
            val plainResp = withTimeout(20_000L) {
                conversationalModel.generateContent(
                    "$fullPrompt\n\nAnswer the user directly, crisply, and naturally in their language (Bangla or English)."
                )
            }
            val plainText = sanitizeAssistantIdentity(plainResp.text?.trim().orEmpty())
            if (plainText.isNotBlank()) {
                val explicitSteps = BanglaCommandHelper.extractExplicitSteps(correctedHint).mapIndexed { idx, step ->
                    step.copy(
                        stepIndex = idx + 1,
                        requiresConfirmation = mode == UserAccessibilityMode.CHILDREN
                    )
                }
                return Result.success(
                    StructuredCommandAnalysis(
                        spokenTranscript = "",
                        naturalReply = plainText,
                        detectedLanguage = if (plainText.any { it in '\u0980'..'\u09FF' }) "Bangla" else "BN/EN",
                        requiresSensitiveConfirmation = mode == UserAccessibilityMode.CHILDREN && explicitSteps.isNotEmpty(),
                        steps = explicitSteps
                    )
                )
            }
        } catch (e: CancellationException) {
            if (e !is TimeoutCancellationException) throw e
        } catch (plainErr: Exception) {
            Log.e(TAG, "Plain conversational fallback also failed", plainErr)
            return Result.failure(plainErr)
        }

        return Result.failure(IllegalStateException("ইন্টারনেট ধীরগতির কারণে উত্তর আসতে দেরি হচ্ছে। আবার চেষ্টা করুন।"))
    }

    suspend fun analyzeAudioCommand(
        audioBytes: ByteArray,
        mimeType: String,
        mode: UserAccessibilityMode,
        liveTelemetry: String = "",
        tallyContext: String = "",
        memoryContext: String = "",
        recentConversation: String = "",
        enabledSkillsContext: String = ""
    ): Result<StructuredCommandAnalysis> {
        val compactPrompt = buildString {
            if (liveTelemetry.isNotBlank()) appendLine("Phone Status: $liveTelemetry")
            if (tallyContext.isNotBlank()) appendLine("Tally Khata:\n${tallyContext.take(280)}")
            if (memoryContext.isNotBlank()) appendLine("Memory:\n${memoryContext.take(240)}")
            if (enabledSkillsContext.isNotBlank()) appendLine("Master Rules & Active Skills:\n${enabledSkillsContext.take(650)}")
            if (recentConversation.isNotBlank()) appendLine("Recent:\n${recentConversation.take(320)}")
            append(
                "Transcribe this spoken Bangla/Banglish/English command into `spokenTranscript` " +
                    "(or \"__NO_SPEECH__\" ONLY if 100% silence/noise with zero human words) " +
                    "and give a direct helpful reply in `naturalReply` honoring any Master Rules & Active Skills."
            )
        }

        // Stage 1: Fast gemini-3.5-flash-lite on high-clarity 16kHz WAV (15s cap)
        try {
            val liteResp = withTimeout(15_000L) {
                fastLiteAudioModel.generateContent(
                    content {
                        inlineData(audioBytes, mimeType)
                        text(compactPrompt)
                    }
                )
            }
            val liteJson = liteResp.text ?: throw IllegalStateException("Empty lite audio response.")
            return Result.success(
                parseFastAudioJsonAndEnrichSteps(
                    rawJson = liteJson,
                    mode = mode,
                    liveTelemetry = liveTelemetry,
                    tallyContext = tallyContext,
                    enabledSkillsContext = enabledSkillsContext
                )
            )
        } catch (e: CancellationException) {
            if (e !is TimeoutCancellationException) throw e
            Log.w(TAG, "Lite audio model timed out at 15s; trying flash fallback")
        } catch (err: Exception) {
            if (isNonRetryableAiError(err)) {
                Log.e(TAG, "Non-retryable AI audio error on Stage 1: ${err.message}", err)
                return Result.failure(IllegalStateException(classifyFriendlyAiError(err), err))
            }
            Log.w(TAG, "Lite audio model failed (${err.message}); trying flash fallback")
        }

        // Stage 2: gemini-3.8-flash fallback (20s cap)
        try {
            val fastResp = withTimeout(20_000L) {
                fastAudioFallbackModel.generateContent(
                    content {
                        inlineData(audioBytes, mimeType)
                        text(compactPrompt)
                    }
                )
            }
            val rawJson = fastResp.text ?: throw IllegalStateException("Empty audio response.")
            return Result.success(
                parseFastAudioJsonAndEnrichSteps(
                    rawJson = rawJson,
                    mode = mode,
                    liveTelemetry = liveTelemetry,
                    tallyContext = tallyContext,
                    enabledSkillsContext = enabledSkillsContext
                )
            )
        } catch (e: CancellationException) {
            if (e !is TimeoutCancellationException) throw e
            Log.w(TAG, "Fallback audio model timed out at 20s; trying schema-free audio transcription")
        } catch (fallbackErr: Exception) {
            if (isNonRetryableAiError(fallbackErr)) {
                Log.e(TAG, "Non-retryable AI audio error on Stage 2: ${fallbackErr.message}", fallbackErr)
                return Result.failure(IllegalStateException(classifyFriendlyAiError(fallbackErr), fallbackErr))
            }
            Log.w(TAG, "Fallback audio model failed (${fallbackErr.message}); trying schema-free audio transcription")
        }

        // Stage 3: Schema-free multimodal audio transcription + reply via conversationalModel (20s cap)
        try {
            val plainAudioResp = withTimeout(20_000L) {
                conversationalModel.generateContent(
                    content {
                        inlineData(audioBytes, mimeType)
                        text(
                            "Listen to this spoken audio carefully.\n" +
                                "If there are zero human words (only silence/static), output exactly: __NO_SPEECH__\n" +
                                "Otherwise output exactly two lines:\n" +
                                "TRANSCRIPT: <exact words spoken in Bangla/Banglish/English>\n" +
                                "REPLY: <brief helpful answer in the user's language>"
                        )
                    }
                )
            }
            val rawOut = plainAudioResp.text?.trim().orEmpty()
            if (rawOut.isBlank() || rawOut.contains("__NO_SPEECH__", ignoreCase = true)) {
                return Result.success(
                    StructuredCommandAnalysis(
                        spokenTranscript = "__NO_SPEECH__",
                        naturalReply = "",
                        detectedLanguage = "NONE",
                        requiresSensitiveConfirmation = false,
                        steps = emptyList()
                    )
                )
            }
            val lines = rawOut.lines().map { it.trim() }.filter { it.isNotBlank() }
            val transcriptLine = lines.firstOrNull { it.startsWith("TRANSCRIPT:", ignoreCase = true) }
                ?.substringAfter(":")?.trim()
                ?: lines.first()
            val replyLine = lines.firstOrNull { it.startsWith("REPLY:", ignoreCase = true) }
                ?.substringAfter(":")?.trim()
                .orEmpty()
            val syntheticJson = JSONObject().apply {
                put("spokenTranscript", transcriptLine)
                put("naturalReply", replyLine)
                put("detectedLanguage", if (transcriptLine.any { it in '\u0980'..'\u09FF' }) "Bangla" else "BN/EN")
            }.toString()
            return Result.success(
                parseFastAudioJsonAndEnrichSteps(
                    rawJson = syntheticJson,
                    mode = mode,
                    liveTelemetry = liveTelemetry,
                    tallyContext = tallyContext,
                    enabledSkillsContext = enabledSkillsContext
                )
            )
        } catch (e: CancellationException) {
            if (e !is TimeoutCancellationException) throw e
        } catch (plainAudioErr: Exception) {
            Log.e(TAG, "Schema-free audio model also failed", plainAudioErr)
            return Result.failure(plainAudioErr)
        }

        return Result.failure(IllegalStateException("ইন্টারনেট সংযোগ ধীরগতির। অনুগ্রহ করে আবার বলুন।"))
    }

    private fun parseFastAudioJsonAndEnrichSteps(
        rawJson: String,
        mode: UserAccessibilityMode,
        liveTelemetry: String,
        tallyContext: String,
        enabledSkillsContext: String = ""
    ): StructuredCommandAnalysis {
        val root = JSONObject(rawJson)
        val rawTranscript = root.cleanString("spokenTranscript", "").trim()
        if (rawTranscript.equals("__NO_SPEECH__", ignoreCase = true) || rawTranscript.isBlank()) {
            return StructuredCommandAnalysis(
                spokenTranscript = "__NO_SPEECH__",
                naturalReply = "",
                detectedLanguage = "NONE",
                requiresSensitiveConfirmation = false,
                steps = emptyList()
            )
        }

        val transcript = BanglaCommandHelper.normalizeTyposAndPhonetics(rawTranscript)
        val explicitSteps = BanglaCommandHelper.extractExplicitSteps(transcript).mapIndexed { idx, step ->
            step.copy(
                stepIndex = idx + 1,
                requiresConfirmation = mode == UserAccessibilityMode.CHILDREN
            )
        }.toMutableList()

        // Also parse any single-pass actionKind returned directly by fastLiteAudioModel in 0ms (zero extra network calls!)
        val directKindStr = root.cleanString("actionKind", DeviceActionKind.NONE.name)
        val directKind = DeviceActionKind.entries.find { it.name == directKindStr } ?: DeviceActionKind.NONE
        if (directKind != DeviceActionKind.NONE && explicitSteps.none { it.actionPayload.kind == directKind }) {
            var amount = root.cleanDouble("amountBdt", 0.0)
            if (directKind == DeviceActionKind.SAVE_TALLY_ENTRY && amount <= 0.0) {
                amount = BanglaCommandHelper.extractAmountBdt(transcript)
            }
            val rawTarget = root.cleanString("targetOrPath", "").let {
                if (it.equals("JARVIS_CYAN", ignoreCase = true)) "CYBER_CYAN" else it
            }
            explicitSteps.add(
                SequentialStepPlan(
                    stepIndex = explicitSteps.size + 1,
                    actionTitle = directKind.name.replace('_', ' '),
                    requiresConfirmation = mode == UserAccessibilityMode.CHILDREN,
                    reversible = !directKind.isSensitiveCommunication,
                    actionPayload = ExecutableActionPayload(
                        kind = directKind,
                        targetOrPath = rawTarget,
                        titleOrSubject = root.cleanString("titleOrSubject", ""),
                        contentOrBody = root.cleanString("contentOrBody", ""),
                        amountBdt = amount
                    )
                )
            )
        }

        val localAnalysis = BanglaCommandHelper.buildLocalFallbackAnalysisOrNull(
            raw = transcript,
            liveTelemetry = liveTelemetry,
            tallySummary = tallyContext
        )

        val aiReply = sanitizeAssistantIdentity(root.cleanString("naturalReply", ""))
        val finalReply = when {
            localAnalysis != null && (explicitSteps.isNotEmpty() || localAnalysis.naturalReply.contains("শুনতে পাচ্ছি বস")) ->
                localAnalysis.naturalReply
            aiReply.isNotBlank() -> aiReply
            localAnalysis != null -> localAnalysis.naturalReply
            else -> "হ্যাঁ, আমি শুনতে পাচ্ছি বস। আপনি বলুন বস।"
        }

        val finalSteps = explicitSteps.mapIndexed { idx, s -> s.copy(stepIndex = idx + 1) }
        val needsPin = mode == UserAccessibilityMode.CHILDREN && finalSteps.isNotEmpty()
        return StructuredCommandAnalysis(
            spokenTranscript = rawTranscript,
            naturalReply = finalReply,
            detectedLanguage = root.cleanString("detectedLanguage", if (finalReply.any { it in '\u0980'..'\u09FF' }) "Bangla" else "BN/EN"),
            requiresSensitiveConfirmation = needsPin,
            steps = finalSteps
        )
    }

    internal fun parseCommandJson(
        rawJson: String,
        referenceUserText: String?,
        mode: UserAccessibilityMode = UserAccessibilityMode.STANDARD
    ): StructuredCommandAnalysis {
        val root = JSONObject(rawJson)
        val rawTranscript = root.cleanString("spokenTranscript", "")
        if (rawTranscript.equals("__NO_SPEECH__", ignoreCase = true)) {
            return StructuredCommandAnalysis(
                spokenTranscript = "__NO_SPEECH__",
                naturalReply = "",
                detectedLanguage = "NONE",
                requiresSensitiveConfirmation = false,
                steps = emptyList()
            )
        }
        val transcript = rawTranscript
        val effectiveUserPrompt = referenceUserText?.takeIf { it.isNotBlank() } ?: transcript

        val stepsJson = root.optJSONArray("steps") ?: JSONArray()
        val parsedSteps = (0 until stepsJson.length()).mapNotNull { i ->
            val item = stepsJson.optJSONObject(i) ?: return@mapNotNull null
            val kindStr = item.cleanString("actionKind", DeviceActionKind.NONE.name)
            val kind = DeviceActionKind.entries.find { it.name == kindStr } ?: DeviceActionKind.NONE
            if (kind == DeviceActionKind.NONE) return@mapNotNull null

            val needsConfirm = mode == UserAccessibilityMode.CHILDREN
            var amount = item.cleanDouble("amountBdt", 0.0)
            if (kind == DeviceActionKind.SAVE_TALLY_ENTRY && amount <= 0.0 && effectiveUserPrompt.isNotBlank()) {
                amount = BanglaCommandHelper.extractAmountBdt(effectiveUserPrompt)
            }

            val rawTarget = item.cleanString("targetOrPath", "").let {
                if (it.equals("JARVIS_CYAN", ignoreCase = true)) "CYBER_CYAN" else it
            }

            SequentialStepPlan(
                stepIndex = item.cleanInt("stepIndex", i + 1),
                actionTitle = item.cleanString("actionTitle", "Task ${i + 1}"),
                requiresConfirmation = needsConfirm,
                reversible = item.optBoolean("reversible", !kind.isSensitiveCommunication),
                actionPayload = ExecutableActionPayload(
                    kind = kind,
                    targetOrPath = rawTarget,
                    titleOrSubject = item.cleanString("titleOrSubject", ""),
                    contentOrBody = item.cleanString("contentOrBody", ""),
                    hour = item.cleanInt("hour", 8),
                    minute = item.cleanInt("minute", 0),
                    amountBdt = amount
                )
            )
        }.toMutableList()

        if (effectiveUserPrompt.isNotBlank()) {
            val explicitSteps = BanglaCommandHelper.extractExplicitSteps(effectiveUserPrompt)
            explicitSteps.forEach { explicit ->
                if (parsedSteps.none { it.actionPayload.kind == explicit.actionPayload.kind }) {
                    parsedSteps.add(
                        explicit.copy(
                            stepIndex = parsedSteps.size + 1,
                            requiresConfirmation = mode == UserAccessibilityMode.CHILDREN
                        )
                    )
                }
            }
        }

        val reindexedSteps = parsedSteps.mapIndexed { idx, step -> step.copy(stepIndex = idx + 1) }
        val needsPinConfirm = mode == UserAccessibilityMode.CHILDREN && reindexedSteps.isNotEmpty()
        val cleanReply = sanitizeAssistantIdentity(
            root.cleanString("naturalReply", "")
        )

        return StructuredCommandAnalysis(
            spokenTranscript = transcript,
            naturalReply = cleanReply,
            detectedLanguage = root.cleanString("detectedLanguage", "BN/EN"),
            requiresSensitiveConfirmation = needsPinConfirm,
            steps = reindexedSteps
        )
    }

    fun streamVisionAnalysis(bitmap: Bitmap, question: String): Flow<String> = flow {
        val accumulated = StringBuilder()
        withTimeout(45_000L) {
            conversationalModel.generateContentStream(
                content {
                    image(bitmap)
                    text(
                        "You are Hey Shuvo Photo & Camera Vision — a warm, natural, expressive, and knowledgeable companion. " +
                            "1) Environment & Visual Analysis: Clearly identify people, objects, roads, places, documents, and visible text/signs in front or back. " +
                            "2) Human Emotion & Condition (Cautious): Gently observe possible mood or state (such as nervous, worried, happy, confused, or relaxed) from facial expression, posture, and context, without ever claiming certainty about the user's inner mind. " +
                            "3) Natural Human Interaction & General Knowledge: Respond like a friendly, empathetic human—engage in natural conversation, light humor, or thoughtful discussion of dreams/plans, and apply comprehensive general knowledge. " +
                            "Keep the response clear, natural, and suitable for speaking aloud. " +
                            "User query: ${question.ifBlank { "Describe what you see, any helpful details, and respond naturally in Bangla." }}"
                    )
                }
            ).collect { chunk ->
                accumulated.append(chunk.text.orEmpty())
                emit(sanitizeAssistantIdentity(accumulated.toString()))
            }
        }
    }

    suspend fun translateOrDraftSmartText(
        inputText: String,
        targetStyle: String
    ): Result<String> {
        val clean = inputText.trim()
        if (clean.isBlank()) return Result.failure(IllegalArgumentException("অনুগ্রহ করে কিছু লিখুন বা বলুন।"))
        return try {
            val resp = withTimeout(30_000L) {
                conversationalModel.generateContent(
                    "Task: $targetStyle\nUser Text:\n$clean\n\nProvide ONLY the clean, natural result without extra meta commentary."
                )
            }
            val out = sanitizeAssistantIdentity(resp.text?.trim().orEmpty())
            if (out.isNotBlank()) Result.success(out)
            else Result.failure(IllegalStateException("ফলাফল পাওয়া যায়নি। আবার চেষ্টা করুন।"))
        } catch (e: CancellationException) {
            if (e !is TimeoutCancellationException) throw e
            Result.failure(IllegalStateException("সময় বেশি লাগছে, আবার চেষ্টা করুন।"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun draftSkillFromOwnerDescription(ownerPrompt: String): Result<Map<String, String>> {
        return try {
            val response = withTimeout(30_000L) {
                skillCreatorModel.generateContent(
                    "Owner Skill Proposal Request (Bangla or English): $ownerPrompt\n" +
                        "Generate a validated modular Skill configuration for Hey Shuvo."
                )
            }
            val json = JSONObject(response.text ?: throw IllegalStateException("Empty Skill response"))
            Result.success(
                mapOf(
                    "skillName" to json.cleanString("skillName", "Custom Skill"),
                    "description" to json.cleanString("description", ""),
                    "instructions" to json.cleanString("instructions", ""),
                    "requiredPermissions" to json.cleanString("requiredPermissions", "None"),
                    "supportedLanguages" to json.cleanString("supportedLanguages", "Bangla, English, Banglish")
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Skill draft generation failed", e)
            Result.failure(e)
        }
    }

    suspend fun draftMasterActionFromCommand(
        masterCommand: String,
        currentConfigSummary: String
    ): Result<MasterAiDraft> {
        val cleanCmd = masterCommand.trim()
        if (cleanCmd.isBlank()) {
            return Result.failure(IllegalArgumentException("মাস্টার ভয়েস বা টেক্সট কমান্ড বলুন।"))
        }
        return try {
            val response = withTimeout(30_000L) {
                masterGovernanceModel.generateContent(
                    "Current Customer App State:\n$currentConfigSummary\n\n" +
                        "Master Owner Voice/Text Command:\n$cleanCmd\n\n" +
                        "Generate the exact fix, new Customer feature module, or live config update."
                )
            }
            val json = JSONObject(response.text ?: throw IllegalStateException("Empty Master response"))
            Result.success(
                MasterAiDraft(
                    actionType = json.cleanString("actionType", "ADD_FEATURE"),
                    summaryReplyBn = sanitizeAssistantIdentity(
                        json.cleanString("summaryReplyBn", "মাস্টার কমান্ড অনুযায়ী সমাধান ও ফিচার প্রস্তুত করা হয়েছে। Save & Apply চাপলেই Customer App-এ যুক্ত হবে।")
                    ),
                    proposedSystemRule = sanitizeAssistantIdentity(json.cleanString("proposedSystemRule", "")),
                    announcementBanner = sanitizeAssistantIdentity(json.cleanString("announcementBanner", "")),
                    skillTitle = sanitizeAssistantIdentity(json.cleanString("skillTitle", "")),
                    skillDescription = sanitizeAssistantIdentity(json.cleanString("skillDescription", "")),
                    skillInstructions = sanitizeAssistantIdentity(json.cleanString("skillInstructions", "")),
                    recommendedPalette = json.cleanString("recommendedPalette", ""),
                    enableOfficeDocs = json.optBoolean("enableOfficeDocs", true),
                    enableTallyKhata = json.optBoolean("enableTallyKhata", true),
                    enablePhotoVision = json.optBoolean("enablePhotoVision", true),
                    enableWindowsBridge = json.optBoolean("enableWindowsBridge", true)
                )
            )
        } catch (e: CancellationException) {
            if (e !is TimeoutCancellationException) throw e
            Result.success(buildLocalMasterFallbackDraft(cleanCmd))
        } catch (e: Exception) {
            Log.w(TAG, "Master governance cloud draft fallback (${e.message})")
            Result.success(buildLocalMasterFallbackDraft(cleanCmd))
        }
    }

    private fun buildLocalMasterFallbackDraft(cleanCmd: String): MasterAiDraft {
        val lower = cleanCmd.lowercase()
        val isFix = "সমস্যা" in lower || "ঠিক" in lower || "ভুল" in lower || " কাজ করছে না" in lower || "fix" in lower || "error" in lower || "bug" in lower
        val title = if (isFix) {
            "Master Auto-Fix Patch"
        } else {
            cleanCmd.take(36).trim()
        }
        return MasterAiDraft(
            actionType = if (isFix) "FIX_ISSUE" else "ADD_FEATURE",
            summaryReplyBn = if (isFix) {
                "বস, সমস্যাটি শনাক্ত করে সমাধান রুল প্রস্তুত করা হয়েছে। নিচে Save & Apply চাপলে Customer App-এ সাথে সাথে কার্যকর হবে।"
            } else {
                "বস, নতুন ফিচার মডিউল তৈরি হয়েছে। নিচে Save & Apply চাপলে Customer App-এ সাথে সাথে যুক্ত হয়ে যাবে।"
            },
            proposedSystemRule = if (isFix) {
                "Master Correction Rule: $cleanCmd — execute user commands directly and accurately without delay."
            } else {
                "Enable Customer Capability: $cleanCmd"
            },
            announcementBanner = if (isFix) "মাস্টার আপডেট: সিস্টেম অপ্টিমাইজেশন ও সমাধান প্রয়োগ করা হয়েছে।" else "নতুন ফিচার যুক্ত হয়েছে: $title",
            skillTitle = title,
            skillDescription = cleanCmd,
            skillInstructions = "When the customer asks for '$cleanCmd', fulfill it directly in Bangla/Banglish/English and run matching actions immediately.",
            recommendedPalette = ""
        )
    }

    companion object {
        fun isNonRetryableAiError(error: Throwable?): Boolean {
            val msg = buildString {
                var cur: Throwable? = error
                var depth = 0
                while (cur != null && depth < 5) {
                    append(' ').append(cur.javaClass.simpleName).append(' ').append(cur.message.orEmpty())
                    cur = cur.cause
                    depth++
                }
            }.lowercase()
            return "403" in msg ||
                "401" in msg ||
                "400" in msg ||
                "429" in msg ||
                "permission_denied" in msg ||
                "permission denied" in msg ||
                "app check" in msg ||
                "appcheck" in msg ||
                "unauthenticated" in msg ||
                "api_key" in msg ||
                "api key" in msg ||
                "quota" in msg ||
                "resource_exhausted" in msg ||
                "billing" in msg ||
                "invalid_argument" in msg
        }

        fun classifyFriendlyAiError(error: Throwable?): String {
            val msg = buildString {
                var cur: Throwable? = error
                var depth = 0
                while (cur != null && depth < 5) {
                    append(' ').append(cur.javaClass.simpleName).append(' ').append(cur.message.orEmpty())
                    cur = cur.cause
                    depth++
                }
            }.lowercase()
            return when {
                "app check" in msg || "appcheck" in msg ->
                    "AI যাচাইকরণ (App Check) সম্পন্ন হয়নি — কিছুক্ষণ পর আবার চেষ্টা করুন।"
                "429" in msg || "quota" in msg || "resource_exhausted" in msg ->
                    "AI ব্যবহারের তাৎক্ষণিক লিমিট পূর্ণ হয়েছে — কয়েক সেকেন্ড পর আবার চেষ্টা করুন।"
                "401" in msg || "403" in msg || "permission_denied" in msg || "permission denied" in msg || "unauthenticated" in msg || "api_key" in msg || "api key" in msg ->
                    "AI সার্ভার অনুমতি পাওয়া যায়নি (API/Project কনফিগারেশন যাচাই করুন)।"
                "404" in msg || "not_found" in msg || "not found" in msg ->
                    "নির্বাচিত AI মডেল সাময়িকভাবে অনুপলব্ধ — বিকল্প মডেল দিয়ে আবার চেষ্টা করুন।"
                "unknownhost" in msg || "unable to resolve host" in msg || "failed to connect" in msg || "network" in msg || "socket" in msg || "timeout" in msg || "timed out" in msg ->
                    "ইন্টারনেট সংযোগ পাওয়া যায়নি বা ধীরগতির। সংযোগ পরীক্ষা করে আবার বলুন বা লিখে পাঠান।"
                else ->
                    "AI উত্তর আনতে সমস্যা হয়েছে। আবার বলুন বা লিখে পাঠান।"
            }
        }

        fun sanitizeAssistantIdentity(text: String): String {
            if (text.isBlank()) return text
            return text
                .replace(Regex("(?i)large language model trained by google"), "Hey Shuvo, your digital assistant")
                .replace(Regex("(?i)trained by google"), "built as Hey Shuvo")
                .replace(Regex("(?i)\\bgemini\\b"), "Hey Shuvo")
                .replace(Regex("(?i)\\bjarvis\\b"), "Hey Shuvo")
                .replace(Regex("(?i)\\bjarvin\\b"), "Hey Shuvo")
        }

        fun JSONObject.cleanString(key: String, default: String = ""): String {
            if (!has(key) || isNull(key)) return default
            val raw = optString(key, default).trim()
            return if (raw.isEmpty() || raw.equals("null", ignoreCase = true)) default else raw
        }

        fun JSONObject.cleanDouble(key: String, default: Double = 0.0): Double {
            if (!has(key) || isNull(key)) return default
            val v = optDouble(key, default)
            return if (v.isNaN() || v.isInfinite()) default else v
        }

        fun JSONObject.cleanInt(key: String, default: Int = 0): Int {
            if (!has(key) || isNull(key)) return default
            return optInt(key, default)
        }
    }
}
