# Logvex Addon

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
keyword hits), `webhook-monitor` (delivery health, queue depth and a bounded delivery history) and
`player-notes` (your own notes about players, shown next to their observed activity).

**Schematic Dropper** — imports a material report or a schematic, acquires missing materials in
Creative mode, drops them one *server-confirmed* transfer at a time, and can place a schematic
block by block into the world with verified placements and resumable checkpoints.

**Notifications** — `notifications` collects events from the other modules into one searchable
history with a HUD and a GUI, and can echo them to chat and play a sound.

**GUI** — Right Shift (or `.log gui`) opens the addon window: every module and HUD element with a
toggle, a search box and a settings page. Module settings are grouped and searchable, and every
value is persisted to `<gamedir>/logaddon/config.json`.

**Commands** — a `.log` (alias `.la`) command tree exposes status, the module list, session
statistics, notifications, the automation task queue, waypoints and schematic control from chat.

## Building

```bash
./gradlew clean test build
```

The jar is written to `build/libs/log-addon-5.0.0.jar`. Drop it into `.minecraft/mods`. No other
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

*Simplified Log Addon format* — `minecraft:<id> <quantity>`, one per line:

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

### Choosing files

**Import Material List...** and **Load Schematic...** open the addon's own file browser, built from
the same widgets as the rest of the GUI. It has no native dependency, so it works on every desktop
and on headless setups where the OS file dialog (`tinyfd`) returns nothing — which is what used to
make loading a file look broken. The browser starts in `<gamedir>/schematicdropper`, pages
a directory of any size, filters to the relevant extensions, and has **Up** and **Data Folder**
buttons. The typed-path buttons (**Import Material Path**, **Load Path**) and the `.log import` /
`.log schematic load` commands remain for scripted use.

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

- **Load Schematic...** reads `.schem` (Sponge v2/v3), `.schematic` (MCEdit) and `.litematic`
  files. Loading happens on a background worker and the result is applied on the next tick, so a
  large file never blocks the game thread. Both the legacy top-level `Palette`/`BlockData` layout
  and the Sponge v3 `Blocks` container (WorldEdit 7.3+ default) are read. Litematic regions are
  unpacked with the same tightly packed bit array Litematica writes, so an entry that straddles a
  `long` boundary (palette widths of 5, 6, 7 … bits) is read correctly instead of coming back as
  air.
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

Press **Right Shift** in-game (or run `.log gui`) to open the addon window. It lists every module
grouped by category with an on/off toggle and a **Settings** button, then the HUD elements, and
ends with a **Close** button.

- **Search** filters the module list by name, title or description.
- **Settings** opens a page with the module's own controls first, then every setting grouped exactly
  as the module declares it. A second search box filters the settings on that page.
- **HUD** rows toggle an element and open its own settings page.
- Long pages scroll with the mouse wheel; the list is clipped to the screen so nothing runs off the
  bottom.

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
<gamedir>/logaddon/config.json
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
| `.log modules` | Every Log Addon module with its on/off state and description. |
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
| `.log digest` | Prints a summary of recent chat activity. |
| `.log timeline [filter]` | Recent session milestones, optionally filtered. |
| `.log filter [stats]` | Chat filter rule count, default action and how many messages were hidden. |
| `.log filter reset` | Resets the filter counters. |
| `.log notes [list]` | Lists saved player notes. |
| `.log notes add <player> <text>` | Saves a note about a player. |
| `.log notes remove <player>` | Removes a player's note. |
| `.log notes search <filter>` | Searches notes by player or body. |

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
- `ConfigManagerTest` — save/load round-trip, missing and corrupt files, unknown-key preservation
  and the dirty flag, all against a real temp file.

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
  notes store, the activity histogram and the log importer are covered by unit tests that use fakes
  and the real 1.21.1 registries.
- The chat filter cancels a message in `ChatHud.addMessage`. A server that sends the same message
  through a path this mixin does not cover (for example a custom chat mod that renders its own
  overlay) is outside its reach; the vanilla chat HUD is what it targets.
- Log import understands the formats this addon writes (text, CSV, JSON lines and its own compressed
  segment). It is not a general chat-log parser: a foreign format is reported as unreadable rather
  than guessed at, and every line it could not parse is listed in the preview.
- Player online time is only known for players whose join *and* leave were both observed. A player
  already present when the client connected is reported as unknown, not as zero, and the addon never
  invents a duration for them. Name changes are not tracked: the client is not reliably told a
  renamed player is the same person, so a rename appears as a new name.
- The bot uses Baritone only when the user has installed it (detected at runtime by reflection). The
  addon bundles no pathfinder of its own beyond the bounded fallback walker, and navigation quality
  with the fallback is limited — it walks in a straight line and stops when blocked rather than
  solving complex terrain.

## License

MIT — see [LICENSE](LICENSE).
