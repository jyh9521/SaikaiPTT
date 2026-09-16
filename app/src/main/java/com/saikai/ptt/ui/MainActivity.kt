package com.saikai.ptt.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.saikai.ptt.BuildConfig
import com.saikai.ptt.R
import com.saikai.ptt.SaikaiApplication
import com.saikai.ptt.core.domain.AppLanguage
import com.saikai.ptt.di.AppContainer
import com.saikai.ptt.locale.AppLocale
import com.saikai.ptt.ui.home.HomeScreen
import com.saikai.ptt.ui.home.HomeViewModel
import com.saikai.ptt.ui.theme.SaikaiPttTheme
import com.saikai.ptt.ui.user.NameGate
import com.saikai.ptt.ui.user.UserManagementScreen
import com.saikai.ptt.ui.user.UsersViewModel
import com.saikai.ptt.ui.user.WelcomeScreen
import kotlinx.coroutines.launch

/**
 * The application's single Activity: it decides which screen is on show.
 *
 * Deliberately thin (`.claude/CLAUDE.md` section 27). It attaches the chosen
 * language, builds the two view models, connects the talk button to the
 * microphone permission, and holds which screen is open. No state of its own
 * and no business logic.
 *
 * ### Two different things decide what is drawn
 *
 * The first is a **gate**, not navigation: a device with no name cannot
 * transmit at all, so the welcome screen is the whole app until one exists
 * (`docs/04_UI_UX.md` section 6). It is not somewhere the user navigates to and
 * there is nothing to go back to.
 *
 * The second is navigation proper, and it is one enum in a `rememberSaveable`
 * rather than a navigation library. See `docs/ADR/ADR-009-Navigation.md`.
 */
class MainActivity : ComponentActivity() {

    /**
     * Applies the chosen language before any resource is resolved.
     *
     * A no-op on Android 13+, where the platform has already done it
     * (`app/locale/AppLocale.kt`).
     */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
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
                val gate by users.gate.collectAsState()
                var destination by rememberSaveable { mutableStateOf(Destination.HOME) }

                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    val padded = Modifier.padding(innerPadding)
                    when (gate) {
                        // The answer has not arrived from storage yet. Drawing
                        // the welcome screen here would flash "create a name" at
                        // a user who created one months ago.
                        NameGate.Loading -> Splash(padded)

                        NameGate.Missing -> WelcomeScreen(
                            message = users.message.collectAsState().value,
                            onCreate = users::createFirstUser,
                            modifier = padded,
                        )

                        is NameGate.Ready -> when (destination) {
                            Destination.HOME -> Home(
                                container = container,
                                onOpenUsers = { destination = Destination.USERS },
                                modifier = padded,
                            )

                            Destination.USERS -> UserManagementScreen(
                                rows = users.rows,
                                editor = users.editor,
                                discardPrompt = users.discardPrompt,
                                deletePrompt = users.deletePrompt,
                                message = users.message,
                                onBack = {
                                    users.dismissMessage()
                                    destination = Destination.HOME
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
        onOpenUsers: () -> Unit,
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
            onSelectPeer = home::select,
            onOpenUsers = onOpenUsers,
            onPressTalk = talk.onPress,
            onReleaseTalk = talk.onRelease,
            onSetServiceRunning = { running ->
                container.homeUseCases.setServiceRunning(this@MainActivity, running)
            },
            modifier = modifier,
            debugExtras = {
                if (BuildConfig.DEBUG) {
                    DebugControls(
                        language = AppLocale.cached(this@MainActivity),
                        onStopService = {
                            container.homeUseCases.setServiceRunning(this@MainActivity, false)
                        },
                        onSelectLanguage = { chosen ->
                            container.locales.set(this@MainActivity, chosen)
                            // Below Android 13 the locale is applied by wrapping
                            // the base context, which only happens when the
                            // Activity attaches. Above it, the platform
                            // recreates the Activity itself.
                            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) recreate()
                        },
                    )
                }
            },
        )
    }
}

/** Which screen is open, once there is a name. */
private enum class Destination { HOME, USERS }

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
 * The first press on a fresh install asks, and does not transmit. Task34 owns
 * onboarding and will ask before the user ever reaches this screen; until then
 * this is what stops the first press failing with MIC_UNAVAILABLE and looking
 * like a bug in the audio pipeline.
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

/**
 * Controls that only exist until the screen that owns them is built.
 *
 * Language and the service switch belong to Task35's settings screen. The name
 * editor that used to sit here is gone: Task33 gives names their own screens,
 * which is what this was standing in for.
 *
 * Guarded by `BuildConfig.DEBUG` at the call site;
 * `docs/08_ReleaseChecklist.md` section 43 forbids test UI in a release build.
 */
@Composable
private fun DebugControls(
    language: AppLanguage,
    onStopService: () -> Unit,
    onSelectLanguage: suspend (AppLanguage) -> Unit,
) {
    val scope = rememberCoroutineScope()

    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "${stringResource(R.string.debug_language_label)}: ${language.tag}",
            style = MaterialTheme.typography.labelMedium,
        )
        OutlinedButton(onClick = { scope.launch { onSelectLanguage(AppLanguage.JAPANESE) } }) {
            Text(stringResource(R.string.language_japanese))
        }
        OutlinedButton(onClick = { scope.launch { onSelectLanguage(AppLanguage.ENGLISH) } }) {
            Text(stringResource(R.string.language_english))
        }
        OutlinedButton(onClick = onStopService) {
            Text(stringResource(R.string.debug_service_stop))
        }
    }
}
