package com.saikai.ptt

import android.app.Application
import com.saikai.ptt.di.AppContainer
import com.saikai.ptt.storage.DataStoreSettingsRepository

/**
 * Owns the application-scope dependency container.
 *
 * The container has to outlive any Activity: the Foreground Service keeps
 * receiving with no Activity alive at all, which is what makes background PTT
 * work (`docs/02_Architecture.md` section 6).
 */
class SaikaiApplication : Application() {

    /**
     * Built lazily so that a process created only to run a ContentProvider or a
     * broadcast receiver does not pay for it.
     */
    val container: AppContainer by lazy {
        AppContainer(
            isDebugBuild = BuildConfig.DEBUG,
            settingsRepositoryFactory = { logger ->
                DataStoreSettingsRepository.create(this, logger)
            },
        )
    }

    /**
     * Starts counting visible Activities.
     *
     * Eagerly, unlike the container itself: the count has to be right from the
     * first Activity, and a lazy observer would report "nothing on screen" for
     * whatever came before it. It costs one object and two callbacks.
     */
    override fun onCreate() {
        super.onCreate()
        container.visibility.register(this)
    }
}
