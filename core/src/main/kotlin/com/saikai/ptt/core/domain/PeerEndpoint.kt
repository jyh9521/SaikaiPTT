package com.saikai.ptt.core.domain

/**
 * Where a peer's packets came from, and where its voice should go.
 *
 * The address is a string rather than an `InetAddress` so that `core` stays free
 * of socket types (`docs/02_Architecture.md` section 5.1): the transport resolves
 * and formats it, the domain only ever compares and stores it.
 *
 * The voice port is carried separately from the control port because it floats.
 * A device that cannot bind the preferred port binds another and announces the
 * one it actually got (`docs/ADR/ADR-002-Transport-And-Ports.md`), so the value
 * here is what the peer said, never an assumption.
 *
 * An endpoint is not an identity. The same peer changes address whenever the
 * network does, and `docs/03_Protocol.md` section 11 is explicit that this
 * updates the record rather than creating a second one.
 */
data class PeerEndpoint(
    val address: String,
    val voicePort: Int,
) {
    init {
        require(address.isNotBlank()) { "A peer endpoint needs an address" }
        require(voicePort in 1..0xFFFF) { "voicePort must be a real port, was $voicePort" }
    }

    override fun toString(): String = "$address:$voicePort"
}
