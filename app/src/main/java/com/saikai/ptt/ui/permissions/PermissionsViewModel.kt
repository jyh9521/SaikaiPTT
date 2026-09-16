package com.saikai.ptt.ui.permissions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.saikai.ptt.permissions.AppPermission
import com.saikai.ptt.permissions.PermissionState
import com.saikai.ptt.usecase.PermissionUseCases
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The permission walkthrough and the permission status screen.
 *
 * One view model for both, because they are the same five rows read the same
 * way. The only thing the walkthrough adds is a cursor.
 *
 * ### Nothing here is observed, so everything is re-read
 *
 * Android publishes no change notification for a permission. The user leaves
 * for system settings, grants something, and comes back -- and the only signal
 * of that is the Activity resuming. So [refresh] exists and the Activity calls
 * it on every resume. A status screen that showed a remembered answer would be
 * wrong precisely when it mattered.
 */
class PermissionsViewModel(private val useCases: PermissionUseCases) : ViewModel() {

    private val _rows = MutableStateFlow(read())

    /** Every permission and its state, as of the last [refresh]. */
    val rows: StateFlow<List<PermissionRow>> = _rows.asStateFlow()

    private val _stepIndex = MutableStateFlow(0)

    /** Which page of the walkthrough is showing. */
    val step: StateFlow<OnboardingStep?> = combine(_rows, _stepIndex) { rows, index ->
        rows.getOrNull(index)?.let { OnboardingStep(it, index, rows.size) }
    }
        .distinctUntilChanged()
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            _rows.value.firstOrNull()?.let { OnboardingStep(it, 0, _rows.value.size) },
        )

    /**
     * True once the walkthrough has run, and **null until storage answers**.
     *
     * Null is not laziness. Both this and the name gate are read from the same
     * DataStore flow but through two view models, so they can settle a frame
     * apart; without a third "not known yet" value the caller would have to
     * guess, and either guess flashes a screen at somebody. The caller waits
     * for null to clear instead. It never runs again once true (section 36.1).
     */
    val guidanceShown: StateFlow<Boolean?> = useCases.observeFirstRun()
        .map { it.guidanceShown }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * Whether anything has actually been refused.
     *
     * Refused, not merely absent: auto-start reads UNKNOWN on every device
     * because there is no API for it, and a notice that can never be cleared is
     * a notice people learn to look past.
     */
    val anyDenied: StateFlow<Boolean> = _rows
        .map { rows -> rows.any { it.state == PermissionState.DENIED } }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** Re-reads every permission from the system. */
    fun refresh() {
        _rows.value = read()
    }

    /**
     * Moves to the next page, whether the user granted it or skipped it.
     *
     * The two are deliberately the same call. Skipping is a supported answer
     * (`docs/01_PRD.md` section 24.2) and the app keeps working without any of
     * these, so there is nothing for a skip to do differently.
     */
    fun advance() {
        refresh()
        val index = _stepIndex.value
        if (index >= _rows.value.lastIndex) finishGuidance() else _stepIndex.value = index + 1
    }

    fun back() {
        val index = _stepIndex.value
        if (index > 0) _stepIndex.value = index - 1
    }

    fun finishGuidance() {
        viewModelScope.launch { useCases.completeGuidance() }
    }

    /** Called once Home is actually reachable: guidance seen and a name exists. */
    fun markFirstLaunchCompleted() {
        viewModelScope.launch { useCases.completeFirstLaunch() }
    }

    private fun read(): List<PermissionRow> {
        val states = useCases.readPermissions()
        // Enum order, which is the order of how much is lost -- and therefore
        // the order the walkthrough should ask in.
        return AppPermission.entries.mapNotNull { permission ->
            states[permission]?.let { PermissionRow(permission, it) }
        }
    }

    class Factory(private val useCases: PermissionUseCases) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            PermissionsViewModel(useCases) as T
    }
}
