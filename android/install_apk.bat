@echo off
title Install Android APK to Phone
cd /d "%~dp0"

echo ========================================================
echo       Wi-Fi Clipboard Sync - Android APK Installer
echo ========================================================
echo.

set ADB="C:\Users\Hasan Mahmud\AppData\Local\Android\Sdk\platform-tools\adb.exe"

if not exist %ADB% (
    set ADB=adb
)

echo [1/3] Checking connected Android devices via ADB...
%ADB% devices

echo.
echo [2/3] Installing app-debug.apk to connected phone...
%ADB% install -r app-debug.apk

if %ERRORLEVEL% equ 0 (
    echo.
    echo [SUCCESS] APK installed successfully!
    echo Launch "Wi-Fi Clipboard Sync" on your phone.
) else (
    echo.
    echo [INFO] If your phone is not connected via USB, you can simply:
    echo 1. Send "app-debug.apk" to your phone via USB cable, Google Drive, or Bluetooth.
    echo 2. Tap to install the APK on your phone.
    echo 3. Enable the Accessibility Service in Android Settings.
)

pause
