package com.saikai.ptt.storage.history

import com.saikai.ptt.core.common.repeatEvery
import com.saikai.ptt.core.config.SaikaiConfig
import com.saikai.ptt.core.domain.SettingsRepository
import com.saikai.ptt.core.history.CleanupReport
import com.saikai.ptt.core.history.HistoryCleaner
import com.saikai.ptt.core.logger.LogCategory
import com.saikai.ptt.core.logger.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * When cleanup runs.
 *
 * [HistoryCleaner] knows what to delete; this knows when to ask it, and reads
 * the retention setting each time rather than caching it -- a user who switches
 * from thirty days to one expects the next pass to act on that, and the next
 * pass is at most six hours away.
 *
 * ### Start-up, then every few hours
 *
 * Both, for different reasons. A device that is restarted daily would be served
 * by start-up alone; a device left running for a week -- the intended
 * deployment, a radio on a charger in a warehouse -- would never clean up at
 * all. `repeatEvery` delays before the first run, so the start-up pass is a
 * separate explicit call.
 *
 * It runs inside the foreground service's scope, so it stops when the service
 * does and does not need a scheduler. WorkManager would be the other answer and
 * is a dependency for something that has no deadline, no constraints and no
 * need to survive the process (`.claude/CLAUDE.md` section 32).
 *
 * ### One at a time
 *
 * The user can ask for a pass from the settings screen while the loop is due
 * one. Two passes at once would each see the other's half-deleted state, so a
 * mutex makes the second wait -- it is a slow background job, and there is
 * nothing to gain by running two.
 */
class HistoryMaintenance(
    private val cleaner: HistoryCleaner,
    private val settings: SettingsRepository,
    private val config: SaikaiConfig,
    private val logger: Logger,
) {

    private val gate = Mutex()
    private var loop: Job? = null

    /** Runs a pass now and every [SaikaiConfig.history] interval after it. */
    fun start(scope: CoroutineScope) {
        if (loop != null) return
        loop = scope.launch {
            runOnce()
            repeatEvery(
                intervalMillis = config.history.cleanupInterval.inWholeMilliseconds,
                name = "cleanup",
                logger = logger,
            ) {
                runOnce()
            }
        }
    }

    fun stop() {
        loop?.cancel()
        loop = null
    }

    /**
     * One pass, against whatever the retention setting says right now.
     *
     * Also what the settings screen's "clean up now" calls, which is why it is
     * public and why it returns the report.
     */
    suspend fun runOnce(): CleanupReport = gate.withLock {
        val retention = settings.current().historyRetention
        logger.d(LogCategory.STORAGE) { "cleanup pass, retention $retention" }
        cleaner.run(retention)
    }
}
