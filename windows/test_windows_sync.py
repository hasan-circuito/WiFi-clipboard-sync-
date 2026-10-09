"""
Automated unit and integration test suite for Windows Wi-Fi Clipboard Sync.
Tests:
1. HistoryManager persistence and deduplication
2. ClipboardEngine Win32 listener and echo suppression
3. SyncServer WebSocket communication and UDP Beacon
4. Full bidirectional sync and echo cancellation
"""

import asyncio
import json
import os
import socket
import tempfile
import time
import unittest
import websockets
import win32clipboard

from history_manager import HistoryManager
from clipboard_engine import ClipboardEngine, compute_sha256
from server import SyncServer, UDPBeacon, get_local_ip, UDP_DISCOVERY_PORT, WS_SERVER_PORT


class TestHistoryManager(unittest.TestCase):
    def setUp(self):
        self.temp_file = tempfile.NamedTemporaryFile(delete=False, suffix=".json")
        self.temp_file.close()
        self.mgr = HistoryManager(storage_path=self.temp_file.name)

    def tearDown(self):
        if os.path.exists(self.temp_file.name):
            try:
                os.remove(self.temp_file.name)
            except Exception:
                pass

    def test_add_and_retrieve_entries(self):
        entry1 = self.mgr.add_entry("First clip item", source="pc")
        self.assertEqual(entry1["text"], "First clip item")
        self.assertEqual(entry1["source"], "pc")

        entry2 = self.mgr.add_entry("Second clip item from phone", source="phone")
        self.assertEqual(entry2["source"], "phone")

        entries = self.mgr.get_entries()
        self.assertEqual(len(entries), 2)
        # Most recent first
        self.assertEqual(entries[0]["text"], "Second clip item from phone")

    def test_deduplication_consecutive(self):
        self.mgr.add_entry("Duplicate test", source="pc")
        self.mgr.add_entry("Duplicate test", source="pc")
        self.assertEqual(len(self.mgr.get_entries()), 1)

    def test_clear_and_persistence(self):
        self.mgr.add_entry("Persistent item", source="pc")
        # Reload from disk
        mgr2 = HistoryManager(storage_path=self.temp_file.name)
        self.assertEqual(len(mgr2.get_entries()), 1)
        self.assertEqual(mgr2.get_entries()[0]["text"], "Persistent item")

        self.mgr.clear()
        self.assertEqual(len(self.mgr.get_entries()), 0)

    def test_whitespace_preservation(self):
        code_snippet = "    def test():\n        return True\n"
        entry = self.mgr.add_entry(code_snippet, source="pc")
        self.assertEqual(entry["text"], code_snippet)
        self.assertIn("def test():", entry["preview"])



class TestClipboardEngine(unittest.TestCase):
    def setUp(self):
        self.copied_events = []
        self.engine = ClipboardEngine(on_local_copy=self._on_copy)
        self.engine.start()
        time.sleep(0.3)

    def tearDown(self):
        self.engine.stop()

    def _on_copy(self, text, text_hash):
        self.copied_events.append((text, text_hash))

    def test_sha256_computation(self):
        text = "Hello Antigravity!"
        h = compute_sha256(text)
        self.assertIsInstance(h, str)
        self.assertEqual(len(h), 64)

    def test_set_and_read_clipboard(self):
        sample_text = f"SampleText_{time.time()}"
        success = self.engine.set_clipboard_text(sample_text)
        self.assertTrue(success)

        read_text = self.engine.get_clipboard_text()
        self.assertEqual(read_text, sample_text)

    def test_echo_suppression(self):
        # Setting clipboard through engine MUST suppress local copy event
        sample_text = f"EchoTest_{time.time()}"
        self.engine.set_clipboard_text(sample_text)
        time.sleep(0.4)
        # Should not have triggered on_local_copy because it was suppressed
        self.assertEqual(len(self.copied_events), 0)

    def test_unicode_bengali(self):
        bengali_text = "আমি একটা টুলস বানাতে চাচ্ছি"
        success = self.engine.set_clipboard_text(bengali_text)
        self.assertTrue(success)
        read_text = self.engine.get_clipboard_text()
        self.assertEqual(read_text, bengali_text)
        h = compute_sha256(bengali_text)
        self.assertEqual(h, "d2eb2488e4f0d24d15ae05bd8e86d1fd389a72c34e84b284f8867547fc789ece")


class TestSyncServer(unittest.TestCase):
    def setUp(self):
        self.test_port = 52530  # Use distinct port for tests
        self.connected_ips = []
        self.received_messages = []

        self.server = SyncServer(
            port=self.test_port,
            on_client_connected=lambda ip: self.connected_ips.append(ip),
            on_client_disconnected=lambda ip: None,
            on_message_received=lambda t, h: self.received_messages.append((t, h))
        )
        self.server.start()
        time.sleep(0.4)

    def tearDown(self):
        self.server.stop()

    def test_websocket_client_exchange(self):
        async def run_ws_client():
            uri = f"ws://127.0.0.1:{self.test_port}"
            async with websockets.connect(uri) as ws:
                # 1. Handshake received from server
                msg = await asyncio.wait_for(ws.recv(), timeout=2.0)
                handshake = json.loads(msg)
                self.assertEqual(handshake.get("type"), "handshake")

                # 2. Client sends clipboard to server
                client_text = "TextFromSimulatedPhone"
                client_hash = compute_sha256(client_text)
                await ws.send(json.dumps({
                    "type": "clipboard",
                    "text": client_text,
                    "hash": client_hash
                }))
                await asyncio.sleep(0.3)

                # 3. Server broadcasts text to client
                server_text = "TextFromSimulatedPC"
                server_hash = compute_sha256(server_text)
                self.server.broadcast_clipboard(server_text, server_hash)

                broadcast_msg = await asyncio.wait_for(ws.recv(), timeout=2.0)
                data = json.loads(broadcast_msg)
                self.assertEqual(data.get("type"), "clipboard")
                self.assertEqual(data.get("text"), server_text)

        asyncio.run(run_ws_client())

        # Verify server recorded the client message
        self.assertGreaterEqual(len(self.received_messages), 1)
        self.assertEqual(self.received_messages[0][0], "TextFromSimulatedPhone")

    def test_target_facing_ip(self):
        ip = get_local_ip("192.168.1.1")
        self.assertIsInstance(ip, str)
        self.assertFalse(ip.startswith("127."))



class TestEndToEndSync(unittest.TestCase):
    def test_end_to_end_loop_prevention(self):
        """
        Verify that receiving text from phone updates PC clipboard,
        and does NOT bounce an echo back to the phone.
        """
        test_port = 52531
        outbound_broadcasts = []

        server = SyncServer(port=test_port)
        engine = ClipboardEngine()

        def on_phone_msg(text, h):
            engine.set_clipboard_text(text)

        server.on_message_received = on_phone_msg

        def on_pc_copy(text, h):
            server.broadcast_clipboard(text, h)

        engine.on_local_copy = on_pc_copy

        server.start()
        engine.start()
        time.sleep(0.4)

        try:
            async def simulated_phone():
                uri = f"ws://127.0.0.1:{test_port}"
                async with websockets.connect(uri) as ws:
                    # Handshake
                    await ws.recv()

                    # Phone sends text to PC
                    sent_text = f"E2E_Phone_Text_{time.time()}"
                    await ws.send(json.dumps({
                        "type": "clipboard",
                        "text": sent_text,
                        "hash": compute_sha256(sent_text)
                    }))

                    # Wait to ensure no echoed message is sent back
                    try:
                        echoed = await asyncio.wait_for(ws.recv(), timeout=1.0)
                        outbound_broadcasts.append(echoed)
                    except asyncio.TimeoutError:
                        pass # Expected: no echo should arrive!

                    # Verify that PC clipboard now holds the sent_text
                    pc_clip = engine.get_clipboard_text()
                    self.assertEqual(pc_clip, sent_text)

            asyncio.run(simulated_phone())

            # outbound_broadcasts must be empty: loop prevention succeeded!
            self.assertEqual(len(outbound_broadcasts), 0)

        finally:
            engine.stop()
            server.stop()


class TestUpdater(unittest.TestCase):
    def test_version_tuple_parsing(self):
        from updater import parse_version_tuple
        self.assertEqual(parse_version_tuple("1.0.0"), (1, 0, 0))
        self.assertEqual(parse_version_tuple("v1.0.1"), (1, 0, 1))
        self.assertEqual(parse_version_tuple("V2.3"), (2, 3, 0))
        self.assertEqual(parse_version_tuple("1.2.3-beta"), (1, 2, 3))
        self.assertEqual(parse_version_tuple("1.0.10"), (1, 0, 10))

    def test_is_newer_version(self):
        from updater import is_newer_version
        self.assertTrue(is_newer_version("1.0.1", "1.0.0"))
        self.assertTrue(is_newer_version("v1.1.0", "1.0.9"))
        self.assertTrue(is_newer_version("2.0.0", "1.99.99"))
        self.assertTrue(is_newer_version("1.0.10", "1.0.9"))

        # Not newer
        self.assertFalse(is_newer_version("1.0.0", "1.0.0"))
        self.assertFalse(is_newer_version("0.9.9", "1.0.0"))
        self.assertFalse(is_newer_version("1.0.0", "1.0.1"))

    def test_parse_release_info_custom_schema(self):
        from updater import parse_release_info
        sample_data = {
            "tag_name": "v1.0.1",
            "version": "1.0.1",
            "name": "Wi-Fi Clipboard Sync v1.0.1",
            "exeUrl": "https://github.com/hasan-circuito/WiFi-clipboard-sync-/releases/download/v1.0.1/WiFiClipboardSync.exe",
            "apkUrl": "https://github.com/hasan-circuito/WiFi-clipboard-sync-/releases/download/v1.0.1/app-debug.apk",
            "changelog": "Added auto-update system"
        }
        info = parse_release_info(sample_data)
        self.assertIsNotNone(info)
        self.assertEqual(info["version"], "1.0.1")
        self.assertEqual(info["tag_name"], "v1.0.1")
        self.assertEqual(info["exe_url"], sample_data["exeUrl"])
        self.assertEqual(info["changelog"], "Added auto-update system")

        # Verify repository root version.json parses correctly and meets release requirements
        root_vjson = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "version.json"))
        if os.path.exists(root_vjson):
            with open(root_vjson, "r", encoding="utf-8") as f:
                root_data = json.load(f)
            from updater import __version__
            root_info = parse_release_info(root_data)
            self.assertIsNotNone(root_info)
            self.assertEqual(root_info["version"], __version__)
            self.assertEqual(root_info["tag_name"], f"v{__version__}")
            self.assertTrue(root_info["exe_url"].endswith("WiFiClipboardSync.exe"))
            self.assertIn("Luminescent Connection Orb", root_info["changelog"])
            self.assertIn("uninstall v1.0.1 once", root_info["changelog"])

    def test_parse_release_info_github_api_schema(self):
        from updater import parse_release_info
        api_data = {
            "tag_name": "v1.0.2",
            "body": "Fixed Win32 clipboard event pumping",
            "published_at": "2026-10-10T12:00:00Z",
            "assets": [
                {
                    "name": "app-debug.apk",
                    "browser_download_url": "https://github.com/.../app-debug.apk"
                },
                {
                    "name": "WiFiClipboardSync.exe",
                    "browser_download_url": "https://github.com/hasan-circuito/WiFi-clipboard-sync-/releases/download/v1.0.2/WiFiClipboardSync.exe"
                }
            ]
        }
        info = parse_release_info(api_data)
        self.assertIsNotNone(info)
        self.assertEqual(info["version"], "1.0.2")
        self.assertEqual(info["tag_name"], "v1.0.2")
        self.assertEqual(info["exe_url"], "https://github.com/hasan-circuito/WiFi-clipboard-sync-/releases/download/v1.0.2/WiFiClipboardSync.exe")
        self.assertEqual(info["changelog"], "Fixed Win32 clipboard event pumping")

    def test_parse_release_info_invalid(self):
        from updater import parse_release_info
        self.assertIsNone(parse_release_info({}))
        self.assertIsNone(parse_release_info(None))
        self.assertIsNone(parse_release_info({"tag_name": "v1.0.0"})) # No exe asset

    def test_generate_updater_batch(self):
        from updater import generate_updater_batch
        new_exe = os.path.join(tempfile.gettempdir(), "test space dir", "test_new.exe")
        target_exe = os.path.join(tempfile.gettempdir(), "test space dir", "test_target.exe")
        pid = 99999

        bat_path = generate_updater_batch(new_exe, target_exe, pid)
        self.assertTrue(os.path.exists(bat_path))
        with open(bat_path, "r", encoding="utf-8") as f:
            content = f.read()

        self.assertIn(str(pid), content)
        # Ensure robust set "VAR=value" quoting syntax is used for paths with spaces
        self.assertIn('set "TARGET=', content)
        self.assertIn('set "NEW=', content)

        # Cleanup batch file
        try:
            os.remove(bat_path)
        except Exception:
            pass

        # Verify apply_update_and_restart detachment flags and process isolation
        from unittest.mock import patch
        from updater import apply_update_and_restart
        with patch("subprocess.Popen") as mock_popen, patch("sys.exit") as mock_exit:
            apply_update_and_restart(new_exe, target_exe)
            mock_popen.assert_called_once()
            args, kwargs = mock_popen.call_args
            flags = kwargs.get("creationflags", 0)
            self.assertEqual(flags, 0x08000000 | 0x00000008)
            import subprocess
            self.assertEqual(kwargs.get("stdin"), subprocess.DEVNULL)
            self.assertEqual(kwargs.get("stdout"), subprocess.DEVNULL)
            self.assertEqual(kwargs.get("stderr"), subprocess.DEVNULL)
            mock_exit.assert_called_once_with(0)

    def test_updater_batch_execution_with_spaces(self):
        """
        Verify that generated batch script correctly copies files when paths contain spaces.
        """
        import subprocess
        from updater import generate_updater_batch
        test_dir = tempfile.mkdtemp(prefix="wifi sync test space ")
        try:
            src_file = os.path.join(test_dir, "WiFiClipboardSync_new.exe")
            dst_file = os.path.join(test_dir, "WiFiClipboardSync.exe")
            with open(src_file, "wb") as f:
                f.write(b"NEW_VERSION_BINARY_DATA")

            # Use a dummy dead PID (e.g. 99999999) so wait loop completes immediately
            bat_path = generate_updater_batch(src_file, dst_file, current_pid=99999999)
            self.assertTrue(os.path.exists(bat_path))

            # Modify the script copy to avoid starting process and deleting self during test
            with open(bat_path, "r", encoding="utf-8") as f:
                bat_code = f.read()
            bat_code = bat_code.replace('start "" "%TARGET%"', 'echo Started %TARGET%')
            test_bat = os.path.join(test_dir, "run_test.bat")
            with open(test_bat, "w", encoding="utf-8") as f:
                f.write(bat_code)

            res = subprocess.run(["cmd.exe", "/c", test_bat], capture_output=True, text=True)
            self.assertEqual(res.returncode, 0)
            self.assertTrue(os.path.exists(dst_file))
            with open(dst_file, "rb") as f:
                self.assertEqual(f.read(), b"NEW_VERSION_BINARY_DATA")
        finally:
            import shutil
            shutil.rmtree(test_dir, ignore_errors=True)

    def test_check_for_updates_offline(self):
        from unittest.mock import patch
        from updater import check_for_updates
        # When all endpoints fail (offline), check_for_updates returns None
        with patch("updater.fetch_json", return_value=None):
            result = check_for_updates("1.0.0")
            self.assertIsNone(result)

    def test_check_for_updates_up_to_date(self):
        from unittest.mock import patch
        from updater import check_for_updates
        # When remote version is same or older, check_for_updates returns False
        sample = {
            "version": "1.0.0",
            "tag_name": "v1.0.0",
            "exeUrl": "https://example.com/WiFiClipboardSync.exe"
        }
        with patch("updater.fetch_json", return_value=sample):
            result = check_for_updates("1.0.0")
            self.assertFalse(result)

    def test_check_for_updates_available(self):
        from unittest.mock import patch
        from updater import check_for_updates
        sample = {
            "version": "1.0.1",
            "tag_name": "v1.0.1",
            "exeUrl": "https://example.com/WiFiClipboardSync.exe",
            "changelog": "New features"
        }
        with patch("updater.fetch_json", return_value=sample):
            result = check_for_updates("1.0.0")
            self.assertIsInstance(result, dict)
            self.assertEqual(result["version"], "1.0.1")


if __name__ == "__main__":
    unittest.main()
