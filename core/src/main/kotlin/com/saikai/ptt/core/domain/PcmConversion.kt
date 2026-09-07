package com.saikai.ptt.core.domain

/**
 * Between the bytes the microphone gives and the samples a codec wants.
 *
 * `AudioRecord` fills a `ByteArray`; every voice codec worth using takes a
 * `ShortArray`. Something has to convert, fifty times a second, and it is worth
 * having in one tested place because the failure mode is silent: a byte order
 * mistake does not throw, it produces audio that is loud, wrong and vaguely
 * rhythmic, and it sounds the same on both devices if both are wrong.
 *
 * Little-endian, which is what `ENCODING_PCM_16BIT` means on every Android
 * device, and both directions take caller-owned buffers so the conversion
 * allocates nothing.
 */
object PcmConversion {

    /** Bytes per 16-bit sample. */
    const val BYTES_PER_SAMPLE: Int = 2

    /**
     * Reads [length] bytes as little-endian 16-bit samples.
     *
     * @return samples written.
     */
    fun bytesToShorts(
        source: ByteArray,
        sourceOffset: Int,
        length: Int,
        target: ShortArray,
        targetOffset: Int,
    ): Int {
        val samples = length / BYTES_PER_SAMPLE
        require(sourceOffset >= 0 && length >= 0 && sourceOffset + length <= source.size) {
            "Byte range is outside a ${source.size}-byte buffer"
        }
        require(targetOffset >= 0 && targetOffset + samples <= target.size) {
            "Sample range is outside a ${target.size}-sample buffer"
        }

        for (index in 0 until samples) {
            val low = source[sourceOffset + index * 2].toInt() and 0xFF
            val high = source[sourceOffset + index * 2 + 1].toInt()
            target[targetOffset + index] = ((high shl 8) or low).toShort()
        }
        return samples
    }

    /**
     * Writes [samples] samples as little-endian 16-bit bytes.
     *
     * @return bytes written.
     */
    fun shortsToBytes(
        source: ShortArray,
        sourceOffset: Int,
        samples: Int,
        target: ByteArray,
        targetOffset: Int,
    ): Int {
        val bytes = samples * BYTES_PER_SAMPLE
        require(sourceOffset >= 0 && samples >= 0 && sourceOffset + samples <= source.size) {
            "Sample range is outside a ${source.size}-sample buffer"
        }
        require(targetOffset >= 0 && targetOffset + bytes <= target.size) {
            "Byte range is outside a ${target.size}-byte buffer"
        }

        for (index in 0 until samples) {
            val value = source[sourceOffset + index].toInt()
            target[targetOffset + index * 2] = (value and 0xFF).toByte()
            target[targetOffset + index * 2 + 1] = ((value shr 8) and 0xFF).toByte()
        }
        return bytes
    }
}
