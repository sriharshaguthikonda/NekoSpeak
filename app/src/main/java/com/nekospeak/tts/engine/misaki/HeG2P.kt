package com.nekospeak.tts.engine.misaki

import android.util.Log

/**
 * Faithful port of upstream Misaki's Hebrew G2P (he.py).
 * Reference: https://github.com/hexgrad/misaki/blob/main/misaki/he.py
 *
 * The upstream uses the `mishkal` Python package for Hebrew phonemization.
 * On Android, we don't have mishkal, so this port uses eSpeak's Hebrew
 * backend as the primary fallback, with basic Hebrew-specific normalization.
 *
 * Hebrew TTS requires niqqud (vowel diacritics) for accurate pronunciation.
 * Without niqqud, Hebrew is ambiguous (like Arabic without tashkeel).
 * This module:
 * 1. Normalizes Hebrew text (final forms → regular forms for processing)
 * 2. Applies eSpeak Hebrew phonemization
 * 3. Returns IPA output
 */
class HeG2P(
    private val espeakFallback: ((String, String) -> String?)? = null,
    private val unk: String = "❓"
) {
    companion object {
        private const val TAG = "HeG2P"

        // Hebrew final form letters → regular form
        private val FINAL_TO_REGULAR = mapOf(
            'ך' to 'כ', 'ם' to 'מ', 'ן' to 'נ', 'ף' to 'פ', 'ץ' to 'צ'
        )

        // Hebrew letter names for fallback spelling
        private val LETTER_NAMES = mapOf(
            'א' to "alef", 'ב' to "bet", 'ג' to "gimel", 'ד' to "dalet",
            'ה' to "he", 'ו' to "vav", 'ז' to "zayin", 'ח' to "het",
            'ט' to "tet", 'י' to "yod", 'כ' to "kaf", 'ל' to "lamed",
            'מ' to "mem", 'נ' to "nun", 'ס' to "samekh", 'ע' to "ayin",
            'פ' to "pe", 'צ' to "tsadi", 'ק' to "qof", 'ר' to "resh",
            'ש' to "shin", 'ת' to "tav"
        )

        // Niqqud (vowel diacritics) range: 0x05B0-0x05C7
        private val NIQQUD_RANGE = 0x05B0..0x05C7

        fun hasNiqqud(text: String): Boolean = text.any { it.code in NIQQUD_RANGE }

        fun isHebrew(text: String): Boolean =
            text.any { it.code in 0x0590..0x05FF || it.code in 0xFB1D..0xFB4F }
    }

    /**
     * Normalize Hebrew text for phonemization.
     * Removes bi-directional markers and normalizes presentation forms.
     */
    fun normalizeHebrew(text: String): String {
        return text
            .replace(Regex("[\\u200E\\u200F\\u202A-\\u202E]"), "") // BiDi markers
            .let { java.text.Normalizer.normalize(it, java.text.Normalizer.Form.NFC) }
    }

    /**
     * Main phonemization entry point.
     */
    fun phonemize(text: String, preservePunctuation: Boolean = true): String {
        if (text.isBlank()) return ""

        // Check for niqqud
        if (!hasNiqqud(text)) {
            Log.w(TAG, "Hebrew text without niqqud detected. Pronunciation may be ambiguous.")
        }

        // Try eSpeak fallback
        val espeakResult = espeakFallback?.invoke(normalizeHebrew(text), "he")
        if (espeakResult != null && espeakResult.isNotBlank()) {
            return espeakResult
        }

        // Fallback: letter-by-letter (poor quality but better than nothing)
        val result = StringBuilder()
        for (ch in text) {
            val regular = FINAL_TO_REGULAR[ch] ?: ch
            val name = LETTER_NAMES[regular]
            if (name != null) {
                result.append(name).append(" ")
            } else if (ch.isWhitespace()) {
                result.append(" ")
            } else if (preservePunctuation && !ch.isLetterOrDigit()) {
                result.append(ch)
            }
        }

        return result.toString().trim()
    }
}