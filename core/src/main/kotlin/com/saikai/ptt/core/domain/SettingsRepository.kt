package com.saikai.ptt.core.domain

import kotlinx.coroutines.flow.Flow

/**
 * The single entry point for every persisted setting.
 *
 * Device identity, local users, language, PTT policy, history retention and ASR
 * state all go through here. Nothing else in the app opens DataStore
 * (`docs/05_DataModel.md` section 3).
 *
 * Mutation is one `update` rather than a setter per key. Read-modify-write is
 * then atomic by construction -- two callers changing different settings at the
 * same time cannot clobber each other -- and a caller states intent as
 * `update { it.copy(asrEnabled = true) }`, which cannot accidentally reset the
 * ten fields it did not mention.
 */
interface SettingsRepository {

    /** Emits the current settings, then again on every change. */
    val settings: Flow<AppSettings>

    /** A one-shot read, for callers that are not observing. */
    suspend fun current(): AppSettings

    /**
     * Applies [transform] atomically and persists the result.
     *
     * @return the settings as stored after the change.
     */
    suspend fun update(transform: (AppSettings) -> AppSettings): AppSettings
}
