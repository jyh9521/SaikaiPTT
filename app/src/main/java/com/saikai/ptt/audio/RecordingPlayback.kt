package com.saikai.ptt.audio

import android.media.AudioAttributes
import android.media.MediaPlayer
import com.saikai.ptt.core.history.ActiveRecordings
import com.saikai.ptt.core.logger.LogCategory
import com.saikai.ptt.core.logger.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException

/**
 * Plays one recording back.
 *
 * `MediaPlayer`, not ExoPlayer: this plays a single local file of a format the
 * platform has decoded since before this app's minimum version, and ExoPlayer
 * is megabytes of library for that (`.claude/CLAUDE.md` section 32). One player
 * at a time, because one screen at a time shows one recording.
 *
 * ### Voice always wins
 *
 * A transmission arriving while a recording is playing stops the recording
 * (`Task39`: 語音優先). That is decided by watching the session state rather
 * than by audio focus: focus tells this player that *something* took the audio,
 * which is also true when a notification chimes, and losing the user's place in
 * a recording because a message arrived would be wrong. Watching the session
 * means stopping for exactly the thing that must win.
 *
 * Focus is still requested, with `USAGE_MEDIA` to match ADR-008, so that other
 * apps behave and the volume keys move the stream the user expects.
 *
 * ### The progress ticker
 *
 * `MediaPlayer` has no position callback, so a coroutine polls it. It runs only
 * while something is actually playing and stops the moment it is not --
 * `.claude/CLAUDE.md` section 13 forbids polling loops that are not needed, and
 * this is the narrowest one that can draw a progress bar.
 *
 * ### One thread
 *
 * Every method here, and [scope], must be the **main** thread. A `MediaPlayer`
 * is not thread-safe, and the alternative -- controls on the main thread and a
 * ticker on `Dispatchers.Default` -- lets the ticker ask a player about itself
 * after [stop] has released it. Five position reads a second cost the main
 * thread nothing, so the container hands this a main-dispatcher scope
 * (`app/di/AppContainer.kt`) and no lock is needed anywhere.
 *
 * [exists] is the one exception: it touches nothing but the file system and is
 * called from a view model's flow, off the main thread.
 *
 * ### Cleanup does not delete what is playing
 *
 * An open recording is claimed in [ActiveRecordings] and released by the same
 * [release] that drops the player, so there is one place to get it wrong and it
 * is the place that already has to be right.
 */
class RecordingPlayback(
    private val filesDir: File,
    private val active: ActiveRecordings,
    private val logger: Logger,
    private val scope: CoroutineScope,
) {

    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private var player: MediaPlayer? = null
    private var ticker: Job? = null

    /** The record currently loaded, so a second tap on the same row resumes. */
    private var loadedRecordId: String? = null

    /**
     * The path the loaded record's audio is at, claimed while it is open.
     *
     * `docs/05_DataModel.md` section 31: a recording being played must survive
     * a cleanup pass, and nothing but this player knows it is being played. A
     * file whose retention window passed while the user was listening to it is
     * removed on the next pass instead, which is the right answer for something
     * one button press from being finished with.
     */
    private var claimedPath: String? = null

    /**
     * Whether a file is actually there.
     *
     * Asked before the screen draws, so a recording whose file has gone --
     * deleted by hand, lost with the app's data, never written because the disk
     * was full -- disables the button and says why instead of failing on tap.
     */
    fun exists(audioPath: String?): Boolean =
        audioPath != null && File(filesDir, audioPath).isFile

    /**
     * Starts, or resumes if this is the record that is already loaded.
     *
     * @return false when there is nothing to play. The caller has usually
     *   already asked [exists]; this is the second check, for the file that
     *   disappeared between the two.
     */
    fun play(recordId: String, audioPath: String?): Boolean {
        val file = audioPath?.let { File(filesDir, it) }
        if (audioPath == null || file == null || !file.isFile) {
            logger.w(LogCategory.STORAGE) { "no recording file to play" }
            _state.value = PlaybackState(error = PlaybackError.MISSING_FILE)
            return false
        }

        if (loadedRecordId == recordId) {
            // Already claimed; a resume must not claim it a second time.
            val current = player
            if (current != null) {
                current.start()
                _state.value = _state.value.copy(playing = true, error = null)
                startTicker()
                return true
            }
        }

        release()
        return try {
            val created = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        // ADR-008: media, not voice-communication, so this comes
                        // out of the speaker and the volume keys reach it.
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                setDataSource(file.absolutePath)
                setOnCompletionListener { onCompleted() }
                setOnErrorListener { _, what, extra ->
                    logger.e(LogCategory.AUDIO) { "playback failed ($what, $extra)" }
                    onFailed()
                    true
                }
                prepare()
            }
            player = created
            loadedRecordId = recordId
            active.claim(audioPath)
            claimedPath = audioPath
            created.start()
            _state.value = PlaybackState(
                playing = true,
                positionMillis = 0,
                durationMillis = created.duration.coerceAtLeast(0),
            )
            startTicker()
            true
        } catch (error: IOException) {
            logger.e(LogCategory.AUDIO, error) { "could not open the recording" }
            release()
            _state.value = PlaybackState(error = PlaybackError.UNPLAYABLE)
            false
        } catch (error: IllegalStateException) {
            logger.e(LogCategory.AUDIO, error) { "could not open the recording" }
            release()
            _state.value = PlaybackState(error = PlaybackError.UNPLAYABLE)
            false
        }
    }

    /** Keeps the position, so the next [play] continues from here. */
    fun pause() {
        val current = player ?: return
        if (!current.isPlaying) return
        current.pause()
        stopTicker()
        _state.value = _state.value.copy(playing = false, positionMillis = position())
    }

    /** Gives up the position and the player. */
    fun stop() {
        release()
        _state.value = PlaybackState()
    }

    /**
     * A transmission started, so this stops.
     *
     * Separate from [stop] only in what it logs: the user did not do this, and
     * a recording that vanished without explanation is worth a line in the log
     * when somebody asks why.
     */
    fun yieldToVoice() {
        if (player == null) return
        logger.i(LogCategory.AUDIO) { "stopping playback: a transmission takes the speaker" }
        stop()
    }

    private fun onCompleted() {
        stopTicker()
        _state.value = _state.value.copy(
            playing = false,
            positionMillis = _state.value.durationMillis,
        )
    }

    private fun onFailed() {
        release()
        _state.value = PlaybackState(error = PlaybackError.UNPLAYABLE)
    }

    private fun startTicker() {
        stopTicker()
        ticker = scope.launch {
            // `playing()` rather than `player?.isPlaying`: the player is
            // released on this same thread, but a platform that throws from a
            // getter should end the ticker, not the scope.
            while (playing()) {
                _state.value = _state.value.copy(positionMillis = position())
                delay(TICK_MILLIS)
            }
        }
    }

    private fun stopTicker() {
        ticker?.cancel()
        ticker = null
    }

    private fun playing(): Boolean = try {
        player?.isPlaying == true
    } catch (_: IllegalStateException) {
        false
    }

    private fun position(): Int = try {
        player?.currentPosition ?: 0
    } catch (_: IllegalStateException) {
        0
    }

    private fun release() {
        stopTicker()
        player?.let { current ->
            try {
                current.stop()
            } catch (_: IllegalStateException) {
                // Never started, or already stopped. Releasing is what matters.
            }
            current.release()
        }
        player = null
        loadedRecordId = null
        claimedPath?.let { active.release(it) }
        claimedPath = null
    }

    private companion object {
        /** Five updates a second: enough for a bar to look continuous, cheap enough to ignore. */
        const val TICK_MILLIS = 200L
    }
}

/** What the player is doing, as the screen needs to draw it. */
data class PlaybackState(
    val playing: Boolean = false,
    val positionMillis: Int = 0,
    val durationMillis: Int = 0,
    val error: PlaybackError? = null,
) {
    /** True once something has been loaded, so the screen shows a scrubber at all. */
    val active: Boolean get() = playing || positionMillis > 0
}

enum class PlaybackError {
    /** The row points at a file that is not there, or has no path at all. */
    MISSING_FILE,

    /** The file is there and the platform will not play it. */
    UNPLAYABLE,
}
