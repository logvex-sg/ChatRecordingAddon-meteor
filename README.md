# Log Client

A standalone Fabric addon for Minecraft 1.21.1.

It runs on Fabric Loader alone: the event bus, HUD system, module registry, GUI kit, command
dispatcher and mixins all live inside the addon (`com.logvex.addon.framework`), so **Meteor Client
is not required**. Baritone is used for navigation only when it happens to be installed, and is
detected at runtime rather than compiled against.

**Chat toolkit** — `chat-logger` (records chat to compressed, searchable logs),
`chat-monitor` (live feed with filters and highlighting), `keyword-alerts` (keyword alerts with
sound and optional webhook), `player-activity` (per-player activity tracking) and
`session-stats` (session statistics), with matching HUD elements.

**Chat intelligence** — `chat-filter` (ordered hide/allow rules applied in the chat mixin, so a
filtered message never reaches chat, the recorder or the log), `chat-digest` (on-demand and timed
summaries of chat activity), `session-timeline` (a bounded timeline of joins, leaves, deaths and
keyword hits), `webhook-monitor` (delivery health, queue depth and a bounded delivery history),
`player-notes` (your own notes about players, shown next to their observed activity),
`player-identity` (observed account name changes and a watchlist) and `session-history` (a
browsable history of past recording sessions).

**Schematic Dropper** — imports a material report or a schematic, acquires missing materials in
Creative mode, drops them one *server-confirmed* transfer at a time, and can place a schematic
block by block into the world with verified placements and resumable checkpoints.

**Notifications** — `notifications` collects events from the other modules into one searchable
history with a HUD and a GUI, and can echo them to chat and play a sound.

**Assistant** — `assistant` is a configurable chat bot: keyword triggers answer messages from chat
(with per-rule sender filters, reply modes, cooldowns, session limits and sounds), memory commands
(`!remember`, `!fact`, `!forget`, ...) store and recall facts, and everything the assistant knows is
persisted to a JSON file that survives a restart.

**Companion** — `companion` serves a small web dashboard and JSON API from inside the client, so a
phone or another computer on the same network can watch and control the bots: live events over
server-sent events, the bot controls, the task queue, the module switches, chat and player search,
statistics and storage usage. It is off by default, binds to loopback, and needs an access token.
See [Companion (web app)](#companion-web-app).

**GUI** — Right Shift (or `.log gui`) opens the addon window: a blue-themed, card-based interface
listing every module and HUD element with an on/off switch, a search box and a settings page, plus a
theme picker and a drag-and-drop HUD editor. Module settings are grouped and searchable, and every
value is persisted to `<gamedir>/logclient/config.json`.

**Commands** — a `.log` (alias `.la`) command tree exposes status, the module list, session
statistics, notifications, the automation task queue, waypoints and schematic control from chat.

## Building

```bash
./gradlew clean test build
```

The jar is written to `build/libs/log-client-13.0.0.jar`. Drop it into `.minecraft/mods`. No other
mod is required; Baritone is optional and only affects bot navigation.

### Java 21 is required for the Gradle daemon

Gradle 8.12 ships Groovy 3.0.22, whose bundled ASM cannot read class files newer than Java 24.
If the Gradle daemon runs on Java 25 or later, build-script compilation fails with:

```
BUG! exception in phase 'semantic analysis' in source unit '_BuildScript_'
Unsupported class file major version 69
```

`gradle/gradle-daemon-jvm.properties` pins the daemon (and the compile/test toolchain) to Java
21, so `./gradlew clean build` works even when a newer JDK is first on `PATH` — no
machine-specific paths and no `org.gradle.java.home`. The build only needs *a* Java 21
installation discoverable by Gradle (e.g. `/usr/lib/jvm/java-21-openjdk-amd64` on Debian/Kali,
or an auto-provisioned toolchain). If Gradle reports `Cannot find a Java installation ...
matching: Compatible with Java 21`, install one:

```bash
sudo apt install openjdk-21-jdk        # Debian / Kali
```

Then verify with `./gradlew --version` — the `Daemon JVM` line should read
`Compatible with Java 21, any vendor (from gradle/gradle-daemon-jvm.properties)`.

## Schematic Dropper

The module drives a small state machine
(`IDLE → SCANNING → SELECTING → ROTATING → DROPPING → WAITING_FOR_CONFIRMATION → …`) that is
ticked once per client tick and never blocks the client thread.

Progress only ever advances on a **confirmed** transfer. Confirmation is derived from the
server's own inventory correction packets, not from the fact that a click was sent, so the addon
cannot report progress for a drop the server rejected or that only existed as a ghost item.

### Controls (module widget)

| Control | Effect |
| --- | --- |
| Start | Begins a run from the current confirmed progress. |
| Pause / Resume | Suspends and continues a run. A run with an in-flight confirmation finishes that confirmation before pausing. |
| Stop | Cancels the pending operation and returns to idle. |
| Reset Progress | Clears confirmed counters without touching the plan or the inventory. |
| Rescan Inventory | Forces a fresh inventory scan. |
| Material Overview | Opens the material overview screen. |
| Acquire Materials | Starts automatic Creative acquisition from the imported list. |
| Acquisition... | Opens the acquisition screen (progress, per-material status, controls). |
| Import Material List... | Opens the built-in file browser and imports a whole material report (or a schematic). |
| Import Material Path | Imports the file named by **material-file**, for scripted or command-line use. |
| Load Schematic... | Opens the built-in file browser and loads a `.schem` / `.schematic` / `.litematic` build. |
| Load Path | Loads the file named by **schematic-file**, for scripted or command-line use. |
| Start Build | Places the loaded schematic block by block, verifying each placement. |
| Pause / Resume / Stop (build row) | Controls a schematic build. |
| Schematic Builder... | Opens the build screen (progress, problems, history, checkpoints). |

### Importing a material list

**Import Material List...** opens the built-in file browser and parses the
**entire** file: the importer never stops at a fixed entry count and never silently truncates.
Two input formats are supported.

*Simplified Log Client format* — `minecraft:<id> <quantity>`, one per line:

```text
minecraft:quartz_pillar 3861
minecraft:spruce_planks 3420
```

Tabs, variable whitespace, Windows/Unix line endings and blank lines are all accepted, quantities
larger than 64 are kept, and the original order is preserved.

*Original material table format* — the markdown table produced by schematic sites:

```text
| Material | Quantity |
| quartz pillar | 3861 (60 stacks + 21) |
```

The stack breakdown is ignored, thousands separators are handled, and only the rows under the
`Material List` heading are imported. `Raw Resources Required` and `Choice Requirements` are
skipped, so crafting ingredients never enter the queue.

Duplicate item ids are aggregated onto their first occurrence. Every entry is validated against
the Minecraft 1.21.1 item registry and anything unknown is reported as an invalid entry (capped in
chat, with the full list in the debug log). The imported list replaces the module's material list,
so both the drop workflow and the acquisition workflow operate on exactly what was imported.

### Import preview

After a file is parsed the **Import Preview** screen opens (and can be reopened any time from the
module's **Import Preview** button). It shows the material count, total item count, valid entries,
duplicates merged and invalid entries, then the **first and last entries** so the range can be
confirmed, and the complete ordered list with a filter box and paging for long lists. Nothing is
acquired or dropped until **Start Acquisition** or **Start Drop Run** is pressed, so a bad import
costs a click rather than a wrong build.

### Choosing files

**Import Material List...** and **Load Schematic...** open the addon's own file browser, built from
the same widgets as the rest of the GUI. It has no native dependency, so it works on every desktop
and on headless setups where the OS file dialog (`tinyfd`) returns nothing — which is what used to
make loading a file look broken. The browser starts in `<gamedir>/schematicdropper`, pages
a directory of any size, filters to the relevant extensions (including `.nbt` and `.dat`, which
schematic exports often use), and has **Up** and **Data Folder** buttons. The typed-path buttons
(**Import Material Path**, **Load Path**) and the `.log import` / `.log schematic load` commands
remain for scripted use.

A schematic is identified by its **content**, not only its extension. The extension is a hint; when
it is missing or wrong the leading bytes and the parsed NBT decide the format (a litematic has a
`Regions` compound, a Sponge/WorldEdit file has a `Palette` with `BlockData`/`Data`, an MCEdit file
has a `Blocks` array with dimensions). A schematic that lost its extension, or carries a generic
`.nbt`, therefore still loads instead of failing as an unsupported file type.

### Automatic Creative acquisition

With **auto-acquire** enabled, the module creates the missing quantity of every imported material
through the Creative inventory, verifies each give against the server, then hands the acquired
materials to the normal drop workflow:

`IMPORT → VALIDATE → CALCULATE → ACQUIRE → VERIFY → DROP → VERIFY DROP → NEXT MATERIAL`

- Only the missing quantity is created; existing inventory is reused.
- Quantities larger than one stack and larger than the whole inventory are handled: with
  **auto-drop** the acquired batch is dropped through the ordinary workflow before the next batch,
  so a 367-material list does not have to fit in 36 slots.
- A give is confirmed only when the server's own slot correction arrives *and* the slot really
  holds the requested item. A rejected or unconfirmed give is never reported as success.
- Failures are classified and surfaced in plain language: not in Creative mode, no creative
  permission, unknown item id, full inventory, invalid quantity, or disconnected.
- **Pause**, **Resume**, **Stop**, **Retry Failed** and **Restart Current** are available from the
  acquisition screen.

### Building a schematic

With **build-enabled** turned on, the module can place a schematic file into the world block by
block. The pipeline is:

`LOAD FILE → COMPARE WITH WORLD → ORDER BY DEPENDENCY → PLACE → VERIFY → CHECKPOINT → NEXT BLOCK`

- **Load Schematic...** reads `.schem` (Sponge v1/v2/v3), `.schematic` (MCEdit) and `.litematic`
  files. Loading happens on a background worker and the result is applied on the next tick, so a
  large file never blocks the game thread. Both the legacy top-level `Palette`/`BlockData` layout
  and the Sponge v3 `Blocks` container (WorldEdit 7.3+ default) are read, and the WorldEdit
  `Schematic` envelope (the payload nested under a `Schematic` key, as the Sponge specification
  describes) is unwrapped first. Without that unwrap every modern `.schem` read an empty root and
  was rejected, which is the "can't import schematics" symptom. Litematic regions are unpacked with
  the same tightly packed bit array Litematica writes, so an entry that straddles a `long` boundary
  (palette widths of 5, 6, 7 … bits) is read correctly instead of coming back as air.
- If the native file dialog does not open (some Linux desktops and headless setups return nothing,
  which looks like "import does nothing"), set **schematic-file** in the *Schematic Building*
  settings group to the full path and press **Load Path**. This reads the same file through the
  same loader without touching the dialog. **Import Material Path** does the same for the
  **material-file** setting in the *Materials* group. Both buttons also tell you when the dialog
  returned nothing, instead of failing silently.
- Loading and importing complete on the module's client tick, so the module must be enabled. If it
  is not, the buttons enable it for you (when you are in a world) rather than appearing to do
  nothing.
- The loaded file's block counts are copied into the material list once per load, so the same
  schematic can also be dropped or acquired through the existing workflows. Blocks that have no
  item form (water, lava, fire and similar) are skipped, since they can neither be dropped nor
  acquired.
- The world is compared first: blocks that already match are skipped, blocks that are merely
  replaceable (grass, water, snow) count as missing, and an unrelated block in the way is reported
  as a conflict according to the **conflicts** setting.
- Placement order comes from the dependency solver: gravity-affected blocks are placed after their
  support, attached blocks after the block they hang from, and multi-cell blocks (doors, beds, tall
  plants) as a unit.
- Every placement is confirmed from the world, never from the fact that a click was sent. A
  placement that does not appear is retried up to the retry limit and then recorded in the failure
  log.
- Progress is checkpointed to `<gamedir>/schematicdropper/checkpoints/<name>.json` after each
  confirmed block, written atomically (temp file + move). On resume the checkpoint is
  **re-validated against the world**, so a build can never skip a block that was changed while the
  game was closed.
- **Schematic Builder...** shows progress, the active queue, recorded problems, session history and
  the saved checkpoints.

### Settings

- **General** — auto-continue, operation delay, retry limit/backoff, recovery mode
  (strict order vs. best effort), pause on missing, completion notification.
- **Materials** — the material list, ordering mode.
- **Rotation** — enable, instant/smooth, speed, restore rotation, target update interval,
  drop pitch.
- **Inventory** — silent (screen handler) or open-inventory mode, stack selection strategy,
  rescan interval, sync timeout.
- **Targeting** — player position or looked-at entity.
- **Progress** — HUD enable, compact/expanded, scale, and per-field visibility.
- **Creative Acquisition** — auto-acquire, auto-drop, acquisition delay, retry limit, sync
  timeout, scan interval.
- **Schematic Building** — build-enabled, origin (player/custom), custom coordinates, conflict
  policy, blocks per tick, range, checkpoints, build notifications.

### Rotation

`RotationMath` is pure maths (no Minecraft types) so it is unit tested directly. Yaw is computed
from the horizontal direction and pitch from the vertical offset; the result is normalised and
clamped, and invalid (non-finite) targets are rejected before any rotation is applied. Rotation
is applied through the addon's own `Rotations` helper, which sends a look packet and then restores
the client's own rotation — so the port keeps its own interpolated state instead of reading
`player.getYaw()`.

## Autonomous bot

The `auto-bot` module runs a Minecraft-free planning core (`com.logvex.addon.bot.core`) behind a
thin Minecraft shell (`BotModule` plus the `bot.mc` adapters). Objectives are turned into tasks,
tasks run through executors (navigate, gather, craft, build, …) one per tick, and every action is
confirmed from the world or the server before it is treated as done.

### Navigation and Baritone

Navigation goes through the addon's own path-manager abstraction (`PathManagers`), so **no separate
Baritone mod jar is bundled or required**. When Baritone is installed, the addon detects it at
runtime (reflection only) and drives it via `IPathManager.moveTo(...)`; when it is not present,
the path manager is the `none` backend and the bot falls back to a bounded built-in walker
that walks toward the target and gives up when it stops making progress. The active backend is
shown on the bot dashboard (`Navigation: Baritone` / `built-in walker`) and, optionally, in the
HUD (`show-navigation`).

The Navigation settings group exposes the path-manager options — `walk-on-water`, `walk-on-lava`,
`path-step` and `path-no-fall`. These are written into Baritone's settings, so Baritone and the
bot agree on how the player may move; they have no effect with the built-in walker. Movement is never teleported or otherwise faked: success requires the player's
actual position to be within the arrival tolerance.

### Profiles

A **profile** is a named snapshot of the bot's settings. **Profiles...** on the bot dashboard (or
the `.bot profile` command) lists the built-in profiles, applies one, captures the current settings
under a new name, and deletes user profiles. Built-in profiles are marked and cannot be overwritten
or deleted, so a known-good starting point is always available. Applying a profile changes only the
settings the profile actually stores; everything else is left as it is, so a profile saved before a
new setting existed still loads cleanly. Profiles are written to
`<gamedir>/logclient/profiles.json` with an atomic write; a corrupt file loads as empty rather than
resetting your configuration.

### Emergency stop

The dashboard's **Emergency Stop** button (or `.bot emergency`) is the immediate, no-questions stop:
it cancels the active task, clears the queue, cancels goals and scheduled tasks, stops navigation and
cancels any in-flight block interaction, then returns to idle. It is deliberately more forceful than
**Stop**, which keeps the queue so work can be resumed.

### `.bot` command

The `.bot` command (alias `.b`) is the short front end for the bot, built on the same methods the
dashboard and HUD use, so a command never diverges from the GUI:

| Command | Effect |
| --- | --- |
| `.bot [status]` | Automation state, queue length, navigation backend and current destination. |
| `.bot start\|pause\|resume\|stop` | Controls the automation, validating the current state first. |
| `.bot emergency` | The immediate stop described above. |
| `.bot goto <x y z>` or `.bot goto <waypoint>` | Queues a navigation task to a coordinate or a saved waypoint. |
| `.bot tasks [clear]` | Lists the queue, or empties it. |
| `.bot waypoint add\|del\|list <name>` | Manages named waypoints. |
| `.bot profile [list\|apply\|save\|delete] <name>` | Manages bot profiles. |


## Assistant

`assistant` is a chat bot that lives inside the addon. It is off by default; enable it like any
other module. Nothing is said or done unless a rule you wrote asks for it, so an empty
configuration is silent.

### Keyword triggers

A trigger watches incoming chat for a keyword or phrase and answers it. Triggers are stored as a
single line each, so the whole feature set is editable from the settings list, the
`.ai triggers` command or the Assistant Manager GUI:

```
pattern;sender=;reply=;mode=chat|notify|silent;cooldown=3000;limit=0;sound=false;remember=false;enabled=true
```

| Field | Meaning |
| --- | --- |
| `pattern` | The keyword or phrase to look for. |
| `case` | Match case-sensitively (`false` by default). |
| `whole` | Only match whole words, so `cat` does not fire inside `concatenate`. |
| `sender` | Only fire for this player. Blank matches anyone. |
| `reply` | What to say. Supports `{player}`, `{message}`, `{time}`, `{date}`, `{server}`, `{fact:key}`, `{count:name}` and `{memory}`. |
| `mode` | `chat` says it publicly, `notify` raises a local notification only, `silent` records the match without speaking. |
| `cooldown` | Minimum milliseconds between two fires of the same rule. |
| `limit` | Maximum fires per session; `0` is unlimited. |
| `sound` | Play a chime when the rule fires. |
| `remember` | Write the exchange to memory. |
| `enabled` | Turn the rule off without deleting it. |

The first rule that matches wins, so a specific rule can be placed above a broad one. A global
rate limit (default 20 replies per minute) caps how often the assistant can answer in total, and a
rule's cooldown and session limit cap it per rule — the assistant cannot flood chat even if a
keyword appears in every message.

The assistant never answers itself: a message from the local player, or one that matches one of
its own recent replies, is ignored.

### Memory commands

When `memory-commands` is on, chat messages that start with the command prefix (default `!`) are
understood:

| Command | Effect |
| --- | --- |
| `!remember key = value` | Store a fact. |
| `!fact key` | Read a fact back. |
| `!forget key` | Remove a fact. |
| `!facts` | List stored facts. |
| `!memory` | A short memory summary. |
| `!whoami` | What the assistant knows about you. |
| `!forgetme` | Erase everything stored about you. |
| `!history` | Recent remembered exchanges. |

How the assistant answers these is controlled by `memory-reply-mode` (`chat`, `notify` or
`silent`). The default, `notify`, keeps answers out of public chat.

### Settings

| Setting | Meaning |
| --- | --- |
| `name` | Shown in the HUD and notifications. |
| `respond-to-triggers` | Whether keyword triggers are answered at all. |
| `memory-commands` / `command-prefix` | Whether memory commands are understood, and their prefix. |
| `watch-public-chat` / `watch-private-messages` / `watch-system` | Which message types are watched. |
| `rules` | The trigger list, one encoded rule per line. |
| `remember-conversations` | Record recent exchanges so `!history` and `{fact:...}` stay useful. |
| `memory-reply-mode` | How memory commands are answered. |
| `persist` | Save memory to disk so it survives a restart. |
| `rate-limit-per-minute` | Global reply cap; `0` disables it. |
| `max-reply-length` | Longest reply, in characters. |
| `match-feedback` | A short local note when a trigger matches. |

### Assistant Manager

`.ai gui` (or the module's "Assistant Manager..." button) opens a window with three tabs:

- **Triggers** — add, edit, enable/disable and remove rules; restore the defaults.
- **Memory** — add, filter and forget facts, see recent exchanges, clear history or all memory.
- **Status** — counts, the memory file path and a "test a message" box that shows what the
  assistant would do with a line of text without sending anything.

### HUD

The `assistant` HUD element shows the number of triggers, remembered facts and handled messages.
It shows a single "Disabled" line when the module is off, so it never displays stale data.

### `.ai` command

| Command | Effect |
| --- | --- |
| `.ai [status]` | Assistant state and memory summary. |
| `.ai gui` | Opens the Assistant Manager. |
| `.ai triggers [list]` | Lists the loaded triggers. |
| `.ai triggers add <pattern> <reply>` | Adds a trigger. |
| `.ai triggers remove <n>` | Removes trigger number `n`. |
| `.ai triggers restore` | Restores the default triggers. |
| `.ai facts` | Lists stored facts. |
| `.ai remember <key> <value>` | Stores a fact. |
| `.ai forget <key>` | Removes a fact. |
| `.ai test <message>` | Shows what the assistant would do with a message. |
| `.ai history` | Recent remembered exchanges. |
| `.ai save` / `.ai clear` | Save memory now / clear all memory. |

### Storage

Memory is stored at `<gamedir>/logclient/assistant-memory.json`. Writes are atomic (temp file plus
move) and happen on a background thread on a slow cadence, so a busy chat cannot cause a disk-write
storm and a crash mid-save cannot corrupt the file. A missing or damaged file loads as empty; a
file written by a newer build is refused rather than silently downgraded.


## Automation modules

These modules automate small, legitimate client actions. Each one is a thin Minecraft shell over a
Minecraft-free controller in `com.logvex.addon.automation.core`, so the decision logic is unit-tested
without a running client. Every module stops acting when the world goes away, resets on a reconnect
and releases any input it pressed when it is disabled.

| Module | What it does |
| --- | --- |
| `auto-eat` | Eats from the hotbar when hunger drops to a threshold, preferring the held food with the highest nutrition. Only selects a slot and uses the item; it never creates food. |
| `auto-tool` | Swaps to the best hotbar tool for the block being mined (suitability, then speed), optionally swapping back afterwards. It only changes the selected slot. |
| `auto-replenish` | Keeps the items you list stocked on the hotbar by moving a stack into place with a normal slot swap. It never invents items and never takes a stack another requested slot needs. |
| `auto-sprint` | Holds sprint on while you move forward, optionally only above a food threshold. |
| `safe-walk` | Releases the forward key at the edge of a drop, exactly as a cautious player would. It only ever removes movement input. |
| `anti-afk` | Performs small, genuine client actions on a randomised schedule (look, jump, sneak, swing, step, swap hand) so you are not kicked for idling. |
| `armor-guard` | Warns when a piece of armour is close to breaking. A missing piece is reported as unavailable, never as "0 % durability". |
| `auto-log` | Disconnects when health, food, inventory space, a hostile mob or another player crosses a rule you enabled. Off by default; every rule is opt-in, and there is a grace period after joining. |
| `auto-reconnect` | Reconnects to the last **multiplayer** server after a disconnect, using vanilla's own connection screen, with a bounded backoff and a retry cap. |
| `server-monitor` | Prints a periodic status line (duration, game mode, latency, free slots) and a session summary on leave, using only what the client can observe. |
| `auto-creative` | Imports a material list (or takes typed entries), acquires the items in Creative mode and can hand the ordered list to the Schematic Dropper queue. See below. |

### Automatic Creative acquisition

`auto-creative` and the Schematic Dropper's acquisition workflow both use the same real give path:
`clickCreativeStack`, the call vanilla's Creative inventory uses, which sends a
`CreativeInventoryActionC2SPacket`. A give is only counted once the **server** acknowledges it and the
slot really holds the requested item, so a client-only prediction is never reported as success.

The workflow is `IMPORT → VALIDATE → CALCULATE → ACQUIRE → VERIFY → DROP → VERIFY DROP → NEXT`.
Materials are processed in imported order; only the missing quantity is acquired; a give is targeted
at a slot that can actually make progress (a full stack is skipped for an empty slot); a rejected give
is retried a bounded number of times before the item is reported failed. In Survival, or without the
creative permission, the module says so and does nothing.

Open the acquisition manager from the module widget (**Open Acquisition Manager**) or run
`.log acquire`. It shows each material with its icon, required/held/still-needed quantities, status
and the overall progress, and provides Start, Pause, Resume, Stop, Retry Failed, Restart Current and
the two import buttons.

### Importing a material list into the acquisition queue

Both the Schematic Dropper and `auto-creative` accept the same two input shapes (auto-detected):

```text
# Format A - simplified
minecraft:quartz_pillar 3861
minecraft:spruce_planks 3420
```

```text
| Material | Quantity |
| quartz pillar | 3861 (60 stacks + 21) |
| spruce planks | 3420 (53 stacks + 28) |
```

The whole file is read to EOF — no line or entry cap — with tabs, CRLF and blank lines tolerated,
quantities above one stack preserved, duplicates merged onto their first occurrence and the original
order kept. In a multi-section report only the **Material List** section is imported; *Raw Resources
Required* and *Choice Requirements* are ignored. Every id is validated against the 1.21.1 item
registry and invalid entries are reported separately. The import is followed by a preview (unique
materials, total required, valid/invalid counts, duplicates merged, first and last entry) before
anything is acquired.

## Chat intelligence modules

These modules extend the chat toolkit. They share the same output formatter (`com.logvex.addon.common.Messages`)
so every line reads the same way: a `[Log]` prefix, sentence case, readable numbers and proper
pluralisation.

| Module | What it does |
| --- | --- |
| `chat-filter` | Ordered hide/allow rules. The first matching rule wins, so a specific `ALLOW` can punch a hole in a broad `HIDE`. A matched message is cancelled in the chat mixin, so it is removed before it reaches the chat HUD, the recorder or the log file. |
| `chat-digest` | Summarises the last N messages: totals per message type, unique players, joins/leaves/deaths and the most active players. Run it on demand (`.log digest`) or on a timer. Optionally forwards each digest to the webhook. |
| `session-timeline` | A bounded timeline of milestones the client can actually observe: session start/end, joins, leaves, deaths and keyword hits. Browsable in a GUI (`.log timeline`). |
| `session-stats` | Live session counters, and a short **session summary printed when you leave a world** (messages, unique players, duration, rate, joins/leaves, deaths, keyword alerts and the most active players). |
| `webhook-monitor` | Watches the shared webhook queue and reports its health. Keeps a bounded delivery history, warns in chat when a delivery fails and can note a recovery. The URL is never stored or shown — only the host. |
| `player-notes` | Your own notes about players, with the observed activity shown beside each note. A note is always explicit user input; the addon never infers one. |
| `player-activity` | Per-player joins, leaves, deaths and message counts, rebuilt from the archive on demand. Supports an **exclude list** so bots or your own alts are never tracked, and shows observed online time per player. |
| `player-identity` | Tracks the names each **account** (by UUID) is seen using, and reports a rename only when the same UUID is later announced under a different name. Includes a per-account **watchlist**; watched renames are highlighted. Never merges two accounts that share a name. |
| `session-history` | A bounded, browsable history of past recording sessions (server, duration, message/event counts), with a configurable cap. Each session is folded into a compact record when it ends, so past sessions can be reviewed without re-reading the archive. |

### Activity graph

The shared runtime counts messages into per-minute buckets (a bounded ring, so memory is fixed).
The **Activity Graph** HUD element draws the last 5–120 minutes as a bar graph scaled to the busiest
minute, and shows the peak and the total for the window. Empty buckets are drawn as a baseline so a
quiet stretch is visible rather than hidden.

### Duplicate grouping

Some servers and chat mods deliver the same line more than once. The **Chat Log** HUD has a
`group-duplicates` setting that collapses *consecutive* repeats into one line with a count, e.g.
`Alex: buying diamonds (x4)`. Only runs of identical consecutive messages (same sender and text) are
merged, so a message that recurs later keeps its own place. It is off by default.

### Player session durations

Join and leave are the only online signals a client sees, so a session is opened by a join and
closed by the matching leave. The tracker keeps the total observed online time, the number of
completed sessions and whether the player is believed to be online right now.

- A player who was **already online** when you connected never produced a join, so no duration can
  be measured. That case is shown as a dash (`-`), never as `0`, so an unknown value and a
  genuine zero are distinguishable.
- The **Recent Players** HUD lists the players seen most recently, with a time-ago and an online
  dot; the **Activity** HUD can add each player's online time; the **Player Activity** screen shows
  an *Online* column.
- `.log playtime [player]` prints the table in chat. Players on the activity exclude list are not
  tracked.

### Player identity and renames

A client cannot see an account's rename history. It can only notice that the **same UUID** is later
announced under a **different name** in the server player list, so that is exactly what
`player-identity` reports — nothing more.

- Two different accounts that happen to share a name are tracked separately and never merged.
- A first observation is not a rename; the previous name is retained and shown as `was <name>`.
- `.log identities [renamed|watch <player>|unwatch <player>|watchlist|clear|<filter>]` browses the
  index in chat; the **Player Identity** screen (`.log identities`, or the module's button) shows a
  table with the current name, previous name, change count, last-seen time and a Watch toggle.
- The **Player Identity** HUD lists the most recent renames as `old -> new`, with a time-ago; a
  watched player's line is highlighted. With nothing observed it says `No renames observed` rather
  than showing an empty box.
- Renames are recorded even while the module is disabled (the packet subscription is installed once
  at bootstrap, like the recorder), so turning the module on never loses history. Notifications are
  raised by the module while it is active.
- The index is saved to `identities.json` when a rename is observed and on shutdown, and restored on
  the next launch, so a rename seen in an earlier session is still reported afterwards. Clearing the
  index (command, module button or screen) is written through immediately.

### Session history

Every session that ends is folded into a compact record: server, start and end time, and the
message, player, join, leave, death and keyword-alert counts that were actually observed.

- `.log sessions [list|clear]` prints the totals and the most recent sessions in chat.
- The **Session History** screen (the module's button) shows a table with a **View Log** button per
  session that opens the log viewer filtered to that session's id.
- The history is bounded; the cap is the module's `history-size` setting and takes effect
  immediately when lowered. A session id is never stored twice.

### Alert history

Chat output is deliberately terse, so a message that scrolled past is easy to miss. Every keyword
hit, webhook delivery failure and internal error is also written to a bounded alert log.

- The dashboard **Alerts** tab shows counts by kind and the most recent entries.
- The **Alert History** screen (`.log alerts`, or the button on that tab) is searchable by kind,
  title or detail, and can be cleared.
- The log never grows without bound and holds no more than the configured capacity.

### Chat filter rules

A rule is one line of `pattern;sender=NAME;type=TYPE;action=hide|allow;case=true;whole=false;enabled=true`. Any part may
be omitted: an empty pattern matches any text, an empty sender any player and a missing type any
message type. `whole` matches whole words only. The settings page for `chat-filter` documents the
format, and the default rule list hides `wts`/`wtb` while always allowing server announcements.

## Live Chat Relay

- Buffers chat (player chat and, optionally, game messages).
- Sends a report every *N* minutes (default 5) and on demand through **Send Now**.
- Messages leave the buffer **only after** a webhook request succeeds, so a failed delivery never
  loses chat.
- Every report is optionally archived as gzip-compressed JSONL, one file per report, with an
  `index.json` holding only metadata. Report bodies are decompressed lazily when opened.
- Writes are atomic (temp file + move) and serialised through a single lock, and a corrupted
  entry is reported rather than silently mis-parsed.

### Controls (module widget)

| Control | Effect |
| --- | --- |
| Send Now | Sends the current buffer immediately. Refuses while a delivery is in flight. |
| View Saved Reports | Opens the archive browser (search, sort, open, delete, delete-all). |
| Run Archive Cleanup | Applies the retention limits right away. |

### Archive settings

Save reports, compression level, maximum archive size, maximum age, maximum report count,
automatic cleanup, and storage directory. The browser header shows the total compressed size,
the total uncompressed size, the report count and the space saved.

Webhook URLs are treated as secrets: they are never written into a report, never included in
error messages, and the Discord payload explicitly disables mentions.

## Companion (web app)

`companion` serves a small web dashboard from inside the client so a phone, tablet or another
computer on the same network can watch and control this client. It is the "app" that connects to
the bots: the client is the server, the browser is the app, and nothing is installed on the phone.

Enable **Companion** in the addon window (or run `.log companion start`). The server listens on
**127.0.0.1** by default; the module widget, the Companion screen and the command all offer a
**Copy address** action that includes the access token, so you can paste one URL into a browser.

### What the app shows

- **Overview** — the player, server address, game mode, in-world state and how long the client has
  been running.
- **Live events** — a server-sent event stream (`/api/v1/events`) that pushes chat lines, bot state
  changes and session changes as they happen. It reconnects on its own and can replay the events it
  missed from a bounded history.
- **Bot** — the bot state, the active task, the queue length and the recent events, with **Start**,
  **Pause**, **Resume**, **Stop** and **Emergency stop**.
- **Tasks** — the queue and the history counters, with **Queue objective** (the same forgiving
  parser the dashboard uses, e.g. `gather 256 oak_log`) and **Cancel** per task.
- **Modules** — every module with an on/off switch, searched by name.
- **Chat** — the recent chat with a search box and a player filter.
- **Players** — observed players with their message counts and last-seen times, marked online when
  the client's player list currently contains them.
- **Stats** — session message, player, join, leave and death totals.
- **Storage** — the segment count, the compressed size and the oldest/newest timestamps.

### Security

- The server is **off unless the module is on**, and binds to loopback only by default. Turning on
  **allow-remote** binds every interface and is a deliberate choice: anyone who can reach the port
  and knows the token can control the client.
- Every request except the app shell and the health probe needs the token, as an
  `Authorization: Bearer <token>` header or a `?token=` query parameter (the query form is what a
  browser and an `EventSource` can send).
- The token is generated on first start and stored in the config. **Generate a new token** (module
  widget, Companion screen or `.log companion regenerate`) replaces it and restarts the server.
- Requests are rate limited per client, and every action runs on the client thread, so the app can
  only do what a user could do by hand — it never edits the inventory, never fabricates state and
  never blocks the game.

### `.log companion`

| Command | Effect |
| --- | --- |
| `.log companion [status]` | Whether the server is listening, the port, the client count and the address. |
| `.log companion start` | Turns the module on and starts the server. |
| `.log companion stop` | Turns the module off and closes the port. |
| `.log companion token` | Prints the access token (treat it like a password). |
| `.log companion regenerate` | Replaces the token and restarts the server. |
| `.log companion gui` | Opens the Companion screen. |

### HTTP API

All responses are JSON. Authenticate with the token unless noted.

| Method | Path | Purpose |
| --- | --- | --- |
| GET | `/` or `/index.html` | The bundled app (public). |
| GET | `/health` | Version and liveness (public, never includes the token). |
| GET | `/api/v1/snapshot` | The full client picture. |
| GET | `/api/v1/events` | The live event stream (SSE). |
| GET | `/api/v1/events?since=<id>` | Replays events after the given id, then streams. |
| GET | `/api/v1/bot` | The bot state on its own. |
| POST | `/api/v1/bot/start` | Starts the bot. |
| POST | `/api/v1/bot/pause` | Pauses the bot. |
| POST | `/api/v1/bot/resume` | Resumes the bot. |
| POST | `/api/v1/bot/stop` | Stops the bot. |
| POST | `/api/v1/bot/emergency-stop` | Cancels the active task, the queue and navigation. |
| GET | `/api/v1/tasks` | The queued tasks. |
| POST | `/api/v1/tasks` | Queues an objective: `{"objective":"gather 256 oak_log"}`. |
| DELETE | `/api/v1/tasks/{id}` | Cancels one queued task. |
| GET | `/api/v1/modules` | Every module with its state. |
| POST | `/api/v1/modules/{name}` | Toggles a module: `{"active":true}`. |
| GET | `/api/v1/chat` | The recent chat. |
| POST | `/api/v1/chat` | Sends a chat line or a `/command`: `{"message":"hello"}`. |
| GET | `/api/v1/players` | The observed players. |
| GET | `/api/v1/storage` | The segment count, compressed size and oldest/newest timestamps. |
| GET | `/api/v1/queries` | The names the query endpoint accepts. |
| GET | `/api/v1/query/{name}` | `messages`, `players` or `stats`, with `q`, `player` and `limit` filters. |
| POST | `/api/v1/command` | Runs a client command: `{"command":"log status"}`. |
| POST | `/api/v1/notify` | Shows a chat line and a notification: `{"message":"..."}`. |

The server answers with a plain message and the right status code when something is wrong — a
missing token is `401`, a bad body is `400`, a client thread that never answers is `504` — and it
never puts an internal exception in the response.

## Log storage locations

Everything the addon writes lives under `<gamedir>/logclient/`:

```
<gamedir>/logclient/config.json      module, HUD and addon settings
<gamedir>/logclient/logs/            gzip-compressed chat log segments and index.json
<gamedir>/logclient/logs/sessions.json   past recording-session summaries
<gamedir>/logclient/identities.json  observed account names and renames
<gamedir>/logclient/assistant-memory.json   assistant facts, player facts and conversation history
<gamedir>/logclient/reports/         gzip-compressed webhook report archive and index.json
```

`sessions.json` and `identities.json` are written atomically (temp file + move) — the session
history when a session ends, the identity index when a rename is observed and on shutdown. Each is
read back on the next launch, and a missing or damaged file loads as empty rather than failing.

## Exporting logs

Export a chosen slice of the archive to a file. Open **Log Archive** and press **Export...**, or use
the module's own *Export session* / *Export all* controls.

The export screen lets you pick:

- **Scope**: the current session, the entire archive, today, or the last seven days.
- **Format**: text, JSON or CSV (the same three the module's `export-format` setting offers).
- **Filters**: a player name, a keyword, and a set of message types.

The match count is computed on the storage worker from exactly the query that will be written, so the
preview never disagrees with the file. The export itself also runs on the worker, so a large archive
does not stall the game. Files are written through a temp file and moved into place, so an
interrupted export never replaces a good file with a partial one.

## Importing logs

An existing export can be read back into the archive, so a log moved from another machine (or a
segment rescued from a backup) is searchable again.

- Open **Log Archive** from the chat-logger widget or the dashboard and press **Import log...**, or
  run `.log importlog <path>`.
- Supported inputs: the plain text export (`.txt`), CSV, JSON lines, and the addon's own compressed
  segment (`.log.gz`). A native segment is CRC-checked, so a damaged file is refused rather than
  half-read.
- The file is parsed on the storage worker, never on the client thread, so a large file does not
  stall the game.
- Nothing is written until you press **Import into archive**. Before that you get a preview: the
  detected format, the number of entries, the first and last entry (so the range and order are
  obvious), and a list of the lines that could not be parsed and why. Unreadable lines are counted
  and shown, never silently dropped.

## GUI

Press **Right Shift** in-game (or run `.log gui`) to open the addon window. It is a card-based,
blue-themed interface with three tabs:

- **Modules** — every module grouped by category, each row a card with an on/off switch and a
  **Settings** button. The header shows how many modules are currently on, and each category heading
  shows how many of its modules are on.
- **HUD** — every HUD element with an on/off switch and a **Settings** button, plus **Arrange HUD**
  (the editor), **Show all** and **Hide all**.
- **Theme** — the colour scheme, the HUD text scale and the command prefix, plus an **About** block
  with the version, counts, the config path and links.

**Search** filters the module or HUD list by name, title or description, and shows a clear empty
state when nothing matches. Long pages scroll with the mouse wheel; the list is clipped to the
screen so nothing runs off the bottom.

**Settings** opens a page with the module's own controls first, then every setting grouped exactly
as the module declares it. A second search box filters the settings on that page.

### HUD editor

**Arrange HUD** (HUD tab) shows every active element at its real position on a live canvas and
lets you drag it with the mouse, with arrow buttons for precise nudges, one-click anchor buttons
(any of the nine screen anchors), align/centre buttons, and a **Hide** button per element. Elements
are drawn exactly as they appear in game, so the editor is a real preview rather than a set of empty
boxes. Positions are saved to the config, so an arrangement survives a restart. The HUD tab also has
**Show all** and **Hide all** to enable or disable every element at once.

### Themes

The **Theme** tab switches between built-in colour schemes — Blue (the default), Midnight, Azure,
Light, Violet, Teal and High Contrast. Applying one restyles every open screen on its next frame
(the shared theme instance is updated in place), and the choice is saved in `config.json`, so the
GUI opens in your scheme on the next launch. A scheme named by an older build (`DARK`, `ROSE`,
`FOREST`) is migrated onto its nearest replacement rather than silently reset.

The chat-intelligence modules open their own screens: **Session Timeline** (searchable, paged
milestone list with per-kind counts), **Chat Digest** (per-type totals and the most active players),
**Player Notes** (add, filter and remove notes, with each player's observed activity shown
beside the note, or an explicit "not observed" when the client has none) and **Log Import** (pick a
file, preview the parsed result, then merge it). A **Webhook Status** HUD element shows delivery
health, queue depth and, optionally, the last failure reason; an **Activity Graph** HUD element
draws recent chat activity as a bar graph; and a **Recent Players** HUD element lists the players
seen most recently with a time-ago and an online dot.

The dashboard also has an **Alerts** tab: counts by kind (keyword, webhook, error) and the most
recent entries, with a button through to the full searchable **Alert History**.

Every value a module can be configured with is editable here: booleans are checkboxes, numbers are
sliders, enums cycle on click, strings and string lists are text boxes, and colours have an R/G/B
slider each. A value that a module declares as dependent is hidden until its parent is on.

## Configuration

All settings live in one file:

```
<gamedir>/logclient/config.json
```

It holds each module's active state and settings, each HUD element's active state, position and
settings, and the addon-wide prefix. It is written when a value changes, on shutdown, and whenever
the client stops.

- Writes are **atomic** (temp file + move) and serialised on one lock, so a crash mid-save cannot
  leave a half-written config.
- Reads are **tolerant**: a missing, truncated or type-mismatched value is skipped, so a stale or
  damaged file never resets the settings that are still valid and never stops the addon loading.
- Keys this build does not understand are **preserved** on save, so a config written by a newer
  build is not silently truncated.

## Commands

The addon registers a `.log` command (alias `.la`). Run `.log help` for the list.

| Command | Effect |
| --- | --- |
| `.log gui` | Opens the addon window (same as Right Shift). |
| `.log status` | Chat logging state, automation state and notification counters. |
| `.log modules` | Every Log Client module with its on/off state and description. |
| `.log stats` | Session statistics: messages, players, joins/leaves, deaths, keyword alerts. |
| `.log record <on\|off>` | Starts or stops chat recording (enabling the module if needed). |
| `.log notifications [count]` | The most recent notifications. |
| `.log notifications clear` | Empties the notification history. |
| `.log tasks` | The automation queue and its history counters. |
| `.log tasks <start\|pause\|resume\|stop\|clear>` | Controls the automation. |
| `.log tasks add <type> <item> [amount]` | Queues a task, e.g. `.log tasks add mining diamond_ore 64`. |
| `.log waypoints [list]` | Lists saved waypoints. |
| `.log waypoints add <name>` | Saves your current position as a waypoint. |
| `.log import <file>` | Imports a material list (or a schematic) from a path, without the file dialog. |
| `.log importlog <file>` | Imports a chat log (.txt, .csv, .json or .log.gz) into the archive. |
| `.log alerts [count]` | The most recent keyword, webhook and error alerts, with per-kind totals. |
| `.log alerts clear` | Empties the alert history. |
| `.log playtime [player]` | Observed player session durations (a dash when unknown). |
| `.log schematic [status]` | The current build state and progress. |
| `.log schematic load <path>` | Loads a schematic from a path (no file dialog). |
| `.log schematic <start\|pause\|resume\|stop>` | Controls a build. |
| `.log acquire [start\|pause\|resume\|stop\|status\|retry]` | Controls automatic Creative material acquisition. |
| `.log acquire import <path>` | Imports a material list into the acquisition queue. |
| `.log schematic acquire` | Starts acquisition from the current schematic/material list. |
| `.log schematic import <path>` | Imports a material list into the Schematic Dropper queue. |
| `.log digest` | Prints a summary of recent chat activity. |
| `.log timeline [filter]` | Recent session milestones, optionally filtered. |
| `.log filter [stats]` | Chat filter rule count, default action and how many messages were hidden. |
| `.log filter reset` | Resets the filter counters. |
| `.log notes [list]` | Lists saved player notes. |
| `.log notes add <player> <text>` | Saves a note about a player. |
| `.log notes remove <player>` | Removes a player's note. |
| `.log notes search <filter>` | Searches notes by player or body. |
| `.log identities [filter]` | Lists observed accounts and their names. |
| `.log identities renamed` | Lists only the accounts seen under more than one name. |
| `.log identities watch <player>` | Adds a player to the rename watchlist. |
| `.log identities unwatch <player>` | Removes a player from the watchlist. |
| `.log identities watchlist` | Lists the watched players. |
| `.log identities clear` | Clears the identity index. |
| `.log sessions [list]` | Totals and the most recent past recording sessions. |
| `.log sessions clear` | Clears the session history. |
| `.log companion [status]` | The companion web server: listening state, port, client count and address. |
| `.log companion <start\|stop>` | Starts or stops the companion server. |
| `.log companion token` | Prints the companion access token. |
| `.log companion regenerate` | Replaces the token and restarts the server. |
| `.log companion gui` | Opens the Companion screen. |

### Assistant (`.ai`, alias `.assistant`)

| Command | Effect |
| --- | --- |
| `.ai [status]` | Assistant state and memory summary. |
| `.ai gui` | Opens the Assistant Manager. |
| `.ai triggers [list\|add <pattern> <reply>\|remove <n>\|restore]` | Manages keyword triggers. |
| `.ai facts` | Lists stored facts. |
| `.ai remember <key> <value>` | Stores a fact. |
| `.ai forget <key>` | Removes a fact. |
| `.ai test <message>` | Shows what the assistant would do with a message. |
| `.ai history` | Recent remembered exchanges. |
| `.ai save` / `.ai clear` | Save memory now / clear all memory. |

Commands are registered through the addon's own `PostInit` hook, and are intercepted from the
client's chat/command path by a mixin before vanilla handles them.

## Testing

`src/test` contains JUnit tests that drive the real engine, tracker, parser, rotation maths and
archive with in-memory doubles (no Minecraft runtime required):

- `DropEngineTest` — full runs, multiple materials, multiple/partial/full stacks, pending
  confirmations, timeouts, rejections, pausing, stopping, resets, missing materials, rotation
  before a drop, rotation restoration, invalid targets, best-effort recovery.
- `ProgressTrackerTest` — confirmed-only progress, clamping, no duplicate progress, 100 %
  completion, safe zero-requirement handling.
- `RotationMathTest` — yaw/pitch conventions, wrapping, shortest-path stepping, interpolation.
- `MaterialListParserTest` — material list parsing and validation.
- `MaterialListImporterTest` — both import formats, section filtering, duplicate aggregation,
  registry validation, and the full 367-entry acceptance fixtures.
- `SchematicFileImportTest` — whole-file reading from disk, 5,000-entry files with no truncation,
  CRLF handling, unreadable-file errors, ordering and the import summary line.
- `AcquisitionEngineTest` — the Creative acquisition state machine driven through fakes: partial
  quantities, stack limits, full inventories, drains, retries, blocked materials and snapshots.
- `WorldComparisonTest`, `PlacementEngineTest`, `DependencySolverTest`, `RecoveryManagerTest`,
  `MaterialManagementTest`, `SchematicReaderTest`, `NewFakes` — the schematic build pipeline:
  world diffing, the placement state machine (including server rejection and retry), dependency
  ordering, checkpoint re-validation on resume, material accounting and the Sponge/MCEdit/Litematic
  readers, all driven by in-memory doubles.
- `SchematicFileLoaderTest` — disk-level loading of gzipped Sponge v2 and v3 `.schem` files,
  including the WorldEdit `Schematic` envelope that real 7.3+ files use, block states with
  properties and multi-layer geometry.
- `ConfigManagerTest` — save/load round-trip, tolerant reads of a missing or corrupt file, unknown
  key preservation, the dirty flag, and the rebrand migration of a stored `logaddon` log folder
  (repointed) versus a user-chosen folder (left alone).
- `FileCheckpointStoreTest` — checkpoint round-trip, newest-first listing, corrupt-file recovery,
  deletion and path-name sanitisation.
- `LegacyBlockIdTableTest` — legacy id/metadata resolution and honest refusal of unknown ids.
- `ChatFilterTest` — the ordered rule set: first-match-wins, default action, player and message-type
  matching, whole-word and case-sensitive modes, disabled rules, and the encode/decode round-trip
  including forward compatibility with unknown keys.
- `DigestAndTimelineTest` — digest counts, top-player ordering, pluralised lines and empty-state
  handling; the bounded session timeline, newest-first reads, search and event formatting.
- `NotesAndWebhookLogTest` — player-note store add/update/remove/search and its bound; the bounded
  webhook delivery log, runtime capacity changes, and `hostOf` never returning a URL's secret path.
- `LogToolsTest` — the per-minute activity histogram (bucketing, gap materialisation, capacity
  bounding, peak/total), session-summary formatting, player-tracker exclusions (case-insensitive,
  applied only to new entries) and `forget`, and the log importer round-tripping every export format
  (native `.log.gz`, JSON, CSV, text) plus unreadable-line reporting and CSV quoting.
- `SessionsAndAlertsTest` — player session durations from observed join/leave pairs (measured time,
  unknown-vs-zero, an in-progress stretch, a repeated join without a leave, exclusions, forget,
  search ordering and the player cap); duplicate grouping (consecutive-only, sender-sensitive,
  suffixes); and the bounded alert log (newest-first reads, eviction, per-kind counts, filtering
  and clearing).
- `ChatRelayCoreTest` — buffer concurrency, gzip round-trip, corruption detection, archive
  persistence, index rebuild, search, cleanup, deletion and payload safety.
- `SettingsTest` — clamping, immutability, dependency visibility, declaration order, JSON
  round-trip and rejection of an invalid stored value.
- `CreativeControllerTest` — the automation acquisition state machine driven through fakes: a single
  item, quantities larger than a stack, an existing partial quantity, an unacknowledged give (never
  reported as success), a rejected give failing after the retry budget, an unknown id, missing
  creative mode, a full inventory waiting rather than failing, progress and ordering.
- `AutomationControllersTest` — every automation controller: eat thresholds/nutrition/cooldown, tool
  scoring and swap-back, sprint gating, safe-walk edge rules, armour classification and urgency,
  the anti-AFK schedule and action set, hotbar replenish planning, and the auto-log rules and grace
  period.
- `AutoCreativeImportTest` — the whole import path: the supplied 367-entry list becomes 367 ordered
  acquisition requests, the table report excludes raw resources, and `parseItems` tolerates
  whitespace, missing amounts and malformed lines while preserving large quantities.
- `ScreenRebuildTest` — a screen rebuild keeps the window frame and replaces its content instead of
  dropping or duplicating it, and re-lays the new widgets out. This guards the regression where a
  window-based screen (the file browser, the import preview) rendered empty after its first click.
- `GuiThemePresetTest` — every preset builds a complete theme with no null colours, the blue default
  is the signature scheme, presets have distinct accents, applying a preset mutates the shared
  instance in place, names round-trip through save/load, and pre-blue names (`DARK`, `ROSE`,
  `FOREST`) migrate onto their nearest replacement.
- `ConfigManagerTest` — save/load round-trip, missing and corrupt files, unknown-key preservation
  and the dirty flag, all against a real temp file.
- `PlayerIdentityTrackerTest` — observed-name tracking: a first observation is not a rename, a
  repeated name does not duplicate, a rename keeps the old name, two accounts sharing a name are
  never merged, blank input is ignored, search covers every observed name, `renamed()` filters
  single-name accounts, the rename log is bounded and newest-first, and the watchlist is
  case-insensitive, bounded and de-duplicated.
- `SessionHistoryTest` — session records built from a session and its statistics, an open session
  using `now`, the bounded history and its cap, duplicate-id rejection, search across server/id/date,
  totals, immediate cap reduction, map round-trip, ordered load, and the JSON file round-trip with a
  corrupt file loading as empty.
- `TriggerRuleTest` / `TriggerCooldownTrackerTest` — trigger encode/decode round-trips (including
  unknown keys from a newer build), case-sensitive and whole-word matching, sender filters,
  first-match-wins, blank-input rejection, and the cooldown/session-limit accounting with reset and
  prune.
- `AssistantMemoryTest` — facts, per-player facts, bounded history ordering, counters, version
  migration (an older map upgrades, a newer one is refused) and the deep, independent `copy`.
- `AssistantBrainTest` — the decision path: trigger replies with template expansion, no-match
  handling, cooldown and session-limit suppression, the global rate limit, trigger-over-command
  precedence, every memory command, reply clamping and silent rules.
- `ResponseTemplateTest` — placeholder expansion for player/message/server/fact/counter/memory, and
  that an unknown or malformed placeholder is left verbatim instead of throwing.
- `FileAssistantStoreTest` — memory round-trip, a missing file loading as empty, corruption
  recovery, refusal of a newer-version file and no temporary file left behind.
- `CompanionRouterTest` — the HTTP surface driven through a fake API: authentication (header,
  query token, wrong and missing token), the public app shell and health probe, the SSE content
  type, every bot control, module toggles, task queueing and cancellation, queries with parameters,
  404/405/400 handling, a client thread that never answers becoming a 504, the config redacting its
  token and clamping an out-of-range port, and the rate limiter refusing a flood.
- `CompanionServerTest` — the real socket: a published event reaches a connected client, the stream
  replays events a client missed on reconnect, a POST body reaches the API, and the server binds a
  real port, serves the app and stops cleanly.
- `CompanionCoreTest` — the event bus (ordered delivery, a bounded ring, backlog replay, a throwing
  subscriber isolated and dropped, monotonic ids, publish never blocking on a subscriber), the rate
  limiter (burst then throttle, per-client buckets, pruning), the client-thread seam (inline,
  scheduler-backed and timeout) and that the bundled app is self-contained and calls the documented
  endpoints.

## Troubleshooting

- **A screen opens but is empty, or a button seems to do nothing.** This was a real bug: a
  window-based screen cleared its whole widget tree on rebuild, which dropped the window frame, so
  the file browser, the import preview and the log viewer rendered nothing after the first click.
  Fixed by re-attaching the frame on every rebuild; `ScreenRebuildTest` guards it.
- **The OS file dialog does not open.** Expected on some Linux desktops and on headless setups:
  `tinyfd` returns nothing there. Use the addon's own file browser (**Import Material List...** /
  **Load Schematic...**), or set the `material-file` / `schematic-file` setting to a full path and
  press the matching *Path* button. The typed-path route and the `.log import` /
  `.log schematic load` commands never touch the native dialog.
- **Loading or importing does nothing.** The work is driven from the module's client tick, so the
  module has to be enabled and you have to be in a world. The buttons enable the module for you when
  they can, and otherwise say why.
- **Creative acquisition reports "not in Creative mode" or "no creative permission".** The module
  refuses to fake items: it needs genuine Creative mode and a server that accepts creative inventory
  changes. On a server that rejects them, that is reported instead of a ghost item.
- **A schematic loads but has no blocks.** Water, lava, fire and similar blocks have no item form
  and are filtered out of the material list; they are counted and reported rather than silently
  dropped.
- **A modern `.schem` reports "The schematic has no block palette".** WorldEdit 7.3+ writes the
  payload nested under a `Schematic` key (the Sponge specification layout). Older builds of the
  addon read the root directly and rejected every such file. Fixed: the loader unwraps the
  `Schematic` envelope before detection, so v1/v2/v3 `.schem` files all load.
- **The HUD element is missing.** Open the addon window (Right Shift), go to the **HUD** tab and
  check the element is on, or press **Show all**. A hidden element costs nothing per frame.
- **The companion app will not connect.** The server only runs while the **Companion** module is
  on, and it binds to loopback by default. From another device, turn on **allow-remote** and use the
  machine's LAN address (`http://<host>:8765/?token=...`), and make sure the port is not blocked by a
  firewall. If the port is already in use the module says so and does not listen; change the port or
  free it. A `401` means the token is missing or wrong — copy the address again from the widget, the
  Companion screen or `.log companion token`.
- **A config written by a newer build.** Unknown keys are preserved on save, so downgrading does not
  truncate them, and a missing or invalid value falls back to its default rather than aborting the
  load. A theme name from an older build (`DARK`, `ROSE`, `FOREST`) migrates to its nearest
  replacement.
- **Upgrading from a build branded "Log Addon".** The data folder was renamed from
  `<gamedir>/logaddon` to `<gamedir>/logclient`. On first launch the addon moves the old folder
  into the new one, and a stored `storage-directory` of `logaddon/logs` is repointed to
  `logclient/logs`, so recorded logs, identities, bot memory and the config are kept. A folder the
  user chose themselves is never rewritten.

## Known limitations

- Import reads the file in chunks on the client tick, so a very large report takes a few ticks to
  load rather than blocking; the list is never truncated.
- Creative acquisition needs genuine Creative mode with server-side permission. On a server that
  refuses creative inventory changes the module reports that instead of faking items.
- Schematic building uses the addon's own `BlockUtils.place` (rotate, select the item, click a
  face, restore), so it is bound by normal survival-style placement rules: the player must be in range
  and a support face must exist. It does not teleport, fly or bypass reach limits.
- The legacy (`.schematic`) id table covers the ids that appear in practice; anything outside it is
  reported as unresolved rather than guessed, and the item form of a legacy block is not always
  recoverable.
- In-game behaviour (the acquisition/build screens, the file browser, the import screen, HUD and chat
  filter) has not been verified in a live Minecraft session from this environment; the core engines,
  the importer, the readers, the checkpoint store, the filter rules, the digest, the timeline, the
  notes store, the activity histogram, the automation controllers and the log importer are covered by
  unit tests that use fakes and the real 1.21.1 registries.
- `auto-reconnect` only reconnects to a server the client already has a saved entry for, and does
  nothing for a single-player world (which cannot be resumed through the connection screen). It uses
  the same path the multiplayer list uses, so the server sees an ordinary client.
- `server-monitor` reports only what the client can observe (duration, game mode, latency from the
  player list, free slots). It does not report server tick rate or a player count it cannot see.
- `armor-guard` only reads the four vanilla armour slots; it never moves or replaces armour.
- The chat filter cancels a message in `ChatHud.addMessage`. A server that sends the same message
  through a path this mixin does not cover (for example a custom chat mod that renders its own
  overlay) is outside its reach; the vanilla chat HUD is what it targets.
- `assistant` is a rule-and-memory bot, not a language model: it matches the keywords you configure
  and stores the facts you give it. It does not generate free-form answers, and it only sees chat
  the client receives — messages from before it was enabled, or from a channel the client never
  receives, are invisible to it. Memory commands work like any other chat: the sender must be able
  to post the prefixed message, and the server must relay it to the client. No server-side plugin is
  involved.
- The assistant HUD and Assistant Manager GUI have not been opened in a live Minecraft session from
  this environment; the trigger engine, memory, templates, decision path and the memory file are
  covered by unit tests.
- Log import understands the formats this addon writes (text, CSV, JSON lines and its own compressed
  segment). It is not a general chat-log parser: a foreign format is reported as unreadable rather
  than guessed at, and every line it could not parse is listed in the preview.
- Player online time is only known for players whose join *and* leave were both observed. A player
  already present when the client connected is reported as unknown, not as zero, and the addon never
  invents a duration for them.
- Player name changes are only reported when the server's player list announces the **same UUID**
  under a different name. If a server does not include the player list, or a rename happens while the
  player is absent from it, no change is detected — the addon never guesses that two names are the
  same person.
- The identity index and session history are persisted under `<gamedir>/logclient/` (`identities.json`
  and `logs/sessions.json`) and restored on the next launch. A file written by an older build that
  lacks the new keys still loads: unknown or missing fields are skipped rather than rejecting the
  record.
- The bot uses Baritone only when the user has installed it (detected at runtime by reflection). The
  addon bundles no pathfinder of its own beyond the bounded fallback walker, and navigation quality
  with the fallback is limited — it walks in a straight line and stops when blocked rather than
  solving complex terrain.
- The companion is a **local** control surface, not a remote-hosting service: it speaks plain HTTP,
  so over an untrusted network the token would travel in the clear. Use it on loopback or a trusted
  LAN; if you enable **allow-remote**, treat the token as a password and rotate it if it leaks.
- The companion's own in-browser dashboard, the Companion screen and the companion HUD element have
  not been opened in a live Minecraft session from this environment. The HTTP surface, the auth, the
  event stream, the bot control mapping, the rate limiter and the bundled app resource are covered
  by unit tests that drive a real server on a real port with a fake API.

## License

MIT — see [LICENSE](LICENSE).
