package com.saikai.ptt.di

import com.saikai.ptt.core.config.SaikaiConfig
import com.saikai.ptt.core.domain.DeviceIdentityProvider
import com.saikai.ptt.core.domain.HistoryRepository
import com.saikai.ptt.core.domain.LocalUserRepository
import com.saikai.ptt.core.domain.SettingsLocalUserRepository
import com.saikai.ptt.core.domain.SettingsRepository
import com.saikai.ptt.core.domain.StoredDeviceIdentityProvider
import com.saikai.ptt.core.logger.LogSink
import com.saikai.ptt.core.logger.Logger
import com.saikai.ptt.locale.LocaleController
import com.saikai.ptt.AppVisibility
import com.saikai.ptt.permissions.PermissionInspector
import com.saikai.ptt.logging.AndroidLogSink
import com.saikai.ptt.service.PttGateway
import com.saikai.ptt.service.ServiceStatus
import com.saikai.ptt.usecase.HomeUseCases
import com.saikai.ptt.usecase.ObserveActiveUser
import com.saikai.ptt.usecase.ObserveOutcomes
import com.saikai.ptt.usecase.ObserveStorageErrors
import com.saikai.ptt.usecase.ObservePeers
import com.saikai.ptt.usecase.ObserveServiceState
import com.saikai.ptt.usecase.ObserveSession
import com.saikai.ptt.usecase.SetServiceRunning
import com.saikai.ptt.usecase.StartPtt
import com.saikai.ptt.usecase.StopPtt
import com.saikai.ptt.usecase.CreateLocalUser
import com.saikai.ptt.usecase.DeleteLocalUser
import com.saikai.ptt.usecase.ObserveLocalUsers
import com.saikai.ptt.usecase.RenameLocalUser
import com.saikai.ptt.usecase.SwitchActiveUser
import com.saikai.ptt.usecase.UserUseCases
import com.saikai.ptt.usecase.CompleteFirstLaunch
import com.saikai.ptt.usecase.CompleteGuidance
import com.saikai.ptt.usecase.ObserveFirstRun
import com.saikai.ptt.usecase.PermissionUseCases
import com.saikai.ptt.usecase.ReadPermissions
import com.saikai.ptt.usecase.ObserveAllowInterrupt
import com.saikai.ptt.usecase.ObserveLanguage
import com.saikai.ptt.usecase.ObserveOverlayEnabled
import com.saikai.ptt.usecase.SetOverlayEnabled
import com.saikai.ptt.usecase.ReadDiagnostics
import com.saikai.ptt.usecase.SetAllowInterrupt
import com.saikai.ptt.usecase.SetLanguage
import com.saikai.ptt.usecase.SettingsUseCases

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
    /**
     * Reads the system's permission state. Null in a plain JVM test, where
     * there is no system to ask and no screen to ask on its behalf -- which is
     * also why [permissionUseCases] is the only thing that touches it.
     */
    private val permissionInspectorFactory: (() -> PermissionInspector)? = null,
    /**
     * Opens the communication history. Null in a plain JVM test, where there is
     * no SQLite to open and nothing that reads history is under test.
     */
    private val historyRepositoryFactory: ((Logger) -> HistoryRepository)? = null,
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

    /**
     * Stored conversations.
     *
     * Application scope, not service scope, and lazy. The history outlives any
     * one session and is read by screens with no service running at all; it is
     * opened on the first query rather than at start-up, so a process created
     * only for a broadcast receiver never touches the disk for it.
     *
     * Absent in a JVM test rather than faked: nothing the container itself does
     * reads history, so a test that needs one supplies it.
     */
    val history: HistoryRepository by lazy {
        val factory = historyRepositoryFactory
            ?: error("This container was built without a HistoryRepository")
        factory(logger)
    }

    /**
     * What the communication service is doing, readable when it is not running.
     *
     * Application scope on purpose. ADR-005 section 5 says a killed process is
     * not promised a restart, so the screen that opens next has to be able to ask
     * what state things are in -- exactly the case where there is no service to
     * bind to.
     */
    val serviceStatus: ServiceStatus = ServiceStatus()

    /**
     * Whether a screen of this app is on display.
     *
     * Application scope because the *service* asks the question and the service
     * outlives every Activity. Android 14 will not promote a foreground service
     * to the `microphone` type from the background, which makes "transmitting
     * requires a visible screen" a platform rule (ADR-005 sections 2 and 3);
     * asking first turns it into a clear refusal instead of an exception at the
     * moment the user presses the talk button.
     */
    val visibility: AppVisibility = AppVisibility()

    /**
     * The talk button, when there is a service behind it.
     *
     * Application scope holding a service-scope object, for the same reason
     * [serviceStatus] exists: a screen can open with no service running, and
     * "nothing to press yet" has to be an answer rather than a crash.
     */
    val ptt: PttGateway = PttGateway()

    /**
     * Everything the Home screen is allowed to do, assembled in one place.
     *
     * Application scope rather than per-Activity so a rotation does not rebuild
     * the graph, and lazy so a process started only for a broadcast receiver
     * never builds it at all. The screen's ViewModel receives this and nothing
     * else: it is the boundary that keeps the UI from reaching a socket
     * (`docs/02_Architecture.md` section 4.2).
     */
    val homeUseCases: HomeUseCases by lazy {
        HomeUseCases(
            observePeers = ObservePeers(serviceStatus),
            observeServiceState = ObserveServiceState(serviceStatus),
            observeSession = ObserveSession(serviceStatus),
            observeOutcomes = ObserveOutcomes(serviceStatus),
            observeStorageErrors = ObserveStorageErrors(serviceStatus),
            observeActiveUser = ObserveActiveUser(localUsers),
            startPtt = StartPtt(ptt),
            stopPtt = StopPtt(ptt),
            setServiceRunning = SetServiceRunning(),
        )
    }

    /**
     * Everything the name screens are allowed to do.
     *
     * Separate from [homeUseCases] rather than merged into one bundle, because
     * the two screens have nothing in common beyond the active name: Home never
     * deletes a user and the name screens never open a session, and a single
     * bundle would hand each of them the other's reach.
     */
    val userUseCases: UserUseCases by lazy {
        UserUseCases(
            observeUsers = ObserveLocalUsers(localUsers),
            observeActiveUser = ObserveActiveUser(localUsers),
            createUser = CreateLocalUser(localUsers),
            renameUser = RenameLocalUser(localUsers),
            deleteUser = DeleteLocalUser(localUsers),
            switchActiveUser = SwitchActiveUser(localUsers, serviceStatus),
        )
    }

    /**
     * Reads the real state of every system permission, on demand.
     *
     * Application scope because none of these questions are about an Activity,
     * and because the answer must be the same wherever it is asked -- the
     * walkthrough, the status screen and the Home notice all read this one.
     */
    val permissionInspector: PermissionInspector by lazy {
        val factory = permissionInspectorFactory
            ?: error("This container was built without a PermissionInspector")
        factory()
    }

    /** The first-run walkthrough and the permission status screen. */
    val permissionUseCases: PermissionUseCases by lazy {
        PermissionUseCases(
            readPermissions = ReadPermissions(permissionInspector),
            observeFirstRun = ObserveFirstRun(settingsRepository),
            completeGuidance = CompleteGuidance(settingsRepository),
            completeFirstLaunch = CompleteFirstLaunch(settingsRepository),
        )
    }

    /**
     * Interface language. Application scope because notifications and the
     * overlay are built outside any Activity and must agree with the UI.
     */
    val locales: LocaleController by lazy {
        LocaleController(settingsRepository, logger)
    }

    /** The settings screen and the language screen. */
    val settingsUseCases: SettingsUseCases by lazy {
        SettingsUseCases(
            observeLanguage = ObserveLanguage(settingsRepository),
            observeAllowInterrupt = ObserveAllowInterrupt(settingsRepository),
            setLanguage = SetLanguage(locales),
            setAllowInterrupt = SetAllowInterrupt(settingsRepository),
            observeOverlayEnabled = ObserveOverlayEnabled(settingsRepository),
            setOverlayEnabled = SetOverlayEnabled(settingsRepository),
            observeServiceState = ObserveServiceState(serviceStatus),
            setServiceRunning = SetServiceRunning(),
            readDiagnostics = ReadDiagnostics(deviceIdentity, config, serviceStatus),
        )
    }
}
