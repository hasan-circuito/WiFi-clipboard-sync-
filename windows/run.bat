@echo off
title Wi-Fi Clipboard Sync
cd /d "%~dp0"

echo ========================================================
echo       Wi-Fi Clipboard Sync (Windows App)
echo ========================================================
echo.

if not exist ".venv\Scripts\python.exe" (
    echo [INFO] First-time setup: Creating Python virtual environment...
    python -m venv .venv
    echo [INFO] Installing required dependencies...
    .\.venv\Scripts\pip install -r requirements.txt
    echo [INFO] Setup complete!
    echo.
)

echo [INFO] Starting Wi-Fi Clipboard Sync Desktop App...
start "" ".\.venv\Scripts\python.exe" "app.py"
echo [INFO] App launched. You can close this terminal window.
exit
