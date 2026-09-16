package com.saikai.ptt.ui.permissions

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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.saikai.ptt.R
import com.saikai.ptt.permissions.AppPermission
import com.saikai.ptt.permissions.PermissionState
import com.saikai.ptt.ui.theme.SaikaiAmber
import com.saikai.ptt.ui.theme.SaikaiGreen
import com.saikai.ptt.ui.theme.SaikaiGrey
import com.saikai.ptt.ui.theme.SaikaiPttTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Every permission, what it is for, and a way back to the system screen that
 * grants it (`docs/04_UI_UX.md` section 36).
 *
 * This screen is the *only* second chance. The walkthrough runs once and never
 * comes back, and nothing in the app pops a permission dialog on its own, so
 * whatever the user skipped is picked up here or not at all -- which is what
 * section 36.1 means by not pestering.
 *
 * Every row says what is lost without it, not what is gained with it, for the
 * same reason the walkthrough does.
 */
@Composable
fun PermissionStatusScreen(
    rows: StateFlow<List<PermissionRow>>,
    /** Opens whatever this permission needs. False when there is nothing to open. */
    onOpen: (AppPermission) -> Boolean,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val current by rows.collectAsState()

    // Which rows could not be opened, so the written instructions take over.
    // Auto-start is in here from the start: there is no intent for it anywhere.
    var manual by remember { mutableStateOf(setOf(AppPermission.AUTO_START)) }

    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text(stringResource(R.string.action_back)) }
            Text(
                text = stringResource(R.string.permission_screen_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(start = 8.dp),
            )
        }

        Text(
            text = stringResource(R.string.permission_screen_note),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(current, key = { it.permission.name }) { row ->
                PermissionRowItem(
                    row = row,
                    showManual = row.permission in manual,
                    onClick = {
                        manual = if (onOpen(row.permission)) {
                            manual - row.permission
                        } else {
                            manual + row.permission
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun PermissionRowItem(
    row: PermissionRow,
    showManual: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(10.dp)
    val stateText = stringResource(row.state.labelRes())

    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .clickable(onClick = onClick),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                StateMark(state = row.state, description = stateText)
                Text(
                    text = stringResource(row.permission.titleRes()),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = stateText,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = stringResource(row.permission.consequenceRes()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            if (showManual) {
                ManualGuidance(row.permission, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}

/**
 * A shape per state, before it is a colour.
 *
 * Filled for granted, barred for denied, hollow for unknown -- three
 * silhouettes that survive a greyscale screenshot, which is how section 3's
 * rule is actually tested.
 */
@Composable
private fun StateMark(state: PermissionState, description: String) {
    val colour = when (state) {
        PermissionState.GRANTED, PermissionState.NOT_APPLICABLE -> SaikaiGreen
        PermissionState.DENIED -> SaikaiAmber
        PermissionState.UNKNOWN -> SaikaiGrey
    }

    Canvas(modifier = Modifier.size(18.dp).semantics { contentDescription = description }) {
        val radius = size.minDimension / 2f
        when (state) {
            PermissionState.GRANTED, PermissionState.NOT_APPLICABLE ->
                drawCircle(color = colour, radius = radius)

            PermissionState.DENIED -> {
                drawCircle(color = colour, radius = radius)
                drawRect(
                    color = Color.White,
                    topLeft = Offset(
                        x = center.x - radius * 0.55f,
                        y = center.y - radius * 0.16f,
                    ),
                    size = Size(width = radius * 1.1f, height = radius * 0.32f),
                )
            }

            PermissionState.UNKNOWN ->
                drawCircle(color = colour, radius = radius - 1.5f, style = Stroke(width = 3f))
        }
    }
}

@Preview(showBackground = true, heightDp = 700)
@Composable
private fun PermissionStatusScreenPreview() {
    SaikaiPttTheme {
        PermissionStatusScreen(
            rows = MutableStateFlow(
                listOf(
                    PermissionRow(AppPermission.MICROPHONE, PermissionState.GRANTED),
                    PermissionRow(AppPermission.NOTIFICATIONS, PermissionState.DENIED),
                    PermissionRow(AppPermission.OVERLAY, PermissionState.DENIED),
                    PermissionRow(AppPermission.BATTERY_OPTIMIZATION, PermissionState.GRANTED),
                    PermissionRow(AppPermission.AUTO_START, PermissionState.UNKNOWN),
                )
            ),
            onOpen = { false },
            onBack = {},
        )
    }
}
