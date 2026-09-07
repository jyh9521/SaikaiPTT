package com.saikai.ptt.core.config

import com.saikai.ptt.core.logger.LogCategory
import com.saikai.ptt.core.logger.LogLevel
import com.saikai.ptt.core.protocol.WireFormat
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Every tunable value in SaikaiPTT, in one place.
 *
 * `docs/01_PRD.md` section 34 and `.claude/CLAUDE.md` section 24 forbid magic
 * numbers scattered through the code: a heartbeat interval that appears in three
 * classes will eventually mean three different things.
 *
 * Two conventions carry most of the weight here:
 *
 * 1. Durations are [Duration], not `Int`. `heartbeatInterval = 5.seconds` cannot
 *    be misread as five milliseconds, and no call site has to remember a unit.
 * 2. Anything derivable is derived, never restated. Peer timeout follows from the
 *    heartbeat interval; audio frame size follows from sample rate and frame
 *    duration. Restating them lets one drift out of step with the other during a
 *    later tuning pass, which is exactly the kind of bug that shows up only on a
 *    slow device on a busy network.
 *
 * Every group validates its invariants in `init`. Failing at construction is far
 * cheaper than debugging an oversized UDP packet in the field.
 *
 * Config is a value, not a service: tests build variants with `copy()`.
 */
data class SaikaiConfig(
    val protocol: ProtocolConfig = ProtocolConfig(),
    val network: NetworkConfig = NetworkConfig(),
    val discovery: DiscoveryConfig = DiscoveryConfig(),
    val presence: PresenceConfig = PresenceConfig(),
    val session: SessionConfig = SessionConfig(),
    val audio: AudioConfig = AudioConfig(),
    val jitterBuffer: JitterBufferConfig = JitterBufferConfig(),
    val rateLimit: RateLimitConfig = RateLimitConfig(),
    val history: HistoryConfig = HistoryConfig(),
    val logging: LoggingConfig = LoggingConfig(),
) {
    init {
        // An Opus frame at the configured bitrate must fit the voice payload cap,
        // with headroom. Raising the bitrate without raising the cap would make
        // every frame fail validation at the receiver -- silence with no error.
        val expectedEncodedBytes =
            audio.opusBitrateBps * audio.frameDuration.inWholeMilliseconds / 8_000
        require(protocol.voiceDataMaxPayloadBytes >= expectedEncodedBytes * 2) {
            "voiceDataMaxPayloadBytes (${protocol.voiceDataMaxPayloadBytes}) leaves no " +
                "headroom for a ${expectedEncodedBytes}-byte frame at " +
                "${audio.opusBitrateBps} bps / ${audio.frameDuration}"
        }

        require(network.receiveBufferBytes >= protocol.maxDatagramBytes) {
            "Receive buffer (${network.receiveBufferBytes}) must hold the largest " +
                "datagram the protocol can produce (${protocol.maxDatagramBytes})"
        }
    }

    companion object {
        /**
         * The configuration for a given build type.
         *
         * Only logging differs. Timing, ports and audio parameters must be
         * identical between debug and release, otherwise the build that gets
         * tested is not the build that ships.
         */
        fun forBuild(isDebugBuild: Boolean): SaikaiConfig = SaikaiConfig(
            logging = if (isDebugBuild) LoggingConfig.debug() else LoggingConfig.release(),
        )
    }
}

/**
 * Wire-format constants. Normative source: `docs/ADR/ADR-003-Wire-Format.md`.
 *
 * These are not tuning knobs -- changing any of them changes the protocol and
 * requires a version bump and an ADR.
 *
 * The values come from [WireFormat], which is what the codec itself compiles
 * against. This class exists so the rest of the application can read them
 * through one configuration object, not so they can differ.
 */
data class ProtocolConfig(
    val version: Int = WireFormat.PROTOCOL_VERSION,
    /** ASCII "SKPT". Rejects other LAN traffic before any field is parsed. */
    val magic: ByteArray = WireFormat.magic(),
    val headerBytes: Int = WireFormat.HEADER_BYTES,
    val maxPayloadBytes: Int = WireFormat.MAX_PAYLOAD_BYTES,
    /** Voice payloads are capped far tighter than the general limit; see [maxDatagramBytes]. */
    val voiceDataMaxPayloadBytes: Int = WireFormat.MAX_VOICE_PAYLOAD_BYTES,
    val maxUserNameBytes: Int = WireFormat.MAX_USER_NAME_BYTES,
    /** UI-side limit. Both apply; the stricter one wins. */
    val maxUserNameCodePoints: Int = 24,
) {
    /** Largest datagram the protocol can produce. Must stay well under the 1500-byte MTU. */
    val maxDatagramBytes: Int get() = headerBytes + maxPayloadBytes

    /** Largest datagram a voice frame can produce: header + 400. */
    val maxVoiceDatagramBytes: Int get() = headerBytes + voiceDataMaxPayloadBytes

    init {
        require(magic.size == 4) { "Magic is a fixed 4 bytes" }
        require(voiceDataMaxPayloadBytes <= maxPayloadBytes) {
            "Voice payload limit cannot exceed the general payload limit"
        }
        require(maxDatagramBytes < MTU_BYTES) {
            "Datagrams must not fragment: $maxDatagramBytes >= $MTU_BYTES"
        }
        require(maxUserNameBytes >= maxUserNameCodePoints) {
            "A code point never encodes to less than one byte"
        }
    }

    // ByteArray gives data classes reference equality, which would make two
    // structurally identical configs compare unequal and break test assertions.
    override fun equals(other: Any?): Boolean =
        this === other || (other is ProtocolConfig &&
            version == other.version &&
            magic.contentEquals(other.magic) &&
            headerBytes == other.headerBytes &&
            maxPayloadBytes == other.maxPayloadBytes &&
            voiceDataMaxPayloadBytes == other.voiceDataMaxPayloadBytes &&
            maxUserNameBytes == other.maxUserNameBytes &&
            maxUserNameCodePoints == other.maxUserNameCodePoints)

    override fun hashCode(): Int {
        var result = version
        result = 31 * result + magic.contentHashCode()
        result = 31 * result + headerBytes
        result = 31 * result + maxPayloadBytes
        result = 31 * result + voiceDataMaxPayloadBytes
        result = 31 * result + maxUserNameBytes
        result = 31 * result + maxUserNameCodePoints
        return result
    }

    companion object {
        const val MTU_BYTES: Int = 1500
    }
}

/**
 * Sockets and ports. Normative source: `docs/ADR/ADR-002-Transport-And-Ports.md`.
 *
 * Two sockets on two threads: 50 voice packets a second must not queue behind
 * control-packet parsing and peer-table updates.
 */
data class NetworkConfig(
    /**
     * Fixed. A device that cannot bind this port cannot be discovered, so unlike
     * the voice port it may not drift silently.
     */
    val controlPort: Int = 45820,
    /** Floating; the port actually bound is advertised in discovery and heartbeat. */
    val voicePort: Int = 45821,
    /** Consecutive ports tried when one is already taken. */
    val portProbeAttempts: Int = 4,
    /** Preallocated and reused per receive thread; never allocated per packet. */
    val receiveBufferBytes: Int = 2048,
) {
    init {
        require(controlPort in 1024..65535) { "controlPort must be a non-privileged port" }
        require(voicePort in 1024..65535) { "voicePort must be a non-privileged port" }
        require(controlPort != voicePort) { "Control and voice must not share a port" }
        require(portProbeAttempts >= 1) { "At least one bind attempt is required" }
    }
}

/**
 * Discovery timing. Normative source: `docs/ADR/ADR-001-Discovery-Strategy.md`.
 */
data class DiscoveryConfig(
    /**
     * A single announcement is lost to a single dropped broadcast, and the two
     * devices then stay invisible to each other until the next heartbeat. Three
     * spread-out sends make that unlikely without being a burst.
     */
    val announceDelays: List<Duration> = listOf(
        Duration.ZERO,
        300.milliseconds,
        900.milliseconds,
    ),
) {
    init {
        require(announceDelays.isNotEmpty()) { "At least one announcement is required" }
        require(announceDelays.zipWithNext().all { (a, b) -> a < b }) {
            "Announcement delays must be strictly increasing"
        }
    }
}

/**
 * Presence timing. Normative source: `docs/03_Protocol.md` section 13.
 */
data class PresenceConfig(
    val heartbeatInterval: Duration = 5.seconds,
    /**
     * A peer is not declared offline for one lost packet. WiFi drops broadcasts
     * routinely, and a peer list that flickers is worse than one that lags.
     */
    val missedIntervalsBeforeOffline: Int = 3,
    val timeoutTolerance: Duration = 1.seconds,
    val evaluationInterval: Duration = 2.seconds,
) {
    /**
     * Derived, not stated: the relationship to [heartbeatInterval] is the point.
     * Retuning the interval must not silently leave the timeout at an old value.
     */
    val peerTimeout: Duration
        get() = heartbeatInterval * missedIntervalsBeforeOffline + timeoutTolerance

    init {
        require(heartbeatInterval > Duration.ZERO) { "heartbeatInterval must be positive" }
        require(missedIntervalsBeforeOffline >= 2) {
            "Fewer than two missed intervals makes a single lost packet look like an offline peer"
        }
        // What actually matters is how late an offline peer is noticed, and that
        // is bounded by the evaluation interval relative to the timeout -- not
        // relative to the heartbeat. Comparing against the heartbeat rejected
        // legitimate configurations: shortening the heartbeat to 1s for a test
        // gives a 4s timeout, which a 2s sweep detects perfectly well.
        require(evaluationInterval <= peerTimeout / 2) {
            "An offline peer would be noticed up to $evaluationInterval after a " +
                "$peerTimeout timeout; sweep at least twice per timeout window"
        }
    }
}

/**
 * PTT session timing. Normative source: `docs/ADR/ADR-003-Wire-Format.md` section 6.
 */
data class SessionConfig(
    val voiceStartRetry: Duration = 150.milliseconds,
    val voiceStartMaxRetries: Int = 2,
    val voiceStartTimeout: Duration = 500.milliseconds,
    /**
     * Capture starts the instant the button goes down and buffers locally until
     * the peer accepts, so the first syllable survives the handshake. Bounded
     * because an unbounded buffer would grow while a dead peer never answers.
     */
    val preRollBuffer: Duration = 500.milliseconds,
    /** A receiver stops waiting when VOICE_DATA and VOICE_END both stop arriving. */
    val idleTimeout: Duration = 3.seconds,
    /** Backstop against a stuck button or a misbehaving peer holding the channel. */
    val maxDuration: Duration = 5.minutes,
) {
    init {
        require(voiceStartMaxRetries >= 0) { "Retry count cannot be negative" }
        require(voiceStartRetry * (voiceStartMaxRetries + 1) <= voiceStartTimeout) {
            "All retries must fit inside the request timeout, otherwise the last one " +
                "is sent and immediately abandoned"
        }
        require(preRollBuffer >= voiceStartTimeout) {
            "The pre-roll buffer must cover the whole handshake window, or audio " +
                "captured while waiting for acceptance is discarded"
        }
        require(idleTimeout < maxDuration) { "idleTimeout must be shorter than maxDuration" }
    }
}

/**
 * Audio parameters. Normative source: `docs/ADR/ADR-004-Audio-Params.md`.
 *
 * Fixed by the protocol version, not negotiated: a peer that used different
 * values would produce audio the other side cannot decode.
 */
/**
 * Which microphone input to ask the platform for.
 *
 * Named here rather than as a platform constant so that `core` stays free of
 * Android types and the preference can be expressed as configuration.
 */
enum class AudioCaptureSource {
    /**
     * The platform's voice pipeline: acoustic echo cancellation, noise
     * suppression and automatic gain, which is what makes speech intelligible in
     * a warehouse or a factory.
     */
    VOICE_COMMUNICATION,

    /** The raw microphone. Some devices and ROMs will not open the first. */
    MIC,
}

data class AudioConfig(
    val sampleRateHz: Int = 16_000,
    val channelCount: Int = 1,
    val frameDuration: Duration = 20.milliseconds,
    val opusBitrateBps: Int = 20_000,
    /** Low, on purpose: the reference device is an MTK P22. */
    val opusComplexity: Int = 3,
    /** In-band forward error correction absorbs isolated packet loss. */
    val opusForwardErrorCorrection: Boolean = true,
    /**
     * Off. Discontinuous transmission saves bandwidth the LAN does not need,
     * while making "silent" and "stopped sending" ambiguous in a half-duplex
     * protocol.
     */
    val opusDiscontinuousTransmission: Boolean = false,
    /**
     * The packet loss the encoder should protect against, as a percentage.
     *
     * Not decoration: libopus only spends bits on the in-band redundant copy
     * when it believes packets are being lost, so with this at zero the FEC
     * switch above is on and emits nothing whatsoever. Ten percent is generous
     * for a quiet LAN and costs a few hundred bits a second at 20 kbps, which is
     * the right trade for the environment this product runs in -- a warehouse
     * where the access point is two walls away.
     */
    val opusExpectedPacketLossPercent: Int = 10,
    val bytesPerSample: Int = 2,
    /**
     * Capture inputs to try, in order (`docs/ADR/ADR-004` section 2).
     *
     * A list rather than a preference plus a fallback, because that is what it
     * is, and because a device that refuses both should fail with one clear
     * error rather than through two nested branches.
     */
    val captureSources: List<AudioCaptureSource> = listOf(
        AudioCaptureSource.VOICE_COMMUNICATION,
        AudioCaptureSource.MIC,
    ),
    /**
     * Multiple of the frame size the capture buffer must reach, on top of the
     * platform minimum. Four frames is 80 ms of slack: enough that a scheduling
     * hiccup on a low-end device does not overrun the buffer, short enough that
     * the latency it can hide stays inside the end-to-end budget.
     */
    val captureBufferFrames: Int = 4,
    /**
     * Frames the output device must be able to hold, over the platform minimum.
     *
     * Six, against the jitter buffer's maximum depth of ten: the buffer decides
     * how much delay to accept, and this only has to keep the device from
     * running dry between one hand-off and the next. Sizing it to the jitter
     * buffer instead would add that latency twice.
     */
    val playbackBufferFrames: Int = 6,
) {
    /** 320 samples at 16 kHz / 20 ms. */
    val frameSizeSamples: Int
        get() = (sampleRateHz * frameDuration.inWholeMicroseconds / 1_000_000L).toInt()

    /** 640 bytes of PCM per frame. */
    val frameSizeBytes: Int get() = frameSizeSamples * bytesPerSample * channelCount

    /** 50 packets per second per direction. */
    val packetsPerSecond: Int get() = (1_000L / frameDuration.inWholeMilliseconds).toInt()

    init {
        require(channelCount == 1) { "PTT is mono; stereo would double bandwidth for nothing" }
        require(frameDuration.inWholeMilliseconds > 0) { "frameDuration must be at least 1 ms" }
        require(1_000L % frameDuration.inWholeMilliseconds == 0L) {
            "Frame duration must divide one second evenly"
        }
        require(opusComplexity in 0..10) { "Opus complexity is 0..10" }
        require(opusExpectedPacketLossPercent in 0..100) {
            "Expected packet loss is a percentage"
        }
        require(!opusForwardErrorCorrection || opusExpectedPacketLossPercent > 0) {
            "In-band FEC with an expected loss of zero emits no redundancy at all; " +
                "either raise the expectation or turn FEC off and say so"
        }
        require(frameSizeSamples > 0) { "Derived frame size must be positive" }
        require(captureSources.isNotEmpty()) { "At least one capture source must be allowed" }
        require(captureBufferFrames >= 2) {
            "A capture buffer of one frame leaves no slack for a late reader"
        }
        require(playbackBufferFrames >= 2) {
            "A playback buffer of one frame runs dry between hand-offs"
        }
    }

    /**
     * The capture buffer to request: the platform minimum, or four frames,
     * whichever is larger (`docs/ADR/ADR-004` section 2).
     *
     * The platform minimum is a floor, not a recommendation -- on some devices
     * it is a single frame, which overruns the moment the reader is descheduled.
     */
    fun captureBufferBytes(platformMinimumBytes: Int): Int =
        maxOf(platformMinimumBytes, captureBufferFrames * frameSizeBytes)

    /** The output buffer to request, on the same principle as [captureBufferBytes]. */
    fun playbackBufferBytes(platformMinimumBytes: Int): Int =
        maxOf(platformMinimumBytes, playbackBufferFrames * frameSizeBytes)
}

/**
 * Jitter buffer depth. Normative source: `docs/ADR/ADR-004-Audio-Params.md` section 4.
 */
data class JitterBufferConfig(
    /** Playback waits for this many frames so reordering has room to resolve. */
    val startThresholdFrames: Int = 3,
    val targetFrames: Int = 3,
    /**
     * Above this the buffer is trading latency for smoothness badly: a
     * walkie-talkie that answers late feels broken even if every frame arrives.
     */
    val maxFrames: Int = 10,
) {
    fun startThreshold(audio: AudioConfig): Duration = audio.frameDuration * startThresholdFrames
    fun maxDepth(audio: AudioConfig): Duration = audio.frameDuration * maxFrames

    init {
        require(startThresholdFrames >= 1) { "Playback needs at least one frame to start" }
        require(targetFrames <= maxFrames) { "Target depth cannot exceed the maximum" }
        require(startThresholdFrames <= maxFrames) { "Start threshold cannot exceed the maximum" }
    }
}

/**
 * Defences against a malfunctioning or hostile device on the LAN.
 * Normative source: `docs/03_Protocol.md` section 45.
 *
 * The product assumes a trusted network, but "trusted" must not mean that one
 * broken device can spin the CPU, exhaust memory or flood the log.
 */
data class RateLimitConfig(
    val maxControlPacketsPerSecondPerSource: Int = 50,
    val maxInvalidPacketsPerSecondPerSource: Int = 20,
    /** How long a source is ignored after exceeding a limit. */
    val silenceDuration: Duration = 5.seconds,
    val maxPeers: Int = 64,
    /** Per issue kind, so a packet storm cannot become a log storm. */
    val maxLogEntriesPerSecondPerKind: Int = 1,
) {
    init {
        require(maxControlPacketsPerSecondPerSource > 0) { "Rate limit must be positive" }
        require(maxInvalidPacketsPerSecondPerSource <= maxControlPacketsPerSecondPerSource) {
            "Invalid packets are a subset of received packets"
        }
        require(maxPeers > 0) { "Peer table must hold at least one peer" }
    }
}

/**
 * Transcription tuning. Normative source: `docs/05_DataModel.md`.
 *
 * Retention is not here. It is a user setting with a stored default, not a
 * tunable, so it lives with the other settings as
 * [com.saikai.ptt.core.domain.HistoryRetention].
 */
data class HistoryConfig(
    /** Bounded: a recording that cannot be transcribed must not be retried forever. */
    val asrMaxRetries: Int = 3,
) {
    init {
        require(asrMaxRetries in 0..10) { "Unbounded ASR retries would burn battery on a bad file" }
    }
}

/** Logging thresholds. The only group that differs between build types. */
data class LoggingConfig(
    val minLevel: LogLevel = LogLevel.DEBUG,
    val enabledCategories: Set<LogCategory> = LogCategory.entries.toSet(),
    /** Guards against a per-packet log path becoming a performance problem. */
    val maxEntriesPerSecondPerKind: Int = 1,
) {
    /**
     * Errors bypass the category filter.
     *
     * Categories exist to suppress volume -- per-packet and per-frame logging
     * that would cost battery and bury everything else. An error is neither:
     * it is rare and it is the reason someone is reading the log. Dropping
     * ERROR from NETWORK in release would mean a field report of "it stopped
     * receiving" arrives with every network error already discarded.
     *
     * [minLevel] still applies, so a config can silence errors deliberately.
     */
    fun isEnabled(level: LogLevel, category: LogCategory): Boolean =
        level.isAtLeast(minLevel) &&
            (level == LogLevel.ERROR || category in enabledCategories)

    companion object {
        fun debug(): LoggingConfig = LoggingConfig(
            minLevel = LogLevel.DEBUG,
            enabledCategories = LogCategory.entries.toSet(),
        )

        /**
         * Release drops DEBUG entirely and narrows to the categories that help
         * diagnose a field report. `docs/01_PRD.md` section 48.
         */
        fun release(): LoggingConfig = LoggingConfig(
            minLevel = LogLevel.INFO,
            enabledCategories = LogCategory.RELEASE_DEFAULT,
        )
    }
}
