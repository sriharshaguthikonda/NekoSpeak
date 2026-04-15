package com.nekospeak.tts.engine.misaki

/**
 * Faithful port of upstream Misaki's Pinyin-to-IPA transcription (transcription.py).
 * Reference: https://github.com/hexgrad/misaki/blob/main/misaki/transcription.py
 * Original: https://github.com/stefantaubert/pinyin-to-ipa (MIT License)
 *
 * Converts pinyin syllables (e.g. "zhong1", "xue2") to IPA phoneme tuples
 * using proper initial/final decomposition with tone application.
 *
 * This replaces the simplified py2ipa table in ZhG2P with the full
 * phonologically-accurate mapping from upstream.
 */
object Transcription {
    // === Initial consonant mapping: pinyin → IPA ===
    private val INITIAL_MAPPING = mapOf(
        "b" to listOf("p"),
        "c" to listOf("ʦʰ"),
        "ch" to listOf("\uAB67ʰ"),  // ʈʂʰ
        "d" to listOf("t"),
        "f" to listOf("f"),
        "g" to listOf("k"),
        "h" to listOf("x"),  // Also "h" variant — takes first
        "j" to listOf("ʨ"),
        "k" to listOf("kʰ"),
        "l" to listOf("l"),
        "m" to listOf("m"),
        "n" to listOf("n"),
        "p" to listOf("pʰ"),
        "q" to listOf("ʨʰ"),
        "r" to listOf("ɻ"),  // Also "ʐ" variant — takes first
        "s" to listOf("s"),
        "sh" to listOf("ʂ"),
        "t" to listOf("tʰ"),
        "x" to listOf("ɕ"),
        "z" to listOf("ʦ"),
        "zh" to listOf("\uAB67"),  // ʈʂ
        "w" to listOf("w"),
        "y" to listOf("j")   // Also "ɥ" variant — takes first
    )

    // === Syllabic consonant mappings ===
    private val SYLLABIC_CONSONANT_MAPPINGS = mapOf(
        "hm" to listOf("h", "m0"),
        "hng" to listOf("h", "ŋ0"),
        "m" to listOf("m0"),
        "n" to listOf("n0"),
        "ng" to listOf("ŋ0")
    )

    // === Interjection mappings ===
    private val INTERJECTION_MAPPINGS = mapOf(
        "io" to listOf("j", "ɔ0"),
        "ê" to listOf("ɛ0"),
        "er" to listOf("ɚ0"),  // Also "aɚ̯0" variant
        "o" to listOf("ɔ0")
    )

    // === Final (rhyme) mapping: pinyin → IPA ===
    private val FINAL_MAPPING = mapOf(
        "a" to listOf("a0"),
        "ai" to listOf("ai̯0"),
        "an" to listOf("a0", "n"),
        "ang" to listOf("a0", "ŋ"),
        "ao" to listOf("au̯0"),
        "e" to listOf("ɤ0"),
        "ei" to listOf("ei̯0"),
        "en" to listOf("ə0", "n"),
        "eng" to listOf("ə0", "ŋ"),
        "i" to listOf("i0"),
        "ia" to listOf("j", "a0"),
        "ian" to listOf("j", "ɛ0", "n"),
        "iang" to listOf("j", "a0", "ŋ"),
        "iao" to listOf("j", "au̯0"),
        "ie" to listOf("j", "e0"),
        "in" to listOf("i0", "n"),
        "iou" to listOf("j", "ou̯0"),
        "ing" to listOf("i0", "ŋ"),
        "iong" to listOf("j", "ʊ0", "ŋ"),
        "ong" to listOf("ʊ0", "ŋ"),
        "ou" to listOf("ou̯0"),
        "u" to listOf("u0"),
        "uei" to listOf("w", "ei̯0"),
        "ua" to listOf("w", "a0"),
        "uai" to listOf("w", "ai̯0"),
        "uan" to listOf("w", "a0", "n"),
        "uen" to listOf("w", "ə0", "n"),
        "uang" to listOf("w", "a0", "ŋ"),
        "ueng" to listOf("w", "ə0", "ŋ"),
        "uo" to listOf("w", "o0"),
        "o" to listOf("w", "o0"),  // uo written as o after b/p/m/f
        "ü" to listOf("y0"),  // After y, j, q, x
        "üe" to listOf("ɥ", "e0"),
        "üan" to listOf("ɥ", "ɛ0", "n"),
        "ün" to listOf("y0", "n")
    )

    // === Special finals after zh/ch/sh/r (retroflex) ===
    private val FINAL_MAPPING_AFTER_ZH_CH_SH_R = mapOf(
        "i" to listOf("ɻ̩0")  // Also "ʐ̩0" variant
    )

    // === Special finals after z/c/s (dental) ===
    private val FINAL_MAPPING_AFTER_Z_C_S = mapOf(
        "i" to listOf("ɹ̩0")  // Also "z̩0" variant
    )

    // === Tone mapping: tone number → IPA tone mark ===
    private val TONE_MAPPING = mapOf(
        1 to "˥",
        2 to "˧˥",
        3 to "˧˩˧",
        4 to "˥˩",
        5 to ""  // neutral tone
    )

    /** Get tone number from pinyin with tone3 style (e.g. "zhong1" → 1). */
    fun getTone(pinyin: String): Int {
        if (pinyin.isEmpty()) return 5
        val last = pinyin.last()
        return if (last.isDigit()) last.digitToInt().let { if (it in 1..5) it else 5 } else 5
    }

    /** Remove tone number from pinyin. */
    fun toNormal(pinyin: String): String {
        if (pinyin.isEmpty()) return pinyin
        val last = pinyin.last()
        return if (last.isDigit()) pinyin.dropLast(1) else pinyin
    }

    /** Get initial from pinyin (e.g. "zhong" → "zh"). */
    fun getInitials(normalPinyin: String): String? {
        if (normalPinyin in SYLLABIC_CONSONANT_MAPPINGS) return null
        if (normalPinyin in INTERJECTION_MAPPINGS) return null

        // Try longest match first
        for (len in listOf(3, 2)) {
            if (normalPinyin.length >= len) {
                val sub = normalPinyin.substring(0, len)
                if (sub in INITIAL_MAPPING) return sub
            }
        }
        if (normalPinyin.isNotEmpty() && normalPinyin[0].toString() in INITIAL_MAPPING) {
            return normalPinyin[0].toString()
        }
        return null
    }

    /** Get final from pinyin (e.g. "zhong" → "ong"). */
    fun getFinals(normalPinyin: String): String? {
        if (normalPinyin in SYLLABIC_CONSONANT_MAPPINGS) return null
        if (normalPinyin in INTERJECTION_MAPPINGS) return null

        val initial = getInitials(normalPinyin) ?: ""
        val final = normalPinyin.substring(initial.length)

        if (final.isEmpty()) return null

        // Handle ü after j/q/x (pypinyin returns u but it means ü)
        if (initial in listOf("j", "q", "x")) {
            val mapped = final.replace("u", "ü")
            if (mapped in FINAL_MAPPING) return mapped
        }

        if (final in FINAL_MAPPING) return final
        // Try with ü
        if (final.replace("u", "ü") in FINAL_MAPPING) return final.replace("u", "ü")

        return null
    }

    /** Apply tone to IPA phoneme list (replace "0" with tone mark). */
    fun applyTone(variants: List<String>, tone: Int): List<String> {
        val toneIpa = TONE_MAPPING[tone] ?: ""
        return variants.map { it.replace("0", toneIpa) }
    }

    /**
     * Convert a pinyin syllable (with tone number) to IPA.
     * Returns the first (most common) pronunciation variant.
     * e.g. "zhong1" → "ʈʂʊŋ˥"
     */
    fun pinyinToIpa(pinyin: String): String {
        val toneNr = getTone(pinyin)
        val normal = toNormal(pinyin).lowercase()

        // Check interjections
        if (normal in INTERJECTION_MAPPINGS) {
            val ipaVariants = INTERJECTION_MAPPINGS[normal]!!
            return applyTone(ipaVariants, toneNr).joinToString("")
        }

        // Check syllabic consonants
        if (normal in SYLLABIC_CONSONANT_MAPPINGS) {
            val ipaVariants = SYLLABIC_CONSONANT_MAPPINGS[normal]!!
            return applyTone(ipaVariants, toneNr).joinToString("")
        }

        val initial = getInitials(normal)
        val final = getFinals(normal) ?: return ""

        // Get initial IPA
        val initialIpa = if (initial != null) INITIAL_MAPPING[initial]?.first() ?: "" else ""

        // Get final IPA (with special cases for zh/ch/sh/r and z/c/s)
        val finalIpa: List<String> = when {
            initial in listOf("zh", "ch", "sh", "r") && final in FINAL_MAPPING_AFTER_ZH_CH_SH_R ->
                FINAL_MAPPING_AFTER_ZH_CH_SH_R[final]!!
            initial in listOf("z", "c", "s") && final in FINAL_MAPPING_AFTER_Z_C_S ->
                FINAL_MAPPING_AFTER_Z_C_S[final]!!
            else -> FINAL_MAPPING[final] ?: return ""
        }

        val result = if (initialIpa.isNotEmpty()) {
            listOf(initialIpa) + finalIpa
        } else {
            finalIpa
        }

        return applyTone(result, toneNr).joinToString("")
    }
}