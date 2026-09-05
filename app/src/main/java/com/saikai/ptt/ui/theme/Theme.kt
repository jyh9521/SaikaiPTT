package com.saikai.ptt.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LightColors = lightColorScheme(
    primary = SaikaiGreen,
    onPrimary = androidx.compose.ui.graphics.Color.White,
    primaryContainer = SaikaiGreenLight,
    error = SaikaiRed,
)

private val DarkColors = darkColorScheme(
    primary = SaikaiGreenLight,
    onPrimary = androidx.compose.ui.graphics.Color.Black,
    primaryContainer = SaikaiGreenDark,
    error = SaikaiRedLight,
)

/**
 * Dynamic colour is deliberately not used: the green/red identity carries
 * operational meaning (idle vs incoming) and must not be recoloured by the
 * device wallpaper. See docs/04_UI_UX.md section 3.
 */
@Composable
fun SaikaiPttTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = SaikaiTypography,
        content = content,
    )
}
