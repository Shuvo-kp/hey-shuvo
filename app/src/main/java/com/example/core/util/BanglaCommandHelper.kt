package com.example.core.util

import com.example.core.actions.DeviceActionKind
import com.example.core.actions.ExecutableActionPayload
import com.example.core.actions.VoiceDeviceController
import com.example.feature.customer.SequentialStepPlan
import com.example.feature.customer.StructuredCommandAnalysis
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Handles Bangla/Banglish/English digit normalization, automatic spelling/typo correction,
 * deterministic multi-action extraction, instant zero-latency local command execution,
 * and accurate local fallback understanding.
 */
object BanglaCommandHelper {

    fun normalizeDigits(input: String): String {
        if (input.isEmpty()) return input
        val sb = StringBuilder(input.length)
        for (ch in input) {
            when (ch) {
                in '\u09E6'..'\u09EF' -> sb.append(('0'.code + (ch.code - '\u09E6'.code)).toChar())
                in '\u0966'..'\u096F' -> sb.append(('0'.code + (ch.code - '\u0966'.code)).toChar())
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    private val DIRECT_PHRASE_TYPO_MAP = listOf(
        // YouTube misspellings
        "ইউটুব" to "ইউটিউব",
        "ইউটিব" to "ইউটিউব",
        "ইওটিউব" to "ইউটিউব",
        "ইউটুবে" to "ইউটিউবে",
        // Location & Maps misspellings
        "লোকেসন" to "লোকেশন",
        "লকেশন" to "লোকেশন",
        "লোকেশান" to "লোকেশন",
        "মেপ" to "ম্যাপ",
        "মাপ খোলো" to "ম্যাপ খোলো",
        "মাপ চালু" to "ম্যাপ চালু",
        "নেভিগেসন" to "নেভিগেশন",
        "নাভিগেশন" to "নেভিগেশন",
        // Photo & Camera Vision misspellings
        "ফোটো" to "ফটো",
        "ফোট" to "ফটো",
        "কামেরা" to "ক্যামেরা",
        "কেমেরা" to "ক্যামেরা",
        // Auto & Manual Mode misspellings
        "মেনুয়াল" to "ম্যানুয়াল",
        "মেনুয়াল" to "ম্যানুয়াল",
        "মানুয়াল" to "ম্যানুয়াল",
        "মানুয়াল" to "ম্যানুয়াল",
        "অটো মট" to "অটো মোড",
        "অটো মুড" to "অটো মোড",
        "ম্যানুয়াল মুড" to "ম্যানুয়াল মোড",
        "ম্যানুয়াল মুড" to "ম্যানুয়াল মোড",
        // Hardware & Apps misspellings
        "ফ্লাসলাইট" to "ফ্ল্যাশলাইট",
        "ফ্লাশলাইট" to "ফ্ল্যাশলাইট",
        "ফ্লাশ লাইট" to "ফ্ল্যাশ লাইট",
        "টর্স" to "টর্চ",
        "ভলিয়ম" to "ভলিউম",
        "ভলিওম" to "ভলিউম",
        "ব্যাটারী" to "ব্যাটারি",
        "বেটারি" to "ব্যাটারি",
        "চারজ" to "চার্জ",
        "হোয়াটসআপ" to "হোয়াটসঅ্যাপ",
        "হোয়াটসআপ" to "হোয়াটসঅ্যাপ",
        "ফেসবूक" to "ফেসবুক",
        "ফেইসবুক" to "ফেসবুক",
        "সেটিংস" to "সেটিং"
    )

    private val TOKEN_TYPO_DICTIONARY = mapOf(
        // YouTube
        "utub" to "youtube",
        "utube" to "youtube",
        "yutub" to "youtube",
        "yutube" to "youtube",
        "youtub" to "youtube",
        "yotube" to "youtube",
        "yuotube" to "youtube",
        "youtbe" to "youtube",
        // Maps & Location
        "mep" to "map",
        "maap" to "map",
        "mpas" to "maps",
        "lokeshon" to "location",
        "lokeson" to "location",
        "locaton" to "location",
        "loaction" to "location",
        "locasion" to "location",
        "lokason" to "location",
        "navigaton" to "navigation",
        "nevigation" to "navigation",
        // Photo & Camera Vision
        "foto" to "photo",
        "poto" to "photo",
        "phooto" to "photo",
        "phto" to "photo",
        "camra" to "camera",
        "cemera" to "camera",
        "kamra" to "camera",
        "kamera" to "camera",
        "vison" to "vision",
        // Auto & Manual
        "ato" to "auto",
        "outo" to "auto",
        "autu" to "auto",
        "menual" to "manual",
        "manul" to "manual",
        "mannual" to "manual",
        "manaul" to "manual",
        "manuall" to "manual",
        // Hardware (Flashlight, Volume, Battery)
        "flas" to "flashlight",
        "flashlite" to "flashlight",
        "flshlight" to "flashlight",
        "torc" to "torch",
        "troch" to "torch",
        "volum" to "volume",
        "volium" to "volume",
        "valume" to "volume",
        "saond" to "sound",
        "saund" to "sound",
        "sond" to "sound",
        "betari" to "battery",
        "batery" to "battery",
        "batary" to "battery",
        "battary" to "battery",
        "chaj" to "charge",
        "charj" to "charge",
        "chrge" to "charge",
        // Apps & Office
        "watsapp" to "whatsapp",
        "whatsap" to "whatsapp",
        "wattsapp" to "whatsapp",
        "whstapp" to "whatsapp",
        "fesbuk" to "facebook",
        "facbook" to "facebook",
        "facebok" to "facebook",
        "fb" to "facebook",
        "mesenger" to "messenger",
        "massenger" to "messenger",
        "crome" to "chrome",
        "chorme" to "chrome",
        "krome" to "chrome",
        "seting" to "setting",
        "setings" to "settings",
        "seeting" to "setting",
        "blutooth" to "bluetooth",
        "blututh" to "bluetooth",
        "kalculator" to "calculator",
        "calculater" to "calculator",
        "exel" to "excel",
        "excell" to "excel",
        "axcel" to "excel",
        "otocad" to "autocad",
        "autocd" to "autocad",
        "tali" to "tally",
        "taly" to "tally",
        // Common Banglish action verbs with typos
        "kro" to "koro",
        "kroo" to "koro",
        "khlo" to "kholo",
        "opn" to "open",
        "opne" to "open",
        "strt" to "start",
        "chlu" to "chalu",
        "dekho" to "dekhao",
        "dkhao" to "dekhao"
    )

    private val FUZZY_CANONICAL_KEYWORDS = listOf(
        "youtube", "whatsapp", "facebook", "messenger", "instagram", "telegram",
        "location", "navigation", "flashlight", "battery", "bluetooth", "calculator",
        "calendar", "settings", "autocad", "camera", "manual", "volume", "chrome"
    )

    /**
     * Automatically corrects spelling mistakes & phonetic Banglish/Bangla/English typos
     * while preserving proper nouns, numbers, and valid words.
     */
    fun normalizeTyposAndPhonetics(raw: String): String {
        if (raw.isBlank()) return raw
        var stage = normalizeDigits(raw)
        for ((wrong, right) in DIRECT_PHRASE_TYPO_MAP) {
            if (wrong in stage) {
                stage = stage.replace(wrong, right)
            }
        }

        val tokenRegex = Regex("""[A-Za-z]+""")
        return tokenRegex.replace(stage) { match ->
            val word = match.value
            val lowerWord = word.lowercase(Locale.US)
            val directReplacement = TOKEN_TYPO_DICTIONARY[lowerWord]
            if (directReplacement != null) {
                directReplacement
            } else if (lowerWord.length >= 5 && lowerWord !in FUZZY_CANONICAL_KEYWORDS) {
                val maxDist = if (lowerWord.length >= 7) 2 else 1
                val best = FUZZY_CANONICAL_KEYWORDS
                    .map { target -> target to levenshteinDistance(lowerWord, target) }
                    .filter { (target, dist) ->
                        dist <= maxDist &&
                            target.first() == lowerWord.first() &&
                            kotlin.math.abs(target.length - lowerWord.length) <= maxDist
                    }
                    .minByOrNull { it.second }
                best?.first ?: word
            } else {
                word
            }
        }
    }

    internal fun levenshteinDistance(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        val prev = IntArray(b.length + 1) { it }
        val curr = IntArray(b.length + 1)
        for (i in a.indices) {
            curr[0] = i + 1
            for (j in b.indices) {
                val cost = if (a[i] == b[j]) 0 else 1
                curr[j + 1] = minOf(
                    curr[j] + 1,
                    prev[j + 1] + 1,
                    prev[j] + cost
                )
            }
            for (j in prev.indices) {
                prev[j] = curr[j]
            }
        }
        return prev[b.length]
    }

    fun parseDoubleOrDefault(raw: String, default: Double = 0.0): Double {
        val cleaned = normalizeDigits(raw)
            .replace(",", "")
            .replace("৳", "")
            .replace(Regex("(?i)(taka|tk|bdt|টাকা)"), "")
            .trim()
        return cleaned.toDoubleOrNull()?.takeIf { !it.isNaN() && !it.isInfinite() } ?: default
    }

    fun extractAmountBdt(raw: String): Double {
        val normalized = normalizeDigits(raw).replace(",", "")
        val currencyRegex = Regex("""(\d+(?:\.\d+)?)\s*(?:টাকা|taka|tk|bdt|৳)""", RegexOption.IGNORE_CASE)
        currencyRegex.find(normalized)?.groupValues?.getOrNull(1)?.toDoubleOrNull()?.let {
            if (it > 0.0) return it
        }
        val prefixCurrencyRegex = Regex("""(?:৳|tk\.?|bdt)\s*(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)
        prefixCurrencyRegex.find(normalized)?.groupValues?.getOrNull(1)?.toDoubleOrNull()?.let {
            if (it > 0.0) return it
        }
        val anyNum = Regex("""\b(\d+(?:\.\d+)?)\b""").findAll(normalized)
            .mapNotNull { it.groupValues.getOrNull(1)?.toDoubleOrNull() }
            .firstOrNull { it > 0.0 }
        if (anyNum != null) return anyNum

        val lower = normalized.lowercase()
        return when {
            "বারোশো" in lower || "বারো শ" in lower || "barosho" in lower -> 1200.0
            "পাঁচশো" in lower || "পাঁচশ" in lower || "pachsho" in lower -> 500.0
            "এক হাজার" in lower || "হাজার" in lower || "ek hajar" in lower -> 1000.0
            "দুই হাজার" in lower || "dui hajar" in lower -> 2000.0
            else -> 0.0
        }
    }

    private val WAKE_WORD_PREFIX_REGEX = Regex(
        """^(?:(?:hey|hei|hi|hello|ok|okay|oi|ay|o)\s+(?:shuvo|suvo|subho)|হে\s*শুভ|হেই\s*শুভ|এই\s*শুভ|ও\s*শুভ|হ্যালো\s*শুভ|শোনো\s*শুভ|শুভ|shuvo|suvo)[\s,!।.\-:]*""",
        RegexOption.IGNORE_CASE
    )

    /**
     * Strips leading "হে শুভ" / "Hey Shuvo" wake-word prefix cleanly and returns (hadWakeWord, remainingCommand).
     */
    fun stripWakeWordPrefix(raw: String): Pair<Boolean, String> {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return false to ""
        val match = WAKE_WORD_PREFIX_REGEX.find(trimmed)
        return if (match != null) {
            val rest = trimmed.substring(match.range.last + 1).trim()
            true to rest
        } else {
            false to trimmed
        }
    }

    /**
     * Returns true when a user command can be executed deterministically and instantaneously (<50ms)
     * without waiting on cloud network latency.
     */
    fun isInstantDirectActionOrFact(raw: String): Boolean {
        val (_, coreText) = stripWakeWordPrefix(raw)
        val trimmed = coreText.ifBlank { raw.trim() }
        if (trimmed.isBlank()) return false
        val corrected = normalizeTyposAndPhonetics(trimmed)
        val lower = corrected.lowercase()

        val openEndedPrompts = listOf(
            "essay", "paragraph", "poem", "story", "code", "explain", "why ", "how does",
            "translate", "অনুবাদ", "রচনা", "কবিতা", "গল্প", "ব্যাখ্যা করো", "কীভাবে কাজ করে", "পার্থক্য কী",
            "চিঠি লেখো", "দরখাস্ত লেখো", "application write"
        )
        if (openEndedPrompts.any { it in lower } && extractExplicitSteps(raw).isEmpty()) {
            return false
        }

        if (extractExplicitSteps(raw).isNotEmpty()) {
            return true
        }

        return buildLocalFallbackAnalysisOrNull(raw) != null && corrected.split(Regex("\\s+")).size <= 8
    }

    fun extractExplicitSteps(raw: String): List<SequentialStepPlan> {
        val (_, strippedCore) = stripWakeWordPrefix(raw)
        val rawTrimmed = strippedCore.trim()
        if (rawTrimmed.isBlank()) return emptyList()
        val trimmed = normalizeTyposAndPhonetics(rawTrimmed)
        val normalized = normalizeDigits(trimmed)
        val lower = normalized.lowercase()
        val steps = mutableListOf<SequentialStepPlan>()

        fun addStep(
            title: String,
            payload: ExecutableActionPayload,
            requiresConfirm: Boolean = false,
            reversible: Boolean = !payload.kind.isSensitiveCommunication
        ) {
            if (steps.none { it.actionPayload.kind == payload.kind }) {
                steps.add(
                    SequentialStepPlan(
                        stepIndex = steps.size + 1,
                        actionTitle = title,
                        requiresConfirmation = requiresConfirm,
                        reversible = reversible,
                        actionPayload = payload
                    )
                )
            }
        }

        // 0A. Voice Mode Switch: AUTO (All Display) vs MANUAL
        val mentionsAutoMode = "auto mode" in lower || "অটো মোড" in lower || "অটো ভয়েস" in lower ||
            "অটো ভয়েস" in lower || "all display" in lower || "অল ডিসপ্লে" in lower ||
            (lower == "auto" || lower == "অটো")
        val mentionsManualMode = "manual mode" in lower || "ম্যানুয়াল মোড" in lower || "ম্যানুয়াল মোড" in lower ||
            "manual voice" in lower || "ম্যানুয়াল ভয়েস" in lower || "ম্যানুয়াল ভয়েস" in lower ||
            (lower == "manual" || lower == "ম্যানুয়াল" || lower == "ম্যানুয়াল")
        if (mentionsManualMode || (mentionsAutoMode && ("off" in lower || "বন্ধ" in lower || "bondho" in lower))) {
            addStep(
                title = "Switch to Manual Voice Mode",
                payload = ExecutableActionPayload(
                    kind = DeviceActionKind.SWITCH_VOICE_MODE,
                    targetOrPath = "MANUAL"
                )
            )
        } else if (mentionsAutoMode) {
            addStep(
                title = "Switch to Auto All-Display Mode",
                payload = ExecutableActionPayload(
                    kind = DeviceActionKind.SWITCH_VOICE_MODE,
                    targetOrPath = "AUTO"
                )
            )
        }

        // 0B. Photo / Camera Vision Mode (Main Display & In-App)
        val asksPhotoVision = "photo mode" in lower || "ফটো মোড" in lower || "ছবি মোড" in lower ||
            "camera vision" in lower || "ক্যামেরা ভিশন" in lower || "ছবি দেখো" in lower ||
            "ছবি বিশ্লেষণ" in lower || "inspect photo" in lower || "vision mode" in lower ||
            (("photo" in lower || "ফটো" in lower || "ছবি" in lower) &&
                ("kholo" in lower || "chalu" in lower || "open" in lower || "দেখাও" in lower || "খোলো" in lower || "চালু" in lower))
        if (asksPhotoVision) {
            addStep(
                title = "Open Photo Vision Mode",
                payload = ExecutableActionPayload(
                    kind = DeviceActionKind.OPEN_PHOTO_VISION_MODE
                )
            )
        }

        // 0B-1. Hand-Gesture Cross-Device File Transfer ("হাতের ইশারায় ট্রান্সফার", "gesture transfer")
        val asksGestureTransfer = "হাতের ইশারা" in lower || "হাতের ইশারায়" in lower || "হাতের ইশারায়" in lower ||
            "হাত মুঠ" in lower || "হাত মুঠো" in lower || "মুঠো করে" in lower ||
            "gesture transfer" in lower || "hand transfer" in lower || "air transfer" in lower ||
            "hater ishara" in lower || "hater isharay" in lower ||
            (("জেসচার" in lower || "ইশারা" in lower || "gesture" in lower) &&
                ("ট্রান্সফার" in lower || "ফাইল" in lower || "শেয়ার" in lower || "শেয়ার" in lower || "transfer" in lower || "share" in lower))
        if (asksGestureTransfer) {
            addStep(
                title = "Open Hand-Gesture File Transfer",
                payload = ExecutableActionPayload(
                    kind = DeviceActionKind.OPEN_GESTURE_TRANSFER_MODE
                )
            )
        }

        // 0B-1B. Birthday Reminder & Birthday Wish ("জন্মদিন সেভ", "বার্থডে উইশ পাঠাও")
        val mentionsBirthday = "জন্মদিন" in lower || "বার্থডে" in lower || "birthday" in lower || "jonmodin" in lower || "bday" in lower
        if (mentionsBirthday) {
            val parsedDate = extractDayAndMonth(normalized)
            val asksSaveBirthday = parsedDate != null && (
                "সেভ" in lower || "রাখো" in lower || "যোগ" in lower || "রিমাইন্ডার" in lower ||
                    "মনে রাখো" in lower || "মনে রেখো" in lower || "save" in lower || "add" in lower || "reminder" in lower || "জন্মদিন" in lower
                ) && !("পাঠাও" in lower || "উইশ করো" in lower || "wish koro" in lower || "send wish" in lower)
            val asksSendWishNow = "উইশ পাঠাও" in lower || "উইশ করো" in lower || "শুভেচ্ছা পাঠাও" in lower ||
                "বার্থডে উইশ" in lower || "wish koro" in lower || "wish pathao" in lower ||
                "send birthday wish" in lower || "wish " in lower

            if (asksSaveBirthday) {
                val person = extractBirthdayPersonName(trimmed)
                val phone = Regex("""(\+?\d[\d\-\s]{6,14}\d)""").find(normalized)?.groupValues?.getOrNull(1)
                    ?.replace(Regex("[\\s\\-]"), "").orEmpty()
                val wishMsg = "শুভ জন্মদিন $person! আপনার আগামী দিনগুলো সুস্থ, সুন্দর ও আনন্দময় হোক।"
                addStep(
                    title = "Save Birthday Reminder: $person (${parsedDate.first}/${parsedDate.second})",
                    payload = ExecutableActionPayload(
                        kind = DeviceActionKind.SAVE_BIRTHDAY_REMINDER,
                        targetOrPath = phone,
                        titleOrSubject = person,
                        contentOrBody = wishMsg,
                        hour = parsedDate.first,
                        minute = parsedDate.second
                    )
                )
            } else if (asksSendWishNow) {
                val person = extractBirthdayPersonName(trimmed)
                val phone = Regex("""(\+?\d[\d\-\s]{6,14}\d)""").find(normalized)?.groupValues?.getOrNull(1)
                    ?.replace(Regex("[\\s\\-]"), "").orEmpty()
                val wishMsg = "শুভ জন্মদিন $person! আপনার প্রতিটি দিন আনন্দ ও সাফল্যে ভরে উঠুক।"
                addStep(
                    title = "Send Birthday Wish to $person",
                    payload = ExecutableActionPayload(
                        kind = DeviceActionKind.SEND_BIRTHDAY_WISH,
                        targetOrPath = phone,
                        titleOrSubject = person,
                        contentOrBody = wishMsg
                    )
                )
            }
        }

        // 0B-2. Voice-Only Background Voice / Sound Listening Control (NO UI toggle — 100% Voice Controlled!)
        val mentionsBgListening = "ব্যাকগ্রাউন্ড" in lower || "background" in lower ||
            "পাশের শব্দ" in lower || "পাশের কথা" in lower || "চারপাশের শব্দ" in lower || "চারপাশের কথা" in lower ||
            "বাইরের শব্দ" in lower || "বাইরের কথা" in lower || "আশেপাশের শব্দ" in lower || "আশেপাশের কথা" in lower ||
            "শুধু আমার কথা" in lower || "only my voice" in lower || "pasher kotha" in lower || "pasher shobdo" in lower
        if (mentionsBgListening && ("শব্দ" in lower || "ভয়েস" in lower || "ভয়েস" in lower || "কথা" in lower || "সাউন্ড" in lower ||
                "voice" in lower || "sound" in lower || "noise" in lower || "শোনো" in lower || "শুনো" in lower || "শোনা" in lower || "shono" in lower || "listen" in lower || "ignore" in lower)
        ) {
            val disableBg = "বন্ধ" in lower || "অফ" in lower || "না" in lower || "শুধু আমার" in lower ||
                "off" in lower || "stop" in lower || "ignore" in lower || "suppress" in lower ||
                "bondho" in lower || "bad dao" in lower || "only my" in lower
            addStep(
                title = if (disableBg) "Suppress Background Noise (Focus Voice)" else "Enable Background Voice Listening",
                payload = ExecutableActionPayload(
                    kind = DeviceActionKind.SET_BACKGROUND_VOICE_LISTENING,
                    targetOrPath = if (disableBg) "OFF" else "ON"
                )
            )
        }

        // 0B-3. Cross-Display System Navigation & Screen Control (Back, Recents, Notifications, Scroll, Tap Text)
        if (lower in setOf("পিছনে যাও", "পেছনে যাও", "ব্যাকে যাও", "ব্যাক করো", "ব্যাক যাও", "ফিরে যাও", "back", "go back", "back koro", "back jao", "pichone jao")) {
            addStep(
                title = "Navigate Back",
                payload = ExecutableActionPayload(kind = DeviceActionKind.GLOBAL_BACK)
            )
        } else if ("রিসেন্ট অ্যাপ" in lower || "recent apps" in lower || "সাম্প্রতিক অ্যাপ" in lower || "open recents" in lower) {
            addStep(
                title = "Open Recent Apps",
                payload = ExecutableActionPayload(kind = DeviceActionKind.GLOBAL_RECENTS)
            )
        } else if (("নোটিফিকেশন" in lower || "notification" in lower) &&
            ("খোলো" in lower || "দেখাও" in lower || "open" in lower || "show" in lower || "kholo" in lower || "dekhao" in lower)
        ) {
            addStep(
                title = "Open Notification Shade",
                payload = ExecutableActionPayload(kind = DeviceActionKind.OPEN_NOTIFICATIONS)
            )
        } else if ("কুইক সেটিং" in lower || "quick setting" in lower || "quick panel" in lower) {
            addStep(
                title = "Open Quick Settings",
                payload = ExecutableActionPayload(kind = DeviceActionKind.OPEN_QUICK_SETTINGS)
            )
        } else if ("স্ক্রোল" in lower || "scroll" in lower || lower in setOf("নিচে নামো", "নিচে যাও", "উপরে ওঠো", "উপরে যাও", "niche namo", "upore otho")) {
            val dir = when {
                "উপরে" in lower || "up" in lower || "upore" in lower -> "UP"
                "বামে" in lower || "left" in lower -> "LEFT"
                "ডানে" in lower || "right" in lower -> "RIGHT"
                else -> "DOWN"
            }
            addStep(
                title = "Scroll Screen ($dir)",
                payload = ExecutableActionPayload(
                    kind = DeviceActionKind.SCROLL_SCREEN,
                    targetOrPath = dir
                )
            )
        } else {
            val tapMatch = Regex(
                """(?:স্ক্রিনে|বাটনে|লেখায়|লেখায়)?\s*['"]?([\u0980-\u09FFA-Za-z0-9\s]{2,28}?)['"]?\s*(?:বাটনে\s*)?(?:চাপো|ট্যাপ করো|ক্লিক করো|প্রেস করো)""",
                RegexOption.IGNORE_CASE
            ).find(trimmed) ?: Regex(
                """(?:click on|tap on|press button)\s+['"]?([A-Za-z0-9\u0980-\u09FF\s]{2,28})['"]?""",
                RegexOption.IGNORE_CASE
            ).find(trimmed)
            val tapTarget = tapMatch?.groupValues?.getOrNull(1)?.trim().orEmpty()
            if (tapTarget.length >= 2 && ("স্ক্রিনে" in lower || "বাটনে" in lower || "ট্যাপ করো" in lower || "ক্লিক করো" in lower || "click on" in lower || "tap on" in lower)) {
                addStep(
                    title = "Tap '$tapTarget' on Screen",
                    payload = ExecutableActionPayload(
                        kind = DeviceActionKind.CLICK_ON_SCREEN_TEXT,
                        targetOrPath = tapTarget
                    )
                )
            }
        }

        // 0C. App Theme & Dark/Light Mode Voice Control (Bangla + Banglish + English)
        val mentionsThemeOrMode = !mentionsAutoMode && !mentionsManualMode && !asksPhotoVision && (
            "থিম" in lower || "theme" in lower || "ডার্ক মোড" in lower || "লাইট মোড" in lower ||
                "dark mode" in lower || "light mode" in lower || "নাইট মোড" in lower || "night mode" in lower ||
                "কালার মোড" in lower
            )
        if (mentionsThemeOrMode) {
            val themeCode = when {
                ("ডার্ক" in lower || "dark" in lower || "night" in lower || "নাইট" in lower) &&
                    ("বন্ধ" in lower || "অফ" in lower || "off" in lower || "bondho" in lower) -> "LIGHT"
                "লাইট" in lower || "সাদা" in lower || "উজ্জ্বল" in lower || "light" in lower || "daylight" in lower || "white" in lower -> "LIGHT"
                "সবুজ" in lower || "পান্না" in lower || "green" in lower || "emerald" in lower || "sobuj" in lower -> "EMERALD_MATRIX"
                "গোল্ড" in lower || "সোনালী" in lower || "হলুদ" in lower || "gold" in lower || "yellow" in lower -> "ROYAL_GOLD"
                "লাল" in lower || "রুবী" in lower || "red" in lower || "crimson" in lower || "lal" in lower -> "CRIMSON_BLAZE"
                "নীল" in lower || "সমুদ্র" in lower || "blue" in lower || "ocean" in lower || "nil" in lower -> "OCEAN_SAPPHIRE"
                "বেগুনি" in lower || "ভায়োলেট" in lower || "ভায়োলেট" in lower || "purple" in lower || "violet" in lower || "beguni" in lower -> "VIOLET_PULSE"
                "সায়ান" in lower || "সায়ান" in lower || "ডিফল্ট" in lower || "cyan" in lower || "default" in lower -> "CYBER_CYAN"
                "ডার্ক" in lower || "কালো" in lower || "dark" in lower || "night" in lower || "kalo" in lower -> "DARK"
                else -> null
            }
            if (themeCode != null) {
                addStep(
                    title = "Change App Theme ($themeCode)",
                    payload = ExecutableActionPayload(
                        kind = DeviceActionKind.CHANGE_APP_THEME,
                        targetOrPath = themeCode
                    )
                )
            }
        }

        // 1. Flashlight / Torch Hardware Control
        if (!mentionsThemeOrMode && (
                "flashlight" in lower || "flash light" in lower || "torch" in lower ||
                    "ফ্ল্যাশলাইট" in lower || "ফ্ল্যাশ লাইট" in lower || "টর্চ" in lower ||
                    ("লাইট" in lower && ("জ্বালা" in lower || "অন" in lower || "চালু" in lower || "বন্ধ" in lower || "নিভা" in lower || "নেভা" in lower || "অফ" in lower)) ||
                    ("light" in lower && ("jala" in lower || "jalao" in lower || "on" in lower || "chalu" in lower || "bondho" in lower || "off" in lower || "niva" in lower))
                )
        ) {
            val isOff = "off" in lower || "বন্ধ" in lower || "নিভা" in lower || "নেভা" in lower || "অফ" in lower || "bondho" in lower || "niva" in lower
            addStep(
                title = if (isOff) "Turn Off Flashlight" else "Turn On Flashlight",
                payload = ExecutableActionPayload(
                    kind = DeviceActionKind.TOGGLE_FLASHLIGHT,
                    targetOrPath = if (isOff) "OFF" else "ON"
                )
            )
        }

        // 2. Hardware Volume Control
        val asksVolume = "volume" in lower || "ভলিউম" in lower || "volium" in lower ||
            (("সাউন্ড" in lower || "sound" in lower || "awaj" in lower || "আওয়াজ" in lower || "আওয়াজ" in lower) &&
                ("বাড়া" in lower || "বাড়া" in lower || "কমা" in lower || "ফুল" in lower || "সাইলেন্ট" in lower || "মিউট" in lower ||
                    "bara" in lower || "barao" in lower || "koma" in lower || "komao" in lower || "full" in lower || "silent" in lower || "mute" in lower)) ||
            "ফোন সাইলেন্ট" in lower || "মিউট কর" in lower || "mute phone" in lower || "silent kor" in lower
        if (asksVolume) {
            val dir = when {
                "mute" in lower || "silent" in lower || "সাইলেন্ট" in lower || "মিউট" in lower -> "MUTE"
                "max" in lower || "full" in lower || "ফুল" in lower || "সর্বোচ্চ" in lower -> "MAX"
                "down" in lower || "lower" in lower || "decrease" in lower || "কমা" in lower || "কম" in lower || "koma" in lower -> "DOWN"
                else -> "UP"
            }
            addStep(
                title = "Adjust Phone Volume ($dir)",
                payload = ExecutableActionPayload(
                    kind = DeviceActionKind.ADJUST_VOLUME,
                    targetOrPath = dir
                )
            )
        }

        // 3. Battery / Storage / Device Status
        if ("battery" in lower || "ব্যাটারি" in lower || "চার্জ কত" in lower || "charge koto" in lower ||
            "charge" in lower && "koto" in lower || "storage" in lower || "স্টোরেজ কত" in lower ||
            "ফোনের চার্জ" in lower || "phone status" in lower || "ফোনের অবস্থা" in lower
        ) {
            addStep(
                title = "Check Live Phone Battery & Storage",
                payload = ExecutableActionPayload(kind = DeviceActionKind.CHECK_DEVICE_STATUS)
            )
        }

        // 4. Self Screen-Time Check
        if ("screen time" in lower || "screentime" in lower || "স্ক্রিন টাইম" in lower ||
            "কোন অ্যাপ কতক্ষণ" in lower || "কতক্ষণ চালিয়েছি" in lower
        ) {
            addStep(
                title = "Check Today's Screen Time",
                payload = ExecutableActionPayload(kind = DeviceActionKind.CHECK_SELF_SCREEN_TIME)
            )
        }

        // 5. Go Home Screen
        if ("home screen" in lower || "go home" in lower || "হোম স্ক্রিন" in lower ||
            "হোমে যাও" in lower || "home e jao" in lower || "main display jao" in lower
        ) {
            addStep(
                title = "Go to Phone Home Screen",
                payload = ExecutableActionPayload(kind = DeviceActionKind.GO_HOME_SCREEN)
            )
        }

        // 6. Undo Last Task
        if (lower == "undo" || "undo last" in lower || "আনডু করো" in lower ||
            "আগের কাজটি বাতিল" in lower || "আগের কাজ আনডু" in lower || "undo koro" in lower
        ) {
            addStep(
                title = "Undo Latest Reversible Task",
                payload = ExecutableActionPayload(kind = DeviceActionKind.UNDO_LAST_TASK)
            )
        }

        // 7A. Set User Home / Current Location ("আমার লোকেশন মিরপুর ঢাকা সেট করো", "set my location to Sylhet")
        val asksLocationSettings = "location setting" in lower || "লোকেশন সেটিং" in lower || "gps setting" in lower
        val setLocationMatch = if (!asksLocationSettings) {
            Regex(
                """(?i)(?:set\s+(?:my\s+)?location\s+(?:to\s+)?|my\s+location\s+is\s+|আমার\s+(?:বর্তমান\s+)?(?:লোকেশন|অবস্থান)\s+)(.+?)(?:\s*(?:সেট\s*করো|সেট\s*কর|রাখো|দাও|set\s*koro|set\s*kor))?$"""
            ).find(trimmed)
        } else null

        val candidateSetLoc = setLocationMatch?.groupValues?.getOrNull(1)?.trim().orEmpty()
        val isLocationQuestion = "কোথায়" in lower || "কোথায়" in lower || "দেখাও" in lower || "বলো" in lower ||
            "where" in lower || "kothay" in lower || "dekhao" in lower || "check" in lower || "খোলো" in lower || "kholo" in lower
        if (candidateSetLoc.length >= 2 && !isLocationQuestion && (
                "সেট" in lower || "set " in lower || "my location is" in lower || "আমার লোকেশন " in lower || "আমার অবস্থান " in lower
                )
        ) {
            addStep(
                title = "Set User Location ($candidateSetLoc)",
                payload = ExecutableActionPayload(
                    kind = DeviceActionKind.SET_USER_LOCATION,
                    targetOrPath = candidateSetLoc
                )
            )
        }

        // 7B. Show Current User Location on Map ("আমার লোকেশন দেখাও", "আমি কোথায়", "current location", "location dekhao")
        val asksCurrentLocation = !asksLocationSettings && steps.none { it.actionPayload.kind == DeviceActionKind.SET_USER_LOCATION } && (
            "current location" in lower || "my location" in lower || "where am i" in lower ||
                "আমার লোকেশন" in lower || "আমার অবস্থান" in lower || "আমি কোথায়" in lower || "আমি কোথায়" in lower ||
                "ami kothay" in lower || "amar location" in lower || "amr location" in lower ||
                (("location" in lower || "লোকেশন" in lower) &&
                    ("দেখাও" in lower || "কোথায়" in lower || "কোথায়" in lower || "dekhao" in lower || "kothay" in lower ||
                        "kholo" in lower || "খোলো" in lower || "চালু" in lower || "chalu" in lower || "open" in lower || "show" in lower)) ||
                lower == "location" || lower == "লোকেশন"
            )
        if (asksCurrentLocation) {
            addStep(
                title = "Show Current Location on Map",
                payload = ExecutableActionPayload(
                    kind = DeviceActionKind.SHOW_CURRENT_LOCATION
                )
            )
        }

        // 7C. Maps Navigation (Origin -> Destination OR Current Location -> Destination)
        val fromToBn = Regex("""(.+?)\s+থেকে\s+(.+?)(?:\s+(?:যাওয়ার|যাওয়ার|যাবো|যাব|ম্যাপ|নেভিগেশন|রাস্তা|রুট|দেখাও|চালু|খোলো).*)?$""").find(trimmed)
        val fromToEn = Regex("""(?i)(?:from\s+)?([A-Za-z\s]+?)\s+to\s+([A-Za-z\s]+?)(?:\s+(?:map|maps|navigation|route|directions).*)?$""").find(trimmed)

        val asksMapsKeywords = !asksCurrentLocation && !asksLocationSettings && (
            "map" in lower || "maps" in lower || "ম্যাপ" in lower || "নেভিগেশন" in lower || "navigation" in lower ||
                "navigate" in lower || "direction" in lower ||
                (("রাস্তা" in lower || "রুট" in lower || "route" in lower || "rasta" in lower) &&
                    ("দেখাও" in lower || "চালু" in lower || "open" in lower || "dekhao" in lower || "chalu" in lower || "jao" in lower)) ||
                (("নিয়ে চলো" in lower || "নিয়ে চলো" in lower || "যাবো" in lower || "যেতে চাই" in lower || "যাওয়ার রাস্তা" in lower || "যাওয়ার রাস্তা" in lower) &&
                    !("youtube" in lower || "ইউটিউব" in lower || "facebook" in lower || "whatsapp" in lower))
            )

        if (asksMapsKeywords) {
            var originPlace = ""
            var destPlace = ""

            if (fromToBn != null && ("ম্যাপ" in lower || "নেভিগেশন" in lower || "রাস্তা" in lower || "রুট" in lower || "চলো" in lower)) {
                originPlace = fromToBn.groupValues[1]
                    .replace(Regex("(?i)(hey shuvo|আমার|আমি|এখন)"), "")
                    .trim()
                destPlace = fromToBn.groupValues[2]
                    .replace(Regex("(?i)(ম্যাপে|ম্যাপ|নেভিগেশন|চালু|করো|খোলো|দেখাও|রাস্তা|রুট)"), "")
                    .trim()
            } else if (fromToEn != null && ("map" in lower || "navigation" in lower || "route" in lower || "navigate" in lower)) {
                originPlace = fromToEn.groupValues[1]
                    .replace(Regex("(?i)\\b(open|show|start|navigate|google|map|maps|route|from)\\b"), "")
                    .trim()
                destPlace = fromToEn.groupValues[2]
                    .replace(Regex("(?i)\\b(map|maps|navigation|route|please)\\b"), "")
                    .trim()
            } else {
                val strippedDest = trimmed
                    .replace(
                        Regex("(?i)\\b(hey|shuvo|tmi|tumi|amr|amar|amake|phone|open|show|start|navigate|to|google|maps?|navigation|route|directions|rasta|jao|jaw|kholo|khulo|chalu|koro|kor|dekhao|niye|cholo)\\b"),
                        " "
                    )
                    .replace(
                        Regex("(আমাকে|আমার|তুমি|ফোনে|গুগল|ম্যাপে|ম্যাপ|নেভিগেশন|রাস্তা|রুট|চালু|করো|কর|খোলো|খুলো|দেখাও|যাওয়ার|যাওয়ার|যেতে চাই|যাবো|নিয়ে চলো|নিয়ে চলো|চলো|যাও)"),
                        " "
                    )
                    .replace(Regex("\\s+"), " ")
                    .trim()
                destPlace = strippedDest
            }

            if (destPlace.isBlank()) {
                // User said "ম্যাপ খোলো" / "open map" with no specific city -> show user's real/selected Bangladesh location!
                addStep(
                    title = "Open Current Location Map",
                    payload = ExecutableActionPayload(
                        kind = DeviceActionKind.SHOW_CURRENT_LOCATION
                    )
                )
            } else {
                addStep(
                    title = if (originPlace.isNotBlank()) {
                        "Navigate: $originPlace → $destPlace"
                    } else {
                        "Open Google Maps Navigation ($destPlace)"
                    },
                    payload = ExecutableActionPayload(
                        kind = DeviceActionKind.OPEN_MAPS_NAVIGATION,
                        targetOrPath = destPlace,
                        titleOrSubject = originPlace
                    )
                )
            }
        }

        // 8. Microsoft Excel (.csv with real formulas)
        if ("excel" in lower || "এক্সেল" in lower || "spreadsheet" in lower) {
            val isOfficeExpense = "অফিস" in lower || "office" in lower || "খরচ" in lower || "expense" in lower || "khoroch" in lower
            val title = if (isOfficeExpense) "Monthly_Office_Expense_Sheet" else "HeyShuvo_Business_Sheet"
            val csv = if (isOfficeExpense) {
                "SL,Expense Category,Quantity,Unit Cost (BDT),Total (BDT)\n" +
                    "1,Office Rent & Maintenance,1,25000,=C2*D2\n" +
                    "2,High-Speed Internet & Utilities,1,6500,=C3*D3\n" +
                    "3,Stationery & Printing,4,850,=C4*D4\n" +
                    "4,Staff Conveyance & Transport,10,450,=C5*D5\n" +
                    "5,Tea & Guest Refreshments,1,3200,=C6*D6\n" +
                    ",,Grand Total,,=SUM(E2:E6)"
            } else {
                "SL,Item Description,Qty,Unit Price (BDT),Total (BDT)\n" +
                    "1,Product / Service A,5,1200,=C2*D2\n" +
                    "2,Product / Service B,3,850,=C3*D3\n" +
                    "3,Product / Service C,2,1500,=C4*D4\n" +
                    ",,Grand Total,,=SUM(E2:E4)"
            }
            addStep(
                title = "Generate Excel Formula Sheet ($title.csv)",
                payload = ExecutableActionPayload(
                    kind = DeviceActionKind.GENERATE_EXCEL_SHEET,
                    titleOrSubject = title,
                    contentOrBody = csv
                )
            )
        }

        // 9. AutoCAD 2-Storey Plan (.lsp)
        if ("autocad" in lower || "auto cad" in lower || "অটোক্যাড" in lower || ".lsp" in lower ||
            "বাড়ির প্ল্যান" in lower || "বাড়ির প্ল্যান" in lower || "barir plan" in lower || "floor plan" in lower
        ) {
            addStep(
                title = "Generate AutoCAD 2-Storey House Plan (.lsp)",
                payload = ExecutableActionPayload(
                    kind = DeviceActionKind.GENERATE_AUTOCAD_SCRIPT,
                    titleOrSubject = "TwoStorey_HousePlan_36x42",
                    contentOrBody = VoiceDeviceController.defaultTwoStoreyHouseAutoLisp()
                )
            )
        }

        // 10. Microsoft Word Document (.doc)
        if (Regex("""\bword\b""").containsMatchIn(lower) || "ms word" in lower || "ওয়ার্ড" in lower || "ওয়ার্ড" in lower || ".doc" in lower) {
            val isMeeting = "মিটিং" in lower || "meeting" in lower || "প্রজেক্ট" in lower || "project" in lower
            val docTitle = if (isMeeting) "Project_Meeting_Minutes" else "HeyShuvo_Official_Document"
            val docBody = if (isMeeting) {
                "প্রজেক্ট মিটিং কার্যবিবরণী (Project Meeting Document)\n" +
                    "তারিখ: ${SimpleDateFormat("dd MMM yyyy", Locale.US).format(Date())}\n\n" +
                    "১. আলোচ্য বিষয় (Agenda):\n" +
                    "   • প্রজেক্টের বর্তমান অগ্রগতি পর্যালোচনা ও সময়সূচি যাচাই।\n" +
                    "   • টিমের দায়িত্ব বণ্টন এবং কোয়ালিটি চেকিং।\n\n" +
                    "২. গৃহীত সিদ্ধান্তসমূহ (Key Decisions):\n" +
                    "   • নির্ধারিত সময়ের মধ্যে সকল মডিউল পরীক্ষা সম্পন্ন করা হবে।\n" +
                    "   • সাপ্তাহিক অগ্রগতি রিপোর্ট সংরক্ষণ করা হবে।\n\n" +
                    "৩. পরবর্তী করণীয় (Action Items):\n" +
                    "   • চূড়ান্ত ডেলিভারি চেকলিস্ট যাচাই ও অনুমোদন।"
            } else {
                "Prepared by Hey Shuvo Digital Assistant\n\nবিষয়: $trimmed\n\n১. সারসংক্ষেপ\n২. মূল বিবরণ ও পরিকল্পনা\n৩. পরবর্তী পদক্ষেপ"
            }
            addStep(
                title = "Generate Microsoft Word Document ($docTitle.doc)",
                payload = ExecutableActionPayload(
                    kind = DeviceActionKind.GENERATE_WORD_DOC,
                    titleOrSubject = docTitle,
                    contentOrBody = docBody
                )
            )
        }

        // 11. PDF Document (.pdf)
        if (Regex("""\bpdf\b""").containsMatchIn(lower) || "পিডিএফ" in lower) {
            val isMeeting = "মিটিং" in lower || "meeting" in lower || "প্রজেক্ট" in lower || "project" in lower
            val pdfTitle = if (isMeeting) "Project_Meeting_Summary_Report" else "HeyShuvo_Summary_Report"
            val pdfBody = if (isMeeting) {
                "Project Meeting Executive Report\n" +
                    "Date: ${SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.US).format(Date())}\n\n" +
                    "1. Project Milestone Status: On Track\n" +
                    "2. Key Deliverables: Word Documentation, Budget Sheet, and Quality Verification\n" +
                    "3. Risk & Action Plan: Complete end-to-end verification prior to rollout\n" +
                    "4. Prepared By: Hey Shuvo Digital Assistant"
            } else {
                "Official PDF Report\nGenerated by Hey Shuvo Digital Assistant\n\nRequest Summary:\n$trimmed"
            }
            addStep(
                title = "Generate PDF Report ($pdfTitle.pdf)",
                payload = ExecutableActionPayload(
                    kind = DeviceActionKind.GENERATE_PDF_FILE,
                    titleOrSubject = pdfTitle,
                    contentOrBody = pdfBody
                )
            )
        }

        // 12. Windows Laptop Bridge
        if (("ল্যাপটপ" in lower || "পিসি" in lower || "কম্পিউটার" in lower || "laptop" in lower || "windows pc" in lower || "computer" in lower) &&
            ("খোলো" in lower || "চালু" in lower || "open" in lower || "কন্ট্রোল" in lower || "run" in lower || "kholo" in lower || "chalu" in lower || "jao" in lower)
        ) {
            val pcTarget = when {
                "excel" in lower || "এক্সেল" in lower -> "excel"
                "word" in lower || "ওয়ার্ড" in lower || "ওয়ার্ড" in lower -> "winword"
                "autocad" in lower || "অটোক্যাড" in lower -> "acad"
                "chrome" in lower || "ক্রোম" in lower -> "chrome"
                else -> "explorer"
            }
            addStep(
                title = "Windows Laptop Command ($pcTarget)",
                payload = ExecutableActionPayload(
                    kind = DeviceActionKind.WINDOWS_DESKTOP_COMMAND,
                    targetOrPath = pcTarget,
                    titleOrSubject = "Launch_$pcTarget"
                )
            )
        }

        // 13. Tally Khata / Shop Ledger Entry
        val isAskingTallySummary = ("কত" in lower || "হিসাব বলো" in lower || "দেখাও" in lower || "total" in lower || "summary" in lower || "koto" in lower) &&
            !("লেখো" in lower || "লিখো" in lower || "lekho" in lower || "likho" in lower || "add" in lower || "save" in lower || "জমা করো" in lower)
        val asksTallyWrite = !isAskingTallySummary && (
            (("ট্যালি" in lower || "tally" in lower || "খাতায়" in lower || "খাতায়" in lower || "khatay" in lower) &&
                ("লেখো" in lower || "লিখো" in lower || "রাখো" in lower || "lekho" in lower || "likho" in lower || "save" in lower || "add" in lower || extractAmountBdt(normalized) > 0.0)) ||
                (("বাকি" in lower || "জমা" in lower || "খরচ" in lower || "baki" in lower || "joma" in lower || "khoroch" in lower) &&
                    ("লেখো" in lower || "লিখো" in lower || "এন্ট্রি" in lower || "lekho" in lower || "likho" in lower))
            )
        if (asksTallyWrite) {
            val amount = extractAmountBdt(normalized).let { if (it > 0.0) it else 500.0 }
            val entryType = when {
                "বাকি" in lower || "due" in lower || "ধার" in lower || "baki" in lower -> "DUE"
                "খরচ" in lower || "expense" in lower || "ব্যয়" in lower || "ব্যয়" in lower || "khoroch" in lower -> "EXPENSE"
                else -> "INCOME"
            }
            val customer = extractCustomerName(trimmed)
            val itemNote = when {
                "দোকান বাকি" in trimmed -> "দোকান বাকি"
                entryType == "DUE" -> "বাকি হিসাব"
                entryType == "EXPENSE" -> "দৈনিক খরচ"
                else -> "নগদ জমা / বিক্রি"
            }
            addStep(
                title = "Save Tally Khata: $customer ($entryType ৳${amount.toLong()})",
                payload = ExecutableActionPayload(
                    kind = DeviceActionKind.SAVE_TALLY_ENTRY,
                    targetOrPath = entryType,
                    titleOrSubject = customer,
                    contentOrBody = itemNote,
                    amountBdt = amount
                )
            )
        }

        // 14. Personal Memory Save
        val asksMemory = steps.none { it.actionPayload.kind == DeviceActionKind.SAVE_BIRTHDAY_REMINDER } && (
            "মনে রাখো" in lower || "মনে রেখো" in lower || "মেমরিতে রাখো" in lower ||
                "mone rakho" in lower || "mone rekho" in lower ||
                lower.startsWith("remember ") || "save to memory" in lower
            )
        if (asksMemory) {
            val cleanedFact = trimmed
                .replace(Regex("(?i)^(hey shuvo[, ]*)?(মনে রাখো|মনে রেখো|মেমরিতে রাখো|mone rakho|mone rekho|remember that|remember)\\s*"), "")
                .trim()
                .ifBlank { trimmed }
            addStep(
                title = "Save to On-Device Memory",
                payload = ExecutableActionPayload(
                    kind = DeviceActionKind.SAVE_PERSONAL_MEMORY,
                    titleOrSubject = "ভয়েস স্মৃতি",
                    contentOrBody = cleanedFact
                )
            )
        }

        // 15. Timer & Alarm Setting (supports both digits and Bangla spoken numbers like "সাতটায়", "সাড়ে ছয়টা")
        if ("timer" in lower || "টাইমার" in lower) {
            val num = Regex("""(\d+)""").find(normalized)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: extractBanglaSpokenNumber(lower)
                ?: 5
            val totalSeconds = if ("সেকেন্ড" in lower || "sec" in lower) num else num * 60
            addStep(
                title = "Start Timer ($num ${if ("সেকেন্ড" in lower || "sec" in lower) "sec" else "min"})",
                payload = ExecutableActionPayload(
                    kind = DeviceActionKind.SET_TIMER,
                    titleOrSubject = "Hey Shuvo Timer",
                    hour = totalSeconds / 3600,
                    minute = (totalSeconds % 3600) / 60,
                    amountBdt = totalSeconds.toDouble()
                )
            )
        } else if ("alarm" in lower || "অ্যালার্ম" in lower || "এলার্ম" in lower) {
            val timeMatch = Regex("""(\d{1,2})(?::(\d{1,2}))?""").find(normalized)
            var hour = timeMatch?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: extractBanglaSpokenNumber(lower)
                ?: 7
            val minute = timeMatch?.groupValues?.getOrNull(2)?.takeIf { it.isNotBlank() }?.toIntOrNull()
                ?: when {
                    "৩০" in trimmed || "30" in normalized || "সাড়ে" in lower || "সাড়ে" in lower || "sare" in lower -> 30
                    "১৫" in trimmed || "পনেরো" in lower || "সোয়া" in lower || "সোয়া" in lower -> 15
                    "৪৫" in trimmed || "পঁয়তাল্লিশ" in lower || "পয়তাল্লিশ" in lower -> 45
                    else -> 0
                }
            if (("বিকাল" in lower || "বিকেল" in lower || "সন্ধ্যা" in lower || "pm" in lower || "bikal" in lower) && hour in 1..11) {
                hour += 12
            } else if ("রাত" in lower || "rat" in lower) {
                if (hour == 12) hour = 0 else if (hour in 6..11) hour += 12
            } else if (("ভোর" in lower || "সকাল" in lower || "am" in lower) && hour == 12) {
                hour = 0
            }
            addStep(
                title = "Set Alarm (%02d:%02d)".format(hour.coerceIn(0, 23), minute.coerceIn(0, 59)),
                payload = ExecutableActionPayload(
                    kind = DeviceActionKind.SET_ALARM,
                    titleOrSubject = "Hey Shuvo Alarm",
                    hour = hour.coerceIn(0, 23),
                    minute = minute.coerceIn(0, 59)
                )
            )
        }

        // 16. Emergency Helpline, Phone Dial, SMS & WhatsApp Draft
        val emergencyHelplines = listOf("999", "333", "16263", "109", "105", "16430")
        val matchedEmergency = emergencyHelplines.firstOrNull { Regex("""\b$it\b""").containsMatchIn(normalized) }
        val phoneMatch = matchedEmergency ?: Regex("""(\+?\d[\d\-\s]{6,14}\d)""").find(normalized)?.groupValues?.getOrNull(1)
            ?.replace(Regex("[\\s\\-]"), "")
        val isWhatsappMsg = ("হোয়াটসঅ্যাপ" in lower || "হোয়াটসঅ্যাপ" in lower || "whatsapp" in lower) &&
            ("মেসেজ" in lower || "message" in lower || "পাঠা" in lower || "লেখ" in lower || "লিখ" in lower || "send" in lower)
        if (phoneMatch != null && ("কল" in lower || "call" in lower || "ডায়াল" in lower || "ডায়াল" in lower || "ফোন দাও" in lower || "phone dao" in lower || (matchedEmergency != null && ("জরুরি" in lower || "হেল্পলাইন" in lower || "কল" in lower || "call" in lower || "dial" in lower)))) {
            addStep(
                title = "Dial Phone ($phoneMatch)",
                payload = ExecutableActionPayload(
                    kind = DeviceActionKind.DIAL_PHONE,
                    targetOrPath = phoneMatch
                )
            )
        } else if (isWhatsappMsg) {
            val cleanBody = rawTrimmed
                .replace(Regex("(?i)(হোয়াটসঅ্যাপে|হোয়াটসঅ্যাপে|হোয়াটসঅ্যাপ|হোয়াটসঅ্যাপ|whatsapp\\s*e|whatsapp|wattsapp|watsapp|whatsap|মেসেজ পাঠাও|মেসেজ লেখো|মেসেজ দাও|send message|message)"), " ")
                .let { if (phoneMatch != null) it.replace(phoneMatch, " ") else it }
                .replace(Regex("\\s+"), " ")
                .trim()
                .ifBlank { rawTrimmed }
            addStep(
                title = if (phoneMatch != null) "Send WhatsApp Draft ($phoneMatch)" else "Prepare WhatsApp Message",
                payload = ExecutableActionPayload(
                    kind = DeviceActionKind.SEND_WHATSAPP_DRAFT,
                    targetOrPath = phoneMatch.orEmpty(),
                    contentOrBody = cleanBody
                )
            )
        } else if (("sms" in lower || "মেসেজ পাঠাও" in lower || "বার্তা পাঠাও" in lower || "message pathao" in lower) && phoneMatch != null) {
            addStep(
                title = "Prepare SMS ($phoneMatch)",
                payload = ExecutableActionPayload(
                    kind = DeviceActionKind.SEND_SMS_DRAFT,
                    targetOrPath = phoneMatch,
                    contentOrBody = trimmed
                )
            )
        }

        // 17. YouTube / Web Media Search
        val youtubePlayVerbs = listOf("চালাও", "চালা", "বাজাও", "বাজা", "সার্চ", "খোঁজো", "খুঁজো", "play", "search", "chalao", "chala", "bajao", "baja", "gan", "গান", "নাটক", "natok", "গজল", "gojol", "news", "খবর")
        val hasYoutubeWord = "youtube" in lower || "ইউটিউব" in lower || "ইউটিউবে" in lower
        val asksYoutubeSearch = hasYoutubeWord && youtubePlayVerbs.any { it in lower }
        if (asksYoutubeSearch) {
            val mediaQuery = trimmed
                .replace(Regex("(?i)(hey shuvo|tmi|tumi|amr|amar|phone|on youtube|in youtube|youtube|ইউটিউবে|ইউটিউব|চালাও|চালা|বাজাও|বাজা|সার্চ করো|সার্চ কর|সার্চ|খোঁজো|খুঁজো|দেখাও|দেখা|play|search|chalao|chala|bajao|baja|koro|kor|jao|jaw)"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
                .ifBlank { "Bangla Popular Song" }
            addStep(
                title = "Play/Search on YouTube: $mediaQuery",
                payload = ExecutableActionPayload(
                    kind = DeviceActionKind.SEARCH_MEDIA_OR_WEB,
                    targetOrPath = "youtube",
                    contentOrBody = mediaQuery
                )
            )
        } else if ("গুগলে সার্চ" in lower || "google search" in lower || "google e search" in lower || lower.startsWith("search for ")) {
            val webQuery = trimmed
                .replace(Regex("(?i)(গুগলে সার্চ করো|গুগলে সার্চ কর|গুগলে সার্চ|google e search koro|google search|search for)"), " ")
                .trim()
            if (webQuery.isNotBlank()) {
                addStep(
                    title = "Search Google: $webQuery",
                    payload = ExecutableActionPayload(
                        kind = DeviceActionKind.SEARCH_MEDIA_OR_WEB,
                        targetOrPath = "google",
                        contentOrBody = webQuery
                    )
                )
            }
        }

        // 18. Open Any App or System Setting
        if (steps.isEmpty()) {
            val openActionVerbs = listOf(
                "open", "launch", "start", "go to", "goto", "show",
                "jao", "jaw", "jau", "ja", "cholo", "chol", "chalu", "kholo", "khulo", "khol", "khul",
                "ber koro", "ber kor", "dekhao", "dekha", "dhuko", "dhuk", "on koro", "on kor", "open kor",
                "চালু", "খোলো", "খুলো", "খোল", "খুল", "ওপেন", "অন করো", "অন কর", "যাও", "যা", "চলো", "চল", "ঢুকো", "ঢোক", "দেখাও", "দেখা", "দাও", "দে", "বের করো", "বের কর"
            )
            val appTargets = listOf(
                "youtube" to "YouTube",
                "ইউটিউব" to "YouTube",
                "whatsapp" to "WhatsApp",
                "whats app" to "WhatsApp",
                "হোয়াটসঅ্যাপ" to "WhatsApp",
                "হোয়াটসঅ্যাপ" to "WhatsApp",
                "facebook" to "Facebook",
                "ফেসবুক" to "Facebook",
                "messenger" to "Messenger",
                "মেসেঞ্জার" to "Messenger",
                "chrome" to "Chrome",
                "ক্রোম" to "Chrome",
                "browser" to "Chrome",
                "ব্রাউজার" to "Chrome",
                "gmail" to "Gmail",
                "জিমেইল" to "Gmail",
                "camera" to "Camera",
                "ক্যামেরা" to "Camera",
                "gallery" to "Gallery",
                "গ্যালারি" to "Gallery",
                "calculator" to "Calculator",
                "ক্যালকুলেটর" to "Calculator",
                "calendar" to "Calendar",
                "ক্যালেন্ডার" to "Calendar",
                "clock" to "Clock",
                "ঘড়ি" to "Clock",
                "ঘড়ি" to "Clock",
                "wifi" to "WiFi Settings",
                "ওয়াইফাই" to "WiFi Settings",
                "ওয়াইফাই" to "WiFi Settings",
                "bluetooth" to "Bluetooth Settings",
                "ব্লুটুথ" to "Bluetooth Settings",
                "location setting" to "Location Settings",
                "লোকেশন সেটিং" to "Location Settings",
                "brightness" to "Display Settings",
                "ব্রাইটনেস" to "Display Settings",
                "display" to "Display Settings",
                "ডিসপ্লে" to "Display Settings",
                "hotspot" to "Hotspot Settings",
                "হটস্পট" to "Hotspot Settings",
                "settings" to "Settings",
                "setting" to "Settings",
                "সেটিং" to "Settings",
                "bkash" to "bKash",
                "বিকাশ" to "bKash",
                "nagad" to "Nagad",
                "নগদ" to "Nagad",
                "rocket" to "Rocket",
                "রকেট" to "Rocket",
                "tiktok" to "TikTok",
                "টিকটক" to "TikTok",
                "imo" to "Imo",
                "ইমো" to "Imo",
                "telegram" to "Telegram",
                "টেলিগ্রাম" to "Telegram",
                "daraz" to "Daraz",
                "দারাজ" to "Daraz",
                "zoom" to "Zoom",
                "জুম" to "Zoom",
                "instagram" to "Instagram",
                "ইনস্টাগ্রাম" to "Instagram",
                "play store" to "Play Store",
                "playstore" to "Play Store",
                "প্লে স্টোর" to "Play Store",
                "files" to "Files",
                "ফাইল" to "Files"
            )
            val informationalTokens = listOf(
                "কী", "কি ", "কেন", "কিভাবে", "ভুলে", "পিন", "পাসওয়ার্ড", "পাসওয়ার্ড",
                "অ্যাকাউন্ট", "একাউন্ট", "সমস্যা", "কাজ করে না", "হেল্প", "সাহায্য", "জানতে",
                "forgot", "pin", "password", "account", "problem", "issue", "help", "reset",
                "what", "why", "how"
            )
            val isInformationalQuestion = informationalTokens.any { it in lower } &&
                openActionVerbs.none { it in lower }

            val matchedApp = appTargets.firstOrNull { (kw, _) -> kw in lower }
            if (matchedApp != null && !isInformationalQuestion && (openActionVerbs.any { it in lower } || trimmed.split(Regex("\\s+")).size <= 5)) {
                addStep(
                    title = "Open ${matchedApp.second}",
                    payload = ExecutableActionPayload(
                        kind = DeviceActionKind.OPEN_APP_OR_WEB,
                        targetOrPath = matchedApp.second
                    )
                )
            } else if (openActionVerbs.any { it in lower } && trimmed.split(Regex("\\s+")).size <= 7) {
                val genericAppName = trimmed
                    .replace(
                        Regex("(?i)\\b(hey|shuvo|please|plz|tmi|tumi|apni|amr|amar|amader|phone|mobile|fne|phn|theke|vitore|e|te|ta|ti|open|launch|start|go|to|goto|app|apps|jao|jaw|jau|cholo|chalu|kholo|khulo|koro|kor|dao|daw|dekhao|ber)\\b"),
                        " "
                    )
                    .replace(
                        Regex("(তুমি|আপনি|আমার|ফোনে|ফোন|মোবাইলে|মোবাইল|অ্যাপস|অ্যাপ|চালু করো|চালু|খোলো|খুলো|ওপেন করো|ওপেন|অন করো|যাও|চলো|ঢুকো|দেখাও|দাও)"),
                        " "
                    )
                    .replace(Regex("\\s+"), " ")
                    .trim()
                if (genericAppName.length >= 2) {
                    addStep(
                        title = "Open $genericAppName",
                        payload = ExecutableActionPayload(
                            kind = DeviceActionKind.OPEN_APP_OR_WEB,
                            targetOrPath = genericAppName
                        )
                    )
                }
            }
        }

        return steps
    }

    internal fun extractBanglaSpokenNumber(lower: String): Int? {
        val numberWords = listOf(
            "বারো" to 12, "এগারো" to 11, "দশ" to 10, "নয়" to 9, "নয়" to 9,
            "আট" to 8, "সাত" to 7, "ছয়" to 6, "ছয়" to 6, "পাঁচ" to 5,
            "চার" to 4, "তিন" to 3, "দুই" to 2, "এক" to 1,
            "পনেরো" to 15, "বিশ" to 20, "তিরিশ" to 30, "ত্রিশ" to 30,
            "twelve" to 12, "eleven" to 11, "ten" to 10, "nine" to 9,
            "eight" to 8, "seven" to 7, "six" to 6, "five" to 5,
            "four" to 4, "three" to 3, "two" to 2, "one" to 1
        )
        return numberWords.firstOrNull { (w, _) -> w in lower }?.second
    }

    private fun extractCustomerName(raw: String): String {
        val stopWords = setOf("দোকান", "ট্যালি", "খাতায়", "খাতায়", "আমার", "আজকের", "বাকি", "খরচ", "আয়", "আয়", "হিসাব", "জমা", "টাকা", "নামে")
        val bnNameMatch = Regex("""([\u0980-\u09FFA-Za-z]+?)(?:-এর|ের| এর)\s*(?:নামে)?""").findAll(raw)
        for (m in bnNameMatch) {
            val candidate = m.groupValues.getOrNull(1)?.trim().orEmpty()
            if (candidate.length >= 2 && candidate.lowercase() !in stopWords) {
                return candidate
            }
        }
        val words = raw
            .replace(Regex("(ট্যালি\\s*খাতায়|ট্যালি\\s*খাতায়|ট্যালি|খাতায়|খাতায়|দোকান|হিসাব|বাকি|খরচ|আয়|আয়|জমা|টাকা|লেখো|লিখো|রাখো|নামে|যোগ|সেভ|করো)"), " ")
            .trim()
            .split(Regex("\\s+"))
            .map { it.replace(Regex("[^\\u0980-\\u09FFA-Za-z]"), "") }
            .filter { it.length >= 2 && it.lowercase() !in stopWords }
        return words.firstOrNull() ?: "গ্রাহক"
    }

    private val MONTH_KEYWORDS = listOf(
        1 to listOf("জানুয়ারি", "জানুয়ারি", "january", "jan"),
        2 to listOf("ফেব্রুয়ারি", "ফেব্রুয়ারি", "february", "feb"),
        3 to listOf("মার্চ", "march", "mar"),
        4 to listOf("এপ্রিল", "april", "apr"),
        5 to listOf("মে", "may"),
        6 to listOf("জুন", "june", "jun"),
        7 to listOf("জুলাই", "july", "jul"),
        8 to listOf("আগস্ট", "অগাস্ট", "august", "aug"),
        9 to listOf("সেপ্টেম্বর", "september", "sep", "sept"),
        10 to listOf("অক্টোবর", "october", "oct"),
        11 to listOf("নভেম্বর", "november", "nov"),
        12 to listOf("ডিসেম্বর", "december", "dec")
    )

    internal fun extractDayAndMonth(normalized: String): Pair<Int, Int>? {
        val lower = normalized.lowercase()
        for ((monthNum, names) in MONTH_KEYWORDS) {
            for (mName in names) {
                if (mName in lower) {
                    val beforeMatch = Regex("""(\d{1,2})\s*(?:ই|শে|তে|st|nd|rd|th)?\s*${Regex.escape(mName)}""", RegexOption.IGNORE_CASE).find(lower)
                    val afterMatch = Regex("""${Regex.escape(mName)}\s*(?:মাসের\s*)?(\d{1,2})""", RegexOption.IGNORE_CASE).find(lower)
                    val day = (beforeMatch?.groupValues?.getOrNull(1) ?: afterMatch?.groupValues?.getOrNull(1))?.toIntOrNull()
                    if (day != null && day in 1..31) {
                        return day to monthNum
                    }
                }
            }
        }
        val slashMatch = Regex("""\b(\d{1,2})[/\-](\d{1,2})\b""").find(normalized)
        if (slashMatch != null) {
            val d = slashMatch.groupValues[1].toIntOrNull()
            val m = slashMatch.groupValues[2].toIntOrNull()
            if (d != null && m != null && d in 1..31 && m in 1..12) {
                return d to m
            }
        }
        return null
    }

    internal fun extractBirthdayPersonName(raw: String): String {
        val possessive = Regex("""([\u0980-\u09FFA-Za-z]+?)(?:-কে|কে|-এর|ের| এর|'s)\b""").find(raw)
        val cand = possessive?.groupValues?.getOrNull(1)?.trim()
        if (!cand.isNullOrBlank() && cand.lowercase() !in setOf("আজ", "কাল", "আমার", "তাকে", "তাদের", "বন্ধু", "birthday", "বার্থডে", "জন্মদিন")) {
            return cand
        }
        val cleanedWords = raw
            .replace(Regex("(?i)(hey|shuvo|হে|শুভ|জন্মদিনের|জন্মদিনে|জন্মদিন|বার্থডেতে|বার্থডে|birthday|jonmodin|উইশ|wish|শুভেচ্ছা|পাঠাও|করো|সেভ|রাখো|মনে|রিমাইন্ডার|তারিখ|save|send|to|for)"), " ")
            .trim()
            .split(Regex("\\s+"))
            .map { it.replace(Regex("[^\\u0980-\\u09FFA-Za-z]"), "") }
            .filter { it.length >= 2 }
        return cleanedWords.firstOrNull() ?: "প্রিয়জন"
    }

    fun buildLocalFallbackAnalysisOrNull(
        raw: String,
        liveTelemetry: String = "",
        tallySummary: String = ""
    ): StructuredCommandAnalysis? {
        val rawTrimmed = raw.trim()
        if (rawTrimmed.isBlank()) return null

        val (hadWakeWord, afterWake) = stripWakeWordPrefix(rawTrimmed)
        val isBn = rawTrimmed.any { it in '\u0980'..'\u09FF' }

        // 0. Pure Wake-Word Check ("হে শুভ" / "শুভ" / "Hey Shuvo" / "Shuvo")
        val wakeTailLower = afterWake.lowercase().trim().trimEnd('?', '.', '!', '।', ',')
        if (hadWakeWord && (afterWake.isBlank() || wakeTailLower in setOf(
                "শুনছো", "শুনছ", "শুনতে পাচ্ছো", "শুনতে পাচ্ছ", "আছো", "আছ", "জি", "হ্যালো", "বলো",
                "suncho", "shuncho", "acho", "are you there", "listening", "boss", "বস"
            ))
        ) {
            return StructuredCommandAnalysis(
                naturalReply = "হ্যাঁ, আমি শুনতে পাচ্ছি বস। আপনি বলুন বস।",
                detectedLanguage = "Bangla",
                requiresSensitiveConfirmation = false,
                steps = emptyList()
            )
        }

        val trimmed = normalizeTyposAndPhonetics(afterWake.ifBlank { rawTrimmed })
        val normalized = normalizeDigits(trimmed)
        val lower = normalized.lowercase().trimEnd('?', '.', '!', '।')

        // 1. Strict Assistant Identity Check
        if (lower in setOf("who are you", "what is your name", "tumi ke", "tmi ke", "apni ke", "tomar nam ki", "tmr nam ki") ||
            "তুমি কে" in trimmed || "আপনি কে" in trimmed || "তোমার নাম কি" in trimmed || "তোমার নাম কী" in trimmed
        ) {
            val reply = if (isBn || "tumi" in lower || "tmi" in lower || "tomar" in lower || "tmr" in lower) {
                "আমি Hey Shuvo, আপনার ডিজিটাল অ্যাসিস্ট্যান্ট।"
            } else {
                "I am Hey Shuvo, your digital assistant."
            }
            return StructuredCommandAnalysis(
                naturalReply = reply,
                detectedLanguage = if (isBn) "Bangla" else "English",
                requiresSensitiveConfirmation = false,
                steps = emptyList()
            )
        }

        // 2. Tally Khata summary query
        if (("ট্যালি" in lower || "tally" in lower || "খাতায়" in lower || "খাতায়" in lower || "khatay" in lower || "মোট বাকি" in lower) &&
            ("কত" in lower || "হিসাব" in lower || "summary" in lower || "total" in lower || "koto" in lower) &&
            !("লেখো" in lower || "লিখো" in lower || "lekho" in lower || "likho" in lower)
        ) {
            val reply = if (tallySummary.isNotBlank()) {
                "আপনার ট্যালি খাতার বর্তমান হিসাব:\n$tallySummary"
            } else {
                "আপনার ট্যালি খাতায় এখনো কোনো এন্ট্রি নেই। ভয়েসে বলুন যেমন: 'রহিমের নামে ১২০০ টাকা বাকি লেখো'।"
            }
            return StructuredCommandAnalysis(
                naturalReply = reply,
                detectedLanguage = "Bangla",
                requiresSensitiveConfirmation = false,
                steps = emptyList()
            )
        }

        // 3. Multi-step explicit device/office/vault/location/mode actions
        val extractedSteps = extractExplicitSteps(rawTrimmed)
        if (extractedSteps.isNotEmpty()) {
            val firstKind = extractedSteps.first().actionPayload.kind
            if (firstKind == DeviceActionKind.CHECK_DEVICE_STATUS && liveTelemetry.isNotBlank()) {
                return StructuredCommandAnalysis(
                    naturalReply = "আপনার ফোনের বর্তমান অবস্থা: $liveTelemetry",
                    detectedLanguage = "Bangla",
                    requiresSensitiveConfirmation = false,
                    steps = extractedSteps
                )
            }
            val summaryTitles = extractedSteps.joinToString(" এবং ") { step ->
                when (step.actionPayload.kind) {
                    DeviceActionKind.SWITCH_VOICE_MODE ->
                        if (step.actionPayload.targetOrPath.equals("AUTO", ignoreCase = true)) {
                            "Auto (All Display) ভয়েস মোড চালু"
                        } else {
                            "Manual ভয়েস মোড চালু"
                        }
                    DeviceActionKind.OPEN_PHOTO_VISION_MODE -> "ফটো ভিশন মোড চালু"
                    DeviceActionKind.SET_USER_LOCATION -> "বর্তমান লোকেশন '${step.actionPayload.targetOrPath}' সেট"
                    DeviceActionKind.SHOW_CURRENT_LOCATION -> "বর্তমান লোকেশন ম্যাপে প্রদর্শন"
                    DeviceActionKind.TOGGLE_FLASHLIGHT -> "ফ্ল্যাশলাইট ${step.actionPayload.targetOrPath}"
                    DeviceActionKind.ADJUST_VOLUME -> "ভলিউম (${step.actionPayload.targetOrPath})"
                    DeviceActionKind.CHECK_DEVICE_STATUS -> "ফোনের লাইভ স্ট্যাটাস চেক"
                    DeviceActionKind.CHECK_SELF_SCREEN_TIME -> "আজকের স্ক্রিন-টাইম চেক"
                    DeviceActionKind.GO_HOME_SCREEN -> "হোম স্ক্রিনে যাওয়া"
                    DeviceActionKind.SEARCH_MEDIA_OR_WEB -> "${step.actionPayload.targetOrPath}-এ '${step.actionPayload.contentOrBody}' চালু"
                    DeviceActionKind.OPEN_MAPS_NAVIGATION -> "Google Maps নেভিগেশন (${step.actionPayload.targetOrPath})"
                    DeviceActionKind.GENERATE_EXCEL_SHEET -> "ফর্মুলাসহ Excel শিট (.csv)"
                    DeviceActionKind.GENERATE_WORD_DOC -> "Microsoft Word ডকুমেন্ট (.doc)"
                    DeviceActionKind.GENERATE_PDF_FILE -> "PDF রিপোর্ট (.pdf)"
                    DeviceActionKind.GENERATE_AUTOCAD_SCRIPT -> "২-তলা বাড়ির AutoCAD প্ল্যান (.lsp)"
                    DeviceActionKind.SAVE_TALLY_ENTRY -> "ট্যালি খাতায় ${step.actionPayload.titleOrSubject}-এর ৳${step.actionPayload.amountBdt.toLong()} (${step.actionPayload.targetOrPath})"
                    DeviceActionKind.SAVE_PERSONAL_MEMORY -> "প্রাইভেট মেমরিতে সংরক্ষণ"
                    DeviceActionKind.SET_ALARM -> "অ্যালার্ম (%02d:%02d)".format(step.actionPayload.hour, step.actionPayload.minute)
                    DeviceActionKind.SET_TIMER -> "টাইমার চালু"
                    DeviceActionKind.OPEN_APP_OR_WEB -> "${step.actionPayload.targetOrPath} চালু"
                    DeviceActionKind.DIAL_PHONE -> "${step.actionPayload.targetOrPath} নাম্বারে কল ডায়াল"
                    DeviceActionKind.UNDO_LAST_TASK -> "সর্বশেষ কাজ Undo"
                    DeviceActionKind.CHANGE_APP_THEME -> "অ্যাপ থিম পরিবর্তন (${step.actionPayload.targetOrPath})"
                    DeviceActionKind.WINDOWS_DESKTOP_COMMAND -> "Windows Laptop কমান্ড (${step.actionPayload.targetOrPath})"
                    DeviceActionKind.SET_BACKGROUND_VOICE_LISTENING ->
                        if (step.actionPayload.targetOrPath.equals("ON", ignoreCase = true)) {
                            "ব্যাকগ্রাউন্ড ভয়েস ও শব্দ শোনা চালু"
                        } else {
                            "ব্যাকগ্রাউন্ড শব্দ ফিল্টার চালু (শুধু আপনার ভয়েস শোনা হবে)"
                        }
                    DeviceActionKind.GLOBAL_BACK -> "পিছনের স্ক্রিনে ফিরে যাওয়া"
                    DeviceActionKind.GLOBAL_RECENTS -> "রিসেন্ট অ্যাপস খোলা"
                    DeviceActionKind.OPEN_NOTIFICATIONS -> "নোটিফিকেশন প্যানেল খোলা"
                    DeviceActionKind.OPEN_QUICK_SETTINGS -> "কুইক সেটিংস খোলা"
                    DeviceActionKind.SCROLL_SCREEN -> "স্ক্রিন স্ক্রোল (${step.actionPayload.targetOrPath})"
                    DeviceActionKind.CLICK_ON_SCREEN_TEXT -> "স্ক্রিনে '${step.actionPayload.targetOrPath}' ট্যাপ"
                    DeviceActionKind.OPEN_GESTURE_TRANSFER_MODE -> "হাতের ইশারায় ফাইল ট্রান্সফার মোড চালু"
                    DeviceActionKind.SAVE_BIRTHDAY_REMINDER -> "${step.actionPayload.titleOrSubject}-এর জন্মদিন (${step.actionPayload.hour}/${step.actionPayload.minute}) সেভ"
                    DeviceActionKind.SEND_BIRTHDAY_WISH -> "${step.actionPayload.titleOrSubject}-কে বার্থডে উইশ প্রস্তুত"
                    else -> step.actionTitle
                }
            }
            val reply = if (extractedSteps.size == 1 && extractedSteps.first().actionPayload.kind == DeviceActionKind.SET_BACKGROUND_VOICE_LISTENING) {
                if (extractedSteps.first().actionPayload.targetOrPath.equals("ON", ignoreCase = true)) {
                    "জি বস, এখন থেকে আমি ব্যাকগ্রাউন্ডের কথা ও শব্দও মনোযোগ দিয়ে শুনছি।"
                } else {
                    "জি বস, ব্যাকগ্রাউন্ডের শব্দ শোনা বন্ধ করেছি—এখন থেকে শুধু আপনার স্পষ্ট কমান্ড শুনব।"
                }
            } else {
                "সম্পন্ন হয়েছে: $summaryTitles।"
            }
            return StructuredCommandAnalysis(
                naturalReply = reply,
                detectedLanguage = if (isBn) "Bangla" else "BN/EN",
                requiresSensitiveConfirmation = false,
                steps = extractedSteps
            )
        }

        // 4. Quick arithmetic calculator
        val mathMatch = Regex("""(-?\d+(?:\.\d+)?)\s*([+\-*/x×÷])\s*(-?\d+(?:\.\d+)?)""").find(normalized)
        if (mathMatch != null) {
            val a = mathMatch.groupValues[1].toDoubleOrNull()
            val op = mathMatch.groupValues[2]
            val b = mathMatch.groupValues[3].toDoubleOrNull()
            if (a != null && b != null) {
                val res = when (op) {
                    "+" -> a + b
                    "-" -> a - b
                    "*", "x", "×" -> a * b
                    "/", "÷" -> if (b != 0.0) a / b else null
                    else -> null
                }
                if (res != null) {
                    val formatted = if (res == res.toLong().toDouble()) res.toLong().toString() else String.format(Locale.US, "%.2f", res)
                    return StructuredCommandAnalysis(
                        naturalReply = "ফলাফল: $a $op $b = $formatted",
                        detectedLanguage = if (isBn) "Bangla" else "English",
                        requiresSensitiveConfirmation = false,
                        steps = emptyList()
                    )
                }
            }
        }

        // 5. Time & Date queries
        if ("সময় কত" in lower || "কয়টা বাজে" in lower || "কয়টা বাজে" in lower || "koyta baje" in lower ||
            "time now" in lower || "what time" in lower || "আজকের তারিখ" in lower || "date today" in lower || "ajker tarikh" in lower
        ) {
            val nowStr = SimpleDateFormat("hh:mm a, dd MMMM yyyy", Locale.US).format(Date())
            return StructuredCommandAnalysis(
                naturalReply = "এখন সময় ও তারিখ: $nowStr",
                detectedLanguage = if (isBn) "Bangla" else "English",
                requiresSensitiveConfirmation = false,
                steps = emptyList()
            )
        }

        // 6. Emergency Helpline Info
        if ("জরুরি নাম্বার" in lower || "হেল্পলাইন" in lower || "emergency number" in lower || "999" == lower) {
            return StructuredCommandAnalysis(
                naturalReply = "বাংলাদেশের জরুরি হেল্পলাইন নাম্বারসমূহ:\n• 999 — পুলিশ, ফায়ার সার্ভিস ও অ্যাম্বুলেন্স\n• 333 — সরকারি তথ্য ও সেবা\n• 16263 — স্বাস্থ্য বাতায়ন (ডাক্তার পরামর্শ)\n• 109 — নারী ও শিশু নির্যাতন প্রতিরোধ\n• 105 — জাতীয় পরিচয়পত্র (NID) হেল্পলাইন",
                detectedLanguage = "Bangla",
                requiresSensitiveConfirmation = false,
                steps = emptyList()
            )
        }

        // 7. Bangladesh e-Passport / NID / BRTA Driving Licence help
        if ("পাসপোর্ট" in lower || "passport" in lower) {
            return StructuredCommandAnalysis(
                naturalReply = "বাংলাদেশ ই-পাসপোর্ট (e-Passport) করার নিয়ম:\n" +
                    "১. www.epassport.gov.bd পোর্টালে অনলাইনে আবেদন ফরম পূরণ করুন।\n" +
                    "২. ৪৮ পৃষ্ঠা ৫ বছর মেয়াদ (রেগুলার): ৪,০২৫ টাকা; ১০ বছর মেয়াদ (রেগুলার): ৫,৭৫০ টাকা।\n" +
                    "৩. পাসপোর্ট অফিসে ছবি, ফিঙ্গারপ্রিন্ট ও কাগজপত্র জমা দিন।",
                detectedLanguage = "Bangla",
                requiresSensitiveConfirmation = false,
                steps = emptyList()
            )
        }
        if ("nid" in lower || "ভোটার আইডি" in lower || "জাতীয় পরিচয়পত্র" in lower || "জাতীয় পরিচয়পত্র" in lower) {
            return StructuredCommandAnalysis(
                naturalReply = "বাংলাদেশ NID সেবা: services.nidw.gov.bd ওয়েবসাইটে একাউন্ট খুলে জন্ম সনদ ও শিক্ষাগত সনদ আপলোড করে নতুন নিবন্ধন বা সংশোধনের আবেদন করুন (হেল্পলাইন: ১০৫)।",
                detectedLanguage = "Bangla",
                requiresSensitiveConfirmation = false,
                steps = emptyList()
            )
        }
        if ("ড্রাইভিং লাইসেন্স" in lower || "driving licence" in lower || "driving license" in lower || "brta" in lower) {
            return StructuredCommandAnalysis(
                naturalReply = "BRTA ড্রাইভিং লাইসেন্স: bsp.brta.gov.bd পোর্টালে প্রথমে লার্নার লাইসেন্সের আবেদন করুন। প্রয়োজন: NID কপি, মেডিকেল সার্টিফিকেট ও রক্তের গ্রুপ রিপোর্ট।",
                detectedLanguage = "Bangla",
                requiresSensitiveConfirmation = false,
                steps = emptyList()
            )
        }

        // 8. Polite greetings
        if (lower in setOf("hi", "hello", "hlw", "hey", "salam", "assalamu alaikum", "kemon aso", "kmn aso", "kemon achen") ||
            "আসসালামু আলাইকুম" in trimmed || "কেমন আছ" in trimmed || "হ্যালো" in trimmed
        ) {
            val reply = if (isBn || "salam" in lower || "aso" in lower || "achen" in lower) {
                "ওয়ালাইকুম আসসালাম! আমি Hey Shuvo প্রস্তুত আছি। বলুন, আপনার জন্য কী করতে পারি?"
            } else {
                "Hello! Hey Shuvo is online and ready. Tell me what you'd like to do."
            }
            return StructuredCommandAnalysis(
                naturalReply = reply,
                detectedLanguage = if (isBn) "Bangla" else "English",
                requiresSensitiveConfirmation = false,
                steps = emptyList()
            )
        }

        return null
    }
}
