package com.saikai.ptt.usecase

import android.content.Context
import com.saikai.ptt.core.config.SaikaiConfig
import com.saikai.ptt.core.domain.AppLanguage
import com.saikai.ptt.core.domain.DeviceIdentityProvider
import com.saikai.ptt.core.domain.SettingsRepository
import com.saikai.ptt.core.logger.LogCategory
import com.saikai.ptt.core.logger.LogLevel
import com.saikai.ptt.core.session.SessionState
import com.saikai.ptt.locale.LocaleController
import com.saikai.ptt.network.LocalAddresses
import com.saikai.ptt.service.ServiceState
import com.saikai.ptt.service.ServiceStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** What the settings screens can observe and do. */
class SettingsUseCases(
    val observeLanguage: ObserveLanguage,
    val observeAllowInterrupt: ObserveAllowInterrupt,
    val setLanguage: SetLanguage,
    val setAllowInterrupt: SetAllowInterrupt,
    val observeOverlayEnabled: ObserveOverlayEnabled,
    val setOverlayEnabled: SetOverlayEnabled,
    val observeServiceState: ObserveServiceState,
    val setServiceRunning: SetServiceRunning,
    val readDiagnostics: ReadDiagnostics,
)

class ObserveLanguage(private val settings: SettingsRepository) {
    operator fun invoke(): Flow<AppLanguage> =
        settings.settings.map { it.language }.distinctUntilChanged()
}

/**
 * The receiver-side interrupt policy.
 *
 * "May other devices interrupt a call I am in", not "may I interrupt others"
 * (`.claude/CLAUDE.md` section 7.1). The caller has no switch and no way to
 * learn this one: it always sends an ordinary request and the callee answers.
 */
class ObserveAllowInterrupt(private val settings: SettingsRepository) {
    operator fun invoke(): Flow<Boolean> =
        settings.settings.map { it.allowInterrupt }.distinctUntilChanged()
}

class SetAllowInterrupt(private val settings: SettingsRepository) {
    suspend operator fun invoke(allow: Boolean) {
        settings.update { it.copy(allowInterrupt = allow) }
    }
}

/**
 * Whether the user wants the floating indicator.
 *
 * Separate from the system permission on purpose. Revoking SYSTEM_ALERT_WINDOW
 * is a trip to system settings and affects nothing else the app might ever want
 * a window for; this is the switch for "not right now", and the indicator
 * requires both (`docs/04_UI_UX.md` section 20).
 */
class ObserveOverlayEnabled(private val settings: SettingsRepository) {
    operator fun invoke(): Flow<Boolean> =
        settings.settings.map { it.overlayEnabled }.distinctUntilChanged()
}

class SetOverlayEnabled(private val settings: SettingsRepository) {
    suspend operator fun invoke(enabled: Boolean) {
        settings.update { it.copy(overlayEnabled = enabled) }
    }
}

/**
 * Changes the interface language.
 *
 * Takes a Context for the same reason [SetServiceRunning] does: it is the
 * caller's, not one held here. Below Android 13 the locale is applied by
 * wrapping an Activity's base context, so which context this is asked with is a
 * fact about where the call comes from (`docs/04_UI_UX.md` section 35.1).
 */
class SetLanguage(private val locales: LocaleController) {
    suspend operator fun invoke(context: Context, language: AppLanguage) {
        locales.set(context, language)
    }
}

/**
 * Everything the developer information page shows, as one snapshot.
 *
 * A snapshot rather than flows, because the page is a readout and not a live
 * dashboard: it is opened to answer "what is this device doing right now", and
 * a value that moves while being read is harder to relay to somebody than one
 * that was true at a stated moment. Re-read by leaving and coming back.
 *
 * Debug builds only (`docs/04_UI_UX.md` section 37).
 */
data class Diagnostics(
    val deviceId: String,
    val addresses: List<String>,
    val protocolVersion: Int,
    val controlPort: Int,
    val voicePort: Int,
    val serviceState: ServiceState,
    val peerCount: Int,
    val sessionState: String,
    val heartbeatSeconds: Long,
    val peerTimeoutSeconds: Long,
    val loggingEnabled: Boolean,
)

class ReadDiagnostics(
    private val identity: DeviceIdentityProvider,
    private val config: SaikaiConfig,
    private val status: ServiceStatus,
) {
    suspend operator fun invoke(): Diagnostics = Diagnostics(
        deviceId = identity.deviceId().value,
        addresses = LocalAddresses.ipv4(),
        protocolVersion = config.protocol.version,
        controlPort = config.network.controlPort,
        voicePort = config.network.voicePort,
        serviceState = status.state.value,
        peerCount = status.peers.value.size,
        // The class name, not a rendered sentence: this page exists to be read
        // by whoever is debugging, and Requesting/Transmitting/Receiving is
        // exactly the vocabulary the logs and the state machine use.
        sessionState = status.session.value.let {
            if (it == SessionState.Idle) "Idle" else it.javaClass.simpleName
        },
        heartbeatSeconds = config.presence.heartbeatInterval.inWholeSeconds,
        peerTimeoutSeconds = config.presence.peerTimeout.inWholeSeconds,
        loggingEnabled = config.logging.isEnabled(LogLevel.DEBUG, LogCategory.NETWORK),
    )
}
