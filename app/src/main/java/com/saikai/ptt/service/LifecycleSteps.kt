package com.saikai.ptt.service

import android.app.Service
import android.content.Context
import android.net.wifi.WifiManager
import com.saikai.ptt.core.common.LifecycleStep
import com.saikai.ptt.core.common.Outcome
import com.saikai.ptt.core.logger.LogCategory
import com.saikai.ptt.core.logger.Logger
import com.saikai.ptt.network.BoundPorts
import com.saikai.ptt.network.UdpTransport

/**
 * Step 1: become a foreground service.
 *
 * First, because everything after it keeps a resource the platform will not let
 * a background process hold. `docs/02_Architecture.md` section 26 puts it here
 * for that reason, and rolling back in reverse means the notification is the
 * last thing to go.
 */
class ForegroundStep(
    private val service: Service,
    private val notifications: ServiceNotifications,
) : LifecycleStep {

    override val name: String = "foreground"

    override suspend fun start() {
        notifications.startForeground(service)
    }

    override suspend fun stop() {
        service.stopForeground(Service.STOP_FOREGROUND_REMOVE)
    }
}

/**
 * Step 2: hold the multicast lock for as long as the service is up.
 *
 * The one power lock this product holds continuously (ADR-005 section 6). WiFi
 * power saving drops broadcast frames once the screen goes off, and DISCOVERY
 * and HEARTBEAT are broadcasts -- without this lock the app stops seeing anyone
 * a minute after the phone goes in a pocket, which is the entire product.
 *
 * A device that will not give up the lock is degraded, not broken: discovery
 * still works while the screen is on. So this logs and continues rather than
 * failing the start and leaving the user with nothing at all.
 */
class MulticastLockStep(
    context: Context,
    private val logger: Logger,
) : LifecycleStep {

    private val appContext = context.applicationContext
    private var lock: WifiManager.MulticastLock? = null

    override val name: String = "multicast-lock"

    val isHeld: Boolean get() = lock?.isHeld == true

    override suspend fun start() {
        val wifi = appContext.getSystemService(WifiManager::class.java)
        if (wifi == null) {
            logger.w(LogCategory.SERVICE) {
                "no WifiManager; broadcasts will stop arriving once the screen is off"
            }
            return
        }
        lock = try {
            wifi.createMulticastLock(LOCK_TAG).apply {
                // Not reference counted: this is acquired once and released
                // once, and a counted lock that is released twice throws.
                setReferenceCounted(false)
                acquire()
            }
        } catch (error: SecurityException) {
            logger.w(LogCategory.SERVICE, error) {
                "could not hold the multicast lock; background discovery will be unreliable"
            }
            null
        }
    }

    override suspend fun stop() {
        lock?.takeIf { it.isHeld }?.release()
        lock = null
    }

    private companion object {
        const val LOCK_TAG = "saikai-ptt"
    }
}

/**
 * Step 3: open both sockets and start both receive loops.
 *
 * Failing here fails the start. A device that cannot bind its control port
 * cannot be discovered and cannot receive, so continuing would leave a
 * foreground notification promising something the app is not doing.
 */
class TransportStep(
    private val transport: UdpTransport,
    private val logger: Logger,
    private val onBound: (BoundPorts) -> Unit = {},
) : LifecycleStep {

    override val name: String = "udp-transport"

    override suspend fun start() {
        when (val outcome = transport.start()) {
            is Outcome.Success -> {
                val ports = outcome.value
                if (!ports.isDiscoverable) {
                    logger.w(LogCategory.SERVICE) {
                        "started on control port ${ports.controlPort}; this device will not " +
                            "be discovered by peers using ${ports.configuredControlPort}"
                    }
                }
                onBound(ports)
            }

            is Outcome.Failure -> error("UDP transport did not start: ${outcome.error}")
        }
    }

    override suspend fun stop() {
        transport.stop()
    }
}
