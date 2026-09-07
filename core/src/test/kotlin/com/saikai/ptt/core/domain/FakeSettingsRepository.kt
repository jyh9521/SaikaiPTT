package com.saikai.ptt.core.domain

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * In-memory settings for tests.
 *
 * `update` holds a lock for the whole read-modify-write, matching DataStore's
 * guarantee. Without that a test could not tell apart code that is correct under
 * concurrency from code that merely happens to work when nothing races.
 */
class FakeSettingsRepository(
    initial: AppSettings = AppSettings.DEFAULT,
) : SettingsRepository {

    private val state = MutableStateFlow(initial)
    private val mutex = Mutex()

    /** How many times [update] has been applied, for asserting on write traffic. */
    var updateCount: Int = 0
        private set

    override val settings: Flow<AppSettings> = state

    override suspend fun current(): AppSettings = state.value

    override suspend fun update(transform: (AppSettings) -> AppSettings): AppSettings =
        mutex.withLock {
            updateCount++
            state.value = transform(state.value)
            state.value
        }
}
