package com.saikai.ptt.audio

import com.saikai.ptt.core.common.Outcome
import com.saikai.ptt.core.config.SaikaiConfig
import com.saikai.ptt.core.domain.AudioError
import com.saikai.ptt.core.domain.VoiceCodec
import com.saikai.ptt.core.logger.LogCategory
import com.saikai.ptt.core.logger.Logger
import com.saikai.ptt.core.protocol.WireFormat

/**
 * Opus, configured from `docs/ADR/ADR-004` and built per `docs/ADR/ADR-007`.
 *
 * One instance is one stream in one direction. Opus carries state between
 * frames -- that is what makes 20 kbps intelligible -- so an instance may not be
 * shared, and [release] frees memory that is not on the JVM heap and will not be
 * collected for us.
 *
 * The measured output at these settings is exactly 50 bytes a frame, constant,
 * because the bitrate is CBR. That is eight times inside the protocol's
 * 400-byte VOICE_DATA limit, and [maxEncodedBytes] reports the protocol's limit
 * rather than the measurement: the encoder is told never to exceed what the wire
 * will carry, so a future bitrate change produces smaller frames rather than
 * frames every receiver silently drops.
 */
class OpusVoiceCodec private constructor(
    private val config: SaikaiConfig,
    private val logger: Logger,
    private var encoder: Long,
    private var decoder: Long,
) : VoiceCodec {

    override val frameSizeSamples: Int get() = config.audio.frameSizeSamples

    override val maxEncodedBytes: Int get() = WireFormat.MAX_VOICE_PAYLOAD_BYTES

    override fun encode(pcm: ShortArray, out: ByteArray): Int {
        val handle = encoder
        if (handle == 0L) return -1
        return OpusNative.encode(
            handle = handle,
            pcm = pcm,
            pcmOffset = 0,
            frameSamples = frameSizeSamples,
            out = out,
            outOffset = 0,
            outCapacity = minOf(out.size, maxEncodedBytes),
        )
    }

    override fun decode(encoded: ByteArray, offset: Int, length: Int, out: ShortArray): Int {
        val handle = decoder
        if (handle == 0L) return -1
        return OpusNative.decode(
            handle = handle,
            encoded = encoded,
            encodedOffset = offset,
            encodedLength = length,
            pcm = out,
            pcmOffset = 0,
            frameSamples = frameSizeSamples,
            useForwardErrorCorrection = false,
        )
    }

    /**
     * Recovers the frame that was lost immediately before [encoded].
     *
     * The redundant copy travels inside the *next* packet, so this is called
     * with the packet that arrived after the gap, not with the gap. Returns zero
     * when the encoder on the far side put no redundancy there -- which is what
     * happens when it was configured with an expected loss of zero, and the
     * reason [com.saikai.ptt.core.config.AudioConfig] refuses that combination.
     */
    override fun decodeLost(encoded: ByteArray, offset: Int, length: Int, out: ShortArray): Int {
        val handle = decoder
        if (handle == 0L) return 0
        val produced = OpusNative.decode(
            handle = handle,
            encoded = encoded,
            encodedOffset = offset,
            encodedLength = length,
            pcm = out,
            pcmOffset = 0,
            frameSamples = frameSizeSamples,
            useForwardErrorCorrection = true,
        )
        return if (produced > 0) produced else 0
    }

    /**
     * Produces a frame of plausible audio for a gap, without any packet.
     *
     * Opus's own packet-loss concealment, which is strictly better than the
     * silence ADR-004 section 4 settles for and costs the same call. Used when
     * [decodeLost] found no redundancy.
     */
    override fun conceal(out: ShortArray): Int {
        val handle = decoder
        if (handle == 0L) return 0
        val produced = OpusNative.decode(
            handle = handle,
            encoded = EMPTY,
            encodedOffset = 0,
            encodedLength = 0,
            pcm = out,
            pcmOffset = 0,
            frameSamples = frameSizeSamples,
            useForwardErrorCorrection = false,
        )
        return if (produced > 0) produced else 0
    }

    override fun release() {
        val enc = encoder
        val dec = decoder
        encoder = 0
        decoder = 0
        if (enc != 0L) OpusNative.destroyEncoder(enc)
        if (dec != 0L) OpusNative.destroyDecoder(dec)
    }

    companion object {
        private val EMPTY = ByteArray(0)

        /**
         * Creates an encoder and a decoder configured from [config].
         *
         * Both, not one each way: a session is half duplex but the same device
         * both speaks and listens, and a codec pair costs about 30 KB of native
         * memory. Two objects to keep track of would buy nothing.
         */
        fun create(config: SaikaiConfig, logger: Logger): Outcome<OpusVoiceCodec, AudioError> {
            if (!OpusNative.isAvailable) {
                logger.e(LogCategory.AUDIO) { "libsaikaiopus.so did not load" }
                return Outcome.failure(AudioError.UNSUPPORTED_FORMAT)
            }

            val audio = config.audio
            val encoder = OpusNative.createEncoder(
                sampleRate = audio.sampleRateHz,
                channels = audio.channelCount,
                application = OpusNative.APPLICATION_VOIP,
            )
            if (encoder == 0L) {
                logger.e(LogCategory.AUDIO) { "opus_encoder_create refused the configuration" }
                return Outcome.failure(AudioError.UNSUPPORTED_FORMAT)
            }

            val configured = OpusNative.configureEncoder(
                handle = encoder,
                bitrate = audio.opusBitrateBps,
                complexity = audio.opusComplexity,
                // Constant bitrate: a walkie-talkie's packet size should not
                // vary with what is being said, because the jitter buffer and
                // the 400-byte cap are both easier to reason about when it does
                // not (ADR-004 section 1).
                useVariableBitrate = false,
                forwardErrorCorrection = audio.opusForwardErrorCorrection,
                discontinuous = audio.opusDiscontinuousTransmission,
                expectedPacketLossPercent = audio.opusExpectedPacketLossPercent,
            )
            if (configured != OpusNative.OPUS_OK) {
                logger.e(LogCategory.AUDIO) { "opus_encoder_ctl failed with $configured" }
                OpusNative.destroyEncoder(encoder)
                return Outcome.failure(AudioError.UNSUPPORTED_FORMAT)
            }

            val decoder = OpusNative.createDecoder(audio.sampleRateHz, audio.channelCount)
            if (decoder == 0L) {
                logger.e(LogCategory.AUDIO) { "opus_decoder_create refused the configuration" }
                OpusNative.destroyEncoder(encoder)
                return Outcome.failure(AudioError.UNSUPPORTED_FORMAT)
            }

            logger.i(LogCategory.AUDIO) {
                "opus ready: ${audio.sampleRateHz}Hz mono, ${audio.opusBitrateBps}bps CBR, " +
                    "complexity ${audio.opusComplexity}, fec=${audio.opusForwardErrorCorrection} " +
                    "at ${audio.opusExpectedPacketLossPercent}% expected loss"
            }
            return Outcome.success(OpusVoiceCodec(config, logger, encoder, decoder))
        }
    }
}
