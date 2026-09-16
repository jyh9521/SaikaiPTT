package com.saikai.ptt.permissions

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * The five things this app needs from the system, and cannot demand.
 *
 * Listed as one enum because the same five appear in three places -- the
 * first-run walkthrough, the status screen and the Home notice -- and a second
 * list would eventually disagree with this one
 * (`docs/01_PRD.md` section 24.1, `docs/04_UI_UX.md` section 36).
 *
 * The order is the order the walkthrough asks in, which is the order of how
 * much is lost without each one.
 */
enum class AppPermission {

    /** Without it: cannot transmit. Receiving is unaffected. */
    MICROPHONE,

    /**
     * Without it: the foreground service still runs and still receives, but its
     * notification is not shown, so the user has no visible sign the app is on
     * -- and some ROMs are quicker to reclaim a process whose notification the
     * user has dismissed.
     */
    NOTIFICATIONS,

    /** Without it: background alerts fall back to the notification (section 18.2). */
    OVERLAY,

    /** Without it: the system may reclaim the process during long idle periods. */
    BATTERY_OPTIMIZATION,

    /**
     * Without it: the service may not come back after a reboot.
     *
     * A vendor setting with no standard API, no standard name and no standard
     * place. It can be explained and never checked or opened -- see
     * [PermissionState.UNKNOWN] and [PermissionNavigator].
     */
    AUTO_START,
}

/** What the system currently says about a permission. */
enum class PermissionState {

    GRANTED,

    /** Asked for and refused, or never granted. */
    DENIED,

    /**
     * The app cannot tell.
     *
     * Only [AppPermission.AUTO_START] is ever this, and it is the honest
     * answer: there is no API for it. Reporting a guess as a fact on the status
     * screen would be worse than saying nothing -- the whole point of that
     * screen is that it shows what is actually true
     * (`docs/04_UI_UX.md` section 36).
     */
    UNKNOWN,

    /** Not a concept on this Android version, so nothing to ask for. */
    NOT_APPLICABLE,
    ;

    /** Whether anything is missing. [UNKNOWN] is not missing; it is unknown. */
    val isSatisfied: Boolean get() = this == GRANTED || this == NOT_APPLICABLE
}

/**
 * Reads the real state of each permission, every time it is asked.
 *
 * Nothing is cached. The user can change any of these in system settings while
 * the app is in the background, and a cached answer would be the status screen
 * lying about exactly the thing it exists to report. Each read is a couple of
 * cheap system calls.
 *
 * Takes the application context: it outlives every screen and none of these
 * questions are about an Activity.
 */
class PermissionInspector(private val context: Context) {

    fun state(permission: AppPermission): PermissionState = when (permission) {
        AppPermission.MICROPHONE -> granted(Manifest.permission.RECORD_AUDIO)

        // Deliberately not checkSelfPermission(POST_NOTIFICATIONS), even on
        // Android 13+. That answers "was the runtime permission granted", and
        // the question worth answering is "will a notification be shown" --
        // which is also false when the user has switched the app's
        // notifications off in system settings, on every version since
        // Android 7. Asking the notification manager covers both.
        AppPermission.NOTIFICATIONS ->
            if (notificationManager()?.areNotificationsEnabled() == true) {
                PermissionState.GRANTED
            } else {
                PermissionState.DENIED
            }

        AppPermission.OVERLAY ->
            if (Settings.canDrawOverlays(context)) {
                PermissionState.GRANTED
            } else {
                PermissionState.DENIED
            }

        AppPermission.BATTERY_OPTIMIZATION -> {
            val power = context.getSystemService(PowerManager::class.java)
            when {
                power == null -> PermissionState.UNKNOWN
                power.isIgnoringBatteryOptimizations(context.packageName) ->
                    PermissionState.GRANTED

                else -> PermissionState.DENIED
            }
        }

        // No API exists. See the enum's own note.
        AppPermission.AUTO_START -> PermissionState.UNKNOWN
    }

    fun all(): Map<AppPermission, PermissionState> =
        AppPermission.entries.associateWith { state(it) }

    /**
     * Whether this permission is asked for with a system dialog rather than a
     * trip to Settings.
     *
     * Only two are, and only one of those on every version: POST_NOTIFICATIONS
     * became a runtime permission in Android 13 and cannot be requested before
     * it (`.claude/CLAUDE.md` section 4.1).
     */
    fun runtimePermission(permission: AppPermission): String? = when (permission) {
        AppPermission.MICROPHONE -> Manifest.permission.RECORD_AUDIO
        AppPermission.NOTIFICATIONS ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Manifest.permission.POST_NOTIFICATIONS
            } else {
                // Before Android 13 there is no permission to request; the
                // switch lives in system settings, so that is where the button
                // goes instead.
                null
            }

        else -> null
    }

    private fun granted(permission: String): PermissionState =
        if (context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) {
            PermissionState.GRANTED
        } else {
            PermissionState.DENIED
        }

    private fun notificationManager(): NotificationManager? =
        context.getSystemService(NotificationManager::class.java)
}

/**
 * Builds the system-settings intent for a permission, or admits there is none.
 *
 * Every intent here is a platform constant. No vendor package or activity name
 * appears anywhere in this file, and none ever should: `docs/01_PRD.md` section
 * 24.2 and `docs/04_UI_UX.md` section 36.1 both forbid it, and the reason is
 * practical rather than stylistic -- a hard-coded vendor activity is an
 * `ActivityNotFoundException` on the next ROM version, or worse, a
 * `SecurityException` on a device where it is not exported.
 *
 * So the contract is: return an intent that might work, and let the caller
 * handle the launch failing. [AppPermission.AUTO_START] returns null, because
 * for that one there is no platform intent at all and inventing one would mean
 * guessing at a vendor.
 */
object PermissionNavigator {

    fun settingsIntent(context: Context, permission: AppPermission): Intent? = when (permission) {
        AppPermission.MICROPHONE -> appDetails(context)

        AppPermission.NOTIFICATIONS ->
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)

        AppPermission.OVERLAY ->
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.fromParts("package", context.packageName, null),
            )

        // The *list* screen, not ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS.
        // The direct request needs the REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
        // permission, which Google Play restricts to a short list of app
        // categories that a walkie-talkie is not on. The list screen needs
        // nothing and lands the user one tap away.
        AppPermission.BATTERY_OPTIMIZATION ->
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)

        AppPermission.AUTO_START -> null
    }

    private fun appDetails(context: Context): Intent =
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null),
        )
}
