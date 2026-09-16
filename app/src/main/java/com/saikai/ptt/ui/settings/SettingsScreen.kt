package com.saikai.ptt.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.saikai.ptt.BuildConfig
import com.saikai.ptt.R
import com.saikai.ptt.core.domain.AppLanguage
import com.saikai.ptt.usecase.Diagnostics
import com.saikai.ptt.ui.theme.SaikaiPttTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Settings, grouped as `docs/04_UI_UX.md` section 34 lays them out.
 *
 * Four of that section's seven groups are here: the user, the call policy,
 * background permissions, language, and information. The other three --
 * communication history, subtitles, and the audio settings under 通話 -- are
 * deliberately absent rather than shown as inert rows.
 *
 * History belongs to Task37 through Task40 and subtitles to Task41: there is no
 * database, no recording list and no recogniser yet, so a retention picker
 * would be a control over nothing and a switch that persists a value nothing
 * reads. `.claude/CLAUDE.md` section 38 is explicit that a feature must not
 * look implemented when it is not, and a settings screen is the easiest place
 * in an app to break that rule. The audio settings are a different case: ADR-004
 * pins every audio parameter deliberately, and there is nothing for a user to
 * choose.
 */
@Composable
fun SettingsScreen(
    activeUserName: StateFlow<String?>,
    language: StateFlow<AppLanguage>,
    allowInterrupt: StateFlow<Boolean>,
    serviceRunning: StateFlow<Boolean>,
    diagnostics: StateFlow<Diagnostics?>,
    onBack: () -> Unit,
    onOpenUsers: () -> Unit,
    onOpenLanguage: () -> Unit,
    onOpenPermissions: () -> Unit,
    onSetAllowInterrupt: (Boolean) -> Unit,
    onSetServiceRunning: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text(stringResource(R.string.action_back)) }
            Text(
                text = stringResource(R.string.settings_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(start = 8.dp),
            )
        }

        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            GroupHeading(R.string.settings_group_user)
            NavigationRow(
                title = R.string.settings_active_user,
                value = activeUserName.collectAsState().value
                    ?: stringResource(R.string.home_no_active_user),
                onClick = onOpenUsers,
            )

            GroupHeading(R.string.settings_group_call)
            SwitchRow(
                title = R.string.settings_allow_interrupt,
                // The wording is the whole safeguard. This switch says "others
                // may interrupt ME", and a caller-side reading of it would be a
                // device that can cut into every call on the network
                // (`.claude/CLAUDE.md` section 7.1).
                description = R.string.settings_allow_interrupt_description,
                checked = allowInterrupt.collectAsState().value,
                onCheckedChange = onSetAllowInterrupt,
            )

            GroupHeading(R.string.settings_group_background)
            SwitchRow(
                title = R.string.settings_service,
                description = R.string.settings_service_description,
                checked = serviceRunning.collectAsState().value,
                onCheckedChange = onSetServiceRunning,
            )
            NavigationRow(
                title = R.string.permission_screen_title,
                value = stringResource(R.string.settings_permissions_value),
                onClick = onOpenPermissions,
            )

            GroupHeading(R.string.settings_group_language)
            NavigationRow(
                title = R.string.settings_language,
                value = stringResource(language.collectAsState().value.labelRes()),
                onClick = onOpenLanguage,
            )

            GroupHeading(R.string.settings_group_about)
            InfoRow(
                title = R.string.settings_version,
                value = BuildConfig.VERSION_NAME,
            )
            Text(
                text = stringResource(R.string.settings_known_limits_title),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
            )
            Text(
                text = stringResource(R.string.settings_known_limits),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )

            // Never in a release build. docs/08_ReleaseChecklist.md section 43
            // forbids shipping developer UI, and the guard is the build type
            // rather than a setting so there is nothing to switch on.
            if (BuildConfig.DEBUG) {
                diagnostics.collectAsState().value?.let { DeveloperSection(it) }
            }
        }
    }
}

@Composable
private fun GroupHeading(@StringRes title: Int) {
    HorizontalDivider(modifier = Modifier.padding(top = 12.dp))
    Text(
        text = stringResource(title),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
    )
}

@Composable
private fun NavigationRow(@StringRes title: Int, value: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = stringResource(title), style = MaterialTheme.typography.bodyLarge)
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = stringResource(R.string.settings_open),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun SwitchRow(
    @StringRes title: Int,
    @StringRes description: Int,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(text = stringResource(title), style = MaterialTheme.typography.bodyLarge)
            Text(
                text = stringResource(description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun InfoRow(@StringRes title: Int, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(title),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The values `docs/04_UI_UX.md` section 37 asks for, read once when the screen
 * opened.
 *
 * Labels are not translated and are not meant to be: this is a page whose
 * audience is whoever is reading a log alongside it, and "Device ID" has to
 * match what the log calls it.
 */
@Composable
private fun DeveloperSection(diagnostics: Diagnostics) {
    GroupHeading(R.string.settings_group_developer)
    DiagnosticLine("Device ID", diagnostics.deviceId)
    DiagnosticLine("IPv4", diagnostics.addresses.joinToString().ifEmpty { "-" })
    DiagnosticLine("Protocol", diagnostics.protocolVersion.toString())
    DiagnosticLine("Ports", "${diagnostics.controlPort} / ${diagnostics.voicePort}")
    DiagnosticLine("Service", diagnostics.serviceState.name)
    DiagnosticLine("Peers", diagnostics.peerCount.toString())
    DiagnosticLine("Session", diagnostics.sessionState)
    DiagnosticLine(
        "Heartbeat / timeout",
        "${diagnostics.heartbeatSeconds}s / ${diagnostics.peerTimeoutSeconds}s",
    )
    DiagnosticLine("Debug logging", diagnostics.loggingEnabled.toString())
}

@Composable
private fun DiagnosticLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.45f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.weight(0.55f),
        )
    }
}

@Preview(showBackground = true, heightDp = 800)
@Composable
private fun SettingsScreenPreview() {
    SaikaiPttTheme {
        SettingsScreen(
            activeUserName = MutableStateFlow("田中"),
            language = MutableStateFlow(AppLanguage.JAPANESE),
            allowInterrupt = MutableStateFlow(false),
            serviceRunning = MutableStateFlow(true),
            diagnostics = MutableStateFlow(null),
            onBack = {},
            onOpenUsers = {},
            onOpenLanguage = {},
            onOpenPermissions = {},
            onSetAllowInterrupt = {},
            onSetServiceRunning = {},
        )
    }
}
