@echo off
setlocal enabledelayedexpansion
title Push Project to GitHub and Trigger Auto-Release
cd /d "%~dp0"

echo ========================================================
echo       Upload Wi-Fi Clipboard Sync to GitHub
echo ========================================================
echo.

echo [1/4] Checking Git status...
git status -s

echo.
echo ========================================================
echo       Release and Auto-Update Configuration
echo ========================================================
echo Pushing a tag (e.g. v1.0.1) triggers GitHub Actions to:
echo  1. Automatically compile Windows standalone WiFiClipboardSync.exe
echo  2. Automatically build Android app-debug.apk
echo  3. Generate version.json manifest
echo  4. Publish a new GitHub Release so all users auto-update!
echo.
set "RELEASE_TAG="
set /p RELEASE_TAG="Enter tag to release (e.g. v1.0.1, or press Enter to skip tag): "

if "!RELEASE_TAG!"=="" goto :skip_tag_sync

echo Syncing local version manifest to %RELEASE_TAG%...
set "CLEAN_VER=%RELEASE_TAG%"
if "!CLEAN_VER:~0,1!"=="v" set "CLEAN_VER=!CLEAN_VER:~1!"
if "!CLEAN_VER:~0,1!"=="V" set "CLEAN_VER=!CLEAN_VER:~1!"

if exist "windows\.venv\Scripts\python.exe" (
    set "PY_EXE=windows\.venv\Scripts\python.exe"
) else (
    set "PY_EXE=python"
)

"%PY_EXE%" -c "import json; p='version.json'; d=json.load(open(p,encoding='utf-8')); d['tag_name']='%RELEASE_TAG%'; d['version']='!CLEAN_VER!'; d['name']='Wi-Fi Clipboard Sync %RELEASE_TAG%'; json.dump(d,open(p,'w',encoding='utf-8'),indent=2,ensure_ascii=False)" 2>nul
"%PY_EXE%" -c "import re; p='windows/updater.py'; s=open(p,encoding='utf-8').read(); open(p,'w',encoding='utf-8').write(re.sub(r'__version__\s*=\s*\"[^\"]+\"', '__version__ = \"!CLEAN_VER!\"', s))" 2>nul

:skip_tag_sync

echo.
echo [2/4] Adding files and committing...
git add .
set "COMMIT_MSG="
set /p COMMIT_MSG="Enter commit message (Press Enter for default): "
if "%COMMIT_MSG%"=="" (
    if not "%RELEASE_TAG%"=="" (
        set "COMMIT_MSG=Release %RELEASE_TAG% with updated binaries and auto-updater"
    ) else (
        set "COMMIT_MSG=Update Wi-Fi Clipboard Sync with complete auto-update system"
    )
)

git commit -m "%COMMIT_MSG%" 2>nul

echo.
echo [3/4] Pushing to main branch (https://github.com/hasan-circuito/WiFi-clipboard-sync-)...
git branch -M main
git push origin main
if !ERRORLEVEL! neq 0 (
    echo.
    echo [ERROR] Failed to push to main branch.
    echo Please check your internet connection or git login credentials.
    goto :end
)

if "!RELEASE_TAG!"=="" goto :skip_tag_push

echo.
echo [4/4] Creating and pushing release tag %RELEASE_TAG%...
git tag -d %RELEASE_TAG% 2>nul
git tag -a %RELEASE_TAG% -m "Release %RELEASE_TAG%"
git push origin %RELEASE_TAG% --force
if !ERRORLEVEL! equ 0 (
    echo.
    echo ========================================================
    echo  [SUCCESS] Release tag %RELEASE_TAG% pushed!
    echo  CI/CD build is now running on GitHub Actions:
    echo  https://github.com/hasan-circuito/WiFi-clipboard-sync-/actions
    echo ========================================================
) else (
    echo.
    echo [WARNING] Failed to push tag %RELEASE_TAG%.
)
goto :end

:skip_tag_push
echo.
echo [4/4] Skipped release tag. Code pushed to main successfully.

:end
echo.
echo ========================================================
echo  Repository: https://github.com/hasan-circuito/WiFi-clipboard-sync-
echo ========================================================
echo.
pause
