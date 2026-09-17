package com.saikai.ptt.di

import com.saikai.ptt.core.config.SaikaiConfig
import com.saikai.ptt.core.domain.DeviceIdentityProvider
import com.saikai.ptt.core.common.subsystemScope
import com.saikai.ptt.core.domain.HistoryRepository
import com.saikai.ptt.core.asr.SpeechRecognizer
import com.saikai.ptt.core.asr.TranscriptionQueue
import com.saikai.ptt.core.history.ActiveRecordings
import com.saikai.ptt.core.history.HistoryCleaner
import com.saikai.ptt.core.history.HistoryEraser
import com.saikai.ptt.core.domain.LocalUserRepository
import com.saikai.ptt.core.domain.SettingsLocalUserRepository
import com.saikai.ptt.core.domain.SettingsRepository
import com.saikai.ptt.core.domain.StoredDeviceIdentityProvider
import com.saikai.ptt.core.logger.LogSink
import com.saikai.ptt.core.logger.Logger
import com.saikai.ptt.locale.LocaleController
import com.saikai.ptt.AppVisibility
import com.saikai.ptt.asr.AsrModel
import com.saikai.ptt.asr.SherpaRecognizer
import com.saikai.ptt.audio.RecordingPlayback
import com.saikai.ptt.storage.history.HistoryMaintenance
import com.saikai.ptt.storage.history.LocalRecordingFiles
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
import com.saikai.ptt.usecase.HistoryUseCases
import com.saikai.ptt.usecase.MarkRecordRead
import com.saikai.ptt.usecase.ObserveHistory
import com.saikai.ptt.usecase.ObserveUnreadCount
import com.saikai.ptt.usecase.ReadRecord
import com.saikai.ptt.usecase.SetRecordFavorite
import com.saikai.ptt.usecase.ClearHistory
import com.saikai.ptt.usecase.DeleteRecords
import com.saikai.ptt.usecase.ObserveRetention
import com.saikai.ptt.usecase.ReadHistoryUsage
import com.saikai.ptt.usecase.RunCleanup
import com.saikai.ptt.usecase.SetRetention
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors

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
    /** The app's private files directory. Null in a plain JVM test. */
    private val filesDirFactory: (() -> java.io.File)? = null,
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

    /** Reading stored conversations. Writing them is the service's job. */
    val historyUseCases: HistoryUseCases by lazy {
        HistoryUseCases(
            observeHistory = ObserveHistory(history),
            observeUnreadCount = ObserveUnreadCount(history),
            readRecord = ReadRecord(history),
            markRead = MarkRecordRead(history),
            setFavorite = SetRecordFavorite(history),
            deleteRecords = DeleteRecords(historyEraser, history),
            clearHistory = ClearHistory(historyEraser),
            readUsage = ReadHistoryUsage(history, recordingFiles),
            runCleanup = RunCleanup(historyMaintenance),
            observeRetention = ObserveRetention(settingsRepository),
            setRetention = SetRetention(settingsRepository),
        )
    }

    /** Deleting because the user asked, as opposed to because time passed. */
    val historyEraser: HistoryEraser by lazy {
        HistoryEraser(history = history, files = recordingFiles, logger = logger)
    }

    /**
     * Plays one recording at a time.
     *
     * Application scope because a `MediaPlayer` is a real resource and one is
     * enough: one screen shows one recording, and a second player would be a
     * second thing holding the speaker. Its scope is its own rather than a
     * screen's, so the progress ticker cannot outlive a failure in something
     * unrelated.
     *
     * The scope is the **main** dispatcher, unlike every other subsystem here.
     * A `MediaPlayer` is not thread-safe, and the only two things that touch
     * this one are the buttons (main thread) and the progress ticker; putting
     * the ticker anywhere else would let it call `isPlaying` on a player that
     * `stop()` released a microsecond earlier. Reading a position five times a
     * second costs the main thread nothing.
     *
     * @param filesDirFactory supplied by the caller, because the private
     *   directory needs a Context and this container deliberately has none.
     */
    val recordingPlayback: RecordingPlayback by lazy {
        val factory = filesDirFactory
            ?: error("This container was built without a files directory")
        RecordingPlayback(
            filesDir = factory(),
            active = activeRecordings,
            logger = logger,
            scope = subsystemScope("playback", logger, Dispatchers.Main.immediate),
        )
    }

    /**
     * Which recordings something is holding open.
     *
     * Application scope and shared by three components that never meet: the
     * recorder claims what it is writing, the player claims what it is
     * sounding, and cleanup refuses to delete either
     * (`docs/05_DataModel.md` sections 31 and 32). A registry rather than a
     * question each of them could be asked, because cleanup runs on its own
     * thread and neither of the others is necessarily alive when it does.
     */
    val activeRecordings: ActiveRecordings by lazy { ActiveRecordings() }

    /** The recordings directory, as cleanup sees it. */
    val recordingFiles: LocalRecordingFiles by lazy {
        val factory = filesDirFactory
            ?: error("This container was built without a files directory")
        LocalRecordingFiles(factory(), logger)
    }

    /**
     * Retention and orphan cleanup.
     *
     * Built here rather than in the service container because it is the
     * settings screen's as much as the service's: the user can ask for a pass
     * from "clean up now", and that must work whether or not the service
     * happens to be running.
     */
    val historyCleaner: HistoryCleaner by lazy {
        HistoryCleaner(
            history = history,
            files = recordingFiles,
            active = activeRecordings,
            logger = logger,
        )
    }

    /** Where the recognition model lives. Fetching it is Task46's. */
    val asrModel: AsrModel by lazy {
        val factory = filesDirFactory
            ?: error("This container was built without a files directory")
        AsrModel(factory())
    }

    /**
     * What is waiting to be recognised.
     *
     * Application scope because the recorder fills it as calls end and the
     * worker drains it, and those are different lifetimes: a recording made
     * while the service was up should still be transcribed after it restarts.
     */
    val transcriptionQueue: TranscriptionQueue by lazy {
        TranscriptionQueue(maxAttempts = config.history.asrMaxRetries)
    }

    /**
     * The engine, on its own thread below the audio ones.
     *
     * `CLAUDE.md` section 18.3 puts voice above recognition unconditionally,
     * and a single low-priority thread is how that is enforced rather than
     * merely intended. Lazy, so a device with ASR off never constructs it.
     */
    val speechRecognizer: SpeechRecognizer by lazy {
        SherpaRecognizer(
            model = asrModel,
            logger = logger,
            dispatcher = asrDispatcher,
            expectedSampleRateHz = config.audio.sampleRateHz,
        )
    }

    private val asrDispatcher: ExecutorCoroutineDispatcher by lazy {
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "saikai-asr").apply {
                // Below the recorder, which is itself below the audio threads.
                priority = Thread.MIN_PRIORITY
                isDaemon = true
            }
        }.asCoroutineDispatcher()
    }

    /**
     * When cleanup runs: at service start-up and every few hours after.
     *
     * Application scope, like the cleaner it drives, so the settings screen's
     * "clean up now" and the service's loop share one mutex and cannot run two
     * passes over each other.
     */
    val historyMaintenance: HistoryMaintenance by lazy {
        HistoryMaintenance(
            cleaner = historyCleaner,
            settings = settingsRepository,
            config = config,
            logger = logger,
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
