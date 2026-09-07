package com.saikai.ptt.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import com.saikai.ptt.core.domain.AudioFocus
import com.saikai.ptt.core.domain.AudioFocusChange
import com.saikai.ptt.core.logger.LogCategory
import com.saikai.ptt.core.logger.Logger

/**
 * Audio focus, on Android.
 *
 * Transient focus (`docs/ADR/ADR-004` section 5), taken when a session starts
 * and given back the moment it ends. Never held while idle: a walkie-talkie that
 * keeps focus between transmissions is one that silences the user's music all
 * day for no reason.
 *
 * `setWillPauseWhenDucked(true)` is set on purpose. It tells the platform not to
 * duck this app automatically but to send the loss instead, which is what lets
 * [AudioFocusPolicy][com.saikai.ptt.core.domain.AudioFocusPolicy] apply the
 * decision the ADR actually made: half-volume speech in a warehouse is not
 * quieter speech, it is no speech.
 */
class AndroidAudioFocus(
    context: Context,
    private val logger: Logger,
) : AudioFocus {

    private val audioManager: AudioManager? =
        context.applicationContext.getSystemService(AudioManager::class.java)

    private val lock = Any()
    private var held: AudioFocusRequest? = null

    override suspend fun request(onChange: (AudioFocusChange) -> Unit): Boolean {
        val manager = audioManager ?: run {
            logger.w(LogCategory.AUDIO) { "no AudioManager; continuing without focus" }
            return true
        }
        synchronized(lock) { if (held != null) return true }

        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attributes)
            .setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener { change -> onChange(change.toDomain()) }
            .build()

        val granted = manager.requestAudioFocus(request) ==
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (granted) {
            synchronized(lock) { held = request }
        } else {
            logger.w(LogCategory.AUDIO) { "audio focus refused" }
        }
        return granted
    }

    override suspend fun release() {
        val manager = audioManager ?: return
        val request = synchronized(lock) { held.also { held = null } } ?: return
        manager.abandonAudioFocusRequest(request)
    }

    private fun Int.toDomain(): AudioFocusChange = when (this) {
        AudioManager.AUDIOFOCUS_GAIN -> AudioFocusChange.GAINED
        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> AudioFocusChange.LOST_TRANSIENT
        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK ->
            AudioFocusChange.LOST_TRANSIENT_CAN_DUCK

        // Anything else, including AUDIOFOCUS_LOSS and values a future platform
        // adds, is treated as a full loss. Guessing benignly about audio focus
        // is how an app ends up talking over a phone call.
        else -> AudioFocusChange.LOST
    }
}
