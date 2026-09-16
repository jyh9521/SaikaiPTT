package com.saikai.ptt.ui.user

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.saikai.ptt.core.common.Outcome
import com.saikai.ptt.core.domain.UserError
import com.saikai.ptt.usecase.SwitchFailure
import com.saikai.ptt.usecase.UserUseCases
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The names stored on this device, and everything that can be done to them.
 *
 * One view model for both name screens. They are two views of one list: the
 * welcome screen is the management screen with nothing in it yet, and the rules
 * -- what a name may contain, which name may be deleted, when the active name
 * may change -- do not differ between them.
 *
 * Nothing here decides what a valid name is or whether a deletion is allowed.
 * Those live in the repository and are reached through [UserUseCases]; this
 * turns their answers into something a screen can display
 * (`docs/02_Architecture.md` section 4.2).
 */
class UsersViewModel(private val useCases: UserUseCases) : ViewModel() {

    /**
     * Whether this device has a name yet.
     *
     * Starts at [NameGate.Loading] and never goes back to it, because the
     * question is answered once by the first value DataStore emits.
     */
    val gate: StateFlow<NameGate> = useCases.observeActiveUser()
        .map { user ->
            if (user == null) NameGate.Missing else NameGate.Ready(user.displayName)
        }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NameGate.Loading)

    val rows: StateFlow<List<UserRow>> = combine(
        useCases.observeUsers(),
        useCases.observeActiveUser(),
    ) { users, active ->
        users.map { UserRow(it.id, it.displayName, active = it.id == active?.id) }
    }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _editor = MutableStateFlow<NameEditor?>(null)

    /** The add-or-edit field, or null when it is closed. */
    val editor: StateFlow<NameEditor?> = _editor.asStateFlow()

    private val _discardPrompt = MutableStateFlow(false)

    /** True while asking whether to throw away an unsaved edit (section 46). */
    val discardPrompt: StateFlow<Boolean> = _discardPrompt.asStateFlow()

    private val _deletePrompt = MutableStateFlow<UserRow?>(null)

    /**
     * The name a deletion has been asked for, before it happens.
     *
     * Deleting is not undoable and the rows are a tap apart, so it is confirmed.
     * This is the only destructive action on the screen.
     */
    val deletePrompt: StateFlow<UserRow?> = _deletePrompt.asStateFlow()

    private val _message = MutableStateFlow<UserMessage?>(null)
    val message: StateFlow<UserMessage?> = _message.asStateFlow()

    // --- The editor -----------------------------------------------------------------

    fun startCreate() {
        _editor.value = NameEditor(editingId = null, text = "", original = "")
    }

    fun startEdit(row: UserRow) {
        _editor.value = NameEditor(
            editingId = row.id,
            text = row.displayName,
            original = row.displayName,
        )
    }

    fun editDraft(text: String) {
        _editor.value = _editor.value?.copy(text = text)
        _message.value = null
    }

    /**
     * Backing out of the editor.
     *
     * Closes immediately when nothing was typed, and asks first when something
     * was. The prompt is section 46's requirement and its scope: unsaved *work*,
     * not merely an open field.
     */
    fun requestCloseEditor() {
        val current = _editor.value ?: return
        if (current.isDirty) _discardPrompt.value = true else _editor.value = null
    }

    fun confirmDiscard() {
        _discardPrompt.value = false
        _editor.value = null
    }

    fun cancelDiscard() {
        _discardPrompt.value = false
    }

    /**
     * Saves the field.
     *
     * The repository validates again and is the authority. This checks first
     * only so the button can be disabled, and a refusal that arrives anyway is
     * shown rather than swallowed.
     */
    fun saveEditor() {
        val current = _editor.value ?: return
        if (!current.canSave) return
        val editingId = current.editingId
        viewModelScope.launch {
            val outcome = if (editingId == null) {
                useCases.createUser(current.text)
            } else {
                useCases.renameUser(editingId, current.text)
            }
            when (outcome) {
                is Outcome.Success -> {
                    _editor.value = null
                    _discardPrompt.value = false
                }

                is Outcome.Failure -> _message.value = outcome.error.toMessage()
            }
        }
    }

    /** Creates the first name, from the welcome screen, which has no editor state. */
    fun createFirstUser(displayName: String) {
        viewModelScope.launch {
            val outcome = useCases.createUser(displayName)
            if (outcome is Outcome.Failure) _message.value = outcome.error.toMessage()
        }
    }

    // --- The list -------------------------------------------------------------------

    fun switchTo(row: UserRow) {
        if (row.active) return
        viewModelScope.launch {
            val outcome = useCases.switchActiveUser(row.id)
            if (outcome is Outcome.Failure) {
                _message.value = when (val reason = outcome.error) {
                    SwitchFailure.InCall -> UserMessage.IN_CALL
                    is SwitchFailure.Rejected -> reason.error.toMessage()
                }
            }
        }
    }

    fun requestDelete(row: UserRow) {
        _deletePrompt.value = row
        _message.value = null
    }

    fun cancelDelete() {
        _deletePrompt.value = null
    }

    fun confirmDelete() {
        val row = _deletePrompt.value ?: return
        _deletePrompt.value = null
        viewModelScope.launch {
            val outcome = useCases.deleteUser(row.id)
            if (outcome is Outcome.Failure) _message.value = outcome.error.toMessage()
        }
    }

    fun dismissMessage() {
        _message.value = null
    }

    class Factory(private val useCases: UserUseCases) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            UsersViewModel(useCases) as T
    }
}

private fun UserError.toMessage(): UserMessage = when (this) {
    // Blank should not reach here -- the save button is disabled for an empty
    // field -- but it is mapped rather than ignored, because a silent no-op is
    // the worst of the three outcomes if some other caller ever forgets.
    UserError.Name.Blank -> UserMessage.NAME_BLANK
    is UserError.Name.TooManyCharacters, is UserError.Name.TooManyBytes ->
        UserMessage.NAME_TOO_LONG

    UserError.CannotDeleteLastUser -> UserMessage.CANNOT_DELETE_LAST
    is UserError.CannotDeleteActiveUser -> UserMessage.CANNOT_DELETE_ACTIVE
    is UserError.NotFound -> UserMessage.NOT_FOUND
}
