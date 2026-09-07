package com.saikai.ptt.core.domain

import com.saikai.ptt.core.common.Outcome

/**
 * Normalises and checks a display name.
 *
 * Shared by the repository and the UI so that the field cannot accept something
 * the repository will later refuse.
 */
object UserNameValidator {

    /** UI-facing limit, counted in code points so an emoji counts as one. */
    const val MAX_CODE_POINTS: Int = 24

    /** Wire limit, counted in UTF-8 bytes (`docs/03_Protocol.md` section 5.1). */
    const val MAX_BYTES: Int = 64

    /**
     * Trims surrounding whitespace and checks both limits.
     *
     * @return the normalised name, or why it was refused.
     */
    fun validate(raw: String): Outcome<String, UserError.Name> {
        val name = raw.trim()
        if (name.isEmpty()) return Outcome.failure(UserError.Name.Blank)

        val codePoints = name.codePointCount(0, name.length)
        if (codePoints > MAX_CODE_POINTS) {
            return Outcome.failure(UserError.Name.TooManyCharacters(codePoints, MAX_CODE_POINTS))
        }

        val bytes = name.toByteArray(Charsets.UTF_8).size
        if (bytes > MAX_BYTES) {
            return Outcome.failure(UserError.Name.TooManyBytes(bytes, MAX_BYTES))
        }

        return Outcome.success(name)
    }

    /**
     * Checks a name that arrived from another device.
     *
     * Only the wire rule applies here. [MAX_CODE_POINTS] is this app's own input
     * limit, chosen so the device list stays readable; a peer built differently,
     * or a later version of this one, may legitimately send a longer name within
     * the 64-byte budget, and refusing it would make that device invisible rather
     * than merely untidy.
     *
     * The protocol layer has already enforced the byte limit by the time a name
     * reaches this. Checking again costs nothing and means the domain does not
     * depend on that having happened.
     *
     * @return the trimmed name, or null if it is unusable.
     */
    fun normalizeRemote(raw: String): String? {
        val name = raw.trim()
        if (name.isEmpty()) return null
        if (name.toByteArray(Charsets.UTF_8).size > MAX_BYTES) return null
        return name
    }
}
