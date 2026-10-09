# 📋 Wi-Fi Clipboard Sync (Windows ↔ Android)

A zero-friction, bidirectional clipboard synchronization tool between your Windows laptop and Android smartphone over local Wi-Fi.

Whenever both devices are connected to the same Wi-Fi network:
* **Copy on Phone** $\to$ Instantly available in Windows clipboard (`Ctrl + V`).
* **Copy on Windows** $\to$ Instantly available in Android clipboard (paste anywhere).
* **Zero-Hassle**: No manual IP entry, no QR code scanning every time. Devices discover each other automatically via UDP broadcast beacons.
* **0% Idle CPU**: Windows uses native Win32 `AddClipboardFormatListener` (event-driven, no battery-draining polling loops).
* **True Android Background Sync**: Uses a lightweight Android `AccessibilityService` and foreground service to sync in the background even when the phone is locked or other apps are open.
* **Loop & Echo Prevention**: Computes SHA-256 hashes of clipboard payloads to prevent ping-pong copy loops.

---

## 📁 Project Architecture

```
wifi-clipboard-sync/
├── windows/
│   ├── WiFiClipboardSync.exe # Standalone 1-Click Executable (NO Python required for friends!)
│   ├── app.py                # Main desktop GUI (CustomTkinter) + Orchestrator
│   ├── clipboard_engine.py   # Win32 AddClipboardFormatListener implementation
│   ├── server.py             # WebSocket server & UDP discovery broadcast
│   ├── history_manager.py    # Persistent clipboard history manager
│   ├── test_windows_sync.py  # Automated unit and integration test suite
│   ├── requirements.txt      # pywin32, websockets, customtkinter, pillow
│   └── run.bat               # 1-Click Windows launcher batch script (for Python developers)
└── android/
    ├── app-debug.apk         # Pre-built ready-to-install Android APK (universal sync)
    ├── install_apk.bat       # 1-Click ADB installer script
    ├── app/
    │   ├── src/main/
    │   │   ├── AndroidManifest.xml
    │   │   ├── java/com/clipboardsync/
    │   │   │   ├── network/
    │   │   │   │   ├── UdpDiscoveryManager.kt   # Wi-Fi auto-discovery
    │   │   │   │   └── WebSocketManager.kt      # Real-time WebSocket client
    │   │   │   ├── service/
    │   │   │   │   ├── ClipboardAccessibilityService.kt # Background clipboard hook
    │   │   │   │   ├── ClipboardCaptureActivity.kt      # Zero-flicker OS clipboard capture
    │   │   │   │   ├── SyncService.kt           # Sticky foreground service
    │   │   │   │   └── BootReceiver.kt          # Auto-start on boot & package update
    │   │   │   └── ui/
    │   │   │       └── MainActivity.kt          # Setup and status dashboard
    │   │   └── res/
    │   └── build.gradle.kts
    └── gradlew.bat / gradlew
```

---

## 🚀 Quick Start (Easiest Way for Friends / Users)

### 💻 Step 1: Start Windows App
> [!TIP]
> **No Python installation needed!** Your friends don't need to install Python or run any terminal commands.

1. Open the `windows/` folder and double-click:
   ```cmd
   WiFiClipboardSync.exe
   ```
   *(Developers can alternatively run `windows/run.bat` or `python app.py`)*
2. The modern dark-themed dashboard will open and display:
   * **Local IP**: (e.g. `192.168.1.105:52526`)
   * **Status**: `🟡 Searching on Wi-Fi...` (broadcasting UDP beacon every 2s)

---

### 📱 Step 2: Install and Setup Android App
1. Install the APK on your phone:
   * Transfer `android/app-debug.apk` directly to your phone (via Telegram, Google Drive, or USB) and tap to install.
   *(Or connect phone via USB and run `android/install_apk.bat`)*
2. Open **Wi-Fi Clipboard Sync** on your phone and enable the 3 permissions:
   * **Accessibility Service**: Tap **"Enable in Accessibility Settings"** $\to$ Select **Wi-Fi Clipboard Sync** $\to$ Turn **ON**. *(Reads user copy actions in background)*.
   * **Display Over Other Apps**: Tap **"Allow Display Over Other Apps"** $\to$ Turn **ON**. *(Essential for universal copy sync across WhatsApp, Facebook, Instagram, and background apps on Android 10+)*.
   * **Battery Optimization**: Tap **"Allow Unrestricted Background"** $\to$ Allow. *(Prevents Android from pausing background sync when screen is off)*.
3. Within 1–2 seconds, both devices will auto-connect:
   * Laptop badge turns **🟢 Phone Connected (192.168.1.xxx)**.
   * Phone status turns **🟢 Connected to Laptop**.

---

### ⚡ Step 3: Enjoy Zero-Friction Universal Sync!
* **Copy on Phone**: Copy any message in WhatsApp, any link in Facebook, or any text in Chrome $\to$ press `Ctrl + V` on your PC!
* **Copy on Laptop**: Press `Ctrl + C` on any text on your laptop $\to$ paste directly on your phone!
* **Clipboard History**: The Windows app keeps recent clipboard history with 1-click copy buttons and character counts.

---

## 🔄 Automatic In-App Updates & CI/CD Pipeline

Whenever updates or bug fixes are published, both the Windows and Android apps automatically detect and install the newest version without users needing to manually find or download files.

### 📱 Android In-App Auto-Updater
* On app startup, the Android app checks the latest release manifest (`version.json` / GitHub Releases API).
* When an update is detected, an in-app update banner appears with release notes.
* Tapping **"Update Now"** downloads the latest APK in the background with real-time percentage progress.
* Once downloaded, the app triggers Android's secure `FileProvider` package installer intent (`ACTION_VIEW`).
* Users can also tap **"🔄 Check for App Updates"** anytime.

### 💻 Windows Auto-Updater
* On desktop startup, a background worker checks GitHub for newer releases.
* When an update is available, it streams `WiFiClipboardSync_new.exe` to the local temporary directory.
* A notification banner and dialog ask the user: *"Update downloaded. Restart now to apply update?"*
* On confirmation, a detached batch script waits for the existing process to close, swaps the binary, and immediately restarts `WiFiClipboardSync.exe`.
* Users can also click **"Check Updates"** on the dashboard anytime.

### 🚀 How to Publish a New Release
1. Run `push_to_github.bat`:
   * Commits all changes and pushes to `main`.
   * When prompted, enter a release tag like `v1.0.1` (or push tag via `git tag v1.0.1 && git push origin v1.0.1`).
2. GitHub Actions (`.github/workflows/release.yml`) automatically triggers to:
   * Compile the Windows standalone executable (`WiFiClipboardSync.exe`) via PyInstaller.
   * Build the Android APK (`app-debug.apk`) via Gradle.
   * Generate the `version.json` release manifest with download URLs and changelog.
   * Publish a GitHub Release for `hasan-circuito/WiFi-clipboard-sync-`.
3. All existing Windows and Android apps will auto-update on their next run!

---

## 🧪 Automated Verification

### Windows Test Suite:
```cmd
cd windows
.\.venv\Scripts\python -m unittest test_windows_sync.py
```
**Tests verified (21/21 passing):**
* ✅ Win32 native clipboard format listener and event pump
* ✅ Echo suppression and SHA-256 loop prevention
* ✅ UDP discovery beacon broadcasting and query response
* ✅ WebSocket bidirectional exchange and client handshake
* ✅ In-memory and disk persistence for clipboard history
* ✅ Full end-to-end simulated phone-to-PC sync without loop bounces
* ✅ Auto-updater semantic version comparison and tuple parsing
* ✅ Auto-updater manifest parsing (`version.json` and GitHub API schemas)
* ✅ Standalone process restart & batch script generation
* ✅ Robust updater batch execution with path spaces support
* ✅ Offline network error handling & up-to-date validation

### Android Test Suite:
```cmd
cd android
.\gradlew.bat testDebugUnitTest
```
**Tests verified (18/18 passing):**
* ✅ SHA-256 clipboard hashing and Bengali unicode integrity
* ✅ Bidirectional selection index extraction & cursor suppression
* ✅ Multilingual copy detection keywords (Bengali, Hindi, Spanish, etc.)
* ✅ Floating toolbar & keyboard package disambiguation
* ✅ Echo suppression & duplicate debounce algorithms
* ✅ Auto-updater semantic version comparison & version code precedence
* ✅ Equal version / versionCode loop prevention
* ✅ Auto-updater JSON parsing for GitHub releases & custom schemas
