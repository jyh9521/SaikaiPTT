package com.saikai.ptt.core.session

import com.saikai.ptt.core.protocol.SessionId

/**
 * What the session machine needs from the audio devices, and nothing more.
 *
 * Deliberately four methods. The session machine decides *when* a microphone is
 * open and a speaker is playing; how either is done is Tasks 22 to 26, and a
 * wider interface now would be a guess that those tasks then have to work
 * around.
 *
 * Capture starts the instant the talk button goes down, before the peer has
 * agreed to anything, and buffers locally (`docs/03_Protocol.md` section 19.4).
 * That is what makes "press and speak" true rather than "press, wait, speak":
 * the handshake is allowed up to half a second and the first syllable is already
 * on disk.
 */
interface VoiceAudio {

    /**
     * Opens the microphone and starts buffering.
     *
     * @return false when the microphone is unavailable -- denied, or taken by a
     *   phone call. An ordinary outcome on a shared device, not an error.
     */
    suspend fun startCapture(): Boolean

    /** Closes the microphone and discards or flushes whatever is buffered. */
    suspend fun stopCapture()

    /**
     * Opens the speaker for an incoming session.
     *
     * @return false when it cannot be opened -- most often because a phone call
     *   holds the audio focus. The session is then refused rather than accepted,
     *   because accepting it would mean playing nothing while telling the
     *   speaker they were heard.
     */
    suspend fun startPlayback(sessionId: SessionId): Boolean

    /** Closes the speaker. */
    suspend fun stopPlayback()
}

/**
 * The audio devices, absent.
 *
 * Lets the session machine be assembled and driven before Task22 exists, and is
 * what the state-machine tests run against: every path through the machine is
 * reachable without a microphone in the room.
 */
object NoVoiceAudio : VoiceAudio {
    override suspend fun startCapture(): Boolean = true
    override suspend fun stopCapture() = Unit
    override suspend fun startPlayback(sessionId: SessionId): Boolean = true
    override suspend fun stopPlayback() = Unit
}
