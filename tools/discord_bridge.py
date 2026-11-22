#!/usr/bin/env python3
"""Two-way Discord <-> Minecraft chat bridge for Log Client.

Log Client already has a loopback HTTP API (the "companion server"). This script is the other
half: it reads that API and posts to Discord, and it takes messages from Discord and injects
them into Minecraft. It is a separate process on purpose, for two reasons.

  * A bot token cannot live in a client mod. Meteor settings are exported and copy-pasted, and
    every user would ship the same token. Here it comes from an environment variable and never
    reaches the mod, the logs, or a config file.
  * The bot must stay online when the game is closed. A Gateway connection inside Minecraft
    would drop the moment the player quits.

Standard library only, so there is nothing to install. Run it with Python 3.10 or newer.

    export DISCORD_BOT_TOKEN=...
    export BRIDGE_CHANNEL_ID=...
    python3 tools/discord_bridge.py

Two transports are supported for talking to Discord:

  gateway (default)  a real bot: sees messages as they happen, shows as online. Needs the
                     Message Content intent enabled in the developer portal.
  webhook            no bot account at all. Reads via a bot token but posts through a channel
                     webhook. Kept because some users already have a webhook URL and no bot.

Security notes, because this crosses a trust boundary:

  * Anything typed in Discord arrives here and is sent to the game. Log Client's own
    ``sendChat`` treats a leading ``/`` as a command, so text from Discord is stripped of a
    leading slash. Without that, a Discord user could run arbitrary Minecraft commands.
  * Chat is escaped before it is posted to Discord, so a player cannot ping @everyone or a
    role by typing it in Minecraft.
  * The companion token is never logged, and never placed in a URL that could end up in a log.
"""

from __future__ import annotations

import argparse
import json
import os
import signal
import socket
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass
from typing import Any, Callable, Iterable, Iterator, Optional

VERSION = "1.0.0"

# Discord limits. A Minecraft chat line is 256 characters, but a Discord message is 2000, so the
# limits differ per direction and are applied separately.
DISCORD_MESSAGE_LIMIT = 2000
MINECRAFT_LINE_LIMIT = 256

# Discord API version. v10 is current.
DISCORD_API = "https://discord.com/api/v10"

# Reconnect backoff for both the Discord gateway and the companion event stream.
BACKOFF_START = 1.0
BACKOFF_MAX = 60.0

# How many recent chat timestamps to remember when suppressing echoes of our own messages.
ECHO_MEMORY = 200


class BridgeError(RuntimeError):
    """A configuration or transport problem the operator needs to see."""


# --------------------------------------------------------------------------- text handling


def sanitize_for_minecraft(text: str) -> str:
    """Make a Discord message safe to send into Minecraft chat.

    The leading-slash rule is the important one. Log Client's ``sendChat`` forwards anything
    starting with ``/`` to ``sendCommand``, so a Discord user could otherwise run Minecraft
    commands. A slash anywhere else is harmless and is left alone, because players legitimately
    type things like "gg / good fight".

    Newlines are collapsed because Minecraft chat is one line; a multi-line Discord message
    would otherwise arrive as one run-on line with odd spacing.
    """
    if text is None:
        return ""
    collapsed = " ".join(text.split())
    # Strip any number of leading slashes, including "/ /op" style attempts and unicode spaces.
    collapsed = collapsed.lstrip("/").lstrip()
    return collapsed.strip()


def escape_for_discord(text: str) -> str:
    """Neutralise mentions and formatting so a player cannot ping or spoof in Discord.

    ``@everyone`` and ``@here`` are the reason this exists: without it, any player who types
    that string in Minecraft would ping an entire Discord server. Role and user mentions are
    broken the same way, and backticks are replaced so a player cannot break out of the code
    formatting the bridge uses for names.
    """
    if text is None:
        return ""
    # A zero-width space after @ keeps the text readable but stops Discord matching a mention.
    escaped = text.replace("@", "@\u200b")
    escaped = escaped.replace("`", "\u2018")
    return escaped


def clamp(text: str, limit: int) -> str:
    """Trim to ``limit`` characters, marking that it was cut."""
    if len(text) <= limit:
        return text
    if limit <= 1:
        return text[:limit]
    return text[: limit - 1] + "\u2026"


def format_minecraft_line(sender: str, message: str, server: str = "") -> str:
    """Render one Minecraft chat line for Discord, as a single line of markdown."""
    who = escape_for_discord(sender).strip() or "server"
    body = escape_for_discord(message).strip()
    line = f"**{who}** {body}"
    if server:
        line = f"{line}  `{escape_for_discord(server)}`"
    return clamp(line, DISCORD_MESSAGE_LIMIT)


def format_discord_for_minecraft(author: str, message: str) -> str:
    """Render a Discord message as the text to inject into Minecraft.

    Minecraft has no markdown, so the angle-bracket form is used, matching what the client
    itself prints. The whole line is clamped to the chat limit.
    """
    who = " ".join((author or "discord").split()) or "discord"
    body = sanitize_for_minecraft(message)
    return clamp(f"<{who}> {body}", MINECRAFT_LINE_LIMIT)


# --------------------------------------------------------------------------- SSE parsing


@dataclass
class SseEvent:
    """One server-sent event."""

    event: str = "message"
    data: str = ""
    id: str = ""


def parse_sse_lines(lines: Iterable[str]) -> Iterator[SseEvent]:
    """Parse an SSE byte stream (already decoded to lines) into events.

    Kept separate from the socket so it can be tested without a server. Comments (lines starting
    with ':') are keep-alives and are ignored, which matters because the companion server sends
    one every 15 seconds and a parser that treated them as data would emit junk.
    """
    current = SseEvent()
    for raw in lines:
        line = raw.rstrip("\r\n")
        if not line:
            # A blank line terminates an event. Only emit if there was any field set.
            if current.data or current.event != "message" or current.id:
                yield current
            current = SseEvent()
            continue
        if line.startswith(":"):
            continue
        name, _, value = line.partition(":")
        if value.startswith(" "):
            value = value[1:]
        if name == "event":
            current.event = value
        elif name == "data":
            # Multiple data lines concatenate with newlines, per the SSE spec.
            current.data = f"{current.data}\n{value}" if current.data else value
        elif name == "id":
            current.id = value
        # "retry" and unknown fields are ignored.


# --------------------------------------------------------------------------- configuration


@dataclass
class Config:
    """Everything the bridge needs, read from the environment or the command line."""

    bot_token: str = ""
    channel_id: str = ""
    companion_url: str = "http://127.0.0.1:6767"
    companion_token: str = ""
    transport: str = "gateway"
    webhook_url: str = ""
    ingress: str = "gateway"  # gateway | poll
    minecraft_to_discord: bool = True
    discord_to_minecraft: bool = True
    poll_seconds: float = 2.0
    ignore_bots: bool = True
    ignored_prefixes: tuple[str, ...] = ("!", ".", "-", "$")
    verbose: bool = False

    @classmethod
    def from_env(cls, argv: Optional[list[str]] = None) -> "Config":
        parser = argparse.ArgumentParser(
            prog="discord_bridge.py",
            description="Two-way Discord <-> Minecraft chat bridge for Log Client.",
        )
        parser.add_argument("--companion-url", default=os.environ.get("COMPANION_URL",
                            "http://127.0.0.1:6767"), help="Log Client companion base URL")
        parser.add_argument("--companion-token", default=os.environ.get("COMPANION_TOKEN", ""),
                            help="Companion access token (or COMPANION_TOKEN)")
        parser.add_argument("--channel-id", default=os.environ.get("BRIDGE_CHANNEL_ID", ""),
                            help="Discord channel id to bridge (or BRIDGE_CHANNEL_ID)")
        parser.add_argument("--transport", choices=("gateway", "webhook"),
                            default=os.environ.get("BRIDGE_TRANSPORT", "gateway"),
                            help="How to post to Discord")
        parser.add_argument("--webhook-url", default=os.environ.get("BRIDGE_WEBHOOK_URL", ""),
                            help="Webhook URL, for --transport webhook")
        parser.add_argument("--ingress", choices=("gateway", "poll"),
                            default=os.environ.get("BRIDGE_INGRESS", "gateway"),
                            help="How to read Discord messages")
        parser.add_argument("--poll-seconds", type=float,
                            default=float(os.environ.get("BRIDGE_POLL_SECONDS", "2")),
                            help="Poll interval for --ingress poll")
        parser.add_argument("--no-mc-to-discord", action="store_true")
        parser.add_argument("--no-discord-to-mc", action="store_true")
        parser.add_argument("--allow-bots", action="store_true")
        parser.add_argument("--verbose", action="store_true")
        args = parser.parse_args(argv)

        return cls(
            bot_token=os.environ.get("DISCORD_BOT_TOKEN", ""),
            channel_id=args.channel_id,
            companion_url=args.companion_url.rstrip("/"),
            companion_token=args.companion_token,
            transport=args.transport,
            webhook_url=args.webhook_url,
            ingress=args.ingress,
            minecraft_to_discord=not args.no_mc_to_discord,
            discord_to_minecraft=not args.no_discord_to_mc,
            poll_seconds=max(0.5, args.poll_seconds),
            ignore_bots=not args.allow_bots,
            verbose=args.verbose,
        )

    def validate(self) -> None:
        """Fail loudly at startup rather than on the first message."""
        if self.transport == "webhook" and not self.webhook_url:
            raise BridgeError("--transport webhook needs --webhook-url (or BRIDGE_WEBHOOK_URL).")
        if self.webhook_url and not self.webhook_url.startswith("https://discord.com/api/webhooks/"):
            raise BridgeError(
                "The webhook URL does not look like a Discord webhook. "
                "Refusing to post captured chat to an unknown host."
            )
        if self.discord_to_minecraft and not self.bot_token:
            # Both ingresses read through the bot API, so neither works without a token.
            raise BridgeError(
                "Reading Discord messages needs DISCORD_BOT_TOKEN, for either --ingress mode."
            )
        if not self.channel_id:
            raise BridgeError("--channel-id is required (or BRIDGE_CHANNEL_ID).")
        if not self.companion_token:
            raise BridgeError(
                "--companion-token is required (or COMPANION_TOKEN). "
                "Copy it from the Companion module in game."
            )
        if not self.minecraft_to_discord and not self.discord_to_minecraft:
            raise BridgeError("Both directions are disabled, so there is nothing to do.")


# --------------------------------------------------------------------------- companion client


class CompanionClient:
    """Minimal client for the Log Client companion HTTP API.

    The token is sent as an Authorization header rather than a query parameter, so it does not
    end up in a URL that a proxy or a log might keep.
    """

    def __init__(self, base_url: str, token: str, timeout: float = 10.0) -> None:
        self.base_url = base_url.rstrip("/")
        self.token = token
        self.timeout = timeout

    def _headers(self) -> dict[str, str]:
        return {"Authorization": f"Bearer {self.token}", "Accept": "application/json"}

    def _request(self, method: str, path: str, body: Optional[dict] = None,
                 timeout: Optional[float] = None) -> tuple[int, str]:
        url = f"{self.base_url}{path}"
        data = None
        headers = self._headers()
        if body is not None:
            data = json.dumps(body).encode("utf-8")
            headers["Content-Type"] = "application/json"
        request = urllib.request.Request(url, data=data, headers=headers, method=method)
        try:
            with urllib.request.urlopen(request, timeout=timeout or self.timeout) as response:
                return response.status, response.read().decode("utf-8", "replace")
        except urllib.error.HTTPError as error:
            # The body carries the addon's own explanation, which is safe to show.
            return error.code, error.read().decode("utf-8", "replace")
        except urllib.error.URLError as error:
            raise BridgeError(f"Cannot reach Log Client at {self.base_url}: {error.reason}") from error

    def snapshot(self) -> dict[str, Any]:
        status, body = self._request("GET", "/api/v1/snapshot")
        if status != 200:
            raise BridgeError(f"Companion returned HTTP {status} for the snapshot.")
        return json.loads(body or "{}")

    def send_chat(self, message: str) -> tuple[bool, str]:
        """Send one line to Minecraft chat. Returns (ok, explanation)."""
        status, body = self._request("POST", "/api/v1/chat", {"message": message})
        return _result_of(status, body)

    def send_command(self, command: str) -> tuple[bool, str]:
        status, body = self._request("POST", "/api/v1/command", {"command": command})
        return _result_of(status, body)

    def health(self) -> bool:
        try:
            status, _ = self._request("GET", "/health", timeout=5.0)
            return status == 200
        except BridgeError:
            return False

    def events(self, last_event_id: str = "") -> Iterator[SseEvent]:
        """Open the event stream and yield events until the connection drops.

        Reconnection is the caller's job: this returns when the socket closes so the caller can
        back off and resume from the last event id, which the server uses to replay what was
        missed.
        """
        headers = self._headers()
        headers["Accept"] = "text/event-stream"
        if last_event_id:
            headers["Last-Event-ID"] = last_event_id
        request = urllib.request.Request(f"{self.base_url}/api/v1/events", headers=headers)
        try:
            response = urllib.request.urlopen(request, timeout=None)
        except urllib.error.HTTPError as error:
            raise BridgeError(f"Event stream refused with HTTP {error.code}.") from error
        except urllib.error.URLError as error:
            raise BridgeError(f"Cannot open the event stream: {error.reason}") from error

        with response:
            for event in parse_sse_lines(_decode_lines(response)):
                yield event


def _result_of(status: int, body: str) -> tuple[bool, str]:
    """Read a CompanionApi.ActionResult out of a response."""
    try:
        parsed = json.loads(body or "{}")
    except json.JSONDecodeError:
        return status == 200, body.strip()[:200]
    if isinstance(parsed, dict):
        ok = parsed.get("ok")
        if ok is None:
            ok = status == 200
        return bool(ok), str(parsed.get("message", ""))[:200]
    return status == 200, ""


def _decode_lines(response: Any) -> Iterator[str]:
    """Yield decoded lines from an HTTP response.

    ``readline`` is the right primitive for SSE: it blocks until a full line arrives, so a
    keep-alive comment is delivered promptly instead of waiting to fill a buffer.
    """
    while True:
        raw = response.readline()
        if not raw:
            return
        yield raw.decode("utf-8", "replace")


# --------------------------------------------------------------------------- Discord output


class DiscordPoster:
    """Posts messages to Discord through a channel webhook."""

    def __init__(self, webhook_url: str, username: str = "Minecraft", timeout: float = 10.0) -> None:
        self.webhook_url = webhook_url
        self.username = username
        self.timeout = timeout
        self._lock = threading.Lock()

    def post(self, content: str, username: Optional[str] = None) -> bool:
        """Post one message. Returns False when Discord refused it.

        ``allowed_mentions`` is pinned to an empty parse list: this content came from Minecraft
        and must never ping anyone, no matter what a player typed.
        """
        payload = {
            "content": clamp(content, DISCORD_MESSAGE_LIMIT),
            "username": username or self.username,
            "allowed_mentions": {"parse": []},
        }
        data = json.dumps(payload).encode("utf-8")
        request = urllib.request.Request(
            self.webhook_url,
            data=data,
            headers={"Content-Type": "application/json", "User-Agent": f"logclient-bridge/{VERSION}"},
            method="POST",
        )
        try:
            with urllib.request.urlopen(request, timeout=self.timeout) as response:
                return 200 <= response.status < 300
        except urllib.error.HTTPError as error:
            # 429 is worth reporting plainly: the operator usually has the channel rate-limited.
            raise BridgeError(f"Discord refused the message (HTTP {error.code}).") from error
        except urllib.error.URLError as error:
            raise BridgeError(f"Cannot reach Discord: {error.reason}") from error


# --------------------------------------------------------------------------- dedup / echo


class DiscordBotPoster:
    """Posts messages to Discord as a bot, through the REST API.

    Used by the gateway transport. A webhook would also work, but a bot already has a token
    here, and posting as the bot keeps the channel's webhook list clean.
    """

    def __init__(self, token: str, channel_id: str, timeout: float = 10.0) -> None:
        self.token = token
        self.channel_id = channel_id
        self.timeout = timeout
        self._lock = threading.Lock()

    def post(self, content: str, username: Optional[str] = None) -> bool:
        payload = {
            "content": clamp(content, DISCORD_MESSAGE_LIMIT),
            # Captured chat must never ping anyone, whatever a player typed.
            "allowed_mentions": {"parse": []},
        }
        data = json.dumps(payload).encode("utf-8")
        url = f"{DISCORD_API}/channels/{urllib.parse.quote(self.channel_id)}/messages"
        request = urllib.request.Request(url, data=data, method="POST", headers={
            "Authorization": f"Bot {self.token}",
            "Content-Type": "application/json",
            "User-Agent": f"logclient-bridge/{VERSION}",
        })
        try:
            with urllib.request.urlopen(request, timeout=self.timeout) as response:
                return 200 <= response.status < 300
        except urllib.error.HTTPError as error:
            raise BridgeError(f"Discord refused the message (HTTP {error.code}).") from error
        except urllib.error.URLError as error:
            raise BridgeError(f"Cannot reach Discord: {error.reason}") from error


class EchoSuppressor:
    """Stops the bridge from posting its own injections back to Discord.

    Without this, a Discord message becomes a Minecraft chat line, which the bridge then reads
    and posts back to Discord, which becomes another line: an infinite loop that fills the
    channel. Lines we inject are remembered for a short window and matched on content.
    """

    def __init__(self, memory: int = ECHO_MEMORY, window_seconds: float = 20.0) -> None:
        self._entries: list[tuple[float, str]] = []
        self._memory = memory
        self._window = window_seconds
        self._lock = threading.Lock()

    def remember(self, text: str) -> None:
        with self._lock:
            self._entries.append((time.monotonic(), text))
            if len(self._entries) > self._memory:
                self._entries = self._entries[-self._memory:]

    def is_echo(self, text: str) -> bool:
        now = time.monotonic()
        with self._lock:
            self._entries = [(t, s) for (t, s) in self._entries if now - t <= self._window]
            for index, (_, remembered) in enumerate(self._entries):
                if remembered == text:
                    # Consume it: one injection produces one echo, so a player saying the same
                    # thing twice later should still reach Discord.
                    del self._entries[index]
                    return True
        return False


class SeenIds:
    """Bounded set of recently handled Discord message ids, to survive reconnects.

    The gateway replays events after a RESUME, so a naive bridge posts duplicates. A small
    ordered set is enough; it only needs to cover the replay window.
    """

    def __init__(self, size: int = 500) -> None:
        self._order: list[str] = []
        self._seen: set[str] = set()
        self._size = size
        self._lock = threading.Lock()

    def add(self, value: str) -> bool:
        """Record an id. Returns True when it is new."""
        with self._lock:
            if value in self._seen:
                return False
            self._seen.add(value)
            self._order.append(value)
            if len(self._order) > self._size:
                oldest = self._order.pop(0)
                self._seen.discard(oldest)
            return True


# --------------------------------------------------------------------------- Discord input


def should_ignore(content: str, author_is_bot: bool, ignore_bots: bool,
                  prefixes: tuple[str, ...]) -> bool:
    """Decide whether an incoming Discord message should reach Minecraft.

    Bot messages are ignored by default: the bridge's own posts appear as a webhook (which is a
    bot), so ignoring them is a second guard against a loop. Prefixes like '!' are ignored so a
    command bot sharing the channel is not mirrored into game chat.
    """
    if not content or not content.strip():
        return True
    if author_is_bot and ignore_bots:
        return True
    stripped = content.lstrip()
    return any(stripped.startswith(prefix) for prefix in prefixes)


# --------------------------------------------------------------------------- the bridge


class Bridge:
    """Wires the companion client to Discord in both directions."""

    def __init__(self, config: Config, companion: CompanionClient,
                 poster: Optional[DiscordPoster], log: Callable[[str], None]) -> None:
        self.config = config
        self.companion = companion
        self.poster = poster
        self.log = log
        self.echoes = EchoSuppressor()
        self.seen = SeenIds()
        self._stop = threading.Event()
        self._stats = {"to_discord": 0, "to_minecraft": 0, "dropped": 0}

    # -- lifecycle

    def stop(self) -> None:
        self._stop.set()

    def stopped(self) -> bool:
        return self._stop.is_set()

    def wait_stopped(self, seconds: float) -> bool:
        """Sleep, returning True if the bridge was stopped meanwhile."""
        return self._stop.wait(seconds)

    # -- Minecraft -> Discord

    def handle_companion_event(self, event: SseEvent) -> None:
        """React to one companion event. Only chat is bridged."""
        if event.event != "chat":
            return
        try:
            payload = json.loads(event.data)
        except json.JSONDecodeError:
            return
        if not isinstance(payload, dict):
            return
        # The event carries ChatEntry.toMap(), which names the text "content". The snapshot uses
        # "message" for the same field, so both are accepted.
        text = payload.get("content") or payload.get("message") or ""
        sender = str(payload.get("sender") or "")
        server = str(payload.get("server") or "")
        if not text:
            return
        if self.echoes.is_echo(text) or self.echoes.is_echo(f"<{sender}> {text}"):
            self._stats["dropped"] += 1
            return
        if not self.config.minecraft_to_discord:
            return
        if self.poster is None:
            return
        line = format_minecraft_line(sender, str(text), server)
        if self.poster.post(line):
            self._stats["to_discord"] += 1

    def run_event_stream(self) -> None:
        """Follow the companion event stream forever, reconnecting with backoff."""
        backoff = BACKOFF_START
        last_id = ""
        while not self.stopped():
            try:
                for event in self.companion.events(last_id):
                    backoff = BACKOFF_START
                    if event.id:
                        last_id = event.id
                    self.handle_companion_event(event)
                    if self.stopped():
                        return
                # A clean close still deserves a pause, so a restarting server is not hammered.
                self.log("Event stream closed; reconnecting.")
            except BridgeError as error:
                self.log(f"{error} Retrying in {backoff:.0f}s.")
            except (socket.timeout, OSError) as error:
                self.log(f"Event stream dropped ({error}); retrying in {backoff:.0f}s.")
            if self._stop.wait(backoff):
                return
            backoff = min(BACKOFF_MAX, backoff * 2)

    def run_snapshot_poll(self) -> None:
        """Fallback for when the event stream is unavailable: poll the snapshot for new lines.

        Less immediate than the stream, and it cannot see a line that is evicted between polls,
        so it is a fallback rather than the default.
        """
        seen_timestamps: set[tuple[int, str, str]] = set()
        while not self.stopped():
            try:
                snapshot = self.companion.snapshot()
                for entry in reversed(snapshot.get("chat") or []):
                    key = (int(entry.get("timestamp") or 0), str(entry.get("sender") or ""),
                           str(entry.get("message") or ""))
                    if key in seen_timestamps:
                        continue
                    seen_timestamps.add(key)
                    self.handle_companion_event(SseEvent(
                        event="chat",
                        data=json.dumps({
                            "content": entry.get("message") or "",
                            "sender": entry.get("sender") or "",
                            "server": "",
                        }),
                    ))
                if len(seen_timestamps) > 2000:
                    seen_timestamps.clear()
            except BridgeError as error:
                self.log(str(error))
            if self._stop.wait(self.config.poll_seconds):
                return

    # -- Discord -> Minecraft

    def handle_discord_message(self, message: dict[str, Any]) -> None:
        """Take one Discord message object and, if it belongs to us, send it to Minecraft."""
        if not self.config.discord_to_minecraft:
            return
        channel = str(message.get("channel_id") or "")
        if channel != self.config.channel_id:
            return
        message_id = str(message.get("id") or "")
        if message_id and not self.seen.add(message_id):
            return

        author = message.get("author") or {}
        is_bot = bool(author.get("bot")) or message.get("webhook_id") is not None
        content = str(message.get("content") or "")
        if should_ignore(content, is_bot, self.config.ignore_bots, self.config.ignored_prefixes):
            return

        name = str(author.get("global_name") or author.get("username") or "discord")
        line = format_discord_for_minecraft(name, content)
        if not line.strip():
            return

        # Remember what we injected so the echo does not come straight back to Discord.
        body = sanitize_for_minecraft(content)
        self.echoes.remember(body)
        self.echoes.remember(f"<{name}> {body}")

        ok, detail = self.companion.send_chat(body)
        if ok:
            self._stats["to_minecraft"] += 1
            self.log(f"Discord -> MC  <{name}> {clamp(body, 80)}")
        else:
            self.log(f"Discord -> MC failed: {detail or 'no reason given'}")


# --------------------------------------------------------------------------- gateway


class DiscordGateway:
    """A minimal Discord Gateway client: enough to receive MESSAGE_CREATE.

    Only what the bridge needs is implemented. Notably, the bridge does not send messages over
    the gateway; it posts through a webhook, which avoids needing the Send Messages permission
    to be reasoned about separately and keeps this file small.
    """

    # Opcodes from the Discord gateway spec.
    OP_DISPATCH = 0
    OP_HEARTBEAT = 1
    OP_IDENTIFY = 2
    OP_RESUME = 6
    OP_RECONNECT = 7
    OP_INVALID_SESSION = 9
    OP_HELLO = 10
    OP_HEARTBEAT_ACK = 11

    def __init__(self, token: str, on_message: Callable[[dict[str, Any]], None],
                 log: Callable[[str], None], intents: int = 1 | 512) -> None:
        self.token = token
        self.on_message = on_message
        self.log = log
        # GUILDS (1) is required to receive channel traffic; MESSAGE_CONTENT (1 << 15) is what
        # actually gives access to the text. Without the latter, content arrives empty.
        self.intents = intents | (1 << 15)
        self._stop = threading.Event()
        self._sequence: Optional[int] = None
        self._session_id: Optional[str] = None
        self._resume_url: Optional[str] = None

    def stop(self) -> None:
        self._stop.set()

    def run(self) -> None:
        backoff = BACKOFF_START
        while not self._stop.is_set():
            try:
                self._run_once()
                backoff = BACKOFF_START
            except BridgeError as error:
                self.log(f"Gateway: {error}")
            except (OSError, socket.timeout) as error:
                self.log(f"Gateway connection lost ({error}).")
            if self._stop.wait(backoff):
                return
            backoff = min(BACKOFF_MAX, backoff * 2)

    def _run_once(self) -> None:
        try:
            from websockets.sync.client import connect  # type: ignore
        except ImportError as error:
            raise BridgeError(
                "The gateway transport needs the 'websockets' package. "
                "Install it with: pip install websockets   "
                "(or use --ingress poll, which needs nothing extra)."
            ) from error

        url = (self._resume_url or "wss://gateway.discord.gg") + "/?v=10&encoding=json"
        with connect(url, max_size=None) as socket_conn:
            hello = json.loads(socket_conn.recv())
            if hello.get("op") != self.OP_HELLO:
                raise BridgeError("Gateway did not start with HELLO.")
            interval = hello["d"]["heartbeat_interval"] / 1000.0
            heartbeat = threading.Thread(target=self._heartbeat_loop,
                                         args=(socket_conn, interval), daemon=True)
            heartbeat.start()

            if self._session_id and self._sequence is not None:
                self._send(socket_conn, {"op": self.OP_RESUME, "d": {
                    "token": self.token, "session_id": self._session_id,
                    "seq": self._sequence}})
                self.log("Gateway: resuming session.")
            else:
                self._send(socket_conn, {"op": self.OP_IDENTIFY, "d": {
                    "token": self.token,
                    "intents": self.intents,
                    "properties": {"os": sys.platform, "browser": "logclient-bridge",
                                   "device": "logclient-bridge"},
                }})
                self.log("Gateway: identifying.")

            while not self._stop.is_set():
                raw = socket_conn.recv()
                payload = json.loads(raw)
                self._handle(payload)

    def _heartbeat_loop(self, socket_conn: Any, interval: float) -> None:
        while not self._stop.is_set():
            if self._stop.wait(interval):
                return
            try:
                self._send(socket_conn, {"op": self.OP_HEARTBEAT, "d": self._sequence})
            except Exception:
                return

    @staticmethod
    def _send(socket_conn: Any, payload: dict[str, Any]) -> None:
        socket_conn.send(json.dumps(payload))

    def _handle(self, payload: dict[str, Any]) -> None:
        op = payload.get("op")
        if op == self.OP_DISPATCH:
            self._sequence = payload.get("s", self._sequence)
            event = payload.get("t")
            if event == "READY":
                self._session_id = payload["d"].get("session_id")
                self._resume_url = payload["d"].get("resume_gateway_url")
                user = (payload["d"].get("user") or {}).get("username", "?")
                self.log(f"Gateway: ready as {user}.")
            elif event == "RESUMED":
                self.log("Gateway: session resumed.")
            elif event == "MESSAGE_CREATE":
                self.on_message(payload.get("d") or {})
        elif op == self.OP_RECONNECT:
            raise BridgeError("Gateway asked for a reconnect.")
        elif op == self.OP_INVALID_SESSION:
            # Not resumable; start clean.
            self._session_id = None
            self._sequence = None
            raise BridgeError("Gateway session invalidated; reconnecting fresh.")


# --------------------------------------------------------------------------- entry point


def _make_log(verbose: bool) -> Callable[[str], None]:
    def log(message: str) -> None:
        stamp = time.strftime("%H:%M:%S")
        print(f"[{stamp}] {message}", flush=True)

    return log


def build(config: Config, log: Callable[[str], None]) -> tuple[Bridge, Optional[DiscordGateway]]:
    """Assemble the bridge. Separated from main so tests can build one without networking."""
    companion = CompanionClient(config.companion_url, config.companion_token)
    poster: Optional[Any] = None
    if config.transport == "webhook" and config.webhook_url:
        poster = DiscordPoster(config.webhook_url)
    elif config.bot_token and config.channel_id:
        poster = DiscordBotPoster(config.bot_token, config.channel_id)
    bridge = Bridge(config, companion, poster, log)
    gateway = None
    if config.ingress == "gateway":
        gateway = DiscordGateway(config.bot_token, bridge.handle_discord_message, log)
    return bridge, gateway


def main(argv: Optional[list[str]] = None) -> int:
    config = Config.from_env(argv)
    log = _make_log(config.verbose)

    try:
        config.validate()
    except BridgeError as error:
        print(f"error: {error}", file=sys.stderr)
        return 2

    bridge, gateway = build(config, log)

    if bridge.poster is None:
        log("No webhook URL: Discord -> Minecraft only. "
            "Set BRIDGE_WEBHOOK_URL to also post chat to Discord.")

    companion = CompanionClient(config.companion_url, config.companion_token)
    if not companion.health():
        log(f"warning: Log Client is not answering at {config.companion_url}. "
            "Is the Companion module enabled?")
    else:
        log(f"Connected to Log Client at {config.companion_url}.")

    threads: list[threading.Thread] = []

    if config.minecraft_to_discord and bridge.poster is not None:
        target = bridge.run_event_stream
        threads.append(threading.Thread(target=target, name="mc-to-discord", daemon=True))

    if gateway is not None:
        threads.append(threading.Thread(target=gateway.run, name="discord-gateway", daemon=True))
    else:
        threads.append(threading.Thread(target=_poll_ingress, args=(bridge, config, log),
                                        name="discord-poll", daemon=True))

    def shutdown(signum: int, _frame: Any) -> None:
        log("Stopping.")
        bridge.stop()
        if gateway is not None:
            gateway.stop()

    signal.signal(signal.SIGINT, shutdown)
    signal.signal(signal.SIGTERM, shutdown)

    for thread in threads:
        thread.start()
    log(f"Bridging channel {config.channel_id}. Ctrl-C to stop.")

    try:
        while any(thread.is_alive() for thread in threads):
            time.sleep(0.5)
    except KeyboardInterrupt:
        shutdown(0, None)

    bridge.stop()
    if gateway is not None:
        gateway.stop()
    return 0


def _poll_ingress(bridge: Bridge, config: Config, log: Callable[[str], None]) -> None:
    """Read Discord messages by polling the REST API, for webhook-only setups.

    Requires a bot token but no gateway connection, so it survives restricted networks that
    block WebSockets.
    """
    last_id = ""
    while not bridge.stopped():
        try:
            messages = fetch_recent_messages(config.bot_token, config.channel_id, after=last_id)
            for message in messages:
                bridge.handle_discord_message(message)
                last_id = str(message.get("id") or last_id)
        except BridgeError as error:
            log(f"Discord poll: {error}")
        if bridge.wait_stopped(config.poll_seconds):
            return


def fetch_recent_messages(token: str, channel_id: str, after: str = "",
                          limit: int = 50) -> list[dict[str, Any]]:
    """Fetch recent messages from a channel, oldest first."""
    query = {"limit": str(min(100, max(1, limit)))}
    if after:
        query["after"] = after
    url = f"{DISCORD_API}/channels/{urllib.parse.quote(channel_id)}/messages?{urllib.parse.urlencode(query)}"
    request = urllib.request.Request(url, headers={
        "Authorization": f"Bot {token}",
        "User-Agent": f"logclient-bridge/{VERSION}",
    })
    try:
        with urllib.request.urlopen(request, timeout=15) as response:
            payload = json.loads(response.read().decode("utf-8", "replace"))
    except urllib.error.HTTPError as error:
        if error.code == 401:
            raise BridgeError("Discord rejected the bot token (HTTP 401).") from error
        if error.code == 403:
            raise BridgeError("The bot cannot read that channel (HTTP 403).") from error
        raise BridgeError(f"Discord returned HTTP {error.code}.") from error
    except urllib.error.URLError as error:
        raise BridgeError(f"Cannot reach Discord: {error.reason}") from error
    if not isinstance(payload, list):
        return []
    return list(reversed(payload))


if __name__ == "__main__":
    raise SystemExit(main())
