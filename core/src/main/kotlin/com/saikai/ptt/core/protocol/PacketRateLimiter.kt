package com.saikai.ptt.core.protocol

import com.saikai.ptt.core.common.Outcome
import com.saikai.ptt.core.config.RateLimitConfig
import com.saikai.ptt.core.logger.LogCategory
import com.saikai.ptt.core.logger.LogLevel
import com.saikai.ptt.core.logger.Logger
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-source rate limiting for the receive path (`docs/03_Protocol.md` section 45).
 *
 * The product assumes a trusted LAN, but "trusted" cannot mean that one broken
 * or malicious device is allowed to spin this device's CPU and drain its
 * battery. A source that exceeds either limit is ignored entirely for
 * [RateLimitConfig.silenceDuration], which turns an unbounded flood into a fixed
 * cost per five seconds.
 *
 * Voice frames are deliberately not rate-limited. Their rate is already bounded
 * by the session checks in step 11 -- a device that is not in a session with
 * this one has its frames dropped before they cost anything -- and a limit there
 * would risk cutting off a legitimate call under packet loss and retransmission.
 *
 * The source key is opaque: this class never learns what an IP address is,
 * because `core` must not depend on socket types. The transport passes whatever
 * identifies a source to it (`docs/02_Architecture.md`).
 *
 * Safe to call from both receive threads.
 */
class PacketRateLimiter(
    private val config: RateLimitConfig,
    private val logger: Logger,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {

    private val sources = ConcurrentHashMap<String, SourceState>()

    // The source table is itself an attack surface: a device forging source
    // addresses would otherwise grow it without bound. Peers are capped at 64,
    // and a few addresses per peer covers a network that is renumbering.
    private val maxTrackedSources: Int = config.maxPeers * TRACKED_SOURCES_PER_PEER

    /** How many sources currently have state. Diagnostics and tests. */
    val trackedSources: Int get() = sources.size

    /**
     * Records the arrival of a control packet and says whether to process it.
     *
     * Call once per datagram, before validating: a silenced source must cost
     * nothing beyond this check.
     */
    fun admit(sourceKey: String): Boolean {
        val now = nowMillis()
        val state = stateFor(sourceKey, now)
        var silenced = false
        val admitted: Boolean
        synchronized(state) {
            if (now < state.silencedUntilMillis) {
                state.lastSeenMillis = now
                return false
            }
            rollWindow(state, now)
            state.controlCount++
            if (state.controlCount > config.maxControlPacketsPerSecondPerSource) {
                silence(state, now)
                silenced = true
                admitted = false
            } else {
                admitted = true
            }
        }
        if (silenced) {
            // Debug, not warn: a burst of control packets is usually a peer that
            // just came back onto the network, not an attack.
            logger.throttled(LogLevel.DEBUG, LogCategory.PROTOCOL, "control-flood") {
                "silencing $sourceKey for ${config.silenceDuration}: over " +
                    "${config.maxControlPacketsPerSecondPerSource} control packets/s"
            }
        }
        return admitted
    }

    /**
     * Records that a packet from this source failed validation.
     *
     * Only rejections with [RejectionReason.countsAsInvalid] belong here. This
     * device's own broadcast echo and frames from a closed session are normal
     * traffic; counting them would let this device silence its own peers, or
     * itself.
     */
    fun recordInvalid(sourceKey: String) {
        val now = nowMillis()
        val state = stateFor(sourceKey, now)
        var silenced = false
        synchronized(state) {
            rollWindow(state, now)
            state.invalidCount++
            if (state.invalidCount > config.maxInvalidPacketsPerSecondPerSource &&
                now >= state.silencedUntilMillis
            ) {
                silence(state, now)
                silenced = true
            }
        }
        if (silenced) {
            // SERVICE rather than PROTOCOL so that it survives into release logs.
            // Section 45 asks for this line specifically, and it is the only
            // evidence a user will ever have that another device on their network
            // is misbehaving. Throttled, so the flood cannot become a log flood.
            logger.throttled(LogLevel.WARN, LogCategory.SERVICE, "invalid-flood") {
                "silencing $sourceKey for ${config.silenceDuration}: over " +
                    "${config.maxInvalidPacketsPerSecondPerSource} invalid packets/s"
            }
        }
    }

    /** Feeds a validation result back in. Convenience for the receive pipeline. */
    fun record(sourceKey: String, outcome: Outcome<Packet, RejectionReason>) {
        val reason = outcome.errorOrNull() ?: return
        if (reason.countsAsInvalid) recordInvalid(sourceKey)
    }

    /** True while this source is being ignored. */
    fun isSilenced(sourceKey: String): Boolean {
        val state = sources[sourceKey] ?: return false
        synchronized(state) {
            return nowMillis() < state.silencedUntilMillis
        }
    }

    /** Drops any state for a source, for example when its peer leaves. */
    fun forget(sourceKey: String) {
        sources.remove(sourceKey)
    }

    fun reset() {
        sources.clear()
    }

    private fun stateFor(key: String, now: Long): SourceState {
        sources[key]?.let { return it }
        if (sources.size >= maxTrackedSources) prune(now)
        return sources.computeIfAbsent(key) { SourceState() }
    }

    private fun prune(now: Long) {
        val cutoff = now - config.silenceDuration.inWholeMilliseconds
        sources.entries.removeIf { entry ->
            synchronized(entry.value) {
                entry.value.lastSeenMillis < cutoff && now >= entry.value.silencedUntilMillis
            }
        }
        if (sources.size < maxTrackedSources) return

        // Every slot is live. Evict the stalest rather than refusing to track,
        // so a forged-address flood cannot pin the table and make the limiter
        // blind to the real peers behind it.
        val stalest = sources.entries.minByOrNull { entry ->
            synchronized(entry.value) { entry.value.lastSeenMillis }
        }
        if (stalest != null) sources.remove(stalest.key, stalest.value)
        logger.throttled(LogLevel.WARN, LogCategory.SERVICE, "source-table-full") {
            "rate limiter is tracking $maxTrackedSources sources; evicting the stalest"
        }
    }

    private fun rollWindow(state: SourceState, now: Long) {
        if (now - state.windowStartMillis >= WINDOW_MILLIS) {
            state.windowStartMillis = now
            state.controlCount = 0
            state.invalidCount = 0
        }
        state.lastSeenMillis = now
    }

    private fun silence(state: SourceState, now: Long) {
        state.silencedUntilMillis = now + config.silenceDuration.inWholeMilliseconds
        state.controlCount = 0
        state.invalidCount = 0
        state.windowStartMillis = now
    }

    private class SourceState {
        var windowStartMillis: Long = 0L
        var lastSeenMillis: Long = 0L
        var controlCount: Int = 0
        var invalidCount: Int = 0
        var silencedUntilMillis: Long = 0L
    }

    private companion object {
        const val WINDOW_MILLIS: Long = 1_000L
        const val TRACKED_SOURCES_PER_PEER: Int = 4
    }
}
