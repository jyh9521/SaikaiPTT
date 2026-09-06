package com.saikai.ptt.storage

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.saikai.ptt.core.domain.AppSettings
import com.saikai.ptt.core.domain.SettingsCodec
import com.saikai.ptt.core.domain.SettingsRepository
import com.saikai.ptt.core.logger.LogCategory
import com.saikai.ptt.core.logger.Logger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

/** The single DataStore instance for the process. */
private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = DataStoreSettingsRepository.STORE_NAME,
)

/**
 * Persists settings with DataStore.
 *
 * Deliberately thin. Every decision about defaults, corrupt values and unknown
 * enum names lives in [SettingsCodec] over in `core`, where it can be tested on
 * the JVM in milliseconds. This class only moves values between DataStore's
 * typed preference keys and that flat map.
 */
class DataStoreSettingsRepository(
    private val dataStore: DataStore<Preferences>,
    private val logger: Logger,
) : SettingsRepository {

    override val settings: Flow<AppSettings> = dataStore.data
        .catch { cause ->
            // A damaged or unreadable file must not stop the app starting
            // (docs/05_DataModel.md section 41). Every setting has a safe
            // default, so starting fresh is strictly better than crashing on
            // launch with no way for the user to recover.
            if (cause is IOException) {
                logger.e(LogCategory.STORAGE, cause) { "Settings unreadable; using defaults" }
                emit(emptyPreferences())
            } else {
                throw cause
            }
        }
        .map { preferences -> SettingsCodec.decode(preferences.toRawMap()) }
        .distinctUntilChanged()

    override suspend fun current(): AppSettings = settings.first()

    override suspend fun update(transform: (AppSettings) -> AppSettings): AppSettings {
        var updated = AppSettings.DEFAULT
        dataStore.edit { preferences ->
            // Read inside the edit block: DataStore serialises these, so
            // read-modify-write is atomic and two callers changing different
            // settings cannot clobber each other.
            updated = transform(SettingsCodec.decode(preferences.toRawMap()))
            preferences.write(updated)
        }
        return updated
    }

    private fun Preferences.toRawMap(): Map<String, Any?> =
        asMap().entries.associate { (key, value) -> key.name to value }

    private fun MutablePreferences.write(settings: AppSettings) {
        SettingsCodec.encode(settings).forEach { (name, value) ->
            when (value) {
                null -> remove(stringPreferencesKey(name))
                is String -> set(stringPreferencesKey(name), value)
                is Boolean -> set(booleanPreferencesKey(name), value)
                else -> error("Unsupported setting type for '$name': ${value::class.java.name}")
            }
        }
    }

    companion object {
        const val STORE_NAME: String = "saikai_settings"

        /** Builds a repository backed by the process-wide store. */
        fun create(context: Context, logger: Logger): SettingsRepository =
            DataStoreSettingsRepository(context.applicationContext.settingsDataStore, logger)
    }
}
