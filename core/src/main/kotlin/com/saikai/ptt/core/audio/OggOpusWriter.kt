package com.saikai.ptt.core.audio

import java.io.OutputStream

/**
 * Wraps already-encoded Opus frames in an Ogg container.
 *
 * ADR-004 section 6 forbids re-encoding: the frames the encoder produced for
 * the network are the frames that go in the file, and on the receiving side the
 * frames that arrived are written as they arrived. This class is the whole cost
 * of that decision -- it adds a container around bytes it never looks inside.
 *
 * ### Why this is written by hand
 *
 * The platform can do it: `MediaMuxer` has supported Ogg output since API 29
 * and this app's minSdk is 30. It is not used, for two reasons. The first is
 * that muxing Opus through `MediaMuxer` means handing it codec-specific data it
 * then re-derives, on ROMs that vary; the second is that a container written
 * here can be proved correct on the JVM, byte for byte, in milliseconds --
 * which is the same argument `docs/02_Architecture.md` section 5.1 makes for
 * everything else in `:core`. Ogg framing is a page header, a segment table and
 * a CRC. It is about two hundred lines and it does not change.
 *
 * ### The shape of the file
 *
 * Two header pages then audio pages, as RFC 7845 requires:
 *
 * 1. `OpusHead`, alone on the first page, which carries the beginning-of-stream
 *    flag.
 * 2. `OpusTags`, alone on the second.
 * 3. Audio, batched so the per-page overhead is paid once per second rather
 *    than once per 20 ms frame. One frame per page would spend 28 bytes of
 *    header on roughly 50 bytes of audio.
 *
 * Nothing here seeks, so a file that stops being written -- the process was
 * killed, the disk filled -- is a valid file that is simply shorter. That
 * property is the reason recordings are written straight through rather than
 * assembled at the end.
 *
 * @param out where pages go. Not closed by this class; the caller owns it.
 * @param sampleRateHz the rate the frames were encoded at, recorded in the
 *   header for information. Granule positions are always in 48 kHz units
 *   regardless of it (RFC 7845 section 4).
 * @param frameSizeSamples samples per frame at [sampleRateHz].
 * @param serialNumber the stream's identity within the file. One logical
 *   stream per file here, so any value will do, but it must not be zero for
 *   some readers.
 */
class OggOpusWriter(
    private val out: OutputStream,
    private val sampleRateHz: Int,
    private val frameSizeSamples: Int,
    private val serialNumber: Int,
    private val channels: Int = 1,
) {

    private var pageSequence = 0
    private var granulePosition = 0L
    private var framesWritten = 0L
    private var finished = false

    /** Packets waiting to go out together. */
    private val pending = ArrayList<ByteArray>()
    private var pendingSegments = 0

    /** 48 kHz samples one frame decodes to, which is what a granule counts. */
    private val samplesPerFrameAt48k: Int =
        (frameSizeSamples.toLong() * GRANULE_RATE_HZ / sampleRateHz).toInt()

    init {
        require(sampleRateHz > 0) { "sampleRateHz must be positive" }
        require(frameSizeSamples > 0) { "frameSizeSamples must be positive" }
        require(channels in 1..2) { "channels must be 1 or 2" }
        writeHeaderPages()
    }

    /** How many frames have been handed over, for the duration the record stores. */
    val frameCount: Long get() = framesWritten

    /**
     * Adds one encoded frame.
     *
     * The bytes are copied: the caller's buffer is the codec's and is reused
     * before this page is written.
     */
    fun write(frame: ByteArray, offset: Int, length: Int) {
        check(!finished) { "the stream is already finished" }
        require(offset >= 0 && length > 0 && offset + length <= frame.size) {
            "frame range $offset..${offset + length} is outside a ${frame.size}-byte array"
        }

        val segments = segmentsFor(length)
        // A page's segment table is one byte long, so 255 segments is the hard
        // limit; the frame cap is the soft one that decides how often the
        // 28-byte header is paid.
        if (pendingSegments + segments > MAX_SEGMENTS || pending.size >= FRAMES_PER_PAGE) {
            flushAudioPage(last = false)
        }

        pending += frame.copyOfRange(offset, offset + length)
        pendingSegments += segments
        framesWritten++
        granulePosition += samplesPerFrameAt48k
    }

    /**
     * Writes what is left and marks the end of the stream.
     *
     * Idempotent, because the failure paths that call it can overlap: a session
     * that ends while the disk is filling can reach this twice.
     */
    fun finish() {
        if (finished) return
        // Always emits a final page, even with nothing pending: a stream with
        // no end-of-stream flag is a truncated stream to a strict reader, and
        // an empty recording is still a file somebody may open.
        flushAudioPage(last = true)
        finished = true
        out.flush()
    }

    private fun writeHeaderPages() {
        writePage(listOf(opusHead()), granule = 0L, beginning = true, last = false)
        writePage(listOf(opusTags()), granule = 0L, beginning = false, last = false)
    }

    private fun flushAudioPage(last: Boolean) {
        if (pending.isEmpty() && !last) return
        writePage(pending.toList(), granulePosition, beginning = false, last = last)
        pending.clear()
        pendingSegments = 0
    }

    /**
     * One Ogg page (RFC 3533 section 6).
     *
     * The checksum covers the whole page with its own field zeroed, so the page
     * is assembled in a buffer, checksummed, patched and written.
     */
    private fun writePage(
        packets: List<ByteArray>,
        granule: Long,
        beginning: Boolean,
        last: Boolean,
    ) {
        val table = segmentTable(packets)
        check(table.size <= MAX_SEGMENTS) { "a page cannot hold ${table.size} segments" }

        val payload = packets.sumOf { it.size }
        val page = ByteArray(HEADER_BYTES + table.size + payload)
        var at = 0

        page[at++] = 'O'.code.toByte()
        page[at++] = 'g'.code.toByte()
        page[at++] = 'g'.code.toByte()
        page[at++] = 'S'.code.toByte()
        page[at++] = 0 // stream structure version
        page[at++] = ((if (beginning) 0x02 else 0x00) or (if (last) 0x04 else 0x00)).toByte()
        at = putLongLe(page, at, granule)
        at = putIntLe(page, at, serialNumber)
        at = putIntLe(page, at, pageSequence++)
        val crcAt = at
        at = putIntLe(page, at, 0) // checksum, filled in below
        page[at++] = table.size.toByte()
        table.forEach { page[at++] = it }
        packets.forEach { packet ->
            packet.copyInto(page, at)
            at += packet.size
        }

        putIntLe(page, crcAt, crc32(page))
        out.write(page)
    }

    /**
     * Lacing values: every packet is 255-byte runs followed by a shorter one.
     *
     * A packet whose length is an exact multiple of 255 still needs the
     * terminating zero, or the reader treats it as continuing onto the next
     * page. Our frames are around fifty bytes, so this is one entry each in
     * practice -- and exactly the kind of edge case that is wrong forever if it
     * is not written down now.
     */
    private fun segmentTable(packets: List<ByteArray>): ByteArray {
        val table = ArrayList<Byte>(packets.size + 1)
        packets.forEach { packet ->
            var remaining = packet.size
            while (remaining >= SEGMENT_MAX) {
                table += SEGMENT_MAX.toByte()
                remaining -= SEGMENT_MAX
            }
            table += remaining.toByte()
        }
        return table.toByteArray()
    }

    private fun segmentsFor(length: Int): Int = length / SEGMENT_MAX + 1

    /** RFC 7845 section 5.1. Nineteen bytes for mapping family 0. */
    private fun opusHead(): ByteArray {
        val head = ByteArray(19)
        var at = 0
        "OpusHead".forEach { head[at++] = it.code.toByte() }
        head[at++] = 1 // version
        head[at++] = channels.toByte()
        at = putShortLe(head, at, PRE_SKIP_SAMPLES)
        at = putIntLe(head, at, sampleRateHz)
        at = putShortLe(head, at, 0) // output gain, Q7.8 dB
        head[at] = 0 // channel mapping family: mono or stereo, no mapping table
        return head
    }

    /** RFC 7845 section 5.2. A vendor string and no user comments. */
    private fun opusTags(): ByteArray {
        val vendor = VENDOR.toByteArray(Charsets.UTF_8)
        val tags = ByteArray(8 + 4 + vendor.size + 4)
        var at = 0
        "OpusTags".forEach { tags[at++] = it.code.toByte() }
        at = putIntLe(tags, at, vendor.size)
        vendor.copyInto(tags, at)
        at += vendor.size
        putIntLe(tags, at, 0) // user comment count
        return tags
    }

    private fun putShortLe(target: ByteArray, at: Int, value: Int): Int {
        target[at] = (value and 0xFF).toByte()
        target[at + 1] = ((value ushr 8) and 0xFF).toByte()
        return at + 2
    }

    private fun putIntLe(target: ByteArray, at: Int, value: Int): Int {
        for (byte in 0 until 4) target[at + byte] = ((value ushr (8 * byte)) and 0xFF).toByte()
        return at + 4
    }

    private fun putLongLe(target: ByteArray, at: Int, value: Long): Int {
        for (byte in 0 until 8) {
            target[at + byte] = ((value ushr (8 * byte)) and 0xFF).toByte()
        }
        return at + 8
    }

    companion object {
        /** Granule positions are counted in 48 kHz samples whatever the input rate. */
        const val GRANULE_RATE_HZ: Int = 48_000

        /**
         * Samples the decoder discards at the start, in 48 kHz units.
         *
         * The encoder needs a little audio before it produces anything
         * meaningful, and RFC 7845 section 4.2 handles that by telling the
         * decoder to throw the warm-up away. libopus reports the exact figure
         * through `OPUS_GET_LOOKAHEAD`; this app's JNI layer does not expose it,
         * and 6.5 ms is the value for the SILK-based modes ADR-004 selects --
         * 16 kHz, VOIP, 20 kbps.
         *
         * Being a little wrong costs a few milliseconds at the very start of a
         * **recording**, and nothing at all live: too large trims a sliver of
         * the first word, too small leaves a sliver of warm-up. If it ever
         * matters, export the real value and pass it in rather than guessing
         * again.
         */
        const val PRE_SKIP_SAMPLES: Int = 312

        /** How many frames share a page. Fifty 20 ms frames is one second. */
        const val FRAMES_PER_PAGE: Int = 50

        private const val HEADER_BYTES = 27
        private const val MAX_SEGMENTS = 255
        private const val SEGMENT_MAX = 255
        private const val VENDOR = "SaikaiPTT"

        /**
         * Ogg's CRC-32, which is not the common one.
         *
         * Polynomial 0x04c11db7 applied most-significant-bit first, with no
         * input or output reflection and no final inversion -- unlike
         * zlib/PNG's CRC-32, which reflects both and inverts. Using
         * `java.util.zip.CRC32` here would produce a file every Ogg reader
         * rejects, which is worth stating because the two are easy to mistake
         * for each other.
         */
        private val CRC_TABLE: IntArray = IntArray(256).also { table ->
            for (index in 0 until 256) {
                var remainder = index shl 24
                repeat(8) {
                    remainder = if (remainder and 0x80000000.toInt() != 0) {
                        (remainder shl 1) xor 0x04c11db7
                    } else {
                        remainder shl 1
                    }
                }
                table[index] = remainder
            }
        }

        internal fun crc32(bytes: ByteArray): Int {
            var crc = 0
            bytes.forEach { byte ->
                val index = ((crc ushr 24) and 0xFF) xor (byte.toInt() and 0xFF)
                crc = (crc shl 8) xor CRC_TABLE[index]
            }
            return crc
        }
    }
}
