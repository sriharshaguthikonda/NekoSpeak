package com.nekospeak.tts.engine

import android.util.Log
import kotlin.math.pow

/**
 * Phonetic-weight-based audio duration estimator for TTS.
 *
 * Port of vocoloco_tts/duration-estimator.js — estimates the number of
 * audio tokens or duration needed for a given text, based on per-character
 * phonetic weights that account for different writing systems.
 *
 * CJK characters produce ~3x more audio than Latin characters because
 * each character is a full syllable. Hangul and kana are intermediate.
 * Arabic/Hebrew use shorter phonemes but have vocalization marks.
 *
 * This is critical for diffusion-based TTS models (like OmniVoice/Kokoro)
 * where you must pre-allocate the target token count before generation.
 * Over-estimating wastes compute; under-estimating truncates the output.
 *
 * Uses binary search over Unicode range breakpoints for O(log n) lookup.
 *
 * Reference: https://github.com/Magkino/vocoloco_tts/blob/main/duration-estimator.js
 * Original: omnivoice/utils/duration.py
 */
object DurationEstimator {
    private const val TAG = "DurationEstimator"

    // Phonetic weights per character type
    private val WEIGHTS = mapOf(
        "cjk" to 3.0f, "hangul" to 2.5f, "kana" to 2.2f,
        "ethiopic" to 3.0f, "yi" to 3.0f,
        "indic" to 1.8f, "thai_lao" to 1.5f, "khmer_myanmar" to 1.8f,
        "arabic" to 1.5f, "hebrew" to 1.5f,
        "latin" to 1.0f, "cyrillic" to 1.0f, "greek" to 1.0f,
        "armenian" to 1.0f, "georgian" to 1.0f,
        "punctuation" to 0.5f, "space" to 0.2f, "digit" to 3.5f,
        "mark" to 0.0f, "default" to 1.0f
    )

    // (end_codepoint, type_key) — sorted for binary search
    private val RANGES = listOf(
        0x02AF to "latin", 0x03FF to "greek", 0x052F to "cyrillic",
        0x058F to "armenian", 0x05FF to "hebrew",
        0x077F to "arabic", 0x089F to "arabic", 0x08FF to "arabic",
        0x097F to "indic", 0x09FF to "indic", 0x0A7F to "indic",
        0x0AFF to "indic", 0x0B7F to "indic", 0x0BFF to "indic",
        0x0C7F to "indic", 0x0CFF to "indic", 0x0D7F to "indic",
        0x0DFF to "indic", 0x0EFF to "thai_lao", 0x0FFF to "indic",
        0x109F to "khmer_myanmar", 0x10FF to "georgian",
        0x11FF to "hangul", 0x137F to "ethiopic", 0x139F to "ethiopic",
        0x13FF to "default", 0x167F to "default", 0x169F to "default",
        0x16FF to "default", 0x171F to "default", 0x173F to "default",
        0x175F to "default", 0x177F to "default", 0x17FF to "khmer_myanmar",
        0x18AF to "default", 0x18FF to "default",
        0x194F to "indic", 0x19DF to "indic", 0x19FF to "khmer_myanmar",
        0x1A1F to "indic", 0x1AAF to "indic", 0x1B7F to "indic",
        0x1BBF to "indic", 0x1BFF to "indic", 0x1C4F to "indic",
        0x1C7F to "indic", 0x1C8F to "cyrillic", 0x1CBF to "georgian",
        0x1CCF to "indic", 0x1CFF to "indic", 0x1D7F to "latin",
        0x1DBF to "latin", 0x1DFF to "default", 0x1EFF to "latin",
        0x309F to "kana", 0x30FF to "kana", 0x312F to "cjk",
        0x318F to "hangul", 0x9FFF to "cjk", 0xA4CF to "yi",
        0xA4FF to "default", 0xA63F to "default", 0xA69F to "cyrillic",
        0xA6FF to "default", 0xA7FF to "latin", 0xA82F to "indic",
        0xA87F to "default", 0xA8DF to "indic", 0xA8FF to "indic",
        0xA92F to "indic", 0xA95F to "indic", 0xA97F to "hangul",
        0xA9DF to "indic", 0xA9FF to "khmer_myanmar", 0xAA5F to "indic",
        0xAA7F to "khmer_myanmar", 0xAADF to "indic", 0xAAFF to "indic",
        0xAB2F to "ethiopic", 0xAB6F to "latin", 0xABBF to "default",
        0xABFF to "indic", 0xD7AF to "hangul", 0xFAFF to "cjk",
        0xFDFF to "arabic", 0xFE6F to "default", 0xFEFF to "arabic",
        0xFFEF to "latin"
    )

    private val BREAKPOINTS = RANGES.map { it.first }

    /** Binary search: find the range containing a codepoint. */
    private fun bisectLeft(arr: List<Int>, value: Int): Int {
        var lo = 0
        var hi = arr.size
        while (lo < hi) {
            val mid = (lo + hi) shr 1
            if (arr[mid] < value) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /** Get Unicode general category (simplified). */
    private fun getCharCategory(code: Int): Char {
        // Combining marks (Mn, Mc, Me)
        if ((code in 0x0300..0x036F) ||
            (code in 0x0483..0x0489) ||
            (code in 0x0591..0x05BD) ||
            (code in 0x064B..0x065F) ||
            (code in 0x0900..0x0903) ||
            (code in 0x093A..0x094F) ||
            (code in 0x0951..0x0957) ||
            (code in 0x0962..0x0963) ||
            (code in 0xFE20..0xFE2F)) return 'M'
        // Punctuation
        if ((code in 0x0021..0x002F) ||
            (code in 0x003A..0x0040) ||
            (code in 0x005B..0x0060) ||
            (code in 0x007B..0x007E) ||
            (code in 0x2000..0x206F) ||
            (code in 0x3000..0x303F)) return 'P'
        // Digits
        if (code in 0x0030..0x0039) return 'N'
        // Symbols
        if ((code in 0x00A0..0x00BF) ||
            (code in 0x2100..0x27FF)) return 'S'
        // Space separators
        if (code == 0x00A0 || code == 0x2000 || code == 0x2001 ||
            code == 0x2002 || code == 0x2003 || code == 0x3000) return 'Z'
        return 'L' // Letter (default)
    }

    /** Get the phonetic weight for a single character. */
    fun getCharWeight(char: Char): Float {
        val code = char.code

        // Fast paths for common ASCII
        if (code in 65..90 || code in 97..122) return WEIGHTS["latin"]!!
        if (code == 32) return WEIGHTS["space"]!!
        if (code == 0x0640) return WEIGHTS["mark"]!! // Arabic Tatweel

        val cat = getCharCategory(code)
        if (cat == 'M') return WEIGHTS["mark"]!!
        if (cat == 'P' || cat == 'S') return WEIGHTS["punctuation"]!!
        if (cat == 'Z') return WEIGHTS["space"]!!
        if (cat == 'N') return WEIGHTS["digit"]!!

        val idx = bisectLeft(BREAKPOINTS, code)
        if (idx < RANGES.size) {
            return WEIGHTS[RANGES[idx].second] ?: WEIGHTS["default"]!!
        }
        // Supplementary CJK planes
        if (code > 0x20000) return WEIGHTS["cjk"]!!
        return WEIGHTS["default"]!!
    }

    /** Calculate total phonetic weight for a text string. */
    fun calculateTotalWeight(text: String): Float {
        var total = 0f
        for (char in text) {
            total += getCharWeight(char)
        }
        return total
    }

    /**
     * Estimate audio duration for target text given reference text and duration.
     *
     * @param targetText Text to estimate duration for
     * @param refText Reference text with known duration
     * @param refDuration Reference audio duration in tokens or seconds
     * @param lowThreshold Minimum output to boost (avoids tiny fragments)
     * @param boostStrength Power-curve boost for short estimates (1/boostStrength)
     * @return Estimated duration in same units as refDuration
     */
    fun estimateDuration(
        targetText: String,
        refText: String,
        refDuration: Float,
        lowThreshold: Float = 50f,
        boostStrength: Float = 3f
    ): Float {
        if (refDuration <= 0f || refText.isBlank()) return 0f
        val refWeight = calculateTotalWeight(refText)
        if (refWeight == 0f) return 0f
        val speedFactor = refWeight / refDuration
        val targetWeight = calculateTotalWeight(targetText)
        val estimated = targetWeight / speedFactor
        if (lowThreshold > 0f && estimated < lowThreshold) {
            val alpha = 1.0f / boostStrength
            return lowThreshold * (estimated / lowThreshold).pow(alpha).toFloat()
        }
        return estimated
    }

    /**
     * Estimate the number of audio tokens needed for TTS synthesis.
     *
     * @param text Input text
     * @param refText Reference text (default: "Nice to meet you.")
     * @param numRefAudioTokens Reference audio token count (default: 25)
     * @param speed Speed multiplier (1.0 = normal)
     * @return Estimated number of audio tokens
     */
    fun estimateTargetTokens(
        text: String,
        refText: String = "Nice to meet you.",
        numRefAudioTokens: Int = 25,
        speed: Float = 1.0f
    ): Int {
        var est = estimateDuration(text, refText, numRefAudioTokens.toFloat())
        if (speed > 0f && speed != 1.0f) est /= speed
        return maxOf(1, kotlin.math.round(est).toInt())
    }

    /**
     * Estimate audio duration in seconds for Kokoro-style models.
     * Uses a typical speaking rate of ~6.5 phoneme-weights per second
     * at normal speed (1.0x) for 24kHz audio.
     *
     * @param text Input text
     * @param speed Speed multiplier (1.0 = normal)
     * @return Estimated duration in seconds
     */
    fun estimateDurationSeconds(text: String, speed: Float = 1.0f): Float {
        val weight = calculateTotalWeight(text)
        // Average speaking rate: ~6.5 phonetic-weights per second at normal speed
        // This is calibrated for English; CJK text will get longer estimates
        // due to higher per-character weights, which is correct.
        val baseDuration = weight / 6.5f
        return if (speed > 0f) baseDuration / speed else baseDuration
    }
}