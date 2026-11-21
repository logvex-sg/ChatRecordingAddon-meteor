# Discord bridge

A two-way bridge between a Discord channel and Minecraft chat, built on the Log Client companion
API. Chat from Minecraft appears in Discord; messages in Discord appear in Minecraft.

The bridge is a separate Python process, not part of the mod. That is deliberate:

- **A bot token cannot live in a client mod.** Meteor settings get exported, shared and pasted into
  issue reports. A token in a setting would leak from every user who ever copied a config.
- **The bot has to stay online when the game is closed.** A Discord connection inside Minecraft
  would drop the moment the player quits.

There is nothing to install: `tools/discord_bridge.py` uses only the Python standard library. The
gateway transport additionally needs `websockets`.

---

## Quick start

### 1. Turn on the companion server in game

Open the **Companion** module in Log Client and enable it. It binds to `127.0.0.1` and prints an
access token. Copy that token; the bridge needs it.

Leave the bind address as loopback. If you expose it to a network, anyone who can reach the port
and has the token can send chat and run commands as you.

### 2. Create a Discord application

1. Go to <https://discord.com/developers/applications> and create an application.
2. **Bot** tab, then **Reset Token** and copy it. This is `DISCORD_BOT_TOKEN`.
3. Under **Privileged Gateway Intents**, enable **Message Content Intent**. Without it, Discord
   delivers messages with empty text and the bridge appears to ignore everything.
4. **OAuth2 → URL Generator**: scopes `bot`, permissions `View Channels` and `Send Messages`.
   Open the generated URL and invite the bot to your server.

### 3. Get the channel id

In Discord, enable **Settings → Advanced → Developer Mode**. Right-click the channel, **Copy
Channel ID**. This is `BRIDGE_CHANNEL_ID`.

### 4. Run it

```bash
export DISCORD_BOT_TOKEN='...'
export BRIDGE_CHANNEL_ID='123456789012345678'
export COMPANION_TOKEN='...'          # from the Companion module
pip install websockets                # only for the gateway transport
python3 tools/discord_bridge.py
```

Expected output:

```
[12:00:00] Connected to Log Client at http://127.0.0.1:8765.
[12:00:00] Gateway: identifying.
[12:00:01] Gateway: ready as MyBridge#1234.
[12:00:01] Bridging channel 123456789012345678. Ctrl-C to stop.
```

Type in Discord and it appears in Minecraft. Talk in Minecraft and it appears in Discord.

---

## Configuration

Every option has an environment variable and a flag. Environment variables are preferred, because
a token in your shell history is better than a token in a file you might commit.

| Variable | Flag | Default | Meaning |
|---|---|---|---|
| `DISCORD_BOT_TOKEN` | – | – | Bot token. Required unless Discord → Minecraft is off. |
| `BRIDGE_CHANNEL_ID` | `--channel-id` | – | The one channel that is bridged. |
| `COMPANION_TOKEN` | `--companion-token` | – | Access token from the Companion module. |
| `COMPANION_URL` | `--companion-url` | `http://127.0.0.1:8765` | Where the companion server listens. |
| `BRIDGE_TRANSPORT` | `--transport` | `gateway` | `gateway` posts as the bot, `webhook` posts through a webhook URL. |
| `BRIDGE_WEBHOOK_URL` | `--webhook-url` | – | Required for `--transport webhook`. |
| `BRIDGE_INGRESS` | `--ingress` | `gateway` | `gateway` reads live, `poll` polls the REST API. |
| `BRIDGE_POLL_SECONDS` | `--poll-seconds` | `2` | Poll interval for `--ingress poll`. |

Flags for one direction only:

```bash
python3 tools/discord_bridge.py --no-discord-to-mc    # Minecraft -> Discord only
python3 tools/discord_bridge.py --no-mc-to-discord    # Discord -> Minecraft only
```

### Transports

**gateway** (default) is a real bot connection over WebSocket. Messages arrive instantly and the
bot shows as online. Needs the Message Content intent and `websockets`.

**webhook** needs no gateway connection. Posting goes through a channel webhook, which needs no
bot permissions in that channel. Reading still needs a bot token. Useful when WebSockets are
blocked, or when you already have a webhook URL.

**`--ingress poll`** reads with the REST API instead of the gateway. Still needs a bot token, but
no `websockets`, so it works on restricted networks. Messages arrive up to `--poll-seconds` late,
and a line posted and deleted within one poll interval is missed.

---

## Security

The bridge crosses a trust boundary: **anything typed in Discord reaches Minecraft.**

**Commands cannot be run from Discord.** Log Client's `sendChat` treats a leading `/` as a command
and executes it. The bridge strips leading slashes before sending, so `/op Steve` in Discord becomes
the chat line `op Steve`. Without that rule, anyone who could post in the channel could run
arbitrary Minecraft commands as you. A slash in the middle of a sentence is left alone, so
"gg / good fight" still works.

**Players cannot ping Discord.** Text from Minecraft is escaped before it is posted: `@everyone`,
`@here` and role or user mentions get a zero-width space so Discord does not treat them as pings,
and every post sets `allowed_mentions: {"parse": []}` as a second guard.

**The token never leaves your machine.** It is read from the environment, sent as an `Authorization`
header rather than a query string, and never logged.

**Only one channel is bridged.** Messages from any other channel are ignored, so the bot cannot be
used to funnel an unrelated server's chat into your game.

**Bot messages are ignored** by default. This is also the second guard against a feedback loop: the
bridge's own posts are webhook or bot messages, so they do not come back.

**Prefixes are ignored.** Messages starting with `!`, `.`, `-` or `$` are not mirrored, so a command
bot sharing the channel keeps working. `--allow-bots` and the prefix list are in `Config` if you
need to change them.

### What this does not protect against

Anyone with **Send Messages** in the bridged channel can talk in your Minecraft chat, and can see
everything said there. Treat the channel as being as trusted as your game session. If that is not
acceptable, put the channel behind a role that only you have.

---

## How it works

```
Minecraft ──► Log Client ──► companion /api/v1/events (SSE) ──► bridge ──► Discord
Minecraft ◄── Log Client ◄── companion POST /api/v1/chat    ◄── bridge ◄── Discord
```

The bridge subscribes to the companion event stream and filters for `chat` events. Going the other
way it posts to `/api/v1/chat`, which is the same path the in-game UI uses.

Two details worth knowing if you modify this:

- **The event stream and the snapshot name the message text differently.** Events carry
  `ChatEntry.toMap()`, which uses `content`; the snapshot's `ChatEntryView` uses `message`. The
  bridge accepts either, and a test pins both.
- **Reconnection resumes from the last event id.** The server replays what was missed when the
  `Last-Event-ID` header is present, so a dropped connection does not lose chat.

---

## Troubleshooting

**Nothing appears in Discord.** Check the Companion module is enabled and the token matches. The
bridge prints a warning at startup when it cannot reach the companion server.

**Nothing appears in Minecraft.** The bridge logs `Discord -> MC failed: <reason>`. "You are not in
a world" means the client is at the main menu.

**Discord messages arrive with empty text.** The Message Content intent is off. Enable it in the
developer portal; a running bot has to be restarted to pick it up.

**`Gateway: 4014` or the bot disconnects immediately.** The bot lacks an intent it needs, usually
Message Content. Check the portal and re-invite with the right scopes.

**Duplicate messages.** The gateway replayed events after a reconnect and the dedup window was too
small. `SeenIds` holds 500 ids; raise it if you bridge a very busy channel.

**Rate limited (HTTP 429).** The channel is sending faster than Discord allows webhooks to post.
The bridge reports it rather than retrying, so you see it instead of a silent gap.

---

## Tests

```bash
python3 -m unittest discover -s tools/tests -v
```

66 tests: unit tests for the text handling, SSE parser, dedup and config validation, plus
integration tests that run the real client against a real HTTP and SSE server on a loopback socket.
