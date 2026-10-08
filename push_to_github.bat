@echo off
title Push Project to GitHub
cd /d "%~dp0"

echo ========================================================
echo       Upload Wi-Fi Clipboard Sync to GitHub
echo ========================================================
echo.

echo [1/3] Checking Git status...
git status -s

echo.
echo [2/3] Adding files (excluding .venv and build caches via .gitignore)...
git add .
git commit -m "Complete working build: Bi-directional Wi-Fi Clipboard Sync"

echo.
echo [3/3] Pushing to GitHub (https://github.com/hasan-circuito/WiFi-clipboard-sync-)...
git branch -M main
git push -u origin main --force

if %ERRORLEVEL% equ 0 (
    echo.
    echo ========================================================
    echo  [SUCCESS] Successfully uploaded to GitHub!
    echo  View at: https://github.com/hasan-circuito/WiFi-clipboard-sync-
    echo ========================================================
) else (
    echo.
    echo [NOTE] If a browser window opened, please click "Authorize" to sign in to GitHub.
)

pause
