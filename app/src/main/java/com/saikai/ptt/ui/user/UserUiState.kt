package com.saikai.ptt.ui.user

import androidx.annotation.StringRes
import com.saikai.ptt.R
import com.saikai.ptt.core.common.Outcome
import com.saikai.ptt.core.domain.UserError
import com.saikai.ptt.core.domain.UserNameValidator

/**
 * What the name screens show.
 *
 * Two screens share this file because they share the rules: the first-run
 * screen creates the only name there is, and the management screen does
 * everything else, but a name is valid or not for the same reasons in both
 * (`docs/04_UI_UX.md` sections 7 and 8).
 */

/**
 * Whether this device knows who it is yet.
 *
 * [Loading] exists because the answer is read from DataStore and is therefore
 * not available in the frame that draws the first screen. Without it every
 * launch would show the welcome screen for a few milliseconds before replacing
 * it -- a flash of "create a name" in front of a user who created one months
 * ago.
 */
sealed interface NameGate {

    data object Loading : NameGate

    /** No name. PTT is unavailable and the welcome screen is the whole app. */
    data object Missing : NameGate

    data class Ready(val displayName: String) : NameGate
}

/** One stored name. */
data class UserRow(
    val id: String,
    val displayName: String,
    /** The name this device speaks under. Marked in the list (section 8). */
    val active: Boolean,
)

/**
 * The add-or-edit field, while it is open.
 *
 * [original] is kept so that backing out can tell an abandoned edit from an
 * untouched one. Section 46 asks for a prompt when there is unsaved work, and
 * only when there is: prompting every time teaches people to dismiss prompts.
 */
data class NameEditor(
    /** The name being edited, or null when creating a new one. */
    val editingId: String?,
    val text: String,
    val original: String,
) {
    val isCreating: Boolean get() = editingId == null

    val problem: NameProblem? get() = nameProblem(text)

    /** True when the field holds something different from what was there. */
    val isDirty: Boolean get() = text.trim() != original

    val canSave: Boolean get() = problem == null && text.isNotBlank()
}

/**
 * Why a name cannot be saved, as the user types.
 *
 * Blank is deliberately not one of these. An empty field is where every new
 * name starts, and colouring it red before a single character has been typed
 * would be scolding the user for beginning. Blank disables the button and says
 * nothing.
 */
enum class NameProblem {
    /** More than [UserNameValidator.MAX_CODE_POINTS] characters. */
    TOO_LONG,

    /**
     * Within the character limit but over the byte limit.
     *
     * Reachable with ordinary names: a Burmese or Bengali character is three
     * UTF-8 bytes, so twenty-four of them are seventy-two -- past the 64 the
     * packet field holds (`docs/03_Protocol.md` section 5.1).
     */
    TOO_LONG_IN_BYTES,
}

/** Something that happened once and is worth a line of text. */
enum class UserMessage {
    /** Section 54: the name may not change while a session is open. */
    IN_CALL,
    CANNOT_DELETE_LAST,
    CANNOT_DELETE_ACTIVE,
    NOT_FOUND,
    NAME_BLANK,
    NAME_TOO_LONG,
}

/**
 * Runs the same check the repository will run, before the user presses save.
 *
 * Shared deliberately: a field that accepts what the repository refuses is a
 * field that lies, and the two limits are not obvious enough to be restated by
 * hand in the UI.
 */
fun nameProblem(raw: String): NameProblem? =
    when (val outcome = UserNameValidator.validate(raw)) {
        is Outcome.Success -> null
        is Outcome.Failure -> when (outcome.error) {
            UserError.Name.Blank -> null
            is UserError.Name.TooManyCharacters -> NameProblem.TOO_LONG
            is UserError.Name.TooManyBytes -> NameProblem.TOO_LONG_IN_BYTES
        }
    }

/**
 * The line of text a [UserMessage] shows.
 *
 * Kept beside the enum rather than in either screen: both screens show the same
 * messages, and a second copy of this mapping is a second place for them to
 * disagree.
 */
@StringRes
internal fun UserMessage.labelRes(): Int = when (this) {
    UserMessage.IN_CALL -> R.string.user_message_in_call
    UserMessage.CANNOT_DELETE_LAST -> R.string.user_message_cannot_delete_last
    UserMessage.CANNOT_DELETE_ACTIVE -> R.string.user_message_cannot_delete_active
    UserMessage.NOT_FOUND -> R.string.user_message_not_found
    UserMessage.NAME_BLANK -> R.string.user_message_name_blank
    UserMessage.NAME_TOO_LONG -> R.string.user_message_name_rejected
}
