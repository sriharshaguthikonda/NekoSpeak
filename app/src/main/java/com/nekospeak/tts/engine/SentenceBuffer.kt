package com.nekospeak.tts.engine

import android.util.Log

/**
 * Smart text chunker for TTS synthesis.
 *
 * Port of vocoloco_tts/sentence-buffer.js — splits input text into
 * complete sentences suitable for TTS, with:
 * - Abbreviation awareness (Mr., Dr., i.e., e.g., etc.) to avoid false splits
 * - Number context awareness (3.14 is not a sentence break)
 * - Min/max character bounds for optimal audio chunking
 * - Markdown cleaning (strips **bold**, *italic*, # headings, `code`)
 * - Force-split for long unbroken text (avoids TTS buffer overflows)
 * - Multilingual abbreviation support (German z.B., d.h., usw., etc.)
 *
 * The min/max bound approach ensures:
 * - Too-short chunks (< minChars) get merged with the next sentence
 *   (avoids choppy audio from tiny fragments like "OK.")
 * - Too-long chunks (> maxChars) get force-split at comma/semicolon/word
 *   boundaries (avoids TTS latency spikes from very long inputs)
 *
 * Reference: https://github.com/Magkino/vocoloco_tts/blob/main/sentence-buffer.js
 */
class SentenceBuffer(
    private val minChars: Int = DEFAULT_MIN_CHARS,
    private val maxChars: Int = DEFAULT_MAX_CHARS,
    private val onSentence: (String) -> Unit
) {
    companion object {
        private const val TAG = "SentenceBuffer"
        const val DEFAULT_MIN_CHARS = 20
        const val DEFAULT_MAX_CHARS = 250

        /** Abbreviations that end with a period but don't end a sentence. */
        private val ABBREVS = setOf(
            // English
            "mr", "mrs", "ms", "dr", "prof", "sr", "jr", "st",
            "vs", "etc", "approx", "dept", "est", "govt",
            "ie", "eg", "al", "fig", "vol", "no",
            // German
            "zb", "dh", "usw", "bzw", "ca", "evtl", "ggf", "inkl",
            "nr", "tel", "str",
            // French
            "mme", "mlle", "mm", "dr", "pr", "vs",
            // Spanish
            "sr", "sra", "srta", "ud", "uds", "etc",
            // Italian
            "sig", "sigra", "dott", "prof", "etc",
            // Common
            "inc", "corp", "ltd", "co", "apt", "ave", "blvd",
            "jan", "feb", "mar", "apr", "jun", "jul", "aug", "sep",
            "sept", "oct", "nov", "dec", "mon", "tue", "wed", "thu",
            "fri", "sat", "sun"
        )

        /** Sentence-ending punctuation followed by whitespace. */
        private val SENTENCE_END = Regex("([.!?;:])\\s")

        /** Markdown patterns to strip for TTS. */
        private val MD_PATTERNS = listOf(
            Regex("\\*\\*(.+?)\\*\\*") to "$1",     // **bold**
            Regex("\\*(.+?)\\*") to "$1",             // *italic*
            Regex("^#+\\s+", RegexOption.MULTILINE) to "", // # heading
            Regex("^[-*]\\s+", RegexOption.MULTILINE) to "", // - list item
            Regex("`(.+?)`") to "$1"                  // `code`
        )
    }

    private var buf = ""

    /**
     * Add text to the buffer and extract any complete sentences.
     * Call this incrementally as text becomes available (streaming).
     */
    fun addText(text: String) {
        buf += text
        extract()
    }

    /**
     * Flush any remaining text in the buffer as a final sentence.
     * Call this when the input stream ends.
     */
    fun flush() {
        val cleaned = cleanForTts(buf)
        if (cleaned.isNotBlank()) onSentence(cleaned)
        buf = ""
    }

    /**
     * One-shot: split a complete text into sentences.
     * Convenience method for non-streaming use cases.
     */
    fun split(text: String): List<String> {
        val results = mutableListOf<String>()
        val buffer = SentenceBuffer(minChars, maxChars) { results.add(it) }
        buffer.addText(text)
        buffer.flush()
        return results
    }

    /** Clean Markdown formatting from text for TTS consumption. */
    fun cleanForTts(text: String): String {
        var result = text
        for ((pattern, replacement) in MD_PATTERNS) {
            result = pattern.replace(result, replacement)
        }
        return result.trim()
    }

    /** Check if the word before a period is an abbreviation. */
    private fun isAbbreviation(textBeforeDot: String): Boolean {
        val parts = textBeforeDot.trimEnd().split(Regex("\\s+"))
        val word = (parts.lastOrNull() ?: "").replace(Regex("\\.+$"), "")
        return word.lowercase() in ABBREVS
    }

    /** Check if a period is between digits (e.g., "3.14"). */
    private fun isNumberContext(text: String, dotPos: Int): Boolean {
        if (dotPos > 0 && dotPos < text.length - 1) {
            return text[dotPos - 1].isDigit() && text[dotPos + 1].isDigit()
        }
        return false
    }

    /** Extract complete sentences from the buffer. */
    private fun extract() {
        var searchStart = 0
        while (true) {
            val match = SENTENCE_END.find(buf, searchStart) ?: break
            val pos = match.range.last + 1  // End of match (including whitespace)
            val dotPos = match.range.first   // Position of the punctuation

            // For periods: check abbreviation and number context
            if (match.groupValues[1] == ".") {
                val textBeforeDot = buf.substring(0, dotPos)
                if (isAbbreviation(textBeforeDot)) {
                    searchStart = pos
                    continue
                }
                if (isNumberContext(buf, dotPos)) {
                    searchStart = pos
                    continue
                }
            }

            val candidate = buf.substring(0, pos).trim()
            val cleaned = cleanForTts(candidate)

            if (cleaned.length >= minChars) {
                onSentence(cleaned)
                buf = buf.substring(pos)
                searchStart = 0
            } else {
                // Too short — merge with next sentence
                searchStart = pos
            }
        }

        // Force-split long buffers that have no sentence breaks
        if (buf.length >= maxChars) {
            var splitPos = -1
            for (delim in listOf(". ", "! ", "? ", "; ", ", ", " - ")) {
                val idx = buf.lastIndexOf(delim, maxChars)
                if (idx >= minChars) {
                    splitPos = idx + delim.length
                    break
                }
            }
            if (splitPos < 0) {
                // No delimiter found — split at word boundary
                val idx = buf.lastIndexOf(' ', maxChars)
                splitPos = if (idx > minChars) idx + 1 else maxChars
            }

            val cleaned = cleanForTts(buf.substring(0, splitPos))
            if (cleaned.isNotBlank()) onSentence(cleaned)
            buf = buf.substring(splitPos)
        }
    }
}