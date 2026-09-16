package com.saikai.ptt.usecase

import com.saikai.ptt.core.domain.SettingsRepository
import com.saikai.ptt.permissions.AppPermission
import com.saikai.ptt.permissions.PermissionInspector
import com.saikai.ptt.permissions.PermissionState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** What the permission walkthrough and the status screen can observe and do. */
class PermissionUseCases(
    val readPermissions: ReadPermissions,
    val observeFirstRun: ObserveFirstRun,
    val completeGuidance: CompleteGuidance,
    val completeFirstLaunch: CompleteFirstLaunch,
)

/**
 * Reads the system's answer for every permission, right now.
 *
 * A function rather than a flow, because there is nothing to observe: Android
 * publishes no change notification for any of these. The screens re-read when
 * the Activity resumes, which is exactly when a trip to system settings ends.
 *
 * This is also the line the ViewModel is not allowed to cross on its own --
 * `docs/02_Architecture.md` section 4.2 forbids it touching a Context, and the
 * Context is on the other side of this call.
 */
class ReadPermissions(private val inspector: PermissionInspector) {
    operator fun invoke(): Map<AppPermission, PermissionState> = inspector.all()
}

/** How far the first run got, as stored. */
data class FirstRunState(
    /** The walkthrough has been through once. It never runs again (section 36.1). */
    val guidanceShown: Boolean,
    /** The whole first-run flow finished: guidance seen and a name exists. */
    val firstLaunchCompleted: Boolean,
)

class ObserveFirstRun(private val settings: SettingsRepository) {
    operator fun invoke(): Flow<FirstRunState> = settings.settings
        .map { FirstRunState(it.permissionGuidanceShown, it.firstLaunchCompleted) }
        .distinctUntilChanged()
}

/**
 * Records that the walkthrough has been seen.
 *
 * Written whether the user granted everything or skipped all of it. That is the
 * point: section 36.1 forbids nagging, so "shown" means shown, not "succeeded".
 * Everything afterwards is reached from the status screen, at the user's
 * initiative.
 */
class CompleteGuidance(private val settings: SettingsRepository) {
    suspend operator fun invoke() {
        settings.update { current ->
            if (current.permissionGuidanceShown) current
            else current.copy(permissionGuidanceShown = true)
        }
    }
}

/** Records that the first run finished. Idempotent, so it costs nothing to call again. */
class CompleteFirstLaunch(private val settings: SettingsRepository) {
    suspend operator fun invoke() {
        settings.update { current ->
            if (current.firstLaunchCompleted) current else current.copy(firstLaunchCompleted = true)
        }
    }
}
