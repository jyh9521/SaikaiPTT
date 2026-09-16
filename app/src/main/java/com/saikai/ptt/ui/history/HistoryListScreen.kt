package com.saikai.ptt.ui.history

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.saikai.ptt.R
import com.saikai.ptt.core.domain.Direction
import com.saikai.ptt.core.domain.RecordStatus
import com.saikai.ptt.ui.theme.SaikaiGreen
import com.saikai.ptt.ui.theme.SaikaiPttTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Every stored conversation, newest first (`docs/04_UI_UX.md` section 26).
 *
 * Deliberately plain. Search, selection and deletion are Task40's, and a search
 * box that does not search is worse than no search box. What is here is what
 * section 27 asks a row to show and what Task39 needs to be reachable: a way
 * into the detail screen.
 */
@Composable
fun HistoryListScreen(
    rows: StateFlow<List<HistoryRow>>,
    onOpen: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val current = rows.collectAsState().value
    val locale = currentLocale()

    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text(stringResource(R.string.action_back)) }
            Text(
                text = stringResource(R.string.history_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(start = 8.dp),
            )
        }

        if (current.isEmpty()) {
            // Not an error: a device that has not been used yet has no history,
            // and so does one whose retention window has just passed.
            Text(
                text = stringResource(R.string.history_empty),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 32.dp),
            )
            return@Column
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(current, key = { it.id }) { row ->
                HistoryRowItem(row = row, locale = locale, onOpen = { onOpen(row.id) })
            }
        }
    }
}

@Composable
private fun HistoryRowItem(
    row: HistoryRow,
    locale: java.util.Locale,
    onOpen: () -> Unit,
) {
    val shape = RoundedCornerShape(10.dp)

    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .clickable(onClick = onOpen),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Unread is a filled dot, a bold name and a word -- never the
                // colour alone (section 28).
                UnreadMark(unread = !row.isRead)
                Text(
                    text = row.remoteUserName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (row.isRead) FontWeight.Normal else FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                if (row.isFavorite) {
                    Text(
                        text = stringResource(R.string.history_favorite_mark),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            Row(
                modifier = Modifier.padding(top = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = stringResource(row.direction.labelRes()),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = HistoryFormat.dateTime(row.timestamp, locale),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = HistoryFormat.duration(row.durationMs),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            row.transcriptPreview?.let { preview ->
                Text(
                    text = preview,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            val note = row.status.labelRes()
                ?: R.string.history_no_audio.takeIf { !row.playable }
            note?.let {
                Text(
                    text = stringResource(it),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

/** Filled while unread, nothing at all once read. */
@Composable
private fun UnreadMark(unread: Boolean) {
    val description = stringResource(R.string.history_unread)
    Canvas(modifier = Modifier.size(10.dp)) {
        if (unread) drawCircle(color = SaikaiGreen, radius = size.minDimension / 2f)
    }
    if (unread) {
        Text(
            text = description,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@androidx.annotation.StringRes
internal fun Direction.labelRes(): Int = when (this) {
    Direction.SEND -> R.string.history_direction_send
    Direction.RECEIVE -> R.string.history_direction_receive
}

@Preview(showBackground = true, heightDp = 600)
@Composable
private fun HistoryListScreenPreview() {
    SaikaiPttTheme {
        HistoryListScreen(
            rows = MutableStateFlow(
                listOf(
                    HistoryRow(
                        id = "1",
                        remoteUserName = "山田",
                        direction = Direction.RECEIVE,
                        timestamp = 1_789_524_000_000L,
                        durationMs = 4_200L,
                        transcriptPreview = "おはようございます",
                        isRead = false,
                        isFavorite = false,
                        status = RecordStatus.COMPLETED,
                        playable = true,
                    ),
                    HistoryRow(
                        id = "2",
                        remoteUserName = "倉庫",
                        direction = Direction.SEND,
                        timestamp = 1_789_520_000_000L,
                        durationMs = 1_800L,
                        transcriptPreview = null,
                        isRead = true,
                        isFavorite = true,
                        status = RecordStatus.INTERRUPTED,
                        playable = false,
                    ),
                )
            ),
            onOpen = {},
            onBack = {},
        )
    }
}
