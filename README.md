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

## 🧪 Automated Verification

Run the full automated test suite on Windows:
```cmd
cd windows
.\.venv\Scripts\python -m unittest test_windows_sync.py
```
**Tests verified:**
* ✅ Win32 native clipboard format listener and event pump
* ✅ Echo suppression and SHA-256 loop prevention
* ✅ UDP discovery beacon broadcasting and query response
* ✅ WebSocket bidirectional exchange and client handshake
* ✅ In-memory and disk persistence for clipboard history
* ✅ Full end-to-end simulated phone-to-PC sync without loop bounces
