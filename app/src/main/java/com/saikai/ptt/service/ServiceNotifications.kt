package com.saikai.ptt.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import com.saikai.ptt.R

/**
 * The ongoing notification the foreground service is required to show.
 *
 * The channel is LOW importance: this notification exists because the platform
 * requires one for a service that keeps a socket open, not because the user
 * needs to be told anything. Making a noise every time the app starts receiving
 * would train them to turn it off, and turning it off is what stops the product
 * working.
 *
 * ### It says two things, and changes between them at most twice a session
 *
 * Resident, it says the app is running. While a transmission is coming in and
 * nothing else is showing it, it names the speaker (`docs/04_UI_UX.md` section
 * 18.2). `docs/01_PRD.md` section 22 caps that at two changes per session --
 * one at the start and one at the end -- and forbids a per-second refresh,
 * which is why the caller drives this from the *edges* of the session state
 * rather than from anything that ticks.
 *
 * ### One state, one call
 *
 * Both the text and the foreground service type are held here, and every change
 * goes through [apply], which re-posts with the current pair. That matters
 * because the two change independently: promoting to the microphone type
 * re-posts the notification, and if the type call did not carry the current
 * text it would quietly revert it. A call that changes nothing does nothing, so
 * the count of posts stays honest.
 *
 * ### A denied notification permission is not an error
 *
 * From Android 13, POST_NOTIFICATIONS is a runtime permission and the user may
 * refuse it. `startForeground` still works, the service still runs, and the
 * notification is simply not displayed -- so receiving keeps working and the
 * user has no way to see that it is. That is the platform's trade, not
 * something to fail on, and nothing here treats it as a failure.
 */
class ServiceNotifications(private val context: Context) {

    private val lock = Any()

    /** The peer being listened to, or null when nothing is coming in. */
    private var receivingFrom: String? = null

    /** The foreground service type currently declared. */
    private var currentType: Int = RESIDENT_TYPE

    /**
     * Creates the channel. Safe to call repeatedly; the platform ignores a
     * channel that already exists, except for the fields the user can change.
     */
    fun ensureChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notification_channel_communication),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.notification_channel_communication_description)
            setShowBadge(false)
        }
        context.getSystemService(NotificationManager::class.java)
            ?.createNotificationChannel(channel)
    }

    fun ongoing(): Notification = ongoing(synchronized(lock) { receivingFrom })

    private fun ongoing(receivingFrom: String?): Notification = Notification.Builder(
        context,
        CHANNEL_ID,
    )
        .setContentTitle(context.getString(R.string.notification_service_title))
        .setContentText(
            if (receivingFrom == null) {
                context.getString(R.string.notification_service_text)
            } else {
                context.getString(R.string.notification_service_receiving, receivingFrom)
            }
        )
        // Placeholder icon; Task31 supplies a proper monochrome status icon.
        .setSmallIcon(R.drawable.ic_launcher_foreground)
        .setCategory(Notification.CATEGORY_SERVICE)
        .apply {
            openApp()?.let { setContentIntent(it) }
            // Android 12 defers a foreground-service notification for up to ten
            // seconds when its channel is below IMPORTANCE_DEFAULT, so that a
            // service which only runs for a moment never flashes one up. This
            // service is not that: it runs until the user stops it, and the
            // notification is the only evidence that receiving is live. Asking
            // for it immediately is the honest signal, and it does not raise the
            // importance -- the notification stays silent.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            }
        }
        .setOngoing(true)
        .setShowWhen(false)
        .build()

    /**
     * Calls `startForeground` with the resident service type.
     *
     * `connectedDevice` only. Adding `microphone` is a runtime promotion made
     * while the talk button is held (ADR-005 section 2), and Android 14 forbids
     * that promotion from the background -- which is precisely why receiving
     * does not need it and works with the screen off.
     *
     * Idempotent: the platform treats a second call as an update to the same
     * notification.
     */
    fun startForeground(service: Service) {
        // Unconditional, unlike every other path here: the platform gives a
        // service started with startForegroundService only seconds to make this
        // call, and skipping it because nothing appears to have changed would
        // be skipping the one that has to happen.
        synchronized(lock) {
            currentType = RESIDENT_TYPE
            service.startForeground(ONGOING_ID, ongoing(receivingFrom), RESIDENT_TYPE)
        }
    }

    /**
     * Names the peer whose transmission is playing.
     *
     * `docs/04_UI_UX.md` section 18.2: with no overlay on screen this is the
     * only sign the user gets that their device is speaking to them, and
     * "no visible feedback at all" is explicitly not allowed.
     */
    fun showReceiving(service: Service, peerName: String) {
        synchronized(lock) { apply(service, currentType, peerName) }
    }

    /** Back to saying nothing more than that the app is running. */
    fun showResident(service: Service) {
        synchronized(lock) { apply(service, currentType, receiving = null) }
    }

    /**
     * Adds `microphone` to the running service's types, for the length of one
     * transmission (ADR-005 section 2).
     *
     * Android 14 forbids this promotion from the background, and the refusal
     * arrives as an exception rather than a return value:
     * `ForegroundServiceStartNotAllowedException` (an `IllegalStateException`)
     * when the app is not foreground, and a `SecurityException` when it has no
     * while-in-use access to the microphone. Both mean the same thing to the
     * caller -- this device may not transmit right now -- so both become false
     * rather than an exception thrown out of a button press.
     *
     * The caller checks visibility first, so this is the backstop rather than
     * the check: the two can disagree, because the app can be backgrounded
     * between the question and the answer.
     *
     * @return true when the service is running as a microphone service.
     */
    fun promoteToMicrophone(service: Service): Boolean =
        synchronized(lock) { apply(service, TRANSMITTING_TYPE, receivingFrom) }

    /**
     * Drops back to the resident type.
     *
     * Called on every path out of a transmission, not only on the button coming
     * up. A service left declaring `microphone` after the user stopped talking
     * keeps the microphone indicator lit in the status bar, which is an
     * unambiguous claim to the user that the app is listening to them.
     */
    fun demoteFromMicrophone(service: Service): Boolean =
        synchronized(lock) { apply(service, RESIDENT_TYPE, receivingFrom) }

    /**
     * Re-posts with the state after the change, or does nothing if there is none.
     *
     * The caller holds [lock] and passes the whole state, so a change to one
     * half cannot drop the other -- which is the failure this exists to
     * prevent: promoting to the microphone type re-posts the notification, and
     * a type call that did not carry the current text would quietly revert it.
     *
     * Doing nothing when nothing changed is what keeps the two-updates-a-session
     * cap of `docs/01_PRD.md` section 22 true by construction rather than by
     * the caller being careful.
     *
     * @return false only when the platform refused a type it does not allow
     *   from the background -- a real answer about whether this device may
     *   transmit, not an error.
     */
    private fun apply(service: Service, type: Int, receiving: String?): Boolean {
        if (type == currentType && receiving == receivingFrom) return true

        try {
            service.startForeground(ONGOING_ID, ongoing(receiving), type)
        } catch (_: IllegalStateException) {
            // Android 14 refuses a promotion to the microphone type from the
            // background. Nothing changed, so nothing is recorded as changed.
            return false
        } catch (_: SecurityException) {
            return false
        }

        currentType = type
        receivingFrom = receiving
        return true
    }

    /**
     * Opens the app when the notification is tapped.
     *
     * Resolved through the package manager rather than by naming the Activity
     * class. The service must stay usable with no Activity alive at all -- that
     * is what makes background receive work -- and an import of the UI package
     * from here is exactly the dependency that erodes it. It is also checked:
     * `ArchitectureRulesTest` fails the build on it.
     */
    private fun openApp(): PendingIntent? {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP }
            ?: return null
        // FLAG_IMMUTABLE is mandatory from Android 12 (ADR-005 section 7).
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    companion object {
        const val CHANNEL_ID: String = "saikai.communication"
        const val ONGOING_ID: Int = 1

        /** What the service declares while it is only discovering and receiving. */
        private const val RESIDENT_TYPE: Int =
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE

        /** What it declares while the talk button is held. */
        private const val TRANSMITTING_TYPE: Int =
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
    }
}
