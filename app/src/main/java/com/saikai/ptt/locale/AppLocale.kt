package com.saikai.ptt.locale

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import com.saikai.ptt.core.domain.AppLanguage

/**
 * Applies the chosen interface language.
 *
 * Two paths, because per-app language only became a platform feature in
 * Android 13 and this app supports Android 11 (`.claude/CLAUDE.md` section 4):
 *
 * - **API 33+** hands the choice to [LocaleManager]. The system stores it,
 *   resolves resources everywhere including in the Foreground Service, and
 *   surfaces it in Settings so the user can change it there too.
 * - **API 30-32** wraps each component's [Context] with an overridden
 *   [Configuration].
 *
 * AppCompat would cover both, but it also brings the AppCompat theme system,
 * which this app does not use and which would fight the platform theme. For one
 * setting it is not a fair trade (`.claude/CLAUDE.md` section 32).
 */
object AppLocale {

    private const val PREFERENCES = "saikai_locale"
    private const val KEY_LANGUAGE = "app_language"

    /**
     * Reads the chosen language synchronously.
     *
     * DataStore is the authoritative store, but it is asynchronous and
     * `attachBaseContext` runs before anything can await it. A one-key
     * SharedPreferences file is the smallest thing that can be read in time;
     * it is a cache of the DataStore value, never a second source of truth.
     */
    fun cached(context: Context): AppLanguage =
        AppLanguage.fromTag(
            context.applicationContext
                .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .getString(KEY_LANGUAGE, null)
        )

    /** Mirrors the authoritative value so the next process start can read it in time. */
    fun cache(context: Context, language: AppLanguage) {
        context.applicationContext
            .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LANGUAGE, language.tag)
            .apply()
    }

    /**
     * Hands the choice to the platform on API 33+.
     *
     * A no-op below that, where [wrap] does the work instead.
     */
    fun applyToSystem(context: Context, language: AppLanguage) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val manager = context.getSystemService(LocaleManager::class.java) ?: return
        manager.applicationLocales = language.languageTag
            ?.let { LocaleList.forLanguageTags(it) }
            ?: LocaleList.getEmptyLocaleList()
    }

    /**
     * Returns a context that resolves resources in the chosen language.
     *
     * Call from `attachBaseContext` in every Activity, and when building text
     * outside one -- notifications and the overlay run in the Service, which has
     * its own context (`docs/04_UI_UX.md` section 35.1).
     */
    fun wrap(base: Context): Context = wrap(base, cached(base))

    fun wrap(base: Context, language: AppLanguage): Context {
        // On API 33+ the system has already resolved the locale for every
        // context in the process. Overriding again would fight it, and would
        // also ignore a change the user made in system Settings.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return base

        val tag = language.languageTag ?: return base
        val configuration = Configuration(base.resources.configuration)
        configuration.setLocales(LocaleList.forLanguageTags(tag))
        return base.createConfigurationContext(configuration)
    }
}
