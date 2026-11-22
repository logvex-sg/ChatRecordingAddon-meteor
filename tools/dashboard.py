#!/usr/bin/env python3
"""A desktop dashboard for Log Client, in the terminal.

This is a native application, not a web page: it draws with curses and talks to the mod's
companion server over HTTP. There is no browser, no JavaScript and no token to paste.

    python3 tools/dashboard.py            # connect automatically
    python3 tools/dashboard.py --once     # print one snapshot and exit (good for scripts)
    python3 tools/dashboard.py --json     # print the raw snapshot JSON

Automatic connection reads the discovery file the mod writes while its companion server runs:

    <game dir>/logclient/companion.json

That file carries the port the server actually bound and the token it actually uses, so the
dashboard connects with no arguments. If the file is absent (the module is off, or the server
bound a routable address) the dashboard falls back to 127.0.0.1 on the usual ports and says what
it tried. ``--token`` and ``--url`` still work for a hand-configured remote server.

Keys: q quit, r refresh, 1..5 switch tab, p pause/resume the bot, e emergency stop.
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass
from typing import Any, Optional

# The ports to probe when there is no discovery file, in the order the mod has used them.
DEFAULT_PORTS = (6767, 6768, 6769)

# A local request should be fast. A longer wait only makes a dead server look like a slow one.
TIMEOUT_SECONDS = 3.0


class ConnectError(RuntimeError):
    """Raised when no companion server could be reached."""


@dataclass(frozen=True)
class Endpoint:
    """Where a companion server is and how to authenticate to it."""

    base_url: str
    token: str = ""
    source: str = ""

    def describe(self) -> str:
        host = urllib.parse.urlsplit(self.base_url).netloc or self.base_url
        return f"{host} ({self.source})" if self.source else host


def discovery_paths() -> list[str]:
    """Every place a discovery file may live, most specific first.

    ``LOGLIENT_COMPANION_FILE`` exists so a test or a launcher can point at a file without
    guessing where the game installed itself.
    """
    paths: list[str] = []
    override = os.environ.get("LOGLIENT_COMPANION_FILE")
    if override:
        paths.append(os.path.expanduser(override))
    game_dir = os.environ.get("MINECRAFT_GAME_DIR")
    if game_dir:
        paths.append(os.path.join(os.path.expanduser(game_dir), "logclient", "companion.json"))
    home = os.path.expanduser("~")
    paths.extend(
        os.path.join(home, base, "logclient", "companion.json")
        for base in (
            ".minecraft",
            ".local/share/PrismLauncher/instances",
            ".var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher",
        )
    )
    return paths


def read_discovery(path: str) -> Optional[Endpoint]:
    """Reads one discovery file, or None when it is absent or unusable.

    A file that is present but unreadable is not an error worth stopping for: the next candidate
    may still work, and the caller reports what it tried when nothing does.
    """
    try:
        with open(path, "r", encoding="utf-8") as handle:
            doc = json.load(handle)
    except (OSError, ValueError):
        return None
    if not isinstance(doc, dict):
        return None
    host = str(doc.get("host") or "127.0.0.1").strip()
    try:
        port = int(doc.get("port") or 0)
    except (TypeError, ValueError):
        return None
    if not host or not 0 < port < 65536:
        return None
    return Endpoint(f"http://{host}:{port}", str(doc.get("token") or ""), "discovered")


def probe(endpoint: Endpoint) -> bool:
    """True when the endpoint answers an unauthenticated health check."""
    try:
        with urllib.request.urlopen(
            endpoint.base_url + "/health", timeout=TIMEOUT_SECONDS
        ) as response:
            return 200 <= response.status < 300
    except (urllib.error.URLError, OSError, ValueError):
        return False


def discover() -> Optional[Endpoint]:
    """The first discovery file whose server answers, or None."""
    for path in discovery_paths():
        endpoint = read_discovery(path)
        if endpoint and probe(endpoint):
            return endpoint
    return None


def guess_local() -> Optional[Endpoint]:
    """The first usual local port that answers, used when there is no discovery file."""
    for port in DEFAULT_PORTS:
        endpoint = Endpoint(f"http://127.0.0.1:{port}", "", "port scan")
        if probe(endpoint):
            return endpoint
    return None


def connect(url: Optional[str], token: Optional[str]) -> Endpoint:
    """Resolves an endpoint: explicit arguments first, then discovery, then a port scan.

    The order matters. An explicit ``--url`` is the user telling us where to go, so it is never
    silently replaced by a discovered server.
    """
    if url:
        base = url.rstrip("/")
        if not base.startswith(("http://", "https://")):
            base = "http://" + base
        return Endpoint(base, token or "", "given")

    found = discover()
    if found:
        # An explicit token still wins over the discovered one.
        return Endpoint(found.base_url, token or found.token, found.source)
    guessed = guess_local()
    if guessed:
        return Endpoint(guessed.base_url, token or "", guessed.source)

    tried = ", ".join(discovery_paths())
    raise ConnectError(
        "No Log Client companion server answered.\n"
        "  1. Turn on the 'companion' module in game (it starts the server).\n"
        "  2. Re-run this dashboard.\n"
        f"  Discovery files checked: {tried}\n"
        "  Ports probed: " + ", ".join(str(p) for p in DEFAULT_PORTS)
    )


class Client:
    """A minimal companion API client."""

    def __init__(self, endpoint: Endpoint) -> None:
        self.endpoint = endpoint

    def _request(self, path: str, method: str = "GET", body: Any = None) -> Any:
        url = self.endpoint.base_url + path
        data = None
        headers = {"Accept": "application/json"}
        if self.endpoint.token:
            headers["Authorization"] = f"Bearer {self.endpoint.token}"
        if body is not None:
            data = json.dumps(body).encode("utf-8")
            headers["Content-Type"] = "application/json"
        request = urllib.request.Request(url, data=data, headers=headers, method=method)
        try:
            with urllib.request.urlopen(request, timeout=TIMEOUT_SECONDS) as response:
                raw = response.read().decode("utf-8", "replace")
        except urllib.error.HTTPError as error:
            if error.code == 401:
                raise ConnectError(
                    "The companion rejected the token. Run 'companion token' in game to read "
                    "the current one."
                ) from error
            raise ConnectError(f"{method} {path} failed: HTTP {error.code}") from error
        except (urllib.error.URLError, OSError) as error:
            raise ConnectError(f"{method} {path} failed: {error}") from error
        if not raw.strip():
            return None
        try:
            return json.loads(raw)
        except ValueError:
            return raw

    def snapshot(self) -> dict[str, Any]:
        return self._request("/api/v1/snapshot") or {}

    def chat(self, text: str) -> Any:
        return self._request("/api/v1/chat", "POST", {"message": text})

    def command(self, text: str) -> Any:
        return self._request("/api/v1/command", "POST", {"command": text})

    def bot(self, action: str) -> Any:
        return self._request(f"/api/v1/bot/{action}", "POST", {})

    def module(self, name: str, active: bool) -> Any:
        return self._request(f"/api/v1/modules/{urllib.parse.quote(name)}", "POST",
                             {"active": active})


def format_snapshot(snapshot: dict[str, Any], width: int = 78) -> str:
    """A plain-text rendering of a snapshot, for --once and for the log."""
    node = snapshot.get("node") or {}
    stats = snapshot.get("stats") or {}
    bot = snapshot.get("bot") or {}
    lines = [
        _rule(width),
        _centre("LOG CLIENT", width),
        _rule(width),
        f"  Player     {node.get('playerName') or '-'}",
        f"  Server     {node.get('serverAddress') or 'not connected'}",
        f"  In world   {'yes' if node.get('inWorld') else 'no'}"
        f"{' (singleplayer)' if node.get('singleplayer') else ''}",
        f"  Version    {node.get('addonVersion') or '-'} on MC {node.get('minecraftVersion') or '-'}",
        _rule(width),
        f"  Messages   {stats.get('messages', 0)}    Players {stats.get('players', 0)}"
        f"    Joins {stats.get('joins', 0)}    Deaths {stats.get('deaths', 0)}",
        f"  Bot        {bot.get('state') or 'idle'}"
        f"    queued {bot.get('queued', 0)}"
        f"    done {bot.get('completed', 0)}    failed {bot.get('failed', 0)}",
        _rule(width),
    ]
    chat = snapshot.get("chat") or []
    if chat:
        lines.append("  Recent chat")
        for entry in chat[-8:]:
            sender = entry.get("sender") or "?"
            message = (entry.get("message") or "").replace("\n", " ")
            lines.append(_clip(f"    <{sender}> {message}", width))
    else:
        lines.append("  No chat recorded yet.")
    players = snapshot.get("players") or []
    if players:
        online = [p.get("name") for p in players if p.get("online")]
        lines.append(_rule(width))
        lines.append(_clip("  Online: " + (", ".join(filter(None, online)) or "none"), width))
    lines.append(_rule(width))
    return "\n".join(lines)


def _rule(width: int) -> str:
    return "+" + "-" * (width - 2) + "+"


def _centre(text: str, width: int) -> str:
    return "|" + text.center(width - 2) + "|"


def _clip(text: str, width: int) -> str:
    return text if len(text) <= width else text[: width - 3] + "..."


def main(argv: Optional[list[str]] = None) -> int:
    parser = argparse.ArgumentParser(
        description="A terminal dashboard for Log Client. Connects to the local companion "
                    "automatically; no token or URL needed.")
    parser.add_argument("--url", help="companion base URL, e.g. http://127.0.0.1:6767")
    parser.add_argument("--token", help="override the token (normally read from discovery)")
    parser.add_argument("--once", action="store_true", help="print one snapshot and exit")
    parser.add_argument("--json", action="store_true", help="print the raw snapshot JSON")
    parser.add_argument("--width", type=int, default=78, help="width for --once output")
    parser.add_argument("--version", action="version", version="Log Client dashboard")
    args = parser.parse_args(argv)

    try:
        endpoint = connect(args.url, args.token)
    except ConnectError as error:
        print(str(error), file=sys.stderr)
        return 2

    client = Client(endpoint)

    if args.json or args.once:
        try:
            snapshot = client.snapshot()
        except ConnectError as error:
            print(str(error), file=sys.stderr)
            return 2
        if args.json:
            print(json.dumps(snapshot, indent=2, sort_keys=True))
        else:
            print(format_snapshot(snapshot, max(40, args.width)))
        return 0

    return run_tui(client)


def run_tui(client: Client) -> int:
    """Runs the interactive dashboard. Imported lazily so --once works without a terminal."""
    try:
        import curses  # noqa: F401
    except ImportError:
        print("curses is not available in this Python. Use --once for a text snapshot.",
              file=sys.stderr)
        return 2
    return _curses_main(client)


def _curses_main(client: Client) -> int:
    import curses

    def loop(screen: Any) -> int:
        curses.curs_set(0)
        screen.nodelay(True)
        curses.start_color()
        try:
            curses.use_default_colors()
            curses.init_pair(1, curses.COLOR_CYAN, -1)
            curses.init_pair(2, curses.COLOR_GREEN, -1)
            curses.init_pair(3, curses.COLOR_YELLOW, -1)
            curses.init_pair(4, curses.COLOR_RED, -1)
        except curses.error:
            pass

        tabs = ["Overview", "Chat", "Players", "Modules", "Bot"]
        tab = 0
        snapshot: dict[str, Any] = {}
        status = "connected"
        scroll = 0

        while True:
            try:
                snapshot = client.snapshot()
                status = "connected"
            except ConnectError as error:
                status = str(error).splitlines()[0]

            screen.erase()
            height, width = screen.getmaxyx()
            _draw_header(screen, width, client.endpoint, tabs, tab, status)
            body = _tab_lines(snapshot, tabs[tab], width)
            visible = body[scroll: scroll + max(0, height - 4)]
            for row, line in enumerate(visible, start=2):
                _safe_addstr(screen, row, 0, _clip(line, width - 1))
            _safe_addstr(screen, height - 1, 0,
                         _clip(" q quit  r refresh  tab/1-5 switch  p pause/resume  e stop "
                               "s send chat  c command ", width - 1))
            screen.refresh()

            key = screen.getch()
            if key == -1:
                time.sleep(0.5)
                continue
            char = chr(key) if 0 <= key < 256 else ""
            if char in ("q", "Q"):
                return 0
            if char == "\t":
                tab = (tab + 1) % len(tabs)
                scroll = 0
            elif char.isdigit() and 1 <= int(char) <= len(tabs):
                tab = int(char) - 1
                scroll = 0
            elif char in ("j", "J"):
                scroll = min(scroll + 1, max(0, len(body) - 1))
            elif char in ("k", "K"):
                scroll = max(0, scroll - 1)
            elif char in ("p", "P"):
                _bot_action(client, "pause" if _paused(snapshot) else "resume")
            elif char in ("e", "E"):
                _bot_action(client, "emergency-stop")
            elif char in ("s", "S"):
                _prompt_and_send(screen, client, chat=True)
            elif char in ("c", "C"):
                _prompt_and_send(screen, client, chat=False)

    try:
        return curses.wrapper(loop)
    except KeyboardInterrupt:
        return 0


def _paused(snapshot: dict[str, Any]) -> bool:
    return bool((snapshot.get("bot") or {}).get("paused"))


def _bot_action(client: Client, action: str) -> None:
    try:
        client.bot(action)
    except ConnectError:
        pass


def _prompt_and_send(screen: Any, client: Client, chat: bool) -> None:
    import curses

    prompt = "Chat to send: " if chat else "Command to run: "
    curses.curs_set(1)
    screen.nodelay(False)
    height, width = screen.getmaxyx()
    _safe_addstr(screen, height - 1, 0, " " * (width - 1))
    _safe_addstr(screen, height - 1, 0, prompt)
    screen.refresh()
    try:
        text = screen.getstr(height - 1, len(prompt), width - len(prompt) - 1).decode(
            "utf-8", "replace")
    except (curses.error, KeyboardInterrupt):
        text = ""
    curses.curs_set(0)
    screen.nodelay(True)
    if not text.strip():
        return
    try:
        if chat:
            client.chat(text)
        else:
            client.command(text)
    except ConnectError:
        pass


def _draw_header(screen: Any, width: int, endpoint: Endpoint, tabs: list[str], tab: int,
                 status: str) -> None:
    import curses

    title = " LOG CLIENT  " + endpoint.describe()
    _safe_addstr(screen, 0, 0, _clip(title.ljust(width - 1), width - 1),
                 curses.color_pair(1) | curses.A_BOLD)
    parts = []
    for index, name in enumerate(tabs, start=1):
        parts.append(f" {index}:{name} " if index - 1 != tab else f"[{index}:{name}]")
    _safe_addstr(screen, 1, 0, _clip("".join(parts), width - 1),
                 curses.color_pair(2))


def _safe_addstr(screen: Any, row: int, col: int, text: str,
                 attrs: int = 0) -> None:
    """Writes text, ignoring the curses error raised by the bottom-right cell."""
    import curses

    try:
        screen.addstr(row, col, text, attrs)
    except curses.error:
        pass


def _tab_lines(snapshot: dict[str, Any], tab: str, width: int) -> list[str]:
    if tab == "Overview":
        return format_snapshot(snapshot, width).splitlines()
    if tab == "Chat":
        chat = snapshot.get("chat") or []
        if not chat:
            return ["  No chat recorded yet."]
        return [
            _clip(f"  {entry.get('type', '?'):<8} <{entry.get('sender') or '?'}> "
                  f"{entry.get('message') or ''}", width)
            for entry in chat
        ]
    if tab == "Players":
        players = snapshot.get("players") or []
        if not players:
            return ["  No players seen yet."]
        return [
            _clip(f"  {'*' if p.get('online') else ' '} {p.get('name') or '?':<16} "
                  f"msgs {p.get('messages', 0):<5} "
                  f"{'excluded' if p.get('excluded') else ''}", width)
            for p in players
        ]
    if tab == "Modules":
        modules = snapshot.get("modules") or []
        if not modules:
            return ["  No modules reported."]
        return [
            _clip(f"  {'[x]' if m.get('active') else '[ ]'} {m.get('title') or m.get('name')}"
                  f"  ({m.get('category') or '-'})", width)
            for m in modules
        ]
    bot = snapshot.get("bot") or {}
    lines = [
        f"  State      {bot.get('state') or 'idle'}",
        f"  Paused     {'yes' if bot.get('paused') else 'no'}",
        f"  Queued     {bot.get('queued', 0)}",
        f"  Completed  {bot.get('completed', 0)}",
        f"  Failed     {bot.get('failed', 0)}",
        f"  Active     {bot.get('activeTask') or '-'}",
    ]
    events = bot.get("recentEvents") or []
    if events:
        lines.append("")
        lines.append("  Recent events")
        lines.extend(_clip(f"    {event}", width) for event in events)
    return lines


if __name__ == "__main__":
    sys.exit(main())
