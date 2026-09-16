package com.saikai.ptt.ui.user

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.saikai.ptt.R
import com.saikai.ptt.ui.theme.SaikaiPttTheme
import androidx.compose.ui.tooling.preview.Preview

/**
 * The first screen a new installation shows.
 *
 * It exists because a device with no name cannot transmit: the name travels in
 * VOICE_START and the receiving device displays it, so there is nothing to send
 * until one is chosen (`docs/01_PRD.md` section 4.1, `docs/04_UI_UX.md`
 * sections 6 and 7). Rather than letting the user reach Home and find the talk
 * button refusing, the name is asked for first and Home appears once it exists.
 *
 * The draft lives here rather than in the view model, and that is the one place
 * this screen differs from the editor. There is nothing to back out to -- this
 * is the whole app until a name exists -- so section 46's unsaved prompt has
 * nothing to protect, and `rememberSaveable` already carries the text across a
 * rotation.
 */
@Composable
fun WelcomeScreen(
    message: UserMessage?,
    onCreate: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var draft by rememberSaveable { mutableStateOf("") }
    val problem = nameProblem(draft)
    val canCreate = problem == null && draft.isNotBlank()

    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.welcome_title),
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            text = stringResource(R.string.welcome_description),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        NameField(
            value = draft,
            onValueChange = { draft = it },
            problem = problem,
            onSubmit = { if (canCreate) onCreate(draft) },
        )

        if (message != null) {
            Text(
                text = stringResource(message.labelRes()),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
            )
        }

        Button(
            onClick = { onCreate(draft) },
            enabled = canCreate,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.welcome_create))
        }
    }
}

@Preview(showBackground = true, heightDp = 600)
@Composable
private fun WelcomeScreenPreview() {
    SaikaiPttTheme {
        WelcomeScreen(message = null, onCreate = {})
    }
}
