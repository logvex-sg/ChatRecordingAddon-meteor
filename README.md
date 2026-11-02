# Logvex Addon (Meteor Client)

A Meteor Client addon for Minecraft 1.21.1 with two modules:

- **Schematic Dropper** — drops the materials of a loaded schematic, one *server-confirmed*
  transfer at a time, with automatic head rotation and live progress tracking.
- **Live Chat Relay** — records chat, relays it to a Discord webhook on a timer, and keeps a
  compressed, searchable local archive of every report.

## Building

```bash
./gradlew clean test build
```

The jar is written to `build/libs/logvex-addon-3.0.0.jar`. Drop it into `.minecraft/mods`
alongside Meteor Client 0.5.8.

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

### Rotation

`RotationMath` is pure maths (no Minecraft types) so it is unit tested directly. Yaw is computed
from the horizontal direction and pitch from the vertical offset; the result is normalised and
clamped, and invalid (non-finite) targets are rejected before any rotation is applied. Rotation
is applied through Meteor's `Rotations` helper, which restores the client's own rotation right
after the movement packet — so the port keeps its own interpolated state instead of reading
`player.getYaw()`.

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
- `ChatRelayCoreTest` — buffer concurrency, gzip round-trip, corruption detection, archive
  persistence, index rebuild, search, cleanup, deletion and payload safety.

## License

MIT — see [LICENSE](LICENSE).
