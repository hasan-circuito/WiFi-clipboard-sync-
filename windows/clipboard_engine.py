"""
Win32 native clipboard listener and modifier.
Uses AddClipboardFormatListener for event-driven, 0% CPU clipboard monitoring.
Includes echo suppression and de-duplication via SHA-256 hashes.
"""

import ctypes
from ctypes import wintypes
import hashlib
import logging
import threading
import time
from typing import Callable, Optional, Set, Dict

import win32api
import win32clipboard
import win32con
import win32gui
import pywintypes

WM_CLIPBOARDUPDATE = 0x031D

logger = logging.getLogger("ClipboardEngine")


def compute_sha256(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


class ClipboardEngine:
    def __init__(self, on_local_copy: Optional[Callable[[str, str], None]] = None):
        """
        on_local_copy: callback(text: str, text_hash: str) when user copies new text locally.
        """
        self.on_local_copy = on_local_copy
        self._running = False
        self._hwnd = None
        self._thread: Optional[threading.Thread] = None
        self._lock = threading.Lock()

        # Cache of hashes to suppress (e.g. text synced from mobile or set by app)
        # mapping hash -> timestamp
        self._suppressed_hashes: Dict[str, float] = {}
        self._last_processed_hash: Optional[str] = None

        self._window_class_name = f"WifiClipSyncListener_{int(time.time() * 1000)}"

    def _cleanup_old_suppressions(self):
        now = time.time()
        # Keep suppressions valid for 15 seconds
        expired = [h for h, t in self._suppressed_hashes.items() if now - t > 15.0]
        for h in expired:
            del self._suppressed_hashes[h]

    def suppress_hash(self, text_hash: str):
        """Mark a hash as suppressed so local change detection ignores it."""
        with self._lock:
            self._cleanup_old_suppressions()
            self._suppressed_hashes[text_hash] = time.time()

    def is_suppressed(self, text_hash: str) -> bool:
        with self._lock:
            self._cleanup_old_suppressions()
            return text_hash in self._suppressed_hashes

    def get_clipboard_text(self, retries: int = 5, delay: float = 0.02) -> Optional[str]:
        """Reads text from Windows clipboard safely with retries for locks."""
        for attempt in range(retries):
            try:
                win32clipboard.OpenClipboard(0)
                try:
                    if win32clipboard.IsClipboardFormatAvailable(win32clipboard.CF_UNICODETEXT):
                        data = win32clipboard.GetClipboardData(win32clipboard.CF_UNICODETEXT)
                        return data
                    elif win32clipboard.IsClipboardFormatAvailable(win32clipboard.CF_TEXT):
                        data = win32clipboard.GetClipboardData(win32clipboard.CF_TEXT)
                        if isinstance(data, bytes):
                            return data.decode("utf-8", errors="replace")
                        return data
                    return None
                finally:
                    win32clipboard.CloseClipboard()
            except (pywintypes.error, Exception):
                time.sleep(delay)
        return None

    def set_clipboard_text(self, text: str, retries: int = 5, delay: float = 0.02) -> bool:
        """Sets text onto Windows clipboard and marks its hash suppressed."""
        if not text:
            return False

        text_hash = compute_sha256(text)
        self.suppress_hash(text_hash)

        for attempt in range(retries):
            try:
                win32clipboard.OpenClipboard(0)
                try:
                    win32clipboard.EmptyClipboard()
                    win32clipboard.SetClipboardData(win32clipboard.CF_UNICODETEXT, text)
                    self._last_processed_hash = text_hash
                    return True
                finally:
                    win32clipboard.CloseClipboard()
            except (pywintypes.error, Exception):
                time.sleep(delay)
        return False

    def _wnd_proc(self, hwnd, msg, wparam, lparam):
        if msg == WM_CLIPBOARDUPDATE:
            self._handle_clipboard_update()
            return 0
        elif msg in (win32con.WM_DESTROY, win32con.WM_CLOSE):
            try:
                ctypes.windll.user32.RemoveClipboardFormatListener(hwnd)
            except Exception:
                pass
            win32gui.PostQuitMessage(0)
            return 0
        return win32gui.DefWindowProc(hwnd, msg, wparam, lparam)

    def _handle_clipboard_update(self):
        text = self.get_clipboard_text()
        if text is None or text == "":
            return

        text_hash = compute_sha256(text)

        # Check if suppressed (echo prevention from mobile sync)
        if self.is_suppressed(text_hash):
            return

        # Check if identical to last processed event
        if self._last_processed_hash == text_hash:
            return

        self._last_processed_hash = text_hash

        if self.on_local_copy:
            try:
                self.on_local_copy(text, text_hash)
            except Exception as e:
                logger.error(f"Error in on_local_copy callback: {e}")

    def _run_message_loop(self):
        try:
            hinstance = win32api.GetModuleHandle(None)
            wc = win32gui.WNDCLASS()
            wc.lpfnWndProc = self._wnd_proc
            wc.lpszClassName = self._window_class_name
            wc.hInstance = hinstance
            atom = win32gui.RegisterClass(wc)

            self._hwnd = win32gui.CreateWindow(
                atom,
                f"WifiClipSyncListener_{id(self)}",
                0, 0, 0, 0, 0,
                0, 0, hinstance, None
            )

            res = ctypes.windll.user32.AddClipboardFormatListener(self._hwnd)
            if not res:
                logger.error("Failed to call AddClipboardFormatListener")

            self._running = True
            win32gui.PumpMessages()
        except Exception as e:
            logger.error(f"Message loop encountered error: {e}")
        finally:
            self._running = False
            if self._hwnd:
                try:
                    ctypes.windll.user32.RemoveClipboardFormatListener(self._hwnd)
                    win32gui.DestroyWindow(self._hwnd)
                except Exception:
                    pass
                self._hwnd = None
            try:
                win32gui.UnregisterClass(self._window_class_name, hinstance)
            except Exception:
                pass

    def start(self):
        """Starts the clipboard listener in a background thread."""
        if self._running or (self._thread and self._thread.is_alive()):
            return
        self._thread = threading.Thread(target=self._run_message_loop, daemon=True, name="ClipboardEngineThread")
        self._thread.start()

        # Wait up to 2 seconds for HWND to be created
        start_t = time.time()
        while self._hwnd is None and time.time() - start_t < 2.0:
            time.sleep(0.05)

    def stop(self):
        """Stops the clipboard listener."""
        if self._hwnd:
            try:
                win32gui.PostMessage(self._hwnd, win32con.WM_CLOSE, 0, 0)
            except Exception:
                pass
        if self._thread and self._thread.is_alive():
            self._thread.join(timeout=1.5)
        self._running = False
