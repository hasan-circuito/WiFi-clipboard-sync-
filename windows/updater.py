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
import subprocess
import urllib.request
import urllib.error
from typing import Optional, Dict, Any, Callable

logger = logging.getLogger("WiFiClipboardSync.Updater")

__version__ = "1.0.2"
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


def generate_updater_batch(new_exe_path: str, target_exe_path: str, current_pid: int) -> str:
    """
    Generates a detached batch script to wait for the current process to exit,
    replace target_exe_path with new_exe_path, and restart target_exe_path.
    Uses set "VAR=value" syntax to correctly handle paths with spaces.
    """
    bat_path = os.path.join(tempfile.gettempdir(), f"update_wifi_clipboard_sync_{current_pid}.bat")
    content = f"""@echo off
setlocal enabledelayedexpansion
title Updating Wi-Fi Clipboard Sync...

set "TARGET={target_exe_path}"
set "NEW={new_exe_path}"
set PID={current_pid}

set WAIT_COUNT=0
:wait_process
tasklist /FI "PID eq %PID%" 2>nul | findstr /R "\\<%PID%\\>" >nul
if not errorlevel 1 (
    set /a WAIT_COUNT+=1
    if !WAIT_COUNT! geq 30 goto kill_process
    timeout /t 1 /nobreak >nul
    goto wait_process
)
goto do_replace

:kill_process
taskkill /F /PID %PID% >nul 2>&1
timeout /t 1 /nobreak >nul

:do_replace
set RETRY_COUNT=0
:replace_file
copy /Y "%NEW%" "%TARGET%" >nul 2>&1
if errorlevel 1 (
    set /a RETRY_COUNT+=1
    if !RETRY_COUNT! geq 15 goto copy_failed
    timeout /t 1 /nobreak >nul
    goto replace_file
)

del "%NEW%" >nul 2>&1
start "" "%TARGET%"
del "%~f0" & exit

:copy_failed
echo Failed to update executable after multiple attempts.
del "%NEW%" >nul 2>&1
del "%~f0" & exit
"""
    with open(bat_path, "w", encoding="utf-8") as f:
        f.write(content)

    return bat_path


def apply_update_and_restart(new_exe_path: str, target_exe_path: Optional[str] = None):
    """
    Launches the standalone updater batch script and exits the current process.
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

    logger.info(f"Spawning detached updater script {bat_path} for PID {current_pid}")

    CREATE_NO_WINDOW = 0x08000000
    DETACHED_PROCESS = 0x00000008

    subprocess.Popen(
        ["cmd.exe", "/c", bat_path],
        creationflags=CREATE_NO_WINDOW | DETACHED_PROCESS,
        close_fds=True
    )

    sys.exit(0)
