package com.saikai.ptt.ui.user

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.saikai.ptt.R
import com.saikai.ptt.ui.theme.SaikaiGreen
import com.saikai.ptt.ui.theme.SaikaiPttTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Add, rename, delete and switch between the names stored on this device
 * (`docs/04_UI_UX.md` section 8).
 *
 * Four rules are enforced below this screen and merely reported by it: a name
 * must fit both limits, the last name cannot be deleted, the name in use cannot
 * be deleted without switching first, and the name in use cannot change during
 * a call (section 54). Restating any of them here would mean two places to fix
 * when one of them changes.
 *
 * The screen takes flows rather than values for the same reason Home does: the
 * list, the editor, the two prompts and the message change independently, and
 * each section reading its own flow keeps a keystroke in the editor from
 * invalidating the list behind it.
 */
@Composable
fun UserManagementScreen(
    rows: StateFlow<List<UserRow>>,
    editor: StateFlow<NameEditor?>,
    discardPrompt: StateFlow<Boolean>,
    deletePrompt: StateFlow<UserRow?>,
    message: StateFlow<UserMessage?>,
    onBack: () -> Unit,
    onSwitch: (UserRow) -> Unit,
    onStartCreate: () -> Unit,
    onStartEdit: (UserRow) -> Unit,
    onRequestDelete: (UserRow) -> Unit,
    onEditDraft: (String) -> Unit,
    onSaveEditor: () -> Unit,
    onCloseEditor: () -> Unit,
    onConfirmDiscard: () -> Unit,
    onCancelDiscard: () -> Unit,
    onConfirmDelete: () -> Unit,
    onCancelDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val openEditor by editor.collectAsState()
    val discarding by discardPrompt.collectAsState()
    val deleting by deletePrompt.collectAsState()

    // System back closes whatever is open, innermost first, and only leaves the
    // screen when nothing is. Without this, back would leave the screen with an
    // unsaved edit still sitting in the view model -- section 46's exact
    // failure, arrived at by the one gesture users make without looking.
    BackHandler(enabled = true) {
        when {
            discarding -> onCancelDiscard()
            deleting != null -> onCancelDelete()
            openEditor != null -> onCloseEditor()
            else -> onBack()
        }
    }

    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text(stringResource(R.string.action_back)) }
            Text(
                text = stringResource(R.string.user_screen_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(start = 8.dp),
            )
        }

        MessageLine(message)

        UserList(
            rows = rows,
            onSwitch = onSwitch,
            onEdit = onStartEdit,
            onDelete = onRequestDelete,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )

        Button(onClick = onStartCreate, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.user_add))
        }
    }

    // Only one dialog at a time. The discard prompt is raised *by* the editor,
    // so showing both would stack two windows and ask the user to answer the
    // outer one through the inner one.
    openEditor?.takeIf { !discarding }?.let { open ->
        NameEditorDialog(
            editor = open,
            onDraft = onEditDraft,
            onSave = onSaveEditor,
            onDismiss = onCloseEditor,
        )
    }

    if (discarding) {
        ConfirmDialog(
            title = R.string.user_discard_title,
            body = R.string.user_discard_body,
            confirm = R.string.user_discard_confirm,
            onConfirm = onConfirmDiscard,
            onDismiss = onCancelDiscard,
        )
    }

    deleting?.let { row ->
        AlertDialog(
            onDismissRequest = onCancelDelete,
            title = { Text(stringResource(R.string.user_delete_title)) },
            text = { Text(stringResource(R.string.user_delete_body, row.displayName)) },
            confirmButton = {
                TextButton(onClick = onConfirmDelete) {
                    Text(stringResource(R.string.user_delete_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = onCancelDelete) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun MessageLine(message: StateFlow<UserMessage?>) {
    val current = message.collectAsState().value ?: return

    Text(
        text = stringResource(current.labelRes()),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
    )
}

@Composable
private fun UserList(
    rows: StateFlow<List<UserRow>>,
    onSwitch: (UserRow) -> Unit,
    onEdit: (UserRow) -> Unit,
    onDelete: (UserRow) -> Unit,
    modifier: Modifier = Modifier,
) {
    val current by rows.collectAsState()

    LazyColumn(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(current, key = { it.id }) { row ->
            UserRowItem(
                row = row,
                onSwitch = { onSwitch(row) },
                onEdit = { onEdit(row) },
                onDelete = { onDelete(row) },
            )
        }
    }
}

/**
 * One name.
 *
 * The name in use is marked three ways -- a filled mark, the word "in use", and
 * a border -- because colour alone is not allowed to carry a state
 * (`docs/04_UI_UX.md` section 3), and "which name am I speaking as" is the one
 * fact on this screen a user must never get wrong.
 */
@Composable
private fun UserRowItem(
    row: UserRow,
    onSwitch: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val shape = RoundedCornerShape(10.dp)
    val activeLabel = stringResource(R.string.user_in_use)

    Surface(
        shape = shape,
        color = if (row.active) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surface
        },
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .border(
                width = if (row.active) 2.dp else 1.dp,
                color = if (row.active) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outlineVariant
                },
                shape = shape,
            )
            // Tapping the row switches to that name. Disabled on the row that
            // is already in use, so the only thing it could do is nothing.
            .selectable(selected = row.active, enabled = !row.active, onClick = onSwitch),
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ActiveMark(active = row.active, description = if (row.active) activeLabel else "")
            Column(modifier = Modifier.weight(1f)) {
                Text(text = row.displayName, style = MaterialTheme.typography.titleMedium)
                if (row.active) {
                    Text(
                        text = activeLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            TextButton(onClick = onEdit) { Text(stringResource(R.string.user_edit)) }
            TextButton(onClick = onDelete) { Text(stringResource(R.string.user_delete)) }
        }
    }
}

/** A filled disc for the name in use, an empty ring for the rest. */
@Composable
private fun ActiveMark(active: Boolean, description: String) {
    Canvas(
        modifier = Modifier.size(18.dp).semantics { contentDescription = description },
    ) {
        val radius = size.minDimension / 2f
        if (active) {
            drawCircle(color = SaikaiGreen, radius = radius)
        } else {
            drawCircle(
                color = SaikaiGreen.copy(alpha = 0.45f),
                radius = radius - 1.5f,
                style = Stroke(width = 3f),
            )
        }
    }
}

@Composable
private fun NameEditorDialog(
    editor: NameEditor,
    onDraft: (String) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (editor.isCreating) R.string.user_add else R.string.user_edit_title
                )
            )
        },
        text = {
            NameField(
                value = editor.text,
                onValueChange = onDraft,
                problem = editor.problem,
                onSubmit = { if (editor.canSave) onSave() },
            )
        },
        confirmButton = {
            TextButton(onClick = onSave, enabled = editor.canSave) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun ConfirmDialog(
    @StringRes title: Int,
    @StringRes body: Int,
    @StringRes confirm: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(title)) },
        text = { Text(stringResource(body)) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Preview(showBackground = true, heightDp = 600)
@Composable
private fun UserManagementScreenPreview() {
    val rows = listOf(
        UserRow("1", "田中", active = true),
        UserRow("2", "山田", active = false),
        UserRow("3", "倉庫", active = false),
    )
    SaikaiPttTheme {
        UserManagementScreen(
            rows = MutableStateFlow(rows),
            editor = MutableStateFlow(null),
            discardPrompt = MutableStateFlow(false),
            deletePrompt = MutableStateFlow(null),
            message = MutableStateFlow(UserMessage.IN_CALL),
            onBack = {},
            onSwitch = {},
            onStartCreate = {},
            onStartEdit = {},
            onRequestDelete = {},
            onEditDraft = {},
            onSaveEditor = {},
            onCloseEditor = {},
            onConfirmDiscard = {},
            onCancelDiscard = {},
            onConfirmDelete = {},
            onCancelDelete = {},
        )
    }
}
