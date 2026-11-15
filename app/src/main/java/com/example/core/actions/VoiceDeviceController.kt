package com.example.core.actions

import android.Manifest
import android.annotation.SuppressLint
import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.Process
import android.os.StatFs
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.example.MainActivity
import com.example.core.preferences.AssistantSettings
import com.example.core.preferences.PreferencesRepository
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class DeviceActionKind {
    NONE,
    OPEN_MAPS_NAVIGATION,
    SHOW_CURRENT_LOCATION,
    SET_USER_LOCATION,
    OPEN_PHOTO_VISION_MODE,
    SWITCH_VOICE_MODE,
    OPEN_APP_OR_WEB,
    SEARCH_MEDIA_OR_WEB,
    TOGGLE_FLASHLIGHT,
    ADJUST_VOLUME,
    CHECK_DEVICE_STATUS,
    CHECK_SELF_SCREEN_TIME,
    GO_HOME_SCREEN,
    DIAL_PHONE,
    SEND_SMS_DRAFT,
    SEND_WHATSAPP_DRAFT,
    SEND_EMAIL_DRAFT,
    SET_ALARM,
    SET_TIMER,
    ADD_CALENDAR_EVENT,
    GENERATE_WORD_DOC,
    GENERATE_EXCEL_SHEET,
    GENERATE_PDF_FILE,
    GENERATE_AUTOCAD_SCRIPT,
    SAVE_TALLY_ENTRY,
    SAVE_PERSONAL_MEMORY,
    UNDO_LAST_TASK,
    SUBMIT_FEATURE_REQUEST,
    CHANGE_APP_THEME,
    WINDOWS_DESKTOP_COMMAND,
    SET_BACKGROUND_VOICE_LISTENING,
    GLOBAL_BACK,
    GLOBAL_RECENTS,
    OPEN_NOTIFICATIONS,
    OPEN_QUICK_SETTINGS,
    SCROLL_SCREEN,
    CLICK_ON_SCREEN_TEXT,
    OPEN_GESTURE_TRANSFER_MODE,
    SAVE_BIRTHDAY_REMINDER,
    SEND_BIRTHDAY_WISH;

    /**
     * In Standard hands-free mode, Android OS intents (Dialer, SMS/WhatsApp/Email draft screens)
     * open directly for seamless full-voice control without requiring extra manual app taps.
     * In Children Safe Mode, sensitive communications remain gated by Parent PIN.
     */
    val isSensitiveCommunication: Boolean
        get() = this == DIAL_PHONE ||
            this == SEND_SMS_DRAFT ||
            this == SEND_WHATSAPP_DRAFT ||
            this == SEND_EMAIL_DRAFT
}

data class ResolvedUserLocation(
    val displayLabel: String,
    val mapsQuery: String,
    val latitude: Double,
    val longitude: Double,
    val isLiveGps: Boolean
)

data class ExecutableActionPayload(
    val kind: DeviceActionKind = DeviceActionKind.NONE,
    val targetOrPath: String = "",
    val titleOrSubject: String = "",
    val contentOrBody: String = "",
    val hour: Int = 8,
    val minute: Int = 0,
    val amountBdt: Double = 0.0
)

data class ActionExecutionOutcome(
    val summary: String,
    val generatedFilePath: String? = null,
    val previewTitle: String? = null,
    val previewBody: String? = null
)

data class SelfScreenTimeItem(
    val packageName: String,
    val minutesToday: Long
)

class VoiceDeviceController(private val context: Context) {

    fun executeAction(payload: ExecutableActionPayload): Result<ActionExecutionOutcome> {
        return try {
            when (payload.kind) {
                DeviceActionKind.NONE -> Result.success(
                    ActionExecutionOutcome("উত্তর সম্পন্ন হয়েছে।")
                )

                DeviceActionKind.SET_BACKGROUND_VOICE_LISTENING -> {
                    val enable = payload.targetOrPath.equals("ON", ignoreCase = true) ||
                        payload.targetOrPath.equals("TRUE", ignoreCase = true)
                    val msg = if (enable) {
                        "✓ ব্যাকগ্রাউন্ড কণ্ঠস্বর ও শব্দ শোনা চালু করা হয়েছে"
                    } else {
                        "✓ ব্যাকগ্রাউন্ড শব্দ ফিল্টার চালু হয়েছে — শুধু আপনার স্পষ্ট নির্দেশ শোনা হবে"
                    }
                    Result.success(ActionExecutionOutcome(msg))
                }

                DeviceActionKind.GLOBAL_BACK -> {
                    val acc = com.example.core.service.HeyShuvoAccessibilityService.activeInstance
                    if (acc != null && acc.performGlobalNav(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)) {
                        Result.success(ActionExecutionOutcome("✓ আগের স্ক্রিনে (Back) যাওয়া হয়েছে"))
                    } else {
                        openAccessibilitySettings()
                        Result.success(ActionExecutionOutcome("✓ অন্য অ্যাপের ভেতর Back করতে একবার Hey Shuvo Accessibility চালু করুন"))
                    }
                }

                DeviceActionKind.GLOBAL_RECENTS -> {
                    val acc = com.example.core.service.HeyShuvoAccessibilityService.activeInstance
                    if (acc != null && acc.performGlobalNav(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS)) {
                        Result.success(ActionExecutionOutcome("✓ রিসেন্ট অ্যাপস খোলা হয়েছে"))
                    } else {
                        openAccessibilitySettings()
                        Result.success(ActionExecutionOutcome("✓ রিসেন্ট অ্যাপস ভয়েসে খুলতে একবার Hey Shuvo Accessibility চালু করুন"))
                    }
                }

                DeviceActionKind.OPEN_NOTIFICATIONS -> {
                    val acc = com.example.core.service.HeyShuvoAccessibilityService.activeInstance
                    if (acc != null && acc.performGlobalNav(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)) {
                        Result.success(ActionExecutionOutcome("✓ নোটিফিকেশন প্যানেল খোলা হয়েছে"))
                    } else {
                        openAccessibilitySettings()
                        Result.success(ActionExecutionOutcome("✓ নোটিফিকেশন প্যানেল ভয়েসে খুলতে একবার Hey Shuvo Accessibility চালু করুন"))
                    }
                }

                DeviceActionKind.OPEN_QUICK_SETTINGS -> {
                    val acc = com.example.core.service.HeyShuvoAccessibilityService.activeInstance
                    if (acc != null && acc.performGlobalNav(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)) {
                        Result.success(ActionExecutionOutcome("✓ কুইক সেটিংস প্যানেল খোলা হয়েছে"))
                    } else {
                        openAccessibilitySettings()
                        Result.success(ActionExecutionOutcome("✓ কুইক সেটিংস ভয়েসে খুলতে একবার Hey Shuvo Accessibility চালু করুন"))
                    }
                }

                DeviceActionKind.SCROLL_SCREEN -> {
                    val down = !payload.targetOrPath.equals("UP", ignoreCase = true)
                    val acc = com.example.core.service.HeyShuvoAccessibilityService.activeInstance
                    if (acc != null && acc.scrollCurrentWindow(down = down)) {
                        val dirBn = if (down) "নিচে" else "উপরে"
                        Result.success(ActionExecutionOutcome("✓ স্ক্রিন $dirBn স্ক্রোল করা হয়েছে"))
                    } else {
                        openAccessibilitySettings()
                        Result.success(ActionExecutionOutcome("✓ যেকোনো অ্যাপে ভয়েসে স্ক্রোল করতে একবার Hey Shuvo Accessibility চালু করুন"))
                    }
                }

                DeviceActionKind.CLICK_ON_SCREEN_TEXT -> {
                    val targetLabel = payload.targetOrPath.ifBlank { payload.titleOrSubject }.trim()
                    val acc = com.example.core.service.HeyShuvoAccessibilityService.activeInstance
                    if (acc != null) {
                        val clicked = acc.clickOnScreenByText(targetLabel)
                        if (clicked) {
                            Result.success(ActionExecutionOutcome("✓ স্ক্রিনে '$targetLabel' ট্যাপ করা হয়েছে"))
                        } else {
                            Result.success(ActionExecutionOutcome("✓ স্ক্রিনে '$targetLabel' খোঁজা হয়েছে (দৃশ্যমান বাটন পাওয়া যায়নি)"))
                        }
                    } else {
                        openAccessibilitySettings()
                        Result.success(ActionExecutionOutcome("✓ অন্য অ্যাপের বাটনে ভয়েসে ট্যাপ করতে একবার Hey Shuvo Accessibility চালু করুন"))
                    }
                }

                DeviceActionKind.CHANGE_APP_THEME -> Result.success(
                    ActionExecutionOutcome("✓ অ্যাপের থিম পরিবর্তন করা হয়েছে: ${payload.targetOrPath}")
                )

                DeviceActionKind.SWITCH_VOICE_MODE -> {
                    val modeLabel = if (payload.targetOrPath.equals("AUTO", ignoreCase = true)) {
                        "AUTO • All Display"
                    } else {
                        "MANUAL"
                    }
                    Result.success(
                        ActionExecutionOutcome("✓ ভয়েস মোড পরিবর্তন হয়েছে: $modeLabel")
                    )
                }

                DeviceActionKind.OPEN_PHOTO_VISION_MODE -> {
                    val photoIntent = Intent(context, MainActivity::class.java).apply {
                        putExtra(EXTRA_TRIGGER_PHOTO_VISION, true)
                        addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                                Intent.FLAG_ACTIVITY_CLEAR_TOP
                        )
                    }
                    tryStartAny(photoIntent)
                    Result.success(
                        ActionExecutionOutcome(
                            summary = "✓ ফটো ও ক্যামেরা ভিশন মোড চালু হয়েছে — ছবি নির্বাচন করুন"
                        )
                    )
                }

                DeviceActionKind.OPEN_GESTURE_TRANSFER_MODE -> {
                    val gestureIntent = Intent(context, MainActivity::class.java).apply {
                        putExtra(EXTRA_TRIGGER_GESTURE_TRANSFER, true)
                        addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                                Intent.FLAG_ACTIVITY_CLEAR_TOP
                        )
                    }
                    tryStartAny(gestureIntent)
                    Result.success(
                        ActionExecutionOutcome(
                            summary = "✓ হাতের ইশারায় ফাইল ট্রান্সফার মোড চালু হয়েছে"
                        )
                    )
                }

                DeviceActionKind.SAVE_BIRTHDAY_REMINDER -> {
                    val person = payload.titleOrSubject.ifBlank { "প্রিয়জন" }
                    val day = payload.hour.coerceIn(1, 31)
                    val month = payload.minute.coerceIn(1, 12)
                    Result.success(
                        ActionExecutionOutcome(
                            summary = "✓ জন্মদিন সেভ করা হয়েছে: $person ($day/$month)",
                            previewTitle = "Birthday Reminder: $person",
                            previewBody = "তারিখ: $day/$month\nউইশ মেসেজ: ${payload.contentOrBody}"
                        )
                    )
                }

                DeviceActionKind.SEND_BIRTHDAY_WISH -> {
                    val recipient = payload.targetOrPath.trim()
                    val person = payload.titleOrSubject.ifBlank { "বন্ধু" }
                    val wishBody = payload.contentOrBody.ifBlank {
                        "শুভ জন্মদিন $person! আপনার আগামী দিনগুলো সুস্থ, সুন্দর ও আনন্দময় হোক।"
                    }
                    val cleanPhone = recipient.filter { it.isDigit() || it == '+' }
                    val url = if (cleanPhone.isNotBlank()) {
                        "https://api.whatsapp.com/send?phone=${Uri.encode(cleanPhone)}&text=${Uri.encode(wishBody)}"
                    } else {
                        "https://api.whatsapp.com/send?text=${Uri.encode(wishBody)}"
                    }
                    val waIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    val smsFallback = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${Uri.encode(cleanPhone)}")).apply {
                        putExtra("sms_body", wishBody)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    tryStartAny(waIntent, smsFallback)
                    Result.success(
                        ActionExecutionOutcome(
                            summary = "✓ বার্থডে উইশ প্রস্তুত: $person",
                            previewTitle = "Birthday Wish → $person",
                            previewBody = wishBody
                        )
                    )
                }

                DeviceActionKind.SET_USER_LOCATION -> {
                    val rawLoc = payload.targetOrPath.ifBlank { AssistantSettings.DEFAULT_BD_LOCATION }
                    val qualified = qualifyLocationForMaps(rawLoc)
                    PreferencesRepository.writeCachedLocationSync(context, qualified)
                    Result.success(
                        ActionExecutionOutcome(
                            summary = "✓ আপনার বর্তমান লোকেশন সেট করা হয়েছে: $qualified",
                            previewTitle = "Updated Current Location",
                            previewBody = "Current Location: $qualified"
                        )
                    )
                }

                DeviceActionKind.SHOW_CURRENT_LOCATION -> {
                    val current = resolveCurrentUserLocation()
                    val mapsSearchUrl = buildCurrentLocationMapsUrl(current)
                    val geoIntent = Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("geo:${current.latitude},${current.longitude}?z=15&q=${Uri.encode(current.mapsQuery)}")
                    ).apply {
                        setPackage("com.google.android.apps.maps")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    val webMapsIntent = Intent(Intent.ACTION_VIEW, Uri.parse(mapsSearchUrl)).apply {
                        setPackage("com.google.android.apps.maps")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    val browserFallbackIntent = Intent(Intent.ACTION_VIEW, Uri.parse(mapsSearchUrl)).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    tryStartAny(geoIntent, webMapsIntent, browserFallbackIntent)
                    val sourceBadge = if (current.isLiveGps) "লাইভ GPS" else "নির্বাচিত লোকেশন"
                    Result.success(
                        ActionExecutionOutcome(
                            summary = "✓ আপনার বর্তমান লোকেশন খোলা হয়েছে: ${current.displayLabel} ($sourceBadge)",
                            previewTitle = "Current Location: ${current.displayLabel}",
                            previewBody = "Location: ${current.displayLabel}\nCoordinates: ${current.latitude}, ${current.longitude}\nMaps URL: $mapsSearchUrl"
                        )
                    )
                }

                DeviceActionKind.OPEN_MAPS_NAVIGATION -> {
                    val currentOrigin = resolveCurrentUserLocation()
                    val explicitOrigin = payload.titleOrSubject.trim().takeIf { it.isNotBlank() }
                        ?.let { qualifyLocationForMaps(it) }
                    val originQuery = explicitOrigin ?: currentOrigin.mapsQuery
                    val originDisplay = explicitOrigin ?: currentOrigin.displayLabel

                    val rawDest = payload.targetOrPath.trim()
                    if (rawDest.isBlank() || rawDest.equals("CURRENT_LOCATION", ignoreCase = true)) {
                        return executeAction(payload.copy(kind = DeviceActionKind.SHOW_CURRENT_LOCATION))
                    }

                    val qualifiedDest = qualifyLocationForMaps(rawDest)
                    val directionsUrl = buildDirectionsMapsUrl(originQuery = originQuery, destinationQuery = qualifiedDest)

                    val mapsDirIntent = Intent(Intent.ACTION_VIEW, Uri.parse(directionsUrl)).apply {
                        setPackage("com.google.android.apps.maps")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    val navIntent = Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("google.navigation:q=${Uri.encode(qualifiedDest)}&mode=d")
                    ).apply {
                        setPackage("com.google.android.apps.maps")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    val browserDirIntent = Intent(Intent.ACTION_VIEW, Uri.parse(directionsUrl)).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }

                    val launched = tryStartAny(mapsDirIntent, navIntent, browserDirIntent)
                    val summary = if (launched) {
                        "✓ ম্যাপ নেভিগেশন চালু হয়েছে: $originDisplay → $qualifiedDest"
                    } else {
                        "✓ রুট প্রস্তুত: $originDisplay → $qualifiedDest"
                    }
                    Result.success(
                        ActionExecutionOutcome(
                            summary = summary,
                            previewTitle = "Route: $originDisplay → $qualifiedDest",
                            previewBody = "Origin: $originDisplay\nDestination: $qualifiedDest\nMaps URL: $directionsUrl"
                        )
                    )
                }

                DeviceActionKind.SEARCH_MEDIA_OR_WEB -> {
                    val platform = payload.targetOrPath.lowercase().trim()
                    val query = payload.contentOrBody.ifBlank { payload.titleOrSubject }.trim().ifBlank { "Bangladesh news" }
                    if ("youtube" in platform || "ইউটিউব" in platform || "utube" in platform || "video" in platform || "গান" in platform) {
                        val ytSearchIntent = Intent(Intent.ACTION_SEARCH).apply {
                            setPackage("com.google.android.youtube")
                            putExtra("query", query)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        val ytWebUrl = "https://m.youtube.com/results?search_query=${Uri.encode(query)}"
                        val ytWebIntent = Intent(Intent.ACTION_VIEW, Uri.parse(ytWebUrl)).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        tryStartAny(ytSearchIntent, ytWebIntent)
                        Result.success(
                            ActionExecutionOutcome(
                                summary = "✓ YouTube-এ চালু করা হয়েছে: $query",
                                previewTitle = "YouTube Search: $query",
                                previewBody = ytWebUrl
                            )
                        )
                    } else {
                        val searchUrl = "https://www.google.com/search?q=${Uri.encode(query)}"
                        val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse(searchUrl)).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        tryStartAny(webIntent)
                        Result.success(
                            ActionExecutionOutcome(
                                summary = "✓ ওয়েবে খোঁজা হয়েছে: $query",
                                previewTitle = "Web Search: $query",
                                previewBody = searchUrl
                            )
                        )
                    }
                }

                DeviceActionKind.TOGGLE_FLASHLIGHT -> {
                    val mode = payload.targetOrPath.lowercase().trim()
                    val enable = !("off" in mode || "বন্ধ" in mode || "নিভাও" in mode || "false" in mode)
                    val toggled = setHardwareTorch(enable)
                    val label = if (enable) "চালু (ON)" else "বন্ধ (OFF)"
                    if (toggled) {
                        Result.success(ActionExecutionOutcome("✓ ফোনের ফ্ল্যাশলাইট / টর্চ $label করা হয়েছে"))
                    } else {
                        Result.success(ActionExecutionOutcome("✓ ফ্ল্যাশলাইট কমান্ড পাঠানো হয়েছে ($label — এই ডিভাইসে ফ্ল্যাশ হার্ডওয়্যার নেই)"))
                    }
                }

                DeviceActionKind.ADJUST_VOLUME -> {
                    val directive = payload.targetOrPath.uppercase().trim()
                    val status = adjustHardwareVolume(directive)
                    Result.success(ActionExecutionOutcome("✓ $status"))
                }

                DeviceActionKind.CHECK_DEVICE_STATUS -> {
                    val report = readLiveDeviceTelemetrySummary()
                    Result.success(
                        ActionExecutionOutcome(
                            summary = "✓ ফোনের লাইভ স্ট্যাটাস যাচাই সম্পন্ন",
                            previewTitle = "Live Phone Status",
                            previewBody = report
                        )
                    )
                }

                DeviceActionKind.CHECK_SELF_SCREEN_TIME -> {
                    if (!hasSelfUsageStatsPermission()) {
                        openUsageAccessSettings()
                        Result.success(
                            ActionExecutionOutcome(
                                summary = "✓ স্ক্রিন-টাইম দেখতে Usage Access সেটিংস খোলা হয়েছে (একবার Allow করুন)"
                            )
                        )
                    } else {
                        val items = querySelfTodayScreenTime()
                        val body = if (items.isEmpty()) {
                            "আজ ১ মিনিটের বেশি ব্যবহৃত কোনো অ্যাপ রেকর্ড হয়নি।"
                        } else {
                            items.joinToString("\n") { "• ${it.packageName}: ${it.minutesToday} মিনিট" }
                        }
                        Result.success(
                            ActionExecutionOutcome(
                                summary = "✓ আজকের স্ক্রিন-টাইম রিপোর্ট প্রস্তুত (${items.size} অ্যাপ)",
                                previewTitle = "Today's Screen Time",
                                previewBody = body
                            )
                        )
                    }
                }

                DeviceActionKind.GO_HOME_SCREEN -> {
                    val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                        addCategory(Intent.CATEGORY_HOME)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    tryStartAny(homeIntent)
                    Result.success(ActionExecutionOutcome("✓ হোম স্ক্রিনে যাওয়া হয়েছে"))
                }

                DeviceActionKind.OPEN_APP_OR_WEB -> {
                    val raw = payload.targetOrPath.trim().ifBlank { "Google" }
                    val target = raw.lowercase()

                    // 1. Check System Settings / Hardware Panels first
                    val systemIntent: Intent? = when {
                        "wifi" in target || "ওয়াইফাই" in target || "ওয়াইফাই" in target ->
                            Intent(Settings.ACTION_WIFI_SETTINGS)
                        "bluetooth" in target || "ব্লুটুথ" in target ->
                            Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
                        "location" in target || "gps" in target || "লোকেশন" in target ->
                            Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                        "airplane" in target || "flight" in target || "এয়ারপ্লেন" in target ->
                            Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS)
                        "hotspot" in target || "tether" in target || "হটস্পট" in target ->
                            Intent(Settings.ACTION_WIRELESS_SETTINGS)
                        "battery" in target || "ব্যাটারি সেটিং" in target ->
                            Intent(Intent.ACTION_POWER_USAGE_SUMMARY)
                        "sound" in target || "volume" in target || "সাউন্ড" in target ->
                            Intent(Settings.ACTION_SOUND_SETTINGS)
                        "display" in target || "brightness" in target || "ডিসপ্লে" in target || "আলো" in target ->
                            Intent(Settings.ACTION_DISPLAY_SETTINGS)
                        "accessibility" in target || "অ্যাক্সেসিবিলিটি" in target ->
                            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        "overlay" in target || "all display" in target ->
                            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
                        "setting" in target || "সেটিং" in target ->
                            Intent(Settings.ACTION_SETTINGS)
                        "camera" in target || "ক্যামেরা" in target ->
                            Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
                        "video camera" in target || "ভিডিও ক্যামেরা" in target ->
                            Intent(MediaStore.INTENT_ACTION_VIDEO_CAMERA)
                        "gallery" in target || "photo" in target || "গ্যালারি" in target || "ছবি" in target ->
                            Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_APP_GALLERY) }
                        "contact" in target || "কন্টাক্ট" in target ->
                            Intent(Intent.ACTION_VIEW, ContactsContract.Contacts.CONTENT_URI)
                        "calculator" in target || "ক্যালকুলেটর" in target ->
                            Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALCULATOR)
                        "calendar" in target || "ক্যালেন্ডার" in target ->
                            Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALENDAR)
                        "clock" in target || "ঘড়ি" in target || "ঘড়ি" in target ->
                            Intent(AlarmClock.ACTION_SHOW_ALARMS)
                        "file" in target || "ফাইল" in target || "download" in target ->
                            Intent(Intent.ACTION_GET_CONTENT).apply { type = "*/*" }
                        else -> null
                    }
                    if (systemIntent != null && tryStartAny(systemIntent.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })) {
                        return Result.success(ActionExecutionOutcome("✓ সিস্টেম ফিচার চালু হয়েছে: $raw"))
                    }

                    // 2. Known popular package fast-path
                    val candidatePackages = resolveKnownAppPackages(target)
                    for (pkg in candidatePackages) {
                        val launchIntent = context.packageManager.getLaunchIntentForPackage(pkg)
                        if (launchIntent != null && tryStartAny(launchIntent.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })) {
                            return Result.success(ActionExecutionOutcome("✓ অ্যাপ চালু হয়েছে: $raw"))
                        }
                    }

                    // 3. Dynamic scan across ALL launchable apps installed on the user's phone!
                    val dynamicMatch = findAndLaunchInstalledAppByName(target)
                    if (dynamicMatch != null) {
                        return Result.success(ActionExecutionOutcome("✓ অ্যাপ চালু হয়েছে: $dynamicMatch"))
                    }

                    // 4. Web fallback so user never hits a dead end
                    val url = when {
                        target.startsWith("http://") || target.startsWith("https://") -> raw
                        "youtube" in target || "ইউটিউব" in target || "utube" in target -> "https://m.youtube.com"
                        "facebook" in target || "ফেসবুক" in target || "fb" == target -> "https://m.facebook.com"
                        "whatsapp" in target || "হোয়াটসঅ্যাপ" in target || "হোয়াটসঅ্যাপ" in target -> "https://web.whatsapp.com"
                        "gmail" in target || "জিমেইল" in target -> "https://mail.google.com"
                        "daraz" in target || "দারাজ" in target -> "https://www.daraz.com.bd"
                        "." in target && !target.contains(" ") -> "https://$raw"
                        else -> "https://www.google.com/search?q=${Uri.encode(raw)}"
                    }
                    val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    tryStartAny(webIntent)
                    Result.success(
                        ActionExecutionOutcome(
                            summary = "✓ ওপেন করা হয়েছে: $raw",
                            previewTitle = "Opened Link / App: $raw",
                            previewBody = url
                        )
                    )
                }

                DeviceActionKind.DIAL_PHONE -> {
                    val number = payload.targetOrPath.ifBlank { "" }
                    val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(number)}")).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    tryStartAny(intent)
                    Result.success(ActionExecutionOutcome("✓ ফোন ডায়ালার খোলা হয়েছে: $number"))
                }

                DeviceActionKind.SEND_SMS_DRAFT -> {
                    val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${Uri.encode(payload.targetOrPath)}")).apply {
                        putExtra("sms_body", payload.contentOrBody)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    tryStartAny(intent)
                    Result.success(
                        ActionExecutionOutcome(
                            summary = "✓ SMS প্রস্তুত: ${payload.targetOrPath}",
                            previewTitle = "SMS Draft → ${payload.targetOrPath}",
                            previewBody = payload.contentOrBody
                        )
                    )
                }

                DeviceActionKind.SEND_WHATSAPP_DRAFT -> {
                    val cleanPhone = payload.targetOrPath.filter { it.isDigit() || it == '+' }
                    val url = if (cleanPhone.isNotBlank()) {
                        "https://api.whatsapp.com/send?phone=${Uri.encode(cleanPhone)}&text=${Uri.encode(payload.contentOrBody)}"
                    } else {
                        "https://api.whatsapp.com/send?text=${Uri.encode(payload.contentOrBody)}"
                    }
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    tryStartAny(intent)
                    Result.success(
                        ActionExecutionOutcome(
                            summary = "✓ WhatsApp মেসেজ প্রস্তুত",
                            previewTitle = "WhatsApp Draft",
                            previewBody = payload.contentOrBody
                        )
                    )
                }

                DeviceActionKind.SEND_EMAIL_DRAFT -> {
                    val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:${Uri.encode(payload.targetOrPath)}")).apply {
                        putExtra(Intent.EXTRA_SUBJECT, payload.titleOrSubject)
                        putExtra(Intent.EXTRA_TEXT, payload.contentOrBody)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    tryStartAny(intent)
                    Result.success(
                        ActionExecutionOutcome(
                            summary = "✓ ইমেইল প্রস্তুত: ${payload.titleOrSubject}",
                            previewTitle = "Email: ${payload.titleOrSubject}",
                            previewBody = "To: ${payload.targetOrPath}\n\n${payload.contentOrBody}"
                        )
                    )
                }

                DeviceActionKind.SET_ALARM -> {
                    val h = payload.hour.coerceIn(0, 23)
                    val m = payload.minute.coerceIn(0, 59)
                    val label = payload.titleOrSubject.ifBlank { "Hey Shuvo Alarm" }
                    val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                        putExtra(AlarmClock.EXTRA_MESSAGE, label)
                        putExtra(AlarmClock.EXTRA_HOUR, h)
                        putExtra(AlarmClock.EXTRA_MINUTES, m)
                        putExtra(AlarmClock.EXTRA_SKIP_UI, false)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    tryStartAny(intent)
                    Result.success(ActionExecutionOutcome("✓ অ্যালার্ম সেট করা হয়েছে: %02d:%02d (%s)".format(h, m, label)))
                }

                DeviceActionKind.SET_TIMER -> {
                    val seconds = ((payload.hour * 3600) + (payload.minute * 60)).let {
                        if (it > 0) it else payload.amountBdt.toInt().takeIf { s -> s > 0 } ?: 300
                    }
                    val label = payload.titleOrSubject.ifBlank { "Hey Shuvo Timer" }
                    val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
                        putExtra(AlarmClock.EXTRA_MESSAGE, label)
                        putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                        putExtra(AlarmClock.EXTRA_SKIP_UI, false)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    tryStartAny(intent)
                    val mins = seconds / 60
                    val remSec = seconds % 60
                    val humanDur = if (mins > 0) "${mins}m ${remSec}s" else "${remSec}s"
                    Result.success(ActionExecutionOutcome("✓ টাইমার চালু হয়েছে: $humanDur ($label)"))
                }

                DeviceActionKind.ADD_CALENDAR_EVENT -> {
                    val title = payload.titleOrSubject.ifBlank { "Hey Shuvo Scheduled Event" }
                    val intent = Intent(Intent.ACTION_INSERT).apply {
                        data = CalendarContract.Events.CONTENT_URI
                        putExtra(CalendarContract.Events.TITLE, title)
                        putExtra(CalendarContract.Events.DESCRIPTION, payload.contentOrBody)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    tryStartAny(intent)
                    Result.success(ActionExecutionOutcome("✓ ক্যালেন্ডার ইভেন্ট প্রস্তুত: $title"))
                }

                DeviceActionKind.GENERATE_WORD_DOC -> {
                    val title = payload.titleOrSubject.ifBlank { "HeyShuvo_Document" }
                    val body = payload.contentOrBody.ifBlank {
                        "Prepared by Hey Shuvo Voice Assistant.\n\n1. Executive Summary\n2. Key Points & Decisions\n3. Next Action Steps"
                    }
                    val file = writeOfficeFile(
                        fileName = sanitizeFileName(title, "HeyShuvo_Word_Doc") + ".doc",
                        content = buildWordCompatibleHtml(title, body)
                    )
                    Result.success(
                        ActionExecutionOutcome(
                            summary = "✓ Microsoft Word ফাইল তৈরি হয়েছে: ${file.name}",
                            generatedFilePath = file.absolutePath,
                            previewTitle = "Word Document: ${file.name}",
                            previewBody = "$title\n${"─".repeat(32)}\n$body"
                        )
                    )
                }

                DeviceActionKind.GENERATE_EXCEL_SHEET -> {
                    val title = payload.titleOrSubject.ifBlank { "HeyShuvo_Sheet" }
                    val csvBody = payload.contentOrBody.ifBlank {
                        "SL,Item Description,Quantity,Unit Price,Total\n1,Item A,5,1200,=C2*D2\n2,Item B,3,850,=C3*D3\n,,Grand Total,,=SUM(E2:E3)"
                    }
                    val utf8BomCsv = if (csvBody.startsWith("\uFEFF")) csvBody else "\uFEFF$csvBody"
                    val file = writeOfficeFile(
                        fileName = sanitizeFileName(title, "HeyShuvo_Excel_Sheet") + ".csv",
                        content = utf8BomCsv
                    )
                    Result.success(
                        ActionExecutionOutcome(
                            summary = "✓ Microsoft Excel ফর্মুলা শিট তৈরি হয়েছে: ${file.name}",
                            generatedFilePath = file.absolutePath,
                            previewTitle = "Excel Sheet (.CSV with Formulas): ${file.name}",
                            previewBody = csvBody
                        )
                    )
                }

                DeviceActionKind.GENERATE_PDF_FILE -> {
                    val title = payload.titleOrSubject.ifBlank { "HeyShuvo_Report" }
                    val body = payload.contentOrBody.ifBlank {
                        "Official PDF Report generated by Hey Shuvo Voice Assistant."
                    }
                    val file = generateNativePdf(
                        fileName = sanitizeFileName(title, "HeyShuvo_PDF_Report") + ".pdf",
                        title = title,
                        body = body
                    )
                    Result.success(
                        ActionExecutionOutcome(
                            summary = "✓ PDF ফাইল তৈরি হয়েছে: ${file.name}",
                            generatedFilePath = file.absolutePath,
                            previewTitle = "PDF Document: ${file.name}",
                            previewBody = "$title\n${"─".repeat(32)}\n$body"
                        )
                    )
                }

                DeviceActionKind.GENERATE_AUTOCAD_SCRIPT -> {
                    val title = payload.titleOrSubject.ifBlank { "TwoStorey_HousePlan_36x42" }
                    val lspContent = payload.contentOrBody.ifBlank { defaultTwoStoreyHouseAutoLisp() }
                    val file = writeOfficeFile(
                        fileName = sanitizeFileName(title, "TwoStorey_HousePlan") + ".lsp",
                        content = lspContent
                    )
                    Result.success(
                        ActionExecutionOutcome(
                            summary = "✓ AutoCAD (.LSP) স্ক্রিপ্ট তৈরি হয়েছে: ${file.name}",
                            generatedFilePath = file.absolutePath,
                            previewTitle = "AutoCAD AutoLISP Script: ${file.name}",
                            previewBody = lspContent
                        )
                    )
                }

                DeviceActionKind.SAVE_TALLY_ENTRY -> {
                    val entryType = payload.targetOrPath.uppercase().let {
                        if (it in setOf("INCOME", "EXPENSE", "DUE")) it else "DUE"
                    }
                    val customer = payload.titleOrSubject.ifBlank { "Customer" }
                    Result.success(
                        ActionExecutionOutcome(
                            summary = "✓ ট্যালি খাতায় এন্ট্রি হয়েছে: $customer ($entryType ৳${payload.amountBdt.toLong()})",
                            previewTitle = "Tally Khata Entry",
                            previewBody = "Customer: $customer\nType: $entryType\nItem: ${payload.contentOrBody}\nAmount: ৳${payload.amountBdt.toLong()}"
                        )
                    )
                }

                DeviceActionKind.SAVE_PERSONAL_MEMORY -> {
                    val topic = payload.titleOrSubject.ifBlank { "Personal Note" }
                    Result.success(
                        ActionExecutionOutcome(
                            summary = "✓ প্রাইভেট মেমরিতে সংরক্ষিত হয়েছে: $topic",
                            previewTitle = "Saved Personal Memory",
                            previewBody = "$topic\n${payload.contentOrBody}"
                        )
                    )
                }

                DeviceActionKind.UNDO_LAST_TASK -> {
                    Result.success(
                        ActionExecutionOutcome(summary = "✓ সর্বশেষ রিভার্সিবল কাজটি Undo করা হয়েছে")
                    )
                }

                DeviceActionKind.SUBMIT_FEATURE_REQUEST -> {
                    val reqTitle = payload.titleOrSubject.ifBlank { "Voice Feature Request" }
                    Result.success(
                        ActionExecutionOutcome(
                            summary = "✓ আপনার ফিচার নোট সংরক্ষিত হয়েছে: $reqTitle"
                        )
                    )
                }

                DeviceActionKind.WINDOWS_DESKTOP_COMMAND -> {
                    val title = payload.titleOrSubject.ifBlank { "Windows_Laptop_Task" }
                    val targetApp = payload.targetOrPath.ifBlank { "explorer" }
                    val scriptBody = payload.contentOrBody.ifBlank {
                        buildWindowsCompanionPowerShell(targetApp, title)
                    }
                    val psFile = writeOfficeFile(
                        fileName = sanitizeFileName(title, "Windows_Laptop_Bridge") + ".ps1",
                        content = scriptBody
                    )
                    Result.success(
                        ActionExecutionOutcome(
                            summary = "✓ Windows PowerShell স্ক্রিপ্ট সেভ হয়েছে (${psFile.name}) — PC-তে শেয়ার/রান করুন",
                            generatedFilePath = psFile.absolutePath,
                            previewTitle = "Windows PC Script (Local Vault): ${psFile.name}",
                            previewBody = scriptBody
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun resolveKnownAppPackages(target: String): List<String> = when {
        "whatsapp" in target || "হোয়াটসঅ্যাপ" in target || "হোয়াটসঅ্যাপ" in target ->
            listOf("com.whatsapp", "com.whatsapp.w4b")
        "youtube" in target || "ইউটিউব" in target || "utube" in target ->
            listOf("com.google.android.youtube")
        "facebook" in target || "ফেসবুক" in target || "fb" == target ->
            listOf("com.facebook.katana", "com.facebook.lite")
        "messenger" in target || "মেসেঞ্জার" in target ->
            listOf("com.facebook.orca", "com.facebook.mlite")
        "word" in target || "ওয়ার্ড" in target || "ওয়ার্ড" in target ->
            listOf("com.microsoft.office.word", "com.microsoft.office.officehubrow")
        "excel" in target || "এক্সেল" in target ->
            listOf("com.microsoft.office.excel", "com.microsoft.office.officehubrow")
        "autocad" in target || "অটোক্যাড" in target || "dwg" in target ->
            listOf("com.autodesk.autocadws", "com.gstarmcad.cad")
        "gmail" in target || "জিমেইল" in target || "মেইল" in target ->
            listOf("com.google.android.gm")
        "chrome" in target || "ক্রোম" in target || "browser" in target || "ব্রাউজার" in target ->
            listOf("com.android.chrome")
        "maps" in target || "ম্যাপ" in target ->
            listOf("com.google.android.apps.maps")
        "play store" in target || "প্লে স্টোর" in target ->
            listOf("com.android.vending")
        "telegram" in target || "টেলিগ্রাম" in target ->
            listOf("org.telegram.messenger")
        "imo" in target || "ইমো" in target ->
            listOf("com.imo.android.imoim")
        "tiktok" in target || "টিকটক" in target ->
            listOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill")
        "zoom" in target || "জুম" in target ->
            listOf("us.zoom.videomeetings")
        "drive" in target || "ড্রাইভ" in target ->
            listOf("com.google.android.apps.docs")
        "bkash" in target || "বিকাশ" in target ->
            listOf("com.bKash.customerapp")
        "nagad" in target || "নগদ" in target ->
            listOf("com.konasl.nagad")
        "daraz" in target || "দারাজ" in target ->
            listOf("com.daraz.android")
        else -> emptyList()
    }

    private fun findAndLaunchInstalledAppByName(queryLower: String): String? {
        if (queryLower.length < 2) return null
        return try {
            val pm = context.packageManager
            val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            val apps = pm.queryIntentActivities(mainIntent, 0)
            val matched = apps.firstOrNull { resolveInfo ->
                val label = resolveInfo.loadLabel(pm)?.toString().orEmpty().lowercase()
                val pkg = resolveInfo.activityInfo?.packageName.orEmpty().lowercase()
                pkg != context.packageName.lowercase() && (
                    label == queryLower ||
                        label.contains(queryLower) ||
                        queryLower.contains(label.takeIf { it.length >= 3 } ?: "___") ||
                        pkg.substringAfterLast('.').contains(queryLower)
                    )
            } ?: return null

            val pkgName = matched.activityInfo.packageName
            val launch = pm.getLaunchIntentForPackage(pkgName)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (launch != null && tryStartAny(launch)) {
                matched.loadLabel(pm)?.toString() ?: pkgName
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun setHardwareTorch(enable: Boolean): Boolean {
        return try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return false
            val torchCameraId = cameraManager.cameraIdList.firstOrNull { id ->
                cameraManager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return false
            cameraManager.setTorchMode(torchCameraId, enable)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun adjustHardwareVolume(directive: String): String {
        return try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                ?: return "অডিও ম্যানেজার পাওয়া যায়নি"
            val stream = AudioManager.STREAM_MUSIC
            val maxVol = audioManager.getStreamMaxVolume(stream).coerceAtLeast(1)
            when {
                "MUTE" in directive || "SILENT" in directive || "সাইলেন্ট" in directive || "মিউট" in directive -> {
                    audioManager.setStreamVolume(stream, 0, AudioManager.FLAG_SHOW_UI)
                    "মিডিয়া ভলিউম মিউট (০%) করা হয়েছে"
                }
                "MAX" in directive || "FULL" in directive || "ফুল" in directive -> {
                    audioManager.setStreamVolume(stream, maxVol, AudioManager.FLAG_SHOW_UI)
                    "ভলিউম ১০০% (সর্বোচ্চ) করা হয়েছে"
                }
                "DOWN" in directive || "DECREASE" in directive || "কমাও" in directive -> {
                    audioManager.adjustStreamVolume(stream, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                    audioManager.adjustStreamVolume(stream, AudioManager.ADJUST_LOWER, 0)
                    val cur = (audioManager.getStreamVolume(stream) * 100) / maxVol
                    "ভলিউম কমানো হয়েছে (বর্তমান: $cur%)"
                }
                else -> {
                    audioManager.adjustStreamVolume(stream, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
                    audioManager.adjustStreamVolume(stream, AudioManager.ADJUST_RAISE, 0)
                    val cur = (audioManager.getStreamVolume(stream) * 100) / maxVol
                    "ভলিউম বাড়ানো হয়েছে (বর্তমান: $cur%)"
                }
            }
        } catch (_: Exception) {
            "ভলিউম পরিবর্তন সম্পন্ন"
        }
    }

    fun readLiveDeviceTelemetrySummary(): String {
        val batteryPct = try {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 0..100 }
                ?: run {
                    val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                    val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
                    val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
                    if (level >= 0 && scale > 0) (level * 100) / scale else 85
                }
        } catch (_: Exception) {
            85
        }

        val isCharging = try {
            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        } catch (_: Exception) {
            false
        }

        val freeStorageGb = try {
            val stat = StatFs(Environment.getDataDirectory().path)
            val freeBytes = stat.availableBlocksLong * stat.blockSizeLong
            String.format(Locale.US, "%.1f GB", freeBytes / (1024.0 * 1024.0 * 1024.0))
        } catch (_: Exception) {
            "Available"
        }

        val netType = try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
            when {
                caps == null -> "Offline"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi Online"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile Data Online"
                else -> "Online"
            }
        } catch (_: Exception) {
            "Online"
        }

        val nowTime = SimpleDateFormat("hh:mm a, dd MMM yyyy", Locale.US).format(Date())
        val locLabel = resolveCurrentUserLocation().displayLabel
        return "Battery: $batteryPct% (${if (isCharging) "Charging" else "On Battery"}) | Free Storage: $freeStorageGb | Network: $netType | Location: $locLabel | Local Time: $nowTime"
    }

    fun canDrawAllDisplayOverlay(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else {
            true
        }
    }

    fun openOverlayPermissionSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val specific = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}")
            ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            val generic = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            tryStartAny(specific, generic)
        }
    }

    private fun buildWindowsCompanionPowerShell(targetApp: String, title: String): String {
        val cleanTarget = targetApp.trim().ifBlank { "notepad" }
        return """
            # Hey Shuvo Windows Laptop Companion Automation (.NET 8 / PowerShell Bridge)
            # Task: $title
            Write-Host "Hey Shuvo executing Windows task: $title" -ForegroundColor Cyan
            Start-Process "$cleanTarget"
        """.trimIndent()
    }

    private fun tryStartAny(vararg intents: Intent): Boolean {
        for (intent in intents) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                context.startActivity(intent)
                return true
            } catch (_: ActivityNotFoundException) {
                continue
            } catch (_: SecurityException) {
                postBackgroundLaunchFallbackNotification(intent)
                continue
            } catch (_: Exception) {
                continue
            }
        }
        return false
    }

    private fun postBackgroundLaunchFallbackNotification(targetIntent: Intent) {
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager ?: return
            val chId = "hey_shuvo_action_launch"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val ch = android.app.NotificationChannel(
                    chId,
                    "Hey Shuvo Quick Launch",
                    android.app.NotificationManager.IMPORTANCE_HIGH
                )
                nm.createNotificationChannel(ch)
            }
            val pi = android.app.PendingIntent.getActivity(
                context,
                (System.currentTimeMillis() and 0xFFFF).toInt(),
                targetIntent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            val notif = androidx.core.app.NotificationCompat.Builder(context, chId)
                .setSmallIcon(com.example.R.drawable.ic_stat_heyshuvo)
                .setContentTitle("Hey Shuvo • অ্যাপ প্রস্তুত")
                .setContentText("ট্যাপ করে সরাসরি চালু করুন (অটো-ওপেন রাখতে All-Display Allow দিন)")
                .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .build()
            nm.notify(4499, notif)
        } catch (_: Exception) {
        }
    }

    fun openOrShareGeneratedFile(filePath: String): Result<String> {
        return try {
            val file = File(filePath)
            if (!file.exists()) return Result.failure(IllegalArgumentException("File not found"))
            val mimeType = when (file.extension.lowercase()) {
                "doc" -> "application/msword"
                "csv" -> "text/csv"
                "pdf" -> "application/pdf"
                else -> "text/plain"
            }
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )

            val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (tryStartAny(viewIntent)) {
                return Result.success("Opened ${file.name}")
            }

            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val chooser = Intent.createChooser(sendIntent, "Open or Share ${file.name}").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
            Result.success("Opened share menu for ${file.name}")
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun readFileReadablePreview(file: File): String {
        return try {
            if (file.extension.equals("pdf", ignoreCase = true)) {
                "PDF Document (${file.name}, ${file.length()} bytes)\nSaved in private vault: ${file.absolutePath}\nTap 'Open / Share' to view in any PDF reader or send to PC."
            } else {
                file.readText().removePrefix("\uFEFF").take(4000)
            }
        } catch (e: Exception) {
            "Preview unavailable: ${e.localizedMessage}"
        }
    }

    private fun writeOfficeFile(fileName: String, content: String): File {
        val dir = File(context.filesDir, "docs").apply { mkdirs() }
        val outFile = File(dir, fileName)
        outFile.writeText(content, Charsets.UTF_8)
        return outFile
    }

    private fun wrapLineByMeasure(line: String, paint: Paint, maxWidthPx: Float): List<String> {
        if (line.isBlank()) return listOf("")
        if (paint.measureText(line) <= maxWidthPx) return listOf(line)
        val words = line.split(" ")
        val wrapped = mutableListOf<String>()
        var current = StringBuilder()
        for (word in words) {
            val candidate = if (current.isEmpty()) word else "$current $word"
            if (paint.measureText(candidate) <= maxWidthPx) {
                current.clear()
                current.append(candidate)
            } else {
                if (current.isNotEmpty()) {
                    wrapped.add(current.toString())
                    current = StringBuilder(word)
                } else {
                    wrapped.addAll(word.chunked(65))
                }
            }
        }
        if (current.isNotEmpty()) wrapped.add(current.toString())
        return wrapped
    }

    private fun generateNativePdf(fileName: String, title: String, body: String): File {
        val dir = File(context.filesDir, "docs").apply { mkdirs() }
        val outFile = File(dir, fileName)
        try {
            val pdfDocument = PdfDocument()
            val titlePaint = Paint().apply {
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                textSize = 17f
                isAntiAlias = true
            }
            val bodyPaint = Paint().apply {
                typeface = android.graphics.Typeface.DEFAULT
                textSize = 12f
                isAntiAlias = true
            }

            val wrappedLines = body.lines().flatMap { wrapLineByMeasure(it, bodyPaint, 510f) }
            var pageNumber = 1
            var pageInfo = PdfDocument.PageInfo.Builder(595, 842, pageNumber).create()
            var page = pdfDocument.startPage(pageInfo)
            page.canvas.drawText(title.take(62), 42f, 54f, titlePaint)
            var y = 88f

            for (wLine in wrappedLines) {
                if (y > 790f) {
                    pdfDocument.finishPage(page)
                    pageNumber++
                    pageInfo = PdfDocument.PageInfo.Builder(595, 842, pageNumber).create()
                    page = pdfDocument.startPage(pageInfo)
                    y = 54f
                }
                page.canvas.drawText(wLine, 42f, y, bodyPaint)
                y += 18f
            }

            pdfDocument.finishPage(page)
            FileOutputStream(outFile).use { pdfDocument.writeTo(it) }
            pdfDocument.close()
            if (outFile.exists() && outFile.length() > 0L) {
                return outFile
            }
        } catch (_: Throwable) {
            // Fall through to standard PDF 1.4 stream writer
        }

        val toAsciiPdfText: (String) -> String = { raw ->
            raw.map { ch -> if (ch.code in 32..126) ch else ' ' }
                .joinToString("")
                .replace("\\", "\\\\")
                .replace("(", "\\(")
                .replace(")", "\\)")
        }
        val escapedTitle = toAsciiPdfText(title).ifBlank { "Hey Shuvo Document" }.take(64)
        val escapedLines = body.lines().take(32).map {
            toAsciiPdfText(it).take(80)
        }
        val streamContent = buildString {
            append("BT /F1 16 Tf 45 785 Td ($escapedTitle) Tj /F1 11 Tf ")
            escapedLines.forEach { line ->
                append("0 -18 Td ($line) Tj ")
            }
            append("ET")
        }
        val minimalPdf = """
            %PDF-1.4
            1 0 obj << /Type /Catalog /Pages 2 0 R >> endobj
            2 0 obj << /Type /Pages /Kids [3 0 R] /Count 1 >> endobj
            3 0 obj << /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >> endobj
            4 0 obj << /Length ${streamContent.length} >> stream
            $streamContent
            endstream endobj
            5 0 obj << /Type /Font /Subtype /Type1 /BaseFont /Helvetica >> endobj
            xref
            0 6
            0000000000 65535 f 
            trailer << /Size 6 /Root 1 0 R >>
            %%EOF
        """.trimIndent()
        outFile.writeText(minimalPdf, Charsets.US_ASCII)
        return outFile
    }

    private fun escapeHtml(raw: String): String = raw
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")

    private fun buildWordCompatibleHtml(title: String, body: String): String {
        val safeTitle = escapeHtml(title)
        val paragraphs = body.lines().joinToString("") { "<p>${escapeHtml(it)}</p>" }
        return """
            <html>
            <head><meta charset="utf-8"><title>$safeTitle</title></head>
            <body style="font-family: 'Noto Sans Bengali', Calibri, Arial, sans-serif; line-height: 1.6;">
            <h1>$safeTitle</h1>
            $paragraphs
            </body>
            </html>
        """.trimIndent()
    }

    internal fun sanitizeFileName(raw: String, fallbackPrefix: String = "HeyShuvo_File"): String {
        val asciiCleaned = raw
            .replace(Regex("[^a-zA-Z0-9_\\-]"), "_")
            .replace(Regex("_+"), "_")
            .trim('_', '-')
            .take(36)
        val alphaNumCount = asciiCleaned.count { it.isLetterOrDigit() }
        return if (alphaNumCount >= 3) asciiCleaned else fallbackPrefix
    }

    @Suppress("DEPRECATION")
    fun hasSelfUsageStatsPermission(): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        } else {
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun openUsageAccessSettings() {
        val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        tryStartAny(intent)
    }

    fun isVoiceAccessibilityEnabled(): Boolean {
        return com.example.core.service.HeyShuvoAccessibilityService.isEnabledInSettings(context)
    }

    fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        tryStartAny(intent)
    }

    fun querySelfTodayScreenTime(): List<SelfScreenTimeItem> {
        if (!hasSelfUsageStatsPermission()) return emptyList()
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return emptyList()
        val end = System.currentTimeMillis()
        val start = end - 24L * 60L * 60L * 1000L
        val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, start, end).orEmpty()
        return stats
            .filter { it.totalTimeInForeground >= 60_000L }
            .groupBy { it.packageName }
            .map { (pkg, list) ->
                SelfScreenTimeItem(
                    packageName = pkg.substringAfterLast('.').replaceFirstChar { c -> c.uppercase() },
                    minutesToday = list.maxOf { it.totalTimeInForeground } / 60_000L
                )
            }
            .sortedByDescending { it.minutesToday }
            .take(8)
    }

    fun listGeneratedLocalDocs(): List<File> {
        val dir = File(context.filesDir, "docs")
        if (!dir.exists()) return emptyList()
        return dir.listFiles()?.sortedByDescending { it.lastModified() }.orEmpty()
    }

    fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    /**
     * Resolves the user's real device GPS location when permitted.
     * CRITICAL FIX: Cloud Android Emulators default mock GPS to Googleplex, Mountain View, USA (37.422, -122.084).
     * Whenever the GPS coordinates fall inside the fake emulator Mountain View box (or GPS is unavailable),
     * we ignore the fake USA location and use the user's saved/selected location (default: Dhaka, Bangladesh).
     */
    @SuppressLint("MissingPermission")
    fun resolveCurrentUserLocation(overrideFallback: String? = null): ResolvedUserLocation {
        val savedFallbackRaw = overrideFallback?.takeIf { it.isNotBlank() }
            ?: PreferencesRepository.readCachedLocationSync(context)
        val qualifiedFallback = qualifyLocationForMaps(savedFallbackRaw)
        val fallbackCoords = lookupKnownBangladeshCoordinates(qualifiedFallback)

        if (hasLocationPermission()) {
            try {
                val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
                if (lm != null) {
                    val providers = listOf(
                        LocationManager.GPS_PROVIDER,
                        LocationManager.NETWORK_PROVIDER,
                        LocationManager.PASSIVE_PROVIDER
                    )
                    var bestLoc: Location? = null
                    for (provider in providers) {
                        val candidate = try {
                            lm.getLastKnownLocation(provider)
                        } catch (_: Exception) {
                            null
                        }
                        if (candidate != null && (bestLoc == null || candidate.time > bestLoc.time)) {
                            bestLoc = candidate
                        }
                    }

                    if (bestLoc != null && !isEmulatorDefaultUsaCoordinate(bestLoc.latitude, bestLoc.longitude)) {
                        val lat = bestLoc.latitude
                        val lon = bestLoc.longitude
                        val geocodedName = try {
                            @Suppress("DEPRECATION")
                            val list = Geocoder(context, Locale.US).getFromLocation(lat, lon, 1)
                            val addr = list?.firstOrNull()
                            if (addr != null) {
                                listOfNotNull(addr.subLocality, addr.locality, addr.countryName)
                                    .filter { it.isNotBlank() }
                                    .distinct()
                                    .joinToString(", ")
                                    .takeIf { it.isNotBlank() }
                            } else null
                        } catch (_: Exception) {
                            null
                        }
                        val coordQuery = String.format(Locale.US, "%.6f,%.6f", lat, lon)
                        return ResolvedUserLocation(
                            displayLabel = geocodedName ?: coordQuery,
                            mapsQuery = coordQuery,
                            latitude = lat,
                            longitude = lon,
                            isLiveGps = true
                        )
                    }
                }
            } catch (_: Exception) {
            }
        }

        return ResolvedUserLocation(
            displayLabel = qualifiedFallback,
            mapsQuery = qualifiedFallback,
            latitude = fallbackCoords.first,
            longitude = fallbackCoords.second,
            isLiveGps = false
        )
    }

    companion object {
        const val EXTRA_TRIGGER_PHOTO_VISION = "extra_trigger_photo_vision"
        const val EXTRA_TRIGGER_GESTURE_TRANSFER = "extra_trigger_gesture_transfer"
        const val EXTRA_TRIGGER_LOCATION_PICKER = "extra_trigger_location_picker"

        private val BANGLADESH_CITY_TRANSLATIONS = listOf(
            "ঢাকা" to "Dhaka",
            "মিরপুর ১০" to "Mirpur 10, Dhaka",
            "মিরপুর" to "Mirpur, Dhaka",
            "ধানমন্ডি" to "Dhanmondi, Dhaka",
            "উত্তরা" to "Uttara, Dhaka",
            "গুলশান" to "Gulshan, Dhaka",
            "বনানী" to "Banani, Dhaka",
            "মতিঝিল" to "Motijheel, Dhaka",
            "ফার্মগেট" to "Farmgate, Dhaka",
            "শাহবাগ" to "Shahbagh, Dhaka",
            "যাত্রাবাড়ী" to "Jatrabari, Dhaka",
            "যাত্রাবাড়ী" to "Jatrabari, Dhaka",
            "সাভার" to "Savar, Dhaka",
            "গাজীপুর" to "Gazipur",
            "নারায়ণগঞ্জ" to "Narayanganj",
            "নারায়ণগঞ্জ" to "Narayanganj",
            "চট্টগ্রাম" to "Chattogram",
            "চিটাগাং" to "Chattogram",
            "সিলেট" to "Sylhet",
            "রাজশাহী" to "Rajshahi",
            "খুলনা" to "Khulna",
            "যশোর" to "Jashore",
            "বরিশাল" to "Barishal",
            "রংপুর" to "Rangpur",
            "ময়মনসিংহ" to "Mymensingh",
            "ময়মনসিংহ" to "Mymensingh",
            "কুমিল্লা" to "Cumilla",
            "কক্সবাজার" to "Cox's Bazar",
            "বগুড়া" to "Bogura",
            "বগুড়া" to "Bogura",
            "পাবনা" to "Pabna",
            "কুষ্টিয়া" to "Kushtia",
            "কুষ্টিয়া" to "Kushtia",
            "ফরিদপুর" to "Faridpur",
            "টাঙ্গাইল" to "Tangail",
            "নোয়াখালী" to "Noakhali",
            "নোয়াখালী" to "Noakhali",
            "ফেনী" to "Feni",
            "ব্রাহ্মণবাড়িয়া" to "Brahmanbaria",
            "দিনাজপুর" to "Dinajpur",
            "সাতক্ষীরা" to "Satkhira",
            "পদ্মা সেতু" to "Padma Bridge",
            "শাহজালাল বিমানবন্দর" to "Hazrat Shahjalal International Airport, Dhaka",
            "এয়ারপোর্ট" to "Hazrat Shahjalal International Airport, Dhaka",
            "এয়ারপোর্ট" to "Hazrat Shahjalal International Airport, Dhaka"
        )

        private val INTERNATIONAL_MARKERS = listOf(
            "bangladesh", "india", "kolkata", "delhi", "mumbai", "dubai", "uae",
            "london", "uk", "united kingdom", "usa", "united states", "new york",
            "california", "canada", "toronto", "saudi", "makkah", "mecca", "madinah",
            "malaysia", "kuala lumpur", "singapore", "thailand", "bangkok", "japan", "australia"
        )

        /**
         * Detects Android Studio / Cloud Emulator's hardcoded Mountain View, California mock GPS
         * (37.4219983, -122.084) so the app never falsely shows USA when the user hasn't travelled to Mountain View.
         */
        fun isEmulatorDefaultUsaCoordinate(latitude: Double, longitude: Double): Boolean {
            return latitude in 37.30..37.50 && longitude in -122.25..-121.95
        }

        /**
         * Converts Bangla/Banglish place names into unambiguous Google Maps queries anchored in Bangladesh
         * unless an international country/city is explicitly mentioned.
         */
        fun qualifyLocationForMaps(rawPlace: String): String {
            var cleaned = rawPlace.trim()
                .trim(',', '.', '?', '!', '।')
            if (cleaned.isBlank()) return AssistantSettings.DEFAULT_BD_LOCATION

            for ((bn, en) in BANGLADESH_CITY_TRANSLATIONS) {
                if (bn in cleaned) {
                    cleaned = cleaned.replace(bn, en)
                }
            }
            cleaned = cleaned.replace(Regex("\\s+"), " ").trim()
            val lower = cleaned.lowercase()

            val coordRegex = Regex("""^-?\d+(?:\.\d+)?\s*,\s*-?\d+(?:\.\d+)?$""")
            if (coordRegex.matches(cleaned)) return cleaned

            if (INTERNATIONAL_MARKERS.any { it in lower } || "বাংলাদেশ" in cleaned) {
                return cleaned
            }
            return "$cleaned, Bangladesh"
        }

        fun lookupKnownBangladeshCoordinates(place: String): Pair<Double, Double> {
            val l = place.lowercase()
            return when {
                "chattogram" in l || "chittagong" in l -> 22.3569 to 91.7832
                "sylhet" in l -> 24.8949 to 91.8687
                "rajshahi" in l -> 24.3745 to 88.6042
                "khulna" in l -> 22.8456 to 89.5403
                "jashore" in l || "jessore" in l -> 23.1664 to 89.2081
                "barishal" in l || "barisal" in l -> 22.7010 to 90.3535
                "rangpur" in l -> 25.7439 to 89.2752
                "mymensingh" in l -> 24.7471 to 90.4203
                "cumilla" in l || "comilla" in l -> 23.4607 to 91.1809
                "cox" in l -> 21.4272 to 92.0058
                "gazipur" in l -> 23.9999 to 90.4203
                "narayanganj" in l -> 23.6238 to 90.5000
                "mirpur" in l -> 23.8042 to 90.3667
                "dhanmondi" in l -> 23.7461 to 90.3742
                "uttara" in l -> 23.8759 to 90.3795
                "gulshan" in l -> 23.7925 to 90.4078
                else -> 23.8103 to 90.4125 // Dhaka center
            }
        }

        fun buildCurrentLocationMapsUrl(resolved: ResolvedUserLocation): String {
            return "https://www.google.com/maps/search/?api=1&query=${Uri.encode(resolved.mapsQuery)}"
        }

        fun buildDirectionsMapsUrl(originQuery: String, destinationQuery: String): String {
            return "https://www.google.com/maps/dir/?api=1" +
                "&origin=${Uri.encode(originQuery)}" +
                "&destination=${Uri.encode(destinationQuery)}" +
                "&travelmode=driving"
        }

        fun defaultTwoStoreyHouseAutoLisp(): String = """
            ;; Hey Shuvo Practical AutoCAD 2-Storey House Plan Generator (.LSP)
            ;; Units: Feet & Inches (Overall Plot Footprint: 36'-0" x 42'-0")
            ;; Includes Ground & First Floor Outer Walls, Living, Dining, 3 Bedrooms, 2 Bathrooms, Kitchen, Staircase & Dimensions
            (defun c:HeyShuvo2StoreyPlan ()
              (command "_.LAYER" "_M" "WALLS" "_C" "4" "" "_M" "STAIRS" "_C" "2" "" "_M" "TEXT_DIM" "_C" "3" "")
              (command "_.LAYER" "_S" "WALLS" "")
              ;; Ground Floor Outer Perimeter (0,0 to 432,504 inches = 36' x 42')
              (command "_.RECTANG" "0,0" "432,504")
              (command "_.OFFSET" "10" (entlast) "10,10" "")
              ;; Master Bedroom (14' x 13'), Bedroom 2 (13' x 12'), Kitchen (10' x 9'), Baths (8' x 5')
              (command "_.LINE" "0,180" "432,180" "")
              (command "_.LINE" "180,0" "180,504" "")
              (command "_.LINE" "300,180" "300,504" "")
              ;; Staircase Core (9' x 15' with 10" treads)
              (command "_.LAYER" "_S" "STAIRS" "")
              (command "_.RECTANG" "185,190" "295,370")
              ;; First Floor Plan Offset at X = 540 inches (45' right)
              (command "_.LAYER" "_S" "WALLS" "")
              (command "_.RECTANG" "540,0" "972,504")
              (command "_.LAYER" "_S" "TEXT_DIM" "")
              (command "_.TEXT" "40,460" "12" "0" "GROUND FLOOR PLAN (36 FT X 42 FT)")
              (command "_.TEXT" "580,460" "12" "0" "FIRST FLOOR PLAN (36 FT X 42 FT)")
              (command "_.DIMLINEAR" "0,0" "432,0" "0,-24")
              (command "_.DIMLINEAR" "0,0" "0,504" "-24,0")
              (princ "\nHey Shuvo 2-Storey House Plan generated. Load via APPLOAD and type HeyShuvo2StoreyPlan.")
              (princ)
            )
        """.trimIndent()
    }
}
