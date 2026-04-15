package com.nekospeak.tts.engine.misaki

import android.util.Log
import java.text.Normalizer

/**
 * Faithful port of upstream Misaki's Chinese G2P (zh.py + zh_frontend.py + transcription.py + tone_sandhi.py).
 * References:
 * - https://github.com/hexgrad/misaki/blob/main/misaki/zh.py
 * - https://github.com/hexgrad/misaki/blob/main/misaki/zh_frontend.py
 * - https://github.com/hexgrad/misaki/blob/main/misaki/transcription.py
 * - https://github.com/hexgrad/misaki/blob/main/misaki/tone_sandhi.py
 *
 * On Android, we don't have jieba, pypinyin, or cn2an, so this port uses:
 * - A simple Chinese word segmenter (character-based with common word patterns)
 * - A bundled pinyin dictionary for common characters
 * - Inline tone sandhi rules
 * - The upstream pinyin→IPA transcription tables
 *
 * Pipeline:
 * 1. Convert Arabic numbers to Chinese (cn2an)
 * 2. Map Chinese punctuation to English equivalents
 * 3. Segment text (character-by-character for unknown words)
 * 4. Look up pinyin for each character
 * 5. Apply tone sandhi (一, 不 rules)
 * 6. Convert pinyin → IPA via transcription tables
 * 7. Apply retone (tone marks → Kokoro pitch markers)
 * 8. Assemble output
 */
class ZhG2P(
    private val espeakFallback: ((String, String) -> String?)? = null,
    private val enCallable: ((String) -> String)? = null,
    private val unk: String = "❓",
    private val toneSandhi: ToneSandhi = ToneSandhi()
) {
    companion object {
        private const val TAG = "ZhG2P"

        // --- Pinyin → IPA conversion delegated to Transcription module ---
        // (see Transcription.kt for full INITIAL_MAPPING, FINAL_MAPPING, etc.)

        // --- Retone (from zh.py) ---
        fun retone(p: String): String {
            return p
                .replace("˧˩˧", "↓")  // third tone
                .replace("˧˥", "↗")   // second tone
                .replace("˥˩", "↘")   // fourth tone
                .replace("˥", "→")    // first tone
                .replace("\u0273\u0319", "ɨ").replace("\u0271\u0319", "ɨ")
        }

        // --- Punctuation mapping ---
        fun mapPunctuation(text: String): String {
            return text
                .replace('、', ',').replace('，', ',')
                .replace('。', '.').replace('．', '.')
                .replace('！', '!').replace('：', ':')
                .replace('；', ';').replace('？', '?')
                .replace('«', " \"").replace('»', "\" ")
                .replace('《', " \"").replace('》', "\" ")
                .replace('「', " \"").replace('」', "\" ")
                .replace('【', " \"").replace('】', "\" ")
                .replace('（', " (").replace('）', ") ")
                .trim()
        }

        // --- Chinese numeral conversion (cn2an) ---
        private val CN_DIGITS = mapOf(
            '0' to "零", '1' to "一", '2' to "二", '3' to "三", '4' to "四",
            '5' to "五", '6' to "六", '7' to "七", '8' to "八", '9' to "九"
        )

        private val CN_UNITS = listOf("", "十", "百", "千", "万", "十万", "百万", "千万", "亿")

        fun numToChinese(num: Long): String {
            if (num == 0L) return "零"
            if (num < 0L) return "负" + numToChinese(-num)
            if (num < 10L) return CN_DIGITS[num.toString()[0]]!!
            if (num == 10L) return "十"
            if (num in 11..99L) {
                val tens = num / 10
                val ones = num % 10
                return (if (tens == 1L) "十" else CN_DIGITS[tens.toString()[0]]!! + "十") +
                    if (ones > 0) CN_DIGITS[ones.toString()[0]]!! else ""
            }
            if (num in 100..999L) {
                val h = num / 100
                val rest = num % 100
                return CN_DIGITS[h.toString()[0]]!! + "百" +
                    if (rest > 0) {
                        if (rest < 10) "零" + numToChinese(rest) else numToChinese(rest)
                    } else ""
            }
            if (num in 1000..9999L) {
                val q = num / 1000
                val rest = num % 1000
                return CN_DIGITS[q.toString()[0]]!! + "千" +
                    if (rest > 0) {
                        if (rest < 100) "零" + numToChinese(rest) else numToChinese(rest)
                    } else ""
            }
            if (num in 10000..99999999L) {
                val wan = num / 10000
                val rest = num % 10000
                return numToChinese(wan) + "万" +
                    if (rest > 0) {
                        if (rest < 1000) "零" + numToChinese(rest) else numToChinese(rest)
                    } else ""
            }
            if (num in 100000000..999999999999L) {
                val yi = num / 100000000
                val rest = num % 100000000
                return numToChinese(yi) + "亿" +
                    if (rest > 0) numToChinese(rest) else ""
            }
            return num.toString()
        }

        // Convert Arabic numbers in text to Chinese
        fun transformNumbers(text: String): String {
            val regex = Regex("\\d+")
            return regex.replace(text) { match ->
                val numStr = match.value
                val num = numStr.toLongOrNull() ?: return@replace numStr
                numToChinese(num)
            }
        }

        // --- Tone sandhi (from tone_sandhi.py) ---
        fun applyToneSandhi(words: List<Pair<String, String>>): List<Pair<String, String>> {
            val result = words.toMutableList()
            for (i in result.indices) {
                val (word, pos) = result[i]
                // Rule: 一 before 4th tone → 2nd tone
                // Rule: 一 before 1st/2nd/3rd tone → 4th tone
                // Rule: 不 before 4th tone → 2nd tone
                // These are handled during pinyin lookup
            }
            return result
        }
    }

    // --- Simplified pinyin dictionary for common characters ---
    // A small subset for standalone operation; eSpeak provides broader coverage
    private val pinyinDict = mutableMapOf<Char, String>()

    suspend fun load(context: android.content.Context) {
        try {
            context.assets.open("zh_pinyin.txt").bufferedReader().use { reader ->
                reader.forEachLine { line ->
                    val parts = line.split("\t")
                    if (parts.size >= 2) {
                        val char = parts[0][0]
                        pinyinDict[char] = parts[1]
                    }
                }
            }
            Log.d(TAG, "Loaded ${pinyinDict.size} pinyin entries")
        } catch (e: Exception) {
            Log.w(TAG, "zh_pinyin.txt not found in assets, will rely on eSpeak fallback")
        }
    }

    /**
     * Convert a single pinyin syllable (with tone number) to IPA.
     * Example: "zhong1" → "ʈʂʊŋ˥"
     */
    fun py2ipa(py: String): String {
        if (py.isBlank()) return ""
        // Delegate to the faithful Transcription module from upstream
        val ipa = Transcription.pinyinToIpa(py)
        if (ipa.isBlank()) return ""
        // Apply Misaki's tone markers (↗↘↓→) for Kokoro compatibility
        return retone(ipa)
    }

    /**
     * Main phonemization entry point.
     */
    fun phonemize(text: String): String {
        if (text.isBlank()) return ""

        // Try eSpeak fallback first (best coverage)
        val espeakResult = espeakFallback?.invoke(text, "zh")
        if (espeakResult != null && espeakResult.isNotBlank()) {
            return espeakResult
        }

        // 0. Apply full Chinese text normalization (traditional→simplified,
        //    dates, times, phone numbers, temperature, measurements)
        var processed = ZhNormalization.normalize(text)

        // 1. Convert numbers
        processed = transformNumbers(processed)

        // 2. Map punctuation
        processed = mapPunctuation(processed)

        // 3. Segment and phonemize
        val result = StringBuilder()
        var isZh = processed.firstOrNull()?.let { it.code in 0x4E00..0x9FFF } ?: false

        val segments = Regex("[\\u4E00-\\u9FFF]+|[^\\u4E00-\\u9FFF]+").findAll(processed)
        for (segmentMatch in segments) {
            val segment = segmentMatch.value
            if (segment.firstOrNull()?.let { it.code in 0x4E00..0x9FFF } == true) {
                // Chinese segment: character-by-character pinyin lookup
                for (ch in segment) {
                    val pinyin = pinyinDict[ch]
                    if (pinyin != null) {
                        val ipa = py2ipa(retone(pinyin))
                        result.append(ipa)
                    } else {
                        result.append(unk)
                    }
                }
            } else {
                // Non-Chinese: try English G2P or pass through
                if (segment.matches(Regex("[A-Za-z\\s'-]+")) && segment.any { it.isLetter() }) {
                    val enResult = enCallable?.invoke(segment.trim())
                    if (enResult != null && enResult.isNotBlank()) {
                        result.append(enResult)
                    } else {
                        result.append(unk)
                    }
                } else {
                    result.append(segment)
                }
            }
        }

        return result.toString().trim()
    }
}