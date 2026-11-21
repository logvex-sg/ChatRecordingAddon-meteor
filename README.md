# Log Client

A Meteor Client addon for Minecraft 1.21.1.

It runs as an addon on Meteor Client: the addon registers its modules, HUD and commands through
Meteor's own registries and event bus, so its features appear in Meteor's GUI and keybind system
alongside the built-in ones. Baritone is used for navigation only when it happens to be installed,
and is detected at runtime rather than compiled against.

**Chat toolkit** — `chat-logger` (records chat to compressed, searchable logs),
`chat-monitor` (live feed with filters and highlighting), `keyword-alerts` (keyword alerts with
sound and optional webhook), `player-activity` (per-player activity tracking) and
`session-stats` (session statistics), with matching HUD elements.

**Chat intelligence** — `chat-filter` (ordered hide/allow rules applied on the incoming message
event, so a filtered message never reaches chat, the recorder or the log), `chat-digest` (on-demand and timed
summaries of chat activity), `session-timeline` (a bounded timeline of joins, leaves, deaths and
keyword hits), `webhook-monitor` (delivery health, queue depth and a bounded delivery history),
`webhook-relay` (one shared webhook and a consistent Discord **embed** look, with cards for keyword
hits, reports, digests, session ends, renames, deaths, announcements, mentions, leaderboards and
player profiles),
`player-notes` (your own notes about players, shown next to their observed activity),
`player-identity` (observed account name changes and a watchlist), `mention-alerts` (alerts when
your name or a watched name is used in chat), `chat-timestamps` (a clock time in front of every
chat line) and `repeat-collapse` (repeated identical lines folded into one), plus `session-history`
(a browsable history of past recording sessions).

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

**GUI** — `!log gui` opens the addon window: a Meteor-style interface with a
navigation rail on the left (Modules, HUD, Theme, About) and a card list on the right. Every module
and HUD element has an on/off switch and a Settings page, the list is searchable, and an enabled
entry is marked with an accent bar. Rounded panels, eased hover and toggle animations and a soft
glow on active controls are shared by every screen through one theme, and there are Meteor-style
presets (Neon, Matrix, Amber, Crimson, Cyan) with a live swatch preview on the Appearance page.
Module settings are grouped and searchable, and every value is persisted to
`<gamedir>/logclient/config.json`.

**Commands** — a `!log` (alias `!la`) command tree exposes status, the module list, session
statistics, notifications, the automation task queue, waypoints and schematic control from chat.

Log Client commands use the `!` prefix by default; `!log gui`, `!ai status` and `!bot start` all
work as typed. The prefix is configurable on the **Theme** page and is honoured for Log Client's own
commands only, so a line is intercepted only when its first word is one of them. Plain chat, other
mods' commands and server commands such as `/spawn` are never touched. Meteor's own commands keep
using Meteor's prefix (a dot unless you change it in Meteor), so the two schemes coexist.

## Building

```bash
./gradlew clean test build
```

The jar is written to `build/libs/log-client-<version>.jar`, where the version comes from
`mod_version` in `gradle.properties`. Drop it into `.minecraft/mods` alongside Meteor Client.
Baritone is optional and only affects bot navigation.

The version is read at runtime from the jar's own `fabric.mod.json`, so there is nothing else to
update when bumping `mod_version` — the GUI rail, the updater and the companion API all follow it.

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

The widget groups its controls under four headings so the whole workflow reads top to bottom, and
the primary action of each group is drawn in the accent colour.

| Control | Effect |
| --- | --- |
| **Material list** | |
| Import file... | Opens the built-in file browser and imports a whole material report (or a schematic). |
| Import from path... | Imports the file named by **material-file**, for scripted or command-line use. |
| Preview import | Reopens the import preview of the last file. |
| Material overview | Opens the material overview screen. |
| **Dropping** | |
| Start | Begins a run from the current confirmed progress. |
| Pause / Resume | Suspends and continues a run. A run with an in-flight confirmation finishes that confirmation before pausing. |
| Stop | Cancels the pending operation and returns to idle. |
| Reset progress | Clears confirmed counters without touching the plan or the inventory. |
| Rescan inventory | Forces a fresh inventory scan. |
| **Creative acquisition** | |
| Acquire materials | Starts automatic Creative acquisition from the imported list. |
| Acquisition window... | Opens the acquisition screen (progress, per-material status, controls). |
| **Schematic building** | |
| Load schematic... | Opens the built-in file browser and loads a `.schem` / `.schematic` / `.litematic` build. |
| Load from path... | Loads the file named by **schematic-file**, for scripted or command-line use. |
| Start build | Places the loaded schematic block by block, verifying each placement. |
| Pause build / Resume build / Stop build | Controls a schematic build. |
| Builder window... | Opens the build screen (progress, problems, history, checkpoints). |

### Importing a material list

**Import file...** opens the built-in file browser and parses the
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
module's **Preview import** button). It shows the material count, total item count, valid entries,
duplicates merged and invalid entries, then the **first and last entries** so the range can be
confirmed, and the complete ordered list with a filter box and paging for long lists. Nothing is
acquired or dropped until **Start Acquisition** or **Start Drop Run** is pressed, so a bad import
costs a click rather than a wrong build.

### Choosing files

**Import file...** and **Load schematic...** open the addon's own file browser, built from
the same widgets as the rest of the GUI. It has no native dependency, so it works on every desktop
and on headless setups where the OS file dialog (`tinyfd`) returns nothing — which is what used to
make loading a file look broken. The browser starts in `<gamedir>/schematicdropper`, pages
a directory of any size, filters to the relevant extensions (including `.nbt` and `.dat`, which
schematic exports often use), and has **Up** and **Data Folder** buttons. The typed-path buttons
(**Import Material Path**, **Load Path**) and the `!log import` / `!log schematic load` commands
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
- Pressing **Start build** turns **build-enabled** on for you when it is off, the same way the load
  and import buttons enable the module. Refusing to build until a separate switch was flipped looked
  like "I can't build schematics" even after the file had loaded.
- **checkpoints** (on by default) controls whether progress is written after each confirmed block.
  Turning it off keeps the run working but leaves nothing to resume from after a restart;
  **Save checkpoint** on the builder screen always writes one on demand.

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
the `!bot profile` command) lists the built-in profiles, applies one, captures the current settings
under a new name, and deletes user profiles. Built-in profiles are marked and cannot be overwritten
or deleted, so a known-good starting point is always available. Applying a profile changes only the
settings the profile actually stores; everything else is left as it is, so a profile saved before a
new setting existed still loads cleanly. Profiles are written to
`<gamedir>/logclient/profiles.json` with an atomic write; a corrupt file loads as empty rather than
resetting your configuration.

### Emergency stop

The dashboard's **Emergency Stop** button (or `!bot emergency`) is the immediate, no-questions stop:
it cancels the active task, clears the queue, cancels goals and scheduled tasks, stops navigation and
cancels any in-flight block interaction, then returns to idle. It is deliberately more forceful than
**Stop**, which keeps the queue so work can be resumed.

### `!bot` command

The `!bot` command (alias `!b`) is the short front end for the bot, built on the same methods the
dashboard and HUD use, so a command never diverges from the GUI:

| Command | Effect |
| --- | --- |
| `!bot [status]` | Automation state, queue length, navigation backend and current destination. |
| `!bot start\|pause\|resume\|stop` | Controls the automation, validating the current state first. |
| `!bot emergency` | The immediate stop described above. |
| `!bot goto <x y z>` or `!bot goto <waypoint>` | Queues a navigation task to a coordinate or a saved waypoint. |
| `!bot tasks [clear]` | Lists the queue, or empties it. |
| `!bot waypoint add\|del\|list <name>` | Manages named waypoints. |
| `!bot profile [list\|apply\|save\|delete] <name>` | Manages bot profiles. |


## Assistant

`assistant` is a chat bot that lives inside the addon. It is off by default; enable it like any
other module. Once enabled it answers from the preconfigured trigger table below, so it works
without any setup; clearing the table makes it silent again.

### What the assistant can hear

Triggers only ever see chat that was classified as a player talking. Minecraft
attaches a signed-chat indicator to properly signed player chat, and an earlier version used that
indicator as the only proof that a player had spoken. That broke on every server that rewrites
chat: an offline-mode server, a proxy such as Velocity, or any chat plugin sends the same line
without the indicator. Those lines were classified as system messages, and with system watching
off — the default — every player message was silently dropped. The assistant then worked on some
servers and not others, which is exactly what "it only works half the time" looked like.

The chat classifier now recovers the sender from the text as well: `<Steve> hello`, `Steve: hello` and
`[MVP+] Steve: hello` all classify as public chat from `Steve`. The recovery is narrow on purpose.
A name must be 3 to 16 characters and contain a letter, so a clock reading like `12:34` stays a
system line, and a known tag such as `Server:`, `Note:` or `Announcement:` is never mistaken for a
player. Join, leave, death and advancement lines keep their own types and are unaffected.

### Keyword triggers

A trigger watches incoming chat for a keyword or phrase and answers it. Triggers are stored as a
single line each, so the whole feature set is editable from the settings list, the
`!ai triggers` command or the Assistant Manager GUI:

```
pattern;sender=;reply=;mode=private;cooldown=3000;limit=0;sound=false;remember=false;enabled=true;aliases=;pool=;whisper=msg;embed=false
```

A fresh install ships **102 preconfigured rules** with 76 extra aliases, all enabled, all at the
3000 ms cooldown, all matched on whole words, and all answered **privately to whoever asked**.
That is 178 phrases answering out of the box — no setup step. They cover greetings, thanks,
farewells, `gg`/`wp`, small talk, the server-figure questions (`what is the tps`, `what is my ping`,
`is the server lagging`, `how many players`), the player-figure questions (`where am i`,
`what is my health`, `what is my armour`, `what is my gamemode`) and the command-shaped set
(`!tps`, `!status`, `!memory`, `!world`, `!time`, `!ping`, `!fps`, `!ram`, `!session`,
`!players`, `!hunger`, `!vitals`, `!facing`, `!offhand`, `!environment`, `!help`).
`!ai triggers restore` puts the same table back.

Every shipped phrase is **reachable**: no rule's phrase can be answered by an earlier rule, so
first-match-wins never has to break a tie and the reply never depends on list order. Two tests
enforce this (`earlierRulesDoNotSwallowLaterOnes`, `everyShippedRuleMatchesItsOwnPattern`), which
is why near-duplicate phrasings are stored as aliases of one rule rather than as two rules.

The cooldown is **per player**. A rule paces each speaker separately, so a busy chat cannot use up
the rule for everyone else: when three players ask `!tps` at once, all three get an answer. The
global rate limit still caps the total.

A reply may name a **private figure** — coordinates, gear, health, the assistant's own memory — but
only when the asker is the local player. A stranger who types a trigger whose reply would expose
one of those gets a refusal instead of the answer, so a trigger can never be used to read the
operator's position or stats. Coarse world facts (`{biome}`, `{dimension}`) stay public, matching
`!world`.

A trigger only ever fires on chat. A line that starts with the command prefix is a command, so a
bare `hello` rule answers chat but never swallows `!hello`.

| Field | Meaning |
| --- | --- |
| `pattern` | The keyword or phrase to look for. |
| `case` | Match case-sensitively (`false` by default). |
| `whole` | Only match whole words, so `cat` does not fire inside `concatenate`. |
| `sender` | Only fire for this player. Blank matches anyone. |
| `reply` | What to send. One literal line: supports the full placeholder set below, e.g. `{player}`, `{tps}`, `{ping}`, `{biome}`, `{fact:key}`. |
| `mode` | `private` whispers the reply to whoever asked — **the default**. `chat` says it publicly, `notify` raises a local notification only, `silent` records the match without speaking. |
| `whisper` | The private-message verb for `mode=private`: `msg` (default), `tell`, `w` or `whisper`. A wrong verb is silently rejected by the server, so set this to whatever your server uses. |
| `cooldown` | Minimum milliseconds between two fires of the same rule. |
| `limit` | Maximum fires per session; `0` is unlimited. |
| `sound` | Play a chime when the rule fires. |
| `remember` | Write the exchange to memory. |
| `enabled` | Turn the rule off without deleting it. |
| `aliases` | Extra phrases that fire the same rule, comma separated, e.g. `latency,ms`. |
| `pool` | No longer used. Kept so a rule written by an older build still loads. |
| `embed` | `auto` (default) renders a block for a command-shaped rule whose reply is all labelled clauses; `on` always renders one; `off` always sends one line. See below. |

#### Block replies (`embed=auto`)

A reply with several figures reads better as a block than as one long sentence, so the assistant
formats one automatically. A command-shaped rule (`!tps`) whose reply is comma-separated
`Label value` clauses becomes an aligned row per clause, provided every clause carries a label:

```
!tps;reply=TPS {tps}, MSPT {mspt} ms, Status {verdict}
```

```
┌─ !tps ───────────────────────────────┐
│ TPS    19.8                          │
│ MSPT   50.5 ms                       │
│ Status good                          │
└──────────────────────────────────────┘
```

A reply that is a single sentence or a single figure stays on one line, because a border around one
value is harder to read than the value itself. A phrase trigger such as `hello` is never boxed
automatically, however many commas its reply has: prose in a border is harder to read, not easier.
`embed=on` forces a block in either case, and `embed=off` keeps a multi-figure reply on one line.

Minecraft chat cannot carry a newline inside one message, so the block is sent as consecutive
lines (up to 12). A block too tall for that budget is not cut off mid-frame: it is re-rendered with
fewer rows and its closing rule carries `+N more`, so a truncated answer still looks like a
finished one. The one-line form is still built and used wherever only one line fits, so a block
never loses a figure. The module's **reply-format** setting (`auto`, `box`, `compact`, `plain`,
`list`) overrides the layout globally; `auto` keeps each reply's own.

Width is measured in columns, not characters, so a name in CJK or an emoji does not pull the right
frame out of line.

#### Meters and sections

A share of something reads faster as a bar than as two numbers, so a reply can draw one with a bar
placeholder:

| Placeholder | Value |
| --- | --- |
| `{memorybar}` | Heap use out of the maximum, as a ten-cell bar. |
| `{healthbar}` | Health out of the maximum. |
| `{hungerbar}` | Food level out of 20. |
| `{tpsbar}` | Tick rate out of 20. |

```
!memory;reply=Used {memoryused} MB, Max {memorymax} MB, Load {memorypercent}%, Bar {memorybar}
```

```
┌─ !memory ─────────────────────────────────┐
│ Used   512 MB                             │
│ Max    4096 MB                            │
│ Load   12%                                │
│ Bar    █░░░░░░░░░                         │
└───────────────────────────────────────────┘
```

A bar never lies about a figure it cannot read: an unmeasurable share draws an empty bar rather than
a full one, and the number stays beside it so the bar is a hint and not the only reading.

A long block can also be split into named groups. The built-in `!about` does this, and the renderer
draws a section as a rule across the block:

```
┌─ Bot ─────────────────────────────────────┐
│ ────────────── Memory ──────────────      │
│ Facts          12                         │
│ Players known  3                          │
│ ──────────── This session ──────────      │
│ Lines seen     84                         │
│ Answered       11                         │
│ Reply rate     13% ██░░░░░░░░░░           │
│ ────────────── Controls ────────────      │
│ Permissions    on                         │
└───────────────────────────────────────────┘
```

**A reply is one literal answer, and it always says the same thing.** Two earlier behaviours are gone
because they made output look broken:

- **`|` in a reply no longer means "pick one at random".** It never looked like a random-choice
  marker to a reader — it looks like a field separator, so `TPS {tps} | MSPT {mspt}` appeared to be
  one line of two fields and then printed *one of them at random*. Use a comma to join fields.
  A rule stored by an older build still renders its first alternative, so no stored rule prints a
  raw pipe.
- **No sentence is appended from `pool`.** A reply is exactly what you wrote.

The first rule that matches wins, so a specific rule can be placed above a broad one. A global
rate limit (default 20 replies per minute) caps how often the assistant can answer in total, and a
rule's cooldown and session limit cap it per rule — the assistant cannot flood chat even if a
keyword appears in every message.

The assistant never answers itself: a message from the local player, or one that matches one of
its own recent replies, is ignored.

### Placeholders

A reply can contain placeholders, which are replaced with live values just before the message is
sent. Names are case-insensitive and may be written with spaces, so `{tps}`, `{TPS}` and `{ Tps }`
are the same placeholder. Run `!ai placeholders` in game for an index of the sections, then
`!ai placeholders <section>` (for example `!ai placeholders server`) to list one section with its
current values. The reference is split because listing every placeholder at once pushed the chat
box off the screen.

| Placeholder | Value |
| --- | --- |
| `{player}` | The sender of the message that fired the rule. |
| `{message}` | The message text that fired the rule. |
| `{time}` / `{date}` | The local clock time (`HH:mm:ss`) and full timestamp. |
| `{server}` | The server's name, or `single-player`. |
| `{tps}` / `{mspt}` | The server's measured tick rate and the milliseconds per tick it implies. |
| `{ping}` | Round-trip latency in milliseconds. |
| `{players}` | How many players the server has sent the client. |
| `{uptime}` | How long the current session has been open. |
| `{verdict}` | `good`, `fair`, `poor`, `lagging` or `offline`. |
| `{dimension}` / `{world}` | The full dimension id and its short name. |
| `{biome}` | The biome at the player's feet. |
| `{worldtime}` | The in-game clock, e.g. `12:00 day`. |
| `{facing}` | The compass direction the player is looking, e.g. `north`. |
| `{health}` / `{hunger}` | Health as `current/max` and the food level. |
| `{x}` / `{y}` / `{z}` / `{coords}` | Position, one axis at a time or as `x, y, z`. |
| `{speed}` | Measured horizontal walking speed in blocks per second. |
| `{fps}` | The client's frame rate. |
| `{weather}` | The current weather, e.g. `clear` or `rain`. |
| `{name}` / `{prefix}` | The assistant's own name and command prefix, so a reply can describe itself. |
| `{memory}` / `{factcount}` | How many facts are stored. |
| `{fact:key}` / `{count:name}` | A stored fact, or a stored counter. |

Two rules keep this honest:

* **Only figures the client can see are reported.** TPS comes from Meteor's keep-alive measurement,
  ping from the player list, the biome from the loaded chunk. Nothing is queried from the server, so
  the same reply works in single-player and on any remote server. A figure the client does not have
  yet reads `n/a` rather than a made-up number.
* **Unknown placeholders are left exactly as written.** `{nonsense}` stays `{nonsense}`, so a typo
  shows up in the reply instead of silently blanking part of it.

New placeholders can be added in code without touching the parser: register a name on
`Placeholders` and every reply path picks it up. `Placeholders.standard().copy()` gives a private
registry for an experiment without changing the shared one.

### Memory commands

When `memory-commands` is on, chat messages that start with the command prefix (default `!`) are
understood:

| Command | Effect |
| --- | --- |
| `!help [page]` | List every command you may use, one page at a time. |
| `!hello` | A greeting that suits the time of day. |
| `!gg` | The configured good-game line. |
| `!status` | Assistant state and memory size. |
| `!activity` | How busy chat has been this session. |
| `!remember key = value` | Store a fact. |
| `!fact key` | Read a fact back. |
| `!forget key` | Remove a fact. |
| `!facts` | List stored facts. |
| `!memory` | A short memory summary. |
| `!whoami` | What the assistant knows about you. |
| `!forgetme` | Erase everything stored about you. |
| `!history` | Recent remembered exchanges. |

Each command has one or more aliases, so `!latency`, `!ms` and `!ping` are the same command, and
`!pos`, `!position` and `!coords` are the same command. An alias is not a second command: it resolves
to the canonical name before any permission check, so a rule on `!remember` also covers `!learn`.
The full table lives in `CommandCatalog`, which is also what `!help` and the permissions screen read,
so the three can never disagree.

How the assistant answers these is controlled by `memory-reply-mode` (`chat`, `notify` or
`silent`). The default, `notify`, keeps answers out of public chat.

#### A trigger may not shadow a restricted command

A trigger is matched **before** the command parser, so a rule named `!coords` would take the place
of the `coords` command. That matters because the two paths gate differently: a command that reads
your position, gear, effects or target is refused to anyone but you, while a trigger answers
whoever typed it unless its reply happens to name a private figure. A trigger in front of a gated
command would therefore hand a stranger what the command refuses them.

No shipped trigger reuses a restricted command's name, and a guard test enforces it. The restricted
verbs — `!coords`, `!armor`, `!held`, `!health`, `!speed`, `!gamemode`, `!level`, `!absorption`,
`!effects`, `!target`, `!location`, `!network` — are answered by the status commands, which are
already gated. If you add your own rule, keep the same rule: use a new word rather than reusing one
of those.

### Configuring every prefix command

Status commands have their own table. Every *other* prefix command — `!hello`, `!gg`, `!who`,
`!facts`, the memory commands — is configured in the **command-table** setting, one line per
command, in the same shape:

```
name = on|off | local|chat|silent | optional template
```

The status table uses the same shape, with one difference worth knowing: a status template is free
text and **may contain `|`** — the shipped `status` line joins three figures that way. Only the two
leading fields are read as fields, so a pipe inside the wording is kept. To rename a command, append
`|alias=newword`; a bare trailing word is still accepted from older configurations, but only when it
is a single word.

Both tables accept `{prefix}` and `{name}`, which expand to the configured command prefix and the
assistant's own name.

- `on`/`off` switches the command off entirely.
- `local` shows the answer in your own chat box, `chat` sends it to the server, `silent` answers
  nothing visible.
- The template is optional. Leave it blank and the command keeps its built-in wording; write one
  and it replaces that wording, expanded with the same placeholders the rest of the assistant uses
  (`{player}`, `{server}`, `{ping}`, `{tps}`, and so on).

```
hello = on | chat | o/
gg = on | chat | gf
who = on | local | {playercount} online right now
facts = off | local
```

Read-only commands default to `local`, because most people asking their own client a question do
not mean to broadcast it. `!hello` and `!gg` default to `chat`, since their whole purpose is to be
seen. A command you never mention stays at its shipped default, so a saved table from an older
version keeps working when new commands are added.

### Live status commands

The same prefix answers live questions about the server and about you, so `!tps` in chat gets an
answer without opening a menu. These run before any free-form handling, so a status question never costs a
model call.

Each command is one entry in a table you can edit: its own on/off switch, its own output mode and
its own template. Nothing is hardcoded, so a command can be reworded, renamed or switched off
without touching the code. The whole set has one master switch in the **Live Status** settings
group, and the **Status** tab previews every command as it would answer right now.

| Command | Answer |
| --- | --- |
| `!status` | The overview: server, tick rate, latency, player count and session length. |
| `!ping` | **The requester's own** latency and a verdict, e.g. `Ping: 42ms (good)`. |
| `!tps` | The tick rate out of 20 and the milliseconds per tick. |
| `!mspt` | Only the milliseconds per tick. |
| `!online` | Players on the server, with the maximum when the server sends one. |
| `!who` | The players the tab list shows, as a wrapped list. |
| `!uptime` | How long this session has been running. |
| `!server` | The server name, address and whether it is single-player. |
| `!coords` | Your position, rounded to whole blocks. |
| `!location` | Dimension, biome and the direction you face. |
| `!health` | Health, absorption and hunger. |
| `!vitals` | Health, hunger and absorption as a labelled block. |
| `!hunger` | Your food and saturation. |
| `!absorption` | The absorption hearts you are carrying. |
| `!level` | Your experience level. |
| `!facing` | The compass direction you face. |
| `!offhand` | What is in your off hand. |
| `!environment` | The biome you are in and the direction you face. |
| `!difficulty` | The world difficulty, as the client can see it. |
| `!armor` | Your armour pieces and their remaining durability. |
| `!held` | What is in each hand. |
| `!effects` | Active potion effects, with their remaining time. |
| `!target` | What you are looking at, or `nothing`. |
| `!gamemode` | The game mode the server set for you. |
| `!weather` | The weather the server reported. |
| `!day` | The world's day number and the in-game clock. |
| `!world` | The dimension and its name. |
| `!fps` | The client's frame rate. |
| `!memory` | The JVM's heap use. |
| `!speed` | How fast you are moving, in blocks per second. |
| `!about` | What the assistant itself is doing this session. |

#### Output modes

Every command ships **local**, which means the answer appears as a notification and is never sent
to the server. Set a command to `chat` to have it spoken once in public chat instead. The trade-off
is the one you would expect: chat answers are visible to everyone on the server, so a local answer
is the safe default.

Some commands describe **you** — your position, gear, effects, target and connection. Those are
always local, even if chat is selected, and they are refused for anyone but you. A stranger cannot
ask this client where you are standing.

#### Templates

Each command has a template, and you can rewrite it. A placeholder in braces is replaced with the
live value:

```
ping = on | local | Ping: {ping}ms ({verdict})
coords = on | local | I am at {x}, {y}, {z} in the {dimension}
```

A figure the client cannot read becomes `n/a` rather than a plausible-looking zero, so a number on
screen is always a measurement. An unknown placeholder is left as written, so a typo is visible
instead of silently vanishing. The **Status** tab lists every placeholder with an example value.

The per-command table also carries a rename: the fourth field changes the word the command answers
to, so `ping = on | local | Ping: {ping}ms | latency` makes `!latency` work and `!ping` stop. A
rename that would collide with another command's name is ignored rather than allowed to hijack it.

#### The webhook card

`!status` can also post a card to the **admin webhook** in the Webhook Relay module. This is a
separate URL from the public webhook on purpose: a status card carries your own figures, and the
public webhook may be readable by a whole Discord server. Leave the admin URL blank and nothing
leaves the client.

`!status` and the `server-stats` HUD read the same snapshot, so the two never disagree. The card is
built from the same list as the chat readout for the same reason.

`!who` reads names from the tab list, so a player who has never spoken is still listed. When a
server reports a player count but hides the names, `!who` says so instead of claiming nobody is
online.

### Chat command macros

A macro is a shortcut **you** type that becomes a server command. It is a different thing from a
trigger: a trigger answers incoming chat, a macro rewrites your own outgoing message. They are
separate types on purpose, so a macro can never be reached by another player's message and a
trigger can never send a command.

Macros are configured in the **Chat Command Macros** settings group or on the assistant screen's
**Macros** tab. One line each:

```text
!tpa <player> => /tpa {player}
!spawn => /spawn
!msg <player> <message> => /msg {player} {message}
```

The part before `=>` is the usage: the shortcut, then its arguments. The part after is the command
with placeholders in braces.

| Form | Meaning |
| --- | --- |
| `<name>` | A required argument. |
| `[name]` | An optional argument; a missing one is simply omitted. |
| `{name}` | The value of that argument. |
| `{message}` | Every remaining word, joined by single spaces. Must be last. |

A trailing `;` section carries extras: `; what it does`, `; off` to disable it, and
`; aliases=!hub,!l` to give it more spellings.

The shortcut is matched on the bare word, not the whole spelling, so changing the command prefix
from `!` to `#` keeps every macro you already wrote reachable. Type the new prefix and the same
shortcuts work; nothing has to be rewritten.

When you type a shortcut the line is **cancelled** and the expanded command is sent instead, so the
shortcut never reaches the server as chat. A macro that is misconfigured — no leading `/`, an
unknown placeholder, a missing required argument, or too many arguments — refuses to send and
explains itself instead. A macro whose command would be its own shortcut is blocked, so it cannot
loop.

| Command | Effect |
| --- | --- |
| `!macros` | List the enabled macros as a block, one row each. |
| `!macrohelp <name>` | One macro's syntax, the command it sends and its aliases. |

`macro-feedback` shows a local note when a macro runs or is refused. `echo-command` shows the
expanded command in chat as if you had typed it; turn it off to send it silently.

### Reports and moderator pings

`!report` and `!mod` post a card to the Discord webhook configured in the **Webhook Relay** module
and ping a role. This is the one place the addon pings anyone: captured chat never mentions a soul.

| Command | Effect |
| --- | --- |
| `!report <what happened>` | Post a report card and ping the moderator role. |
| `!mod <what you need>` | The same, titled as a moderator request. |

Set `moderator-role-id` to a Discord role id (bare digits or the full `<@&…>` form). Only roles are
allowed to parse, never `@everyone`, so a misconfigured report cannot mention the whole server.
With no role set the card is posted without a mention — deliberately, rather than sending an
`@here` that would render as dead text and look like a failed ping.

`allow-reports` switches the feature off entirely; nothing is posted and nothing is queued.

### Chat features and command whitelist

Every command has a permission level. The level is checked **before** the command runs, so a refused
`!remember` stores nothing, a refused `!forget` deletes nothing, and a refused command never reaches
any other source. Refusals are counted separately in `!ai status` so a user can see them.

Permissions are **off** by default, which leaves every command exactly as it was before this feature
existed. Turning them on is a deliberate choice, because it changes who can talk to the assistant.

| Level | Who may use the command |
| --- | --- |
| `everyone` | Anyone in chat. |
| `whitelist` | The players listed on that command's own whitelist. |
| `administrators` | The players in the shared administrator list. |
| `local-only` | Only the local player. Nothing arriving from chat can use it. |

The default level for each command is set in `CommandCatalog`. Anything that changes state
(`remember`, `forget`, `forgetme`) defaults to `local-only`; read-only figures such as `ping` and
`tps` default to `everyone`; `history` defaults to `whitelist`.

Two policies cover commands with no rule at all:

- `unlisted-commands = open` (default) — a command with no rule is open to everyone.
- `unlisted-commands = local-only` — a command with no rule is closed to chat. Choose this if you
  would rather a new command be silent than accidentally public.

#### How a sender is identified

A chat line carries a name and nothing else, and a name in chat is just text — anyone can type
another player's name. So the identity used for permission checks has two parts:

1. the **name** from the message, and
2. the **UUID** from the client's own player list, which the server populated.

The UUID is the part that cannot be forged in chat. A whitelist entry may be written as a bare name
(`Alice`) or pinned to an account (`Alice=069a79f4-44e9-4726-a5be-fca90e38aaf5`). A pinned entry
matches on the UUID, so it survives a rename, and a player who types someone else's name does not
match it. When the client cannot resolve a name to an account, the identity stays unverified, and a
pinned entry refuses it — which is the honest answer, because in that case the client genuinely
cannot prove who spoke.

`require-verified-names` makes bare-name entries refuse unverified senders too. That is stricter and
is the right choice on a server where you trust the player list but not the chat text; it will
refuse players on an offline-mode server, who have no real account UUID.

The local player is always identified as themselves and always passes `local-only`.

#### Editing the whitelist

- `!ai perms` lists every command with its level and whitelist.
- `!ai perms gui` opens the permissions screen: one row per command, a button that cycles the level,
  and a per-command whitelist editor.
- `!ai perms set <command> <level>` sets one level.
- `!ai perms allow <command> <user>` / `!ai perms deny <command> <user>` edit one whitelist.
- `!ai perms admin add <user>` / `!ai perms admin remove <user>` edit the administrator list.
- `!ai perms reset <command>` returns a command to its default level.

Rules are stored in the `command-permissions` setting as
`command;level=<level>;whitelist=<bool>;users=<entries>;enabled=<bool>`, so they survive a restart
and can be edited by hand. A rule written by an older build, which carried only `whitelist=true`, is
migrated to the matching level. A rule whose level cannot be read resolves to `local-only` rather
than to the open default, so a typo in the configuration closes a command instead of opening it.

### How an unclaimed line is handled

Anything that is not a trigger and not a command is left alone. The assistant answers only what it
was configured to answer: there is no model in the mod and no guessing at intent. A line that no
rule and no command claims produces no reply, which is what keeps the assistant predictable — every
answer it gives can be traced back to a rule you wrote or a command you enabled.

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
| `permissions` | Master switch for the permission model; off by default. |
| `unlisted-commands` | `open` or `local-only` for commands with no rule. |
| `command-permissions` | The per-command rules, one encoded rule per line. |
| `administrators` | Players treated as administrators, `Name` or `Name=uuid`. |
| `require-verified-names` | Refuse bare-name entries for senders the client cannot pin. |
| `duplicate-filter` | Drop a message the assistant already answered in the last few seconds. |
| `greetings` | Greet the local player on joining a world. |
| `morning` / `afternoon` / `evening` / `night` | Greeting variants per period, separated by `\|`. |

### Assistant Manager

`!ai gui` (or the module's "Assistant Manager..." button) opens a window with three tabs:

- **Triggers** — add, edit, enable/disable and remove rules; restore the defaults.
- **Memory** — add, filter and forget facts, see recent exchanges, clear history or all memory.
- **Status** — counts, the memory file path, this session's chat activity, and a "test a message"
  box that runs a line through the live runtime, so it shows the same permission decision a real
  chat line would get.

The widget also has an "Assistant Permissions..." button, which opens the permissions screen
described above.

### Greetings

With `greetings` on, the assistant greets the local player when they join a world, choosing a
greeting that suits the time of day. Each period has its own pool of variants, and one is picked at
random, so the greeting does not repeat word for word. A blank pool falls back to a neutral
greeting rather than sending nothing.

### HUD

The `assistant` HUD element shows the number of triggers, remembered facts and handled messages.
It shows a single "Disabled" line when the module is off, so it never displays stale data.

### `!ai` command

| Command | Effect |
| --- | --- |
| `!ai [status]` | Assistant state and memory summary. |
| `!ai gui` | Opens the Assistant Manager. |
| `!ai triggers [list]` | Lists the loaded triggers. |
| `!ai triggers add <pattern> <reply>` | Adds a trigger. |
| `!ai triggers remove <n>` | Removes trigger number `n`. |
| `!ai triggers restore` | Restores the default triggers. |
| `!ai facts` | Lists stored facts. |
| `!ai remember <key> <value>` | Stores a fact. |
| `!ai forget <key>` | Removes a fact. |
| `!ai test <message>` | Shows what the assistant would do with a message. |
| `!ai history` | Recent remembered exchanges. |
| `!ai placeholders [section]` | With no section, an index of the placeholder sections and how many each holds. With a section (`player`, `time`, `server`, `world`, `position`, `client`), that section only, each name with its current value. |
| `!ai perms` | Lists every command with its permission level and whitelist. |
| `!ai perms gui` | Opens the permissions screen. |
| `!ai perms set <command> <level>` | Sets one command's level. |
| `!ai perms allow\|deny <command> <user>` | Edits one command's whitelist. |
| `!ai perms admin add\|remove <user>` | Edits the administrator list. |
| `!ai perms reset <command>` | Returns a command to its default level. |
| `!ai save` / `!ai clear` | Save memory now / clear all memory. |

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
| `log-eat` | Eats from the hotbar when hunger drops to a threshold, preferring the held food with the highest nutrition. Only selects a slot and uses the item; it never creates food. |
| `log-tool` | Swaps to the best hotbar tool for the block being mined (suitability, then speed), optionally swapping back afterwards. It only changes the selected slot. |
| `log-replenish` | Keeps the items you list stocked on the hotbar by moving a stack into place with a normal slot swap. It never invents items and never takes a stack another requested slot needs. |
| `auto-sprint` | Holds sprint on while you move forward, optionally only above a food threshold. |
| `log-safe-walk` | Releases the forward key at the edge of a drop, exactly as a cautious player would. It only ever removes movement input. |
| `log-anti-afk` | Performs small, genuine client actions on a randomised schedule (look, jump, sneak, swing, step, swap hand) so you are not kicked for idling. |
| `armor-guard` | Warns when a piece of armour is close to breaking. A missing piece is reported as unavailable, never as "0 % durability". |
| `log-panic` | Disconnects when health, food, inventory space, a hostile mob or another player crosses a rule you enabled. Off by default; every rule is opt-in, and there is a grace period after joining. |
| `log-reconnect` | Reconnects to the last **multiplayer** server after a disconnect, using vanilla's own connection screen, with a bounded backoff and a retry cap. |
| `server-monitor` | Prints a periodic status line (duration, game mode, latency, free slots) and a session summary on leave, using only what the client can observe. |
| `auto-login` | Answers a server's `/login` prompt with the password stored for that server. Only sends in reply to a recognised prompt, never on join, so a password cannot be sent as public chat on a server without an auth plugin. Passwords live in a separate owner-only file, never in the Meteor config. |
| `watchlist-alerts` | Raises a notification when a player on the chat log's watchlist joins or leaves. Only real transitions alert, so a server that announces a join twice does not produce two notices. |
| `death-tracker` | Remembers where you died. `!deaths` lists them newest first, `!back` shows the most recent in your current dimension. Coordinates from another dimension are reported, never offered as a return target. |
| `auto-creative` | Imports a material list (or takes typed entries), acquires the items in Creative mode and can hand the ordered list to the Schematic Dropper queue. See below. |

The `log-` prefix on several modules is deliberate, not decoration. Meteor's registry is keyed by
class but de-duplicated by name, so an addon module that reuses a Meteor name **deletes Meteor's
module from its own registry**. Meteor's mixins then look that module up by class, get `null`, and
crash: while this addon called its reconnect module `auto-reconnect`, Meteor's
`DisconnectedScreenMixin` read `Modules.get().get(AutoReconnect.class).time` and threw on every
disconnect. `ModuleNameTest` fails the build if any name here clashes with Meteor's or with another
module of ours, and `LogClient` logs an error at startup if a duplicate name reaches the registry.

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
`!log acquire`. It shows each material with its icon, required/held/still-needed quantities, status
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
| `chat-filter` | Ordered hide/allow rules. The first matching rule wins, so a specific `ALLOW` can punch a hole in a broad `HIDE`. A matched message is cancelled on the incoming message event at the highest priority, so it is removed before it reaches the chat HUD, the recorder or the log file. |
| `chat-timestamps` | Puts a clock time in front of every incoming chat line (`HH:mm`, `HH:mm:ss` or 12-hour), optionally dimmed. Only the displayed text changes: the log keeps its own timestamp field, so nothing is stored twice. |
| `repeat-collapse` | Folds a run of identical consecutive chat lines into the first one plus a "(N more of the same line)" note. The window and whether your own messages are exempt are configurable. Every line is still written to the log, because the log is evidence. |
| `chat-digest` | Summarises the last N messages: totals per message type, unique players, joins/leaves/deaths and the most active players. Run it on demand (`!log digest`) or on a timer. Optionally forwards each digest to the webhook. |
| `session-timeline` | A bounded timeline of milestones the client can actually observe: session start/end, joins, leaves, deaths and keyword hits. Browsable in a GUI (`!log timeline`). |
| `session-stats` | Live session counters, and a short **session summary printed when you leave a world** (messages, unique players, duration, rate, joins/leaves, deaths, keyword alerts and the most active players). |
| `webhook-monitor` | Watches the shared webhook queue and reports its health. Keeps a bounded delivery history, warns in chat when a delivery fails and can note a recovery. The URL is never stored or shown — only the host. |
| `webhook-relay` | Owns the single webhook target and the embed appearance every other feature sends through, and raises its own cards for session end, renames, watchlist presence, deaths and announcements. Can send a **leaderboard** card and a per-player **profile** card on demand. See **Webhook relay and embeds**. |
| `mention-alerts` | Alerts when your own name or a watched name is used as a whole word in chat. Raises a notification, an optional sound, an optional chat note and an optional mention embed, and records each hit in the alert log. |
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
- `!log playtime [player]` prints the table in chat. Players on the activity exclude list are not
  tracked.

### Player identity and renames

A client cannot see an account's rename history. It can only notice that the **same UUID** is later
announced under a **different name** in the server player list, so that is exactly what
`player-identity` reports — nothing more.

- Two different accounts that happen to share a name are tracked separately and never merged.
- A first observation is not a rename; the previous name is retained and shown as `was <name>`.
- `!log identities [renamed|watch <player>|unwatch <player>|watchlist|clear|<filter>]` browses the
  index in chat; the **Player Identity** screen (`!log identities`, or the module's button) shows a
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

- `!log sessions [list|clear]` prints the totals and the most recent sessions in chat.
- The **Session History** screen (the module's button) shows a table with a **View Log** button per
  session that opens the log viewer filtered to that session's id.
- The history is bounded; the cap is the module's `history-size` setting and takes effect
  immediately when lowered. A session id is never stored twice.

### Alert history

Chat output is deliberately terse, so a message that scrolled past is easy to miss. Every keyword
hit, webhook delivery failure and internal error is also written to a bounded alert log.

- The dashboard **Alerts** tab shows counts by kind and the most recent entries.
- The **Alert History** screen (`!log alerts`, or the button on that tab) is searchable by kind,
  title or detail, and can be cleared.
- The log never grows without bound and holds no more than the configured capacity.

### Mention alerts

`mention-alerts` watches for your own player name and any names you list, and fires only when one
appears as a whole word, so `Steve` does not trigger on `SteveJobs` or `Steve_99`. On a busy server
this answers "did anyone mention me?" without reading every line.

Each hit can raise a notification, play a note, print a chat line and forward a mention embed to the
shared webhook, and it is recorded in the alert log like any other noteworthy event.

- **names** — the names to watch, one per line.
- **include-own-name** — also watch your own player name (on by default).
- **others-only** — ignore lines you sent yourself (on by default).
- **sound**, **notification**, **chat-feedback**, **webhook** — the output channels.
- **cooldown-seconds** — shortest gap between two alerts for the same name (2 by default). Mentions
  inside the gap are counted, not printed.
- **burst-limit** — most alerts allowed in ten seconds across every name (6 by default).

A watched name that appears in every message used to produce a chat line and a sound per message.
Alerts are now throttled per name and capped per burst: one alert carries the count of what it
absorbed, as `Steve (+4 more)`. Set **cooldown-seconds** to `0` to turn the per-name throttle off.
`keyword-alerts` has the same two settings, because a rule that matches a common word floods the same
way.

### Summary

`!summary [note]` posts a chat summary to the webhook as a formatted embed: totals, joins, leaves,
deaths, the most active players and the keyword highlights for the recent window, with your note and
name attached. It is the shareable counterpart to `!log digest`, which only prints to your own chat.

It is **local-only** for the same reason as `!modcall`: the card describes your archive, and a
stranger in public chat could otherwise fill your Discord channel. Nothing is sent unless a webhook
URL is configured, and the module reports a failure rather than claiming the card went out.

### What the assistant can do: `!ai caps`

`!ai caps` lists every ability the assistant has, each with a yes/no and the reason. Every row is
derived from live state, so it never claims something it cannot do:

| Ability | Decided by |
|---|---|
| Triggers | switched on, and at least one rule armed |
| Memory | `memory-commands` |
| Reports | webhook-relay configured with `allow-reports` |
| Permissions | `permissions` |
| Server figures | being connected (TPS and latency are measured, not reported) |
| Player list | being in a world |
| World position | being in a world |

A disconnected client says it cannot see server figures instead of printing `n/a` for a figure it
never had. The same applies to reports: with no channel configured, the row reads `no - no reports
channel configured`, which is why `!modcall` refuses rather than silently doing nothing.

### Modcall

`!modcall <reason>` files a report **entirely on your own client**: it posts an embed card to the
reports channel you configured in **webhook-relay**. No server command is sent and nothing is said
in chat.

That is a deliberate change from earlier builds, which sent a private message to one named
moderator:

```
/msg <moderator> Report has been submitted: <reason>   <- removed
/msg <you> !report <reason>                            <- removed
```

Both lines had problems. `/report` is not a vanilla command, so on most servers it silently did
nothing; and a `/msg` reaches exactly one player, who is often offline, and leaves no record. The
current build posts to your own channel instead, so a report is written down even when no moderator
is looking at that moment.

`!modcall` is its own module (**modcall**), so it can be switched on and off on its own. Its
settings are:

- **kind** — label the card `mod` (a request for help) or `report` (an incident).
- **allow-remote** — off by default, so only you can file a report through your client.
- **feedback** — confirm locally when the report was queued.
- **cooldown-seconds** — per-player gap, 60 s by default, so one person cannot flood the channel.

Reports need the **webhook-relay** module configured with a URL and `allow-reports` on. When it is
not, `!modcall` says so instead of failing silently, and `!ai caps` shows the reason.

This command posts to your own channel, so it is **local-only**: only you can trigger it. That check
does not depend on the `permissions` setting, which is off by default — otherwise a stranger could
type `!modcall` in public chat and have their words appear in your reports under your name.

The reason is text from chat, so it is sanitised before it reaches the card: every control character
becomes a space (a newline would otherwise break the embed), runs of whitespace are collapsed, and
the reason is capped at 180 characters.

### Filed reports: `!reports`

Every report your client files is recorded locally, so the log answers "did I already report that,
and did it go through?" without scrolling Discord.

```
!reports               the newest reports, newest first
!reports griefer       only reports whose reporter or reason mentions "griefer"
```

The log lives in **webhook-relay** under **Report log**:

- **report-log-size** — how many reports to remember, 200 by default.
- **persist-report-log** — keep the log across restarts.

A report that failed to deliver **stays in the log, marked failed**, because a report you believe
was filed but was not is exactly the case worth keeping. `!reports` says the log is unavailable when
the relay module is not loaded, rather than claiming you have filed nothing — those are different
facts.

### One player, one card: `!player`

`!player <name>` composes everything the client already knows about a player into a single card:

```
!player Steve
```

It gathers remembered facts, how many messages they have sent this session, and how often they have
asked the assistant something. The pieces existed before, but each needed its own command; this
answers "who is this person" in one go.

Only figures the client actually holds are shown. A player who has never spoken has **no activity
row at all**, which is not the same as having zero messages. A player the client knows nothing about
gets a plain "I know nothing about them yet" rather than an empty card, since an empty card implies
data that does not exist.

### FAQ entries: `!faq`

`!faq` keeps up to **50** short key/answer notes in the assistant's memory and reads them back by
name or by number. An FAQ entry *is* a stored fact, so `!faq add spawn = the blue bed` and the
`{fact:spawn}` placeholder read the same value — there is no second store that can drift.

```
!faq                      show the subcommands
!faq count                how many of the 50 slots are used
!faq list [text]          list entries, numbered, optionally filtered by key or answer
!faq 30                   read entry 30
!faq view 30              the same, spelled out
!faq view spawn           read the entry named "spawn"
!faq add key = answer     create or update an entry
!faq remove 30            delete entry 30
!faq remove key           delete the entry named "key"
```

Every listing is numbered, and the number is the entry's position in the store, not its position in
that one listing — so entry 30 is entry 30 whether you saw it in a full list, a filtered list, or
typed it from memory. A listing shows twelve rows by default and then says how many it held back.

`count` answers the "how many are there" question directly:

```
FAQ: Used 32 of 50, Spare 18, Read !faq <number>
```

The store stops at 50 entries. A new entry past the cap is refused with a message saying so; updating
an existing key is always allowed, so a typo in entry 12 stays fixable even when the FAQ is full.

**Numbers shift after a removal.** Numbering is positional, so deleting entry 30 moves the old 31 into
slot 30. Numbers are a shortcut for reading, not stable identifiers — use the key when you need
something that does not move.

The FAQ is its own module (**faq**), so it answers even with the assistant switched off. Settings:

- **chat-output** — off by default, so answers stay in your own notifications instead of filling
  public chat. Turn it on to answer out loud.
- **max-listed** — how many rows one listing shows before it asks you to narrow the search.
- **allow-remote-write** — off by default, so a stranger in public chat cannot edit your memory.

### Chat filter rules

A rule is one line of `pattern;sender=NAME;type=TYPE;action=hide|allow;case=true;whole=false;enabled=true`. Any part may
be omitted: an empty pattern matches any text, an empty sender any player and a missing type any
message type. `whole` matches whole words only. The settings page for `chat-filter` documents the
format, and the default rule list hides `wts`/`wtb` while always allowing server announcements.

## Webhook relay and embeds

`webhook-relay` is the single place the addon's Discord output is configured, and the single place
its look is defined. Every other feature (keyword alerts, periodic chat reports, digests) reads the
shared target it publishes, so there is **one webhook to set** instead of one per module.

All output is sent as **rich embeds**, not a flat `content` string: each card has a coloured accent
bar, a title and named fields, so a keyword hit or a session summary reads as a purpose-built message
instead of a paste. Embeds are also not collapsed behind a "show more" link the way long content is.

| Card | Raised when |
| --- | --- |
| Keyword alert | A keyword rule with `webhook=true` matches a line. |
| Chat summary | The chat-logger sends a buffered report (timer or **Send Now**). |
| Chat digest | The digest module forwards a digest, the relay's own timer fires, or `!log webhook digest`. |
| Session summary | A recording session ends (leaving a world). |
| Player rename | The same UUID is announced under a new name. |
| Watchlist join/leave | A watched player joins or leaves (opt-in). |
| Death | A death message is observed (opt-in). |
| Announcement | A server announcement is observed (opt-in). |
| Mention | `mention-alerts` sees your name or a watched name used in chat (opt-in). |
| Leaderboard | `!log webhook leaderboard`, the relay widget's **Leaderboard** button. |
| Player profile | `!log webhook profile <player>`, or **Profile...** on the relay widget. |
| Server status | `!log webhook sendstatus`, or **Status** on the relay widget. |
| Test | The **Test Embed** button or `!log webhook test`. |

### Settings

- **webhook-url** — the target. Treated as a secret: never written to logs, payloads or error
  messages, and the Discord payload explicitly disables mentions so captured chat cannot ping a role.
- **webhook-username** — the display name used for every message.
- **relay-enabled** — a master switch; off keeps the URL but transmits nothing.
- **accent** — `auto` colours each card by its type (joins green, deaths red); any other value forces
  one colour across every card. The palette is `blurple, slate, green, yellow, red, pink, teal,
  purple, orange`.
- **footer**, **author**, **timestamp**, **inline-fields**, **show-server** — the shared appearance.
- **session-end**, **player-rename**, **watchlist-presence**, **deaths**, **announcements** — which
  event cards to send.
- **digest**, **digest-interval-minutes** — a timer for digest embeds.

### Embed preview

**Preview Embeds...** (the relay's widget, the dashboard **Webhook** tab, or `!log webhook preview`)
renders every card type exactly as Discord would — accent stripe, title and fields — so the
appearance can be checked without a webhook. **Copy JSON** puts the exact payload on the clipboard
for a webhook tester. The card selector covers keyword alerts, summaries, digests, session
summaries, renames, mentions, leaderboards, profiles and the test card.

### `!log webhook`

| Command | Effect |
| --- | --- |
| `!log webhook [status]` | Target host, accent and delivery health. |
| `!log webhook test` | Sends a test embed. |
| `!log webhook preview` | Opens the embed preview. |
| `!log webhook digest` | Sends a digest embed now. |
| `!log webhook leaderboard` | Sends the most active players as a leaderboard embed. |
| `!log webhook profile <player>` | Sends one player's observed activity as a profile embed. |
| `!log webhook sendstatus` | Sends the live server figures (TPS, ping, players, world) as a status embed. |
| `!log webhook set <url>` | Sets the webhook URL. |
| `!log webhook accent <name>` | Sets the accent (or `auto`). |

## Live Chat Relay

- Buffers chat (player chat and, optionally, game messages).
- Sends a report every *N* minutes (default 5) and on demand through **Send Now**, as a **chat
  summary embed**.
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

Enable **Companion** in the addon window (or run `!log companion start`). The server listens on
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
  widget, Companion screen or `!log companion regenerate`) replaces it and restarts the server.
- Requests are rate limited per client, and every action runs on the client thread, so the app can
  only do what a user could do by hand — it never edits the inventory, never fabricates state and
  never blocks the game.

### `!log companion`

| Command | Effect |
| --- | --- |
| `!log companion [status]` | Whether the server is listening, the port, the client count and the address. |
| `!log companion start` | Turns the module on and starts the server. |
| `!log companion stop` | Turns the module off and closes the port. |
| `!log companion token` | Prints the access token (treat it like a password). |
| `!log companion regenerate` | Replaces the token and restarts the server. |
| `!log companion gui` | Opens the Companion screen. |

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
| POST | `/api/v1/chat` | Sends a chat line: `{"message":"hello"}`. A line starting with `/` is treated as a command. |
| GET | `/api/v1/players` | The observed players. |
| GET | `/api/v1/storage` | The segment count, compressed size and oldest/newest timestamps. |
| GET | `/api/v1/queries` | The names the query endpoint accepts. |
| GET | `/api/v1/query/{name}` | `messages`, `players` or `stats`, with `q`, `player` and `limit` filters. |
| POST | `/api/v1/command` | Runs a line as if typed: `{"command":"/tpa Steve"}`. It goes through the same send event the game posts for typed input, so a macro such as `!tpa <player>` expands and an addon command such as `!log status` runs. |
| POST | `/api/v1/notify` | Shows a chat line and a notification: `{"message":"..."}`. |

The server answers with a plain message and the right status code when something is wrong — a
missing token is `401`, a bad body is `400`, a client thread that never answers is `504` — and it
never puts an internal exception in the response.

## Updates

The **Updates** module checks GitHub Releases for a newer build of Log Client and, on request,
downloads the verified jar. It never installs anything itself: the jar is written to
`<gamedir>/logclient/updates` and the module tells you the path. All network work runs on a daemon
worker, so the game thread is never blocked.

### Settings

- **channel** — *Stable* offers full releases only; *Beta* also offers pre-releases.
- **check-on-join** — check once after joining a world.
- **auto-download** — download a newer release automatically as soon as one is found. Off by
  default; when on, the module reacts to the "update available" state and fetches the jar without
  further prompting.
- **check-interval-minutes** — how often to check while the game runs; `0` disables the periodic
  check.
- **chat-feedback** — a short line when a check finishes or a download completes.

The **Updates** screen shows the current state, the newest version, the download progress and the
result, with buttons to check now, download and open the release page. A failed check or download is
reported with the reason instead of an exception.

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
  run `!log importlog <path>`.
- Supported inputs: the plain text export (`.txt`), CSV, JSON lines, and the addon's own compressed
  segment (`!log.gz`). A native segment is CRC-checked, so a damaged file is refused rather than
  half-read.
- The file is parsed on the storage worker, never on the client thread, so a large file does not
  stall the game.
- Nothing is written until you press **Import into archive**. Before that you get a preview: the
  detected format, the number of entries, the first and last entry (so the range and order are
  obvious), and a list of the lines that could not be parsed and why. Unreadable lines are counted
  and shown, never silently dropped.

## GUI

Press **Right Shift** in-game (or run `!log gui`) to open the addon window. It has a navigation
rail on the left and a card list on the right, in the style of a modern Meteor-client interface:

- **Modules** — every module grouped by category, each row a card with an on/off switch and a
  **Settings** button. The rail shows how many modules are currently on, and each category heading
  shows how many of its modules are on. An enabled entry carries an accent bar down its left edge.
- **HUD** — every HUD element with an on/off switch and a **Settings** button, plus **Arrange HUD**
  (the editor), **Show all** and **Hide all**.
- **Theme** — the colour scheme, a live swatch preview of the derived colours, the HUD text scale and
  the command prefix used by Log Client's own commands (default `!`).
- **About** — the version, registration counts, the config path, the shortcuts and the licence.

**Search** filters the module or HUD list by name, title or description, and shows a clear empty
state when nothing matches. Long pages scroll with the mouse wheel; the content column is clipped to
the screen so nothing runs off the bottom, while the rail stays fully visible.

**Settings** opens a page with the module's own controls first, then every setting grouped exactly
as the module declares it. Each setting is a card with its name, description and control. The page
header carries a **Back** button and an on/off switch for the module itself, and a second search box
filters the settings on that page.

### Look and feel

Every screen is drawn through one theme, so the whole addon is visually consistent. Panels, buttons,
toggles and text fields have rounded corners and a hairline border; hover, selection and toggle
changes are eased over a fraction of a second rather than snapped; and active controls carry a soft
glow in the theme's accent colour. The navigation rail draws its own glyphs from primitives, so
there are no icon textures to keep in sync and the rail recolours with the theme.

Animations run off a shared real-time clock that both the GUI and the HUD advance once per frame, so
an animation looks the same whether it is drawn in a screen or on the HUD.

### HUD editor

**Arrange HUD** (HUD tab) shows every active element at its real position on a live canvas and
lets you drag it with the mouse, with arrow buttons for precise nudges, one-click anchor buttons
(any of the nine screen anchors), align/centre buttons, and a **Hide** button per element. Elements
are drawn exactly as they appear in game, so the editor is a real preview rather than a set of empty
boxes. Positions are saved to the config, so an arrangement survives a restart. The HUD tab also has
**Show all** and **Hide all** to enable or disable every element at once.

### Themes

The **Theme** page switches between built-in colour schemes — Blue (the default), Midnight, Azure,
Light, Violet, Teal, High Contrast, and the Meteor-style **Neon**, **Matrix**, **Amber**,
**Crimson** and **Cyan**. Applying one restyles every open screen on its next frame (the shared
theme instance is updated in place) and the HUD with it, and the choice is saved in `config.json`,
so the GUI opens in your scheme on the next launch. A scheme named by an older build (`DARK`,
`ROSE`, `FOREST`) is migrated onto its nearest replacement rather than silently reset. The
**Preview** strip shows the accent, secondary, glow and status colours the selected preset derives,
so a scheme can be judged without leaving the page.

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

The addon registers a `!log` command (alias `!la`). Run `!log help` for the list.

| Command | Effect |
| --- | --- |
| `!log gui` | Opens the addon window (same as Right Shift). |
| `!log status` | Chat logging state, automation state and notification counters, in a titled block. |
| `!log modules` | Every Log Client module with its on/off state and description. |
| `!log modules check` | Reports module names claimed by more than one module. A clash silently deletes the earlier module and later crashes the code that looks it up by class, so this is worth running after installing another addon. |
| `!autologin [status\|gui\|on\|off\|servers\|clear\|clearall\|test]` | Manages the per-server login password. The password is set in the module's GUI; no command ever prints it. |
| `!deaths [back\|clear]` | Lists remembered deaths newest first, shows the most recent one in your dimension, or clears the history. |
| `!log stats` | Session statistics in a titled block: messages, rate, players, joins/leaves, deaths and keyword alerts, with the most active players in the footer. |
| `!log record <on\|off>` | Starts or stops chat recording (enabling the module if needed). |
| `!log notifications [count]` | The most recent notifications. |
| `!log notifications clear` | Empties the notification history. |
| `!log tasks` | The automation queue and its history counters. |
| `!log tasks <start\|pause\|resume\|stop\|clear>` | Controls the automation. |
| `!log tasks add <type> <item> [amount]` | Queues a task, e.g. `!log tasks add mining diamond_ore 64`. |
| `!log waypoints [list]` | Lists saved waypoints. |
| `!log waypoints add <name>` | Saves your current position as a waypoint. |
| `!log import <file>` | Imports a material list (or a schematic) from a path, without the file dialog. |
| `!log importlog <file>` | Imports a chat log (.txt, .csv, .json or .log.gz) into the archive. |
| `!log alerts [count]` | The most recent keyword, webhook and error alerts, with per-kind totals. |
| `!log alerts clear` | Empties the alert history. |
| `!log playtime [player]` | Observed player session durations (a dash when unknown). |
| `!log schematic [status]` | The current build state and progress. |
| `!log schematic load <path>` | Loads a schematic from a path (no file dialog). |
| `!log schematic <start\|pause\|resume\|stop>` | Controls a build. |
| `!log acquire [start\|pause\|resume\|stop\|status\|retry]` | Controls automatic Creative material acquisition. |
| `!log acquire import <path>` | Imports a material list into the acquisition queue. |
| `!log schematic acquire` | Starts acquisition from the current schematic/material list. |
| `!log schematic import <path>` | Imports a material list into the Schematic Dropper queue. |
| `!log digest` | Prints a summary of recent chat activity. |
| `!log summary [note]` | Posts the same summary to the webhook as a formatted embed, with the note attached and the caller named. Unlike `!log digest` this leaves the archive, so it is the shareable form. |
| `!log search [terms]` | Searches the recorded archive and prints the newest matches as an aligned block. Plain words search the message text; `player:<name>` filters the sender, `type:<chat,system,join,leave,death>` filters the message kind, and `limit:<n>` (max 50) sets how many are shown. Quote a phrase to keep it together. An unknown `key:value` is searched for literally, so a colon in chat never blocks a search. With no terms it lists the newest messages. |
| `!log timeline [filter]` | Recent session milestones, optionally filtered. |
| `!log filter [stats]` | Chat filter rule count, default action and how many messages were hidden. |
| `!log filter reset` | Resets the filter counters. |
| `!log notes [list]` | Lists saved player notes. |
| `!log notes add <player> <text>` | Saves a note about a player. |
| `!log notes remove <player>` | Removes a player's note. |
| `!log notes search <filter>` | Searches notes by player or body. |
| `!log identities [filter]` | Lists observed accounts and their names. |
| `!log identities renamed` | Lists only the accounts seen under more than one name. |
| `!log identities watch <player>` | Adds a player to the rename watchlist. |
| `!log identities unwatch <player>` | Removes a player from the watchlist. |
| `!log identities watchlist` | Lists the watched players. |
| `!log identities clear` | Clears the identity index. |
| `!log sessions [list]` | Totals and the most recent past recording sessions. |
| `!log sessions clear` | Clears the session history. |
| `!log companion [status]` | The companion web server: listening state, port, client count and address. |
| `!log companion <start\|stop>` | Starts or stops the companion server. |
| `!log companion token` | Prints the companion access token. |
| `!log companion regenerate` | Replaces the token and restarts the server. |
| `!log companion gui` | Opens the Companion screen. |

### Assistant (`!ai`, alias `!assistant`)

| Command | Effect |
| --- | --- |
| `!ai [status]` | Assistant state and memory summary. |
| `!ai gui` | Opens the Assistant Manager. |
| `!ai triggers [list\|add <pattern> <reply>\|remove <n>\|restore]` | Manages keyword triggers. |
| `!ai facts` | Lists stored facts. |
| `!ai remember <key> <value>` | Stores a fact. |
| `!ai forget <key>` | Removes a fact. |
| `!ai test <message>` | Shows what the assistant would do with a message. |
| `!ai history` | Recent remembered exchanges. |
| `!ai placeholders [section]` | Lists placeholders one section at a time, with current values. |
| `!ai save` / `!ai clear` | Save memory now / clear all memory. |

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
  (native `!log.gz`, JSON, CSV, text) plus unreadable-line reporting and CSV quoting.
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
  scoring and swap-back, sprint gating, log-safe-walk edge rules, armour classification and urgency,
  the log-anti-afk schedule and action set, hotbar replenish planning, and the log-panic rules and grace
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
- `PlaceholdersTest` — every built-in placeholder (server, world, player, time), the `n/a` result
  for figures the client cannot see, case and whitespace tolerance, and that a copy of the registry
  can gain a placeholder without changing the shared one.
- `ServerStatsTest` — tick-rate and uptime formatting, the lagging and verdict thresholds, the
  day/night world clock wrapping at 24000 ticks, compass names for every yaw quadrant, the
  brief/full views and normalisation of null or out-of-range input.
- `StatsCommandsTest` — which verb answers, the wording of each answer, custom verb names, the
  per-command and global switches, and the "Not connected." reply outside a world.
- `SpeedTrackerTest` — straight-line and diagonal measurement, ignoring too-frequent samples,
  dropping the reference after a long gap, and reset.
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
- `CompanionDispatchTest` — the send path a command takes from the app: the line reaches a real
  `SendMessageEvent` listener, a listener that claims it stops the line being sent twice, a macro
  shortcut such as `!tpa Steve` expands rather than being sent verbatim, an empty line is never
  posted, and a missing bus still yields the line instead of silently dropping it.
- `CompanionCoreTest` — the event bus (ordered delivery, a bounded ring, backlog replay, a throwing
  subscriber isolated and dropped, monotonic ids, publish never blocking on a subscriber), the rate
  limiter (burst then throttle, per-client buckets, pruning), the client-thread seam (inline,
  scheduler-backed and timeout) and that the bundled app is self-contained and calls the documented
  endpoints.
- `VersionTest` — release-tag parsing, channel filtering (stable vs. beta) and ordering.
- `GitHubReleaseSourceTest` — the release feed parsed into candidates, channel selection and a
  malformed feed failing cleanly rather than throwing.
- `UpdateEngineTest` — the check/download state machine: an up-to-date check, a newer release
  becoming available, a download reaching READY, a checksum mismatch rejected, and a source failure
  reported as FAILED with a message instead of a thrown exception.
- `ModCallTest` — that a modcall builds a client-side report, never a server command, and that a
  reason containing a
  newline cannot become a second command.
- `SummaryEmbedTest` — the requested-summary card: title and accent, the requested-by and note
  fields, the counts, and that the note is neutralised so it cannot ping a role or break the
  markdown.
- `UnsignedChatAndRoutingTest` — the behaviour that decides whether the assistant answers at all:
  unsigned and plugin-rewritten chat (`<Steve> hi`, `Steve: hi`, `[MVP+] Steve: hi`) classifies as
  public chat while clock readings and `Server:` tags stay system, join/leave/death lines keep their
  own types, and the shipped trigger table fires on ordinary chat without nested patterns making
  rule order decide the answer.
- `ModuleNameTest` — every module's registered name is unique and none is a name Meteor ships. Meteor
  de-duplicates by name, so a clash silently deletes Meteor's module and crashes the code that looks
  it up by class; this test is the regression for the `auto-reconnect` crash on disconnect. It reads
  the sources because Meteor's registry only exists inside a running game.
- `HighlightAnnouncerTest` — a highlighted message is announced once, a repeat delivery on a later
  tick is ignored, a genuinely new message still announces, memory stays bounded, and the monitor no
  longer polls the recent buffer on a tick (the cause of the same line being logged 20 times a second).
- `LogServiceListenerTest` — live-listener registration is idempotent (activating a module twice
  cannot double-announce), distinct listeners all fire, a removed listener is silent, and one listener
  throwing neither stops recording nor silences the others.
- `ModuleAuditTest` — duplicate detection used by both the startup guard and `!log modules check`:
  counts every claimant, reports all clashes in a stable order, and ignores blank names.
- `RowsTest` — the list-cap rule behind every truncated answer: a list that fits reports nothing
  remaining, exactly-at-limit is not truncated, one over reports exactly one, and a non-positive
  limit shows nothing while still counting every row.

## Troubleshooting

- **A screen opens but is empty, or a button seems to do nothing.** This was a real bug: a
  window-based screen cleared its whole widget tree on rebuild, which dropped the window frame, so
  the file browser, the import preview and the log viewer rendered nothing after the first click.
  Fixed by re-attaching the frame on every rebuild; `ScreenRebuildTest` guards it.
- **The OS file dialog does not open.** Expected on some Linux desktops and on headless setups:
  `tinyfd` returns nothing there. Use the addon's own file browser (**Import file...** /
  **Load Schematic...**), or set the `material-file` / `schematic-file` setting to a full path and
  press the matching *Path* button. The typed-path route and the `!log import` /
  `!log schematic load` commands never touch the native dialog.
- **Loading or importing does nothing.** The work is driven from the module's client tick, so the
  module has to be enabled and you have to be in a world. The buttons enable the module for you when
  they can, and otherwise say why.
- **"Start build" appeared to do nothing.** It used to refuse until **build-enabled** was turned on
  by hand. It now switches that on for you when you press it, so a loaded schematic builds straight
  away.
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
  Companion screen or `!log companion token`.
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
- `log-reconnect` only reconnects to a server the client already has a saved entry for, and does
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

### Starter knowledge

A fresh install is not empty. The assistant ships with a short set of facts — what it is, what the
prefix is, how memory works, how macros work, and that player-specific answers stay local — so
`!fact <key>` answers something useful on the first day instead of returning nothing.

These are ordinary facts: `!facts` lists them, `!forget` removes them, and `!remember` overwrites
them. The seed is only written when the store has never held anything, so a fact you delete stays
deleted rather than reappearing at the next launch.

The assistant answers from what it has stored when you ask for it by name. `!fact <key>` reads one
fact back and `!facts` lists them all:

```
you: !fact prefix
bot: prefix: ! by default. Type !help for the command list.
```

It does not answer a free-form question such as "what is the prefix?" on its own: there is no model
in the mod, so a line that no trigger and no command claims is left alone. That is deliberate — an
answer you did not configure is an answer you cannot check.
