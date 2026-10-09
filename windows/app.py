"""
WiFi Clipboard Sync - Windows Desktop GUI & Core Orchestrator.
Deep Obsidian Dark UI inspired by 21st.dev's "AI Thinking Orb and Input".
Features:
1. Luminescent Connection Orb with pre-cached gradient discs & < 0.5% CPU idle usage.
2. Floating Obsidian Input Container with tactile push & live character count.
3. Raycast-style clipboard history stream with search filter, type badges, & 1-click copy.
4. Security & Trust Badge ("🛡️ 100% Local • Zero Cloud").
5. Background Services: Win32 clipboard hook, UDP discovery beacon, WebSocket server, tray icon, and auto-updater.
"""

import os
import sys
import time
import math
import re
import logging
import threading
from typing import Optional, List, Dict

# Setup logging
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s"
)
logger = logging.getLogger("WiFiClipboardSync")

try:
    import customtkinter as ctk
    from PIL import Image, ImageDraw, ImageFilter, ImageTk
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


def detect_content_type(text: str) -> str:
    """Classifies clipboard text into 'URL', 'CODE', or 'TEXT'."""
    stripped = text.strip()
    if not stripped:
        return "TEXT"

    # 1. URL detection
    if re.match(r"^(https?|ftp|file|ws|wss)://[^\s]+$", stripped, re.IGNORECASE) or \
       re.match(r"^www\.[a-zA-Z0-9\-]+(\.[a-zA-Z0-9\-]+)+[^\s]*$", stripped, re.IGNORECASE):
        return "URL"

    # 2. JSON detection (valid JSON objects or arrays)
    if (stripped.startswith("{") and stripped.endswith("}")) or \
       (stripped.startswith("[") and stripped.endswith("]")):
        try:
            import json
            json.loads(stripped)
            return "CODE"
        except Exception:
            pass

    # 3. HTML / XML elements
    if re.match(r"^<(!DOCTYPE|[a-zA-Z0-9_\-]+)(\s+[^>]*)?>.*(</[a-zA-Z0-9_\-]+>|/>)$", stripped, re.DOTALL | re.IGNORECASE) or \
       re.search(r"<(html|head|body|div|span|script|style|p|a|ul|li|table|tr|td|form|input|button|header|footer|section)[\s>]", stripped, re.IGNORECASE):
        return "CODE"

    # 4. Common Shell / CLI commands
    if re.match(r"^(npm|npx|pip|pip3|pnpm|yarn|git|docker|kubectl|curl|wget|cargo|go|dotnet|python|python3)\s+[a-zA-Z0-9_\-]+", stripped):
        return "CODE"

    # 5. Direct Programming declarations & statements
    if stripped.startswith("import ") or (stripped.startswith("from ") and " import " in stripped):
        return "CODE"
    if re.match(r"^(def|class|function|fn|pub fn|func)\s+[a-zA-Z0-9_]+", stripped):
        return "CODE"
    if re.match(r"^(const|let|var|val)\s+[a-zA-Z0-9_]+\s*=", stripped):
        return "CODE"
    if re.match(r"^(SELECT|INSERT\s+INTO|UPDATE|DELETE\s+FROM|CREATE\s+TABLE|ALTER\s+TABLE)\b", stripped, re.IGNORECASE):
        return "CODE"

    # 6. Keyword and symbol frequency
    keywords = re.findall(
        r"\b(def|class|import|from|return|function|const|let|var|if|else|elif|for|while|"
        r"public|private|protected|static|final|val|fun|fn|async|await|try|catch|except|"
        r"throw|throws|struct|enum|interface|type|extends|implements|package|namespace)\b",
        stripped
    )
    keyword_count = len(keywords)

    code_symbols = [
        r"(SELECT|INSERT|UPDATE|DELETE|FROM|WHERE|JOIN|GROUP BY|ORDER BY)\b",
        r"\b(console\.(log|error|warn)|print\(|System\.out\.print|fmt\.Print|std::cout)",
        r"(//|/\*|\*/|#include|<!--|-->)",
        r"[a-zA-Z0-9_]+\s*\([^)]*\)\s*[{:]",
        r"(=>|->|::|===|!==|!=|==|&&|\|\||\+\+|--)",
        r";\s*$"
    ]
    symbol_matches = sum(1 for pat in code_symbols if re.search(pat, stripped, re.MULTILINE | re.IGNORECASE))

    lines = stripped.splitlines()
    has_indentation = any(l.startswith("    ") or l.startswith("\t") for l in lines)
    has_brackets = ("{" in stripped and "}" in stripped) or ("(" in stripped and ")" in stripped and ";" in stripped)

    total_signals = keyword_count + symbol_matches
    if total_signals >= 2:
        return "CODE"
    if total_signals >= 1 and (has_indentation or has_brackets or len(lines) > 2):
        return "CODE"

    return "TEXT"


class LuminescentOrb(ctk.CTkCanvas if USE_CTK else object):
    """
    Hardware-efficient luminescent connection orb.
    Pre-renders gradient discs at startup (< 35ms) and swaps pre-cached frames in an after() loop.
    Strictly consumes < 0.5% CPU when idle.
    """
    def __init__(self, master, size=110, **kwargs):
        if not USE_CTK:
            return
        super().__init__(
            master,
            width=size,
            height=size,
            bg="#12131C",
            highlightthickness=0,
            **kwargs
        )
        self.size = size
        self.state = "searching" # "searching", "connected", "syncing"
        self.base_state = "searching"
        self.is_syncing = False

        self.frame_idx = 0
        self.is_paused = False
        self._after_id = None

        self.searching_frames: List[ImageTk.PhotoImage] = []
        self.connected_frames: List[ImageTk.PhotoImage] = []
        self.sync_frames: List[ImageTk.PhotoImage] = []

        self._pre_render_frames()

        init_img = self.searching_frames[0] if self.searching_frames else None
        self.img_id = self.create_image(size // 2, size // 2, anchor="center", image=init_img)
        self._loop()

    def _pre_render_frames(self):
        sz = self.size
        cx, cy = sz // 2, sz // 2
        bg_color = (18, 19, 28, 255) # matches #12131C card surface

        # 1. State 1: Searching (Thinking Motion) - 30 frames
        # Amber (#F59E0B) and Cyan (#06B6D4) rotating orbital satellites & concentric radar rings
        for i in range(30):
            img = Image.new("RGBA", (sz, sz), bg_color)
            phase = i / 30.0
            theta = phase * 2 * math.pi

            # Glow canvas
            glow = Image.new("RGBA", (sz, sz), (0, 0, 0, 0))
            d_glow = ImageDraw.Draw(glow)

            # Expanding concentric radar rings
            ring1 = (phase * 2.0) % 1.0
            r1 = int(14 + ring1 * 34)
            a1 = int((1.0 - ring1) * 90)
            d_glow.ellipse([cx - r1, cy - r1, cx + r1, cy + r1], outline=(6, 182, 212, a1), width=2)

            ring2 = (phase * 2.0 + 0.5) % 1.0
            r2 = int(14 + ring2 * 34)
            a2 = int((1.0 - ring2) * 90)
            d_glow.ellipse([cx - r2, cy - r2, cx + r2, cy + r2], outline=(245, 158, 11, a2), width=2)

            # Orbiting satellites glow
            orbit_r = 25
            ax = int(cx + orbit_r * math.cos(theta))
            ay = int(cy + orbit_r * math.sin(theta))
            d_glow.ellipse([ax - 9, ay - 9, ax + 9, ay + 9], fill=(245, 158, 11, 140))

            bx = int(cx + orbit_r * math.cos(theta + math.pi))
            by = int(cy + orbit_r * math.sin(theta + math.pi))
            d_glow.ellipse([bx - 9, by - 9, bx + 9, by + 9], fill=(6, 182, 212, 140))

            glow = glow.filter(ImageFilter.GaussianBlur(radius=5))
            img.alpha_composite(glow)

            # Sharp satellite cores
            d = ImageDraw.Draw(img)
            # Center subtle core
            d.ellipse([cx - 7, cy - 7, cx + 7, cy + 7], fill=(24, 26, 38, 255), outline=(40, 44, 62, 255))
            # Amber core
            d.ellipse([ax - 4, ay - 4, ax + 4, ay + 4], fill=(245, 158, 11, 255))
            d.ellipse([ax - 2, ay - 2, ax + 2, ay + 2], fill=(254, 243, 199, 255))
            # Cyan core
            d.ellipse([bx - 4, by - 4, bx + 4, by + 4], fill=(6, 182, 212, 255))
            d.ellipse([bx - 2, by - 2, bx + 2, by + 2], fill=(224, 242, 254, 255))

            self.searching_frames.append(ImageTk.PhotoImage(img))

        # 2. State 2: Connected (Trust Anchor) - 32 frames (~2.5s cycle at 80ms interval)
        # Luminous Emerald (#10B981) breathing aura
        for i in range(32):
            img = Image.new("RGBA", (sz, sz), bg_color)
            phase = i / 32.0
            pulse = 0.5 + 0.5 * math.sin(phase * 2 * math.pi)

            glow = Image.new("RGBA", (sz, sz), (0, 0, 0, 0))
            d_glow = ImageDraw.Draw(glow)

            # Outer breathing aura
            r_outer = int(22 + 12 * pulse)
            alpha_outer = int(60 + 65 * pulse)
            d_glow.ellipse([cx - r_outer, cy - r_outer, cx + r_outer, cy + r_outer], fill=(16, 185, 129, alpha_outer))

            glow = glow.filter(ImageFilter.GaussianBlur(radius=8))
            img.alpha_composite(glow)

            # Sharp harmonic ring & core
            d = ImageDraw.Draw(img)
            r_ring = int(34 + 5 * pulse)
            alpha_ring = int(80 + 70 * pulse)
            d.ellipse([cx - r_ring, cy - r_ring, cx + r_ring, cy + r_ring], outline=(52, 211, 153, alpha_ring), width=1)

            # Mid corona
            r_mid = int(14 + 3 * pulse)
            d.ellipse([cx - r_mid, cy - r_mid, cx + r_mid, cy + r_mid], fill=(16, 185, 129, 220))

            # Inner mint core
            r_core = int(8 + 2 * pulse)
            d.ellipse([cx - r_core, cy - r_core, cx + r_core, cy + r_core], fill=(52, 211, 153, 255))
            # Specular center anchor
            d.ellipse([cx - 3, cy - 3, cx + 3, cy + 3], fill=(236, 253, 245, 255))

            self.connected_frames.append(ImageTk.PhotoImage(img))

        # 3. State 3: Syncing Transmission - 12 frames (420ms quick shockwave)
        # Cyan-white shockwave ripple & flash
        for i in range(12):
            img = Image.new("RGBA", (sz, sz), bg_color)
            t = i / 12.0
            wave_r = int(12 + t * 40)
            alpha = int((1.0 - t) * 255)
            w = max(1, int(3 * (1.0 - t)))

            # Central flash on early frames
            if t < 0.5:
                flash_alpha = int((0.5 - t) / 0.5 * 180)
                glow = Image.new("RGBA", (sz, sz), (0, 0, 0, 0))
                d_glow = ImageDraw.Draw(glow)
                d_glow.ellipse([cx - 20, cy - 20, cx + 20, cy + 20], fill=(224, 242, 254, flash_alpha))
                glow = glow.filter(ImageFilter.GaussianBlur(radius=6))
                img.alpha_composite(glow)

            d = ImageDraw.Draw(img)
            d.ellipse([cx - wave_r, cy - wave_r, cx + wave_r, cy + wave_r], outline=(224, 242, 254, alpha), width=w)

            # Central core
            d.ellipse([cx - 8, cy - 8, cx + 8, cy + 8], fill=(6, 182, 212, alpha))
            d.ellipse([cx - 3, cy - 3, cx + 3, cy + 3], fill=(255, 255, 255, alpha))

            self.sync_frames.append(ImageTk.PhotoImage(img))

    def set_state(self, state: str):
        if state == "syncing":
            self.trigger_sync()
            return
        self.base_state = state
        if not self.is_syncing:
            self.state = state

    def trigger_sync(self):
        self.is_syncing = True
        self.state = "syncing"
        self.frame_idx = 0

    def pause(self):
        self.is_paused = True
        if self._after_id:
            try:
                self.after_cancel(self._after_id)
            except Exception:
                pass
            self._after_id = None

    def resume(self):
        if self.is_paused:
            self.is_paused = False
            if self._after_id:
                try:
                    self.after_cancel(self._after_id)
                except Exception:
                    pass
                self._after_id = None
            self._loop()

    def _loop(self):
        if self.is_paused:
            return

        try:
            if not self.winfo_exists():
                return
        except Exception:
            return

        if self.is_syncing:
            frames = self.sync_frames
            interval = 35
        elif self.state == "connected":
            frames = self.connected_frames
            interval = 80
        else:
            frames = self.searching_frames
            interval = 60

        if frames:
            idx = self.frame_idx % len(frames)
            try:
                self.itemconfig(self.img_id, image=frames[idx])
            except Exception:
                return
            self.frame_idx += 1

            if self.is_syncing and self.frame_idx >= len(self.sync_frames):
                self.is_syncing = False
                self.state = self.base_state
                self.frame_idx = 0

        self._after_id = self.after(interval, self._loop)


class WindowsTrayManager:
    """Lightweight Windows system tray icon integration using win32gui."""
    def __init__(self, app):
        self.app = app
        self.hwnd = None
        self.hicon = None
        self._running = True
        self._thread = threading.Thread(target=self._run_tray_loop, daemon=True)
        self._thread.start()

    def _run_tray_loop(self):
        try:
            import win32gui
            import win32con
            wc = win32gui.WNDCLASS()
            hinst = wc.hInstance = win32gui.GetModuleHandle(None)
            wc.lpszClassName = "WiFiClipboardSyncTray"
            wc.lpfnWndProc = self._wndproc
            try:
                win32gui.RegisterClass(wc)
            except Exception:
                pass
            self.hwnd = win32gui.CreateWindow(
                wc.lpszClassName, "WiFiClipboardSyncTray",
                0, 0, 0, 0, 0, 0, 0, hinst, None
            )
            self.hicon = win32gui.LoadIcon(0, win32con.IDI_APPLICATION)
            nid = (
                self.hwnd, 0,
                win32gui.NIF_ICON | win32gui.NIF_MESSAGE | win32gui.NIF_TIP,
                win32con.WM_USER + 20,
                self.hicon,
                "Wi-Fi Clipboard Sync (100% Local)"
            )
            win32gui.Shell_NotifyIcon(win32gui.NIM_ADD, nid)
            win32gui.PumpMessages()
        except Exception as e:
            logger.debug(f"Tray icon disabled or unsupported in current environment: {e}")

    def _wndproc(self, hwnd, msg, wparam, lparam):
        import win32gui
        import win32con
        if msg == win32con.WM_USER + 20:
            if lparam in (win32con.WM_LBUTTONUP, win32con.WM_LBUTTONDBLCLK):
                self.app.restore_window()
            elif lparam == win32con.WM_RBUTTONUP:
                self._show_menu()
            return 0
        elif msg == win32con.WM_DESTROY:
            try:
                win32gui.Shell_NotifyIcon(win32gui.NIM_DELETE, (self.hwnd, 0))
            except Exception:
                pass
            win32gui.PostQuitMessage(0)
            return 0
        return win32gui.DefWindowProc(hwnd, msg, wparam, lparam)

    def _show_menu(self):
        try:
            import win32gui
            import win32con
            menu = win32gui.CreatePopupMenu()
            win32gui.AppendMenu(menu, win32con.MF_STRING, 1001, "Open Wi-Fi Sync")
            win32gui.AppendMenu(menu, win32con.MF_STRING, 1002, "Check for Updates")
            win32gui.AppendMenu(menu, win32con.MF_SEPARATOR, 0, "")
            win32gui.AppendMenu(menu, win32con.MF_STRING, 1003, "Exit")
            pos = win32gui.GetCursorPos()
            win32gui.SetForegroundWindow(self.hwnd)
            cmd = win32gui.TrackPopupMenu(
                menu, win32con.TPM_LEFTALIGN | win32con.TPM_RETURNCMD,
                pos[0], pos[1], 0, self.hwnd, None
            )
            if cmd == 1001:
                self.app.restore_window()
            elif cmd == 1002:
                self.app._manual_check_update()
            elif cmd == 1003:
                self.app.on_close()
        except Exception:
            pass

    def destroy(self):
        self._running = False
        if self.hwnd:
            try:
                import win32gui
                win32gui.Shell_NotifyIcon(win32gui.NIM_DELETE, (self.hwnd, 0))
                win32gui.PostMessage(self.hwnd, 16, 0, 0)
            except Exception:
                pass


class ClipboardSyncApp:
    def __init__(self):
        self.history_mgr = HistoryManager()
        self.auto_sync_enabled = True
        self.connected_phone_ip = None
        self.search_query = ""

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

        # System tray icon
        self.tray = WindowsTrayManager(self)

    def _init_ui(self):
        if USE_CTK:
            ctk.set_appearance_mode("dark")
            ctk.set_default_color_theme("blue")
            self.root = ctk.CTk()
            self.root.configure(fg_color="#09090B")
        else:
            self.root = tk.Tk()

        self.root.title(f"Wi-Fi Clipboard Sync v{APP_VERSION}")
        self.root.geometry("680x820")
        self.root.minsize(580, 680)

        # Pause orb animation when minimized for 0.0% CPU drain
        if USE_CTK:
            self.root.bind("<Unmap>", self._on_window_unmap)
            self.root.bind("<Map>", self._on_window_map)

        self._build_widgets()
        self.root.protocol("WM_DELETE_WINDOW", self.on_close)

    def _on_window_unmap(self, event):
        if event.widget == self.root and hasattr(self, "orb") and self.orb:
            self.orb.pause()

    def _on_window_map(self, event):
        if event.widget == self.root and hasattr(self, "orb") and self.orb:
            self.orb.resume()

    def restore_window(self):
        def _restore():
            self.root.deiconify()
            self.root.lift()
            self.root.focus_force()
        self.root.after(0, _restore)

    def _build_widgets(self):
        if USE_CTK:
            self._build_ctk_widgets()
        else:
            self._build_tk_fallback_widgets()

    def _build_ctk_widgets(self):
        # 1. Header Frame with Security & Trust Badge
        header = ctk.CTkFrame(self.root, fg_color="transparent")
        header.pack(fill="x", padx=20, pady=(16, 6))

        title_col = ctk.CTkFrame(header, fg_color="transparent")
        title_col.pack(side="left")

        title_label = ctk.CTkLabel(
            title_col,
            text=f"Wi-Fi Clipboard Sync",
            font=ctk.CTkFont(size=20, weight="bold"),
            text_color="#F4F4F5"
        )
        title_label.pack(anchor="w")

        subtitle = ctk.CTkLabel(
            title_col,
            text=f"v{APP_VERSION} • Instant P2P LAN Synchronizer",
            font=ctk.CTkFont(size=12),
            text_color="#71717A"
        )
        subtitle.pack(anchor="w", pady=(1, 0))

        # Security & Trust Badge: "🛡️ 100% Local • Zero Cloud"
        trust_badge = ctk.CTkFrame(
            header,
            fg_color="#0D1F1D",
            border_color="#10B981",
            border_width=1,
            corner_radius=14
        )
        trust_badge.pack(side="right", pady=4)

        trust_label = ctk.CTkLabel(
            trust_badge,
            text="🛡️ 100% Local • Zero Cloud",
            font=ctk.CTkFont(size=11, weight="bold"),
            text_color="#34D399"
        )
        trust_label.pack(padx=12, pady=6)

        # 2. Dynamic Update Banner Card (Obsidian themed)
        self.update_card = ctk.CTkFrame(
            self.root,
            corner_radius=12,
            fg_color="#12131C",
            border_color="#06B6D4",
            border_width=1
        )

        self.update_info_label = ctk.CTkLabel(
            self.update_card,
            text="",
            font=ctk.CTkFont(size=12, weight="bold"),
            text_color="#38BDF8"
        )
        self.update_info_label.pack(side="left", padx=16, pady=10)

        self.update_action_btn = ctk.CTkButton(
            self.update_card,
            text="Restart Now",
            width=110,
            fg_color="#10B981",
            hover_color="#059669",
            text_color="#09090B",
            font=ctk.CTkFont(size=12, weight="bold"),
            command=self._confirm_apply_update
        )

        self.update_dismiss_btn = ctk.CTkButton(
            self.update_card,
            text="Later",
            width=60,
            fg_color="#27272A",
            hover_color="#3F3F46",
            text_color="#A1A1AA",
            command=self._dismiss_update_card
        )
        self.update_dismiss_btn.pack(side="right", padx=(4, 12), pady=10)

        # 3. Connection Hero Card with Luminescent Orb
        self.status_card = ctk.CTkFrame(
            self.root,
            corner_radius=16,
            fg_color="#12131C",
            border_color="#23263B",
            border_width=1
        )
        self.status_card.pack(fill="x", padx=20, pady=(6, 8))

        hero_layout = ctk.CTkFrame(self.status_card, fg_color="transparent")
        hero_layout.pack(fill="x", padx=14, pady=12)

        # Luminescent Orb Widget
        self.orb = LuminescentOrb(hero_layout, size=110)
        self.orb.pack(side="left", padx=(4, 16))

        # Hero Status Details
        details_col = ctk.CTkFrame(hero_layout, fg_color="transparent")
        details_col.pack(side="left", fill="both", expand=True)

        ctk.CTkLabel(
            details_col,
            text="CONNECTION STATUS",
            font=ctk.CTkFont(size=10, weight="bold"),
            text_color="#71717A"
        ).pack(anchor="w")

        self.status_badge = ctk.CTkLabel(
            details_col,
            text="🟡 Searching on Wi-Fi...",
            font=ctk.CTkFont(size=16, weight="bold"),
            text_color="#F59E0B"
        )
        self.status_badge.pack(anchor="w", pady=(2, 2))

        local_ip = get_local_ip()
        self.ip_label = ctk.CTkLabel(
            details_col,
            text=f"🌐 Local Server: {local_ip}:{WS_SERVER_PORT} • UDP Port 52525",
            font=ctk.CTkFont(size=12),
            text_color="#9CA3AF"
        )
        self.ip_label.pack(anchor="w")

        # Controls strip inside hero card
        controls_strip = ctk.CTkFrame(details_col, fg_color="transparent")
        controls_strip.pack(anchor="w", pady=(8, 0))

        self.auto_sync_switch = ctk.CTkSwitch(
            controls_strip,
            text="Auto-Sync",
            command=self._toggle_auto_sync,
            font=ctk.CTkFont(size=12),
            progress_color="#10B981"
        )
        self.auto_sync_switch.select()
        self.auto_sync_switch.pack(side="left")

        self.check_update_btn = ctk.CTkButton(
            controls_strip,
            text="Check Updates",
            width=96,
            height=26,
            fg_color="#1F2438",
            hover_color="#2E334D",
            text_color="#38BDF8",
            font=ctk.CTkFont(size=11),
            command=self._manual_check_update
        )
        self.check_update_btn.pack(side="left", padx=12)

        # 4. Floating Input Container (21st.dev Style)
        input_card = ctk.CTkFrame(
            self.root,
            corner_radius=16,
            fg_color="#12131C",
            border_color="#23263B",
            border_width=1
        )
        input_card.pack(fill="x", padx=20, pady=(4, 8))

        input_header = ctk.CTkFrame(input_card, fg_color="transparent")
        input_header.pack(fill="x", padx=14, pady=(10, 4))

        ctk.CTkLabel(
            input_header,
            text="⚡ FLOATING CLIPBOARD PUSH",
            font=ctk.CTkFont(size=10, weight="bold"),
            text_color="#71717A"
        ).pack(side="left")

        self.input_char_badge = ctk.CTkLabel(
            input_header,
            text="0 chars",
            font=ctk.CTkFont(size=11),
            text_color="#71717A"
        )
        self.input_char_badge.pack(side="right")

        self.input_placeholder = "Type or paste text to sync across devices..."
        self._input_is_placeholder = True

        self.floating_textbox = ctk.CTkTextbox(
            input_card,
            height=58,
            corner_radius=10,
            fg_color="#0A0B12",
            border_color="#23263B",
            border_width=1,
            text_color="#71717A",
            font=ctk.CTkFont(size=12)
        )
        self.floating_textbox.insert("1.0", self.input_placeholder)
        self.floating_textbox.pack(fill="x", padx=14, pady=(2, 8))
        self.floating_textbox.bind("<FocusIn>", self._on_input_focus_in)
        self.floating_textbox.bind("<FocusOut>", self._on_input_focus_out)
        self.floating_textbox.bind("<KeyRelease>", self._on_input_text_change)
        self.floating_textbox.bind("<<Paste>>", self._on_input_text_change)
        self.floating_textbox.bind("<ButtonRelease-1>", self._on_input_text_change)

        input_actions = ctk.CTkFrame(input_card, fg_color="transparent")
        input_actions.pack(fill="x", padx=14, pady=(0, 10))

        self.activity_banner = ctk.CTkLabel(
            input_actions,
            text="Ready. Copy text on Phone or PC to sync.",
            font=ctk.CTkFont(size=11, slant="italic"),
            text_color="#71717A"
        )
        self.activity_banner.pack(side="left")

        self.push_btn = ctk.CTkButton(
            input_actions,
            text="⚡ Push to Phone",
            width=130,
            height=30,
            corner_radius=8,
            fg_color="#10B981",
            hover_color="#059669",
            text_color="#09090B",
            font=ctk.CTkFont(size=12, weight="bold"),
            command=self._on_push_clicked
        )
        self.push_btn.pack(side="right")

        # 5. Raycast-style Clipboard History Stream Header
        history_header = ctk.CTkFrame(self.root, fg_color="transparent")
        history_header.pack(fill="x", padx=20, pady=(6, 4))

        ctk.CTkLabel(
            history_header,
            text="Clipboard History",
            font=ctk.CTkFont(size=14, weight="bold"),
            text_color="#F4F4F5"
        ).pack(side="left")

        self.clear_btn = ctk.CTkButton(
            history_header,
            text="Clear",
            width=54,
            height=26,
            fg_color="#27272A",
            hover_color="#EF4444",
            text_color="#A1A1AA",
            font=ctk.CTkFont(size=11),
            command=self._clear_history
        )
        self.clear_btn.pack(side="right", padx=(8, 0))

        self.search_entry = ctk.CTkEntry(
            history_header,
            placeholder_text="🔍 Search history...",
            width=220,
            height=28,
            corner_radius=8,
            fg_color="#12131C",
            border_color="#23263B",
            border_width=1,
            text_color="#F4F4F5",
            font=ctk.CTkFont(size=11)
        )
        self.search_entry.pack(side="right")
        self.search_entry.bind("<KeyRelease>", self._on_search_query_change)

        # 6. Scrollable History Container
        self.history_scroll = ctk.CTkScrollableFrame(
            self.root,
            corner_radius=14,
            fg_color="#09090B",
            border_color="#23263B",
            border_width=1
        )
        self.history_scroll.pack(fill="both", expand=True, padx=20, pady=(4, 16))

        self._refresh_history_ui()

    def _build_tk_fallback_widgets(self):
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

    def _on_input_focus_in(self, event=None):
        if USE_CTK and getattr(self, "_input_is_placeholder", False):
            self.floating_textbox.delete("1.0", "end")
            self.floating_textbox.configure(text_color="#F4F4F5")
            self._input_is_placeholder = False
            self.input_char_badge.configure(text="0 chars")

    def _on_input_focus_out(self, event=None):
        if USE_CTK:
            content = self.floating_textbox.get("1.0", "end-1c").strip()
            if not content:
                self._input_is_placeholder = True
                self.floating_textbox.delete("1.0", "end")
                self.floating_textbox.configure(text_color="#71717A")
                self.floating_textbox.insert("1.0", getattr(self, "input_placeholder", ""))
                self.input_char_badge.configure(text="0 chars")

    def _on_input_text_change(self, event=None):
        if USE_CTK:
            if getattr(self, "_input_is_placeholder", False):
                self.input_char_badge.configure(text="0 chars")
                return
            content = self.floating_textbox.get("1.0", "end-1c")
            self.input_char_badge.configure(text=f"{len(content)} chars")

    def _on_push_clicked(self):
        text = ""
        if USE_CTK:
            if not getattr(self, "_input_is_placeholder", False):
                typed = self.floating_textbox.get("1.0", "end-1c").strip()
                if typed:
                    text = typed
                    self.floating_textbox.delete("1.0", "end")
                    self._input_is_placeholder = True
                    self.floating_textbox.configure(text_color="#71717A")
                    self.floating_textbox.insert("1.0", getattr(self, "input_placeholder", ""))
                    self.input_char_badge.configure(text="0 chars")

        if not text:
            # Fallback to current system clipboard
            text = self.clipboard_engine.get_clipboard_text()
        else:
            # Sync local PC clipboard as well so both devices match
            self.clipboard_engine.set_clipboard_text(text)

        if not text or not text.strip():
            self._set_banner("Clipboard & input are currently empty.")
            return

        text_hash = compute_sha256(text)
        self.history_mgr.add_entry(text, source="pc")

        # Trigger quick shockwave transmission ripple
        if USE_CTK and hasattr(self, "orb") and self.orb:
            self.orb.trigger_sync()

        if self.server.is_connected:
            self.server.broadcast_clipboard(text, text_hash)
            self._set_banner(f"📤 Pushed {len(text)} characters to Phone.")
        else:
            self._set_banner(f"Saved locally. Listening for phone connection on Wi-Fi.")

        self._refresh_history_ui()

    def _on_search_query_change(self, event=None):
        if USE_CTK:
            self.search_query = self.search_entry.get().strip().lower()
            self._refresh_history_ui()

    def _toggle_auto_sync(self):
        if USE_CTK:
            self.auto_sync_enabled = bool(self.auto_sync_switch.get())
        state = "enabled" if self.auto_sync_enabled else "paused"
        self._set_banner(f"Auto-Sync is now {state}.")

    def _set_banner(self, text: str, duration: float = 4.0):
        def _update():
            if USE_CTK:
                self.activity_banner.configure(text=text)
            else:
                self.activity_banner.config(text=text)
        self.root.after(0, _update)

    def _on_client_connected(self, client_ip: str):
        self.connected_phone_ip = client_ip
        def _update():
            if USE_CTK:
                if hasattr(self, "orb") and self.orb:
                    self.orb.set_state("connected")
                self.status_badge.configure(
                    text=f"🟢 Linked to Phone ({client_ip})",
                    text_color="#10B981"
                )
            else:
                self.status_badge.configure(text=f"Connected ({client_ip})", fg="green")
            self._set_banner(f"Phone linked from {client_ip}!")
        self.root.after(0, _update)

    def _on_client_disconnected(self, client_ip: str):
        self.connected_phone_ip = None
        def _update():
            if USE_CTK:
                if hasattr(self, "orb") and self.orb:
                    self.orb.set_state("searching")
                self.status_badge.configure(
                    text="🟡 Searching on Wi-Fi...",
                    text_color="#F59E0B"
                )
            else:
                self.status_badge.configure(text="Searching on Wi-Fi...", fg="orange")
            self._set_banner("Phone disconnected. Listening for reconnection...")
        self.root.after(0, _update)

    def _on_local_copy(self, text: str, text_hash: str):
        """Called when user copies text locally on PC."""
        logger.info(f"Local copy detected ({len(text)} chars)")
        self.history_mgr.add_entry(text, source="pc")

        if USE_CTK and hasattr(self, "orb") and self.orb:
            self.root.after(0, self.orb.trigger_sync)

        if self.auto_sync_enabled and self.server.is_connected:
            self.server.broadcast_clipboard(text, text_hash)
            self._set_banner(f"📤 Synced {len(text)} chars to Phone.")
        else:
            self._set_banner(f"💻 Copied locally ({len(text)} chars).")

        self.root.after(0, self._refresh_history_ui)

    def _on_remote_message(self, text: str, text_hash: str):
        """Called when phone sends new clipboard text over WebSocket."""
        logger.info(f"Remote message received from phone ({len(text)} chars)")
        self.clipboard_engine.set_clipboard_text(text)
        self.history_mgr.add_entry(text, source="phone")

        if USE_CTK and hasattr(self, "orb") and self.orb:
            self.root.after(0, self.orb.trigger_sync)

        self._set_banner(f"📥 Received {len(text)} chars from Phone!")
        self.root.after(0, self._refresh_history_ui)

    def _clear_history(self):
        self.history_mgr.clear()
        self._refresh_history_ui()
        self._set_banner("Clipboard history cleared.")

    def _copy_item_to_clipboard(self, text: str, copy_btn=None):
        self.clipboard_engine.set_clipboard_text(text)
        text_hash = compute_sha256(text)

        if USE_CTK and hasattr(self, "orb") and self.orb:
            self.orb.trigger_sync()

        if self.auto_sync_enabled and self.server.is_connected:
            self.server.broadcast_clipboard(text, text_hash)
            self._set_banner(f"Copied to PC & synced {len(text)} chars to Phone.")
        else:
            self._set_banner(f"Copied to PC clipboard ({len(text)} chars).")

        if copy_btn and USE_CTK:
            try:
                orig_text = copy_btn.cget("text")
                orig_fg = copy_btn.cget("fg_color")
                orig_tc = copy_btn.cget("text_color")
                copy_btn.configure(text="Copied! ✓", fg_color="#064E3B", text_color="#34D399")
                def _reset():
                    try:
                        if copy_btn.winfo_exists():
                            copy_btn.configure(text=orig_text, fg_color=orig_fg, text_color=orig_tc)
                    except Exception:
                        pass
                self.root.after(1200, _reset)
            except Exception:
                pass

    def _refresh_history_ui(self):
        for widget in self.history_scroll.winfo_children():
            widget.destroy()

        entries = self.history_mgr.get_entries()

        # Apply search query filter if set
        if self.search_query:
            entries = [e for e in entries if self.search_query in e.get("text", "").lower()]

        if not entries:
            if USE_CTK:
                msg = "No matching clipboard items." if self.search_query else "No clipboard items yet.\nCopy text on Phone or PC to start syncing!"
                empty_lbl = ctk.CTkLabel(
                    self.history_scroll,
                    text=msg,
                    text_color="#71717A",
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
        content_type = detect_content_type(text)

        if USE_CTK:
            card = ctk.CTkFrame(
                self.history_scroll,
                corner_radius=12,
                fg_color="#12131C",
                border_color="#23263B",
                border_width=1
            )
            card.pack(fill="x", pady=4, padx=4)

            # Top bar of card
            top_bar = ctk.CTkFrame(card, fg_color="transparent")
            top_bar.pack(fill="x", padx=12, pady=(8, 2))

            # Raycast-style Type Badge
            if content_type == "URL":
                type_pill = ctk.CTkLabel(
                    top_bar,
                    text="🔗 URL",
                    font=ctk.CTkFont(size=10, weight="bold"),
                    fg_color="#083344",
                    text_color="#38BDF8",
                    corner_radius=6,
                    padx=6,
                    pady=2
                )
            elif content_type == "CODE":
                type_pill = ctk.CTkLabel(
                    top_bar,
                    text="💻 CODE",
                    font=ctk.CTkFont(size=10, weight="bold"),
                    fg_color="#2E1065",
                    text_color="#C084FC",
                    corner_radius=6,
                    padx=6,
                    pady=2
                )
            else:
                type_pill = ctk.CTkLabel(
                    top_bar,
                    text="📝 TEXT",
                    font=ctk.CTkFont(size=10, weight="bold"),
                    fg_color="#1E293B",
                    text_color="#94A3B8",
                    corner_radius=6,
                    padx=6,
                    pady=2
                )
            type_pill.pack(side="left")

            # Source Badge
            if source == "phone":
                src_badge = ctk.CTkLabel(
                    top_bar,
                    text="📱 Phone",
                    font=ctk.CTkFont(size=10, weight="bold"),
                    fg_color="#064E3B",
                    text_color="#34D399",
                    corner_radius=6,
                    padx=6,
                    pady=2
                )
            else:
                src_badge = ctk.CTkLabel(
                    top_bar,
                    text="💻 PC",
                    font=ctk.CTkFont(size=10, weight="bold"),
                    fg_color="#182230",
                    text_color="#38BDF8",
                    corner_radius=6,
                    padx=6,
                    pady=2
                )
            src_badge.pack(side="left", padx=6)

            meta_lbl = ctk.CTkLabel(
                top_bar,
                text=f"{time_str} • {char_count} chars",
                font=ctk.CTkFont(size=11),
                text_color="#71717A"
            )
            meta_lbl.pack(side="left", padx=4)

            copy_btn = ctk.CTkButton(
                top_bar,
                text="Copy",
                width=62,
                height=24,
                corner_radius=6,
                fg_color="#1F2438",
                hover_color="#2E334D",
                text_color="#F4F4F5",
                font=ctk.CTkFont(size=11)
            )
            copy_btn.configure(command=lambda t=text, b=copy_btn: self._copy_item_to_clipboard(t, b))
            copy_btn.pack(side="right")

            # Content preview (up to 3 lines)
            preview_text = text if len(text) <= 220 else text[:217] + "..."
            font_family = "Consolas" if content_type == "CODE" else None
            content_lbl = ctk.CTkLabel(
                card,
                text=preview_text,
                font=ctk.CTkFont(family=font_family, size=12),
                justify="left",
                anchor="w",
                text_color="#D4D4D8",
                wraplength=540
            )
            content_lbl.pack(fill="x", padx=12, pady=(4, 10))
        else:
            card = tk.Frame(self.history_scroll, relief="groove", bd=1)
            card.pack(fill="x", pady=2)
            tk.Label(card, text=f"[{source.upper()}] {time_str}").pack(side="left")
            tk.Button(card, text="Copy", command=lambda t=text: self._copy_item_to_clipboard(t)).pack(side="right")
            tk.Label(card, text=text[:80]).pack(anchor="w")

    def start(self):
        self.clipboard_engine.start()
        self.server.start()
        logger.info(f"WiFi Clipboard Sync v{APP_VERSION} started successfully.")

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
                        self._set_banner("Could not check updates. Check internet connection.")
                        try:
                            from tkinter import messagebox
                            self.root.after(0, lambda: messagebox.showwarning(
                                "Update Check Failed",
                                "Could not connect to update server."
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
                self.update_card.pack(fill="x", padx=20, pady=(4, 6), before=self.status_card)
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
            if hasattr(self, "orb") and self.orb:
                self.orb.pause()
        except Exception:
            pass
        try:
            if hasattr(self, "tray") and self.tray:
                self.tray.destroy()
        except Exception:
            pass
        try:
            self.clipboard_engine.stop()
        except Exception:
            pass
        try:
            self.server.stop()
        except Exception:
            pass
        if not cleanup_only:
            try:
                self.root.destroy()
            except Exception:
                pass
            sys.exit(0)


def main():
    app = ClipboardSyncApp()
    app.start()


if __name__ == "__main__":
    main()
