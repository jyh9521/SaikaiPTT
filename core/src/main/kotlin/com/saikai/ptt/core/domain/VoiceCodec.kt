package com.saikai.ptt.core.domain

/**
 * Turns one frame of PCM into one packet, and back.
 *
 * Nothing above this knows what codec is in use. That matters more than it
 * usually would: `docs/ADR/ADR-004` records that Android does not guarantee an
 * Opus *encoder*, so the implementation is a native library, and the ADR already
 * names a fallback that would change the format outright. An interface with no
 * codec-specific types in it is what makes that fallback a swap rather than a
 * rewrite.
 *
 * **The caller owns the buffers.** Both directions run fifty times a second for
 * the length of every transmission, and returning a fresh array each time would
 * be the largest allocation source in the app. The pipeline allocates one input
 * and one output buffer per session and passes the same pair every frame.
 *
 * One instance is one stream, in one direction, for one session. Codec state is
 * carried between frames -- that is what makes 20 kbps intelligible -- so an
 * instance may not be shared between two sessions, and [release] must be called
 * when the session ends because the state usually lives outside the JVM heap.
 */
interface VoiceCodec {

    /** Samples per frame. 320 at 16 kHz and 20 ms (`docs/ADR/ADR-004` section 1). */
    val frameSizeSamples: Int

    /**
     * The largest packet [encode] can produce.
     *
     * The caller sizes its output buffer from this once, at start-up. It must
     * stay within the protocol's VOICE_DATA limit of 400 bytes
     * (`docs/ADR/ADR-003` section 1) or the frames will encode locally and be
     * dropped by every receiver.
     */
    val maxEncodedBytes: Int

    /**
     * Encodes [frameSizeSamples] samples from [pcm] into [out].
     *
     * @return bytes written, or a negative value if the frame could not be
     *   encoded. Never throws: a codec failure mid-transmission should cost one
     *   frame, not the call.
     */
    fun encode(pcm: ShortArray, out: ByteArray): Int

    /**
     * Decodes [length] bytes from [encoded] into [out].
     *
     * @return samples written, or a negative value on failure.
     */
    fun decode(encoded: ByteArray, offset: Int, length: Int, out: ShortArray): Int

    /**
     * Reconstructs the frame that was lost immediately before [encoded].
     *
     * Only meaningful for a codec carrying forward error correction, which
     * ADR-004 section 1 switches on. Worth stating in the interface because
     * enabling FEC in the encoder does nothing on its own: the redundant copy
     * travels inside the *next* packet and is only recovered if the decoder is
     * asked for it. A codec that cannot do this returns zero and the caller
     * inserts silence, which is the v1 behaviour either way.
     *
     * @return samples written, or 0 when nothing could be recovered.
     */
    fun decodeLost(encoded: ByteArray, offset: Int, length: Int, out: ShortArray): Int = 0

    /**
     * Invents a frame to cover a gap, with nothing to work from.
     *
     * The other half of what a lost frame needs. [decodeLost] recovers the real
     * audio when the packet after it arrived and carried the redundant copy;
     * this is what is left when even that is gone, and a codec that models
     * speech can extrapolate the last frame far better than silence can -- at
     * the same cost, since the decoder has to advance its state over the gap
     * either way.
     *
     * `docs/ADR/ADR-007` left the choice between this, [decodeLost] and silence
     * to the jitter buffer, which is the only thing that knows which frames it
     * still holds. All three are now used, in that order of preference.
     *
     * @return samples written, or 0 when the codec cannot conceal, in which
     *   case the caller inserts silence.
     */
    fun conceal(out: ShortArray): Int = 0

    /** Frees whatever the codec holds. Safe to call twice. */
    fun release()
}

/**
 * A codec that does nothing, so that everything above it can be tested.
 *
 * Samples go out as little-endian 16-bit and come back identical, which makes it
 * useful for exactly the thing a real codec makes hard: asserting that a
 * pipeline delivered the audio it was given, rather than something that merely
 * sounds similar.
 *
 * **Its frames are twice the size of a real one.** At the product's 320 samples
 * it produces 640 bytes, which is over the protocol's 400-byte VOICE_DATA limit,
 * so a test that builds real datagrams must construct it with a smaller frame --
 * 160 samples fits. That is not a flaw to work around: a codec whose output does
 * not fit the wire is precisely what ADR-003's limit is there to catch, and it
 * is better caught by a test than by a receiver.
 */
class PassthroughVoiceCodec(
    override val frameSizeSamples: Int = 320,
) : VoiceCodec {

    init {
        require(frameSizeSamples > 0) { "A frame is at least one sample" }
    }

    override val maxEncodedBytes: Int get() = frameSizeSamples * Short.SIZE_BYTES

    private var released = false

    override fun encode(pcm: ShortArray, out: ByteArray): Int {
        if (released) return -1
        if (pcm.size < frameSizeSamples || out.size < maxEncodedBytes) return -1
        PcmConversion.shortsToBytes(pcm, 0, frameSizeSamples, out, 0)
        return maxEncodedBytes
    }

    override fun decode(encoded: ByteArray, offset: Int, length: Int, out: ShortArray): Int {
        if (released) return -1
        if (length % Short.SIZE_BYTES != 0) return -1
        val samples = length / Short.SIZE_BYTES
        if (offset < 0 || offset + length > encoded.size || out.size < samples) return -1
        PcmConversion.bytesToShorts(encoded, offset, length, out, 0)
        return samples
    }

    override fun release() {
        released = true
    }
}
