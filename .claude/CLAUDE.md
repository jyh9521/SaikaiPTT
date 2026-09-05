# SaikaiPTT - Claude Code Project Instructions

## 1. Project Identity

Project name:

SaikaiPTT

Application display name:

西海PTT

Android application package namespace:

com.saikai.ptt

Project type:

Production-ready Android application.

Product category:

Offline WiFi LAN Push-To-Talk (PTT) application.

Primary development environment:

Android Studio + Kotlin + Claude Code.

---

## 2. Project Goal

SaikaiPTT is a lightweight Android LAN walkie-talkie application.

The core concept is:

> Devices connected to the same WiFi network can automatically discover each other and communicate using one-to-one Push-To-Talk voice communication.

The application must operate entirely within the local network.

Internet access is NOT required for core communication.

No cloud server is required.

No user registration is required.

No login system is required.

No account system is required.

---

## 3. Core Product Principles

All engineering decisions must prioritize the following order:

1. Stability
2. Real-time communication reliability
3. Compatibility
4. Low power consumption
5. Low CPU and memory usage
6. Maintainability
7. Extensibility
8. Feature richness

Do NOT sacrifice reliability or compatibility merely to add more features.

Do NOT introduce unnecessary complexity.

Do NOT introduce heavy frameworks when a lightweight Android platform solution is sufficient.

---

## 4. Supported Android Versions

Minimum SDK: Android 11 / API 30

Target / Compile SDK: pinned explicitly in the Version Catalog (see Task03). Do NOT use "whatever is latest on this machine" — Release builds must be reproducible.

Supported range: Android 11 through current Android releases.

Reference low-end device: MediaTek MTK P22 class, Android 11, 4 GB RAM.
Reference modern device: current Samsung Galaxy flagship.

Low-end compatibility is a first-class requirement.

### 4.1 Android version compatibility red lines

Check these before writing any platform-facing code. Full detail in `docs/ADR/ADR-005-Foreground-Service-And-Compatibility.md`.

| API | Version | Constraint |
|---|---|---|
| 30 | 11 | minSdk. Scoped storage; recordings always go to app-private storage |
| 31 | 12 | **Cannot start a foreground service from the background.** `PendingIntent` must specify `FLAG_IMMUTABLE` |
| 33 | 13 | `POST_NOTIFICATIONS` is a runtime permission. Per-app language handled by the system (`AppCompatDelegate.setApplicationLocales` + `localeConfig`) |
| 34 | 14 | **FGS must declare `foregroundServiceType` and hold the matching `FOREGROUND_SERVICE_*` permission.** `BOOT_COMPLETED` must not start a `microphone`/`camera` type FGS |
| 35 | 15 | **Native libraries must be 16 KB page-size aligned.** `dataSync` FGS capped at 6h (not used here) |
| 36 | 16 | Same constraints as 15; re-verify FGS and notification behaviour on a real device before release |

Consequence for this product:

> **Receiving PTT works in the background, on-screen-off and locked.**
> **Transmitting requires the app UI to be visible** (microphone-type FGS cannot be promoted from the background on Android 14+).

## 5. Core Communication Model

SaikaiPTT is NOT a broadcast voice application.

Voice communication MUST be:

one-to-one.

The user must select a target device before starting PTT.

Voice packets MUST be sent only to the selected target device.

Do NOT broadcast voice packets.

Device discovery may use broadcast or multicast mechanisms, but actual voice traffic must be directed to the selected peer.

Recommended transport:

UDP Unicast.

Recommended voice codec:

Opus.

If compatibility or implementation complexity makes Opus impractical in a specific stage, a simpler fallback may be implemented, but the architecture must keep the audio transport replaceable.

---

## 6. Push-To-Talk Behavior

The communication model is traditional half-duplex PTT.

Press and hold:

Start transmitting.

Release:

Stop transmitting.

The normal communication model is:

one active speaker at a time.

The receiver does NOT need to accept or answer an incoming PTT request.

Receiving a PTT request is automatic.

---

## 7. Busy and Force Interrupt Modes

Default behavior: Busy mode.

If the target device is already engaged in a PTT session, the new request is rejected and the caller receives a clear BUSY status.

### 7.1 Force Interrupt is a RECEIVER-side switch

Setting key: `allow_interrupt` (Boolean, default **false**).

Meaning:

> "Allow other devices to interrupt a call I am currently in."

The caller does not need — and cannot — know the target's policy. The caller always sends an ordinary request; the callee replies with VOICE_ACCEPT or BUSY.

Rejected alternative: a sender-side switch. Any single device could then unilaterally interrupt every call on the network, with no way for the callee to refuse.

Intended environments: warehouses, factories, security, property management, emergency communication.

The callee is the sole arbiter of session ownership. Ownership transfer must be a single atomic operation.

See `docs/03_Protocol.md §33`, `§34`.

## 8. Device Identity

Every installation must have a persistent unique Device ID.

Generate a UUID v4 on first startup.

Store it locally.

The Device ID must remain stable across normal application restarts.

Do NOT use the following as the application's primary identity:

- MAC address
- IMEI
- device serial number
- Android hardware serial
- other restricted hardware identifiers

IP addresses are transport addresses, not device identities.

The architecture must distinguish:

Device ID

from

current IP address.

A device's IP address may change without changing its identity.

---

## 9. User Names

The application supports locally stored user names.

On first startup, the user must create or select a name before using PTT.

The device may store multiple names.

The user can:

- create a name
- rename a name
- delete a name
- switch the currently active name

The currently selected user name must be transmitted as part of the communication metadata.

Other devices do NOT need to have the same name stored locally.

If a remote device sends:

Device ID = X

User name = 田中

the receiving device must be able to display:

田中

even if "田中" does not exist in its local name list.

---

## 10. Device Discovery

The application must automatically discover peers on the local WiFi network.

Users must NOT be required to manually enter IP addresses, ports, hostnames or device registration information.

### 10.1 Mechanism

**v1 uses pure UDP broadcast discovery. NSD / mDNS is NOT implemented.**

See `docs/ADR/ADR-001-Discovery-Strategy.md`.

Reasons: one code path across Android 11~16 (NsdManager's API differs before/after API 34), no vendor-ROM variance on low-end MediaTek devices, shared socket and payload with heartbeat, and immediate propagation of username changes.

Known limitation: enterprise APs with AP Isolation or broadcast filtering will prevent discovery. This is documented in the README; v1 provides no manual-IP fallback.

Receiving broadcasts requires holding a `WifiManager.MulticastLock` — see §13.

### 10.2 Separation

Discovery logic must remain isolated from voice communication.

A discovery failure must not crash or block the audio subsystem.

## 11. Heartbeat and Presence

Heartbeat handling must be independent from discovery.

The system should distinguish:

Discovery:

"How do I find a device?"

Heartbeat:

"Is this discovered device still online?"

Voice:

"Send and receive audio."

Connection/session:

"Is a specific communication session active?"

Do not combine all of these responsibilities into a single class or service.

Preferred high-level components:

- DiscoveryManager
- HeartbeatManager
- ConnectionManager
- VoiceManager

Each component must have a single responsibility.

---

## 12. Automatic Recovery

The application must recover automatically from temporary WiFi or network changes.

Example:

WiFi disconnected:

- mark peers unavailable
- stop active network transmission
- avoid crashes

WiFi reconnects:

- restart discovery
- rebuild peer list
- restore heartbeat
- resume normal operation

The user should not need to manually restart the application.

The application must not assume that an IP address is permanent.

---

## 13. Background Operation

The application must support long-term background operation using an Android Foreground Service with a persistent notification.

Background operation must prioritize low CPU and low battery consumption.

Do NOT create unnecessary polling loops.
Do NOT perform unnecessary continuous network scans.

### 13.1 Foreground service type

```xml
android:foregroundServiceType="connectedDevice|microphone"
```

- Idle / receiving: `startForeground()` with `connectedDevice` only.
- While the user holds PTT: `startForeground()` again including `microphone`; revert on release.

Receiving and playback need no microphone, so **background receive is unaffected** by the microphone restrictions.

### 13.2 Power locks — the ONLY three permitted uses

The earlier blanket "no WakeLock" rule was too strict: with the screen off, WiFi power save drops broadcast frames, so without a `MulticastLock` the app cannot receive heartbeats in the background and the core requirement fails.

**Except for the three cases below, holding any power lock is forbidden.**

| Lock | Held when | Why |
|---|---|---|
| `MulticastLock` | For as long as the service is READY | Required to receive broadcast heartbeats with the screen off |
| `WifiLock(WIFI_MODE_FULL_LOW_LATENCY)` | Only during an active voice session | Reduces voice jitter |
| `PARTIAL_WAKE_LOCK` (5-minute timeout) | Only during an active voice session | Keeps the audio thread alive with the screen off |

Never hold a power lock for heartbeat, discovery, logging or UI refresh. Never hold one without a timeout.

See `docs/ADR/ADR-005-Foreground-Service-And-Compatibility.md §6`.

## 14. Overlay / Floating Window

Optional system overlay support is part of the product.

Normal idle state:

green indicator.

Incoming PTT activity:

red indicator.

The overlay may display the remote user's current name.

Example:

green:

SaikaiPTT

incoming:

red + remote username

Clicking the overlay should open the relevant application screen.

The overlay must remain lightweight.

---

## 15. Internationalization

Internationalization is mandatory from the beginning of development.

Default application language:

Japanese.

Supported languages:

- Japanese: ja
- Simplified Chinese: zh-CN
- English: en
- Burmese: my
- Bengali: bn

Do NOT hard-code user-facing strings in Kotlin or UI code.

All translatable strings must use Android resource localization mechanisms.

The localization architecture must make it possible to add more languages without changing business logic.

User-facing text includes:

- UI labels
- notifications
- overlay text
- error messages
- permission explanations
- settings
- empty states
- busy state
- status messages
- history records
- accessibility descriptions where applicable

---

## 16. Communication History

Communication history is a core feature.

Each **successfully established and completed** PTT session creates a local history entry on both the sending and the receiving device.

A request that was rejected (BUSY) or timed out with no answer creates **no** record.

The authoritative field list is `docs/05_DataModel.md §10`. Do not maintain a second copy of it anywhere else. It includes, among others:

- record id, session id
- sender / receiver device id, remote device id
- local user id, remote user name snapshot
- direction (SEND / RECEIVE)
- timestamp, durationMs
- audioPath, audioFormat
- transcript, transcriptStatus (5 states, default NOT_REQUESTED)
- isRead, isFavorite
- status (COMPLETED / INTERRUPTED / FAILED)

History must remain local. No cloud synchronization.

## 17. Audio Recording

Completed PTT sessions should be locally recorded.

Preferred format:

Opus.

Fallback:

AAC where required.

Do not store raw PCM files as the default persistent format.

Audio playback must be available from communication history.

The history system should allow users to:

- play
- pause
- resume
- stop

recordings.

Audio storage must be independent from the database.

The database stores metadata and the local audio file path.

---

## 18. Offline Speech Recognition

Offline speech-to-text is an optional feature. Recognition itself is 100% local: no cloud ASR, no audio upload, no remote API.

Current language requirement: Japanese only.

Recommended engine: Vosk or another suitable offline Japanese ASR.

### 18.1 Default is OFF — for every device

ASR is **disabled by default on all devices**, not only low-end ones. The user turns it on explicitly.

### 18.2 Model delivery — the one networking exception

The recognition model is **not bundled in the APK**. The first time the user enables ASR, the app asks for confirmation and downloads roughly 50 MB. After that it works fully offline, forever.

So the product's offline promise is stated precisely as:

> Core functionality — discovery, presence, one-to-one PTT, recording, playback, history — **never** requires the Internet.
> The single exception: enabling Japanese subtitles for the first time requires one download of the recognition model.

Download failure or cancellation leaves ASR off and affects nothing else.

See `docs/ADR/ADR-006-ASR-Engine-And-Model-Delivery.md`.

### 18.3 Priority

Speech recognition runs AFTER a PTT segment has finished. Real-time transcription is not a requirement.

Audio communication always has higher priority than speech recognition.

If recognition fails: the recording stays playable, the history entry stays valid, and the PTT system must not fail.

## 19. History Search

Communication history should support:

- remote user name search
- transcript text search
- chronological browsing

Optional:

favorite records.

Favorite records must not be automatically deleted by history cleanup.

---

## 20. History Cleanup

History retention should be configurable.

Suggested options:

- 1 day
- 3 days
- 7 days
- 30 days
- forever

Cleanup should remove associated local audio files together with the corresponding database record.

Avoid orphaned audio files.

---

## 21. Unread Records

Received communication records can have an unread state.

Opening or viewing the record should clear the unread state.

Unread communication history should be clearly identifiable in the UI.

---

## 22. Data Storage

Use:

DataStore

for lightweight persistent application settings.

Examples:

- Device ID
- current username
- username list
- language selection
- busy/force-interrupt preference
- user preferences

Use:

Room

for structured communication history data.

Do NOT use a database for simple application preferences.

---

## 23. Communication Protocol

The protocol is versioned and extensible. Its **normative definition** is:

- `docs/03_Protocol.md`
- `docs/ADR/ADR-003-Wire-Format.md` — the byte-exact wire format
- `docs/ADR/ADR-002-Transport-And-Ports.md` — sockets and ports

Never invent a packet layout. Never change one silently. Any format change requires a new ADR and a ProtocolVersion bump.

### 23.1 Shape

Every packet is a fixed **72-byte big-endian header** plus a `0..1024` byte payload. Device IDs and Session IDs travel as **16-byte binary UUIDs**, not 36-byte ASCII.

### 23.2 Packet types implemented in v1

```
DISCOVERY            DISCOVERY_RESPONSE
HEARTBEAT            PING / PONG
VOICE_START          VOICE_ACCEPT
VOICE_DATA           VOICE_END
BUSY                 SESSION_TERMINATE
```

`VOICE_ACCEPT` and `SESSION_TERMINATE` were added during document review: without a positive acknowledgement the sender state machine could never legally leave `REQUESTING`, and without a terminate packet an interrupted peer could never be told.

`FORCE_INTERRUPT`, `ERROR`, `GOODBYE`, `CAPABILITIES` are **reserved and unused in v1**.

### 23.3 Rules

Unknown packet types must be ignored safely, never crash, and never spam the log.

Do NOT prematurely implement future features such as group calling, messaging or file transfer.

## 24. Configuration

Do not scatter magic constants throughout the codebase.

Create a centralized configuration mechanism for values such as:

- heartbeat interval
- peer timeout
- discovery interval
- network ports
- audio parameters
- retry counts
- protocol version
- logging behavior

Centralized configuration must allow safe future tuning.

---

## 25. Error Handling

Use structured error definitions.

Examples:

- NETWORK_DISCONNECTED
- PEER_OFFLINE
- TARGET_BUSY
- PERMISSION_DENIED
- MICROPHONE_UNAVAILABLE
- AUDIO_INITIALIZATION_FAILED
- SEND_FAILED
- RECEIVE_FAILED
- DISCOVERY_FAILED
- SERVICE_START_FAILED

Error handling must be predictable and testable.

Do not rely on parsing arbitrary exception strings for application logic.

---

## 26. Logging

Create a centralized logging abstraction.

Example API:

Logger.d()
Logger.i()
Logger.w()
Logger.e()

Development builds:

detailed logging enabled.

Release builds:

debug logging disabled by default.

Logging must be easy to enable during development and troubleshooting.

Potential log categories:

- application lifecycle
- discovery
- heartbeat
- network
- protocol
- audio
- service
- storage
- permissions
- speech recognition

Do not log sensitive data unnecessarily.

Do not log full audio payloads.

---

## 27. Architecture

Preferred architecture:

MVVM + UseCase + Repository.

High-level flow:

UI
↓
ViewModel
↓
UseCase
↓
Repository
↓
Core implementation modules

Infrastructure should be accessed through interfaces where practical.

Business logic must not depend directly on UI classes.

Do not place all application logic in MainActivity.

Do not place all logic in the Foreground Service.

---

## 28. Suggested Module Structure

The project may use modules similar to:

- app
- core
- common
- network
- discovery
- heartbeat
- protocol
- audio
- service
- storage
- ui
- settings
- logger

Do not create modules merely for the sake of having many modules.

Module boundaries must correspond to actual responsibilities.

---

## 29. Interface-First Design

Core subsystems should expose stable interfaces.

Examples:

DiscoveryService
HeartbeatService
VoiceTransport
AudioRecorder
AudioPlayer
SpeechRecognizer
HistoryRepository
SettingsRepository

This allows implementations to be replaced later.

Example:

UDP voice transport may later be replaced by another transport without rewriting the UI layer.

---

## 30. UI Principles

Use Material Design 3.

The UI must remain simple.

Primary visual identity:

green.

Incoming PTT:

red.

Avoid:

- excessive animation
- unnecessary graphics
- complex navigation
- heavy visual effects

The application should feel like a digital walkie-talkie, not a social/chat application.

---

## 31. Performance Requirements

Performance is a first-class requirement. These are measurable targets on the MTK P22 reference device, all expressed **as a percentage of one core**.

| Metric | Target | Conditions |
|---|---|---|
| Idle CPU | < 1% of one core | service running, screen off, no traffic, 10-minute average |
| Transmit CPU | ≤ 15% of one core | capture + Opus encode + UDP send + recording write |
| Receive CPU | ≤ 12% of one core | UDP receive + jitter buffer + decode + playback + recording write |
| Java heap | < 32 MB | idle |
| Total PSS | < 130 MB | idle, excluding the ASR model |
| Mouth-to-ear latency | P50 ≤ 250 ms, P95 ≤ 400 ms | same AP |
| Idle network | ≤ 1 broadcast packet / 5 s / device | heartbeat |

### 31.1 About the memory numbers

The earlier "approximately 30 MB" invited wasted optimization: a Compose + Room + DataStore app with a foreground service realistically sits at 60–120 MB PSS on Android 11. It is therefore split into two measurable figures: **Java heap < 32 MB** (a real constraint on the app's own allocations) and **total PSS < 130 MB** (a tripwire for abnormal growth).

The ASR model is excluded from every target above.

### 31.2 Discipline

Do not sacrifice stability to hit a number. Measure on real low-end hardware — code inspection is not a performance measurement.

## 32. Dependency Policy

Minimize third-party dependencies.

Before introducing a dependency:

1. determine whether Android/Kotlin/Jetpack already provides the required functionality
2. assess maintenance status
3. assess APK and memory cost
4. assess Android 11 compatibility
5. assess long-term stability

Do not add a library simply because it makes a small task easier.

---

## 33. Testing

Core subsystems must have automated tests where practical.

Prioritize testing for:

- Device ID persistence
- username storage
- packet encoding/decoding
- discovery
- heartbeat
- peer timeout
- reconnect
- audio state transitions
- PTT state machine
- busy handling
- force interrupt
- history persistence
- cleanup logic
- transcript state

Real-device testing is mandatory for network and audio behavior.

---

## 34. Development Method

DO NOT generate the entire project in one step.

Development must be incremental.

Each task must:

1. have a clearly defined scope
2. modify only what is necessary
3. preserve existing functionality
4. include appropriate tests
5. be reviewed before moving to the next task

When a task is completed:

- run build checks
- run relevant tests
- review changed files
- inspect architecture impact
- document important decisions
- commit
- STOP

Do not automatically continue to unrelated tasks.

### 34.1 Where the build actually runs

Claude's sandbox **cannot build this project**. Verified during Task02:

- Egress is restricted to an allow-list. `dl.google.com`, `repo1.maven.org`
  and `services.gradle.org` all return 403, so Gradle cannot resolve AGP,
  Kotlin or any AndroidX artifact.
- The device-side VM has a JRE 11 with no compiler, far below what AGP 9.x
  and Kotlin 2.2 require.

So the division of labour is:

| Step | Who |
|---|---|
| Write and edit all project files | Claude |
| Static verification (XML well-formedness, resource and reference consistency, structure and numbering checks) | Claude |
| Gradle Sync, Debug build, Release build, unit tests, instrumented tests | **The developer, in Android Studio** |
| Interpret failures and fix | Claude, from the pasted output |

Claude must therefore:

- never claim a build succeeded that it did not run
- state explicitly, at the end of every task that touches the build, that the
  build is unverified and needs to be run
- say exactly what to run and what output to send back

### 34.2 Determining versions

Claude cannot query Maven. When a dependency version is needed, take it from
ground truth on the developer's machine rather than from memory:

- `~/.gradle/wrapper/dists` — Gradle distributions actually downloaded
- `~/.gradle/caches/modules-2/files-2.1` — artifacts actually resolved
- a project generated by the developer's own Android Studio — the version
  set that IDE currently endorses
- `<SDK>/platforms` and `<SDK>/build-tools` — installed SDK levels

Never guess a version number for a release that post-dates Claude's training
data. Android Studio 2026.1.4 ships AGP 9.x, whose DSL differs materially
from AGP 8 (`compileSdk { version = release(n) }`, `buildTypes { release {
optimization { enable = false } } }`, and no separate Kotlin Android plugin —
AGP 9 has built-in Kotlin support). Read a real generated project before
writing build scripts.

## 35. Task Discipline

When I tell you:

"Execute TaskXX"

you must:

1. read CLAUDE.md
2. read relevant documents under docs/
3. read the specified task document
4. inspect the existing implementation
5. identify dependencies
6. implement only the requested task
7. test the result
8. summarize changes
9. identify any unresolved issues
10. STOP and wait for further instructions

Do NOT silently implement future tasks.

---

## 36. Protect Existing Architecture

Do not casually change:

- public interfaces
- protocol formats
- database schema
- module boundaries
- package structure
- persistent identifiers

If a change becomes necessary:

explain why before making the change.

If the change is architecturally significant:

create an ADR under:

docs/ADR/

---

## 37. Architecture Decision Records

Important technical decisions live in `docs/ADR/`.

Any change to protocol format, database schema, module boundaries, public interfaces, persistent identifiers or Android compatibility handling requires an ADR **first**.

An accepted ADR is never edited silently. To change a decision, write a new ADR and mark the old one Superseded.

### 37.1 Current ADRs

| ADR | Subject |
|---|---|
| ADR-001 | Discovery strategy — pure UDP broadcast |
| ADR-002 | Transport and ports — two sockets, two receive threads |
| ADR-003 | Protocol wire format — 72-byte header, packet types, validation order |
| ADR-004 | Audio parameters, Opus settings, jitter buffer, AudioFocus policy |
| ADR-005 | Foreground service types, Android version compatibility, power locks |
| ADR-006 | Offline Japanese ASR engine and model delivery |

**Read the relevant ADRs before implementing any task that touches those areas.** Where an ADR and a `docs/` file disagree, the ADR wins and the doc must be corrected.

Each ADR documents: context, problem, options considered, chosen solution, rationale, consequences.

## 38. Code Quality

Production code must:

- compile
- be maintainable
- use consistent naming
- handle lifecycle correctly
- handle cancellation correctly
- avoid leaks
- avoid unnecessary allocations
- avoid blocking the main thread
- avoid long-running work on UI thread
- use structured concurrency appropriately

Do not leave fake implementations in production code.

Do not leave TODO stubs for required functionality.

Do not claim a feature is implemented if it is only mocked.

---

## 39. Android Lifecycle

Lifecycle correctness is especially important.

Pay attention to:

- Activity lifecycle
- Service lifecycle
- process death
- WiFi changes
- permission changes
- screen rotation
- background/foreground transitions
- app force-stop
- device reboot

The application must fail gracefully.

---

## 40. Privacy

All communication history and audio remain local.

No analytics service is required.

No cloud telemetry is required.

No audio should leave the local network except to the intended communication peer.

Do not collect unnecessary personal information.

---

## 41. Security Boundaries

The initial product is intended for trusted local networks.

Do not add unnecessary heavyweight authentication infrastructure.

However, protocol design should avoid blindly trusting malformed packets.

Validate:

- packet type
- protocol version
- packet length
- sequence numbers
- payload size
- sender information

Malformed network packets must not crash the application.

---

## 42. Documentation

Important project knowledge belongs in documentation.

Use:

docs/

for:

- requirements
- architecture
- protocol
- UI/UX
- data model
- development plan
- test plan
- release checklist
- architecture decisions

Use:

tasks/

for individual implementation tasks.

Do not place large design documents inside CLAUDE.md.

CLAUDE.md contains project-wide behavioral rules.

---

## 43. Git Discipline and Repository Sync

Remote:

```text
https://github.com/jyh9521/SaikaiPTT
```

Branch: `main`

### 43.1 Commit after every piece of work

Every completed task ends with a commit. Never leave finished work
uncommitted — the point of committing per task is to be able to roll back
to any known-good state.

Message pattern:

```text
feat: ...
fix: ...
refactor: ...
test: ...
docs: ...
chore: ...
```

Rules:

- One task, one commit. Do not mix unrelated tasks.
- The subject line says what changed; the body says **why**, especially for
  decisions that a future reader would otherwise have to re-derive.
- Never commit secrets, keystores, tokens, or ASR model files.
- Leave the working tree clean at the end of a task.

### 43.2 Push

Push to `origin main` after each task's commit, so the remote is never more
than one task behind local.

If the environment cannot authenticate to GitHub (a sandbox without the
user's credentials), still commit locally, then **tell the user explicitly
that the commits are local and need pushing**, and give them the command.
Never silently leave work unpushed and unmentioned.

### 43.3 Tags

Tag the completion of each phase (`06_DevelopmentPlan.md §3`):

```text
phase-0-baseline
phase-1-skeleton
phase-2-core
...
```

and every release candidate:

```text
v0.1.0-rc1
```

Tags are the cheap rollback points between the fine-grained per-task commits.

## 44. Final Priority

When requirements conflict, use this priority:

1. Safety and crash prevention
2. Stable real-time PTT communication
3. Android compatibility
4. Background reliability
5. Low power consumption
6. Low resource usage
7. Maintainable architecture
8. Extensibility
9. Additional features

The product must remain a lightweight LAN walkie-talkie.

Do not allow the project to evolve into a general-purpose chat application without explicit approval.