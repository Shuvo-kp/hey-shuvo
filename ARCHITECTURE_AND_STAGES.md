# Hey Shuvo — Master Architecture, Open Testing Mode & Universal Voice Software Control

## 1. Multi-Platform System Architecture

1. **Customer App (Android Kotlin / Jetpack Compose — Voice-First Control)**
   - **Open Testing Mode (Current Default)**: Opens directly without a mandatory Google Sign-In lock screen so all Customer & Master features can be tested immediately. Optional Google Sign-In (`CredentialManager` + `GetSignInWithGoogleOption`) can be connected anytime from **Vault -> HUD, Modes & Auth** or **Master** to enable live cloud sync.
   - **100% Voice-Driven Technical & Daily Knowledge**: Standalone calculator UI removed; all technical, scientific, daily life, Bangladesh NID/Passport/Driving Licence, and route queries are answered directly via natural voice/chat in Bangla, English, Banglish, Sylheti, Chittagonian, and Noakhali.
   - **End-to-End Phone & Software Voice Control (With User Permission)**:
     - **Google Maps & Navigation**: Direct turn-by-turn navigation intents.
     - **Phone Apps & Communication**: Launches WhatsApp, Messenger, Facebook, YouTube, Word, Excel; prepares Phone Dialer, SMS, WhatsApp, and Email drafts; sets system Alarms, Timers, and Calendar events.
     - **Office & Engineering File Generation**: Voice-generates real Microsoft Word (`.doc`), Excel (`.csv` with live `=SUM(...)` / multiplication formulas), native Android `PdfDocument` (`.pdf`), and AutoCAD 2-Storey House Plan AutoLISP (`.lsp`) scripts, opening/sharing via `FileProvider`.
     - **Self-Only Digital Wellbeing (`UsageStatsManager`)**: Customers can inspect their own daily app usage (YouTube, Facebook, etc.) locally on their own phone after granting Android Usage Access.

2. **Master App (Owner Governance Portal — Privacy-Compliant)**
   - **Zero Surveillance / Zero Personal Data Exposure**: Never exposes personal emails or accesses any customer's private calls, SMS, contacts, personal files, or social media browsing.
   - **Owner Controls**: Manages private circle invitations, approves/suspends accounts, triages customer Feature Requests, generates AI-assisted modular Skills, controls staged rollout (`5%`, `25%`, `100%`), triggers the Emergency Remote Kill Switch, and maintains immutable Audit Logs.

3. **Windows Laptop Companion (`/windows-companion/HeyShuvoDesktopCompanion.cs` — .NET 8 / C# 12)**
   - Local loopback host (`127.0.0.1:49550`) that executes voice actions on Windows 10/11: Google Maps routes, Microsoft Word (`.doc`), Excel formula sheets (`.csv`), AutoCAD 2-Storey House Plan AutoLISP (`.lsp`) scripts, and allow-listed desktop apps (`winword.exe`, `excel.exe`, `acad.exe`).
