package com.saikai.ptt.di

import com.saikai.ptt.core.config.SaikaiConfig
import com.saikai.ptt.core.domain.DeviceIdentityProvider
import com.saikai.ptt.core.domain.LocalUserRepository
import com.saikai.ptt.core.domain.SettingsLocalUserRepository
import com.saikai.ptt.core.domain.SettingsRepository
import com.saikai.ptt.core.domain.StoredDeviceIdentityProvider
import com.saikai.ptt.core.logger.LogSink
import com.saikai.ptt.core.logger.Logger
import com.saikai.ptt.logging.AndroidLogSink

/**
 * Application-scope dependencies: the objects that live as long as the process.
 *
 * Hand-written rather than a DI framework (`docs/02_Architecture.md` section 7).
 * The object graph is a few dozen entries, the wiring is readable as plain code
 * with nothing generated, and tests construct what they need directly.
 *
 * Two scopes exist. This is the outer one. Communication components --
 * transport, discovery, presence, session manager, audio, overlay -- belong to a
 * **service scope** created and destroyed with the Foreground Service (Task15),
 * so that stopping the service actually releases the sockets and audio devices
 * instead of leaving them owned by a process-lifetime object.
 *
 * @param isDebugBuild supplied by the caller rather than read from `BuildConfig`
 *   here, so the container stays constructible in a plain JVM test.
 * @param logSink overridable so a JVM test can assemble the container without
 *   `android.util.Log`, which throws outside an instrumented environment.
 * @param settingsRepositoryFactory takes the assembled [Logger] and returns the
 *   settings store. Passed as a factory rather than a Context so the container
 *   itself needs no Android types and stays constructible in a plain JVM test.
 */
class AppContainer(
    isDebugBuild: Boolean,
    logSink: LogSink = AndroidLogSink(),
    settingsRepositoryFactory: (Logger) -> SettingsRepository,
) {

    /**
     * Every tunable value in the app. Injected rather than read from a global so
     * tests can shorten timeouts instead of waiting them out.
     */
    val config: SaikaiConfig = SaikaiConfig.forBuild(isDebugBuild)

    /**
     * Built from the same config, so the build type decides logging in exactly
     * one place.
     */
    val logger: Logger = Logger(config.logging, logSink)

    /**
     * The single entry point for persisted settings. Nothing else in the app
     * opens DataStore (`docs/05_DataModel.md` section 3).
     *
     * Lazy: a process started only for a broadcast receiver should not open the
     * store until something actually reads a setting.
     */
    val settingsRepository: SettingsRepository by lazy { settingsRepositoryFactory(logger) }

    /**
     * This installation's permanent identity, generated on first use.
     *
     * Application scope because it must be the same value everywhere: the peer
     * table, every outgoing packet header and every history record key off it,
     * and two instances could disagree during the first launch that creates it.
     */
    val deviceIdentity: DeviceIdentityProvider by lazy {
        StoredDeviceIdentityProvider(settingsRepository, logger)
    }

    /**
     * The names stored on this device and which one is in use.
     *
     * The active name is transmitted with every PTT session, so the whole app
     * must agree on it -- hence one instance, not one per screen.
     */
    val localUsers: LocalUserRepository by lazy {
        SettingsLocalUserRepository(settingsRepository)
    }
}
