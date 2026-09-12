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
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.saikai.ptt.BuildConfig
import com.saikai.ptt.R
import com.saikai.ptt.SaikaiApplication
import com.saikai.ptt.audio.AndroidAudioPlayer
import com.saikai.ptt.audio.AndroidAudioRecorder
import com.saikai.ptt.core.common.Outcome
import com.saikai.ptt.core.domain.AppLanguage
import com.saikai.ptt.core.domain.AudioPlayer
import com.saikai.ptt.core.domain.AudioRecorder
import com.saikai.ptt.core.domain.Peer
import com.saikai.ptt.core.session.SessionState
import com.saikai.ptt.locale.AppLocale
import com.saikai.ptt.service.CommunicationService
import com.saikai.ptt.service.ServiceState
import com.saikai.ptt.ui.theme.SaikaiPttTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.PI
import kotlin.math.sin

/**
 * Placeholder entry point.
 *
 * The real Home screen -- active user, peer list, target selection and the PTT
 * button -- is built in Task32. This Activity exists so the project can be
 * built, installed and launched, and now so the language infrastructure can be
 * exercised. It holds no business logic.
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
        // Debug affordance, removed with the placeholder screen in Task32. It is
        // the only way to check ADR-004's capture path on a real microphone,
        // which is what Task20 is accepted on.
        val recorder = AndroidAudioRecorder(this, container.config, container.logger)
        val speaker = AndroidAudioPlayer(container.config, container.logger)

        setContent {
            SaikaiPttTheme {
                LaunchedEffect(Unit) { container.locales.reconcile(this@MainActivity) }

                val serviceState by container.serviceStatus.state.collectAsState()
                val peers by container.serviceStatus.peers.collectAsState()
                val session by container.serviceStatus.session.collectAsState()
                val activeUser by container.localUsers.activeUser.collectAsState(initial = null)

                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    PlaceholderScreen(
                        language = AppLocale.cached(this@MainActivity),
                        serviceState = serviceState,
                        peers = peers,
                        activeUserName = activeUser?.displayName,
                        session = session,
                        onTalkPress = { peer ->
                            when (val outcome = container.ptt.press(peer)) {
                                is Outcome.Success -> ""
                                is Outcome.Failure -> outcome.error.name
                            }
                        },
                        onTalkRelease = { container.ptt.release() },
                        onSetName = { name ->
                            val current = container.localUsers.activeUser.first()
                            if (current == null) {
                                container.localUsers.create(name)
                            } else {
                                container.localUsers.rename(current.id, name)
                            }
                        },
                        onProbeMicrophone = {
                            probeMicrophone(recorder, container.config.audio.frameSizeBytes)
                        },
                        onProbeSpeaker = {
                            probeSpeaker(
                                speaker,
                                container.config.audio.sampleRateHz,
                                container.config.audio.frameSizeSamples,
                            )
                        },
                        onToggleService = {
                            if (serviceState == ServiceState.READY ||
                                serviceState == ServiceState.STARTING
                            ) {
                                CommunicationService.stop(this@MainActivity)
                            } else {
                                CommunicationService.start(this@MainActivity)
                            }
                        },
                        onSelectLanguage = { language ->
                            container.locales.set(this@MainActivity, language)
                            // Below Android 13 the locale is applied by wrapping
                            // the base context, which only happens when the
                            // Activity attaches. Above it, the platform
                            // recreates the Activity itself.
                            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) recreate()
                        },
                        modifier = Modifier.padding(innerPadding),
                    )
                }
            }
        }
    }
}

@Composable
private fun PlaceholderScreen(
    language: AppLanguage,
    serviceState: ServiceState,
    peers: List<Peer>,
    activeUserName: String?,
    session: SessionState,
    onTalkPress: suspend (Peer) -> String,
    onTalkRelease: suspend () -> Unit,
    onProbeMicrophone: suspend () -> String,
    onProbeSpeaker: suspend () -> String,
    onSetName: suspend (String) -> Unit,
    onToggleService: () -> Unit,
    onSelectLanguage: suspend (AppLanguage) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var nameDraft by remember(activeUserName) { mutableStateOf(activeUserName.orEmpty()) }
    var talkError by remember { mutableStateOf("") }

    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            text = stringResource(R.string.placeholder_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // Debug only. The real language screen is Task35, and
        // docs/08_ReleaseChecklist.md section 43 forbids shipping test UI.
        if (BuildConfig.DEBUG) {
            Text(
                text = "${stringResource(R.string.placeholder_language_label)}: ${language.tag}",
                style = MaterialTheme.typography.labelMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { scope.launch { onSelectLanguage(AppLanguage.JAPANESE) } }) {
                    Text(stringResource(R.string.language_japanese))
                }
                OutlinedButton(onClick = { scope.launch { onSelectLanguage(AppLanguage.ENGLISH) } }) {
                    Text(stringResource(R.string.language_english))
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = nameDraft,
                    onValueChange = { nameDraft = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.placeholder_name_label)) },
                    modifier = Modifier.width(180.dp),
                )
                OutlinedButton(
                    onClick = { scope.launch { onSetName(nameDraft) } },
                    enabled = nameDraft.isNotBlank(),
                ) {
                    Text(stringResource(R.string.placeholder_name_set))
                }
            }

            Text(
                text = "${stringResource(R.string.placeholder_service_label)}: $serviceState",
                style = MaterialTheme.typography.labelMedium,
            )
            OutlinedButton(onClick = onToggleService) {
                Text(
                    stringResource(
                        if (serviceState == ServiceState.READY || serviceState == ServiceState.STARTING) {
                            R.string.placeholder_service_stop
                        } else {
                            R.string.placeholder_service_start
                        }
                    )
                )
            }

            MicrophoneProbe(onProbe = onProbeMicrophone)
            SpeakerProbe(onProbe = onProbeSpeaker)

            Text(
                text = "${stringResource(R.string.placeholder_session_label)}: " +
                    session.javaClass.simpleName + talkError,
                style = MaterialTheme.typography.labelMedium,
            )
            Text(
                text = "${stringResource(R.string.placeholder_peers_label)} (${peers.size})",
                style = MaterialTheme.typography.labelMedium,
            )
            peers.forEach { peer ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "${peer.userName}  ${peer.state}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TalkButton(
                        peer = peer,
                        onPress = { talkError = onTalkPress(it).let { e -> if (e.isEmpty()) "" else "  $e" } },
                        onRelease = onTalkRelease,
                    )
                }
            }
        }
    }
}

/**
 * Press and hold to talk, for as long as the finger is down.
 *
 * Debug only, and the only way Task25 can be accepted: two devices, A holds the
 * button, B hears it. `detectTapGestures` rather than a click, because the whole
 * behaviour under test is what happens between the press and the release --
 * including that letting go ends the session even if the peer never answered.
 *
 * The permission request is here rather than at start-up because Task34 owns
 * onboarding; without it the very first press on a fresh install would fail with
 * MIC_UNAVAILABLE and look like a bug in the pipeline.
 */
@Composable
private fun TalkButton(
    peer: Peer,
    onPress: suspend (Peer) -> Unit,
    onRelease: suspend () -> Unit,
) {
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

    val interactions = remember { MutableInteractionSource() }

    // Press and release rather than click, because the whole behaviour under
    // test lives between them -- including that letting go ends the session even
    // if the peer never answered. Reading them off the interaction source is
    // what a Material button already publishes; a competing pointer handler on
    // the same button would be racing the button's own.
    LaunchedEffect(interactions, granted) {
        if (!granted) return@LaunchedEffect
        interactions.interactions.collect { interaction ->
            when (interaction) {
                is PressInteraction.Press -> onPress(peer)
                is PressInteraction.Release, is PressInteraction.Cancel -> onRelease()
                else -> Unit
            }
        }
    }

    OutlinedButton(
        // The permission prompt is here rather than at start-up because
        // onboarding is Task34. Without it the first press on a fresh install
        // fails with MIC_UNAVAILABLE and looks like a bug in the pipeline.
        onClick = { if (!granted) request.launch(Manifest.permission.RECORD_AUDIO) },
        interactionSource = interactions,
    ) {
        Text(stringResource(R.string.placeholder_talk))
    }
}

/**
 * Records for two seconds and reports what arrived.
 *
 * Debug only. Task20 is accepted on "captures reliably on a real device", and a
 * frame count against the expected hundred, plus a peak level that moves when
 * someone speaks, is the smallest thing that can actually show it.
 */
@Composable
private fun MicrophoneProbe(onProbe: suspend () -> String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var result by remember { mutableStateOf("") }

    val request = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) scope.launch { result = onProbe() } else result = "denied"
    }

    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedButton(onClick = {
            val granted = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
            if (granted) {
                result = "..."
                scope.launch { result = onProbe() }
            } else {
                request.launch(Manifest.permission.RECORD_AUDIO)
            }
        }) {
            Text(stringResource(R.string.placeholder_mic_test))
        }
        Text(text = result, style = MaterialTheme.typography.labelMedium)
    }
}

/** Plays a test tone. Debug only; goes with the placeholder in Task32. */
@Composable
private fun SpeakerProbe(onProbe: suspend () -> String) {
    val scope = rememberCoroutineScope()
    var result by remember { mutableStateOf("") }

    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedButton(onClick = {
            result = "..."
            scope.launch { result = onProbe() }
        }) {
            Text(stringResource(R.string.placeholder_speaker_test))
        }
        Text(text = result, style = MaterialTheme.typography.labelMedium)
    }
}

/**
 * Two seconds of 440 Hz, written one 20 ms frame at a time.
 *
 * Paced like the real receive path rather than written in one go, so what it
 * proves is what matters: that the device keeps up frame by frame. A tone
 * written as a single buffer would sound fine on a device that cannot.
 */
private suspend fun probeSpeaker(
    player: AudioPlayer,
    sampleRateHz: Int,
    frameSizeSamples: Int,
): String {
    return when (val outcome = player.start()) {
        is Outcome.Failure -> "failed: ${outcome.error}"
        is Outcome.Success -> {
            val frame = ByteArray(frameSizeSamples * 2)
            var phase = 0.0
            val step = 2.0 * PI * TONE_HZ / sampleRateHz
            val frames = (PROBE_MILLIS / 20).toInt()
            var short = 0
            var firstShort = -1

            repeat(frames) { index ->
                for (sample in 0 until frameSizeSamples) {
                    val value = (sin(phase) * TONE_AMPLITUDE).toInt().toShort()
                    frame[sample * 2] = (value.toInt() and 0xFF).toByte()
                    frame[sample * 2 + 1] = ((value.toInt() shr 8) and 0xFF).toByte()
                    phase += step
                }
                if (player.write(frame, 0, frame.size) < frame.size) {
                    short++
                    if (firstShort < 0) firstShort = index
                }
                delay(20)
            }

            player.stop()
            // Where the shortfall happened is the whole answer. Once, near the
            // start, is the device not draining until play() takes effect.
            // Spread through the run would be a device that cannot keep up.
            "short=$short/$frames first=$firstShort"
        }
    }
}

private const val TONE_HZ = 440.0
private const val TONE_AMPLITUDE = 8_000.0

/**
 * Two seconds of capture, counted and measured.
 *
 * The peak is computed on the capture thread, from the reused buffer, which is
 * also a live check of that contract: reading it after the callback returned
 * would give the next frame's audio.
 */
private suspend fun probeMicrophone(recorder: AudioRecorder, frameSizeBytes: Int): String {
    val frames = AtomicInteger()
    val peak = AtomicInteger()
    val firstAt = AtomicLong()
    val lastAt = AtomicLong()
    val silentFrames = AtomicInteger()

    val outcome = recorder.start { pcm, offset, length, capturedAtMillis ->
        if (frames.getAndIncrement() == 0) firstAt.set(capturedAtMillis)
        lastAt.set(capturedAtMillis)
        var loudest = 0
        var index = offset
        val end = offset + length - 1
        while (index < end) {
            // Little-endian 16-bit PCM.
            val sample = (((pcm[index + 1].toInt() shl 8) or (pcm[index].toInt() and 0xFF))
                .toShort()).toInt()
            val magnitude = if (sample == Short.MIN_VALUE.toInt()) Short.MAX_VALUE.toInt()
            else abs(sample)
            if (magnitude > loudest) loudest = magnitude
            index += 2
        }
        // A frame of literal zeros is a different fault from a quiet one: it
        // means the device handed back silence, not that the room was still.
        if (loudest == 0) silentFrames.incrementAndGet()
        peak.updateAndGet { maxOf(it, loudest) }
    }

    return when (outcome) {
        is Outcome.Failure -> "failed: ${outcome.error}"
        is Outcome.Success -> {
            delay(PROBE_MILLIS)
            recorder.stop()

            val count = frames.get()
            val level = peak.get().toFloat() / Short.MAX_VALUE
            // The span between the first and last frame, not the wall clock:
            // opening the device takes a few tens of milliseconds, and counting
            // that as missing frames would blame the capture loop for the
            // hardware's start-up. What matters is the rate once it is running.
            val spanMillis = (lastAt.get() - firstAt.get()).coerceAtLeast(0L)
            val expectedInSpan = if (count > 1) spanMillis / 20 + 1 else count.toLong()
            "f=$count/$expectedInSpan ${spanMillis}ms zero=${silentFrames.get()} peak=%.3f raw=%d"
                .format(level, peak.get())
        }
    }
}

private const val PROBE_MILLIS = 2_000L

@Preview(showBackground = true)
@Composable
private fun PlaceholderScreenPreview() {
    SaikaiPttTheme {
        PlaceholderScreen(
            language = AppLanguage.JAPANESE,
            serviceState = ServiceState.STOPPED,
            peers = emptyList(),
            activeUserName = null,
            session = SessionState.Idle,
            onTalkPress = { "" },
            onTalkRelease = {},
            onProbeMicrophone = { "" },
            onProbeSpeaker = { "" },
            onSetName = {},
            onToggleService = {},
            onSelectLanguage = {},
        )
    }
}
