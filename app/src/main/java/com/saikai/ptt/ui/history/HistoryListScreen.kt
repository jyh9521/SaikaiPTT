package com.saikai.ptt.ui.history

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
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
 * Every stored conversation, newest first, with a search box over it
 * (`docs/04_UI_UX.md` sections 26 and 27).
 *
 * ### Two modes, and the list is the same list in both
 *
 * Normally a tap opens a record. Once anything is ticked the list is selecting:
 * a tap ticks instead of opening, and a bar at the top says how many and offers
 * to delete them. A long press is what starts it, which is the gesture Android
 * users already know for this and costs no permanent chrome on a screen whose
 * ordinary use is "open the thing I just missed".
 *
 * Nothing here deletes anything. It asks, and `HistoryViewModel` decides once
 * the confirmation comes back (`docs/05_DataModel.md` sections 38 and 39).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HistoryListScreen(
    rows: StateFlow<List<HistoryRow>>,
    query: StateFlow<String>,
    selection: StateFlow<Set<String>>,
    onSearch: (String) -> Unit,
    onOpen: (String) -> Unit,
    onToggleSelected: (String) -> Unit,
    onClearSelection: () -> Unit,
    onDeleteSelected: () -> Unit,
    onOpenSettings: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val current = rows.collectAsState().value
    val term = query.collectAsState().value
    val selected = selection.collectAsState().value
    val selecting = selected.isNotEmpty()
    val locale = currentLocale()
    val focus: FocusManager = LocalFocusManager.current

    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (selecting) {
            SelectionBar(
                count = selected.size,
                onCancel = onClearSelection,
                onDelete = onDeleteSelected,
            )
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text(stringResource(R.string.action_back)) }
                Text(
                    text = stringResource(R.string.history_title),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f).padding(start = 8.dp),
                )
                TextButton(onClick = onOpenSettings) {
                    Text(stringResource(R.string.history_manage))
                }
            }
        }

        OutlinedTextField(
            value = term,
            onValueChange = onSearch,
            singleLine = true,
            label = { Text(stringResource(R.string.history_search_label)) },
            placeholder = { Text(stringResource(R.string.history_search_hint)) },
            trailingIcon = {
                if (term.isNotEmpty()) {
                    TextButton(onClick = { onSearch("") }) {
                        Text(stringResource(R.string.action_clear))
                    }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            // The search is already live; the key just puts the keyboard away.
            // Through the focus manager rather than the keyboard controller,
            // which has moved in and out of experimental more than once.
            keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
            modifier = Modifier.fillMaxWidth(),
        )

        if (current.isEmpty()) {
            Text(
                text = stringResource(
                    // Two different empty states. "No history" on a device that
                    // has never been used is a fact; on a search it would read
                    // as though the history had gone.
                    if (term.isBlank()) R.string.history_empty else R.string.history_no_matches
                ),
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
                HistoryRowItem(
                    row = row,
                    locale = locale,
                    selecting = selecting,
                    selected = row.id in selected,
                    onOpen = { if (selecting) onToggleSelected(row.id) else onOpen(row.id) },
                    onLongPress = { onToggleSelected(row.id) },
                )
            }
        }
    }
}

/** What replaces the title bar once anything is ticked. */
@Composable
private fun SelectionBar(count: Int, onCancel: () -> Unit, onDelete: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
        Text(
            text = stringResource(R.string.history_selected_count, count),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f).padding(start = 8.dp),
        )
        TextButton(onClick = onDelete) {
            Text(
                text = stringResource(R.string.action_delete),
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HistoryRowItem(
    row: HistoryRow,
    locale: java.util.Locale,
    selecting: Boolean,
    selected: Boolean,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
) {
    val shape = RoundedCornerShape(10.dp)

    Surface(
        shape = shape,
        color = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surface
        },
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .combinedClickable(onClick = onOpen, onLongClick = onLongPress),
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (selecting) {
                // A tick as well as the background colour: selection must not
                // be carried by colour alone either (section 28).
                Checkbox(
                    checked = selected,
                    onCheckedChange = { onLongPress() },
                    modifier = Modifier.padding(end = 8.dp),
                )
            }

            Column(modifier = Modifier.weight(1f)) {
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

@Preview(showBackground = true, heightDp = 700)
@Composable
private fun HistoryListScreenPreview() {
    val rows = listOf(
        HistoryRow(
            id = "1",
            remoteUserName = "山田",
            direction = Direction.RECEIVE,
            timestamp = 1_789_524_000_000L,
            durationMs = 4_200L,
            transcriptPreview = "おはようございます",
            isRead = false,
            isFavorite = true,
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
            isFavorite = false,
            status = RecordStatus.INTERRUPTED,
            playable = false,
        ),
    )
    SaikaiPttTheme {
        HistoryListScreen(
            rows = MutableStateFlow(rows),
            query = MutableStateFlow(""),
            selection = MutableStateFlow(emptySet()),
            onSearch = {},
            onOpen = {},
            onToggleSelected = {},
            onClearSelection = {},
            onDeleteSelected = {},
            onOpenSettings = {},
            onBack = {},
        )
    }
}
