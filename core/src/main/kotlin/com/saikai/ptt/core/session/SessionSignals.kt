package com.saikai.ptt.core.session

import com.saikai.ptt.core.domain.DeviceId
import com.saikai.ptt.core.domain.PeerEndpoint
import com.saikai.ptt.core.protocol.SessionId
import com.saikai.ptt.core.protocol.TerminationReason

/**
 * The control packets a session sends.
 *
 * An interface so that the state machine never sees a socket. Every decision in
 * this package is about ordering and ownership, and all of it has to be provable
 * on a JVM -- a machine that could only be exercised by two phones in a room
 * would be a machine whose race conditions are found by users.
 *
 * All of these go on the control channel, never the voice one: session set-up
 * must not queue behind fifty audio frames a second, which is the point of
 * ADR-002 having two sockets at all.
 *
 * @return false when the datagram could not be handed to the network. Not an
 *   exception: a send failing while WiFi drops is expected, and the caller has a
 *   state machine to keep consistent rather than a stack to unwind.
 */
interface SessionSignals {

    suspend fun voiceStart(
        sessionId: SessionId,
        target: DeviceId,
        endpoint: PeerEndpoint,
        localName: String,
    ): Boolean

    suspend fun voiceAccept(
        sessionId: SessionId,
        target: DeviceId,
        endpoint: PeerEndpoint,
    ): Boolean

    /**
     * Refuses a request.
     *
     * [sessionId] is the id the *caller* put in its VOICE_START, echoed back.
     * Not the session this device is actually in -- that one is never disclosed,
     * because it would tell any device on the LAN who is talking to whom
     * (ADR-003 section 4 keeps the payload empty for the same reason).
     *
     * Echoing the caller's own id costs nothing and is what lets it tell this
     * refusal from a stale one. A caller whose first request timed out and who
     * has since pressed again would otherwise take the late BUSY from the
     * abandoned attempt as the answer to the new one.
     */
    suspend fun busy(
        sessionId: SessionId,
        target: DeviceId,
        endpoint: PeerEndpoint,
    ): Boolean

    /**
     * Ends the transmission.
     *
     * VOICE_END carries the last frame's sequence number and the frame count
     * (ADR-003 section 4), and neither is a parameter here on purpose. They
     * describe the frames that actually went out, which only the implementation
     * knows: the state machine counts no frames, and a frame the codec refused
     * or the socket dropped never became a sequence number. Passing them from
     * above would mean passing a guess, which is what the two zeroes here were
     * until the send pipeline existed.
     */
    suspend fun voiceEnd(
        sessionId: SessionId,
        target: DeviceId,
        endpoint: PeerEndpoint,
    ): Boolean

    suspend fun sessionTerminate(
        sessionId: SessionId,
        target: DeviceId,
        endpoint: PeerEndpoint,
        reason: TerminationReason,
    ): Boolean
}
