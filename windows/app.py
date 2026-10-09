"""
WiFi Clipboard Sync - Windows Desktop GUI & Core Orchestrator.
Provides a modern dark-mode dashboard with live connection indicators,
recent clipboard history, and auto-sync controls.
"""

import os
import sys
import time
import logging
import threading
from typing import Optional

# Setup logging
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s"
)
logger = logging.getLogger("WiFiClipboardSync")

try:
    import customtkinter as ctk
    USE_CTK = True
except ImportError:
    import tkinter as tk
    from tkinter import ttk, messagebox
    USE_CTK = False

from clipboard_engine import ClipboardEngine, compute_sha256
from server import SyncServer, get_local_ip, WS_SERVER_PORT
from history_manager import HistoryManager
from updater import (
    check_for_updates,
    download_update,
    apply_update_and_restart,
    __version__ as APP_VERSION
)


class ClipboardSyncApp:
    def __init__(self):
        self.history_mgr = HistoryManager()
        self.auto_sync_enabled = True
        self.connected_phone_ip = None

        # Auto-updater state
        self.pending_update = None
        self.downloaded_update_path = None
        self.is_checking_update = False
        self.is_downloading_update = False
        self.update_card = None

        # Networking & Clipboard components
        self.clipboard_engine = ClipboardEngine(on_local_copy=self._on_local_copy)
        self.server = SyncServer(
            port=WS_SERVER_PORT,
            on_client_connected=self._on_client_connected,
            on_client_disconnected=self._on_client_disconnected,
            on_message_received=self._on_remote_message
        )

        # Setup GUI
        self._init_ui()

    def _init_ui(self):
        if USE_CTK:
            ctk.set_appearance_mode("dark")
            ctk.set_default_color_theme("blue")
            self.root = ctk.CTk()
        else:
            self.root = tk.Tk()

        self.root.title(f"Wi-Fi Clipboard Sync v{APP_VERSION}")
        self.root.geometry("640x720")
        self.root.minsize(560, 600)

        self._build_widgets()
        self.root.protocol("WM_DELETE_WINDOW", self.on_close)

    def _build_widgets(self):
        if USE_CTK:
            self._build_ctk_widgets()
        else:
            self._build_tk_fallback_widgets()

    def _build_ctk_widgets(self):
        # Header Frame
        header = ctk.CTkFrame(self.root, corner_radius=12, fg_color=("#2b2d42", "#1e1f29"))
        header.pack(fill="x", padx=16, pady=(16, 8))

        title_label = ctk.CTkLabel(
            header,
            text=f"📋 Wi-Fi Clipboard Sync v{APP_VERSION}",
            font=ctk.CTkFont(size=20, weight="bold")
        )
        title_label.pack(anchor="w", padx=16, pady=(12, 2))

        subtitle = ctk.CTkLabel(
            header,
            text="Seamless, zero-friction sync between Laptop and Phone",
            font=ctk.CTkFont(size=12),
            text_color="#8d99ae"
        )
        subtitle.pack(anchor="w", padx=16, pady=(0, 10))

        # Dynamic Update Banner Card (shown when an update is found / ready)
        self.update_card = ctk.CTkFrame(self.root, corner_radius=10, fg_color=("#1d3557", "#14213d"))

        self.update_info_label = ctk.CTkLabel(
            self.update_card,
            text="",
            font=ctk.CTkFont(size=12, weight="bold"),
            text_color="#a8dadc"
        )
        self.update_info_label.pack(side="left", padx=16, pady=10)

        self.update_action_btn = ctk.CTkButton(
            self.update_card,
            text="Restart Now",
            width=110,
            fg_color="#2a9d8f",
            hover_color="#21867a",
            command=self._confirm_apply_update
        )

        self.update_dismiss_btn = ctk.CTkButton(
            self.update_card,
            text="Later",
            width=60,
            fg_color="#6c757d",
            hover_color="#495057",
            command=self._dismiss_update_card
        )
        self.update_dismiss_btn.pack(side="right", padx=(4, 12), pady=10)

        # Status & Network Banner
        self.status_card = ctk.CTkFrame(self.root, corner_radius=10, fg_color=("#1f242d", "#171a21"))
        self.status_card.pack(fill="x", padx=16, pady=8)

        # Network Info
        local_ip = get_local_ip()
        self.ip_label = ctk.CTkLabel(
            self.status_card,
            text=f"🌐 Local IP: {local_ip}:{WS_SERVER_PORT}",
            font=ctk.CTkFont(size=13, weight="bold"),
            text_color="#a8dadc"
        )
        self.ip_label.pack(side="left", padx=16, pady=12)

        # Connection status badge
        self.status_badge = ctk.CTkLabel(
            self.status_card,
            text="🟡 Searching on Wi-Fi...",
            font=ctk.CTkFont(size=13, weight="bold"),
            text_color="#f4a261"
        )
        self.status_badge.pack(side="right", padx=16, pady=12)

        # Controls & Toolbar Frame
        controls = ctk.CTkFrame(self.root, corner_radius=10)
        controls.pack(fill="x", padx=16, pady=6)

        self.auto_sync_switch = ctk.CTkSwitch(
            controls,
            text="Auto-Sync Enabled",
            command=self._toggle_auto_sync,
            font=ctk.CTkFont(size=13)
        )
        self.auto_sync_switch.select()
        self.auto_sync_switch.pack(side="left", padx=16, pady=10)

        # Action Buttons
        self.clear_btn = ctk.CTkButton(
            controls,
            text="Clear History",
            width=100,
            fg_color="#e63946",
            hover_color="#d62828",
            command=self._clear_history
        )
        self.clear_btn.pack(side="right", padx=12, pady=10)

        self.check_update_btn = ctk.CTkButton(
            controls,
            text="Check Updates",
            width=110,
            fg_color="#3a86ff",
            hover_color="#2667d4",
            command=self._manual_check_update
        )
        self.check_update_btn.pack(side="right", padx=4, pady=10)

        self.send_now_btn = ctk.CTkButton(
            controls,
            text="Push Clipboard",
            width=120,
            command=self._push_current_clipboard
        )
        self.send_now_btn.pack(side="right", padx=4, pady=10)

        # Sync Activity Notification Banner
        self.activity_banner = ctk.CTkLabel(
            self.root,
            text="Ready. Copy any text on Phone or PC to sync.",
            font=ctk.CTkFont(size=12, slant="italic"),
            text_color="#8ecae6"
        )
        self.activity_banner.pack(anchor="w", padx=20, pady=(4, 6))

        # History Header
        history_header = ctk.CTkFrame(self.root, fg_color="transparent")
        history_header.pack(fill="x", padx=16, pady=(6, 2))

        ctk.CTkLabel(
            history_header,
            text="Recent Synced Items",
            font=ctk.CTkFont(size=15, weight="bold")
        ).pack(side="left")

        # Scrollable History Container
        self.history_scroll = ctk.CTkScrollableFrame(self.root, corner_radius=10)
        self.history_scroll.pack(fill="both", expand=True, padx=16, pady=(4, 16))

        self._refresh_history_ui()

    def _build_tk_fallback_widgets(self):
        # Fallback standard Tkinter UI in case CustomTkinter isn't available
        lbl = tk.Label(self.root, text="Wi-Fi Clipboard Sync", font=("Arial", 16, "bold"))
        lbl.pack(pady=10)
        self.ip_label = tk.Label(self.root, text=f"IP: {get_local_ip()}:{WS_SERVER_PORT}")
        self.ip_label.pack()
        self.status_badge = tk.Label(self.root, text="Searching on Wi-Fi...", fg="orange")
        self.status_badge.pack(pady=5)

        self.activity_banner = tk.Label(self.root, text="Ready.", fg="gray")
        self.activity_banner.pack()

        self.history_scroll = tk.Frame(self.root)
        self.history_scroll.pack(fill="both", expand=True, padx=10, pady=10)
        self._refresh_history_ui()

    def _toggle_auto_sync(self):
        if USE_CTK:
            self.auto_sync_enabled = bool(self.auto_sync_switch.get())
        state = "enabled" if self.auto_sync_enabled else "paused"
        self._set_banner(f"Auto-Sync is now {state}.")

    def _set_banner(self, text: str, duration: float = 4.0):
        def _update():
            self.activity_banner.configure(text=text)
        self.root.after(0, _update)

    def _on_client_connected(self, client_ip: str):
        self.connected_phone_ip = client_ip
        def _update():
            if USE_CTK:
                self.status_badge.configure(
                    text=f"🟢 Phone Connected ({client_ip})",
                    text_color="#2ec4b6"
                )
            else:
                self.status_badge.configure(text=f"Connected ({client_ip})", fg="green")
            self._set_banner(f"Phone connected from {client_ip}!")
        self.root.after(0, _update)

    def _on_client_disconnected(self, client_ip: str):
        self.connected_phone_ip = None
        def _update():
            if USE_CTK:
                self.status_badge.configure(
                    text="🟡 Searching on Wi-Fi...",
                    text_color="#f4a261"
                )
            else:
                self.status_badge.configure(text="Searching on Wi-Fi...", fg="orange")
            self._set_banner("Phone disconnected. Waiting for reconnection...")
        self.root.after(0, _update)

    def _on_local_copy(self, text: str, text_hash: str):
        """Called when user copies text locally on PC."""
        logger.info(f"Local copy detected ({len(text)} chars)")
        self.history_mgr.add_entry(text, source="pc")

        if self.auto_sync_enabled and self.server.is_connected:
            self.server.broadcast_clipboard(text, text_hash)
            self._set_banner(f"📤 Sent {len(text)} characters to Phone.")
        else:
            self._set_banner(f"💻 Copied locally ({len(text)} chars).")

        self.root.after(0, self._refresh_history_ui)

    def _on_remote_message(self, text: str, text_hash: str):
        """Called when phone sends new clipboard text over WebSocket."""
        logger.info(f"Remote message received from phone ({len(text)} chars)")
        # Suppress echo before setting to OS clipboard
        self.clipboard_engine.set_clipboard_text(text)
        self.history_mgr.add_entry(text, source="phone")

        self._set_banner(f"📥 Received {len(text)} characters from Phone!")
        self.root.after(0, self._refresh_history_ui)

    def _push_current_clipboard(self):
        text = self.clipboard_engine.get_clipboard_text()
        if not text or not text.strip():
            self._set_banner("Clipboard is currently empty.")
            return

        text_hash = compute_sha256(text)
        self.history_mgr.add_entry(text, source="pc")

        if self.server.is_connected:
            self.server.broadcast_clipboard(text, text_hash)
            self._set_banner(f"📤 Manually pushed {len(text)} characters to Phone.")
        else:
            self._set_banner(f"No phone connected. Beacon is broadcasting on Wi-Fi.")

        self._refresh_history_ui()

    def _clear_history(self):
        self.history_mgr.clear()
        self._refresh_history_ui()
        self._set_banner("Clipboard history cleared.")

    def _copy_item_to_clipboard(self, text: str, copy_btn=None):
        self.clipboard_engine.set_clipboard_text(text)
        text_hash = compute_sha256(text)
        if self.auto_sync_enabled and self.server.is_connected:
            self.server.broadcast_clipboard(text, text_hash)
            self._set_banner(f"Copied to PC & synced {len(text)} chars to Phone.")
        else:
            self._set_banner(f"Copied to clipboard ({len(text)} chars).")
        if copy_btn and USE_CTK:
            orig_text = copy_btn.cget("text")
            copy_btn.configure(text="Copied!")
            self.root.after(1200, lambda: copy_btn.configure(text=orig_text))

    def _refresh_history_ui(self):
        # Clear existing history widgets
        for widget in self.history_scroll.winfo_children():
            widget.destroy()

        entries = self.history_mgr.get_entries()
        if not entries:
            if USE_CTK:
                empty_lbl = ctk.CTkLabel(
                    self.history_scroll,
                    text="No clipboard items yet.\nCopy text on Phone or PC to start syncing!",
                    text_color="#6c757d",
                    font=ctk.CTkFont(size=13)
                )
                empty_lbl.pack(pady=40)
            else:
                tk.Label(self.history_scroll, text="No items yet").pack(pady=20)
            return

        for entry in entries:
            self._create_history_card(entry)

    def _create_history_card(self, entry: dict):
        text = entry.get("text", "")
        source = entry.get("source", "pc")
        time_str = entry.get("time_str", "")
        char_count = entry.get("char_count", len(text))

        if USE_CTK:
            card = ctk.CTkFrame(self.history_scroll, corner_radius=8, fg_color=("#2b2d42", "#1f222e"))
            card.pack(fill="x", pady=4, padx=4)

            # Top bar of card
            top_bar = ctk.CTkFrame(card, fg_color="transparent")
            top_bar.pack(fill="x", padx=10, pady=(8, 2))

            if source == "phone":
                src_badge = ctk.CTkLabel(
                    top_bar,
                    text="📱 Phone",
                    font=ctk.CTkFont(size=12, weight="bold"),
                    text_color="#48cae4"
                )
            else:
                src_badge = ctk.CTkLabel(
                    top_bar,
                    text="💻 This PC",
                    font=ctk.CTkFont(size=12, weight="bold"),
                    text_color="#90e0ef"
                )
            src_badge.pack(side="left")

            meta_lbl = ctk.CTkLabel(
                top_bar,
                text=f"{time_str} • {char_count} chars",
                font=ctk.CTkFont(size=11),
                text_color="#8d99ae"
            )
            meta_lbl.pack(side="left", padx=8)

            copy_btn = ctk.CTkButton(
                top_bar,
                text="Copy",
                width=60,
                height=24,
                font=ctk.CTkFont(size=11)
            )
            copy_btn.configure(command=lambda t=text, b=copy_btn: self._copy_item_to_clipboard(t, b))
            copy_btn.pack(side="right")

            # Content preview (up to 3 lines)
            preview_text = text if len(text) <= 200 else text[:197] + "..."
            content_lbl = ctk.CTkLabel(
                card,
                text=preview_text,
                font=ctk.CTkFont(size=12),
                justify="left",
                anchor="w",
                wraplength=520
            )
            content_lbl.pack(fill="x", padx=10, pady=(2, 8))
        else:
            card = tk.Frame(self.history_scroll, relief="groove", bd=1)
            card.pack(fill="x", pady=2)
            tk.Label(card, text=f"[{source.upper()}] {time_str}").pack(side="left")
            tk.Button(card, text="Copy", command=lambda t=text: self._copy_item_to_clipboard(t)).pack(side="right")
            tk.Label(card, text=text[:80]).pack(anchor="w")

    def start(self):
        # Start clipboard listener & network server
        self.clipboard_engine.start()
        self.server.start()
        logger.info(f"WiFi Clipboard Sync v{APP_VERSION} started successfully.")

        # Start auto-update check in background thread
        threading.Thread(
            target=self._background_check_and_download_update,
            args=(False,),
            daemon=True
        ).start()

        self.root.mainloop()

    def _manual_check_update(self):
        threading.Thread(
            target=self._background_check_and_download_update,
            args=(True,),
            daemon=True
        ).start()

    def _background_check_and_download_update(self, manual: bool = False):
        if self.is_checking_update or self.is_downloading_update:
            if manual:
                self._set_banner("Update operation already in progress...")
            return

        self.is_checking_update = True
        try:
            if manual:
                self._set_banner("Checking for updates online...")
            logger.info("Checking for app updates...")
            update_result = check_for_updates(APP_VERSION)
            if not update_result:
                if manual:
                    if update_result is False:
                        self._set_banner(f"App is up to date (v{APP_VERSION}).")
                        try:
                            from tkinter import messagebox
                            self.root.after(0, lambda: messagebox.showinfo(
                                "Up to Date",
                                f"Wi-Fi Clipboard Sync v{APP_VERSION} is already the latest version!"
                            ))
                        except Exception:
                            pass
                    else:
                        self._set_banner("Could not check updates. Check your internet connection.")
                        try:
                            from tkinter import messagebox
                            self.root.after(0, lambda: messagebox.showwarning(
                                "Update Check Failed",
                                "Could not connect to update server. Please check your internet connection."
                            ))
                        except Exception:
                            pass
                return

            self.pending_update = update_result
            remote_ver = update_result.get("version", "")
            exe_url = update_result.get("exe_url")
            if not exe_url:
                return

            logger.info(f"Downloading update v{remote_ver} from {exe_url}...")
            self._set_banner(f"✨ Update v{remote_ver} found! Downloading in background...")
            self._show_update_banner_ui(f"✨ Downloading update v{remote_ver} (0%)...", show_action=False)

            self.is_downloading_update = True
            dest_file = download_update(
                exe_url,
                progress_callback=lambda p: self._on_download_progress(remote_ver, p)
            )
            self.downloaded_update_path = dest_file

            logger.info(f"Update v{remote_ver} ready to install.")
            self._set_banner(f"🎉 Update v{remote_ver} downloaded! Restart now to apply.")
            self._show_update_banner_ui(f"🎉 Update v{remote_ver} downloaded. Restart to apply!", show_action=True)

            # Prompt user in desktop UI
            try:
                from tkinter import messagebox
                def _prompt():
                    if messagebox.askyesno(
                        "Update Downloaded",
                        f"Wi-Fi Clipboard Sync v{remote_ver} has been downloaded.\n\nRestart now to apply update?"
                    ):
                        self._confirm_apply_update()
                self.root.after(0, _prompt)
            except Exception:
                pass

        except Exception as e:
            logger.warning(f"Update check error: {e}")
            if manual:
                self._set_banner(f"Update check failed: {e}")
        finally:
            self.is_checking_update = False
            self.is_downloading_update = False

    def _on_download_progress(self, remote_ver: str, pct: int):
        self._set_banner(f"Downloading update v{remote_ver}... {pct}%")
        self._show_update_banner_ui(f"Downloading v{remote_ver}... {pct}%", show_action=False)

    def _show_update_banner_ui(self, message: str, show_action: bool = False):
        def _update():
            if self.update_card and USE_CTK:
                self.update_card.pack(fill="x", padx=16, pady=(4, 6), before=self.status_card)
                self.update_info_label.configure(text=message)
                if show_action:
                    self.update_action_btn.pack(side="right", padx=(4, 12), pady=10)
                else:
                    self.update_action_btn.pack_forget()
        self.root.after(0, _update)

    def _dismiss_update_card(self):
        if self.update_card:
            self.update_card.pack_forget()

    def _confirm_apply_update(self):
        if not self.downloaded_update_path or not os.path.exists(self.downloaded_update_path):
            self._set_banner("Update executable not found on disk.")
            return

        self._set_banner("Restarting to apply update...")
        try:
            self.on_close(cleanup_only=True)
            self.root.destroy()
        except Exception:
            pass
        apply_update_and_restart(self.downloaded_update_path)

    def on_close(self, cleanup_only: bool = False):
        logger.info("Shutting down WiFi Clipboard Sync...")
        try:
            self.clipboard_engine.stop()
        except Exception:
            pass
        try:
            self.server.stop()
        except Exception:
            pass
        if not cleanup_only:
            self.root.destroy()
            sys.exit(0)


def main():
    app = ClipboardSyncApp()
    app.start()


if __name__ == "__main__":
    main()
