package com.saikai.ptt.core.logger

/**
 * Where log entries actually go.
 *
 * The Logger lives in `core`, which has no Android types, so the destination is
 * injected: `android.util.Log` in the app, a recording list in tests, nothing at
 * all where logging is off.
 *
 * Implementations must be safe to call from any thread and must not block. This
 * is called from the UDP receive threads and the audio capture thread; a sink
 * that takes a lock held by something slow would add jitter to live speech.
 */
fun interface LogSink {

    // No default for `throwable`: a fun interface's abstract method may not have
    // one, and the SAM conversion is worth more here than the default. Every
    // caller passes it explicitly anyway.
    fun write(
        level: LogLevel,
        category: LogCategory,
        message: String,
        throwable: Throwable?,
    )

    companion object {
        /** Discards everything. Useful as a default and in tests that ignore logging. */
        val None: LogSink = LogSink { _, _, _, _ -> }
    }
}
