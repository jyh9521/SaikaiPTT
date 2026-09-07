package com.saikai.ptt.audio

import com.saikai.ptt.core.common.Outcome
import com.saikai.ptt.core.domain.AudioFocus
import com.saikai.ptt.core.domain.AudioFocusChange
import com.saikai.ptt.core.domain.AudioFocusPolicy
import com.saikai.ptt.core.domain.AudioFrameSink
import com.saikai.ptt.core.domain.AudioPlayer
import com.saikai.ptt.core.domain.AudioRecorder
import com.saikai.ptt.core.logger.LogCategory
import com.saikai.ptt.core.logger.Logger
import com.saikai.ptt.core.protocol.SessionId
import com.saikai.ptt.core.session.VoiceAudio

/**
 * The audio devices, as one session-shaped object.
 *
 * The session machine says when a microphone and a speaker should be open; this
 * says how, and holds the audio focus around both. Keeping focus here rather
 * than in the machine means the rule "focus lasts exactly as long as a session"
 * is enforced by the same object that opens the devices, instead of by two
 * places agreeing.
 *
 * Focus is taken before the device is opened and given back if the device will
 * not open, so a refused microphone never leaves this app holding the focus --
 * which would silence the user's music with nothing to show for it.
 *
 * @param onFocusLost called when the platform takes focus back and
 *   [AudioFocusPolicy] says the session must end. Wired to the session machine's
 *   own teardown, so a phone call ends the transmission properly -- VOICE_END
 *   sent, recording finalised -- instead of the audio simply stopping.
 */
class AndroidVoiceAudio(
    private val recorder: AudioRecorder,
    private val player: AudioPlayer,
    private val focus: AudioFocus,
    private val logger: Logger,
    private val frames: AudioFrameSink,
    private val onFocusLost: (AudioFocusChange) -> Unit,
) : VoiceAudio {

    override suspend fun startCapture(): Boolean {
        if (!focus.request(::onFocusChange)) {
            logger.w(LogCategory.AUDIO) { "no audio focus; not opening the microphone" }
            return false
        }
        return when (val outcome = recorder.start(frames)) {
            is Outcome.Success -> true
            is Outcome.Failure -> {
                logger.w(LogCategory.AUDIO) { "capture refused: ${outcome.error}" }
                focus.release()
                false
            }
        }
    }

    override suspend fun stopCapture() {
        recorder.stop()
        focus.release()
    }

    override suspend fun startPlayback(sessionId: SessionId): Boolean {
        if (!focus.request(::onFocusChange)) {
            logger.w(LogCategory.AUDIO) { "no audio focus; not opening the speaker" }
            return false
        }
        return when (val outcome = player.start()) {
            is Outcome.Success -> true
            is Outcome.Failure -> {
                logger.w(LogCategory.AUDIO) { "playback refused: ${outcome.error}" }
                focus.release()
                false
            }
        }
    }

    override suspend fun stopPlayback() {
        player.stop()
        focus.release()
    }

    /**
     * Runs on a platform callback thread, so it does no work of its own.
     *
     * Every kind of loss ends the session, transient and duckable included
     * (`docs/ADR/ADR-004` section 5). There is nothing to resume: audio held
     * back during a phone call and played afterwards is not a delayed message,
     * it is a confusing one.
     */
    private fun onFocusChange(change: AudioFocusChange) {
        if (!AudioFocusPolicy.endsSession(change)) return
        logger.i(LogCategory.AUDIO) { "audio focus lost ($change); ending the session" }
        onFocusLost(change)
    }
}
