package com.saikai.ptt.service

import com.saikai.ptt.core.domain.PeerRegistry
import com.saikai.ptt.core.logger.LogCategory
import com.saikai.ptt.core.logger.Logger
import com.saikai.ptt.network.NetworkLink
import com.saikai.ptt.network.NetworkLinkSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Takes the network half of the service down when WiFi goes and brings it back
 * when WiFi returns.
 *
 * `docs/03_Protocol.md` sections 40 and 41. The sequence is not written out here
 * -- it is the tail of [ServiceLifecycle]'s own step list, released in reverse
 * and started again in order. That is the whole reason the start-up sequence is
 * a list: a later task that inserts audio or a session manager gets it torn down
 * and rebuilt across a WiFi drop without knowing this class exists.
 *
 * Above the lifecycle rather than inside it. A step that restarted the list it
 * belongs to would have to be careful never to restart itself, and "careful
 * never to" is not a property a resource sequence should depend on.
 *
 * Three rules from the protocol, and each one is a decision not to be clever:
 *
 * - **The Device ID never changes.** An address is not an identity. A phone that
 *   moves between access points is the same radio to everyone who was talking to
 *   it, and the peer table keys on the id precisely so that this is free.
 * - **A voice session is not carried across.** It is ended and marked
 *   interrupted. Real-time audio that survives a network change is a promise
 *   that cannot be kept, and trying produces a call that sounds broken instead
 *   of one that ended.
 * - **A renumbering is a recovery.** The network can stay "available" through a
 *   DHCP change that moves this device to another subnet, and every peer's idea
 *   of where to send voice is then wrong with nothing having been lost.
 */
class NetworkRecovery(
    private val monitor: NetworkLinkSource,
    private val lifecycle: ServiceLifecycle,
    private val peers: PeerRegistry,
    private val logger: Logger,
    /** The first step that depends on the network. Everything from here is cycled. */
    private val firstNetworkStep: String,
    /** Ends any voice session. Supplied by the session machine in Task19. */
    private val onNetworkLost: suspend () -> Unit = {},
) {

    private val mutex = Mutex()
    private var watcher: Job? = null
    private var applied: NetworkLink? = null

    fun start(scope: CoroutineScope) {
        monitor.start()
        watcher = scope.launch {
            monitor.link.collect { link -> apply(link) }
        }
    }

    suspend fun stop() {
        watcher?.cancelAndJoin()
        watcher = null
        monitor.stop()
        applied = null
    }

    private suspend fun apply(link: NetworkLink) = mutex.withLock {
        val previous = applied
        if (previous == link) return

        when {
            !link.available -> {
                if (previous?.available == true) lose()
                applied = link
            }

            previous == null || !previous.available -> {
                if (regain(link)) applied = link
            }

            else -> {
                // Still available, different addresses: renumbered underneath us.
                logger.i(LogCategory.NETWORK) {
                    "addresses changed ${previous.addresses} -> ${link.addresses}; recovering"
                }
                lose()
                if (regain(link)) applied = link else applied = NetworkLink.NONE
            }
        }
    }

    private suspend fun lose() {
        logger.i(LogCategory.SERVICE) { "network lost; releasing from $firstNetworkStep" }
        // Ending the session first: everything below is about to stop being able
        // to send, and a session that finds that out by having its packets
        // disappear ends far less cleanly than one that is told.
        onNetworkLost()
        peers.setNetworkAvailable(false)
        lifecycle.releaseFrom(firstNetworkStep)
    }

    private suspend fun regain(link: NetworkLink): Boolean {
        logger.i(LogCategory.SERVICE) { "network back at ${link.addresses}; restoring" }
        // Marked available before the sockets open, so that the first
        // announcement to arrive is not derived offline and then corrected.
        peers.setNetworkAvailable(true)
        val outcome = lifecycle.restore()
        if (!outcome.isSuccess) {
            peers.setNetworkAvailable(false)
            logger.e(LogCategory.SERVICE) {
                "could not restore after the network came back: ${outcome.errorOrNull()}"
            }
            return false
        }
        return true
    }
}
