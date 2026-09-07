package com.saikai.ptt.locale

import android.content.Context
import com.saikai.ptt.core.domain.AppLanguage
import com.saikai.ptt.core.domain.SettingsRepository
import com.saikai.ptt.core.logger.LogCategory
import com.saikai.ptt.core.logger.Logger

/**
 * Changes the interface language and keeps the three places that need to know
 * in agreement.
 *
 * DataStore is authoritative: it is what the rest of the app reads. The
 * SharedPreferences cache exists only so the next process start can resolve the
 * locale before its first Activity attaches. The platform is told on API 33+ so
 * the choice shows up in system Settings.
 *
 * Writing all three from one place is the point -- a language that is stored but
 * not applied, or applied but not stored, is a bug that only shows up after a
 * restart.
 */
class LocaleController(
    private val settings: SettingsRepository,
    private val logger: Logger,
) {

    /** The language to use right now, without waiting on storage. */
    fun current(context: Context): AppLanguage = AppLocale.cached(context)

    suspend fun set(context: Context, language: AppLanguage) {
        settings.update { it.copy(language = language) }
        AppLocale.cache(context, language)
        AppLocale.applyToSystem(context, language)
        logger.i(LogCategory.LIFECYCLE) { "Interface language set to ${language.tag}" }
    }

    /**
     * Brings the cache back in line with the authoritative value.
     *
     * Only matters if the two ever diverge -- a crash between the two writes in
     * [set], or app data restored from a backup of DataStore alone.
     */
    suspend fun reconcile(context: Context) {
        val stored = settings.current().language
        if (AppLocale.cached(context) != stored) {
            logger.w(LogCategory.LIFECYCLE) { "Locale cache was stale; restoring ${stored.tag}" }
            AppLocale.cache(context, stored)
            AppLocale.applyToSystem(context, stored)
        }
    }
}
