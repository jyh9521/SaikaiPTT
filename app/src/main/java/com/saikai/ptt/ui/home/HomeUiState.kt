package com.saikai.ptt.ui.home

import com.saikai.ptt.core.domain.DeviceId
import com.saikai.ptt.core.domain.PresenceState

/**
 * What the Home screen shows, split the way it changes.
 *
 * Not one state object. `PeerRegistry` recomputes and emits on every heartbeat,
 * which is every five seconds per peer, and a single `HomeUiState` would make
 * each of those a new value for the whole screen -- so the user's name, the
 * service banner and the talk button would all recompose because somebody
 * across the room is still switched on. Task32 forbids exactly that. Four
 * flows, each carrying only what it describes, and each of these types holding
 * only what is displayed.
 *
 * That last part is what makes the split work. [PeerRow] deliberately does not
 * carry `lastSeenMillis` or an endpoint: those change on every heartbeat and
 * nothing on screen shows them, so dropping them means an ordinary heartbeat
 * produces a value equal to the last one and nothing recomposes at all.
 */

/** The banner across the top: who this device is, and whether it can hear anyone. */
data class HomeHeader(
    val activeUserName: String?,
    val connection: ConnectionState,
)

/**
 * The one piece of service state a user has any use for.
 *
 * `ServiceState` has six values and `docs/04_UI_UX.md` section 25 is explicit
 * that the internal ones need not all be exposed. What a user needs to know is
 * whether the app can hear anybody, and if not, whether that is something they
 * can do something about -- which is not the same cut as the service's own
 * lifecycle, so the two enums are mapped rather than shared.
 */
enum class ConnectionState {
    /** Nothing is running. The user has not started it, or it stopped. */
    STOPPED,

    /** Coming up. */
    STARTING,

    /**
     * Going down, at the user's request.
     *
     * Its own value rather than folded into [STOPPED], because the two differ
     * in what the user may do next: STOPPED offers a button that starts the
     * service, and offering that button here would race the shutdown it is
     * meant to follow. Symmetric with [STARTING], and just as short-lived.
     */
    STOPPING,

    /** Running and on a network. */
    READY,

    /** Running, but there is no WiFi (`docs/04_UI_UX.md` section 23). */
    NO_NETWORK,

    /** It tried and could not. */
    FAILED,
}

/** The device list, and the two states that are not a list (section 24). */
sealed interface PeerListState {

    /** Running, and nobody has answered yet. Never an error (section 24). */
    data object Searching : PeerListState

    /** No network, so an empty list is not news (section 23). */
    data object Unavailable : PeerListState

    data class Peers(val rows: List<PeerRow>) : PeerListState
}

/**
 * One row.
 *
 * [state] carries the icon *and* the text, and the screen shows both: section
 * 11.3 forbids colour as the only way to tell one from another, and an icon
 * alone is colour by another name to anyone who cannot tell green from orange.
 */
data class PeerRow(
    val deviceId: DeviceId,
    val userName: String,
    val state: PresenceState,
    val selected: Boolean,
)

/** Who the talk button will call, and what it is called. */
data class TargetState(
    val deviceId: DeviceId?,
    val userName: String?,
) {
    val isChosen: Boolean get() = deviceId != null
}

/** The talk button, and what happened last time it was pressed. */
data class PttState(
    val phase: PttPhase,
    /** The peer being spoken to or listened to, for the line above the button. */
    val peerName: String?,
    /** Shown until the next press. Null when there is nothing to say. */
    val message: PttMessage?,
) {
    /**
     * Whether the button can be pressed at all.
     *
     * Never turned off because a peer *looks* busy. Section 11.2 and section
     * 14.1: the callee decides, the list is up to a heartbeat out of date, and
     * refusing locally on the strength of it would refuse calls that would have
     * connected.
     */
    val enabled: Boolean get() = phase == PttPhase.READY
}

enum class PttPhase {
    /** No target chosen, no network, or no service. */
    UNAVAILABLE,

    /** Ready to talk. */
    READY,

    /** Pressed, waiting for the peer to agree. */
    REQUESTING,

    /** Talking. */
    TRANSMITTING,

    /** Somebody else is talking to this device. */
    RECEIVING,
}

/** Something that happened once and is worth a line of text (sections 21.2, 21.3). */
enum class PttMessage {
    TARGET_BUSY,
    NO_RESPONSE,
    NO_NAME,
    NO_MICROPHONE,
    APP_NOT_VISIBLE,
    UNREACHABLE,
    ALREADY_IN_SESSION,
    INTERRUPTED,
}
