package com.saikai.ptt.core.common

/**
 * A result that either succeeded or failed with a typed reason.
 *
 * `.claude/CLAUDE.md` section 25 requires structured errors and forbids driving
 * application logic off exception message strings. Exceptions are for things
 * that should not happen; a name that is too long, a peer that is busy or a
 * malformed packet are all expected outcomes on a LAN full of other people's
 * devices, and the caller has to handle each one differently.
 *
 * A typed error also lets the UI localise. `04_UI_UX.md` section 38 requires
 * short, clear, actionable messages in five languages, which is only possible
 * if the failure arrives as a value the UI can match on rather than a sentence
 * someone already wrote in English.
 */
sealed interface Outcome<out T, out E> {

    data class Success<out T>(val value: T) : Outcome<T, Nothing>

    data class Failure<out E>(val error: E) : Outcome<Nothing, E>

    val isSuccess: Boolean get() = this is Success

    fun valueOrNull(): T? = (this as? Success)?.value

    fun errorOrNull(): E? = (this as? Failure)?.error

    /** Collapses both branches into one value. */
    fun <R> fold(onSuccess: (T) -> R, onFailure: (E) -> R): R = when (this) {
        is Success -> onSuccess(value)
        is Failure -> onFailure(error)
    }

    /** Transforms the success value, leaving a failure untouched. */
    fun <R> map(transform: (T) -> R): Outcome<R, E> = when (this) {
        is Success -> Success(transform(value))
        is Failure -> this
    }

    companion object {
        fun <T> success(value: T): Outcome<T, Nothing> = Success(value)
        fun <E> failure(error: E): Outcome<Nothing, E> = Failure(error)
    }
}
