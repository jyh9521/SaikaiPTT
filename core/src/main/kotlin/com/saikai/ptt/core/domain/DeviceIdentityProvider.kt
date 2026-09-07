package com.saikai.ptt.core.domain

import com.saikai.ptt.core.logger.LogCategory
import com.saikai.ptt.core.logger.Logger
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Supplies this installation's permanent [DeviceId], generating it on first use. */
interface DeviceIdentityProvider {

    /**
     * The Device ID, generated and persisted on first call.
     *
     * Returns the same value for the lifetime of the installation.
     */
    suspend fun deviceId(): DeviceId
}

/**
 * Stores the identity through [SettingsRepository] rather than opening its own
 * file. One store means one place where a corrupt write can happen, and one
 * place to reason about `docs/05_DataModel.md` section 41.
 *
 * Pure Kotlin with no Android types, so first-generation, persistence and the
 * repair path are all provable in a JVM test.
 *
 * @param generate injectable so a test can force a known or colliding value.
 */
class StoredDeviceIdentityProvider(
    private val settings: SettingsRepository,
    private val logger: Logger,
    private val generate: () -> DeviceId = DeviceId::random,
) : DeviceIdentityProvider {

    // The id is read on the send path for every packet header. Reading DataStore
    // each time would be pointless work for a value that cannot change.
    @Volatile
    private var cached: DeviceId? = null
    private val mutex = Mutex()

    override suspend fun deviceId(): DeviceId {
        cached?.let { return it }
        return mutex.withLock {
            cached ?: resolve().also { cached = it }
        }
    }

    private suspend fun resolve(): DeviceId {
        DeviceId.parse(settings.current().deviceId)?.let { return it }

        // Generating inside the transform makes this safe against two callers
        // racing: SettingsRepository.update is atomic, so the second sees the
        // first's value and keeps it. Without that, two coroutines starting the
        // app could each mint an id and one would silently win.
        val stored = settings.update { current ->
            if (DeviceId.parse(current.deviceId) != null) {
                current
            } else {
                // Blank means never written; only a non-blank value that
                // failed to parse is actual damage. It cannot be put on the
                // wire, so a replacement is the only way forward -- but peers
                // will then see this installation as a new device, which
                // deserves to be loud rather than silent.
                if (!current.deviceId.isNullOrBlank()) {
                    logger.e(LogCategory.STORAGE) {
                        "Stored device id was unreadable; generating a replacement"
                    }
                }
                current.copy(deviceId = generate().value)
            }
        }

        return DeviceId.parse(stored.deviceId)
            ?: error("Device id was just written but does not parse: ${stored.deviceId}")
    }
}
