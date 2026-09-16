package com.saikai.ptt.ui.permissions

import androidx.annotation.StringRes
import com.saikai.ptt.R
import com.saikai.ptt.permissions.AppPermission
import com.saikai.ptt.permissions.PermissionState

/**
 * What the walkthrough and the status screen show.
 *
 * Both are views of the same five rows: the walkthrough shows them one at a
 * time with the consequence spelled out, the status screen shows them together
 * (`docs/01_PRD.md` section 24, `docs/04_UI_UX.md` section 36).
 */

/** One permission as it stands. */
data class PermissionRow(
    val permission: AppPermission,
    val state: PermissionState,
) {
    val isSatisfied: Boolean get() = state.isSatisfied
}

/**
 * One page of the walkthrough.
 *
 * [index] and [total] are carried rather than derived in the UI so that the
 * progress line and the "this is the last one" button agree with the list the
 * view model is actually stepping through.
 */
data class OnboardingStep(
    val row: PermissionRow,
    val index: Int,
    val total: Int,
) {
    val isLast: Boolean get() = index == total - 1
}

/** The name of a permission, as a user would say it. */
@StringRes
internal fun AppPermission.titleRes(): Int = when (this) {
    AppPermission.MICROPHONE -> R.string.permission_microphone
    AppPermission.NOTIFICATIONS -> R.string.permission_notifications
    AppPermission.OVERLAY -> R.string.permission_overlay
    AppPermission.BATTERY_OPTIMIZATION -> R.string.permission_battery
    AppPermission.AUTO_START -> R.string.permission_auto_start
}

/**
 * What is lost without it.
 *
 * Required by `docs/04_UI_UX.md` section 36.1 on both screens, and it is the
 * only honest way to ask: a permission request with no stated consequence is a
 * demand, and this app can run without every one of these.
 */
@StringRes
internal fun AppPermission.consequenceRes(): Int = when (this) {
    AppPermission.MICROPHONE -> R.string.permission_microphone_without
    AppPermission.NOTIFICATIONS -> R.string.permission_notifications_without
    AppPermission.OVERLAY -> R.string.permission_overlay_without
    AppPermission.BATTERY_OPTIMIZATION -> R.string.permission_battery_without
    AppPermission.AUTO_START -> R.string.permission_auto_start_without
}

/**
 * The fallback text, shown when there is no settings screen to open.
 *
 * Two cases reach it: auto-start, which has no platform intent at all, and any
 * permission whose intent the device refuses to launch. Section 36.1 requires
 * words in both cases rather than a button that does nothing.
 */
@StringRes
internal fun AppPermission.manualGuidanceRes(): Int = when (this) {
    AppPermission.MICROPHONE -> R.string.permission_manual_microphone
    AppPermission.NOTIFICATIONS -> R.string.permission_manual_notifications
    AppPermission.OVERLAY -> R.string.permission_manual_overlay
    AppPermission.BATTERY_OPTIMIZATION -> R.string.permission_manual_battery
    AppPermission.AUTO_START -> R.string.permission_manual_auto_start
}

/** The status word beside a row. Never colour alone (section 3). */
@StringRes
internal fun PermissionState.labelRes(): Int = when (this) {
    PermissionState.GRANTED -> R.string.permission_state_granted
    PermissionState.DENIED -> R.string.permission_state_denied
    PermissionState.UNKNOWN -> R.string.permission_state_unknown
    PermissionState.NOT_APPLICABLE -> R.string.permission_state_not_applicable
}
