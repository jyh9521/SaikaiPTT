package com.saikai.ptt.core.logger

/**
 * Subsystem a log entry belongs to.
 *
 * Categories exist so that troubleshooting one subsystem does not require
 * drowning in the others: chasing a discovery problem should not mean reading
 * every audio frame callback. `docs/01_PRD.md` section 48 and
 * `docs/02_Architecture.md` section 33.
 */
enum class LogCategory {
    LIFECYCLE,
    NETWORK,
    DISCOVERY,
    PRESENCE,
    PROTOCOL,
    SESSION,
    AUDIO,
    SERVICE,
    STORAGE,
    PERMISSION,
    ASR,
    OVERLAY,
    ;

    companion object {
        /**
         * Categories that stay on in release builds **below ERROR level**.
         *
         * Deliberately narrow. The realtime path (protocol, audio, network) logs
         * per packet or per frame -- 50 packets a second during a call -- so
         * leaving it enabled in release would cost battery and bury anything
         * useful. `docs/03_Protocol.md` section 50.
         *
         * Errors are never filtered by category; see
         * [com.saikai.ptt.core.config.LoggingConfig.isEnabled]. This set
         * suppresses volume, not diagnosis.
         */
        val RELEASE_DEFAULT: Set<LogCategory> = setOf(
            LIFECYCLE,
            SERVICE,
            PERMISSION,
            STORAGE,
        )
    }
}
