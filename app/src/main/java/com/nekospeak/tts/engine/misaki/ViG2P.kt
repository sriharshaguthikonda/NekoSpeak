package com.nekospeak.tts.engine.misaki

import android.util.Log
import java.util.Locale

/**
 * Faithful port of upstream Misaki's Vietnamese G2P (vi.py + vi_cleaner/).
 * References:
 * - https://github.com/hexgrad/misaki/blob/main/misaki/vi.py
 *
 * Ported as pure Kotlin without underthesea, spacy, or the vi_cleaner package.
 * Replaces:
 * - underthesea word tokenizer → simple space/punctuation splitting
 * - ViCleaner → basic Vietnamese text normalization
 * - spacy → not needed (no English detection used)
 *
 * The core phonology engine (onset/nucleus/coda/tone mapping) is a complete
 * faithful port of the upstream vi.py `trans()` and `convert()` functions.
 */
class ViG2P(
    private val enG2P: ((String) -> String)? = null,
    private val espeakFallback: ((String, String) -> String?)? = null,
    private val unk: String = "❓",
    private val cleaner: ViCleaner = ViCleaner()
) {
    companion object {
        private const val TAG = "ViG2P"

        // --- Onset mapping ---
        private val ONSETS = mapOf(
            "b" to "b", "t" to "t", "th" to "tʰ", "đ" to "d", "ch" to "c",
            "kh" to "x", "g" to "ɣ", "l" to "l", "m" to "m", "n" to "n",
            "ngh" to "ŋ", "nh" to "ɲ", "ng" to "ŋ", "ph" to "f", "v" to "v",
            "x" to "s", "d" to "z", "h" to "h", "p" to "p", "qu" to "kw",
            "gi" to "j", "tr" to "ʈ", "k" to "k", "c" to "k", "gh" to "ɣ",
            "r" to "ʐ", "s" to "ʂ",
            // Old/mixed alphabet
            "f" to "f", "j" to "j", "w" to "w", "z" to "z"
        )

        // --- Nucleus mapping ---
        private val NUCLEI = mapOf(
            "a" to "a", "á" to "a", "à" to "a", "ả" to "a", "ã" to "a", "ạ" to "a",
            "â" to "ɤ̆", "ấ" to "ɤ̆", "ầ" to "ɤ̆", "ẩ" to "ɤ̆", "ẫ" to "ɤ̆", "ậ" to "ɤ̆",
            "ă" to "ă", "ắ" to "ă", "ằ" to "ă", "ẳ" to "ă", "ẵ" to "ă", "ặ" to "ă",
            "e" to "ɛ", "é" to "ɛ", "è" to "ɛ", "ẻ" to "ɛ", "ẽ" to "ɛ", "ẹ" to "ɛ",
            "ê" to "e", "ế" to "e", "ề" to "e", "ể" to "e", "ễ" to "e", "ệ" to "e",
            "i" to "i", "í" to "i", "ì" to "i", "ỉ" to "i", "ĩ" to "i", "ị" to "i",
            "o" to "ɔ", "ó" to "ɔ", "ò" to "ɔ", "ỏ" to "ɔ", "õ" to "ɔ", "ọ" to "ɔ",
            "ô" to "o", "ố" to "o", "ồ" to "o", "ổ" to "o", "ỗ" to "o", "ộ" to "o",
            "ơ" to "ɤ", "ớ" to "ɤ", "ờ" to "ɤ", "ở" to "ɤ", "ỡ" to "ɤ", "ợ" to "ɤ",
            "u" to "u", "ú" to "u", "ù" to "u", "ủ" to "u", "ũ" to "u", "ụ" to "u",
            "ư" to "ɯ", "ứ" to "ɯ", "ừ" to "ɯ", "ử" to "ɯ", "ữ" to "ɯ", "ự" to "ɯ",
            "y" to "i", "ý" to "i", "ỳ" to "i", "ỷ" to "i", "ỹ" to "i", "ỵ" to "i",
            // Diphthongs / combined nuclei
            "eo" to "eo", "êu" to "ɛu", "ia" to "iə", "iê" to "iə",
            "ua" to "uə", "uô" to "uə", "ưa" to "ɯə", "ươ" to "ɯə",
            "yê" to "iɛ"
        )

        // --- Off-glide mapping ---
        private val OFFGLIDES = mapOf(
            "ai" to "aj", "ay" to "ăj", "ao" to "aw", "au" to "ăw",
            "ây" to "ɤ̆j", "âu" to "ɤ̆w",
            "iu" to "iw", "oi" to "ɔj", "ôi" to "oj", "ui" to "uj",
            "uy" to "ʷi", "ơi" to "ɤj", "ưi" to "ɯj", "ưu" to "ɯw",
            "iêu" to "iəw", "yêu" to "iəw",
            "uôi" to "uəj", "ươi" to "ɯəj", "ươu" to "ɯəw"
        )

        // --- On-glide mapping ---
        private val ONGLIDES = mapOf(
            "oa" to "ʷa", "oă" to "ʷă", "oe" to "ʷɛ", "uy" to "ʷi",
            "uyê" to "ʷiə", "uyu" to "ʷiu"
        )

        // --- On-off glide mapping ---
        private val ONOFFGLIDES = mapOf(
            "oai" to "aj", "oay" to "ăj", "oao" to "aw",
            "uai" to "aj", "uay" to "ăj", "uây" to "ɤ̆j"
        )

        // --- Coda mapping ---
        private val CODAS = mapOf(
            "p" to "p", "t" to "t", "c" to "k", "m" to "m", "n" to "n",
            "ng" to "ŋ", "nh" to "ɲ", "ch" to "tʃ", "k" to "k"
        )

        // --- Tone mapping (Phạm system) ---
        private val TONES = mapOf(
            'á' to 5, 'à' to 2, 'ả' to 4, 'ã' to 3, 'ạ' to 6,
            'ấ' to 5, 'ầ' to 2, 'ẩ' to 4, 'ẫ' to 3, 'ậ' to 6,
            'ắ' to 5, 'ằ' to 2, 'ẳ' to 4, 'ẵ' to 3, 'ặ' to 6,
            'é' to 5, 'è' to 2, 'ẻ' to 4, 'ẽ' to 3, 'ẹ' to 6,
            'ế' to 5, 'ề' to 2, 'ể' to 4, 'ễ' to 3, 'ệ' to 6,
            'í' to 5, 'ì' to 2, 'ỉ' to 4, 'ĩ' to 3, 'ị' to 6,
            'ó' to 5, 'ò' to 2, 'ỏ' to 4, 'õ' to 3, 'ọ' to 6,
            'ố' to 5, 'ồ' to 2, 'ổ' to 4, 'ỗ' to 3, 'ộ' to 6,
            'ớ' to 5, 'ờ' to 2, 'ở' to 4, 'ỡ' to 3, 'ợ' to 6,
            'ú' to 5, 'ù' to 2, 'ủ' to 4, 'ũ' to 3, 'ụ' to 6,
            'ứ' to 5, 'ừ' to 2, 'ử' to 4, 'ữ' to 3, 'ự' to 6,
            'ý' to 5, 'ỳ' to 2, 'ỷ' to 4, 'ỹ' to 3, 'ỵ' to 6
        )

        // --- GI special cases ---
        private val GI_MAP = mapOf("gi" to "zi")

        // --- QU special cases ---
        private val QU_MAP = mapOf("quy" to "kwi")

        // --- Letter pronunciation ---
        private val EN_LETTERS = mapOf(
            "a" to "ây", "b" to "bi", "c" to "si", "d" to "đi", "e" to "i",
            "f" to "ép", "g" to "giy", "h" to "hếch", "i" to "ai", "j" to "giây",
            "k" to "cây", "l" to "eo", "m" to "em", "n" to "en", "o" to "âu",
            "p" to "pi", "q" to "kiu", "r" to "a", "s" to "ét", "t" to "ti",
            "u" to "diu", "v" to "vi", "w" to "đắp liu", "x" to "ít", "y" to "quai", "z" to "giét"
        )

        private val VI_LETTERS = mapOf(
            "a" to "a", "ă" to "á", "â" to "ớ", "b" to "bê", "c" to "cê",
            "d" to "dê", "đ" to "đê", "e" to "e", "ê" to "ê", "f" to "phờ",
            "g" to "gờ", "h" to "hờ", "i" to "i", "j" to "giây", "k" to "ka",
            "l" to "lờ", "m" to "mờ", "n" to "nờ", "o" to "o", "ô" to "ô",
            "ơ" to "ơ", "p" to "pờ", "q" to "quy", "r" to "rờ", "s" to "sờ",
            "t" to "tờ", "u" to "u", "ư" to "ư", "v" to "vi", "w" to "gờ",
            "x" to "xờ", "y" to "i", "z" to "gia"
        )

        private val VI_CHARS = setOf(
            'ă', 'â', 'đ', 'ê', 'ô', 'ơ', 'ư', 'á', 'à', 'ả', 'ã', 'ạ',
            'ắ', 'ằ', 'ẳ', 'ẵ', 'ặ', 'ấ', 'ầ', 'ẩ', 'ẫ', 'ậ', 'é', 'è',
            'ẻ', 'ẽ', 'ẹ', 'ế', 'ề', 'ể', 'ễ', 'ệ', 'í', 'ì', 'ỉ', 'ĩ',
            'ị', 'ó', 'ò', 'ỏ', 'õ', 'ọ', 'ố', 'ồ', 'ổ', 'ỗ', 'ộ', 'ớ',
            'ờ', 'ở', 'ỡ', 'ợ', 'ú', 'ù', 'ủ', 'ũ', 'ụ', 'ứ', 'ừ', 'ử',
            'ữ', 'ự', 'ý', 'ỳ', 'ỷ', 'ỹ', 'ỵ'
        )
    }

    private val dialect: String = "n" // north

    /**
     * Convert a single Vietnamese syllable to IPA.
     * Faithful port of upstream vi.py `trans()` + `convert()`.
     */
    fun convert(word: String, glottal: Int = 0, pham: Int = 1, cao: Int = 0, palatals: Int = 0): String {
        var ons = ""
        var nuc = ""
        var cod = ""
        var ton = 0
        var oOffset = 0
        var cOffset = 0
        val l = word.length

        if (l == 0) return ""

        // Find onset
        if (l >= 3 && word.substring(0, 3) in ONSETS) {
            ons = ONSETS[word.substring(0, 3)]!!
            oOffset = 3
        } else if (l >= 2 && word.substring(0, 2) in ONSETS) {
            ons = ONSETS[word.substring(0, 2)]!!
            oOffset = 2
        } else if (word[0].toString() in ONSETS) {
            ons = ONSETS[word[0].toString()]!!
            oOffset = 1
        }

        // Find coda
        if (l >= 2 && word.substring(l - 2) in CODAS) {
            cod = CODAS[word.substring(l - 2)]!!
            cOffset = 2
        } else if (l >= 1 && word[l - 1].toString() in CODAS) {
            cod = CODAS[word[l - 1].toString()]!!
            cOffset = 1
        }

        // Find nucleus
        if (word.substring(0, 2) in GI_MAP && cod.isNotEmpty() && l == 3) {
            nuc = "i"
            ons = "z"
        } else {
            val nucl = if (oOffset + cOffset <= l) word.substring(oOffset, l - cOffset) else ""
            if (nucl in NUCLEI) {
                if (oOffset == 0 && glottal == 1 && word[0].toString() !in ONSETS) {
                    ons = "ʔ" + NUCLEI[nucl]!!
                }
                nuc = NUCLEI[nucl]!!
            } else if (nucl in ONGLIDES && ons != "kw") {
                nuc = ONGLIDES[nucl]!!
                if (ons.isNotEmpty()) ons += "w" else ons = "w"
            } else if (nucl in ONGLIDES && ons == "kw") {
                nuc = ONGLIDES[nucl]!!
            } else if (nucl in ONOFFGLIDES) {
                cod = ONOFFGLIDES[nucl]!!.last().toString()
                nuc = ONOFFGLIDES[nucl]!!.dropLast(1)
                if (ons != "kw") {
                    if (ons.isNotEmpty()) ons += "w" else ons = "w"
                }
            } else if (nucl in OFFGLIDES) {
                cod = OFFGLIDES[nucl]!!.last().toString()
                nuc = OFFGLIDES[nucl]!!.dropLast(1)
            } else if (word in GI_MAP) {
                ons = GI_MAP[word]!![0].toString()
                nuc = GI_MAP[word]!![1].toString()
            } else if (word in QU_MAP) {
                ons = QU_MAP[word]!!.dropLast(1)
                nuc = QU_MAP[word]!!.last().toString()
            } else {
                return "" // Unknown
            }
        }

        // Velar fronting (northern)
        if (dialect == "n") {
            if (nuc == "a") {
                if (cod == "k" && cOffset == 2) nuc = "ɛ"
                if (cod == "ɲ" && nuc == "a") nuc = "ɛ"
            }
            if (palatals == 1 && nuc in listOf("i", "e", "ɛ") && cod == "k") {
                cod = "c"
            }
        } else {
            // Southern/central
            if (nuc in listOf("i", "e")) {
                if (cod == "k") cod = "t"
                if (cod == "ŋ") cod = "n"
            } else if (nuc in listOf("iə", "ɯə", "uə", "u", "ɯ", "ɤ", "o", "ɔ", "ă", "ɤ̆")) {
                if (cod == "t") cod = "k"
                if (cod == "n") cod = "ŋ"
            }
            // Monophthongization (southern)
            if (dialect == "s" && cod in listOf("m", "p")) {
                if (nuc == "iə") nuc = "i"
                if (nuc == "uə") nuc = "u"
                if (nuc == "ɯə") nuc = "ɯ"
            }
        }

        // Tone detection
        val toneChars = word.filter { it in TONES }
        ton = if (toneChars.isNotEmpty()) TONES[toneChars.last()] ?: 1 else 1

        // Labialized allophony
        if (cOffset != 0 && nuc in listOf("u", "o", "ɔ")) {
            if (cod == "ŋ") cod = "ŋ͡m"
            if (cod == "k") cod = "k͡p"
        }

        return listOf(ons, nuc, cod, ton.toString()).joinToString("/")
    }

    /**
     * Main phonemization entry point.
     */
    fun phonemize(text: String): String {
        if (text.isBlank()) return ""

        // Try eSpeak fallback first
        val espeakResult = espeakFallback?.invoke(text, "vi")
        if (espeakResult != null && espeakResult.isNotBlank()) {
            return espeakResult
        }

        // Apply full ViCleaner normalization pipeline
        // (abbreviations, acronyms, roman numerals, dates, measurements,
        //  currency, numbers, letters, etc.)
        var processed = cleaner.cleanText(text)

        // Simple word tokenization (split by spaces)
        val tokens = processed.split(Regex("\\s+")).filter { it.isNotEmpty() }

        val result = mutableListOf<String>()
        for (tk in tokens) {
            // Handle punctuation
            if (tk in listOf(".", ",", ";", ":", "!", "?", ")", "}", "]")) {
                result.add(tk.replace(')', ')'))
                continue
            }
            if (tk in listOf("(", "{", "[")) {
                result.add("(")
                continue
            }
            if (tk in listOf("\"", "'", "–", "\u201C", "\u201D")) {
                result.add("\"")
                continue
            }

            // Try direct Vietnamese conversion
            val firstTry = convert(tk)
            if (firstTry.isNotEmpty() && !firstTry.contains("[")) {
                val parts = firstTry.split("/")
                val ipa = parts.take(3).joinToString("") + parts.getOrElse(3) { "" }
                result.add(ipa)
            } else {
                // Substring fallback for foreign words
                val lower = tk.lowercase(Locale.ROOT)
                val hasViChars = lower.any { it in VI_CHARS }

                if (!hasViChars && lower.matches(Regex("[a-z]+"))) {
                    // Try English G2P
                    val engResult = enG2P?.invoke(lower)
                    if (engResult != null && unk !in engResult) {
                        result.add(engResult)
                        continue
                    }
                }

                // Letter-by-letter fallback for acronyms
                if (lower.uppercase(Locale.ROOT) == lower && lower.length <= 6) {
                    val mapping = if (hasViChars) VI_LETTERS else EN_LETTERS
                    for (ch in lower) {
                        val letterIpa = convert(mapping[ch.toString()] ?: ch.toString())
                        if (letterIpa.isNotEmpty()) result.add(letterIpa.replace("/", ""))
                        else result.add(ch.toString())
                    }
                } else {
                    result.add(unk)
                }
            }
        }

        return result.joinToString(" ")
    }
}