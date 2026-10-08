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


if __name__ == "__main__":
    unittest.main()
