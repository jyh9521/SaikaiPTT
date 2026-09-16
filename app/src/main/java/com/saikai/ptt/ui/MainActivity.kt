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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.saikai.ptt.core.domain.LocalUser
import com.saikai.ptt.locale.AppLocale
import com.saikai.ptt.ui.home.HomeScreen
import com.saikai.ptt.ui.home.HomeViewModel
import com.saikai.ptt.ui.theme.SaikaiPttTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The application's only screen so far: it hosts Home.
 *
 * Deliberately thin (`.claude/CLAUDE.md` section 27). It attaches the chosen
 * language, builds the ViewModel, connects the talk button to the microphone
 * permission, and hands everything else to [HomeScreen]. No state and no
 * business logic live here.
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

                val home: HomeViewModel = viewModel(
                    factory = HomeViewModel.Factory(container.homeUseCases)
                )
                val talk = rememberMicrophoneGate(
                    onPress = home::press,
                    onRelease = home::release,
                )

                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    HomeScreen(
                        header = home.header,
                        peers = home.peers,
                        target = home.target,
                        ptt = home.ptt,
                        onSelectPeer = home::select,
                        onPressTalk = talk.onPress,
                        onReleaseTalk = talk.onRelease,
                        onSetServiceRunning = { running ->
                            container.homeUseCases.setServiceRunning(
                                this@MainActivity,
                                running,
                            )
                        },
                        modifier = Modifier.padding(innerPadding),
                        debugExtras = {
                            if (BuildConfig.DEBUG) {
                                DebugControls(
                                    language = AppLocale.cached(this@MainActivity),
                                    activeUser = container.localUsers.activeUser
                                        .collectAsState(initial = null).value,
                                    onSetName = { name ->
                                        val current = container.localUsers.activeUser.first()
                                        if (current == null) {
                                            container.localUsers.create(name)
                                        } else {
                                            container.localUsers.rename(current.id, name)
                                        }
                                    },
                                    onStopService = {
                                        container.homeUseCases.setServiceRunning(
                                            this@MainActivity,
                                            false,
                                        )
                                    },
                                    onSelectLanguage = { chosen ->
                                        container.locales.set(this@MainActivity, chosen)
                                        // Below Android 13 the locale is applied
                                        // by wrapping the base context, which
                                        // only happens when the Activity
                                        // attaches. Above it, the platform
                                        // recreates the Activity itself.
                                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                                            recreate()
                                        }
                                    },
                                )
                            }
                        },
                    )
                }
            }
        }
    }
}

/** The two callbacks the talk button needs, with the permission prompt in front of them. */
private class TalkHandlers(val onPress: () -> Unit, val onRelease: () -> Unit)

/**
 * Puts the microphone permission in front of the talk button.
 *
 * Here rather than in the ViewModel because a permission is an Activity-scoped
 * interaction with the platform, not a piece of screen state; and here rather
 * than in [HomeScreen] because the screen should be composable in a preview and
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
 * Controls that only exist until the screens that own them are built.
 *
 * The name editor belongs to Task33 and the language and service switches to
 * Task35. Without them a fresh install has no name, and without a name nothing
 * can be transmitted at all -- so removing the placeholder screen without
 * leaving these behind would make the app untestable between here and there.
 *
 * Guarded by `BuildConfig.DEBUG` at the call site;
 * `docs/08_ReleaseChecklist.md` section 43 forbids test UI in a release build.
 */
@Composable
private fun DebugControls(
    language: AppLanguage,
    activeUser: LocalUser?,
    onSetName: suspend (String) -> Unit,
    onStopService: () -> Unit,
    onSelectLanguage: suspend (AppLanguage) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var nameDraft by remember(activeUser?.displayName) {
        mutableStateOf(activeUser?.displayName.orEmpty())
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = nameDraft,
                onValueChange = { nameDraft = it },
                singleLine = true,
                label = { Text(stringResource(R.string.debug_name_label)) },
                modifier = Modifier.width(180.dp),
            )
            OutlinedButton(
                onClick = { scope.launch { onSetName(nameDraft) } },
                enabled = nameDraft.isNotBlank(),
            ) {
                Text(stringResource(R.string.debug_name_set))
            }
        }

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
}
