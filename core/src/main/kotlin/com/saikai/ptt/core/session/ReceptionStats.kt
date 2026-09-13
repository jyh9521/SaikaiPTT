package com.saikai.ptt.core.session

import com.saikai.ptt.core.protocol.SessionId

/**
 * What happened to one incoming transmission, once it is over.
 *
 * Every number here is a frame the listener either heard, half-heard or never
 * heard, and they are worth keeping apart because they mean different things
 * about the network:
 *
 * - [concealed] is ordinary loss, recovered or invented. A few per transmission
 *   is a normal WiFi link.
 * - [droppedOverflow] means the audio arrived but too late to be worth playing;
 *   the link stalled and then caught up. This is the one that sounds worst.
 * - [droppedLate] is reordering that the buffer could not absorb.
 * - [neverArrived] is the difference between what the sender says it sent and
 *   the newest frame that reached this device: the tail that vanished.
 * - [droppedImplausible] should always be zero. A non-zero value means a peer
 *   sent a sequence number that cannot belong to the session it claims.
 *
 * `docs/01_PRD.md` asks for the loss count in the debug log and on a developer
 * information page. The log line is written when the transmission ends; the
 * page is Task35, and until then the debug screen shows the last one.
 */
data class ReceptionStats(
    val sessionId: SessionId,
    /** Frames the sender says it sent, from the VOICE_END payload. */
    val expectedFrames: Int,
    val played: Int,
    val concealed: Int,
    val droppedLate: Int,
    val droppedOverflow: Int,
    val droppedImplausible: Int,
    val neverArrived: Int,
) {

    /** Frames that were sent and never reached the speaker intact. */
    val lost: Int get() = concealed + droppedOverflow + neverArrived

    /** Loss as a percentage of what the sender says it sent. */
    val lossPercent: Double
        get() = if (expectedFrames <= 0) 0.0 else lost * 100.0 / expectedFrames

    /** One line, for a log or a diagnostics screen. */
    override fun toString(): String = buildString {
        append(played).append(" played")
        if (concealed > 0) append(", ").append(concealed).append(" concealed")
        if (droppedLate > 0) append(", ").append(droppedLate).append(" late")
        if (droppedOverflow > 0) append(", ").append(droppedOverflow).append(" too late")
        if (neverArrived > 0) append(", ").append(neverArrived).append(" never arrived")
        if (droppedImplausible > 0) {
            append(", ").append(droppedImplausible).append(" implausible")
        }
        if (expectedFrames > 0) {
            append(" (").append(String.format("%.1f", lossPercent)).append("% lost of ")
                .append(expectedFrames).append(")")
        }
    }
}
