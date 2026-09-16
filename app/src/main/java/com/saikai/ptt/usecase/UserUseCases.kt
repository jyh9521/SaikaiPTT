package com.saikai.ptt.usecase

import com.saikai.ptt.core.common.Outcome
import com.saikai.ptt.core.domain.LocalUser
import com.saikai.ptt.core.domain.LocalUserRepository
import com.saikai.ptt.core.domain.UserError
import com.saikai.ptt.core.session.SessionState
import com.saikai.ptt.service.ServiceStatus
import kotlinx.coroutines.flow.Flow

/**
 * What the name screens can observe and do.
 *
 * The lifecycle itself -- validation, the last-name rule, the active-name rule,
 * the repair of a dangling pointer -- already lives in
 * [com.saikai.ptt.core.domain.SettingsLocalUserRepository] and is not restated
 * here. This layer adds the one rule the repository cannot know, in
 * [SwitchActiveUser], and otherwise only names the actions
 * (`docs/02_Architecture.md` section 4.3).
 */
class UserUseCases(
    val observeUsers: ObserveLocalUsers,
    val observeActiveUser: ObserveActiveUser,
    val createUser: CreateLocalUser,
    val renameUser: RenameLocalUser,
    val deleteUser: DeleteLocalUser,
    val switchActiveUser: SwitchActiveUser,
)

/** Every name stored on this device, as the list changes. */
class ObserveLocalUsers(private val users: LocalUserRepository) {
    operator fun invoke(): Flow<List<LocalUser>> = users.users
}

/**
 * Adds a name.
 *
 * The first one created also becomes the name in use -- the repository does
 * that, because the user reached this flow precisely because there was none and
 * being asked to choose it again would be asking twice.
 */
class CreateLocalUser(private val users: LocalUserRepository) {
    suspend operator fun invoke(displayName: String): Outcome<LocalUser, UserError> =
        users.create(displayName)
}

/** Changes a name's text. The id does not change, so history keeps pointing at it. */
class RenameLocalUser(private val users: LocalUserRepository) {
    suspend operator fun invoke(id: String, displayName: String): Outcome<LocalUser, UserError> =
        users.rename(id, displayName)
}

/** Removes a name, unless it is the last one or the one in use. */
class DeleteLocalUser(private val users: LocalUserRepository) {
    suspend operator fun invoke(id: String): Outcome<Unit, UserError> = users.delete(id)
}

/** Why a switch did not happen. */
sealed interface SwitchFailure {

    /**
     * A call is in progress.
     *
     * `docs/04_UI_UX.md` section 54: the identity a session was opened under has
     * to stay the same for as long as the session lasts. The peer was told who
     * is speaking in VOICE_START and is showing that name; changing it halfway
     * would leave the two devices disagreeing about who is talking, and the
     * history record written at the end would name somebody who did not speak.
     */
    data object InCall : SwitchFailure

    /** The repository refused it. */
    data class Rejected(val error: UserError) : SwitchFailure
}

/**
 * Changes which name this device speaks under.
 *
 * The in-call check lives here rather than in the repository because the
 * repository has no idea a session exists and must not learn: it is pure Kotlin
 * over stored settings, and giving it a dependency on the session machine would
 * make storing a name require a running service.
 *
 * Read from [ServiceStatus] rather than the service, which is the same reason
 * that class exists -- the screen works with nothing running, and with nothing
 * running there is no session to be in.
 */
class SwitchActiveUser(
    private val users: LocalUserRepository,
    private val status: ServiceStatus,
) {
    suspend operator fun invoke(id: String): Outcome<LocalUser, SwitchFailure> {
        if (status.session.value != SessionState.Idle) {
            return Outcome.failure(SwitchFailure.InCall)
        }
        return when (val outcome = users.switchActive(id)) {
            is Outcome.Success -> outcome
            is Outcome.Failure -> Outcome.failure(SwitchFailure.Rejected(outcome.error))
        }
    }
}
