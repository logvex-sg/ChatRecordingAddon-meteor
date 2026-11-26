#!/usr/bin/env python3
"""Tests for the terminal dashboard.

These run a real HTTP server in a thread and a real discovery file on disk, so the code under
test uses the same urllib and json paths it uses against the mod. Nothing here is mocked: the
only stand-in is the mod itself, which cannot run outside Minecraft.
"""

from __future__ import annotations

import contextlib
import json
import os
import sys
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import dashboard  # noqa: E402


SNAPSHOT = {
    "node": {
        "id": "abc123",
        "name": "Log Client",
        "addonVersion": "18.21.0",
        "minecraftVersion": "1.21.1",
        "playerName": "Tester",
        "serverAddress": "mc.example.net",
        "inWorld": True,
        "singleplayer": False,
    },
    "stats": {"messages": 42, "players": 3, "joins": 1, "leaves": 0, "deaths": 0},
    "bot": {"state": "running", "queued": 2, "completed": 5, "failed": 1, "paused": False,
            "activeTask": "mine", "recentEvents": ["started"]},
    "chat": [
        {"timestamp": 1, "type": "chat", "sender": "Alice", "message": "hello"},
        {"timestamp": 2, "type": "chat", "sender": "Bob", "message": "gg"},
    ],
    "players": [
        {"name": "Alice", "online": True, "messages": 2, "excluded": False},
        {"name": "Bob", "online": False, "messages": 1, "excluded": True},
    ],
    "modules": [
        {"name": "chat-logger", "title": "Chat Logger", "category": "Logging", "active": True},
    ],
}


class _Handler(BaseHTTPRequestHandler):
    """A stand-in for the companion: health, snapshot and a couple of actions."""

    token = "secret-token"
    calls: list[tuple[str, str, dict]] = []

    def log_message(self, *args):  # keep the test output clean
        pass

    def _authorised(self) -> bool:
        return self.headers.get("Authorization") == f"Bearer {self.token}"

    def _send(self, status: int, payload) -> None:
        body = json.dumps(payload).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):  # noqa: N802
        if self.path == "/health":
            self._send(200, {"status": "ok"})
            return
        if not self._authorised():
            self._send(401, {"error": "unauthorised"})
            return
        if self.path == "/api/v1/snapshot":
            self._send(200, SNAPSHOT)
            return
        self._send(404, {"error": "not found"})

    def do_POST(self):  # noqa: N802
        length = int(self.headers.get("Content-Length") or 0)
        body = json.loads(self.rfile.read(length) or b"{}")
        type(self).calls.append((self.path, self.headers.get("Authorization", ""), body))
        if not self._authorised():
            self._send(401, {"error": "unauthorised"})
            return
        self._send(200, {"ok": True})


class DashboardTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.server = ThreadingHTTPServer(("127.0.0.1", 0), _Handler)
        cls.port = cls.server.server_address[1]
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()

    def setUp(self):
        _Handler.calls.clear()
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)

    def _write_discovery(self, **overrides) -> str:
        doc = {"version": 1, "host": "127.0.0.1", "port": self.port,
               "token": "secret-token", "pid": os.getpid()}
        doc.update(overrides)
        path = Path(self.tmp.name) / "companion.json"
        path.write_text(json.dumps(doc), encoding="utf-8")
        return str(path)

    # ---------------------------------------------------------------- discovery

    def test_reads_a_discovery_file(self):
        endpoint = dashboard.read_discovery(self._write_discovery())
        self.assertIsNotNone(endpoint)
        self.assertEqual(f"http://127.0.0.1:{self.port}", endpoint.base_url)
        self.assertEqual("secret-token", endpoint.token)

    def test_missing_file_is_not_an_endpoint(self):
        self.assertIsNone(dashboard.read_discovery(str(Path(self.tmp.name) / "absent.json")))

    def test_malformed_file_is_ignored(self):
        path = Path(self.tmp.name) / "companion.json"
        path.write_text("{not json", encoding="utf-8")
        self.assertIsNone(dashboard.read_discovery(str(path)))

    def test_a_file_without_a_usable_port_is_ignored(self):
        for bad in ({"port": 0}, {"port": "x"}, {"port": 70000}):
            path = self._write_discovery(**bad)
            self.assertIsNone(dashboard.read_discovery(path), bad)

    def test_discovery_prefers_the_override_variable(self):
        path = self._write_discovery()
        old = os.environ.get("LOGLIENT_COMPANION_FILE")
        os.environ["LOGLIENT_COMPANION_FILE"] = path
        try:
            self.assertEqual(path, dashboard.discovery_paths()[0])
            endpoint = dashboard.discover()
        finally:
            if old is None:
                os.environ.pop("LOGLIENT_COMPANION_FILE", None)
            else:
                os.environ["LOGLIENT_COMPANION_FILE"] = old
        self.assertIsNotNone(endpoint, "the running test server should have been discovered")
        self.assertEqual(self.port, int(endpoint.base_url.rsplit(":", 1)[1]))

    def test_connect_uses_the_discovered_token(self):
        path = self._write_discovery()
        old = os.environ.get("LOGLIENT_COMPANION_FILE")
        os.environ["LOGLIENT_COMPANION_FILE"] = path
        try:
            endpoint = dashboard.connect(None, None)
        finally:
            if old is None:
                os.environ.pop("LOGLIENT_COMPANION_FILE", None)
            else:
                os.environ["LOGLIENT_COMPANION_FILE"] = old
        self.assertEqual("secret-token", endpoint.token)
        self.assertEqual("discovered", endpoint.source)

    def test_an_explicit_url_is_never_replaced_by_discovery(self):
        endpoint = dashboard.connect(f"127.0.0.1:{self.port}", "typed")
        self.assertEqual(f"http://127.0.0.1:{self.port}", endpoint.base_url)
        self.assertEqual("typed", endpoint.token)
        self.assertEqual("given", endpoint.source)

    @contextlib.contextmanager
    def _without_a_local_server(self):
        """Temporarily declares that there are no usual ports to probe.

        ``connect`` falls back to scanning ``DEFAULT_PORTS``, so a developer with a headless
        instance up would otherwise see these 'nothing is running' tests pass and fail at random.
        Emptying the list states the test's premise directly: nothing is listening on this machine.
        """
        original = dashboard.DEFAULT_PORTS
        dashboard.DEFAULT_PORTS = ()
        try:
            yield
        finally:
            dashboard.DEFAULT_PORTS = original

    def test_connect_fails_with_a_useful_message(self):
        old = os.environ.get("LOGLIENT_COMPANION_FILE")
        os.environ["LOGLIENT_COMPANION_FILE"] = str(Path(self.tmp.name) / "absent.json")
        try:
            with self._without_a_local_server():
                with self.assertRaises(dashboard.ConnectError) as caught:
                    dashboard.connect(None, None)
        finally:
            if old is None:
                os.environ.pop("LOGLIENT_COMPANION_FILE", None)
            else:
                os.environ["LOGLIENT_COMPANION_FILE"] = old
        message = str(caught.exception)
        self.assertIn("companion", message.lower())
        self.assertIn("absent.json", message)

    # ---------------------------------------------------------------- client

    def test_snapshot_reads_through_the_api(self):
        client = dashboard.Client(dashboard.Endpoint(
            f"http://127.0.0.1:{self.port}", "secret-token", "test"))
        snapshot = client.snapshot()
        self.assertEqual("Tester", snapshot["node"]["playerName"])
        self.assertEqual(42, snapshot["stats"]["messages"])

    def test_a_wrong_token_says_so(self):
        client = dashboard.Client(dashboard.Endpoint(
            f"http://127.0.0.1:{self.port}", "wrong", "test"))
        with self.assertRaises(dashboard.ConnectError) as caught:
            client.snapshot()
        self.assertIn("token", str(caught.exception).lower())

    def test_chat_and_command_post_the_right_keys(self):
        client = dashboard.Client(dashboard.Endpoint(
            f"http://127.0.0.1:{self.port}", "secret-token", "test"))
        client.chat("hello there")
        client.command("say hi")
        paths = [call[0] for call in _Handler.calls]
        self.assertIn("/api/v1/chat", paths)
        self.assertIn("/api/v1/command", paths)
        bodies = {call[0]: call[2] for call in _Handler.calls}
        self.assertEqual("hello there", bodies["/api/v1/chat"]["message"])
        self.assertEqual("say hi", bodies["/api/v1/command"]["command"])

    def test_bot_actions_hit_their_routes(self):
        client = dashboard.Client(dashboard.Endpoint(
            f"http://127.0.0.1:{self.port}", "secret-token", "test"))
        for action in ("start", "pause", "resume", "stop", "emergency-stop"):
            client.bot(action)
        paths = [call[0] for call in _Handler.calls]
        self.assertEqual(5, len(paths))
        self.assertIn("/api/v1/bot/emergency-stop", paths)

    def test_module_names_are_url_quoted(self):
        client = dashboard.Client(dashboard.Endpoint(
            f"http://127.0.0.1:{self.port}", "secret-token", "test"))
        client.module("chat logger", True)
        self.assertIn("/api/v1/modules/chat%20logger", [call[0] for call in _Handler.calls])

    # ---------------------------------------------------------------- rendering

    def test_plain_snapshot_names_the_player_and_server(self):
        text = dashboard.format_snapshot(SNAPSHOT)
        self.assertIn("Tester", text)
        self.assertIn("mc.example.net", text)
        self.assertIn("<Alice> hello", text)
        self.assertIn("running", text)

    def test_plain_snapshot_survives_an_empty_snapshot(self):
        # A server that has just started reports very little; the renderer must not raise.
        text = dashboard.format_snapshot({})
        self.assertIn("LOG CLIENT", text)
        self.assertIn("not connected", text)

    def test_long_lines_are_clipped(self):
        snapshot = dict(SNAPSHOT)
        snapshot["chat"] = [{"type": "chat", "sender": "A" * 200, "message": "x" * 500}]
        text = dashboard.format_snapshot(snapshot, width=60)
        for line in text.splitlines():
            self.assertLessEqual(len(line), 60)

    def test_every_tab_renders(self):
        for tab in ("Overview", "Chat", "Players", "Modules", "Bot"):
            lines = dashboard._tab_lines(SNAPSHOT, tab, 78)
            self.assertTrue(lines, tab)

    def test_tabs_render_on_an_empty_snapshot(self):
        for tab in ("Overview", "Chat", "Players", "Modules", "Bot"):
            lines = dashboard._tab_lines({}, tab, 78)
            self.assertTrue(lines, tab)

    # ---------------------------------------------------------------- entry point

    def test_once_mode_prints_and_exits_zero(self):
        path = self._write_discovery()
        old = os.environ.get("LOGLIENT_COMPANION_FILE")
        os.environ["LOGLIENT_COMPANION_FILE"] = path
        try:
            import contextlib
            import io
            out = io.StringIO()
            with contextlib.redirect_stdout(out):
                code = dashboard.main(["--once"])
        finally:
            if old is None:
                os.environ.pop("LOGLIENT_COMPANION_FILE", None)
            else:
                os.environ["LOGLIENT_COMPANION_FILE"] = old
        self.assertEqual(0, code)
        self.assertIn("Tester", out.getvalue())

    def test_json_mode_emits_parsable_json(self):
        path = self._write_discovery()
        old = os.environ.get("LOGLIENT_COMPANION_FILE")
        os.environ["LOGLIENT_COMPANION_FILE"] = path
        try:
            import contextlib
            import io
            out = io.StringIO()
            with contextlib.redirect_stdout(out):
                code = dashboard.main(["--json"])
        finally:
            if old is None:
                os.environ.pop("LOGLIENT_COMPANION_FILE", None)
            else:
                os.environ["LOGLIENT_COMPANION_FILE"] = old
        self.assertEqual(0, code)
        self.assertEqual("Tester", json.loads(out.getvalue())["node"]["playerName"])

    def test_no_server_exits_two_with_a_message(self):
        old = os.environ.get("LOGLIENT_COMPANION_FILE")
        os.environ["LOGLIENT_COMPANION_FILE"] = str(Path(self.tmp.name) / "absent.json")
        try:
            import contextlib
            import io
            err = io.StringIO()
            with self._without_a_local_server():
                with contextlib.redirect_stderr(err):
                    code = dashboard.main(["--once"])
        finally:
            if old is None:
                os.environ.pop("LOGLIENT_COMPANION_FILE", None)
            else:
                os.environ["LOGLIENT_COMPANION_FILE"] = old
        self.assertEqual(2, code)
        self.assertIn("No Log Client companion server", err.getvalue())


if __name__ == "__main__":
    unittest.main()
