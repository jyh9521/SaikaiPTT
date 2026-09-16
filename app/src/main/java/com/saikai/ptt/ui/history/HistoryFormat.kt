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
    /**
     * `12.3 MB`, in the user's own numerals this time.
     *
     * Unlike [duration]: this is a quantity, not a clock, so a Bengali reader
     * should see Bengali digits. Powers of 1024 with SI-looking suffixes, which
     * is what every file manager on Android shows and therefore what the number
     * will be compared against.
     */
    fun size(bytes: Long, locale: Locale): String {
        val safe = bytes.coerceAtLeast(0)
        if (safe < 1024) return String.format(locale, "%d B", safe)
        var value = safe.toDouble()
        var unit = 0
        while (value >= 1024 && unit < SIZE_UNITS.lastIndex) {
            value /= 1024
            unit++
        }
        // One decimal below 100, none above: "9.7 MB" and "412 MB" are both
        // three significant figures and neither is noise.
        val pattern = if (value < 100) "%.1f %s" else "%.0f %s"
        return String.format(locale, pattern, value, SIZE_UNITS[unit])
    }

    private val SIZE_UNITS = arrayOf("B", "KB", "MB", "GB", "TB")

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
