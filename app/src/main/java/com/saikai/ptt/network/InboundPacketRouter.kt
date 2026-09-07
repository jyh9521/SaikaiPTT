package com.saikai.ptt.network

import com.saikai.ptt.core.logger.LogCategory
import com.saikai.ptt.core.logger.LogLevel
import com.saikai.ptt.core.logger.Logger
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Fans one validated packet out to everything that wants it.
 *
 * Discovery, presence and the session manager all read the same stream and are
 * started and stopped at different points in the service lifecycle, so the
 * transport is given this once at construction and the components register and
 * unregister around it.
 *
 * A listener that throws is logged and skipped; the others still get the packet.
 * Without that, one component's bug would silently deafen the rest of the app --
 * the kind of failure that looks like a network problem for a week.
 *
 * The two receive threads call this directly, so it allocates nothing per
 * packet: a copy-on-write list is read by index rather than through an iterator.
 *
 * The aliasing contract of [InboundPacket] applies to every listener
 * independently. A listener that keeps a VOICE_DATA frame past its callback must
 * copy it; the buffer is reused as soon as the last listener returns.
 */
class InboundPacketRouter(private val logger: Logger) : InboundPacketListener {

    private val listeners = CopyOnWriteArrayList<InboundPacketListener>()

    fun register(listener: InboundPacketListener) {
        listeners.addIfAbsent(listener)
    }

    fun unregister(listener: InboundPacketListener) {
        listeners.remove(listener)
    }

    fun clear() {
        listeners.clear()
    }

    val size: Int get() = listeners.size

    override fun onPacket(inbound: InboundPacket) {
        for (index in listeners.indices) {
            val listener = listeners[index]
            try {
                listener.onPacket(inbound)
            } catch (error: Throwable) {
                logger.throttled(LogLevel.ERROR, LogCategory.NETWORK, "listener-failed", error) {
                    "${listener.javaClass.simpleName} threw on ${inbound.packet.type}"
                }
            }
        }
    }
}
