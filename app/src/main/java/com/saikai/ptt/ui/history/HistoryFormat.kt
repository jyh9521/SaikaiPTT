package com.saikai.ptt.ui.history

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Times and durations, in the user's own conventions.
 *
 * `docs/04_UI_UX.md` section 49: dates, times and numbers are formatted against
 * the current locale at the point of use, never assembled from resource
 * fragments. A stored timestamp is UTC milliseconds and stays that way
 * (`docs/05_DataModel.md` section 17); this is the only place it becomes
 * something to read.
 */
internal object HistoryFormat {

    /** `2026/09/16 11:40`, or whatever that means where the user is. */
    fun dateTime(millis: Long, locale: Locale): String =
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale)
            .format(Date(millis))

    /**
     * `mm:ss`.
     *
     * `Locale.ROOT` on purpose, so it is ASCII digits in every language. Java
     * would otherwise render this in Bengali numerals under a `bn` locale,
     * which is correct for a quantity and wrong for a clock beside a progress
     * bar -- a stopwatch reads the same everywhere.
     */
    fun duration(millis: Long): String {
        val total = millis.coerceAtLeast(0)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(total)
        val seconds = TimeUnit.MILLISECONDS.toSeconds(total) - minutes * 60
        return String.format(Locale.ROOT, "%02d:%02d", minutes, seconds)
    }
}

/**
 * The locale the app is currently showing.
 *
 * Read from the context's own configuration rather than `LocalConfiguration`,
 * which has been deprecated and undeprecated across Compose releases; this has
 * been stable since API 24 and the app's minimum is 30. It is the *app's*
 * language, not the system's, because below Android 13 `MainActivity` wraps its
 * base context to apply the choice (`app/locale/AppLocale.kt`).
 */
@Composable
internal fun currentLocale(): Locale {
    val locales = LocalContext.current.resources.configuration.locales
    return if (locales.isEmpty) Locale.getDefault() else locales[0]
}
