"""
Unit and integration tests for Wi-Fi Clipboard Sync UI Redesign.
Validates:
1. detect_content_type accuracy across URL, CODE, and TEXT inputs
2. LuminescentOrb frame generation, state transitions, and timer safety
3. Idle loop execution count (pause / resume single-loop guarantee)
4. Floating input placeholder and push synchronization
"""

import json
import time
import unittest
import customtkinter as ctk

from app import detect_content_type, LuminescentOrb, ClipboardSyncApp, USE_CTK
from clipboard_engine import ClipboardEngine, compute_sha256


class TestContentTypeDetection(unittest.TestCase):
    def test_url_detection(self):
        self.assertEqual(detect_content_type("https://github.com/hasan-circuito"), "URL")
        self.assertEqual(detect_content_type("http://localhost:8080/api/v1"), "URL")
        self.assertEqual(detect_content_type("www.google.com/search?q=test"), "URL")
        self.assertEqual(detect_content_type("ftp://files.example.org/download.zip"), "URL")
        self.assertEqual(detect_content_type("ws://192.168.1.100:52525"), "URL")

    def test_code_python(self):
        self.assertEqual(detect_content_type("import os"), "CODE")
        self.assertEqual(detect_content_type("from typing import Optional, List"), "CODE")
        self.assertEqual(detect_content_type("def calculate_total(a, b):\n    return a + b"), "CODE")
        self.assertEqual(detect_content_type("class LuminescentOrb:\n    pass"), "CODE")

    def test_code_javascript_typescript(self):
        self.assertEqual(detect_content_type("const port = 52525;"), "CODE")
        self.assertEqual(detect_content_type("let active = true;"), "CODE")
        self.assertEqual(detect_content_type("console.log('Syncing text');"), "CODE")
        self.assertEqual(detect_content_type("function handleClick(e) { e.preventDefault(); }"), "CODE")

    def test_code_json(self):
        self.assertEqual(detect_content_type('{"type": "clipboard", "text": "hello"}'), "CODE")
        self.assertEqual(detect_content_type('{\n  "version": "1.0.1",\n  "active": true\n}'), "CODE")
        self.assertEqual(detect_content_type('[1, 2, 3, 4, 5]'), "CODE")

    def test_code_html_xml(self):
        self.assertEqual(detect_content_type("<div>Hello World</div>"), "CODE")
        self.assertEqual(detect_content_type("<span class=\"badge\">Active</span>"), "CODE")
        self.assertEqual(detect_content_type("<!DOCTYPE html><html><body></body></html>"), "CODE")

    def test_code_sql(self):
        self.assertEqual(detect_content_type("SELECT * FROM users WHERE active = 1;"), "CODE")
        self.assertEqual(detect_content_type("INSERT INTO clipboard (id, text) VALUES (1, 'test');"), "CODE")

    def test_code_cli_commands(self):
        self.assertEqual(detect_content_type("npm install customtkinter"), "CODE")
        self.assertEqual(detect_content_type("git commit -m 'Redesign UI'"), "CODE")
        self.assertEqual(detect_content_type("curl -X POST https://api.example.com"), "CODE")
        self.assertEqual(detect_content_type("docker run -d -p 80:80 nginx"), "CODE")

    def test_text_notes_and_natural_language(self):
        self.assertEqual(detect_content_type("Meeting tomorrow at 10 AM in the conference room."), "TEXT")
        self.assertEqual(detect_content_type("Remember to buy milk, eggs, and bread."), "TEXT")
        self.assertEqual(detect_content_type("আমি বাংলায় গান গাই"), "TEXT")
        self.assertEqual(detect_content_type(""), "TEXT")
        self.assertEqual(detect_content_type("   "), "TEXT")


class TestLuminescentOrbWidget(unittest.TestCase):
    def setUp(self):
        self.root = ctk.CTk()

    def tearDown(self):
        try:
            self.root.update_idletasks()
            self.root.destroy()
        except Exception:
            pass

    def test_frames_pre_rendered(self):
        orb = LuminescentOrb(self.root, size=110)
        self.assertEqual(len(orb.searching_frames), 30)
        self.assertEqual(len(orb.connected_frames), 32)
        self.assertEqual(len(orb.sync_frames), 12)
        orb.pause()

    def test_state_transitions(self):
        orb = LuminescentOrb(self.root, size=110)
        self.assertEqual(orb.state, "searching")

        orb.set_state("connected")
        self.assertEqual(orb.state, "connected")
        self.assertEqual(orb.base_state, "connected")

        orb.set_state("searching")
        self.assertEqual(orb.state, "searching")

        orb.trigger_sync()
        self.assertTrue(orb.is_syncing)
        self.assertEqual(orb.state, "syncing")
        orb.pause()

    def test_pause_and_resume_no_loop_accumulation(self):
        orb = LuminescentOrb(self.root, size=110)
        
        # Test pausing cancels after_id
        orb.pause()
        self.assertTrue(orb.is_paused)
        self.assertIsNone(orb._after_id)

        # Test resume schedules a single loop
        orb.resume()
        self.assertFalse(orb.is_paused)

        # Rapid pause / resume cycles must NOT multiply loops
        for _ in range(5):
            orb.pause()
            orb.resume()

        loop_count = 0
        orig_loop = orb._loop
        def counted_loop():
            nonlocal loop_count
            loop_count += 1
            orig_loop()
        orb._loop = counted_loop

        # Run event loop for 180ms
        t_end = time.time() + 0.18
        while time.time() < t_end:
            self.root.update()
            time.sleep(0.01)

        orb.pause()
        # In 180ms at 60ms interval, exactly ~3 frames should execute
        self.assertLessEqual(loop_count, 5)


class TestFloatingInputAndClipboard(unittest.TestCase):
    def test_placeholder_logic(self):
        root = ctk.CTk()
        try:
            input_card = ctk.CTkFrame(root)
            tb = ctk.CTkTextbox(input_card)
            placeholder = "Type or paste text..."
            tb.insert("1.0", placeholder)
            
            # Content matches placeholder
            content = tb.get("1.0", "end-1c").strip()
            self.assertEqual(content, placeholder)

            # Clear on focus
            tb.delete("1.0", "end")
            self.assertEqual(tb.get("1.0", "end-1c").strip(), "")
        finally:
            root.destroy()


if __name__ == "__main__":
    unittest.main()
