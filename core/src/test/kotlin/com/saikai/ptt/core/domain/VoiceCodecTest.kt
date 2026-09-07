package com.saikai.ptt.core.domain

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The codec contract, exercised through the implementation that does nothing.
 *
 * The passthrough exists so the pipeline above it can be tested for delivering
 * *the same audio*, which a real codec makes impossible -- Opus at 20 kbps
 * returns something that sounds right, not something that compares equal.
 */
class VoiceCodecTest {

    private val codec = PassthroughVoiceCodec(frameSizeSamples = 320)

    private fun ramp(size: Int) = ShortArray(size) { (it * 97 - 16_000).toShort() }

    @Test
    fun `a frame survives encoding and decoding unchanged`() {
        val pcm = ramp(codec.frameSizeSamples)
        val encoded = ByteArray(codec.maxEncodedBytes)
        val decoded = ShortArray(codec.frameSizeSamples)

        val bytes = codec.encode(pcm, encoded)
        assertEquals(codec.maxEncodedBytes, bytes)

        val samples = codec.decode(encoded, 0, bytes, decoded)
        assertEquals(codec.frameSizeSamples, samples)
        assertArrayEquals(pcm, decoded)
    }

    @Test
    fun `the buffers the caller supplies are the buffers used`() {
        // Nothing is allocated per frame; encoding twice into one buffer must
        // simply overwrite it.
        val encoded = ByteArray(codec.maxEncodedBytes)
        codec.encode(ramp(codec.frameSizeSamples), encoded)
        val first = encoded.copyOf()

        codec.encode(ShortArray(codec.frameSizeSamples), encoded)

        assertTrue(first.any { it != 0.toByte() })
        assertTrue(encoded.all { it == 0.toByte() })
    }

    @Test
    fun `a buffer that is too small fails rather than writing past it`() {
        assertTrue(codec.encode(ramp(codec.frameSizeSamples), ByteArray(10)) < 0)
        assertTrue(codec.encode(ramp(10), ByteArray(codec.maxEncodedBytes)) < 0)
        assertTrue(codec.decode(ByteArray(640), 0, 640, ShortArray(10)) < 0)
    }

    @Test
    fun `a released codec refuses to work rather than crashing`() {
        // Release frees state that usually lives outside the JVM heap; a use
        // after it must be a return value, not a native crash.
        codec.release()
        codec.release()

        assertTrue(codec.encode(ramp(codec.frameSizeSamples), ByteArray(640)) < 0)
        assertTrue(codec.decode(ByteArray(640), 0, 640, ShortArray(320)) < 0)
    }

    @Test
    fun `recovering a lost frame is optional and reports that it did nothing`() {
        assertEquals(0, codec.decodeLost(ByteArray(640), 0, 640, ShortArray(320)))
    }

    @Test
    fun `the passthrough does not fit the wire, and that is the point`() {
        // ADR-003 caps a VOICE_DATA payload at 400 bytes. Uncompressed 20 ms
        // frames are 640, which is exactly the mistake that limit exists to
        // catch -- better caught here than by every receiver on the network.
        assertTrue(PassthroughVoiceCodec(320).maxEncodedBytes > 400)
        assertTrue(PassthroughVoiceCodec(160).maxEncodedBytes <= 400)
    }
}
