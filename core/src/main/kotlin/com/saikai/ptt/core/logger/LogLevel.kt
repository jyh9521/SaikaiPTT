package com.saikai.ptt.core.logger

/**
 * Log severity, ordered so that a minimum level can be compared numerically.
 *
 * Defined here rather than in [com.saikai.ptt.core.config] because logging owns
 * the concept; the config only chooses a threshold. The Logger itself arrives in
 * Task06.
 */
enum class LogLevel {
    DEBUG,
    INFO,
    WARN,
    ERROR,
    ;

    /** True when a message at [this] level should be emitted given [minimum]. */
    fun isAtLeast(minimum: LogLevel): Boolean = ordinal >= minimum.ordinal
}
