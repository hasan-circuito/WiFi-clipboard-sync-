@echo off
cd /d "%~dp0"
echo ========================================================
echo       Pushing Wi-Fi Clipboard Sync v1.0.6 to GitHub
echo ========================================================
echo.
git push origin main
git push origin v1.0.6 --force
echo.
echo ========================================================
echo  [SUCCESS] v1.0.6 Pushed to GitHub!
echo  Check build progress at:
echo  https://github.com/hasan-circuito/WiFi-clipboard-sync-/actions
echo ========================================================
echo.
pause
