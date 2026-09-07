package com.saikai.ptt.core.protocol

import com.saikai.ptt.core.domain.DeviceId
import com.saikai.ptt.core.domain.LocalPresence

/**
 * Turns what this device is into the payload DISCOVERY, DISCOVERY_RESPONSE and
 * HEARTBEAT all share (`docs/03_Protocol.md` section 9).
 *
 * One place, because the three packet types are the same announcement sent for
 * three different reasons, and discovery and presence are separate components
 * that must not drift into announcing different things about the same device.
 */
object PresenceAnnouncement {

    /**
     * Whether this device has something it can legally put on the wire.
     *
     * Checked before encoding rather than trusted, because the encoder cannot be
     * forgiving: [PresencePayload] rejects an empty or over-long name outright,
     * which would take out the coroutine doing the announcing rather than skip
     * one broadcast. A device with no name yet is supposed to be silent, not
     * broken -- and a blank row in every peer list on the network, which nobody
     * can call, is the alternative.
     */
    fun canAnnounce(presence: LocalPresence): Boolean {
        if (presence.deviceId.isZero) return false
        if (presence.voicePort !in 1..0xFFFF) return false
        if (presence.userName.isBlank()) return false
        return presence.userName.toByteArray(Charsets.UTF_8).size <= WireFormat.MAX_USER_NAME_BYTES
    }

    /**
     * Writes one announcement into [into] and returns its length.
     *
     * @param target the recipient for a unicast DISCOVERY_RESPONSE;
     *   [DeviceId.ZERO] for the broadcast types.
     */
    fun encode(
        type: PacketType,
        presence: LocalPresence,
        timestampMillis: Long,
        into: ByteArray,
        target: DeviceId = DeviceId.ZERO,
    ): Int = Packet.of(
        type = type,
        senderDeviceId = presence.deviceId,
        payload = PresencePayload(
            peerState = if (presence.busy) PeerState.BUSY else PeerState.IDLE,
            voicePort = presence.voicePort,
            userName = presence.userName,
        ),
        timestampMillis = timestampMillis,
        targetDeviceId = target,
    ).encodeTo(into)
}
