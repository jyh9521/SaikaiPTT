package com.saikai.ptt.ui.permissions

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.saikai.ptt.R
import com.saikai.ptt.permissions.AppPermission
import com.saikai.ptt.permissions.PermissionState
import com.saikai.ptt.ui.theme.SaikaiPttTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The first-run walkthrough: one permission per page, with what is lost without
 * it (`docs/01_PRD.md` section 24.2).
 *
 * Every page can be skipped and the app keeps working, which is why the
 * consequence is stated rather than the benefit. "Allow the microphone" is a
 * demand; "without this you can still hear other people but not answer them" is
 * a choice, and it happens to be true.
 *
 * It runs exactly once. Whatever the user does here -- grant everything or skip
 * all five -- the flag is written and this never appears again; the status
 * screen is where anything missing is picked up later (section 36.1 forbids
 * asking repeatedly).
 */
@Composable
fun OnboardingScreen(
    step: StateFlow<OnboardingStep?>,
    /** Opens whatever this permission needs. False when there is nothing to open. */
    onOpen: (AppPermission) -> Boolean,
    onAdvance: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val current by step.collectAsState()
    val page = current ?: return

    // Only from the second page: back on the first is the system's to handle,
    // which leaves the app. Trapping the user inside a wizard they are allowed
    // to skip entirely would be a strange place to hold them.
    BackHandler(enabled = page.index > 0) { onBack() }

    // Reset with every page, so the fallback text never lingers onto the next
    // permission.
    var showManual by remember(page.row.permission) { mutableStateOf(false) }

    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.onboarding_progress, page.index + 1, page.total),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(page.row.permission.titleRes()),
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            text = stringResource(R.string.onboarding_without_label),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(page.row.permission.consequenceRes()),
            style = MaterialTheme.typography.bodyLarge,
        )

        Text(
            text = stringResource(
                R.string.permission_current_state,
                stringResource(page.row.state.labelRes()),
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (showManual || page.row.permission == AppPermission.AUTO_START) {
            ManualGuidance(page.row.permission)
        }

        Spacer(modifier = Modifier.weight(1f))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onAdvance, modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(
                        if (page.isLast) R.string.onboarding_finish else R.string.onboarding_skip
                    )
                )
            }
            if (page.row.state.isSatisfied) {
                Button(onClick = onAdvance, modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(
                            if (page.isLast) R.string.onboarding_finish
                            else R.string.onboarding_next
                        )
                    )
                }
            } else {
                Button(
                    onClick = { showManual = !onOpen(page.row.permission) },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.onboarding_allow))
                }
            }
        }
    }
}

/**
 * What to do by hand when there is no screen to send the user to.
 *
 * Two ways to arrive: auto-start, which has no platform intent on any device,
 * and a settings intent the device refused to launch. Section 36.1 asks for
 * words in both cases rather than a button that silently does nothing.
 */
@Composable
internal fun ManualGuidance(permission: AppPermission, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        shape = RoundedCornerShape(8.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Text(
            text = stringResource(permission.manualGuidanceRes()),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(12.dp),
        )
    }
}

@Preview(showBackground = true, heightDp = 640)
@Composable
private fun OnboardingScreenPreview() {
    SaikaiPttTheme {
        OnboardingScreen(
            step = MutableStateFlow(
                OnboardingStep(
                    row = PermissionRow(AppPermission.MICROPHONE, PermissionState.DENIED),
                    index = 0,
                    total = 5,
                )
            ),
            onOpen = { true },
            onAdvance = {},
            onBack = {},
        )
    }
}
