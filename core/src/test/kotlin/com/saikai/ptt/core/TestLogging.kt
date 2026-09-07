package com.saikai.ptt.core

import com.saikai.ptt.core.logger.LogCategory
import com.saikai.ptt.core.logger.LogLevel
import com.saikai.ptt.core.logger.LogSink

/** Captures log output so tests can assert on throttling and release silence. */
internal class RecordingSink : LogSink {
    val entries = mutableListOf<String>()

    override fun write(
        level: LogLevel,
        category: LogCategory,
        message: String,
        throwable: Throwable?,
    ) {
        entries += "$level/$category: $message"
    }
}

/** A clock the test advances by hand, so rate-limit windows are deterministic. */
internal class FakeClock(var millis: Long = 0L) : () -> Long {
    override fun invoke(): Long = millis
    fun advance(by: Long) {
        millis += by
    }
}
