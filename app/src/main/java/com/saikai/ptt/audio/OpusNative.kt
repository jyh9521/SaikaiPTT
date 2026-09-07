package com.saikai.ptt.audio

/**
 * The raw JNI surface of `libsaikaiopus.so`.
 *
 * Every method mirrors one libopus call and nothing else. No policy, no
 * defaults, no state beyond the two handles the caller holds -- all of that
 * lives in [OpusVoiceCodec], where it can be read next to the config it comes
 * from instead of being spread across a language boundary.
 *
 * Loading is attempted once and its failure is recorded rather than thrown. A
 * device that will not load the library must degrade to "cannot talk" with a
 * typed error, not crash the process on the first class access -- and until
 * ADR-007's alignment work is confirmed on every ABI, "will not load" is a
 * condition worth handling rather than assuming away.
 */
internal object OpusNative {

    /** Opus application mode. VOIP, per `docs/ADR/ADR-004` section 1. */
    const val APPLICATION_VOIP: Int = 2048

    /** libopus success. Every other return is an Opus error code, and negative. */
    const val OPUS_OK: Int = 0

    val isAvailable: Boolean = try {
        System.loadLibrary("saikaiopus")
        true
    } catch (_: UnsatisfiedLinkError) {
        false
    } catch (_: SecurityException) {
        false
    }

    /** @return an encoder handle, or 0 when one could not be created. */
    @JvmStatic
    external fun createEncoder(sampleRate: Int, channels: Int, application: Int): Long

    /** @return [OPUS_OK], or the first setting that was refused. */
    @JvmStatic
    external fun configureEncoder(
        handle: Long,
        bitrate: Int,
        complexity: Int,
        useVariableBitrate: Boolean,
        forwardErrorCorrection: Boolean,
        discontinuous: Boolean,
        expectedPacketLossPercent: Int,
    ): Int

    /** @return bytes written, or a negative Opus error code. */
    @JvmStatic
    external fun encode(
        handle: Long,
        pcm: ShortArray,
        pcmOffset: Int,
        frameSamples: Int,
        out: ByteArray,
        outOffset: Int,
        outCapacity: Int,
    ): Int

    @JvmStatic
    external fun destroyEncoder(handle: Long)

    /** @return a decoder handle, or 0 when one could not be created. */
    @JvmStatic
    external fun createDecoder(sampleRate: Int, channels: Int): Long

    /**
     * @param encodedLength zero asks for packet-loss concealment instead of a
     *   decode: Opus produces a frame of plausible audio rather than a hole.
     * @return samples written, or a negative Opus error code.
     */
    @JvmStatic
    external fun decode(
        handle: Long,
        encoded: ByteArray,
        encodedOffset: Int,
        encodedLength: Int,
        pcm: ShortArray,
        pcmOffset: Int,
        frameSamples: Int,
        useForwardErrorCorrection: Boolean,
    ): Int

    @JvmStatic
    external fun destroyDecoder(handle: Long)
}
