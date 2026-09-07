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
 * Placeholder content: the real notification, with the active user, the peer
 * being talked to and its actions, is Task31. What is not placeholder is the
 * channel and the foreground type, because both are load-bearing on modern
 * Android and both are awkward to change once devices have the channel.
 *
 * The channel is LOW importance: this notification exists because the platform
 * requires one for a service that keeps a socket open, not because the user
 * needs to be told anything. Making a noise every time the app starts receiving
 * would train them to turn it off, and turning it off is what stops the product
 * working.
 */
class ServiceNotifications(private val context: Context) {

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

    fun ongoing(): Notification = Notification.Builder(context, CHANNEL_ID)
        .setContentTitle(context.getString(R.string.notification_service_title))
        .setContentText(context.getString(R.string.notification_service_text))
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
        service.startForeground(
            ONGOING_ID,
            ongoing(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
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
    }
}
