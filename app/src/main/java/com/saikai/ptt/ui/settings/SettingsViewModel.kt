package com.saikai.ptt.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.saikai.ptt.core.domain.AppLanguage
import com.saikai.ptt.service.ServiceState
import com.saikai.ptt.usecase.Diagnostics
import com.saikai.ptt.usecase.ObserveActiveUser
import com.saikai.ptt.usecase.SettingsUseCases
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The settings screen and the language screen.
 *
 * Every value here is a stored setting read back through a use case, so the
 * screen renders what is actually persisted rather than what was last tapped.
 * That distinction matters for the interrupt switch: it is a policy other
 * devices act on, and a switch that looked on while the store said off would be
 * a device that quietly refuses interruptions it claims to accept.
 */
class SettingsViewModel(
    private val useCases: SettingsUseCases,
    private val activeUser: ObserveActiveUser,
    private val isDebugBuild: Boolean,
) : ViewModel() {

    val language: StateFlow<AppLanguage> = useCases.observeLanguage()
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppLanguage.DEFAULT)

    val allowInterrupt: StateFlow<Boolean> = useCases.observeAllowInterrupt()
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /**
     * Whether the communication service is up.
     *
     * A switch rather than a read-out: Home offers a way to start it when it is
     * stopped, and nothing else in the app offers a way to stop it. Leaving the
     * only route to "force stop in system settings" would be leaving the user
     * to kill the app to silence it.
     */
    val serviceRunning: StateFlow<Boolean> = useCases.observeServiceState()
        .map { it == ServiceState.READY || it == ServiceState.STARTING || it == ServiceState.DEGRADED }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val activeUserName: StateFlow<String?> = activeUser()
        .map { it?.displayName }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _diagnostics = MutableStateFlow<Diagnostics?>(null)

    /** Null until read, and null forever in a release build. */
    val diagnostics: StateFlow<Diagnostics?> = _diagnostics.asStateFlow()

    init {
        // Read once, on the way in. The page is a readout rather than a live
        // dashboard, and `docs/04_UI_UX.md` section 37 asks for the values, not
        // for them to tick.
        if (isDebugBuild) {
            viewModelScope.launch { _diagnostics.value = useCases.readDiagnostics() }
        }
    }

    fun setServiceRunning(context: Context, running: Boolean) {
        useCases.setServiceRunning(context, running)
    }

    fun setAllowInterrupt(allow: Boolean) {
        viewModelScope.launch { useCases.setAllowInterrupt(allow) }
    }

    /**
     * Changes the language.
     *
     * [onApplied] runs after the write, because below Android 13 the choice
     * only takes effect when an Activity attaches -- so the caller has to
     * recreate itself, and it must not do so before the value is stored
     * (`docs/04_UI_UX.md` section 35.1).
     */
    fun setLanguage(context: Context, language: AppLanguage, onApplied: () -> Unit) {
        viewModelScope.launch {
            useCases.setLanguage(context, language)
            onApplied()
        }
    }

    class Factory(
        private val useCases: SettingsUseCases,
        private val activeUser: ObserveActiveUser,
        private val isDebugBuild: Boolean,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            SettingsViewModel(useCases, activeUser, isDebugBuild) as T
    }
}
