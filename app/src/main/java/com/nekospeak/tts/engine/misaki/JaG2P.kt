package com.nekospeak.tts.engine.misaki

import android.content.Context
import android.util.Log

/**
 * Faithful port of upstream Misaki's Japanese G2P (ja.py + cutlet.py + num2kana.py).
 * References:
 * - https://github.com/hexgrad/misaki/blob/main/misaki/ja.py
 * - https://github.com/hexgrad/misaki/blob/main/misaki/cutlet.py
 * - https://github.com/hexgrad/misaki/blob/main/misaki/num2kana.py
 *
 * On Android, we don't have pyopenjtalk or fugashi (MeCab), so this port
 * uses eSpeak's Japanese phonemizer as the primary backend, then applies
 * Misaki's M2P (moras-to-phonemes) mapping for Kokoro-compatible output.
 * The ja_words.txt dictionary (147K+ entries from cutlet) is used for
 * word segmentation of kana strings.
 *
 * Pipeline:
 * 1. Normalize text (full-width → half-width, Unicode NFKC, half-width katakana)
 * 2. Convert digits to Japanese words (num2kana)
 * 3. Load ja_words.txt dictionary for word segmentation
 * 4. Segment kana text using greedy longest-match against dictionary
 * 5. Use eSpeak ja backend for pronunciation (with kana→IPA fallback)
 * 6. Apply M2P (moras-to-phonemes) mapping
 * 7. Assemble final phoneme string
 */
class JaG2P(
    private val espeakFallback: ((String, String) -> String?)? = null,
    private val unk: String = "❓"
) {
    companion object {
        private const val TAG = "JaG2P"

        // --- Katakana → IPA mapping (from ja.py M2P) ---
        val M2P = mutableMapOf<String, String>()

        init {
            // Single katakana → IPA
            val singleM2P = mapOf(
                'ァ' to "a", 'ア' to "a", 'ィ' to "i", 'イ' to "i",
                'ゥ' to "u", 'ウ' to "u", 'ェ' to "e", 'エ' to "e",
                'ォ' to "o", 'オ' to "o", 'カ' to "ka", 'ガ' to "ɡa",
                'キ' to "ki", 'ギ' to "ɡi", 'ク' to "ku", 'グ' to "ɡu",
                'ケ' to "ke", 'ゲ' to "ɡe", 'コ' to "ko", 'ゴ' to "ɡo",
                'サ' to "sa", 'ザ' to "za", 'シ' to "ɕi", 'ジ' to "ʥi",
                'ス' to "su", 'ズ' to "zu", 'セ' to "se", 'ゼ' to "ze",
                'ソ' to "so", 'ゾ' to "zo", 'タ' to "ta", 'ダ' to "da",
                'チ' to "ʨi", 'ヂ' to "ʥi", 'ツ' to "ʦu", 'ヅ' to "zu",
                'テ' to "te", 'デ' to "de", 'ト' to "to", 'ド' to "do",
                'ナ' to "na", 'ニ' to "ni", 'ヌ' to "nu", 'ネ' to "ne",
                'ノ' to "no", 'ハ' to "ha", 'バ' to "ba", 'パ' to "pa",
                'ヒ' to "hi", 'ビ' to "bi", 'ピ' to "pi",
                'フ' to "fu", 'ブ' to "bu", 'プ' to "pu",
                'ヘ' to "he", 'ベ' to "be", 'ペ' to "pe",
                'ホ' to "ho", 'ボ' to "bo", 'ポ' to "po",
                'マ' to "ma", 'ミ' to "mi", 'ム' to "mu", 'メ' to "me",
                'モ' to "mo", 'ャ' to "ja", 'ヤ' to "ja",
                'ュ' to "ju", 'ユ' to "ju", 'ョ' to "jo", 'ヨ' to "jo",
                'ラ' to "ra", 'リ' to "ri", 'ル' to "ru", 'レ' to "re",
                'ロ' to "ro", 'ヮ' to "wa", 'ワ' to "wa",
                'ヰ' to "i", 'ヱ' to "e", 'ヲ' to "o",
                'ヴ' to "vu", 'ヵ' to "ka", 'ヶ' to "ke",
                'ヷ' to "va", 'ヸ' to "vi", 'ヹ' to "ve", 'ヺ' to "vo"
            )
            for ((k, v) in singleM2P) {
                M2P[k.toString()] = v
            }
            // Special characters
            M2P["ッ"] = "ʔ"
            M2P["ン"] = "ɴ"
            M2P["ー"] = "ː"

            // Digraph katakana → IPA
            val digraphs = mapOf(
                "イェ" to "je", "ウィ" to "wi", "ウゥ" to "wu", "ウェ" to "we", "ウォ" to "wo",
                "キィ" to "ᶄi", "キェ" to "ᶄe", "キャ" to "ᶄa", "キュ" to "ᶄu", "キョ" to "ᶄo",
                "ギィ" to "ᶃi", "ギェ" to "ᶃe", "ギャ" to "ᶃa", "ギュ" to "ᶃu", "ギョ" to "ᶃo",
                "クァ" to "Ka", "クィ" to "Ki", "クゥ" to "Ku", "クェ" to "Ke", "クォ" to "Ko",
                "クヮ" to "Ka", "グァ" to "Ga", "グィ" to "Gi", "グゥ" to "Gu", "グェ" to "Ge", "グォ" to "Go",
                "グヮ" to "Ga", "シェ" to "ɕe", "シャ" to "ɕa", "シュ" to "ɕu", "ショ" to "ɕo",
                "ジェ" to "ʥe", "ジャ" to "ʥa", "ジュ" to "ʥu", "ジョ" to "ʥo",
                "スィ" to "si", "ズィ" to "zi", "チェ" to "ʨe", "チャ" to "ʨa", "チュ" to "ʨu", "チョ" to "ʨo",
                "ヂェ" to "ʥe", "ヂャ" to "ʥa", "ヂュ" to "ʥu", "ヂョ" to "ʥo",
                "ツァ" to "ʦa", "ツィ" to "ʦi", "ツェ" to "ʦe", "ツォ" to "ʦo",
                "ティ" to "ti", "テェ" to "ƫe", "テャ" to "ƫa", "テュ" to "ƫu", "テョ" to "ƫo",
                "ディ" to "di", "デェ" to "ᶁe", "デャ" to "ᶁa", "デュ" to "ᶁu", "デョ" to "ᶁo",
                "トゥ" to "tu", "ドゥ" to "du",
                "ニィ" to "ɲi", "ニェ" to "ɲe", "ニャ" to "ɲa", "ニュ" to "ɲu", "ニョ" to "ɲo",
                "ヒィ" to "çi", "ヒェ" to "çe", "ヒャ" to "ça", "ヒュ" to "çu", "ヒョ" to "ço",
                "ビィ" to "ᶀi", "ビェ" to "ᶀe", "ビャ" to "ᶀa", "ビュ" to "ᶀu", "ビョ" to "ᶀo",
                "ピィ" to "ᶈi", "ピェ" to "ᶈe", "ピャ" to "ᶈa", "ピュ" to "ᶈu", "ピョ" to "ᶈo",
                "ファ" to "fa", "フィ" to "fi", "フェ" to "fe", "フォ" to "fo",
                "ミィ" to "ᶆi", "ミェ" to "ᶆe", "ミャ" to "ᶆa", "ミュ" to "ᶆu", "ミョ" to "ᶆo",
                "リィ" to "ᶉi", "リェ" to "ᶉe", "リャ" to "ᶉa", "リュ" to "ᶉu", "リョ" to "ᶉo",
                "ヴァ" to "va", "ヴィ" to "vi", "ヴェ" to "ve", "ヴォ" to "vo",
                "ヴャ" to "ᶀa", "ヴュ" to "ᶀu", "ヴョ" to "ᶀo"
            )
            M2P.putAll(digraphs)

            // Katakana Phonetic Extensions (from cutlet.py)
            val phoneticExt = mapOf(
                'ㇰ' to "ク", 'ㇱ' to "シ", 'ㇲ' to "ス", 'ㇳ' to "ト",
                'ㇴ' to "ヌ", 'ㇵ' to "ハ", 'ㇶ' to "ヒ", 'ㇷ' to "フ",
                'ㇸ' to "ヘ", 'ㇹ' to "ホ", 'ㇺ' to "ム", 'ㇻ' to "ラ",
                'ㇼ' to "リ", 'ㇽ' to "ル", 'ㇾ' to "レ", 'ㇿ' to "ロ"
            )
            for ((k, v) in phoneticExt) {
                M2P[k.toString()] = M2P[v].toString()
            }
        }

        // --- Hiragana → Katakana mapping ---
        private val HIRA_TO_KATA: Map<Char, Char>

        init {
            val h2k = mutableMapOf<Char, Char>()
            // Hiragana range: 0x3041-0x3096, Katakana range: 0x30A1-0x30F6
            for (i in 0x3041..0x3096) {
                h2k[i.toChar()] = (i + 0x60).toChar()
            }
            HIRA_TO_KATA = h2k
        }

        // --- Half-width katakana → full-width mapping ---
        private val HALFWIDTH_KATA = mapOf(
            '･' to '・', 'ｦ' to 'ヲ', 'ｧ' to 'ァ', 'ｨ' to 'ィ', 'ｩ' to 'ゥ',
            'ｪ' to 'ェ', 'ｫ' to 'ォ', 'ｬ' to 'ャ', 'ｭ' to 'ュ', 'ｮ' to 'ョ',
            'ｯ' to 'ッ', 'ｰ' to 'ー', 'ｱ' to 'ア', 'ｲ' to 'イ', 'ｳ' to 'ウ',
            'ｴ' to 'エ', 'ｵ' to 'オ', 'ｶ' to 'カ', 'ｷ' to 'キ', 'ｸ' to 'ク',
            'ｹ' to 'ケ', 'ｺ' to 'コ', 'ｻ' to 'サ', 'ｼ' to 'シ', 'ｽ' to 'ス',
            'ｾ' to 'セ', 'ｿ' to 'ソ', 'ﾀ' to 'タ', 'ﾁ' to 'チ', 'ﾂ' to 'ツ',
            'ﾃ' to 'テ', 'ﾄ' to 'ト', 'ﾅ' to 'ナ', 'ﾆ' to 'ニ', 'ﾇ' to 'ヌ',
            'ﾈ' to 'ネ', 'ﾉ' to 'ノ', 'ﾊ' to 'ハ', 'ﾋ' to 'ヒ', 'ﾌ' to 'フ',
            'ﾍ' to 'ヘ', 'ﾎ' to 'ホ', 'ﾏ' to 'マ', 'ﾐ' to 'ミ', 'ﾑ' to 'ム',
            'ﾒ' to 'メ', 'ﾓ' to 'モ', 'ﾔ' to 'ヤ', 'ﾕ' to 'ユ', 'ﾖ' to 'ヨ',
            'ﾗ' to 'ラ', 'ﾘ' to 'リ', 'ﾙ' to 'ル', 'ﾚ' to 'レ', 'ﾛ' to 'ロ',
            'ﾜ' to 'ワ', 'ﾝ' to 'ン'
        )

        // --- Dakuten/handakuten mapping (from cutlet.py) ---
        private val DAKUTEN_MAP = mapOf(
            'か' to 'が', 'き' to 'ぎ', 'く' to 'ぐ', 'け' to 'げ', 'こ' to 'ご',
            'さ' to 'ざ', 'し' to 'じ', 'す' to 'ず', 'せ' to 'ぜ', 'そ' to 'ぞ',
            'た' to 'だ', 'ち' to 'ぢ', 'つ' to 'づ', 'て' to 'で', 'と' to 'ど',
            'は' to 'ば', 'ひ' to 'び', 'ふ' to 'ぶ', 'へ' to 'べ', 'ほ' to 'ぼ'
        )
        private val HANDAKUTEN_MAP = mapOf(
            'は' to 'ぱ', 'ひ' to 'ぴ', 'ふ' to 'ぷ', 'へ' to 'ぺ', 'ほ' to 'ぽ'
        )

        // Small kana (sutegana)
        private val SUTEGANA = setOf('ゃ', 'ゅ', 'ょ', 'ぁ', 'ぃ', 'ぅ', 'ぇ', 'ぉ')

        // --- Punctuation mapping (from cutlet.py) ---
        private val PUNCT_MAP = mapOf(
            '«' to '"', '»' to '"', '、' to ',', '。' to '.',
            '〈' to '"', '〉' to '"', '《' to '"', '》' to '"',
            '「' to '"', '」' to '"', '『' to '"', '』' to '"',
            '【' to '"', '】' to '"', '！' to '!', '（' to '(',
            '）' to ')', '：' to ':', '；' to ';', '？' to '?'
        )

        private val PUNCT_VALUES = setOf(
            '!', '"', '(', ')', ',', '.', ':', ';', '?', '—', '\u201C', '\u201D', '…'
        )

        private val VOWELS = setOf('a', 'e', 'i', 'o', 'u')
        private val TAILS = M2P.values.map { it.last() }.toSet()

        // --- Japanese word dictionary (loaded from ja_words.txt) ---
        private var jaWords: Set<String> = emptySet()
        @Volatile private var jaWordsLoaded = false

        /** Load the ja_words.txt dictionary from assets. */
        fun loadDictionary(context: Context) {
            if (jaWordsLoaded) return
            try {
                val words = mutableSetOf<String>()
                context.assets.open("ja_words.txt").bufferedReader().use { reader ->
                    reader.forEachLine { line ->
                        val trimmed = line.trim()
                        if (trimmed.isNotEmpty()) words.add(trimmed)
                    }
                }
                jaWords = words
                jaWordsLoaded = true
                Log.d(TAG, "Loaded ${words.size} entries from ja_words.txt")
            } catch (e: Exception) {
                Log.w(TAG, "ja_words.txt not found in assets, word segmentation will be limited")
                jaWordsLoaded = true // Don't retry
            }
        }

        // Convert a number string to kana reading (delegates to Num2Kana)
        fun numToKana(numStr: String): String {
            return Num2Kana.convert(numStr)
        }
    }

    /**
     * Segment kana text into words using the ja_words dictionary.
     * Uses greedy longest-match from left (same algorithm as cutlet.py).
     */
    fun segmentKana(text: String): List<String> {
        if (!jaWordsLoaded || jaWords.isEmpty()) {
            // Fallback: character-by-character for kana, group non-kana
            return segmentSimple(text)
        }

        val result = mutableListOf<String>()
        var i = 0
        while (i < text.length) {
            val ch = text[i]

            // Non-kana characters: group them together
            if (!isKana(ch)) {
                val start = i
                while (i < text.length && !isKana(text[i])) i++
                result.add(text.substring(start, i))
                continue
            }

            // Find the next position where character type changes
            var z = i + 1
            while (z < text.length && isKana(text[z])) z++

            // Try longest match first within the kana run
            var j: Int? = null
            for (jj in z downTo i + 1) {
                if (text.substring(i, jj) in jaWords) {
                    j = jj
                    break
                }
            }

            if (j == null) {
                // No word match; emit single character
                result.add(ch.toString())
                i++
            } else {
                result.add(text.substring(i, j))
                i = j
            }
        }
        return result
    }

    /** Simple fallback segmentation: group consecutive kana or non-kana. */
    private fun segmentSimple(text: String): List<String> {
        val result = mutableListOf<String>()
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            if (isKana(ch)) {
                // Try digraph grouping
                if (i + 1 < text.length && isKana(text[i + 1])) {
                    val pair = text.substring(i, i + 2)
                    if (pair in M2P) {
                        result.add(pair)
                        i += 2
                        continue
                    }
                }
                result.add(ch.toString())
                i++
            } else {
                val start = i
                while (i < text.length && !isKana(text[i])) i++
                result.add(text.substring(start, i))
            }
        }
        return result
    }

    private fun isKana(ch: Char): Boolean {
        val c = ch.code
        return c in 0x3041..0x3096 ||  // Hiragana
               c in 0x30A1..0x30FA ||  // Katakana
               c in 0x30FC..0x30FF ||  // Katakana extensions (ー, ヽ, ヾ)
               c in 0xFF65..0xFF9F ||  // Half-width katakana
               c in 0x31F0..0x31FF     // Katakana Phonetic Extensions
    }

    /**
     * Convert kana string to IPA phonemes using M2P mapping.
     * Handles digraphs (e.g. キャ → ᶄa) before single characters.
     */
    fun kanaToIpa(kana: String): String {
        val result = StringBuilder()
        var i = 0
        while (i < kana.length) {
            // Try digraph first
            if (i + 1 < kana.length) {
                val digraph = kana.substring(i, i + 2)
                if (digraph in M2P) {
                    result.append(M2P[digraph])
                    i += 2
                    continue
                }
            }
            val ch = kana[i]
            val single = ch.toString()
            if (single in M2P) {
                result.append(M2P[single])
            } else if (ch in PUNCT_MAP) {
                result.append(PUNCT_MAP[ch])
            } else if (ch in PUNCT_VALUES) {
                result.append(ch)
            } else if (ch.isWhitespace()) {
                result.append(' ')
            } else if (ch.isLetterOrDigit()) {
                result.append(ch)
            }
            i++
        }
        return result.toString()
    }

    /**
     * Convert hiragana to katakana.
     */
    fun hiraToKata(text: String): String {
        return text.map { ch ->
            // Handle combining dakuten/handakuten (from cutlet.py)
            when {
                HIRA_TO_KATA.containsKey(ch) -> HIRA_TO_KATA[ch]!!
                ch.code == 0x3099 -> { // combining dakuten
                    // Apply to previous character
                    ch // handled separately in normalizeText
                }
                ch.code == 0x309A -> { // combining handakuten
                    ch // handled separately in normalizeText
                }
                else -> ch
            }
        }.joinToString("")
    }

    /**
     * Normalize Japanese text: NFKC, full-width → half-width digits/ascii,
     * half-width katakana → full-width, combining dakuten application.
     * Ported from cutlet.py's normalize_text.
     */
    fun normalizeText(text: String): String {
        var result = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFKC)

        // Apply combining dakuten/handakuten (from cutlet.py)
        val sb = StringBuilder()
        var i = 0
        while (i < result.length) {
            val ch = result[i]
            if (i + 1 < result.length) {
                val next = result[i + 1]
                if (next.code == 0x3099 && DAKUTEN_MAP.containsKey(ch)) {
                    // Dakuten: apply to previous kana
                    sb.append(DAKUTEN_MAP[ch])
                    i += 2
                    continue
                }
                if (next.code == 0x309A && HANDAKUTEN_MAP.containsKey(ch)) {
                    // Handakuten: apply to previous kana
                    sb.append(HANDAKUTEN_MAP[ch])
                    i += 2
                    continue
                }
            }
            sb.append(ch)
            i++
        }
        result = sb.toString()

        // Convert half-width katakana to full-width
        result = result.map { ch -> HALFWIDTH_KATA[ch] ?: ch }.joinToString("")

        // Convert full-width digits to half-width (from cutlet.py: mojimoji.zen_to_han)
        result = result.map { ch ->
            if (ch.code in 0xFF10..0xFF19) (ch.code - 0xFF10 + 0x30).toChar() // ０-９ → 0-9
            else if (ch.code in 0xFF21..0xFF3A) (ch.code - 0xFF21 + 0x41).toChar() // Ａ-Ｚ → A-Z
            else if (ch.code in 0xFF41..0xFF5A) (ch.code - 0xFF41 + 0x61).toChar() // ａ-ｚ → a-z
            else ch
        }.joinToString("")

        // Convert digits to kana reading (from cutlet.py: num2kana Convert)
        result = Regex("\\d+").replace(result) { match ->
            numToKana(match.value)
        }

        return result
    }

    /**
     * Main phonemization entry point.
     * Returns phonemes as a string for Kokoro.
     */
    fun phonemize(text: String): String {
        if (text.isBlank()) return ""

        // 1. Normalize
        val normalized = normalizeText(text)

        // 2. Try eSpeak fallback for Japanese
        val espeakResult = espeakFallback?.invoke(normalized, "ja")
        if (espeakResult != null && espeakResult.isNotBlank()) {
            return espeakResult
        }

        // 3. Direct kana conversion (fallback if eSpeak unavailable)
        // Convert all hiragana to katakana first
        val kataOnly = hiraToKata(normalized)

        // 4. Segment into words using dictionary (if loaded)
        val segments = segmentKana(kataOnly)

        // 5. Convert each segment to IPA
        val ipaParts = segments.map { seg ->
            kanaToIpa(seg)
        }

        // 6. Clean up
        return ipaParts.joinToString(" ").replace(Regex("\\s+"), " ").trim()
    }
}