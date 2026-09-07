package com.saikai.ptt.core.session

import com.saikai.ptt.core.domain.DeviceId
import com.saikai.ptt.core.domain.PeerEndpoint
import com.saikai.ptt.core.protocol.SessionId
import com.saikai.ptt.core.protocol.TerminationReason

/**
 * What this device is doing about voice, right now.
 *
 * One value, never a set of booleans (`docs/01_PRD.md` section 43). "Requesting
 * and not yet transmitting and not receiving" has eight combinations, five of
 * which are nonsense, and every one of them would have to be excluded by hand at
 * every read.
 *
 * It is also the *ownership* variable of `docs/03_Protocol.md` section 34: the
 * single thing a concurrent VOICE_START competes for. Two devices calling at the
 * same instant are arbitrated by one compare against this and nothing else,
 * which is only possible because there is exactly one of it.
 *
 * A device is in one session at a time, in one direction. SaikaiPTT is one to
 * one and half duplex, so sending and receiving are branches of the same state
 * rather than two states that could both be set.
 */
sealed interface SessionState {

    /** No session. The only state from which talking can be started. */
    data object Idle : SessionState

    /** Any live session, either direction. */
    sealed interface Active : SessionState {
        val sessionId: SessionId
        val peer: DeviceId
        val peerName: String
        val endpoint: PeerEndpoint
        val startedAtMillis: Long

        /** True when this device is the one speaking. */
        val outgoing: Boolean
    }

    /**
     * VOICE_START sent, waiting for VOICE_ACCEPT.
     *
     * Capture is already running and buffering locally (`docs/03_Protocol.md`
     * section 19.4). Nothing is transmitted yet, but the first syllable is
     * already recorded -- which is the whole reason the handshake is allowed to
     * take up to half a second without the user hearing about it.
     */
    data class Requesting(
        override val sessionId: SessionId,
        override val peer: DeviceId,
        override val peerName: String,
        override val endpoint: PeerEndpoint,
        override val startedAtMillis: Long,
    ) : Active {
        override val outgoing: Boolean get() = true
    }

    /** Accepted. Buffered frames go out first, then real time. */
    data class Transmitting(
        override val sessionId: SessionId,
        override val peer: DeviceId,
        override val peerName: String,
        override val endpoint: PeerEndpoint,
        override val startedAtMillis: Long,
    ) : Active {
        override val outgoing: Boolean get() = true
    }

    /** Someone else's voice is arriving and playing. */
    data class Receiving(
        override val sessionId: SessionId,
        override val peer: DeviceId,
        override val peerName: String,
        override val endpoint: PeerEndpoint,
        override val startedAtMillis: Long,
    ) : Active {
        override val outgoing: Boolean get() = false
    }
}

/** Why an attempt to talk did not become a transmission. */
enum class SendFailure {
    /** This device is already in a session. Nothing was sent. */
    ALREADY_IN_SESSION,

    /** No name has been chosen, so there is no identity to transmit. */
    NO_LOCAL_NAME,

    /** The microphone could not be opened. */
    MIC_UNAVAILABLE,

    /** The datagram could not be handed to the network at all. */
    UNREACHABLE,

    /** The peer refused: it is in a call and does not allow interruption. */
    TARGET_BUSY,

    /** Nothing came back within the request window. */
    NO_RESPONSE,

    /** The button was released before the peer answered. */
    CANCELLED,

    /** The network went away mid-session. */
    NETWORK_LOST,
}

/**
 * How a session finished.
 *
 * Separate from [SessionState] because the state is what is true now and this is
 * what happened. The history record (Task27) and the message the UI shows are
 * both built from these, and neither can be recovered from a state that has
 * already returned to [SessionState.Idle].
 */
sealed interface SessionOutcome {
    val peer: DeviceId
    val peerName: String

    /** Never became a transmission. No history record is written for these. */
    data class SendFailed(
        override val peer: DeviceId,
        override val peerName: String,
        val reason: SendFailure,
    ) : SessionOutcome

    /** Spoke and finished normally. */
    data class SendEnded(
        val sessionId: SessionId,
        override val peer: DeviceId,
        override val peerName: String,
        val startedAtMillis: Long,
        val endedAtMillis: Long,
    ) : SessionOutcome

    /** Was speaking and was cut off. */
    data class SendInterrupted(
        val sessionId: SessionId,
        override val peer: DeviceId,
        override val peerName: String,
        val startedAtMillis: Long,
        val endedAtMillis: Long,
        val reason: TerminationReason,
    ) : SessionOutcome

    /** Listened to a whole transmission. */
    data class ReceiveEnded(
        val sessionId: SessionId,
        override val peer: DeviceId,
        override val peerName: String,
        val startedAtMillis: Long,
        val endedAtMillis: Long,
    ) : SessionOutcome

    /** Was listening and the session ended some other way. */
    data class ReceiveInterrupted(
        val sessionId: SessionId,
        override val peer: DeviceId,
        override val peerName: String,
        val startedAtMillis: Long,
        val endedAtMillis: Long,
        val reason: TerminationReason,
    ) : SessionOutcome
}
