package com.saikai.ptt.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import com.saikai.ptt.SaikaiApplication
import com.saikai.ptt.core.common.Outcome
import com.saikai.ptt.core.logger.LogCategory
import com.saikai.ptt.di.ServiceContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Owns everything that has to keep working when no screen is on.
 *
 * The sockets, the receive threads and the multicast lock belong here from the
 * first line of code that creates them, and never to an Activity. That ordering
 * is the reason this task exists at all: the alternative -- build the
 * communication stack under the UI and move it into a service later -- means
 * changing the owner of every resource in the app at the point where the app has
 * the most resources, which is the largest avoidable piece of rework in the plan.
 *
 * The service does lifecycle and wiring and nothing else
 * (`docs/02_Architecture.md` section 25). It parses no packets, touches no
 * audio, and holds no business state; it starts the components that do, in the
 * order [ServiceContainer] declares, and releases them in reverse.
 *
 * `START_STICKY`, but with no promise attached. Android 12 forbids starting a
 * foreground service from the background, so a process the system kills may not
 * be allowed to come back (ADR-005 section 5). The UI checks [ServiceStatus] and
 * restarts on next launch.
 */
class CommunicationService : Service() {

    // Not Dispatchers.Main: nothing here touches a view, start-up reads DataStore,
    // and shutdown joins two receive threads. Running that on the main thread
    // would put a disk read between the user and their talk button.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val mutex = Mutex()
    private lateinit var notifications: ServiceNotifications
    private var container: ServiceContainer? = null
    private var lifecycle: ServiceLifecycle? = null
    private var peerMirror: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        notifications = ServiceNotifications(this).also { it.ensureChannel() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Android gives a service started with startForegroundService only a few
        // seconds to call startForeground, and resolving the device identity
        // reads DataStore. Claiming the foreground synchronously here, before any
        // suspending work, is what keeps the platform from killing the process
        // mid-start. ForegroundStep calls the same idempotent method again in its
        // proper place, so the reverse-order shutdown still releases it correctly.
        notifications.startForeground(this)

        if (intent?.action == ACTION_STOP) {
            scope.launch {
                shutdown()
                stopSelf()
            }
            return START_NOT_STICKY
        }

        scope.launch { startUp() }
        return START_STICKY
    }

    override fun onDestroy() {
        // Blocking, deliberately. onDestroy is the last moment the sockets and
        // the multicast lock can be released by this process, and closing them
        // takes milliseconds -- the receive threads are unblocked by the close
        // itself. The timeout is there so that a step which hangs costs a
        // stutter rather than an ANR.
        runBlocking {
            val finished = withTimeoutOrNull(SHUTDOWN_TIMEOUT_MILLIS) { shutdown() }
            if (finished == null) {
                appContainerOrNull()?.logger?.e(LogCategory.SERVICE) {
                    "shutdown did not finish within ${SHUTDOWN_TIMEOUT_MILLIS}ms"
                }
            }
        }
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun startUp(): Unit = mutex.withLock {
        if (lifecycle != null) return@withLock

        val app = (application as SaikaiApplication).container
        val status = app.serviceStatus

        val deviceId = try {
            app.deviceIdentity.deviceId()
        } catch (error: Throwable) {
            app.logger.e(LogCategory.SERVICE, error) { "could not resolve the device identity" }
            status.publishFailure(LifecycleFailure(IDENTITY_STEP, error))
            status.publish(ServiceState.FAILED)
            stopSelf()
            return@withLock
        }

        val serviceContainer = ServiceContainer(
            service = this,
            app = app,
            localDeviceId = deviceId,
            notifications = notifications,
            onBound = { ports ->
                app.logger.i(LogCategory.SERVICE) {
                    "listening on control ${ports.controlPort}, voice ${ports.voicePort}"
                }
            },
        )
        val serviceLifecycle = ServiceLifecycle(
            steps = serviceContainer.steps,
            logger = app.logger,
            onState = status::publish,
        )
        container = serviceContainer
        lifecycle = serviceLifecycle

        when (val outcome = serviceLifecycle.start()) {
            is Outcome.Success -> {
                // The peer table lives in service scope; the UI has to be able
                // to read it without binding to a service that may not be there.
                peerMirror = scope.launch {
                    serviceContainer.peers.peers.collect(status::publishPeers)
                }
            }
            is Outcome.Failure -> {
                // Everything the sequence started has already been released.
                status.publishFailure(outcome.error)
                serviceContainer.close()
                container = null
                lifecycle = null
                stopSelf()
            }
        }
    }

    private suspend fun shutdown(): Unit = mutex.withLock {
        peerMirror?.cancel()
        peerMirror = null
        lifecycle?.stop()
        container?.close()
        lifecycle = null
        container = null
        // Nothing has been heard from anyone since the sockets closed, and
        // saying so is more honest than leaving a stale list on screen.
        (application as? SaikaiApplication)?.container?.serviceStatus?.publishPeers(emptyList())
    }

    private fun appContainerOrNull() = (application as? SaikaiApplication)?.container

    companion object {
        /** Sent by the ongoing notification's stop action (Task31). */
        const val ACTION_STOP: String = "com.saikai.ptt.action.STOP"

        private const val IDENTITY_STEP = "device-identity"
        private const val SHUTDOWN_TIMEOUT_MILLIS = 3_000L

        /**
         * Starts the service in the foreground.
         *
         * Must be called from a context the platform considers foreground.
         * Android 12 and later throw `ForegroundServiceStartNotAllowedException`
         * otherwise, and that is a caller-side condition -- there is nothing this
         * service can do about it from inside.
         */
        fun start(context: Context) {
            context.startForegroundService(Intent(context, CommunicationService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CommunicationService::class.java))
        }
    }
}
