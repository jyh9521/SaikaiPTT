package com.saikai.ptt.core.domain

import com.saikai.ptt.core.common.Outcome
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.util.UUID

/**
 * The names stored on this device, and which one is in use.
 *
 * A name is local configuration, not an account. A remote device does not need
 * to know it in advance -- it arrives with the voice (`docs/01_PRD.md`
 * section 5.2).
 */
interface LocalUserRepository {

    val users: Flow<List<LocalUser>>

    /** The name currently in use, or null when none is selected. */
    val activeUser: Flow<LocalUser?>

    suspend fun create(displayName: String): Outcome<LocalUser, UserError>

    suspend fun rename(id: String, displayName: String): Outcome<LocalUser, UserError>

    suspend fun delete(id: String): Outcome<Unit, UserError>

    suspend fun switchActive(id: String): Outcome<LocalUser, UserError>
}

/**
 * Local users on top of [SettingsRepository].
 *
 * Pure Kotlin, so every rule below is provable in a JVM test rather than only
 * on a device.
 *
 * @param now injectable so tests can assert on timestamps.
 * @param newId injectable so tests can force a known or colliding id.
 */
class SettingsLocalUserRepository(
    private val settings: SettingsRepository,
    private val now: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) : LocalUserRepository {

    override val users: Flow<List<LocalUser>> =
        settings.settings.map { it.localUsers }.distinctUntilChanged()

    override val activeUser: Flow<LocalUser?> =
        settings.settings.map { it.activeUser }.distinctUntilChanged()

    override suspend fun create(displayName: String): Outcome<LocalUser, UserError> {
        val name = when (val checked = UserNameValidator.validate(displayName)) {
            is Outcome.Success -> checked.value
            is Outcome.Failure -> return Outcome.failure(checked.error)
        }

        val timestamp = now()
        val user = LocalUser(newId(), name, timestamp, timestamp)

        settings.update { current ->
            current.copy(
                localUsers = current.localUsers + user,
                // The first name created becomes the one in use: the user just
                // asked for a name in a flow that exists because there was none,
                // and making them pick it again would be pointless.
                activeUserId = current.activeUserId ?: user.id,
            ).repaired()
        }
        return Outcome.success(user)
    }

    override suspend fun rename(id: String, displayName: String): Outcome<LocalUser, UserError> {
        val name = when (val checked = UserNameValidator.validate(displayName)) {
            is Outcome.Success -> checked.value
            is Outcome.Failure -> return Outcome.failure(checked.error)
        }

        var renamed: LocalUser? = null
        settings.update { current ->
            val existing = current.localUsers.firstOrNull { it.id == id }
                ?: return@update current
            // The id is the identity and does not change. History records
            // reference the user who spoke, and a rename must not orphan them
            // (docs/05_DataModel.md section 5).
            val updated = existing.copy(displayName = name, updatedAt = now())
            renamed = updated
            current.copy(
                localUsers = current.localUsers.map { if (it.id == id) updated else it },
            ).repaired()
        }

        return renamed?.let { Outcome.success(it) } ?: Outcome.failure(UserError.NotFound(id))
    }

    override suspend fun delete(id: String): Outcome<Unit, UserError> {
        var failure: UserError? = null
        settings.update { current ->
            when {
                current.localUsers.none { it.id == id } -> {
                    failure = UserError.NotFound(id)
                    current
                }

                current.localUsers.size == 1 -> {
                    failure = UserError.CannotDeleteLastUser
                    current
                }

                current.activeUserId == id -> {
                    failure = UserError.CannotDeleteActiveUser(id)
                    current
                }

                else -> current.copy(
                    localUsers = current.localUsers.filterNot { it.id == id },
                ).repaired()
            }
        }
        return failure?.let { Outcome.failure(it) } ?: Outcome.success(Unit)
    }

    override suspend fun switchActive(id: String): Outcome<LocalUser, UserError> {
        var selected: LocalUser? = null
        settings.update { current ->
            val target = current.localUsers.firstOrNull { it.id == id }
                ?: return@update current
            selected = target
            current.copy(activeUserId = target.id)
        }
        return selected?.let { Outcome.success(it) } ?: Outcome.failure(UserError.NotFound(id))
    }
}

/**
 * Drops an active-user pointer that no longer resolves.
 *
 * `docs/05_DataModel.md` section 6 requires repair rather than a crash. Clearing
 * is the repair, rather than silently promoting some other name: the active user
 * is the identity transmitted with every packet, and speaking as a name the user
 * did not choose is worse than being asked to choose one. With no active user
 * the UI shows the picker and PTT stays unavailable (`docs/01_PRD.md`
 * section 4.1), which is the honest state.
 *
 * Applied after every mutation, so stored data converges to consistency on the
 * next write even if it arrived damaged.
 */
private fun AppSettings.repaired(): AppSettings =
    if (activeUserId != null && localUsers.none { it.id == activeUserId }) {
        copy(activeUserId = null)
    } else {
        this
    }
