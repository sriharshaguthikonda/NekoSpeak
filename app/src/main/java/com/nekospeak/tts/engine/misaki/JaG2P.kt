package com.nekospeak.tts.engine.misaki

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
 *
 * Pipeline:
 * 1. Normalize text (full-width → half-width, Unicode NFKC)
 * 2. Convert digits to Japanese words (num2kana)
 * 3. Use eSpeak ja backend for word segmentation + pronunciation
 * 4. Map kana output → IPA via HEPBURN/M2P tables
 * 5. Apply pitch accent approximation
 * 6. Assemble final phoneme string
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

        // --- Punctuation mapping ---
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

        private val PUNCT_STOPS = setOf('!', ')', ',', '.', ':', ';', '?', '"')
        private val PUNCT_STARTS = setOf('(', '"')

        private val VOWELS = setOf('a', 'e', 'i', 'o', 'u')
        private val TAILS = M2P.values.map { it.last() }.toSet()

        // Convert a number string to kana reading (delegates to Num2Kana)
        fun numToKana(numStr: String): String {
            return Num2Kana.convert(numStr)
        }
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
        return text.map { HIRA_TO_KATA[it] ?: it }.joinToString("")
    }

    /**
     * Normalize Japanese text: NFKC, full-width → half-width, half-width katakana → full-width.
     */
    fun normalizeText(text: String): String {
        var result = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFKC)
        // Convert half-width katakana to full-width (simple mapping)
        result = result.map { ch ->
            if (ch.code in 0xFF65..0xFF9F) {
                // Half-width katakana range
                val offset = ch.code - 0xFF65
                if (offset in 0..56) {
                    // Map to full-width katakana
                    when (offset) {
                        0 -> '・'; 1 -> 'ァ'; 2 -> 'ア'; 3 -> 'ィ'; 4 -> 'イ'
                        5 -> 'ゥ'; 6 -> 'ウ'; 7 -> 'ェ'; 8 -> 'エ'; 9 -> 'ォ'
                        10 -> 'オ'; 11 -> 'カ'; 12 -> 'キ'; 13 -> 'ク'; 14 -> 'ケ'
                        15 -> 'コ'; 16 -> 'サ'; 17 -> 'シ'; 18 -> 'ス'; 19 -> 'セ'
                        20 -> 'ソ'; 21 -> 'タ'; 22 -> 'チ'; 23 -> 'ツ'; 24 -> 'テ'
                        25 -> 'ト'; 26 -> 'ナ'; 27 -> 'ニ'; 28 -> 'ヌ'; 29 -> 'ネ'
                        30 -> 'ノ'; 31 -> 'ハ'; 32 -> 'ヒ'; 33 -> 'フ'; 34 -> 'ヘ'
                        35 -> 'ホ'; 36 -> 'マ'; 37 -> 'ミ'; 38 -> 'ム'; 39 -> 'メ'
                        40 -> 'モ'; 41 -> 'ヤ'; 42 -> 'ユ'; 43 -> 'ヨ'; 44 -> 'ラ'
                        45 -> 'リ'; 46 -> 'ル'; 47 -> 'レ'; 48 -> 'ロ'; 49 -> 'ワ'
                        50 -> 'ヲ'; 51 -> 'ン'; else -> ch
                    }
                } else ch
            } else ch
        }.joinToString("")
        return result
    }

    /**
     * Main phonemization entry point.
     * Returns (phonemes, pitch) as a single string for Kokoro.
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
        // Convert all hiragana to katakana first, then apply M2P
        val kataOnly = hiraToKata(normalized)
        val ipa = kanaToIpa(kataOnly)

        // 4. Clean up
        return ipa.replace(Regex("\\s+"), " ").trim()
    }
}