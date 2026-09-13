package com.saikai.ptt.core.domain

import com.saikai.ptt.core.common.Outcome

/**
 * The speaker, as the rest of the app needs it.
 *
 * Frames go in as 16 kHz mono 16-bit PCM, 20 ms at a time, in the order they
 * should be heard. Reordering, gap filling and how deep to buffer are the jitter
 * buffer's job, not this one's: by the time a frame reaches here it is the next
 * thing the user should hear.
 */
interface AudioPlayer {

    /** True between a successful [start] and [stop]. */
    val isPlaying: Boolean

    /** Opens the output device. */
    suspend fun start(): Outcome<Unit, AudioError>

    /**
     * Queues one frame, without waiting.
     *
     * Never blocks. The caller is on the path between the network and the
     * speaker, and a write that waited for room would turn one late frame into
     * every later frame being late as well.
     *
     * @return the bytes accepted, which is less than [length] when the device is
     *   already full. The remainder is dropped on purpose: in a live
     *   conversation the newest audio is the only audio worth having, and the
     *   alternative is a delay that grows for the rest of the transmission.
     */
    fun write(pcm: ByteArray, offset: Int, length: Int): Int

    /**
     * Stops now, discarding whatever is still queued. Safe when not started.
     *
     * The right answer whenever the session did not end of its own accord: a
     * force interrupt, a network loss, a phone call taking the audio. In all
     * of those the queued audio belongs to a transmission that has been cut
     * off, and playing it out would delay what comes next in order to finish
     * something the listener has already been told is over.
     */
    suspend fun stop()

    /**
     * Plays out what is already queued, then stops.
     *
     * For the one ending where the tail is wanted: the speaker reached the end
     * of a sentence and said so. Between the jitter buffer's cushion and the
     * device's own there is always a fraction of a second in flight when a
     * transmission ends, and discarding it clips the last word off every
     * single one.
     *
     * Bounded by how much the device could be holding, so it cannot become an
     * unbounded wait inside a service that is shutting down.
     */
    suspend fun drainAndStop()
}

/**
 * What the platform said about audio focus.
 *
 * Modelled here rather than passing platform constants around, so that the
 * policy below is a decision this project made and can test, not a `when` over
 * integers buried in a listener.
 */
enum class AudioFocusChange {
    /** Focus is back. */
    GAINED,

    /** Lost, indefinitely. Something else owns audio now. */
    LOST,

    /** Lost briefly -- a notification, a navigation prompt. */
    LOST_TRANSIENT,

    /** Lost briefly, with permission to keep playing quietly instead. */
    LOST_TRANSIENT_CAN_DUCK,
}

/**
 * What losing audio focus does to a PTT session (`docs/ADR/ADR-004` section 5).
 *
 * Every kind of loss ends the session, immediately, including the two that
 * normally mean "pause" or "turn down". That is a deliberate departure from how
 * a media app behaves, for two reasons:
 *
 * - **There is nothing to resume.** Push-to-talk is live. Audio held back during
 *   a phone call and played afterwards is not a delayed message, it is a
 *   confusing one, and the speaker has long since stopped waiting for an answer.
 * - **Ducking loses the words.** The product exists to be understood in a noisy
 *   warehouse. Half-volume speech there is not quieter speech, it is no speech,
 *   so CAN_DUCK is treated exactly like a transient loss.
 */
object AudioFocusPolicy {

    /** Whether this change must end the current session. */
    fun endsSession(change: AudioFocusChange): Boolean = when (change) {
        AudioFocusChange.GAINED -> false
        AudioFocusChange.LOST,
        AudioFocusChange.LOST_TRANSIENT,
        AudioFocusChange.LOST_TRANSIENT_CAN_DUCK,
        -> true
    }

    /** Whether playback should continue at a lower volume instead of stopping. */
    fun shouldDuck(change: AudioFocusChange): Boolean = false
}

/**
 * Audio focus, held for exactly as long as a session lasts.
 *
 * Requested when a session starts in either direction and released the moment it
 * ends, never held between them: a walkie-talkie that keeps the audio focus
 * while idle is a walkie-talkie that silences music all day.
 */
interface AudioFocus {

    /**
     * Asks for transient focus.
     *
     * @param onChange called when the platform takes focus back. Runs on a
     *   platform callback thread, so it must not block.
     * @return false when focus was refused. The session should not start.
     */
    suspend fun request(onChange: (AudioFocusChange) -> Unit): Boolean

    /** Gives focus back. Safe to call when it was never held. */
    suspend fun release()
}
