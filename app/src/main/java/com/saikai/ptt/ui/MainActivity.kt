package com.saikai.ptt.ui

import android.content.Context
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.saikai.ptt.BuildConfig
import com.saikai.ptt.R
import com.saikai.ptt.SaikaiApplication
import com.saikai.ptt.core.domain.AppLanguage
import com.saikai.ptt.locale.AppLocale
import com.saikai.ptt.ui.theme.SaikaiPttTheme
import kotlinx.coroutines.launch

/**
 * Placeholder entry point.
 *
 * The real Home screen -- active user, peer list, target selection and the PTT
 * button -- is built in Task32. This Activity exists so the project can be
 * built, installed and launched, and now so the language infrastructure can be
 * exercised. It holds no business logic.
 */
class MainActivity : ComponentActivity() {

    /**
     * Applies the chosen language before any resource is resolved.
     *
     * A no-op on Android 13+, where the platform has already done it
     * (`app/locale/AppLocale.kt`).
     */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as SaikaiApplication).container

        setContent {
            SaikaiPttTheme {
                LaunchedEffect(Unit) { container.locales.reconcile(this@MainActivity) }

                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    PlaceholderScreen(
                        language = AppLocale.cached(this@MainActivity),
                        onSelectLanguage = { language ->
                            container.locales.set(this@MainActivity, language)
                            // Below Android 13 the locale is applied by wrapping
                            // the base context, which only happens when the
                            // Activity attaches. Above it, the platform
                            // recreates the Activity itself.
                            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) recreate()
                        },
                        modifier = Modifier.padding(innerPadding),
                    )
                }
            }
        }
    }
}

@Composable
private fun PlaceholderScreen(
    language: AppLanguage,
    onSelectLanguage: suspend (AppLanguage) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()

    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            text = stringResource(R.string.placeholder_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // Debug only. The real language screen is Task35, and
        // docs/08_ReleaseChecklist.md section 43 forbids shipping test UI.
        if (BuildConfig.DEBUG) {
            Text(
                text = "${stringResource(R.string.placeholder_language_label)}: ${language.tag}",
                style = MaterialTheme.typography.labelMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { scope.launch { onSelectLanguage(AppLanguage.JAPANESE) } }) {
                    Text(stringResource(R.string.language_japanese))
                }
                OutlinedButton(onClick = { scope.launch { onSelectLanguage(AppLanguage.ENGLISH) } }) {
                    Text(stringResource(R.string.language_english))
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun PlaceholderScreenPreview() {
    SaikaiPttTheme {
        PlaceholderScreen(language = AppLanguage.JAPANESE, onSelectLanguage = {})
    }
}
