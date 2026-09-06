package com.saikai.ptt.core.domain

/**
 * The five supported interface languages, plus following the system.
 *
 * `docs/01_PRD.md` section 26. Japanese is the product default, not English.
 */
enum class AppLanguage(val tag: String) {
    /** Follow the device language, falling back to Japanese when it is not one of ours. */
    SYSTEM("system"),
    JAPANESE("ja"),
    SIMPLIFIED_CHINESE("zh-CN"),
    ENGLISH("en"),
    BURMESE("my"),
    BENGALI("bn"),
    ;

    companion object {
        val DEFAULT: AppLanguage = JAPANESE

        /**
         * Never throws. A stored tag can survive a downgrade that removed a
         * language, and an unreadable preference must not stop the app starting.
         */
        fun fromTag(tag: String?): AppLanguage =
            entries.firstOrNull { it.tag == tag } ?: DEFAULT
    }
}
