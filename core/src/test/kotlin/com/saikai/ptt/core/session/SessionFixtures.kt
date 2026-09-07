package com.saikai.ptt.core.session

import com.saikai.ptt.core.domain.DeviceId
import com.saikai.ptt.core.domain.Peer
import com.saikai.ptt.core.domain.PeerEndpoint
import com.saikai.ptt.core.domain.PresenceState
import com.saikai.ptt.core.protocol.AudioCodec
import com.saikai.ptt.core.protocol.SessionId
import com.saikai.ptt.core.protocol.TerminationReason
import com.saikai.ptt.core.protocol.VoiceStartPayload

/** Every control packet the machine asked for, in order, as readable strings. */
internal class RecordingSignals(private val sendSucceeds: () -> Boolean = { true }) :
    SessionSignals {

    val sent = mutableListOf<String>()

    @Synchronized
    private fun record(entry: String): Boolean {
        val ok = sendSucceeds()
        if (ok) sent += entry
        return ok
    }

    @Synchronized
    fun snapshot(): List<String> = sent.toList()

    fun count(prefix: String): Int = snapshot().count { it.startsWith(prefix) }

    override suspend fun voiceStart(
        sessionId: SessionId,
        target: DeviceId,
        endpoint: PeerEndpoint,
        localName: String,
    ): Boolean = record("VOICE_START $sessionId -> $target as $localName")

    override suspend fun voiceAccept(
        sessionId: SessionId,
        target: DeviceId,
        endpoint: PeerEndpoint,
    ): Boolean = record("VOICE_ACCEPT $sessionId -> $target")

    override suspend fun busy(target: DeviceId, endpoint: PeerEndpoint): Boolean =
        record("BUSY -> $target")

    override suspend fun voiceEnd(
        sessionId: SessionId,
        target: DeviceId,
        endpoint: PeerEndpoint,
        finalDataSequence: Int,
        frameCount: Int,
    ): Boolean = record("VOICE_END $sessionId -> $target")

    override suspend fun sessionTerminate(
        sessionId: SessionId,
        target: DeviceId,
        endpoint: PeerEndpoint,
        reason: TerminationReason,
    ): Boolean = record("TERMINATE $sessionId -> $target ($reason)")
}

/** A microphone and a speaker that only count how often they were opened. */
internal class FakeAudio(
    var microphoneAvailable: Boolean = true,
    var speakerAvailable: Boolean = true,
) : VoiceAudio {
    var capturing = false
        private set
    var playing = false
        private set
    var captureStarts = 0
        private set

    override suspend fun startCapture(): Boolean {
        if (!microphoneAvailable) return false
        captureStarts++
        capturing = true
        return true
    }

    override suspend fun stopCapture() {
        capturing = false
    }

    override suspend fun startPlayback(sessionId: SessionId): Boolean {
        if (!speakerAvailable) return false
        playing = true
        return true
    }

    override suspend fun stopPlayback() {
        playing = false
    }
}

internal object SessionFixtures {

    fun peer(name: String, address: String = "192.168.1.20"): Peer = Peer(
        deviceId = DeviceId.random(),
        userName = name,
        endpoint = PeerEndpoint(address, 45_821),
        protocolVersion = 1,
        state = PresenceState.ONLINE,
        lastSeenMillis = 0L,
    )

    /** A VOICE_START payload this build can actually decode. */
    fun voiceStart(userName: String): VoiceStartPayload = VoiceStartPayload(
        codec = AudioCodec.OPUS,
        sampleRateHz = 16_000,
        frameMillis = 20,
        userName = userName,
    )
}
