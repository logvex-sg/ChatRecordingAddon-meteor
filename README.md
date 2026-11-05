# Logvex Addon (Meteor Client)

A Meteor Client addon for Minecraft 1.21.1.

**Chat toolkit** — `chat-logger` (records chat to compressed, searchable logs),
`chat-monitor` (live feed with filters and highlighting), `keyword-alerts` (keyword alerts with
sound and optional webhook), `player-activity` (per-player activity tracking) and
`session-stats` (session statistics), with matching HUD elements.

**Schematic Dropper** — imports a material report or a schematic, acquires missing materials in
Creative mode, drops them one *server-confirmed* transfer at a time, and can place a schematic
block by block into the world with verified placements and resumable checkpoints.

## Building

```bash
./gradlew clean test build
```

The jar is written to `build/libs/log-addon-5.0.0.jar`. Drop it into `.minecraft/mods`
alongside Meteor Client 0.5.8.

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
| Import Material List... | Opens a file picker and imports a whole material report (or a schematic). |
| Load Schematic... | Opens a file picker and loads a `.schem` / `.schematic` / `.litematic` build. |
| Start Build | Places the loaded schematic block by block, verifying each placement. |
| Pause / Resume / Stop (build row) | Controls a schematic build. |
| Schematic Builder... | Opens the build screen (progress, problems, history, checkpoints). |

### Importing a material list

**Import Material List...** opens the native file picker (`.txt`, `.md`, `.csv`) and parses the
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
  and the Sponge v3 `Blocks` container (WorldEdit 7.3+ default) are read.
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
- Progress is checkpointed to `<config>/schematicdropper/checkpoints/<name>.json` after each
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
is applied through Meteor's `Rotations` helper, which restores the client's own rotation right
after the movement packet — so the port keeps its own interpolated state instead of reading
`player.getYaw()`.

## Autonomous bot

The `auto-bot` module runs a Minecraft-free planning core (`com.logvex.addon.bot.core`) behind a
thin Meteor shell (`BotModule` plus the `bot.mc` adapters). Objectives are turned into tasks,
tasks run through executors (navigate, gather, craft, build, …) one per tick, and every action is
confirmed from the world or the server before it is treated as done.

### Navigation and Baritone

Navigation goes through Meteor's own path-manager abstraction (`PathManagers`), so **no separate
Baritone mod jar is bundled or required**. When Baritone (or Voyager) is installed, Meteor selects
it automatically and the bot drives it via `IPathManager.moveTo(...)`; when neither is present,
Meteor's path manager is the `none` backend and the bot falls back to a bounded built-in walker
that walks toward the target and gives up when it stops making progress. The active backend is
shown on the bot dashboard (`Navigation: Baritone` / `built-in walker`) and, optionally, in the
HUD (`show-navigation`).

The Navigation settings group exposes Meteor's path-manager options — `walk-on-water`,
`walk-on-lava`, `path-step` and `path-no-fall`. These are written into Meteor's own path-manager
settings, so Baritone and the bot agree on how the player may move; they have no effect with the
built-in walker. Movement is never teleported or otherwise faked: success requires the player's
actual position to be within the arrival tolerance.

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
- `ChatRelayCoreTest` — buffer concurrency, gzip round-trip, corruption detection, archive
  persistence, index rebuild, search, cleanup, deletion and payload safety.

## Known limitations

- Import reads the file in chunks on the client tick, so a very large report takes a few ticks to
  load rather than blocking; the list is never truncated.
- Creative acquisition needs genuine Creative mode with server-side permission. On a server that
  refuses creative inventory changes the module reports that instead of faking items.
- Schematic building uses Meteor's `BlockUtils.place` (rotate, select the item, click a face,
  restore), so it is bound by normal survival-style placement rules: the player must be in range
  and a support face must exist. It does not teleport, fly or bypass reach limits.
- The legacy (`.schematic`) id table covers the ids that appear in practice; anything outside it is
  reported as unresolved rather than guessed, and the item form of a legacy block is not always
  recoverable.
- In-game behaviour (the acquisition/build screens, HUD and file picker) has not been verified in a
  live Minecraft session from this environment; the core engines, the importer, the readers, the
  checkpoint store and the legacy id table are covered by unit tests that use fakes and the real
  1.21.1 registries.
- The bot uses Baritone only when the user has installed it (Meteor detects it automatically). The
  addon bundles no pathfinder of its own beyond the bounded fallback walker, and navigation quality
  with the fallback is limited — it walks in a straight line and stops when blocked rather than
  solving complex terrain.

## License

MIT — see [LICENSE](LICENSE).
