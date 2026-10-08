"""
Network server for WiFi Clipboard Sync.
Contains:
1. UDP Discovery Beacon for zero-config phone discovery.
2. Async WebSocket Server for bi-directional real-time clipboard sync.
"""

import asyncio
import json
import logging
import socket
import threading
import time
from typing import Callable, Optional, Set

import websockets

logger = logging.getLogger("SyncServer")

UDP_DISCOVERY_PORT = 52525
WS_SERVER_PORT = 52526


def get_local_ip(target_ip: Optional[str] = None) -> str:
    """Finds the best local IPv4 address, optionally targeting a specific client."""
    # Try connecting to the client IP or a public IP to find the routing interface
    probe_dest = target_ip if target_ip and not target_ip.startswith("127.") else "8.8.8.8"
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.settimeout(0.5)
        s.connect((probe_dest, 80))
        ip = s.getsockname()[0]
        s.close()
        if ip and not ip.startswith("127."):
            return ip
    except Exception:
        pass

    # Fallback to hostname lookup
    try:
        hostname = socket.gethostname()
        _, _, ip_list = socket.gethostbyname_ex(hostname)
        for ip in ip_list:
            if not ip.startswith("127."):
                return ip
    except Exception:
        pass

    return "127.0.0.1"


class UDPBeacon(threading.Thread):
    """
    Broadcasts UDP discovery beacons on port 52525 and responds
    immediately to direct discovery probes.
    """
    def __init__(self, ws_port: int = WS_SERVER_PORT, broadcast_interval: float = 2.0):
        super().__init__(daemon=True, name="UDPDiscoveryBeacon")
        self.ws_port = ws_port
        self.broadcast_interval = broadcast_interval
        self._running = False
        self.hostname = socket.gethostname()

    def run(self):
        self._running = True
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        sock.settimeout(0.5)

        try:
            sock.bind(("", UDP_DISCOVERY_PORT))
        except Exception as e:
            logger.warning(f"Could not bind UDP listening socket on {UDP_DISCOVERY_PORT}: {e}")

        last_broadcast = 0.0

        while self._running:
            now = time.time()
            local_ip = get_local_ip()

            payload = {
                "service": "clip-sync",
                "version": "1.0",
                "name": self.hostname,
                "ip": local_ip,
                "port": self.ws_port
            }
            data = json.dumps(payload).encode("utf-8")

            # Periodic broadcast
            if now - last_broadcast >= self.broadcast_interval:
                last_broadcast = now
                try:
                    sock.sendto(data, ("255.255.255.255", UDP_DISCOVERY_PORT))
                except Exception as e:
                    logger.debug(f"UDP broadcast send error: {e}")

            # Listen for inbound discover probes
            try:
                buf, addr = sock.recvfrom(2048)
                client_ip = addr[0]
                if client_ip != local_ip and client_ip != "127.0.0.1":
                    try:
                        req = json.loads(buf.decode("utf-8"))
                        # CRITICAL: Only respond to discovery queries, NEVER to general beacons
                        # This prevents packet storm loops between multiple instances/machines.
                        if (
                            req.get("service") == "clip-sync-discover"
                            and req.get("name") != self.hostname
                        ):
                            # Provide the interface IP facing this specific client
                            target_facing_ip = get_local_ip(client_ip)
                            resp_payload = {
                                "service": "clip-sync",
                                "version": "1.0",
                                "name": self.hostname,
                                "ip": target_facing_ip,
                                "port": self.ws_port
                            }
                            resp_data = json.dumps(resp_payload).encode("utf-8")
                            sock.sendto(resp_data, addr)
                    except Exception:
                        pass
            except socket.timeout:
                pass
            except Exception as e:
                time.sleep(0.1)

        sock.close()

    def stop(self):
        self._running = False


class SyncServer:
    def __init__(
        self,
        port: int = WS_SERVER_PORT,
        on_client_connected: Optional[Callable[[str], None]] = None,
        on_client_disconnected: Optional[Callable[[str], None]] = None,
        on_message_received: Optional[Callable[[str, str], None]] = None
    ):
        self.port = port
        self.on_client_connected = on_client_connected
        self.on_client_disconnected = on_client_disconnected
        self.on_message_received = on_message_received

        self._connected_clients: Set = set()
        self._lock = threading.Lock()
        self._loop: Optional[asyncio.AbstractEventLoop] = None
        self._server_thread: Optional[threading.Thread] = None
        self._server = None
        self._beacon = UDPBeacon(ws_port=self.port)
        self._running = False

    @property
    def client_count(self) -> int:
        with self._lock:
            return len(self._connected_clients)

    @property
    def is_connected(self) -> bool:
        return self.client_count > 0

    async def _handle_connection(self, websocket):
        client_ip = websocket.remote_address[0]
        logger.info(f"Client connected: {client_ip}")

        with self._lock:
            self._connected_clients.add(websocket)

        if self.on_client_connected:
            try:
                self.on_client_connected(client_ip)
            except Exception as e:
                logger.error(f"Error in on_client_connected: {e}")

        # Send welcome/handshake
        try:
            welcome_msg = json.dumps({
                "type": "handshake",
                "device": "pc",
                "name": socket.gethostname(),
                "timestamp": time.time()
            })
            await websocket.send(welcome_msg)
        except Exception:
            pass

        try:
            async for message in websocket:
                try:
                    payload = json.loads(message)
                    msg_type = payload.get("type")

                    if msg_type == "clipboard":
                        text = payload.get("text", "")
                        text_hash = payload.get("hash", "")
                        if text and self.on_message_received:
                            self.on_message_received(text, text_hash)

                    elif msg_type == "ping":
                        pong_msg = json.dumps({"type": "pong", "timestamp": time.time()})
                        await websocket.send(pong_msg)

                except json.JSONDecodeError:
                    logger.warning("Received invalid JSON message from client")
                except Exception as e:
                    logger.error(f"Error processing client message: {e}")

        except websockets.exceptions.ConnectionClosed:
            pass
        finally:
            with self._lock:
                self._connected_clients.discard(websocket)

            logger.info(f"Client disconnected: {client_ip}")
            if self.on_client_disconnected:
                try:
                    self.on_client_disconnected(client_ip)
                except Exception as e:
                    logger.error(f"Error in on_client_disconnected: {e}")

    def _run_server_loop(self):
        self._loop = asyncio.new_event_loop()
        asyncio.set_event_loop(self._loop)

        async def start_ws():
            self._server = await websockets.serve(
                self._handle_connection,
                "0.0.0.0",
                self.port,
                ping_interval=10,
                ping_timeout=5
            )
            logger.info(f"WebSocket server listening on 0.0.0.0:{self.port}")
            self._running = True

        self._loop.run_until_complete(start_ws())
        self._loop.run_forever()

    def start(self):
        """Starts the WebSocket server and the UDP beacon."""
        if self._running:
            return

        self._server_thread = threading.Thread(
            target=self._run_server_loop,
            daemon=True,
            name="WebSocketServerThread"
        )
        self._server_thread.start()

        self._beacon.start()

        # Wait briefly for server to be up
        start_t = time.time()
        while not self._running and time.time() - start_t < 2.0:
            time.sleep(0.05)

    def broadcast_clipboard(self, text: str, text_hash: str):
        """Broadcasts clipboard text to all connected clients."""
        if not self._running or not self._loop:
            return

        with self._lock:
            if not self._connected_clients:
                return
            clients = list(self._connected_clients)

        payload = json.dumps({
            "type": "clipboard",
            "text": text,
            "hash": text_hash,
            "sender": "pc",
            "timestamp": time.time()
        })

        async def _send_all():
            for ws in clients:
                try:
                    await ws.send(payload)
                except Exception as e:
                    logger.warning(f"Error broadcasting to client: {e}")

        asyncio.run_coroutine_threadsafe(_send_all(), self._loop)

    def stop(self):
        """Stops both the WebSocket server and UDP beacon."""
        self._running = False
        self._beacon.stop()

        if self._loop:
            async def _close_server():
                with self._lock:
                    clients = list(self._connected_clients)
                for ws in clients:
                    try:
                        await ws.close()
                    except Exception:
                        pass
                if self._server:
                    self._server.close()
                    await self._server.wait_closed()

            try:
                future = asyncio.run_coroutine_threadsafe(_close_server(), self._loop)
                future.result(timeout=2.0)
            except Exception:
                pass

            self._loop.call_soon_threadsafe(self._loop.stop)
