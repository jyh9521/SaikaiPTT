package com.saikai.ptt.service

import android.content.Context
import android.net.wifi.WifiManager
import android.os.PowerManager
import com.saikai.ptt.core.config.SaikaiConfig
import com.saikai.ptt.core.logger.LogCategory
import com.saikai.ptt.core.logger.Logger
import com.saikai.ptt.core.session.SessionState

/**
 * The two power locks a voice session is allowed to hold, and nothing else.
 *
 * `docs/ADR/ADR-005` section 6 overrides the project's general ban on wake locks
 * for exactly three cases. The multicast lock is one and is held for the life of
 * the service ([MulticastLockStep]); the other two are these, and they exist
 * only while a session does:
 *
 * - `WIFI_MODE_FULL_LOW_LATENCY` keeps the WiFi radio out of the power-saving
 *   duty cycle that turns 20 ms frames into bursts of jitter.
 * - `PARTIAL_WAKE_LOCK` keeps the CPU running so the audio threads are not
 *   suspended with the screen.
 *
 * Both are released the moment the session ends, and the wake lock also carries
 * a timeout. The timeout is not belt and braces: a lock held by a process that
 * stops running its own release path drains a battery flat, and the one thing
 * that can be relied on to notice is the platform. ADR-005 sets it at five
 * minutes, matching the session's own maximum duration, so it can only fire
 * after something has already gone wrong.
 *
 * Driven from the session state rather than from the press and release of the
 * button. Every way a session can end -- a peer refusing, no answer, a force
 * interrupt, WiFi disappearing, audio focus going to a phone call -- passes
 * through the state machine, and only some of them pass through the button.
 */
class VoiceSessionPowerLocks(
    context: Context,
    private val config: SaikaiConfig,
    private val logger: Logger,
) {

    private val appContext = context.applicationContext
    private val lock = Any()

    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null

    val isHeld: Boolean get() = synchronized(lock) { wakeLock != null || wifiLock != null }

    /** Acquires for an active session, releases for anything else. */
    fun onSessionState(state: SessionState) {
        if (state is SessionState.Active) acquire() else release()
    }

    fun acquire() = synchronized(lock) {
        if (wifiLock == null) {
            wifiLock = try {
                appContext.getSystemService(WifiManager::class.java)
                    ?.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, WIFI_TAG)
                    ?.apply {
                        setReferenceCounted(false)
                        acquire()
                    }
            } catch (error: RuntimeException) {
                // A device that will not give the low-latency lock still works;
                // it is jitter, not a failure, and a session must not be lost
                // over it.
                logger.w(LogCategory.SERVICE, error) { "no WiFi low-latency lock" }
                null
            }
        }
        if (wakeLock == null) {
            wakeLock = try {
                appContext.getSystemService(PowerManager::class.java)
                    ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_TAG)
                    ?.apply {
                        setReferenceCounted(false)
                        acquire(config.session.maxDuration.inWholeMilliseconds)
                    }
            } catch (error: RuntimeException) {
                logger.w(LogCategory.SERVICE, error) { "no partial wake lock" }
                null
            }
        }
    }

    fun release() = synchronized(lock) {
        wifiLock?.takeIf { it.isHeld }?.release()
        wifiLock = null
        // isHeld can already be false: the timeout may have fired, and releasing
        // a lock the platform has already reclaimed throws.
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    private companion object {
        const val WIFI_TAG = "saikai-ptt:voice"
        const val WAKE_TAG = "saikai-ptt:voice"
    }
}
