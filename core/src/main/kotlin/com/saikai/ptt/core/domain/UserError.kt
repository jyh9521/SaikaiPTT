package com.saikai.ptt.core.domain

/**
 * Why a local-user operation was refused.
 *
 * Every case carries what the UI needs to explain itself in five languages
 * without the domain layer writing any prose (`docs/04_UI_UX.md` section 38).
 */
sealed interface UserError {

    /** The proposed display name is not usable. */
    sealed interface Name : UserError {

        /** Empty, or nothing but whitespace. */
        data object Blank : Name

        data class TooManyCharacters(val actual: Int, val max: Int) : Name

        /**
         * The name fits the character limit but not the byte limit.
         *
         * Both limits exist because the name travels in packet payloads with a
         * one-byte length prefix (`docs/03_Protocol.md` section 5.1). A Burmese
         * or Bengali character is three bytes in UTF-8, so twenty-four
         * characters can be seventy-two bytes -- a limit counted in characters
         * alone would let a perfectly ordinary name overflow the field.
         */
        data class TooManyBytes(val actual: Int, val max: Int) : Name
    }

    /** No stored user has this id. */
    data class NotFound(val id: String) : UserError

    /**
     * The only remaining name cannot be deleted.
     *
     * Without a name there is no identity to transmit and PTT is unavailable
     * (`docs/01_PRD.md` section 25), so deleting the last one would strand the
     * user in the first-run flow with their history still on the device.
     */
    data object CannotDeleteLastUser : UserError

    /**
     * The name currently in use cannot be deleted directly.
     *
     * Switching first is required so the user chooses who they become, rather
     * than the app picking for them (`docs/01_PRD.md` section 25).
     */
    data class CannotDeleteActiveUser(val id: String) : UserError
}
