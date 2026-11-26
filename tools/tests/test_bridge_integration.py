"""Integration tests: a real HTTP/SSE server speaking the companion protocol.

The unit tests use fakes, which cannot catch a mistake in the HTTP or SSE layer itself. These
drive the real client against a real socket, so framing, chunking, auth headers and keep-alive
handling are exercised end to end.

Run with:  python3 -m unittest discover -s tools/tests -v
"""
import importlib.util
import json
import os
import sys
import threading
import time
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

_HERE = os.path.dirname(os.path.abspath(__file__))
_MODULE_PATH = os.path.join(os.path.dirname(_HERE), "discord_bridge.py")
_spec = importlib.util.spec_from_file_location("db", _MODULE_PATH)
db = importlib.util.module_from_spec(_spec)
sys.modules["db"] = db
_spec.loader.exec_module(db)

TOKEN = "secret-token"


class _State:
    """Shared server state: chat received from the bridge, and open SSE subscribers."""

    def __init__(self):
        self.received_chat = []
        self.subscribers = []
        self.lock = threading.Lock()


def make_handler(state):
    class Handler(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def log_message(self, *args):
            pass

        def _authorised(self):
            return self.headers.get("Authorization") == f"Bearer {TOKEN}"

        def _empty(self, status):
            self.send_response(status)
            self.send_header("Content-Length", "0")
            self.end_headers()

        def do_GET(self):
            if self.path == "/health":
                self.send_response(200)
                self.send_header("Content-Length", "2")
                self.end_headers()
                self.wfile.write(b"{}")
                return
            if self.path == "/api/v1/events":
                if not self._authorised():
                    self._empty(401)
                    return
                self.send_response(200)
                self.send_header("Content-Type", "text/event-stream")
                self.send_header("Transfer-Encoding", "chunked")
                self.end_headers()
                queue = []
                with state.lock:
                    state.subscribers.append(queue)
                try:
                    def chunk(text):
                        raw = text.encode()
                        self.wfile.write(f"{len(raw):X}\r\n".encode() + raw + b"\r\n")
                        self.wfile.flush()

                    chunk(": connected\n\n")
                    deadline = time.time() + 5
                    while time.time() < deadline:
                        if queue:
                            chunk(queue.pop(0))
                        else:
                            time.sleep(0.02)
                    self.wfile.write(b"0\r\n\r\n")
                except Exception:
                    pass
                finally:
                    with state.lock:
                        if queue in state.subscribers:
                            state.subscribers.remove(queue)
                return
            self._empty(404)

        def do_POST(self):
            length = int(self.headers.get("Content-Length") or 0)
            body = json.loads(self.rfile.read(length) or b"{}")
            if not self._authorised():
                self._empty(401)
                return
            if self.path == "/api/v1/chat":
                state.received_chat.append(body.get("message"))
                payload = json.dumps({"ok": True, "message": "Sent."}).encode()
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(payload)))
                self.end_headers()
                self.wfile.write(payload)
                return
            self._empty(404)

    return Handler


class IntegrationTest(unittest.TestCase):
    def setUp(self):
        self.state = _State()
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), make_handler(self.state))
        self.base = f"http://127.0.0.1:{self.server.server_address[1]}"
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        self.config = db.Config(bot_token="t", channel_id="chan", companion_token=TOKEN,
                                companion_url=self.base)

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()

    def _bridge(self, poster=None):
        return db.Bridge(self.config, db.CompanionClient(self.base, TOKEN), poster,
                         log=lambda _m: None)

    def _publish(self, text, sender="Steve"):
        frame = ("id: 1\nevent: chat\ndata: "
                 + json.dumps({"content": text, "sender": sender, "server": "srv"}) + "\n\n")
        with self.state.lock:
            for queue in self.state.subscribers:
                queue.append(frame)

    def test_wrong_token_is_rejected(self):
        client = db.CompanionClient(self.base, "wrong-token")
        with self.assertRaises(db.BridgeError):
            client.snapshot()

    def test_health_probe(self):
        self.assertTrue(db.CompanionClient(self.base, TOKEN).health())

    def test_discord_message_reaches_companion(self):
        self._bridge().handle_discord_message({
            "id": "1", "channel_id": "chan", "content": "hello from discord",
            "author": {"username": "Alice"},
        })
        self.assertEqual(["hello from discord"], self.state.received_chat)

    def test_slash_is_neutralised_over_the_wire(self):
        self._bridge().handle_discord_message({
            "id": "1", "channel_id": "chan", "content": "/op Alice",
            "author": {"username": "Alice"},
        })
        self.assertEqual(["op Alice"], self.state.received_chat)

    def test_sse_chat_reaches_discord(self):
        posted = []

        class Poster:
            def post(self, content="", username=None, embed=None):
                posted.append(embed if embed is not None else content)
                return True

        bridge = self._bridge(Poster())
        stream = db.CompanionClient(self.base, TOKEN).events()
        thread = threading.Thread(
            target=lambda: [bridge.handle_companion_event(event) for event in stream],
            daemon=True)
        thread.start()
        time.sleep(0.5)
        self._publish("hello from minecraft")
        time.sleep(0.6)

        cards = [item for item in posted if hasattr(item, "to_map")]
        self.assertTrue(cards, posted)
        self.assertTrue(any("hello from minecraft" in card.description for card in cards), posted)
        self.assertTrue(any(card.author == "Steve · srv" for card in cards), posted)
        self.assertFalse(any("keep-alive" in card.description for card in cards), posted)


if __name__ == "__main__":
    unittest.main(verbosity=2)
