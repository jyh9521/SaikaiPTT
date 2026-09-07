package com.saikai.ptt.core.protocol

/**
 * Why a datagram was dropped, numbered by the step that dropped it.
 *
 * `docs/ADR/ADR-003-Wire-Format.md` section 8 fixes twelve checks in a fixed
 * order. A single boolean "invalid" would satisfy the protocol and be useless in
 * the field: "3000 invalid packets" says nothing, while "3000 bad-magic from one
 * source" is someone else's discovery protocol and "3000 wrong-target" is a
 * device that thinks it is talking to us.
 *
 * Two of these are not faults at all, which is why [countsAsInvalid] and
 * [isLoggable] exist:
 *
 * - Every broadcast this device sends comes straight back to it. Counting that
 *   echo as invalid would rate-limit the device against itself.
 * - Voice frames from a session that has already ended are expected after a
 *   force interrupt or a normal hang-up: the other side still has frames in
 *   flight. Section 45 says to drop them silently, and voice is not rate-limited
 *   at all -- its rate is already bounded by the session checks.
 */
enum class RejectionReason(
    /** The step in ADR-003 section 8 that produced this rejection. */
    val step: Int,
    /** Throttle key for logging. A small fixed set, never a per-packet value. */
    val logKind: String,
    /** Whether this counts towards the per-source invalid-packet rate. */
    val countsAsInvalid: Boolean = true,
    /** Whether this is worth a log line at all. */
    val isLoggable: Boolean = true,
) {
    /** Step 1: fewer than 72 bytes arrived. */
    TOO_SHORT(1, "too-short"),

    /** Step 2: not `SKPT`. Almost always someone else's protocol. */
    BAD_MAGIC(2, "bad-magic"),

    /** Step 3: a protocol version this build does not speak. */
    UNSUPPORTED_VERSION(3, "bad-version"),

    /** Step 4: PayloadLength over 1024, or not matching the datagram length. */
    PAYLOAD_LENGTH_MISMATCH(4, "bad-length"),

    /** Step 5: Reserved was not zero. */
    RESERVED_NOT_ZERO(5, "reserved-set"),

    /** Step 6: a packet type this build does not implement. */
    UNKNOWN_PACKET_TYPE(6, "unknown-type"),

    /** Step 7: the all-zero sender id, which no real device has. */
    SENDER_INVALID(7, "bad-sender"),

    /** Step 7: this device's own broadcast, arriving back at it. Normal. */
    OWN_BROADCAST_ECHO(7, "loopback", countsAsInvalid = false, isLoggable = false),

    /** Step 8: addressed to another device. */
    WRONG_TARGET(8, "wrong-target"),

    /** Step 9: a session packet carrying the all-zero session id. */
    MISSING_SESSION(9, "no-session"),

    /** Step 10: the payload does not fit its type's layout. */
    MALFORMED_PAYLOAD(10, "bad-payload"),

    /** Step 11: a voice frame for a session this device is not receiving. Normal. */
    FOREIGN_SESSION(11, "foreign-session", countsAsInvalid = false, isLoggable = false),

    /** Step 11: a third device injecting frames into someone else's session. */
    FOREIGN_SESSION_SENDER(11, "session-sender"),

    /** Step 12: the payload exceeds the maximum for its type. */
    PAYLOAD_TOO_LARGE(12, "payload-too-large"),
}
