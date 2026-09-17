package com.saikai.ptt.core.audio

import com.saikai.ptt.core.common.Outcome

/**
 * Takes Opus frames back out of the Ogg container [OggOpusWriter] put them in.
 *
 * Recognition needs PCM, and a recording is Opus inside Ogg. This class does
 * the container half; the codec half is the decoder the receive path already
 * owns. Neither re-encodes anything, so a recording that is recognised is the
 * same audio that was played (ADR-004 section 6).
 *
 * ### Why this is written by hand, again
 *
 * Same argument as the writer's, plus one more. `MediaExtractor` can demux Ogg
 * and is available from API 21, but it is the platform's, which means it varies
 * by ROM in exactly the low-end population this app targets, and it cannot be
 * tested anywhere but on a device. A reader written here can be checked against
 * the writer in milliseconds on the JVM, and that round trip is the test that
 * actually matters: if these two agree, every recording this app has ever
 * written can be read back.
 *
 * ### A damaged file gives up as little as possible
 *
 * A page whose checksum fails is skipped, not fatal
 * (`tasks/Task45` and the general rule in `docs/05_DataModel.md` section 48).
 * A file cut off mid-page -- the process was killed, the disk filled -- reads
 * as everything up to the cut. The reasoning is the same one that made the
 * writer stream straight through: a recording is worth what can be salvaged
 * from it, and half a sentence beats an error.
 *
 * Whatever was skipped is reported rather than hidden, because a transcript
 * built from a file with holes in it should be marked as such.
 *
 * ### Memory
 *
 * Takes and holds the whole file. A PTT session is bounded by how long somebody
 * can hold a button, and ADR-004's 20 kbps means a minute costs 150 KB; the
 * decoded PCM that follows is an order of magnitude larger and has to be
 * contiguous anyway, because that is what the recogniser accepts. [MAX_BYTES]
 * is the guard against a file that is not what it claims to be.
 */
object OggOpusReader {

    /**
     * Parses [bytes] into its Opus packets.
     *
     * The two header packets are consumed, not returned: [OggOpusStream.packets]
     * is audio only.
     */
    fun read(bytes: ByteArray): Outcome<OggOpusStream, OggError> {
        if (bytes.size > MAX_BYTES) return Outcome.failure(OggError.TooLarge(bytes.size))

        val packets = ArrayList<ByteArray>()
        // A packet may be split across pages, so a partial one is carried over.
        var partial: ByteArrayBuilder? = null
        var serial: Int? = null
        var info: OpusStreamInfo? = null
        var seenTags = false
        var lastGranule = 0L
        var skipped = 0
        var truncated = false
        var at = 0

        while (at < bytes.size) {
            val start = indexOfCapture(bytes, at)
            if (start < 0) {
                // Nothing that looks like a page in what is left.
                if (at < bytes.size) truncated = true
                break
            }
            if (start != at) {
                // Bytes between pages: the tail of something that failed to
                // parse. Resynchronising on the next capture pattern is what
                // every Ogg reader does, and it is why the pattern exists.
                skipped++
                partial = null
            }

            val page = parsePage(bytes, start)
            if (page == null) {
                // Either the header runs past the end of the file, or the
                // payload does. Both mean the file stops here.
                truncated = true
                break
            }
            at = page.endOffset

            if (!page.checksumOk) {
                skipped++
                partial = null
                continue
            }

            if (page.beginning && serial == null) serial = page.serial
            if (serial != null && page.serial != serial) {
                // A second logical stream. This app writes one per file;
                // anything else is not ours to interpret.
                continue
            }

            // A page that says it continues a packet, when nothing is pending,
            // follows a page that was skipped. The fragment is unusable.
            var carried = if (page.continued) partial else null
            if (!page.continued) partial = null

            for (piece in page.packets) {
                if (piece.complete) {
                    val packet = if (carried != null) carried.finish(piece.bytes) else piece.bytes
                    carried = null
                    when {
                        info == null -> info = parseOpusHead(packet) ?: return Outcome.failure(
                            OggError.NotOpus
                        )
                        !seenTags -> seenTags = true
                        else -> packets += packet
                    }
                } else {
                    // The last piece of the page, still running.
                    carried = (carried ?: ByteArrayBuilder()).also { it.add(piece.bytes) }
                }
            }
            partial = carried
            lastGranule = page.granule
        }

        val header = info ?: return Outcome.failure(
            if (packets.isEmpty() && skipped == 0) OggError.NotOgg else OggError.NotOpus
        )

        return Outcome.success(
            OggOpusStream(
                info = header,
                packets = packets,
                granulePosition = lastGranule,
                pagesSkipped = skipped,
                truncated = truncated,
            )
        )
    }

    /** Where the next `OggS` starts at or after [from], or -1. */
    private fun indexOfCapture(bytes: ByteArray, from: Int): Int {
        var at = from
        val limit = bytes.size - 4
        while (at <= limit) {
            if (bytes[at] == 'O'.code.toByte() &&
                bytes[at + 1] == 'g'.code.toByte() &&
                bytes[at + 2] == 'g'.code.toByte() &&
                bytes[at + 3] == 'S'.code.toByte()
            ) {
                return at
            }
            at++
        }
        return -1
    }

    /** @return null when the page runs past the end of [bytes]. */
    private fun parsePage(bytes: ByteArray, start: Int): ParsedPage? {
        if (start + HEADER_BYTES > bytes.size) return null
        if (bytes[start + 4].toInt() != 0) return null // stream structure version

        val flags = bytes[start + 5].toInt()
        val granule = longLe(bytes, start + 6)
        val serial = intLe(bytes, start + 14)
        val storedCrc = intLe(bytes, start + 22)
        val segmentCount = bytes[start + 26].toInt() and 0xFF

        val tableAt = start + HEADER_BYTES
        if (tableAt + segmentCount > bytes.size) return null

        var payloadBytes = 0
        for (index in 0 until segmentCount) {
            payloadBytes += bytes[tableAt + index].toInt() and 0xFF
        }
        val payloadAt = tableAt + segmentCount
        val end = payloadAt + payloadBytes
        if (end > bytes.size) return null

        // The checksum covers the page with its own field zeroed. Copying is
        // the honest way to say that; a page is a few hundred bytes.
        val page = bytes.copyOfRange(start, end)
        for (index in 22 until 26) page[index] = 0
        val checksumOk = OggOpusWriter.crc32(page) == storedCrc

        val pieces = ArrayList<PagePacket>(segmentCount)
        if (checksumOk) {
            var runStart = payloadAt
            var runLength = 0
            for (index in 0 until segmentCount) {
                val lacing = bytes[tableAt + index].toInt() and 0xFF
                runLength += lacing
                if (lacing < SEGMENT_MAX) {
                    pieces += PagePacket(
                        bytes.copyOfRange(runStart, runStart + runLength),
                        complete = true,
                    )
                    runStart += runLength
                    runLength = 0
                }
            }
            if (runLength > 0) {
                // Ends on a 255, so it continues onto the next page.
                pieces += PagePacket(
                    bytes.copyOfRange(runStart, runStart + runLength),
                    complete = false,
                )
            }
        }

        return ParsedPage(
            granule = granule,
            serial = serial,
            continued = flags and 0x01 != 0,
            beginning = flags and 0x02 != 0,
            last = flags and 0x04 != 0,
            checksumOk = checksumOk,
            packets = pieces,
            endOffset = end,
        )
    }

    /** RFC 7845 section 5.1. @return null when this is not an `OpusHead`. */
    private fun parseOpusHead(packet: ByteArray): OpusStreamInfo? {
        if (packet.size < 19) return null
        if (!startsWith(packet, "OpusHead")) return null
        if (packet[8].toInt() != 1) return null // version
        return OpusStreamInfo(
            channels = packet[9].toInt() and 0xFF,
            preSkipSamples = shortLe(packet, 10),
            inputSampleRateHz = intLe(packet, 12),
            outputGainQ78 = shortLe(packet, 16).toShort().toInt(),
        )
    }

    private fun startsWith(bytes: ByteArray, text: String): Boolean {
        if (bytes.size < text.length) return false
        text.forEachIndexed { index, char ->
            if (bytes[index] != char.code.toByte()) return false
        }
        return true
    }

    private fun shortLe(bytes: ByteArray, at: Int): Int =
        (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)

    private fun intLe(bytes: ByteArray, at: Int): Int {
        var value = 0
        for (byte in 0 until 4) value = value or ((bytes[at + byte].toInt() and 0xFF) shl (8 * byte))
        return value
    }

    private fun longLe(bytes: ByteArray, at: Int): Long {
        var value = 0L
        for (byte in 0 until 8) {
            value = value or ((bytes[at + byte].toLong() and 0xFF) shl (8 * byte))
        }
        return value
    }

    private class ByteArrayBuilder {
        private val pieces = ArrayList<ByteArray>(2)

        fun add(bytes: ByteArray) {
            pieces += bytes
        }

        fun finish(tail: ByteArray): ByteArray {
            val total = pieces.sumOf { it.size } + tail.size
            val out = ByteArray(total)
            var at = 0
            pieces.forEach { it.copyInto(out, at); at += it.size }
            tail.copyInto(out, at)
            return out
        }
    }

    private class PagePacket(val bytes: ByteArray, val complete: Boolean)

    private class ParsedPage(
        val granule: Long,
        val serial: Int,
        val continued: Boolean,
        val beginning: Boolean,
        val last: Boolean,
        val checksumOk: Boolean,
        val packets: List<PagePacket>,
        val endOffset: Int,
    )

    private const val HEADER_BYTES = 27
    private const val SEGMENT_MAX = 255

    /**
     * The largest file this will parse.
     *
     * 64 MB is about seventy minutes at ADR-004's bitrate -- far beyond any
     * press-and-hold -- and it bounds what a corrupt or substituted file can
     * make this allocate.
     */
    const val MAX_BYTES: Int = 64 * 1024 * 1024
}

/** What `OpusHead` says about the stream (RFC 7845 section 5.1). */
data class OpusStreamInfo(
    val channels: Int,
    /** Samples the decoder discards at the start, in 48 kHz units. */
    val preSkipSamples: Int,
    /** Informational: granule positions are 48 kHz regardless (RFC 7845 section 4). */
    val inputSampleRateHz: Int,
    val outputGainQ78: Int,
)

/** A parsed recording: its header, its audio packets, and what was lost. */
class OggOpusStream(
    val info: OpusStreamInfo,
    val packets: List<ByteArray>,
    /** The last page's granule, i.e. total 48 kHz samples including the pre-skip. */
    val granulePosition: Long,
    /** Pages dropped for a failed checksum or a lost resynchronisation. */
    val pagesSkipped: Int,
    /** True when the file ended mid-page. */
    val truncated: Boolean,
) {
    /** True when nothing was lost, which is what an ordinary recording looks like. */
    val intact: Boolean get() = pagesSkipped == 0 && !truncated

    /**
     * Playable length in milliseconds, pre-skip removed.
     *
     * Derived from the granule rather than from the packet count, because a
     * damaged file has fewer packets than its timeline says.
     */
    val durationMillis: Long
        get() = ((granulePosition - info.preSkipSamples).coerceAtLeast(0) * 1000L) /
            OggOpusWriter.GRANULE_RATE_HZ
}

/** Why a recording could not be read at all. Anything recoverable is not here. */
sealed interface OggError {
    /** No Ogg page was found. Not a recording, or empty. */
    data object NotOgg : OggError

    /** Ogg, but the first packet is not an `OpusHead`. */
    data object NotOpus : OggError

    /** Larger than [OggOpusReader.MAX_BYTES]. */
    data class TooLarge(val bytes: Int) : OggError
}
