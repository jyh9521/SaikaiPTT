# SaikaiPTT Master Prompt

> Project: SaikaiPTT
>
> Application name: 西海PTT
>
> Repository: jyh9521/SaikaiPTT
>
> Document status: Master Specification
>
> This document defines the highest-level product and engineering goals of SaikaiPTT.

---

# 1. Project Overview

SaikaiPTT is a lightweight Android application that provides real-time Push-To-Talk communication between devices connected to the same local WiFi network.

The product is designed to behave like a traditional digital walkie-talkie rather than a chat application.

The core communication principle is:

> Select one target device, press and hold to speak, release to stop.

Communication must remain entirely within the local network.

Internet connectivity must NOT be required for core communication.

The application must work without:

- account registration
- login
- cloud services
- remote servers
- Internet connectivity

---

# 2. Product Positioning

SaikaiPTT should be positioned as:

> Professional Offline LAN Push-To-Talk

The product should prioritize:

- reliability
- simplicity
- low latency
- low resource usage
- low power consumption
- long-term background operation
- compatibility with old Android devices

SaikaiPTT must NOT evolve into a general-purpose messaging application unless explicitly requested.

---

# 3. Core User Experience

The expected user experience is similar to a traditional walkie-talkie.

Basic flow:

1. Launch SaikaiPTT.
2. Select or create a local user name.
3. App automatically discovers other SaikaiPTT devices on the same WiFi.
4. User selects exactly one target device.
5. User presses and holds the PTT button.
6. Voice is transmitted to the selected device.
7. The receiver immediately hears the audio.
8. Releasing the PTT button ends transmission.
9. The communication is recorded locally.
10. The recording can later be replayed.
11. The recording may be processed locally to generate Japanese subtitles.

The receiving user does not need to accept an incoming call.

---

# 4. Mandatory Design Principle: One-to-One Communication

SaikaiPTT is a point-to-point communication system.

It is NOT a voice broadcast system.

It is NOT a group walkie-talkie system.

It is NOT a voice chat room.

One PTT session always has:

- one sender
- one receiver

Voice data must be sent directly to the selected peer.

Network discovery may use broadcast or multicast.

Actual voice traffic MUST be unicast.

---

# 5. Offline-First Architecture

Core functionality must work without Internet access.

Required core functions:

- peer discovery
- peer presence
- PTT
- voice transmission
- voice reception
- communication history
- local playback
- local speech recognition

All core communication must remain functional when the device has no Internet connection.

No cloud dependency may be introduced into the core architecture.

---

# 6. Target Android Versions

Minimum supported Android version:

Android 11 / API 30

Target:

Latest stable Android SDK available during development.

Supported environment:

Android 11 through current Android releases.

The application must support both:

- low-end legacy Android devices
- modern flagship devices

Reference low-end device:

MediaTek P22-class hardware
with approximately 4 GB RAM.

Reference modern device class:

Samsung Galaxy S26-class flagship hardware.

Low-end compatibility is a major product requirement.

---

# 7. Performance Philosophy

Performance is a first-class product requirement.

SaikaiPTT must remain lightweight even when the application is running continuously in the background.

Priority:

1. reliable communication
2. low latency
3. low power usage
4. low CPU usage
5. low memory usage

Do not sacrifice communication reliability simply to reduce a benchmark number.

At the same time, do not introduce unnecessary background work.

Avoid:

- busy loops
- unnecessary polling
- aggressive wake locks
- continuous network scanning
- unnecessary allocations
- heavy animation
- unnecessary third-party libraries

---

# 8. Background Operation

SaikaiPTT must support long-term background operation.

Foreground Service should be used where appropriate.

The application must be able to receive PTT communication while it is not in the foreground, provided the user has enabled the required system permissions and background operation.

The application may maintain a persistent notification indicating that LAN communication is active.

Battery optimization guidance should be provided to the user when necessary.

---

# 9. Incoming PTT Behavior

Incoming PTT communication must be automatic.

The receiver does not need to:

- accept
- answer
- confirm
- tap an incoming-call button

If the application is in the foreground:

- automatically show the current PTT communication
- show the sender name
- immediately play the voice

If the application is in the background:

- the background communication service receives the voice
- the overlay indicator changes state
- the indicator changes from green to red
- the sender name may be displayed
- tapping the overlay opens the relevant PTT screen

The system should behave as closely as possible to a physical walkie-talkie.

---

# 10. Busy Mode

The default communication mode is Busy Mode.

If the target is already communicating with another peer:

the new request must be rejected.

The caller should receive a clear BUSY response.

The existing communication must not be interrupted.

---

# 11. Force Interrupt Mode

SaikaiPTT may provide an optional Force Interrupt mode.

Default:

disabled.

When enabled:

a new incoming PTT request is allowed to interrupt an existing PTT session.

This feature is useful for:

- factories
- warehouses
- security
- property management
- urgent operational communication

The feature must be explicit and user-configurable.

---

# 12. Device Identity

Every SaikaiPTT installation must have a persistent unique Device ID.

Generate a UUID v4 during initial setup.

Store it locally.

The Device ID should remain stable across application restarts and normal network changes.

Do NOT use:

- MAC address
- IMEI
- hardware serial number
- other restricted hardware identifiers

The Device ID identifies the installation.

The current IP address identifies the current network endpoint.

These concepts must remain separate.

---

# 13. Local User Identity

SaikaiPTT supports locally stored user names.

A device may contain multiple local user names.

The user must be able to:

- create a name
- rename a name
- delete a name
- switch the active name

A user name is local configuration.

Remote devices do not need to have that name stored locally.

When communication starts, the current user name should be transmitted as part of the protocol metadata.

---

# 14. Peer Discovery

SaikaiPTT must automatically discover compatible peers on the same local WiFi network.

The user should not have to manually enter:

- IP addresses
- ports
- hostnames

Preferred discovery mechanisms:

- Android NSD / mDNS
- UDP broadcast if required

Discovery must be separated from:

- heartbeat
- voice transport
- communication session management

Discovery must not depend on the Internet.

---

# 15. Presence and Heartbeat

Discovery answers:

> Which devices exist?

Heartbeat answers:

> Which discovered devices are still online?

These responsibilities must remain separate.

The system should expose clear peer states such as:

- discovered
- online
- offline
- busy
- communicating

Heartbeat frequency must be conservative enough to minimize power consumption.

---

# 16. Automatic Network Recovery

Network changes must be handled automatically.

Example:

WiFi connection lost:

- stop active network transmission
- mark peers unavailable
- preserve application state
- do not crash

WiFi connection restored:

- restart discovery
- restart heartbeat
- refresh peer state
- rebuild current network endpoints
- continue normal operation

The user should not need to restart the app manually.

---

# 17. Voice Communication

The main communication model is half-duplex Push-To-Talk.

Press and hold:

Start transmitting.

Release:

Stop transmitting.

The audio path should prioritize low latency.

Recommended codec:

Opus.

A replaceable transport/audio architecture must be used so that implementation details can evolve without changing UI/business logic.

---

# 18. Voice Recording

Completed PTT sessions must be stored locally.

Each communication should produce a local communication history record.

Audio should be stored in a compressed format such as:

- Opus preferred
- AAC fallback if required

Do not store persistent PCM audio by default.

Recording and communication metadata should be stored separately.

---

# 19. Communication History

Communication History is a major feature.

Each PTT session should create a history entry containing information such as:

- record ID
- remote Device ID
- remote user name
- direction
- timestamp
- duration
- local audio path
- transcript
- recognition status
- read/unread state
- favorite state

The user must be able to browse previous communications.

---

# 20. Audio Playback

The user must be able to replay historical recordings.

At minimum:

- play
- pause
- resume
- stop

Playback must not interfere with the PTT service.

History must remain useful even when speech recognition fails.

---

# 21. Offline Japanese Speech Recognition

SaikaiPTT may perform local speech recognition after a PTT session ends.

Requirements:

- completely offline
- no cloud ASR
- no audio upload
- Japanese language support
- transcription performed after the PTT segment ends

The speech recognition subsystem must not interfere with live voice communication.

Voice communication has higher priority than transcription.

Recommended engine:

Vosk or another suitable lightweight offline Japanese ASR implementation.

Speech recognition is not required to operate in real time.

---

# 22. Transcript and History Integration

After the recording is saved:

1. communication record is created
2. local transcription is scheduled
3. transcription runs in the background
4. transcript is stored locally
5. history UI is updated

Possible states:

- pending
- processing
- completed
- failed

Recognition failure must not invalidate the recording.

The user must still be able to replay the audio.

---

# 23. History Search

History should support:

- remote user name search
- transcript text search
- chronological browsing

The system should allow the user to quickly find previous communications.

---

# 24. Favorite Records

Users should be able to mark important communication records as favorites.

Favorite records should be protected from automatic cleanup.

---

# 25. Unread Records

Received communications may have an unread state.

Unread records should be visually identifiable.

Viewing the record should mark it as read.

This prevents important communications from being missed.

---

# 26. Automatic History Cleanup

Users should be able to configure automatic history retention.

Suggested values:

- 1 day
- 3 days
- 7 days
- 30 days
- forever

When a record is deleted:

the associated local audio file should also be deleted.

The cleanup process must avoid orphaned audio files.

---

# 27. Floating Overlay

SaikaiPTT should support an optional system overlay.

Normal state:

green.

Incoming PTT state:

red.

The overlay may display the remote user's current name.

Example:

Normal:

green indicator

Incoming:

red indicator + user name

Tapping the overlay should open the relevant application screen.

The overlay must remain lightweight.

---

# 28. Internationalization

Internationalization is mandatory.

Default language:

Japanese.

Supported languages:

- Japanese (ja)
- Simplified Chinese (zh-CN)
- English (en)
- Burmese (my)
- Bengali (bn)

All user-facing strings must be externalized.

Never hard-code user-facing text inside business logic.

Adding another language later must not require changes to business logic.

Localized content includes:

- application UI
- settings
- notifications
- overlay
- system guidance
- errors
- empty states
- busy messages
- status messages
- history interface
- accessibility labels

---

# 29. Local Data Storage

Use DataStore for lightweight settings.

Examples:

- Device ID
- active user name
- stored names
- language
- feature settings
- battery/background preferences

Use Room for structured communication history.

Audio files must remain outside the Room database.

Room should store metadata and local file references.

---

# 30. Communication Protocol

The network protocol must be versioned.

Messages should have a structured packet layout containing concepts such as:

- protocol version
- packet type
- device ID
- user name
- timestamp
- sequence number
- payload

Initial packet types should include:

- DISCOVERY
- HEARTBEAT
- PING
- PONG
- VOICE_START
- VOICE_DATA
- VOICE_END
- BUSY
- FORCE_INTERRUPT

The protocol must be extensible.

Future packet types should be addable without breaking the protocol architecture.

Do not implement unrelated future features merely because the protocol supports them.

---

# 31. Security and Input Validation

SaikaiPTT is initially designed for trusted local networks.

No heavyweight authentication infrastructure is required for the initial product.

However, all received packets must be validated.

Validate at minimum:

- protocol version
- packet type
- packet size
- payload limits
- sequence information
- sender information

Malformed packets must not crash the application.

---

# 32. Error Handling

The application should use structured errors.

Examples:

- NETWORK_DISCONNECTED
- PEER_OFFLINE
- TARGET_BUSY
- PERMISSION_DENIED
- MICROPHONE_UNAVAILABLE
- AUDIO_INITIALIZATION_FAILED
- DISCOVERY_FAILED
- SEND_FAILED
- RECEIVE_FAILED
- SERVICE_START_FAILED

Errors should be predictable and testable.

Do not use arbitrary exception message strings as application logic.

---

# 33. Logging

The project must provide a centralized logging system.

Suggested categories:

- lifecycle
- network
- discovery
- heartbeat
- protocol
- audio
- service
- storage
- permissions
- speech recognition

Development builds should allow detailed logging.

Release builds should disable verbose debugging logs by default.

Logs must not contain unnecessary private information.

Do not log raw audio payloads.

---

# 34. Configuration

Avoid magic numbers.

Centralize configurable values such as:

- heartbeat interval
- peer timeout
- discovery settings
- network ports
- audio parameters
- retry counts
- protocol version
- logging behavior
- history retention defaults

Changing a configuration value should not require hunting through unrelated source files.

---

# 35. Architecture Principles

Preferred architecture:

MVVM + UseCase + Repository.

High-level flow:

UI
→ ViewModel
→ UseCase
→ Repository
→ Infrastructure

Core subsystems must communicate through appropriate interfaces.

UI must not directly manage:

- UDP sockets
- AudioRecord
- AudioTrack
- database internals

The Foreground Service must not become a container for all application logic.

---

# 36. Core Module Responsibilities

Suggested responsibilities:

DiscoveryManager
- device discovery

HeartbeatManager
- peer presence

ConnectionManager
- communication session state

VoiceManager
- voice transmission/reception

ProtocolManager
- packet encoding/decoding

HistoryRepository
- communication history

SpeechRecognizer
- offline transcription

OverlayController
- system overlay state

AudioRecorder
- local recording

AudioPlayer
- playback

These components should remain independently testable.

---

# 37. Extensibility

Interfaces should be used where future implementation changes are likely.

Examples:

VoiceTransport
AudioRecorder
AudioPlayer
SpeechRecognizer
PeerDiscovery
HistoryRepository

The application should be able to replace a subsystem without rewriting unrelated layers.

Example:

The voice transport could later be changed from UDP to another transport without redesigning the UI.

---

# 38. UI Philosophy

SaikaiPTT must look and feel like a digital walkie-talkie.

Visual principles:

- simple
- clear
- fast
- minimal

Primary visual identity:

green

Incoming PTT:

red

Avoid excessive:

- animation
- decoration
- effects
- navigation complexity

The PTT control should be the primary interaction element.

---

# 39. Permissions and System Integration

The application may require:

- microphone permission
- notification permission
- foreground service support
- overlay permission
- battery optimization guidance
- network access

The user should be guided through required system settings.

System-specific behavior must be handled defensively because Android vendors may implement background restrictions differently.

---

# 40. Privacy

All communication records are local.

Audio recordings remain local.

Transcripts remain local.

No cloud synchronization is required.

No analytics backend is required for core functionality.

No audio should be uploaded outside the LAN.

---

# 41. Dependency Policy

Third-party dependencies must be minimized.

Before adding a dependency:

1. check whether Android SDK or Jetpack already provides equivalent functionality
2. assess maintenance status
3. assess compatibility with Android 11+
4. assess memory overhead
5. assess APK size impact
6. assess long-term maintainability

Do not introduce a library solely for convenience when a lightweight native solution is sufficient.

---

# 42. Testing Philosophy

Testing must cover both software logic and real-device behavior.

Unit tests should cover:

- Device ID persistence
- configuration
- packet encoding
- packet decoding
- heartbeat
- peer timeout
- session state
- busy state
- force interrupt
- history persistence
- cleanup
- transcript state

Real-device testing is required for:

- WiFi discovery
- UDP transmission
- audio input
- audio playback
- background behavior
- overlay
- reconnect
- low-end hardware compatibility

---

# 43. Development Method

The project must be developed incrementally.

Do not generate the complete production application in one step.

Each implementation task should:

1. have a clear scope
2. identify dependencies
3. modify only the required components
4. preserve previously completed functionality
5. include appropriate tests
6. be reviewed before moving forward

After each task:

- build
- test
- inspect changes
- review architecture impact
- document important decisions
- stop

Do not silently continue into unrelated tasks.

---

# 44. Architecture Decision Records

Important technical decisions should be recorded as ADR documents.

Recommended location:

docs/ADR/

Examples:

ADR-001-Discovery.md
ADR-002-VoiceTransport.md
ADR-003-AudioCodec.md
ADR-004-SpeechRecognition.md

Each ADR should document:

- context
- problem
- alternatives
- selected solution
- rationale
- consequences

---

# 45. Documentation Structure

The project documentation should be divided by topic.

Expected documents:

docs/00_MasterPrompt.md
docs/01_PRD.md
docs/02_Architecture.md
docs/03_Protocol.md
docs/04_UI_UX.md
docs/05_DataModel.md
docs/06_DevelopmentPlan.md
docs/07_TestPlan.md
docs/08_ReleaseChecklist.md

Implementation tasks belong under:

tasks/

Claude should consult the relevant documents before implementing a task.

---

# 46. Development Priorities

The following priorities are mandatory:

1. reliable PTT communication
2. low latency
3. Android compatibility
4. background reliability
5. low power consumption
6. low CPU/memory usage
7. maintainable architecture
8. extensibility
9. additional features

Features must not compromise the core PTT experience.

---

# 47. Out of Scope

The initial product does NOT require:

- login
- registration
- cloud synchronization
- Internet chat
- public servers
- social features
- friend systems
- group chat
- file transfer
- image sharing
- video communication
- GPS
- maps
- Bluetooth communication
- NFC
- advertisements
- in-app purchases

These must not be introduced without explicit product approval.

---

# 48. Long-Term Product Direction

SaikaiPTT should remain:

> a lightweight professional LAN digital walkie-talkie

The application may gain additional capabilities over time, but every future capability must be evaluated against:

- low resource usage
- reliability
- privacy
- offline operation
- maintainability

New functionality must not turn the application into an unnecessarily heavy messaging platform.

---

# 49. Definition of Success

SaikaiPTT is successful when:

A user can install it on an Android device, create a local name, connect the device to the same WiFi network as another SaikaiPTT device, automatically discover that peer, select the peer, hold the PTT button, and immediately communicate with that device.

The same communication must remain usable while the receiving application is operating in the background.

The conversation should then be available in local communication history, including:

- timestamp
- sender
- recording
- playback
- transcript when local Japanese speech recognition is enabled
- unread state
- favorite state

All of this should work without requiring an Internet connection.

---

# 50. Final Product Rule

When making any design decision, always ask:

> Does this make SaikaiPTT more reliable, more lightweight, more compatible, or more useful as a digital LAN walkie-talkie?

If the answer is no:

do not add it.

The core philosophy of SaikaiPTT is:

> Simple outside.  
> Solid inside.  
> Local by design.  
> Lightweight by necessity.  
> Reliable enough to behave like a real walkie-talkie.