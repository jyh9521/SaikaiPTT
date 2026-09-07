package com.saikai.ptt.core.protocol

import com.saikai.ptt.core.domain.DeviceId

/**
 * The voice session this device is currently receiving, if any.
 *
 * Step 11 of the validation order needs both halves: a frame must belong to the
 * open session *and* come from the device that opened it. `docs/03_Protocol.md`
 * section 35 is explicit -- a third device must not be able to inject audio into
 * a session by guessing or replaying its id.
 *
 * A read-only snapshot on purpose. Who owns this state, and how ownership
 * transfers on a force interrupt, is the session state machine's problem; the
 * validator only ever asks what it is right now.
 */
data class ReceivingSession(
    val sessionId: SessionId,
    val senderDeviceId: DeviceId,
)
