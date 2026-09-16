package com.saikai.ptt.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.saikai.ptt.R
import com.saikai.ptt.audio.PlaybackState
import com.saikai.ptt.core.domain.Direction
import com.saikai.ptt.core.domain.RecordStatus
import com.saikai.ptt.core.domain.TranscriptStatus
import com.saikai.ptt.ui.theme.SaikaiPttTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * One conversation, with its recording (`docs/04_UI_UX.md` section 29).
 *
 * ### The play button is the only thing that can be off
 *
 * Everything else on this screen draws whatever the record says, including when
 * there is no audio at all. Task39 is explicit: a missing file disables the
 * button **and says why**, and the rest of the record is shown as usual. A
 * screen that refuses to open because its recording is gone would lose the
 * transcript, the name and the time along with it.
 *
 * The subtitle state never affects the button either. Recognition is something
 * that happens to a recording afterwards; a recording that has not been
 * transcribed, or whose transcription failed, plays exactly as well.
 */
@Composable
fun HistoryDetailScreen(
    detail: StateFlow<HistoryDetail?>,
    playback: StateFlow<PlaybackState>,
    /** Why the button is off, computed from the record. Null means it is on. */
    unavailable: (HistoryDetail) -> PlaybackUnavailable?,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onStop: () -> Unit,
    onToggleFavorite: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val current = detail.collectAsState().value
    val locale = currentLocale()

    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text(stringResource(R.string.action_back)) }
            Text(
                text = stringResource(R.string.history_detail_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(start = 8.dp),
            )
        }

        if (current == null) {
            Text(
                text = stringResource(R.string.history_missing_record),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 32.dp),
            )
            return@Column
        }

        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = current.remoteUserName,
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(top = 8.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(current.direction.labelRes()),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = HistoryFormat.dateTime(current.timestamp, locale),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = HistoryFormat.duration(current.durationMs),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            current.status.labelRes()?.let {
                Text(
                    text = stringResource(it),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            PlayerSection(
                detail = current,
                playback = playback,
                unavailable = unavailable(current),
                onPlay = onPlay,
                onPause = onPause,
                onStop = onStop,
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            TranscriptSection(current)

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(onClick = onToggleFavorite, modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(
                            if (current.isFavorite) R.string.history_unfavorite
                            else R.string.history_favorite
                        )
                    )
                }
            }
        }
    }
}

@Composable
private fun PlayerSection(
    detail: HistoryDetail,
    playback: StateFlow<PlaybackState>,
    unavailable: PlaybackUnavailable?,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onStop: () -> Unit,
) {
    val state = playback.collectAsState().value

    if (unavailable != null) {
        // Disabled with a reason. "Nothing happens when I press it" is the one
        // thing a user cannot work out for themselves.
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = stringResource(unavailable.labelRes()),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(12.dp),
            )
        }
        Button(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.history_play))
        }
        return
    }

    // The duration the file reports once it is open, and the recorded one
    // before that: they agree, and the stored one is there so the bar has a
    // scale before anything has been pressed.
    val total = if (state.durationMillis > 0) state.durationMillis else detail.durationMs.toInt()
    val progress = if (total > 0) state.positionMillis.toFloat() / total else 0f

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LinearProgressIndicator(
            progress = { progress.coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(6.dp),
        )
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = HistoryFormat.duration(state.positionMillis.toLong()),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = HistoryFormat.duration(total.toLong()),
                style = MaterialTheme.typography.labelMedium,
            )
        }

        state.error?.let {
            Text(
                text = stringResource(it.labelRes()),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = if (state.playing) onPause else onPlay,
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    stringResource(
                        when {
                            state.playing -> R.string.history_pause
                            state.active -> R.string.history_resume
                            else -> R.string.history_play
                        }
                    )
                )
            }
            OutlinedButton(
                onClick = onStop,
                enabled = state.active,
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.history_stop))
            }
        }
    }
}

/**
 * The subtitle area, which is absent when subtitles were never asked for.
 *
 * `docs/04_UI_UX.md` section 29.1. ASR is off by default on every device, so
 * NOT_REQUESTED is the ordinary case and an empty box there would imply
 * something had gone missing.
 */
@Composable
private fun TranscriptSection(detail: HistoryDetail) {
    if (detail.transcriptStatus == TranscriptStatus.NOT_REQUESTED) return

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = stringResource(R.string.history_transcript),
            style = MaterialTheme.typography.titleSmall,
        )
        detail.transcriptStatus.statusLabelRes()?.let {
            Text(
                text = stringResource(it),
                style = MaterialTheme.typography.bodyMedium,
                color = if (detail.transcriptStatus == TranscriptStatus.FAILED) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        detail.transcript?.let {
            Text(text = it, style = MaterialTheme.typography.bodyLarge)
        }
        if (detail.transcriptStatus == TranscriptStatus.FAILED) {
            // The way back in that section 29.1 asks for. Re-running
            // recognition is Task41's; until the recogniser exists there is
            // nothing to offer but the explanation, and inventing a button that
            // does nothing would be worse.
            Text(
                text = stringResource(R.string.history_transcript_retry_later),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Preview(showBackground = true, heightDp = 700)
@Composable
private fun HistoryDetailScreenPreview() {
    SaikaiPttTheme {
        HistoryDetailScreen(
            detail = MutableStateFlow(
                HistoryDetail(
                    id = "1",
                    remoteUserName = "山田",
                    direction = Direction.RECEIVE,
                    timestamp = 1_789_524_000_000L,
                    durationMs = 4_200L,
                    audioPath = "records/2026/09/16/1.opus",
                    transcript = "おはようございます",
                    transcriptStatus = TranscriptStatus.COMPLETED,
                    isFavorite = false,
                    status = RecordStatus.COMPLETED,
                    fileAvailable = true,
                )
            ),
            playback = MutableStateFlow(
                PlaybackState(playing = true, positionMillis = 2_000, durationMillis = 4_200)
            ),
            unavailable = { null },
            onPlay = {},
            onPause = {},
            onStop = {},
            onToggleFavorite = {},
            onBack = {},
        )
    }
}
