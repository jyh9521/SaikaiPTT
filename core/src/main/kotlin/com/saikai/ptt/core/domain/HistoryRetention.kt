package com.saikai.ptt.core.domain

import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

/**
 * How long completed communications are kept before automatic cleanup.
 *
 * `docs/05_DataModel.md` section 29. Favourited records are exempt from cleanup
 * regardless of this setting.
 */
enum class HistoryRetention(val duration: Duration?) {
    ONE_DAY(1.days),
    THREE_DAYS(3.days),
    SEVEN_DAYS(7.days),
    THIRTY_DAYS(30.days),

    /** Kept until the user deletes it. */
    FOREVER(null),
    ;

    companion object {
        val DEFAULT: HistoryRetention = SEVEN_DAYS

        /** Never throws; an unrecognised stored value falls back to [DEFAULT]. */
        fun fromName(name: String?): HistoryRetention =
            entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}
