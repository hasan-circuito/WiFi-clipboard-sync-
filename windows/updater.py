"""
WiFi Clipboard Sync - Windows Auto-Updater Module.
Checks for updates from GitHub Releases, downloads new executables in the background,
and handles hot replacement & process restart.
"""

import os
import sys
import json
import logging
import tempfile
import shutil
import subprocess
import urllib.request
import urllib.error
from typing import Optional, Dict, Any, Callable

logger = logging.getLogger("WiFiClipboardSync.Updater")

__version__ = "1.0.6"
GITHUB_REPO = "hasan-circuito/WiFi-clipboard-sync-"

RELEASE_ASSET_URL = f"https://github.com/{GITHUB_REPO}/releases/latest/download/version.json"
RAW_VERSION_URL = f"https://raw.githubusercontent.com/{GITHUB_REPO}/main/version.json"
GITHUB_API_URL = f"https://api.github.com/repos/{GITHUB_REPO}/releases/latest"


def parse_version_tuple(ver_str: str) -> tuple:
    """
    Parses version string (e.g. 'v1.0.1', '1.2.0', '1.0') into an integer tuple.
    """
    cleaned = str(ver_str).strip().lstrip("vV")
    cleaned = cleaned.split("-")[0].split("+")[0]
    parts = []
    for item in cleaned.split("."):
        digits = "".join([c for c in item if c.isdigit()])
        parts.append(int(digits) if digits else 0)
    while len(parts) < 3:
        parts.append(0)
    return tuple(parts)


def is_newer_version(remote_ver: str, current_ver: str = __version__) -> bool:
    """
    Returns True if remote_ver is strictly newer than current_ver.
    Uses packaging.version if available, with a robust tuple fallback.
    """
    try:
        from packaging import version
        return version.parse(remote_ver) > version.parse(current_ver)
    except Exception:
        return parse_version_tuple(remote_ver) > parse_version_tuple(current_ver)


def parse_release_info(data: Dict[str, Any]) -> Optional[Dict[str, Any]]:
    """
    Parses version info from either version.json format or GitHub API release format.
    """
    if not isinstance(data, dict):
        return None

    # Format 1: version.json schema
    if "exeUrl" in data:
        ver = str(data.get("version", "")).strip()
        tag = str(data.get("tag_name", "")).strip()
        version_str = ver if ver else tag.lstrip("vV")
        return {
            "version": version_str or "1.0.0",
            "tag_name": tag or f"v{version_str}",
            "exe_url": data.get("exeUrl"),
            "changelog": data.get("changelog", "Update available."),
            "published_at": data.get("publishedAt")
        }

    # Format 2: GitHub Releases API schema
    if "tag_name" in data and "assets" in data:
        tag = str(data.get("tag_name", "")).strip()
        version_str = tag.lstrip("vV")
        assets = data.get("assets", [])
        exe_url = None
        for asset in assets:
            name = asset.get("name", "")
            if name.lower().endswith(".exe"):
                exe_url = asset.get("browser_download_url")
                break

        if exe_url:
            return {
                "version": version_str,
                "tag_name": tag,
                "exe_url": exe_url,
                "changelog": data.get("body", "Update available."),
                "published_at": data.get("published_at")
            }

    return None


def fetch_json(url: str, timeout: int = 6) -> Optional[Dict[str, Any]]:
    """
    Fetches JSON data from a URL with User-Agent header.
    """
    req = urllib.request.Request(
        url,
        headers={"User-Agent": "WiFiClipboardSync-Windows-Updater"}
    )
    try:
        with urllib.request.urlopen(req, timeout=timeout) as response:
            if response.status == 200:
                raw = response.read().decode("utf-8")
                return json.loads(raw)
    except Exception as e:
        logger.debug(f"Failed to fetch {url}: {e}")
    return None


def check_for_updates(current_ver: str = __version__) -> Any:
    """
    Checks GitHub for newer versions of the Windows application.
    Returns:
        - dict: update info if a newer version is found
        - False: if checked successfully and application is already up to date
        - None: if unable to reach any update endpoints (network/offline error)
    """
    endpoints = [RELEASE_ASSET_URL, RAW_VERSION_URL, GITHUB_API_URL]
    any_reachable = False
    for url in endpoints:
        try:
            logger.info(f"Checking for updates at {url}")
            data = fetch_json(url)
            if not data:
                continue

            info = parse_release_info(data)
            if not info or not info.get("exe_url"):
                continue

            any_reachable = True
            remote_ver = info["version"]
            if is_newer_version(remote_ver, current_ver):
                logger.info(f"New update found: v{remote_ver} (current: v{current_ver})")
                return info
            else:
                logger.info(f"Current version v{current_ver} is up to date with remote v{remote_ver}.")
                return False
        except Exception as e:
            logger.warning(f"Error checking update endpoint {url}: {e}")

    return False if any_reachable else None


def download_update(
    exe_url: str,
    dest_path: Optional[str] = None,
    progress_callback: Optional[Callable[[int], None]] = None
) -> str:
    """
    Downloads the updated executable to dest_path (defaults to %TEMP%\\WiFiClipboardSync_new.exe).
    Reports progress via progress_callback(percentage: int).
    """
    if not dest_path:
        dest_path = os.path.join(tempfile.gettempdir(), "WiFiClipboardSync_new.exe")

    temp_dest = dest_path + ".tmp"
    if os.path.exists(temp_dest):
        try:
            os.remove(temp_dest)
        except Exception:
            pass

    req = urllib.request.Request(
        exe_url,
        headers={"User-Agent": "WiFiClipboardSync-Windows-Updater"}
    )

    with urllib.request.urlopen(req, timeout=30) as response:
        total_size = int(response.headers.get("Content-Length", 0))
        downloaded = 0
        chunk_size = 65536

        with open(temp_dest, "wb") as f:
            while True:
                chunk = response.read(chunk_size)
                if not chunk:
                    break
                f.write(chunk)
                downloaded += len(chunk)
                if total_size > 0 and progress_callback:
                    pct = int((downloaded * 100) / total_size)
                    progress_callback(pct)

    if os.path.exists(dest_path):
        try:
            os.remove(dest_path)
        except Exception:
            pass

    os.rename(temp_dest, dest_path)
    logger.info(f"Downloaded new update to: {dest_path}")
    return dest_path


def cleanup_old_executables(target_exe_path: Optional[str] = None):
    """
    Cleans up leftover .old executables from previous atomic update renames
    and temporary update scripts. Safely called during application startup.
    """
    try:
        if not target_exe_path:
            if getattr(sys, "frozen", False):
                target_exe_path = sys.executable
            else:
                target_exe_path = os.path.join(
                    os.path.dirname(os.path.abspath(__file__)),
                    "WiFiClipboardSync.exe"
                )

        old_exe = f"{target_exe_path}.old"
        if os.path.exists(old_exe):
            try:
                os.remove(old_exe)
                logger.info(f"Cleaned up previous version executable: {old_exe}")
            except Exception as e:
                logger.debug(f"Could not remove {old_exe} (may still be releasing handle): {e}")

        # Also cleanup leftover update scripts in temp directory
        temp_dir = tempfile.gettempdir()
        try:
            for fname in os.listdir(temp_dir):
                if fname.startswith("update_wifi_clipboard_sync_") and (fname.endswith(".bat") or fname.endswith(".vbs")):
                    try:
                        os.remove(os.path.join(temp_dir, fname))
                    except Exception:
                        pass
        except Exception:
            pass
    except Exception as e:
        logger.debug(f"Error during old executable cleanup: {e}")


def generate_updater_batch(new_exe_path: str, target_exe_path: str, current_pid: int) -> str:
    """
    Generates a reliable standalone batch script implementing Atomic Rename (.old).
    In Windows NTFS, a running executable cannot be overwritten with copy/move directly,
    but it CAN be renamed to .old!
    We rename TARGET -> TARGET.old, move NEW -> TARGET, start TARGET, and clean up.
    All visible ping retry loops have been eliminated.
    """
    bat_path = os.path.join(tempfile.gettempdir(), f"update_wifi_clipboard_sync_{current_pid}.bat")
    content = f"""@echo off
setlocal enabledelayedexpansion
title Updating Wi-Fi Clipboard Sync...

set "TARGET={target_exe_path}"
set "NEW={new_exe_path}"
set "OLD={target_exe_path}.old"
set PID={current_pid}

:: 1. Wait for parent process to exit (silent wait, zero console window flashing)
if not "%PID%"=="" (
    powershell.exe -WindowStyle Hidden -NoProfile -NonInteractive -Command "try {{ Wait-Process -Id %PID% -Timeout 10 -ErrorAction SilentlyContinue }} catch {{}}" >nul 2>&1
)

:: 2. Atomic Rename (.old)
:: Windows NTFS allows renaming running/open executables even when write/overwrite is locked.
if exist "%OLD%" del /f /q "%OLD%" >nul 2>&1

set RETRY_COUNT=0
:try_rename
if exist "%TARGET%" (
    move /y "%TARGET%" "%OLD%" >nul 2>&1
    if errorlevel 1 (
        set /a RETRY_COUNT+=1
        if !RETRY_COUNT! lss 5 (
            powershell.exe -WindowStyle Hidden -NoProfile -NonInteractive -Command "Start-Sleep -Milliseconds 250" >nul 2>&1
            goto try_rename
        )
    )
)

:: 3. Place new executable
move /y "%NEW%" "%TARGET%" >nul 2>&1
if not exist "%TARGET%" (
    copy /y "%NEW%" "%TARGET%" >nul 2>&1
)
if not exist "%TARGET%" if exist "%OLD%" (
    move /y "%OLD%" "%TARGET%" >nul 2>&1
)

:: 4. Launch updated application, cleanup temp binary, and self-delete
if exist "%TARGET%" (
    start "" "%TARGET%"
)
if exist "%NEW%" del /f /q "%NEW%" >nul 2>&1
(goto) 2>nul & del "%~f0"
"""
    with open(bat_path, "w", encoding="utf-8") as f:
        f.write(content)

    return bat_path


def generate_silent_vbs_launcher(bat_path: str, current_pid: int) -> str:
    """
    Generates a tiny VBScript wrapper to execute the updater batch script
    completely hidden (window style 0, no conhost allocation), eliminating console flashing.
    """
    vbs_path = os.path.join(tempfile.gettempdir(), f"update_wifi_clipboard_sync_{current_pid}.vbs")
    escaped_bat = bat_path.replace('"', '""')
    content = f'''Set WshShell = CreateObject("WScript.Shell")
WshShell.Run "cmd.exe /c """"{escaped_bat}""""", 0, False
Set WshShell = Nothing
'''
    with open(vbs_path, "w", encoding="utf-8") as f:
        f.write(content)
    return vbs_path


def apply_update_and_restart(new_exe_path: str, target_exe_path: Optional[str] = None):
    """
    Launches the standalone updater via a completely silent launcher with atomic rename (.old)
    and exits the current process. Zero visible CMD flashing windows appear on screen.
    """
    if not target_exe_path:
        if getattr(sys, "frozen", False):
            target_exe_path = sys.executable
        else:
            # Dev mode fallback: target WiFiClipboardSync.exe in project windows folder
            target_exe_path = os.path.join(
                os.path.dirname(os.path.abspath(__file__)),
                "WiFiClipboardSync.exe"
            )

    current_pid = os.getpid()
    bat_path = generate_updater_batch(new_exe_path, target_exe_path, current_pid)
    vbs_path = generate_silent_vbs_launcher(bat_path, current_pid)

    logger.info(f"Spawning silent updater launcher {vbs_path} for PID {current_pid}")

    CREATE_NO_WINDOW = 0x08000000
    DETACHED_PROCESS = 0x00000008

    wscript_path = shutil.which("wscript.exe") or os.path.join(
        os.environ.get("SystemRoot", "C:\\Windows"), "System32", "wscript.exe"
    )

    if os.path.exists(wscript_path):
        cmd = [wscript_path, "//nologo", vbs_path]
    else:
        cmd = ["cmd.exe", "/c", bat_path]

    subprocess.Popen(
        cmd,
        creationflags=CREATE_NO_WINDOW | DETACHED_PROCESS,
        stdin=subprocess.DEVNULL,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL
    )

    sys.exit(0)


