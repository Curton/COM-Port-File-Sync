# link-lab — serial link emulator + protocol peer for com_file_sync

`link-lab` is the regression-test harness for COM Port File Sync. It exists so the application can
be exercised the way it runs in the field — over a real COM port, at a real serial speed, with a
real protocol counterpart — without a second physical machine.

It provides two things:

1. **A serial link emulator.** Every byte that crosses the wire is paced to the configured baud
   rate (plus latency and jitter) and can be dropped, corrupted, noised, delayed or tampered with.
   The application cannot tell the difference from a slow, imperfect cable.
2. **A protocol peer.** The peer is *the application itself*, compiled from `src/main/java` and run
   headless: a real `FileSyncManager` with its full listener loop answers heartbeats, negotiates
   roles, exchanges manifests, receives batches/deltas/appends, serves conflict content and shared
   text. Only the wire and the operator surface are new.

```
   ┌───────────────────┐                     ┌──────────────────────────────┐
   │  app under test   │  COM10 ◄──────────► │  com0com virtual pair        │
   │  (real Swing UI)  │                     │  COM11 ◄──────────────────┐  │
   └───────────────────┘                     └──────────────────────────┼──┘
                                                                       │
                                              ┌────────────────────────▼───────────┐
                                              │  link-lab                          │
                                              │  WireChannel: pace / drop / noise │
                                              │  FrameTap:   DROP DELAY INJECT    │
                                              │  RemotePeer: headless FileSyncMgr │
                                              │  workspace B + console + scenarios│
                                              └────────────────────────────────────┘
```

The project deliberately has **no cross-version wire compatibility**: the peer runs the same build
as the app under test (see `app.version` in `pom.xml`, keep it in sync with the application pom).

---

## 1. Prerequisites: a virtual COM port pair

The application opens real COM ports, and so does this tool. On Windows the pair is provided by a
virtual-port driver such as **com0com** (see <https://github.com/tanvir-ahmed-m4/com0com>, also
available from <https://sourceforge.net/projects/com0com/>). The tool does **not** ship a driver:
it speaks to the pair the driver creates.

Install and create a pair (run `setupc.exe` from an elevated prompt in the com0com install
directory):

```bat
setupc.exe install PortName=COM10 PortClass=com0com
setupc.exe install PortName=COM11 PortClass=com0com
setupc.exe list
```

You should see `COM10 <-> COM11` (use `-PortName=COM10` query flags to confirm the pairing). Any
pair works; the convention below assumes **COM10 = app, COM11 = link-lab**. For bridge mode you
need two pairs, e.g. `COM10<->COM11` and `COM13<->COM14` (app A on COM10, app B on COM13, the tool
relays COM11 <-> COM14).

Important: **com0com pairs do not model line timing at all** — data written to one end appears in
the other end's buffer immediately, regardless of baud rate. That is exactly what link-lab adds:
the tool paces every byte at the configured serial rate on both directions.

## 2. Building

From `tools/link-lab` (this repo's tool directory):

```bash
JAVA_HOME="C:/Users/liuke/.jdks/corretto-21.0.9" \
/c/Users/liuke/scoop/apps/maven/current/bin/mvn.cmd -o package
```

or just use `run.bat`, which builds without tests and launches:

```bat
run.bat peer --port COM11 --frames
```

The build compiles the application sources in place (build-helper add-source of
`../../src/main/java`), so the simulated peer always runs the exact protocol code of this build.
The shaded jar is `target/link-lab-<version>.jar`. This sub-project has its own `target/` (ordinary
build output); the *application's* `target/` remains the rollback archive and must never be
cleaned.

## 3. Modes

```
java -jar target/link-lab-1.0.0.jar ports [--...]
java -jar target/link-lab-1.0.0.jar selftest  [wire options]
java -jar target/link-lab-1.0.0.jar peer     --port COM11 [options]
java -jar target/link-lab-1.0.0.jar bridge   --ports COM11,COM14 [wire options]
```

### `ports`

Lists the COM ports jSerialComm can see (i.e. whether the com0com pair is visible to Java).

### `selftest`

Pushes 100 kB through the emulator and reports the achieved rate against the model. No hardware:

```bat
run.bat selftest --baud 9600
# [WIRE] selftest: 100000 bytes in 104 ms -> 960 B/s (model: 960 B/s, PASS)
```

### `peer`

The simulated other machine. It listens on one end of the pair, the app under test on the other.

```bat
run.bat peer --port COM11 --workspace C:\lab\peerB --roles peer-sender --frames --trace trace.txt
```

| Option | Meaning | Default |
|--------|---------|---------|
| `--port COMx` | the tool's end of the virtual pair | required |
| `--workspace DIR` | the peer's sync folder (stands in for machine B) | `link-lab-workspace` |
| `--roles peer-sender\|peer-receiver\|auto` | deterministic role assignment | `peer-sender` |
| `--script FILE` | run a JSON scenario and exit (see §6) | none |
| `--frames` | log every control frame to the trace | off |
| `--trace FILE` | also write the trace to a file | stdout only |

The peer redirects the application's disk caches (`manifest-*`, `sigcache-*`) into
`<workspace>/.cache`, so runs are repeatable and never touch `~/.filesync`.

### `bridge`

The tool becomes the cable: two real app instances talk through the emulated wire. Nothing of the
application protocol is replaced, which makes this the closest thing to a two-machine test:

```bat
run.bat bridge --ports COM11,COM14 --baud 19200 --loss 0.5 --latency 10
```

## 4. The wire model

Parameters (all live: they can be changed mid-session from the console or a scenario).

| Parameter | Flag | Meaning |
|-----------|------|---------|
| baud | `--baud N` | line speed; the byte rate is `baud / bitsPerByte` |
| frame format | fixed 8N1 | one byte = 10 bit times, so 115200 baud = **11520 B/s**, 9600 = **960 B/s** |
| latency | `--latency MS` | one-way propagation delay per write batch (null-modem/USB adapter: 1-5 ms) |
| jitter | `--jitter MS` | random extra delay in `[0, jitter)` per batch |
| loss | `--loss PCT` | percent of bytes silently dropped |
| corruption | `--corrupt PCT` | percent of bytes delivered with one flipped bit |
| noise | `--noise PERIOD BURST` | a random `BURST`-byte burst after every `PERIOD` passing bytes |
| high water | (fixed 1 MiB) | bytes buffered before the writer is blocked — models a receiver that cannot keep up |

The pacing is exact, not approximate: each byte carries its wire departure time and the queue never
releases bytes early, so long-run throughput equals `baud/10` regardless of burstiness (verified by
`ByteScheduleTest` and `link-lab selftest`).

Backpressure is real too: if the receiving side stops reading, the emulator's buffer fills, the
pump stops draining the port, and the sender blocks — the same cascade as on a real cable.

### Frame tampering

Control frames (`[[SYNC:...]]`) are recognised in the byte stream and can be dropped, delayed,
corrupted, or surrounded by injected frames; raw XMODEM traffic always passes through untouched.
Rules are listed with `{"op":"tamper", ...}` in scenarios or `tamper ...` in the console:

```
tamper to-app drop HEARTBEAT_ACK#2                   drop the 2nd heartbeat ack toward the app
tamper to-app delay FILE_DATA#1 500                  hold a transfer announcement 500 ms
tamper to-app inject-after HEARTBEAT_ACK#1 '[[SYNC:DIRECTION_CHANGE:true]]'
```

Role assignment is deterministic: `--roles peer-sender` injects a `DIRECTION_CHANGE` pair that
settles both sides (the application has no random tie-breaking left), so a regression never depends
on negotiation luck. `--roles auto` leaves the real negotiation in place.

Frames are detected across read boundaries: a real driver merges ready bytes into one read, so a
frame split over two reads - or a frame followed in the same read by raw bytes (the XMODEM `'C'`
handshake byte right after an `ACK` frame) - is still detected as one frame, and only the longest
prefix that can still open a frame is held back. Everything else passes through untouched, so the
tap can never swallow raw transfer bytes (covered by `FrameTapTest`).

## 5. Peer mode: the operator console

Started without `--script`, the peer reads commands from stdin while the trace streams:

```
state                              connection / role / syncing
ls                                 workspace files
push <file>                        send a workspace file (drop-file path)
sync                               start a sync as sender
cancel                             cancel the running sync
text <message>                     send shared text
texts                              texts received from the app
folder                             ask the app for its sync folder
content <path>                     fetch the app's copy of a file (conflict path)
applog                             fetch the app's log text
inject <to-app|to-peer> <frame>    put a raw frame on the wire
fault loss|corrupt|baud|latency|jitter|noise|clean ...
tamper <to-app|to-peer> <drop|delay|corrupt|inject-after|inject-before> <CMD>[#n] [arg]
stats                              wire statistics
disconnect / connect COMx          link cycle
quit
```

Trace lines look like:

```
[14:02:11.412][APP->LAB ] FRAME [[SYNC:HEARTBEAT]]
[14:02:11.418][LAB->APP ] FRAME [[SYNC:HEARTBEAT_ACK]]
[14:02:11.418][LAB->APP ] WIRE  TAMPER inject after HEARTBEAT_ACK - ... [[SYNC:DIRECTION_CHANGE:true]]
[14:02:12.004][PEER     ] SYNC_COMPLETE
[14:02:12.005][PEER     ] inbound  wire: 25123 B offered, 25123 B released (11519 B/s), ...
```

## 6. Scenarios (repeatable regressions)

A scenario is a JSON list of steps executed against a live peer. Every step has a timeout, so a
hung protocol path fails the run instead of stalling it. The runner exits non-zero when any step
fails (CI-friendly).

```bash
java -jar target/link-lab-1.0.0.jar peer --port COM11 --script scenarios/happy-path.json
```

| Step | Effect |
|------|--------|
| `waitMs` | sleep |
| `push` / `waitFile` | send a workspace file / wait until a file appears in the workspace |
| `sync` | peer performs a full sync as sender (preview + apply) |
| `cancel` | cancel the running sync |
| `text` / `expectText` | send shared text / assert text received from the app |
| `inject` | put a raw frame on the wire (`to-app` or `to-peer`) |
| `fault` | change wire parameters (`baud`, `lossPct`, `corruptPct`, `latencyMs`, `jitterMs`, `noisePeriodBytes`, `noiseBurstBytes`) |
| `tamper` | add a frame rule (`action`: `drop`, `delay`, `corrupt`, `inject-after`, `inject-before`) |
| `resetMeter` | restart the throughput meter |
| `expectThroughput` | assert the achieved bytes/s of a channel within `[minBps, maxBps]` |
| `expectStats` | assert e.g. `minDropped` bytes on a channel |
| `waitState` | wait for `syncing`, `connected`, `sender`, `roleNegotiated` to reach a value |
| `folder` / `content` / `applog` | exercise the folder-context, conflict-fetch and log-fetch paths |
| `linkCycle` | disconnect and reconnect on the same port |

Bundled scenarios: `scenarios/happy-path.json`, `scenarios/slow-link.json`,
`scenarios/dirty-link.json`.

## 7. Regression playbook (connection lifecycle through shared text)

The app's README features map to concrete lab sessions:

| Feature to regress | Suggested session |
|---|---|
| Connect + role negotiation | `peer --roles peer-receiver` (app becomes sender); watch `ROLE_NEGOTIATE` / `DIRECTION_CHANGE` in the trace |
| Sync preview + transfer | app: Sync Preview, tick rows, Start Sync; console: `sync` for the reverse direction |
| Large file / delta | put a 10 MB file in both workspaces, modify 100 kB in the peer's copy, `sync` — the trace shows `DELTA_SIG_REQ`, `FILE_DELTA` |
| Batch of small files | 200 small files in the peer workspace, `sync` — one `BATCH_DATA` per ~32 KiB |
| Cancel mid-transfer | start a sync on a slow link, app: Cancel; console: `cancel` — verify both sides return to idle |
| Reconnect | `disconnect`, then `connect COM11`; repeat a sync |
| Shared text during transfer | console `text hello`, then `sync` — inline text is interleaved between XMODEM blocks |
| Noisy link | `--loss 1 --corrupt 0.05 --noise 20000 30` or the dirty-link scenario |
| Slow link | `--baud 9600 --latency 20`; note the app's 10 s XMODEM block timeout is exercised for real |
| Conflict resolution | same file modified on both sides; app: Sync Preview → conflict dialog; console `content <path>` fetches the app's copy |

## 8. What the tool does not model

- **Flow control (RTS/CTS) and FIFO sizes** are not modelled; only the byte rate, latency, loss,
  corruption and noise.
- A control frame cannot appear inside XMODEM block data in practice (the same assumption the app's
  own parser makes), and the tap uses that assumption.
- The app's UI still needs a human (or an external UI automation) to click buttons; the tool drives
  the *peer* and the *wire*, not the app's frames.
- The app's COM settings must match the tool's (115200 8N1 by default; change the app's Settings if
  you emulate a different rate, since the wire model only changes timing, not the port's baud
  register).

## 9. Semantics the tool relies on (protocol facts)

Useful when reading a trace or writing a scenario (all in `src/main/java`):

- Frames are `[[SYNC:<CMD>[:<escaped-param>]*]]\n`; params escape `\`, `:`, `]]` (`SyncProtocol.java:206-317`).
- Heartbeats answer each other immediately; the link is declared lost after 15 s of *any* silence
  (`ConnectionService.java:16-19`, `FileSyncManager.java:1097-1103`).
- There is no greeting frame: the first byte on a fresh link is a heartbeat
  (`ConnectionService.waitForConnection`).
- Sender wins by higher `System.currentTimeMillis()*1000 + rand(1000)` negotiation priority
  (`RoleNegotiationService.java:225-237`).
- Every payload command is `ANNOUNCE -> ACK frame -> 'C' -> XMODEM`, except `DELTA_SIG_DATA`
  (the receiver ACKs and the sender sends `C`).
- XMODEM is CRC-only, blocks of 128/1024/4096, sender waits 10 s per block ACK, 10 retries
  (`XModemTransfer.java:27-49`).
- XMODEM tolerates flipped bits (per-block CRC + NAK re-send) but not silently dropped bytes: one
  dropped byte desynchronises the block stream and burns the 10-retry budget, so `--loss` models a
  fault the transfer layer cannot recover from. The end-to-end tests therefore flip bits with
  `--corrupt` and lose whole *frames* with a `tamper drop` rule instead.
- Manifests are always GZIP'd JSON; the batch envelope is `BTH\0` v1; signatures are `SGS\0` v2;
  deltas are `DLT\0` v1.
- Frame-level `ACK` is a text frame; XMODEM/interleave ACKs are the raw byte `0x06` — do not
  confuse them in a trace.

## 11. Two-ended regression tests (`src/test/java/com/filesync/lab/e2e`)

No hardware and no COM port: a `DuplexLink` wires two `LinkSerialPortManager`s back to back in one
JVM, and two *real* `FileSyncManager`s (`RemotePeer.attach`, headless) run the production protocol
stack against each other over the emulated wire. Roles are never forced — the real negotiation
decides, and the tests follow whichever side won, so the election stays under test.

| Test | Path under test |
|---|---|
| `fullSyncTransfersFilesAndDeletions` | sync preview + batch transfer + deletion propagation (strict mode) |
| `dropFileIsReceivedAndSaved` | drop-file receive: registry folder resolution, sanitising, collision rename |
| `remoteFolderContextAndChangeNotification` | remote-folder exchange + folder-change notification |
| `sharedTextIsInterleavedIntoALiveFileTransfer` | shared text queued mid-transfer, dispatched between XMODEM blocks |
| `linkCycleLosesAndRecoversTheSession` | unplug → loss detection → re-plug → re-negotiation → resumed sync |
| `syncSurvivesALossyWire` | 40 kB payload carried through a wire that really flips bits, with retries |
| `aDroppedControlFrameIsRecoveredByReconnectingAndRetrying` | dropped `ACK` (tamper rule) → wedged exchange → disconnect/reconnect/retry |

Operational notes for these tests:

- Roles re-negotiate after every session rebuild, so which side is sender/receiver is read fresh
  each time (`sender()` / `receiver()`), and a payload assertion always checks the side that did
  *not* start with the file.
- `RemotePeer.halt()` + `restart()` are the deterministic equivalent of Disconnect/Connect:
  halting interrupts a listener that a lost control frame left parked in its protocol timeout,
  which waiting alone would not clear.
- Fault counters (`WireStats`) are owned per port manager and reused across channel re-attachments,
  so the counts describe the whole session, not just the latest conduit.
- A `STATE`-transition sampler thread logs both managers' liveness flags (connected, role
  negotiated, running, sender, syncing, plus the protocol's xmodem/awaiting/blocking flags) while
  each test runs; on failure it shows immediately which side was wedged.

### Where they run

The whole `com.filesync.lab.e2e` package is excluded from the default local build (`mvn test` runs
only the 49 wire-model unit tests). It runs:

- in CI, from the GitHub Actions **"link-lab regression"** workflow (`.github/workflows/link-lab.yml`)
  — manual dispatch only, never on push or pull request;
- locally on demand: `mvn -o test -Pe2e` in this directory (49 + 8 e2e tests, ~1 minute).

## 12. Development notes

- Unit tests cover the wire model without hardware: rate accuracy, backpressure, fault statistics,
  frame tampering, and the app-facing port wrapper (`mvn -o test` in this directory).
- Adding `-Pe2e` to the same command also runs the two-ended regression suite (section 11): two
  real application instances over one emulated link, covering the sync, drop-file, folder,
  shared-text, link-cycle and noisy-wire paths end to end.
- `spotless` is configured for this sub-project only; if you run `spotless:apply` scope it with
  `-DspotlessFiles` — the build compiles the application sources in place and a bare `apply` would
  touch them too.
