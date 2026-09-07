package com.saikai.ptt.core.protocol

import com.saikai.ptt.core.domain.DeviceId

/**
 * Fixed identities and byte helpers shared by the protocol tests.
 *
 * The ids are literals rather than `random()` so that a byte-for-byte
 * expectation can be written out in full: the layout test is only worth
 * anything if every byte in it was chosen, not observed.
 */
internal object ProtocolFixtures {

    val SENDER: DeviceId = DeviceId.parse("00112233-4455-6677-8899-aabbccddeeff")!!
    val TARGET: DeviceId = DeviceId.parse("0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0")!!
    val SESSION: SessionId = SessionId.parse("12345678-9abc-def0-1234-56789abcdef0")!!

    const val TIMESTAMP: Long = 0x0102030405060708L

    fun bytes(vararg values: Int): ByteArray =
        ByteArray(values.size) { values[it].toByte() }

    /** A valid encoded datagram for [type], for tests that then corrupt one field. */
    fun datagram(type: PacketType): ByteArray = Packet.of(
        type = type,
        senderDeviceId = SENDER,
        payload = payloadFor(type),
        timestampMillis = TIMESTAMP,
        targetDeviceId = TARGET,
        sessionId = SESSION,
        sequenceNumber = 7,
    ).toBytes()

    /** A representative payload for every implemented type. */
    fun payloadFor(type: PacketType): PacketPayload = when (type) {
        PacketType.DISCOVERY,
        PacketType.DISCOVERY_RESPONSE,
        PacketType.HEARTBEAT,
        -> PresencePayload(PeerState.IDLE, 45821, "Kenji")

        PacketType.PING,
        PacketType.PONG,
        PacketType.VOICE_ACCEPT,
        PacketType.BUSY,
        -> EmptyPayload

        PacketType.VOICE_START -> VoiceStartPayload(AudioCodec.OPUS, 16_000, 20, "Kenji")
        PacketType.VOICE_DATA -> VoiceDataPayload(ByteArray(53) { (it * 7).toByte() })
        PacketType.VOICE_END -> VoiceEndPayload(finalDataSequence = 251, frameCount = 250)
        PacketType.SESSION_TERMINATE ->
            SessionTerminatePayload(TerminationReason.INTERRUPTED_BY_PEER)

        PacketType.FORCE_INTERRUPT,
        PacketType.ERROR,
        PacketType.GOODBYE,
        PacketType.CAPABILITIES,
        -> error("$type is reserved in v1 and has no payload")
    }
}
