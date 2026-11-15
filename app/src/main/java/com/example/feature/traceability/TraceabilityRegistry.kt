package com.example.feature.traceability

enum class ImplementationStatus(val badge: String) {
    FULLY_IMPLEMENTED("Fully Implemented"),
    PROTOTYPE_ONLY("Prototype Only"),
    REQUIRES_NATIVE_ANDROID("Requires Native Android Development"),
    REQUIRES_WINDOWS_INTEGRATION("Requires Windows Integration"),
    REQUIRES_EXTERNAL_API("Requires External API"),
    NOT_YET_IMPLEMENTED("Not Yet Implemented")
}

data class TraceabilityItem(
    val sectionNumber: Int,
    val domain: String,
    val featureTitle: String,
    val stagePlanned: Int,
    val status: ImplementationStatus,
    val verificationNote: String
)

object TraceabilityRegistry {
    val allItems: List<TraceabilityItem> = listOf(
        TraceabilityItem(
            1, "Core Identity", "Hey Shuvo Voice-First Personal & Desktop Assistant", 1,
            ImplementationStatus.FULLY_IMPLEMENTED,
            "Strict identity reply ('I am Hey Shuvo, your digital assistant'), Open Preview testing active + optional Google Sign-In."
        ),
        TraceabilityItem(
            2, "Access Control", "Open Preview Testing + Optional Google Cloud Sync", 1,
            ImplementationStatus.FULLY_IMPLEMENTED,
            "Open access enabled for rapid checking; Google Sign-In ready in Vault/Master whenever cloud governance is needed."
        ),
        TraceabilityItem(
            3, "Language & Dialect", "Bangla, English, Banglish + Sylheti, Chittagonian, Noakhali", 1,
            ImplementationStatus.FULLY_IMPLEMENTED,
            "Automatic typo/spelling tolerance and natural spoken/text replies in the user's language."
        ),
        TraceabilityItem(
            4, "Voice Control Engine", "Phone & Software Voice Control (Maps, Apps, Calls, SMS, WhatsApp, Email, Alarms)", 1,
            ImplementationStatus.FULLY_IMPLEMENTED,
            "Structured Voice Action Executor launches real Android Maps navigation, apps, alarms, calendar, and message drafts with user permission."
        ),
        TraceabilityItem(
            5, "Office & AutoCAD", "Voice-Driven Word (.doc), Excel (.csv Formulas), PDF & AutoCAD 2-Storey .LSP Generator", 1,
            ImplementationStatus.FULLY_IMPLEMENTED,
            "Generates real sharable Word, Excel, PDF, and AutoCAD AutoLISP (.lsp) files directly from voice commands."
        ),
        TraceabilityItem(
            6, "Windows Companion", "Windows Laptop C#/.NET 8 Companion Bridge & AutoCAD/Office Automation", 2,
            ImplementationStatus.REQUIRES_WINDOWS_INTEGRATION,
            "Generates JSON/AutoLISP desktop task packets on Android + complete C#/.NET local companion blueprint."
        ),
        TraceabilityItem(
            7, "Digital Wellbeing", "Self-Only Consented Screen-Time & Permission Center", 1,
            ImplementationStatus.FULLY_IMPLEMENTED,
            "Displays user's own screen-time breakdown via UsageStatsManager after explicit permission (never sent to Master App)."
        ),
        TraceabilityItem(
            8, "Special User Modes", "Children Safe Mode (PIN Gate) & Elderly Mode (Large Text + Slow TTS)", 1,
            ImplementationStatus.FULLY_IMPLEMENTED,
            "Live typography scaling, 0.82x clear TTS rate, and Parent PIN protection."
        ),
        TraceabilityItem(
            9, "Privacy & Memory", "On-Device Memory Control, Tally Khata & Reversible Task History", 1,
            ImplementationStatus.FULLY_IMPLEMENTED,
            "Stored strictly in on-device Room SQLite; zero exposure to Master App or other users."
        ),
        TraceabilityItem(
            10, "Master App", "Privacy-Safe Owner Governance, Skill Manager & Emergency Kill Switch", 1,
            ImplementationStatus.FULLY_IMPLEMENTED,
            "Manages invitations, approvals, Feature Requests, modular Skills, and Audit Logs with zero customer PII/spyware."
        )
    )
}
