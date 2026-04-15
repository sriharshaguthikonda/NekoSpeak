package com.nekospeak.tts.engine.misaki

import android.content.Context
import android.util.Log

/**
 * Compact CMU Pronouncing Dictionary for Korean loanword conversion.
 *
 * Loads a subset of the CMUdict (3,000+ high-frequency English words)
 * encoded as word|phoneme_ids where phoneme IDs are base36-encoded
 * indices into the 39-phoneme ARPAbet inventory.
 *
 * This replaces the full NLTK cmudict.dict() (123K entries, ~5MB)
 * with a ~46KB asset file covering the most common English words
 * that appear as loanwords in Korean text.
 *
 * Reference: http://www.speech.cs.cmu.edu/cgi-bin/cmudict
 * License: CMUdict is public domain.
 */
class CmuDict {
    companion object {
        private const val TAG = "CmuDict"

        // 39 base ARPAbet phonemes (stress stripped)
        private val BASE_PHONEMES = arrayOf(
            "AA", "AE", "AH", "AO", "AW", "AY",
            "B", "CH", "D", "DH",
            "EH", "ER", "EY", "F", "G",
            "HH", "IH", "IY", "JH", "K",
            "L", "M", "N", "NG", "OW",
            "OY", "P", "R", "S", "SH",
            "T", "TH", "UH", "UW", "V",
            "W", "Y", "Z", "ZH"
        )

        private val BASE36 = "0123456789abcdefghijklmnopqrstuvwxyz"

        private var dict: Map<String, List<String>> = emptyMap()
        private var loaded = false

        /**
         * Load the CMUdict from assets. Call once during init.
         */
        fun load(context: Context) {
            if (loaded) return
            try {
                val entries = mutableMapOf<String, List<String>>()
                context.assets.open("cmudict_korean.txt").bufferedReader().use { reader ->
                    reader.forEachLine { line ->
                        val pipeIdx = line.indexOf('|')
                        if (pipeIdx < 0) return@forEachLine
                        val word = line.substring(0, pipeIdx)
                        val ids = line.substring(pipeIdx + 1).trim()
                        if (ids.isEmpty()) return@forEachLine

                        // Decode base36 phoneme IDs back to ARPAbet strings
                        val phonemes = ids.split(' ').map { idStr ->
                            val n = fromBase36(idStr)
                            val stress = n % 3
                            val baseIdx = n / 3
                            if (baseIdx < BASE_PHONEMES.size) {
                                BASE_PHONEMES[baseIdx] + stress.toString()
                            } else {
                                ""
                            }
                        }.filter { it.isNotEmpty() }

                        // Only store first pronunciation for each word
                        if (word !in entries && phonemes.isNotEmpty()) {
                            entries[word] = phonemes
                        }
                    }
                }
                dict = entries
                loaded = true
                Log.d(TAG, "Loaded ${entries.size} CMUdict entries")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load CMUdict: ${e.message}")
                loaded = true // Don't retry
            }
        }

        private fun fromBase36(s: String): Int {
            var result = 0
            for (c in s) {
                result = result * 36 + BASE36.indexOf(c.lowercaseChar())
            }
            return result
        }

        /**
         * Look up ARPAbet pronunciation for an English word.
         * Returns first pronunciation as list of ARPAbet strings (with stress),
         * or null if not found.
         */
        fun lookup(word: String): List<String>? {
            return dict[word.lowercase()]
        }

        /** Check if the dictionary has been loaded. */
        fun isLoaded(): Boolean = loaded
    }
}