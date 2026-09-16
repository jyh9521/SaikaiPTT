package com.saikai.ptt.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import com.saikai.ptt.BuildConfig
import com.saikai.ptt.R
import com.saikai.ptt.SaikaiApplication
import com.saikai.ptt.di.AppContainer
import com.saikai.ptt.locale.AppLocale
import com.saikai.ptt.permissions.AppPermission
import com.saikai.ptt.permissions.PermissionNavigator
import com.saikai.ptt.ui.history.DeleteDialog
import com.saikai.ptt.ui.history.HistoryDetailScreen
import com.saikai.ptt.ui.history.HistorySettingsScreen
import com.saikai.ptt.ui.history.HistoryListScreen
import com.saikai.ptt.ui.history.HistoryViewModel
import com.saikai.ptt.ui.home.HomeScreen
import com.saikai.ptt.ui.home.HomeViewModel
import com.saikai.ptt.ui.permissions.OnboardingScreen
import com.saikai.ptt.ui.permissions.PermissionStatusScreen
import com.saikai.ptt.ui.permissions.PermissionsViewModel
import com.saikai.ptt.ui.settings.LanguageScreen
import com.saikai.ptt.ui.settings.SettingsScreen
import com.saikai.ptt.ui.settings.SettingsViewModel
import com.saikai.ptt.ui.theme.SaikaiPttTheme
import com.saikai.ptt.ui.user.NameGate
import com.saikai.ptt.ui.user.UserManagementScreen
import com.saikai.ptt.ui.user.UsersViewModel
import com.saikai.ptt.ui.user.WelcomeScreen
import kotlinx.coroutines.flow.StateFlow

/**
 * The application's single Activity: it decides which screen is on show.
 *
 * Deliberately thin (`.claude/CLAUDE.md` section 27). It attaches the chosen
 * language, builds the view models, owns the two things that genuinely need an
 * Activity -- runtime permission dialogs and launching system settings -- and
 * holds which screen is open. No state of its own and no business logic.
 *
 * ### Three things decide what is drawn, and only one of them is navigation
 *
 * First the **first-run sequence**, in the order `docs/01_PRD.md` section 50
 * lays it out: permissions, then a name, then Home. Neither is somewhere the
 * user navigates to and neither has anywhere to go back to -- a device with no
 * name cannot transmit at all, and the walkthrough runs exactly once ever.
 *
 * Then **navigation** proper, which is one enum in a `rememberSaveable` rather
 * than a navigation library. See `docs/ADR/ADR-009-Navigation.md`.
 */
class MainActivity : ComponentActivity() {

    /**
     * Bumped on every resume, to re-read the permissions.
     *
     * Android publishes no change notification for a permission, and the moment
     * one is most likely to have changed is the moment the user comes back from
     * system settings -- which is this. A Compose state rather than a lifecycle
     * observer inside the composition, because `LocalLifecycleOwner` has moved
     * between artifacts more than once and this needs no library at all.
     */
    private var resumeCount by mutableStateOf(0)

    /**
     * Applies the chosen language before any resource is resolved.
     *
     * A no-op on Android 13+, where the platform has already done it
     * (`app/locale/AppLocale.kt`).
     */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    override fun onResume() {
        super.onResume()
        resumeCount++
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as SaikaiApplication).container

        setContent {
            SaikaiPttTheme {
                LaunchedEffect(Unit) { container.locales.reconcile(this@MainActivity) }

                val users: UsersViewModel = viewModel(
                    factory = UsersViewModel.Factory(container.userUseCases)
                )
                val permissions: PermissionsViewModel = viewModel(
                    factory = PermissionsViewModel.Factory(container.permissionUseCases)
                )
                val settings: SettingsViewModel = viewModel(
                    factory = SettingsViewModel.Factory(
                        useCases = container.settingsUseCases,
                        activeUser = container.userUseCases.observeActiveUser,
                        isDebugBuild = BuildConfig.DEBUG,
                    )
                )
                val history: HistoryViewModel = viewModel(
                    factory = HistoryViewModel.Factory(
                        useCases = container.historyUseCases,
                        playback = container.recordingPlayback,
                        observeSession = container.homeUseCases.observeSession,
                    )
                )
                val openPermission = rememberPermissionOpener(container, permissions)

                LaunchedEffect(resumeCount) { permissions.refresh() }

                val gate by users.gate.collectAsState()
                val guidanceShown by permissions.guidanceShown.collectAsState()
                // A real stack now, one level deep in practice: Settings opens
                // the name screen and the permission screen, and backing out of
                // either has to land on Settings rather than on Home. See
                // docs/ADR/ADR-010-Navigation-Back-Stack.md.
                var stack by rememberSaveable { mutableStateOf(listOf(Destination.HOME)) }
                val destination = stack.last()
                val open: (Destination) -> Unit = { next -> stack = stack + next }
                val goBack: () -> Unit = {
                    if (destination == Destination.HISTORY &&
                        history.selection.value.isNotEmpty()
                    ) {
                        // Backing out of a selection is not backing out of the
                        // screen. The bar at the top says the list is in a mode;
                        // back leaves the mode first.
                        history.clearSelection()
                    } else if (stack.size > 1) {
                        // Leaving the detail releases the player, whether the
                        // user used the screen's own button or the system one.
                        // Here rather than in the screen's `onBack` because the
                        // system button does not go through the screen at all.
                        if (destination == Destination.HISTORY_DETAIL) history.close()
                        stack = stack.dropLast(1)
                    }
                }

                // Declared before the screens, so a screen with something open
                // of its own -- an editor, a confirmation -- registers later and
                // wins. Closing that comes before leaving the screen.
                BackHandler(enabled = stack.size > 1) { goBack() }

                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    val padded = Modifier.padding(innerPadding)
                    when {
                        // Storage has not answered yet. Drawing anything else
                        // here would flash a first-run screen at somebody who
                        // finished the first run months ago.
                        gate == NameGate.Loading || guidanceShown == null -> Splash(padded)

                        guidanceShown == false -> OnboardingScreen(
                            step = permissions.step,
                            onOpen = openPermission,
                            onAdvance = permissions::advance,
                            onBack = permissions::back,
                            modifier = padded,
                        )

                        gate == NameGate.Missing -> WelcomeScreen(
                            message = users.message.collectAsState().value,
                            onCreate = users::createFirstUser,
                            modifier = padded,
                        )

                        destination == Destination.USERS -> Users(users, padded, goBack)

                        destination == Destination.PERMISSIONS -> PermissionStatusScreen(
                            rows = permissions.rows,
                            onOpen = openPermission,
                            onBack = goBack,
                            modifier = padded,
                        )

                        destination == Destination.SETTINGS -> SettingsScreen(
                            activeUserName = settings.activeUserName,
                            language = settings.language,
                            allowInterrupt = settings.allowInterrupt,
                            serviceRunning = settings.serviceRunning,
                            overlayEnabled = settings.overlayEnabled,
                            diagnostics = settings.diagnostics,
                            onBack = goBack,
                            onOpenUsers = { open(Destination.USERS) },
                            onOpenLanguage = { open(Destination.LANGUAGE) },
                            onOpenPermissions = { open(Destination.PERMISSIONS) },
                            onSetAllowInterrupt = settings::setAllowInterrupt,
                            onSetServiceRunning = { running ->
                                settings.setServiceRunning(this@MainActivity, running)
                            },
                            onSetOverlayEnabled = settings::setOverlayEnabled,
                            modifier = padded,
                        )

                        destination == Destination.HISTORY -> {
                            HistoryListScreen(
                                rows = history.rows,
                                query = history.query,
                                selection = history.selection,
                                onSearch = history::search,
                                onOpen = { id ->
                                    history.open(id)
                                    open(Destination.HISTORY_DETAIL)
                                },
                                onToggleSelected = history::toggleSelected,
                                onClearSelection = history::clearSelection,
                                onDeleteSelected = history::requestDeleteSelected,
                                onOpenSettings = { open(Destination.HISTORY_SETTINGS) },
                                onBack = goBack,
                                modifier = padded,
                            )
                            // Declared after the screen so it is on top of it,
                            // and shared with the detail screen: one dialog
                            // decides what a deletion says.
                            DeleteDialog(
                                prompt = history.prompt.collectAsState().value,
                                onSetClearFavorites = history::setClearFavorites,
                                onConfirm = history::confirmPrompt,
                                onDismiss = history::dismissPrompt,
                            )
                        }

                        destination == Destination.HISTORY_SETTINGS -> HistorySettingsScreen(
                            retention = history.retention,
                            usage = history.usage,
                            busy = history.busy,
                            prompt = history.prompt,
                            onSetRetention = history::setRetention,
                            onRefresh = history::refreshUsage,
                            onCleanUpNow = history::cleanUpNow,
                            onRequestClearAll = history::requestClearAll,
                            onSetClearFavorites = history::setClearFavorites,
                            onConfirmPrompt = history::confirmPrompt,
                            onDismissPrompt = history::dismissPrompt,
                            onBack = goBack,
                            modifier = padded,
                        )

                        destination == Destination.HISTORY_DETAIL -> {
                            HistoryDetailScreen(
                                detail = history.detail,
                                playback = history.playbackState,
                                unavailable = history::unavailable,
                                onPlay = history::play,
                                onPause = history::pause,
                                onStop = history::stopPlayback,
                                onToggleFavorite = history::toggleFavorite,
                                onDelete = history::requestDeleteOpen,
                                // `goBack` releases the player; the list behind
                                // is still live, so the record it just marked
                                // read redraws on its own.
                                onBack = goBack,
                                modifier = padded,
                            )
                            DeleteDialog(
                                prompt = history.prompt.collectAsState().value,
                                onSetClearFavorites = history::setClearFavorites,
                                // The record this screen is showing is the one
                                // that goes, so leaving is part of confirming.
                                onConfirm = {
                                    history.confirmPrompt()
                                    goBack()
                                },
                                onDismiss = history::dismissPrompt,
                            )
                        }

                        destination == Destination.LANGUAGE -> LanguageScreen(
                            current = settings.language,
                            onSelect = { chosen ->
                                settings.setLanguage(this@MainActivity, chosen) {
                                    // Below Android 13 the locale is applied by
                                    // wrapping the base context, which only
                                    // happens when the Activity attaches. Above
                                    // it, the platform recreates the Activity
                                    // itself (`docs/04_UI_UX.md` section 35.1).
                                    if (Build.VERSION.SDK_INT <
                                        Build.VERSION_CODES.TIRAMISU
                                    ) {
                                        recreate()
                                    }
                                }
                            },
                            onBack = goBack,
                            modifier = padded,
                        )

                        else -> {
                            // Everything the first run asks for is done, which
                            // is the definition of the flag.
                            LaunchedEffect(Unit) { permissions.markFirstLaunchCompleted() }
                            Home(
                                container = container,
                                permissions = permissions,
                                unreadCount = history.unreadCount,
                                onOpenUsers = { open(Destination.USERS) },
                                onOpenPermissions = { open(Destination.PERMISSIONS) },
                                onOpenSettings = { open(Destination.SETTINGS) },
                                onOpenHistory = { open(Destination.HISTORY) },
                                modifier = padded,
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * The Home screen and everything it needs.
     *
     * A member function so it can reach the Activity: starting a foreground
     * service and applying a locale both need one, and both are refused from an
     * application context.
     */
    @Composable
    private fun Home(
        container: AppContainer,
        permissions: PermissionsViewModel,
        unreadCount: StateFlow<Int>,
        onOpenUsers: () -> Unit,
        onOpenPermissions: () -> Unit,
        onOpenSettings: () -> Unit,
        onOpenHistory: () -> Unit,
        modifier: Modifier,
    ) {
        val home: HomeViewModel = viewModel(
            factory = HomeViewModel.Factory(container.homeUseCases)
        )
        val talk = rememberMicrophoneGate(onPress = home::press, onRelease = home::release)

        HomeScreen(
            header = home.header,
            peers = home.peers,
            target = home.target,
            ptt = home.ptt,
            permissionWarning = permissions.anyDenied,
            unreadCount = unreadCount,
            onSelectPeer = home::select,
            onOpenUsers = onOpenUsers,
            onOpenPermissions = onOpenPermissions,
            onOpenSettings = onOpenSettings,
            onOpenHistory = onOpenHistory,
            onPressTalk = talk.onPress,
            onReleaseTalk = talk.onRelease,
            onSetServiceRunning = { running ->
                container.homeUseCases.setServiceRunning(this@MainActivity, running)
            },
            modifier = modifier,
        )
    }

    @Composable
    private fun Users(users: UsersViewModel, modifier: Modifier, onBack: () -> Unit) {
        UserManagementScreen(
            rows = users.rows,
            editor = users.editor,
            discardPrompt = users.discardPrompt,
            deletePrompt = users.deletePrompt,
            message = users.message,
            onBack = {
                users.dismissMessage()
                onBack()
            },
            onSwitch = users::switchTo,
            onStartCreate = users::startCreate,
            onStartEdit = users::startEdit,
            onRequestDelete = users::requestDelete,
            onEditDraft = users::editDraft,
            onSaveEditor = users::saveEditor,
            onCloseEditor = users::requestCloseEditor,
            onConfirmDiscard = users::confirmDiscard,
            onCancelDiscard = users::cancelDiscard,
            onConfirmDelete = users::confirmDelete,
            onCancelDelete = users::cancelDelete,
            modifier = modifier,
        )
    }

    /**
     * Turns "the user tapped this permission" into whatever that permission
     * actually needs, and reports whether anything opened.
     *
     * Two of the five are runtime permissions with a system dialog; the rest
     * live in Settings. The escalation between them is deliberate and is the
     * cheapest way to handle a permanently refused one without asking the
     * platform awkward questions: **the first tap asks, a later tap goes to
     * Settings.** Android stops showing the dialog after two refusals, and a
     * button whose only effect is a dialog that no longer appears is a button
     * that looks broken.
     *
     * Returning false is a real answer, not a failure: auto-start has no
     * platform intent on any device, and any intent can be refused by a ROM
     * that does not have that screen. The caller shows written instructions
     * instead (`docs/04_UI_UX.md` section 36.1).
     */
    @Composable
    private fun rememberPermissionOpener(
        container: AppContainer,
        permissions: PermissionsViewModel,
    ): (AppPermission) -> Boolean {
        val asked = remember { mutableSetOf<AppPermission>() }
        val launcher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { permissions.refresh() }

        return remember(container, permissions) {
            opener@{ permission: AppPermission ->
                val runtime = container.permissionInspector.runtimePermission(permission)
                if (runtime != null && asked.add(permission)) {
                    launcher.launch(runtime)
                    return@opener true
                }

                val intent = PermissionNavigator.settingsIntent(this@MainActivity, permission)
                    ?: return@opener false
                try {
                    startActivity(intent)
                    true
                } catch (_: ActivityNotFoundException) {
                    // A ROM without that settings screen. Expected, not broken.
                    false
                } catch (_: SecurityException) {
                    // A ROM whose settings screen is not exported to us.
                    false
                }
            }
        }
    }
}

/** Which screen is open, once the first run is done. */
private enum class Destination {
    HOME,
    USERS,
    PERMISSIONS,
    SETTINGS,
    LANGUAGE,
    HISTORY,
    HISTORY_DETAIL,
    HISTORY_SETTINGS,
}

/** Shown for the one frame or two before storage answers. */
@Composable
private fun Splash(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineMedium,
        )
    }
}

/** The two callbacks the talk button needs, with the permission prompt in front of them. */
private class TalkHandlers(val onPress: () -> Unit, val onRelease: () -> Unit)

/**
 * Puts the microphone permission in front of the talk button.
 *
 * Here rather than in the ViewModel because a permission is an Activity-scoped
 * interaction with the platform, not a piece of screen state; and here rather
 * than in `HomeScreen` because the screen should be composable in a preview and
 * a test without a permission controller behind it.
 *
 * This survives Task34's walkthrough rather than being replaced by it. Asking
 * in the moment the user reaches for the microphone is the one request Android
 * itself recommends, and it is not what section 36.1 forbids: the user pressed
 * a button, and nothing here asks on its own. Once the platform stops showing
 * the dialog, the press fails with MIC_UNAVAILABLE and Home says so, with the
 * permission notice above it leading to the status screen.
 */
@Composable
private fun rememberMicrophoneGate(
    onPress: () -> Unit,
    onRelease: () -> Unit,
): TalkHandlers {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val request = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted = it }

    // Tracked so a press that only opened the permission dialog does not send a
    // release for a session that was never started.
    var pressed by remember { mutableStateOf(false) }

    return remember(granted) {
        TalkHandlers(
            onPress = {
                if (granted) {
                    pressed = true
                    onPress()
                } else {
                    request.launch(Manifest.permission.RECORD_AUDIO)
                }
            },
            onRelease = {
                if (pressed) {
                    pressed = false
                    onRelease()
                }
            },
        )
    }
}
