package com.saikai.ptt.logging

import android.util.Log
import com.saikai.ptt.core.logger.LogCategory
import com.saikai.ptt.core.logger.LogLevel
import com.saikai.ptt.core.logger.LogSink

/**
 * Writes log entries to logcat.
 *
 * This is the only place in the project that touches `android.util.Log`; the
 * Logger itself is Android-free so that protocol, session and presence logic
 * stays testable on the JVM.
 *
 * The category becomes part of the tag, which is what makes
 * `adb logcat -s SaikaiPTT.DISCOVERY:*` useful when chasing one subsystem
 * without reading every audio frame.
 */
class AndroidLogSink(private val tagPrefix: String = DEFAULT_TAG_PREFIX) : LogSink {

    // Tags are built once per category rather than per entry: this runs on the
    // receive and audio threads, where per-call string concatenation is exactly
    // the allocation the Logger design exists to avoid.
    private val tags: Array<String> =
        Array(LogCategory.entries.size) { "$tagPrefix.${LogCategory.entries[it].name}" }

    override fun write(
        level: LogLevel,
        category: LogCategory,
        message: String,
        throwable: Throwable?,
    ) {
        val tag = tags[category.ordinal]
        when (level) {
            LogLevel.DEBUG -> Log.d(tag, message, throwable)
            LogLevel.INFO -> Log.i(tag, message, throwable)
            LogLevel.WARN -> Log.w(tag, message, throwable)
            LogLevel.ERROR -> Log.e(tag, message, throwable)
        }
    }

    companion object {
        const val DEFAULT_TAG_PREFIX: String = "SaikaiPTT"
    }
}
