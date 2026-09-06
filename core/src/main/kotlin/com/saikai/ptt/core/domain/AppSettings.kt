package com.saikai.ptt.core.domain

/**
 * Every persisted setting, as one immutable snapshot.
 *
 * The complete key list and its defaults are `docs/05_DataModel.md` section 3.
 * Communication history does not live here -- that is Room. This is small,
 * structured configuration only.
 */
data class AppSettings(
    /** Null until first launch generates one (Task08). Never changes afterwards. */
    val deviceId: String? = null,
    val localUsers: List<LocalUser> = emptyList(),
    /** Null when no user exists yet, or when the stored id no longer resolves. */
    val activeUserId: String? = null,
    val language: AppLanguage = AppLanguage.DEFAULT,
    /** Receiver-side policy: may others interrupt a call I am in. */
    val allowInterrupt: Boolean = false,
    val historyRetention: HistoryRetention = HistoryRetention.DEFAULT,
    val asrEnabled: Boolean = false,
    /** Whether the offline Japanese model has been downloaded and verified. */
    val asrModelReady: Boolean = false,
    val overlayEnabled: Boolean = false,
    val firstLaunchCompleted: Boolean = false,
    val permissionGuidanceShown: Boolean = false,
) {
    /** The active user, or null when [activeUserId] does not resolve to a stored user. */
    val activeUser: LocalUser?
        get() = activeUserId?.let { id -> localUsers.firstOrNull { it.id == id } }

    companion object {
        val DEFAULT: AppSettings = AppSettings()
    }
}

/**
 * Storage key names.
 *
 * Kept as constants in one place so the strings appear exactly once: a typo in a
 * key silently reads a default forever, which is the kind of bug that looks like
 * "my settings do not save" and is very hard to see in a diff.
 */
object SettingsKeys {
    const val DEVICE_ID = "device_id"
    const val LOCAL_USERS = "local_users"
    const val ACTIVE_USER_ID = "active_user_id"
    const val APP_LANGUAGE = "app_language"
    const val ALLOW_INTERRUPT = "allow_interrupt"
    const val HISTORY_RETENTION = "history_retention"
    const val ASR_ENABLED = "asr_enabled"
    const val ASR_MODEL_READY = "asr_model_ready"
    const val OVERLAY_ENABLED = "overlay_enabled"
    const val FIRST_LAUNCH_COMPLETED = "first_launch_completed"
    const val PERMISSION_GUIDANCE_SHOWN = "permission_guidance_shown"

    val ALL: List<String> = listOf(
        DEVICE_ID,
        LOCAL_USERS,
        ACTIVE_USER_ID,
        APP_LANGUAGE,
        ALLOW_INTERRUPT,
        HISTORY_RETENTION,
        ASR_ENABLED,
        ASR_MODEL_READY,
        OVERLAY_ENABLED,
        FIRST_LAUNCH_COMPLETED,
        PERMISSION_GUIDANCE_SHOWN,
    )
}

/**
 * Converts between [AppSettings] and a flat key/value map.
 *
 * This lives in `core`, deliberately, and it is where all the defaulting and
 * corruption handling happens. The Android side (`app.storage`) is then a thin
 * adapter that only moves values in and out of DataStore.
 *
 * The reason is testability. "Corrupt local data must not crash the app"
 * (`docs/05_DataModel.md` section 41) is a promise worth proving, and proving it
 * against a real DataStore needs a device. As a pure function over a map it is
 * provable in milliseconds on the JVM, against a value of the wrong type, a
 * truncated user list, an unknown enum name -- every shape a half-written file
 * can take.
 *
 * [decode] never throws.
 */
object SettingsCodec {

    fun decode(raw: Map<String, Any?>): AppSettings = AppSettings(
        deviceId = raw.string(SettingsKeys.DEVICE_ID)?.takeIf { it.isNotBlank() },
        localUsers = LocalUserCodec.decode(raw.string(SettingsKeys.LOCAL_USERS).orEmpty()),
        activeUserId = raw.string(SettingsKeys.ACTIVE_USER_ID)?.takeIf { it.isNotBlank() },
        language = AppLanguage.fromTag(raw.string(SettingsKeys.APP_LANGUAGE)),
        allowInterrupt = raw.boolean(SettingsKeys.ALLOW_INTERRUPT, default = false),
        historyRetention = HistoryRetention.fromName(raw.string(SettingsKeys.HISTORY_RETENTION)),
        asrEnabled = raw.boolean(SettingsKeys.ASR_ENABLED, default = false),
        asrModelReady = raw.boolean(SettingsKeys.ASR_MODEL_READY, default = false),
        overlayEnabled = raw.boolean(SettingsKeys.OVERLAY_ENABLED, default = false),
        firstLaunchCompleted = raw.boolean(SettingsKeys.FIRST_LAUNCH_COMPLETED, default = false),
        permissionGuidanceShown =
            raw.boolean(SettingsKeys.PERMISSION_GUIDANCE_SHOWN, default = false),
    )

    fun encode(settings: AppSettings): Map<String, Any?> = mapOf(
        SettingsKeys.DEVICE_ID to settings.deviceId,
        SettingsKeys.LOCAL_USERS to LocalUserCodec.encode(settings.localUsers),
        SettingsKeys.ACTIVE_USER_ID to settings.activeUserId,
        SettingsKeys.APP_LANGUAGE to settings.language.tag,
        SettingsKeys.ALLOW_INTERRUPT to settings.allowInterrupt,
        SettingsKeys.HISTORY_RETENTION to settings.historyRetention.name,
        SettingsKeys.ASR_ENABLED to settings.asrEnabled,
        SettingsKeys.ASR_MODEL_READY to settings.asrModelReady,
        SettingsKeys.OVERLAY_ENABLED to settings.overlayEnabled,
        SettingsKeys.FIRST_LAUNCH_COMPLETED to settings.firstLaunchCompleted,
        SettingsKeys.PERMISSION_GUIDANCE_SHOWN to settings.permissionGuidanceShown,
    )

    // A value of the wrong type is treated as absent rather than coerced. A
    // Boolean stored where a String belongs means the file is damaged, and
    // guessing at it would persist the damage on the next write.
    private fun Map<String, Any?>.string(key: String): String? = this[key] as? String

    private fun Map<String, Any?>.boolean(key: String, default: Boolean): Boolean =
        this[key] as? Boolean ?: default
}
