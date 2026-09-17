package com.saikai.ptt.asr

import java.io.File

/**
 * Where the recognition model lives on this device, and whether it is there.
 *
 * Paths only. Fetching it is Task46's; this is what Task45 needs in order to
 * load one that has already arrived -- by download later, or by `adb push`
 * while that download is being built.
 *
 * The four file names are the upstream ones, unchanged. Renaming them on the
 * way in would mean the digests in `ADR-012` no longer describe what is on
 * disk, and those digests are the only reason to trust any of it.
 */
class AsrModel(filesDir: File) {

    /** `<filesDir>/asr/ja-v1/`. Versioned, so a later model can land beside this one. */
    val directory: File = File(File(filesDir, ROOT), VERSION)

    val encoder: File get() = File(directory, ENCODER)
    val decoder: File get() = File(directory, DECODER)
    val joiner: File get() = File(directory, JOINER)
    val tokens: File get() = File(directory, TOKENS)

    /**
     * Whether all four files are present and non-empty.
     *
     * Presence only -- the digests were checked when the files were installed
     * (Task46), and re-hashing 148 MB every time the engine loads would cost
     * seconds on the reference device for a file nothing else can write.
     */
    val installed: Boolean
        get() = try {
            listOf(encoder, decoder, joiner, tokens).all { it.isFile && it.length() > 0 }
        } catch (_: SecurityException) {
            false
        }

    /** Bytes on disk, for the settings screen to show and offer to remove. */
    val bytes: Long
        get() = try {
            listOf(encoder, decoder, joiner, tokens).sumOf { if (it.isFile) it.length() else 0L }
        } catch (_: SecurityException) {
            0L
        }

    companion object {
        const val ROOT: String = "asr"

        /**
         * The model's version, which is the release tag it came from.
         *
         * `ADR-012`: the tag is part of the download URL and must not change
         * without the digests changing with it.
         */
        const val VERSION: String = "ja-v1"

        const val ENCODER: String = "encoder-epoch-99-avg-1.int8.onnx"
        const val DECODER: String = "decoder-epoch-99-avg-1.int8.onnx"
        const val JOINER: String = "joiner-epoch-99-avg-1.int8.onnx"
        const val TOKENS: String = "tokens.txt"
    }
}
