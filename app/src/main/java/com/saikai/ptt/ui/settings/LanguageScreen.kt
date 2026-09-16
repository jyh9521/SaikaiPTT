package com.saikai.ptt.ui.settings

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
import androidx.compose.foundation.selection.selectable
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.saikai.ptt.R
import com.saikai.ptt.core.domain.AppLanguage
import com.saikai.ptt.ui.theme.SaikaiGreen
import com.saikai.ptt.ui.theme.SaikaiPttTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The interface language (`docs/04_UI_UX.md` section 35).
 *
 * Every language is written in itself and none of them is translated. A person
 * looking for Burmese is looking for မြန်မာ, and showing them "Burmese" in a
 * script they cannot read is showing them nothing -- which is why the entries
 * are marked `translatable="false"` in the resources.
 *
 * The one exception is the first entry, "follow the system", which is not a
 * language and is translated like any other label.
 */
@Composable
fun LanguageScreen(
    current: StateFlow<AppLanguage>,
    onSelect: (AppLanguage) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val selected = current.collectAsState().value

    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text(stringResource(R.string.action_back)) }
            Text(
                text = stringResource(R.string.settings_language),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(start = 8.dp),
            )
        }

        AppLanguage.entries.forEach { language ->
            LanguageRow(
                language = language,
                selected = language == selected,
                onSelect = { onSelect(language) },
            )
        }
    }
}

@Composable
private fun LanguageRow(language: AppLanguage, selected: Boolean, onSelect: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    val chosenLabel = stringResource(R.string.settings_language_selected)

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
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outlineVariant
                },
                shape = shape,
            )
            .selectable(selected = selected, onClick = onSelect),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // A filled disc for the chosen one, an empty ring for the rest --
            // and the word as well, because colour never carries a state on its
            // own here (section 3).
            Canvas(
                modifier = Modifier
                    .size(18.dp)
                    .semantics { contentDescription = if (selected) chosenLabel else "" },
            ) {
                val radius = size.minDimension / 2f
                if (selected) {
                    drawCircle(color = SaikaiGreen, radius = radius)
                } else {
                    drawCircle(
                        color = SaikaiGreen.copy(alpha = 0.45f),
                        radius = radius - 1.5f,
                        style = Stroke(width = 3f),
                    )
                }
            }
            Text(
                text = stringResource(language.labelRes()),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            if (selected) {
                Text(
                    text = chosenLabel,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/** Each language named in itself; "follow the system" is a label, not a language. */
@StringRes
internal fun AppLanguage.labelRes(): Int = when (this) {
    AppLanguage.SYSTEM -> R.string.language_system
    AppLanguage.JAPANESE -> R.string.language_japanese
    AppLanguage.SIMPLIFIED_CHINESE -> R.string.language_chinese
    AppLanguage.ENGLISH -> R.string.language_english
    AppLanguage.BURMESE -> R.string.language_burmese
    AppLanguage.BENGALI -> R.string.language_bengali
}

@Preview(showBackground = true, heightDp = 560)
@Composable
private fun LanguageScreenPreview() {
    SaikaiPttTheme {
        LanguageScreen(
            current = MutableStateFlow(AppLanguage.JAPANESE),
            onSelect = {},
            onBack = {},
        )
    }
}
