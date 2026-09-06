package com.saikai.ptt.core.domain

/**
 * A name stored on this device.
 *
 * The identity is [id], not [displayName]: names are editable, and a rename
 * must not orphan the history records that reference the user who spoke
 * (`docs/05_DataModel.md` section 5).
 */
data class LocalUser(
    val id: String,
    val displayName: String,
    val createdAt: Long,
    val updatedAt: Long,
)

/**
 * Serialises the user list for DataStore.
 *
 * Hand-written rather than pulling in a serialization framework for one list of
 * four-field records (`.claude/CLAUDE.md` section 32).
 *
 * The format is length-prefixed -- `<charCount>:<value>` per field, four fields
 * per record, concatenated -- which means no escaping and no delimiter that a
 * display name could collide with. A name containing `:`, a newline, or the
 * separator itself round-trips unchanged, and names are user input in five
 * scripts, so "no character is special" is worth more here than readability.
 *
 * Decoding never throws. Local data can be truncated by a process kill mid-write
 * or corrupted on disk, and `docs/05_DataModel.md` section 41 requires that this
 * degrade to a default rather than crash the app on launch.
 */
object LocalUserCodec {

    fun encode(users: List<LocalUser>): String = buildString {
        users.forEach { user ->
            field(user.id)
            field(user.displayName)
            field(user.createdAt.toString())
            field(user.updatedAt.toString())
        }
    }

    /** Returns an empty list for any input that is not wholly well-formed. */
    fun decode(encoded: String): List<LocalUser> {
        if (encoded.isEmpty()) return emptyList()
        val users = mutableListOf<LocalUser>()
        var cursor = 0
        while (cursor < encoded.length) {
            val id = readField(encoded, cursor) ?: return emptyList()
            val name = readField(encoded, id.next) ?: return emptyList()
            val created = readField(encoded, name.next) ?: return emptyList()
            val updated = readField(encoded, created.next) ?: return emptyList()

            val createdAt = created.value.toLongOrNull() ?: return emptyList()
            val updatedAt = updated.value.toLongOrNull() ?: return emptyList()
            if (id.value.isEmpty()) return emptyList()

            users += LocalUser(id.value, name.value, createdAt, updatedAt)
            cursor = updated.next
        }
        return users
    }

    private fun StringBuilder.field(value: String) {
        append(value.length).append(':').append(value)
    }

    private class Field(val value: String, val next: Int)

    private fun readField(source: String, start: Int): Field? {
        val separator = source.indexOf(':', start)
        if (separator < 0) return null
        val length = source.substring(start, separator).toIntOrNull() ?: return null
        if (length < 0) return null
        val valueStart = separator + 1
        val valueEnd = valueStart + length
        if (valueEnd > source.length) return null
        return Field(source.substring(valueStart, valueEnd), valueEnd)
    }
}
