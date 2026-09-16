package com.saikai.ptt.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.saikai.ptt.R
import com.saikai.ptt.core.domain.HistoryRetention
import com.saikai.ptt.ui.theme.SaikaiPttTheme
import com.saikai.ptt.usecase.HistoryUsage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * How long recordings are kept, how much room they take, and how to get rid of
 * them (`docs/05_DataModel.md` sections 29, 39 and 45).
 *
 * ### Usage is counted once, when this opens
 *
 * Section 45 says so, and a directory walk on every recomposition would be the
 * wrong way to show a number that changes once a conversation. [onRefresh] runs
 * on entry and after anything that could have changed it.
 *
 * ### Changing the retention does not delete anything
 *
 * Picking "1 day" on a device holding a month of history is a tap that would
 * otherwise destroy most of it before the user's finger left the screen. The
 * setting is stored; the next pass -- scheduled, or the button below -- acts on
 * it. That is also why "clean up now" exists at all.
 */
@Composable
fun HistorySettingsScreen(
    retention: StateFlow<HistoryRetention>,
    usage: StateFlow<HistoryUsage?>,
    busy: StateFlow<Boolean>,
    prompt: StateFlow<DeletePrompt?>,
    onSetRetention: (HistoryRetention) -> Unit,
    onRefresh: () -> Unit,
    onCleanUpNow: () -> Unit,
    onRequestClearAll: () -> Unit,
    onSetClearFavorites: (Boolean) -> Unit,
    onConfirmPrompt: () -> Unit,
    onDismissPrompt: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val current = retention.collectAsState().value
    val measured = usage.collectAsState().value
    val working = busy.collectAsState().value
    val locale = currentLocale()

    LaunchedEffect(Unit) { onRefresh() }

    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text(stringResource(R.string.action_back)) }
            Text(
                text = stringResource(R.string.history_manage),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(start = 8.dp),
            )
        }

        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.history_storage_title),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 12.dp),
            )
            Text(
                text = if (measured == null) {
                    stringResource(R.string.history_storage_measuring)
                } else {
                    stringResource(
                        R.string.history_storage_summary,
                        measured.total,
                        HistoryFormat.size(measured.bytes, locale),
                    )
                },
                style = MaterialTheme.typography.bodyLarge,
            )
            if (measured != null && measured.favorites > 0) {
                Text(
                    text = stringResource(R.string.history_storage_favorites, measured.favorites),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            Text(
                text = stringResource(R.string.history_retention_title),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = stringResource(R.string.history_retention_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(Modifier.selectableGroup()) {
                HistoryRetention.entries.forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = option == current,
                                onClick = { onSetRetention(option) },
                                role = Role.RadioButton,
                            )
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = option == current, onClick = null)
                        Text(
                            text = stringResource(option.labelRes()),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(start = 12.dp),
                        )
                    }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            OutlinedButton(
                onClick = onCleanUpNow,
                enabled = !working,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    stringResource(
                        if (working) R.string.history_cleanup_running
                        else R.string.history_cleanup_now
                    )
                )
            }
            Text(
                text = stringResource(R.string.history_cleanup_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedButton(
                onClick = onRequestClearAll,
                enabled = !working,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            ) {
                Text(
                    text = stringResource(R.string.history_clear_all),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }

    DeleteDialog(
        prompt = prompt.collectAsState().value,
        onSetClearFavorites = onSetClearFavorites,
        onConfirm = onConfirmPrompt,
        onDismiss = onDismissPrompt,
    )
}

/**
 * The confirmation for both kinds of deletion.
 *
 * Shared by the list and this screen so there is one place that decides what a
 * deletion says. For "everything", the favourites question is inside the same
 * dialog as a checkbox that starts clear: section 39 forbids taking them
 * silently, and a second dialog on top of the first is how a user ends up
 * confirming something they did not read.
 */
@Composable
fun DeleteDialog(
    prompt: DeletePrompt?,
    onSetClearFavorites: (Boolean) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (prompt == null) return

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    when (prompt) {
                        is DeletePrompt.Selected -> R.string.history_delete_title
                        is DeletePrompt.Everything -> R.string.history_clear_all_title
                    }
                )
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (prompt) {
                    is DeletePrompt.Selected -> {
                        Text(
                            stringResource(
                                R.string.history_delete_message,
                                prompt.ids.size,
                            )
                        )
                        if (prompt.favorites > 0) {
                            Text(
                                text = stringResource(
                                    R.string.history_delete_favorites_warning,
                                    prompt.favorites,
                                ),
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }

                    is DeletePrompt.Everything -> {
                        Text(stringResource(R.string.history_clear_all_message))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = prompt.includeFavorites,
                                onCheckedChange = onSetClearFavorites,
                            )
                            Text(
                                text = stringResource(R.string.history_clear_favorites_too),
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                        if (!prompt.includeFavorites) {
                            Text(
                                text = stringResource(R.string.history_clear_keeps_favorites),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = stringResource(R.string.action_delete),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Preview(showBackground = true, heightDp = 780)
@Composable
private fun HistorySettingsScreenPreview() {
    SaikaiPttTheme {
        HistorySettingsScreen(
            retention = MutableStateFlow(HistoryRetention.SEVEN_DAYS),
            usage = MutableStateFlow(HistoryUsage(total = 128, favorites = 4, bytes = 32_500_000)),
            busy = MutableStateFlow(false),
            prompt = MutableStateFlow(null),
            onSetRetention = {},
            onRefresh = {},
            onCleanUpNow = {},
            onRequestClearAll = {},
            onSetClearFavorites = {},
            onConfirmPrompt = {},
            onDismissPrompt = {},
            onBack = {},
        )
    }
}
