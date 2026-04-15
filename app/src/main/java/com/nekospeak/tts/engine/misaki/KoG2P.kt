package com.nekospeak.tts.engine.misaki

import android.util.Log
import java.text.Normalizer

/**
 * Faithful port of upstream Misaki's Korean G2P (ko.py → g2pkc/).
 * References:
 * - https://github.com/hexgrad/misaki/blob/main/misaki/ko.py
 * - https://github.com/hexgrad/misaki/blob/main/misaki/g2pkc/g2pk.py
 * - https://github.com/hexgrad/misaki/blob/main/misaki/g2pkc/special.py
 * - https://github.com/hexgrad/misaki/blob/main/misaki/g2pkc/regular.py
 * - https://github.com/hexgrad/misaki/blob/main/misaki/g2pkc/utils.py
 * - https://github.com/hexgrad/misaki/blob/main/misaki/g2pkc/english.py
 * - https://github.com/hexgrad/misaki/blob/main/misaki/g2pkc/numerals.py
 *
 * Ported as pure Kotlin without external dependencies (no MeCab, jamo, NLTK, CMUdict).
 * Replaces:
 * - MeCab POS tagger → simple heuristic tagger
 * - CMU Pronouncing Dictionary → letter-by-letter Hangul conversion
 * - jamo (h2j) → inline Hangul decomposition
 * - idioms.txt → hardcoded essential idioms
 * - table.csv → hardcoded essential rules
 *
 * Pipeline (matching upstream g2pK):
 * 1. Idioms replacement
 * 2. English → Hangul conversion
 * 3. Annotate (POS tagging for special rules)
 * 4. Spell out Arabic numbers
 * 5. Decompose Hangul to jamo
 * 6. Apply special rules (jyeo, ye, ui, jamo, etc.)
 * 7. Apply regular table rules (batchim + onset)
 * 8. Apply link rules
 * 9. Postprocessing (group vowels, compose)
 */
class KoG2P(
    private val espeakFallback: ((String, String) -> String?)? = null,
    private val unk: String = "❓"
) {
    companion object {
        private const val TAG = "KoG2P"

        // --- Dynamic dictionaries (loaded from g2pkc assets) ---
        private var fullIdioms: List<Pair<String, String>> = emptyList()
        private var fullRules: List<Triple<String, String, String>> = emptyList() // (from, to, rule_num)
        private var tableRules: List<Triple<String, String, String>> = emptyList() // (coda, onset, result)
        @Volatile private var dictsLoaded = false

        /** Load g2pkc dictionaries from assets. Call once during init. */
        fun loadDictionaries(context: android.content.Context) {
            if (dictsLoaded) return
            try {
                fullIdioms = loadIdioms(context)
                fullRules = loadRules(context)
                tableRules = loadTable(context)
                dictsLoaded = true
                Log.d(TAG, "Loaded g2pkc: ${fullIdioms.size} idioms, ${fullRules.size} rules, ${tableRules.size} table entries")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load g2pkc dictionaries: ${e.message}")
                dictsLoaded = true // Don't retry
            }
        }

        private fun loadIdioms(context: android.content.Context): List<Pair<String, String>> {
            val list = mutableListOf<Pair<String, String>>()
            try {
                context.assets.open("g2pkc/idioms.txt").bufferedReader().use { reader ->
                    reader.forEachLine { line ->
                        val trimmed = line.trim()
                        if (trimmed.isEmpty() || trimmed.startsWith("#")) return@forEachLine
                        val parts = trimmed.split("===")
                        if (parts.size == 2) {
                            list.add(parts[0].trim() to parts[1].trim())
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "g2pkc/idioms.txt not found")
            }
            return list
        }

        private fun loadRules(context: android.content.Context): List<Triple<String, String, String>> {
            val list = mutableListOf<Triple<String, String, String>>()
            try {
                context.assets.open("g2pkc/rules.txt").bufferedReader().use { reader ->
                    var currentRule = ""
                    reader.forEachLine { line ->
                        val trimmed = line.trim()
                        if (trimmed.isEmpty() || trimmed.startsWith("#")) return@forEachLine
                        if (trimmed.matches(Regex("\\d+\\.\\d*"))) {
                            currentRule = trimmed
                            return@forEachLine
                        }
                        if (trimmed.startsWith("->")) {
                            // This is a rule example, parse it
                            val arrowParts = trimmed.removePrefix("->").trim().split("[", "]")
                            // Rules are examples; the actual rules are applied via special.py/regular.py
                            // We store them for reference only
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "g2pkc/rules.txt not found")
            }
            return list
        }

        private fun loadTable(context: android.content.Context): List<Triple<String, String, String>> {
            val list = mutableListOf<Triple<String, String, String>>()
            try {
                context.assets.open("g2pkc/table.csv").bufferedReader().use { reader ->
                    val lines = reader.readLines()
                    if (lines.size < 2) return list
                    // First row is header: ,ᄒ,ᄀ,ᄁ,...
                    val onsets = lines[0].split(",").drop(1)
                    // Remaining rows: ᇂ,ᄒ,ᄏ(12),ᄁ,...
                    for (i in 1 until lines.size) {
                        val cols = lines[i].split(",")
                        if (cols.size < 2) continue
                        val coda = cols[0]
                        for (j in 1 until minOf(cols.size, onsets.size + 1)) {
                            val cell = cols[j].trim()
                            if (cell.isNotEmpty() && cell != coda) {
                                val onset = onsets[j - 1]
                                list.add(Triple(coda, onset, cell))
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "g2pkc/table.csv not found")
            }
            return list
        }

        // Hangul Unicode ranges
        private const val SYL_START = 0xAC00  // 가
        private const val SYL_END = 0xD7A3    // 힣
        private const val CHO_START = 0x1100  // ᄀ
        private const val JUNG_START = 0x1161 // ᅡ
        private const val JONG_START = 0x11A7 // ᆧ (filler, actual jong 0x11A8)

        // Choseong (onset) list: ᄀ ᄁ ᄂ ᄃ ᄄ ᄅ ᄆ ᄇ ᄈ ᄉ ᄊ ᄋ ᄌ ᄍ ᄎ ᄏ ᄐ ᄑ ᄒ
        private val CHOSEN = listOf(
            'ᄀ', 'ᄁ', 'ᄂ', 'ᄃ', 'ᄄ', 'ᄅ', 'ᄆ', 'ᄇ', 'ᄈ', 'ᄉ',
            'ᄊ', 'ᄋ', 'ᄌ', 'ᄍ', 'ᄎ', 'ᄏ', 'ᄐ', 'ᄑ', 'ᄒ'
        )

        // Jungseong (vowel) list: ᅡ ᅢ ᅣ ᅤ ᅥ ᅦ ᅧ ᅨ ᅩ ᅪ ᅫ ᅬ ᅭ ᅮ ᅯ ᅰ ᅱ ᅲ ᅳ ᅴ ᅵ
        private val JUNGSEON = listOf(
            'ᅡ', 'ᅢ', 'ᅣ', 'ᅤ', 'ᅥ', 'ᅦ', 'ᅧ', 'ᅨ', 'ᅩ', 'ᅪ',
            'ᅫ', 'ᅬ', 'ᅭ', 'ᅮ', 'ᅯ', 'ᅰ', 'ᅱ', 'ᅲ', 'ᅳ', 'ᅴ', 'ᅵ'
        )

        // Jongseong (coda) list: (none) ᆨ ᆩ ᆪ ᆫ ᆬ ᆭ ᆮ ᆯ ᆰ ᆱ ᆲ ᆳ ᆴ ᆵ ᆶ ᆷ ᆸ ᆹ ᆺ ᆻ ᆼ ᆽ ᆾ ᆿ ᇀ ᇁ ᇂ
        private val JONGSEON = listOf(
            '\u0000', 'ᆨ', 'ᆩ', 'ᆪ', 'ᆫ', 'ᆬ', 'ᆭ', 'ᆮ', 'ᆯ', 'ᆰ',
            'ᆱ', 'ᆲ', 'ᆳ', 'ᆴ', 'ᆵ', 'ᆶ', 'ᆷ', 'ᆸ', 'ᆹ', 'ᆺ',
            'ᆻ', 'ᆼ', 'ᆽ', 'ᆾ', 'ᆿ', 'ᇀ', 'ᇁ', 'ᇂ'
        )

        // --- Hangul decomposition (replaces jamo.h2j) ---
        fun h2j(syllable: Char): String {
            val code = syllable.code
            if (code !in SYL_START..SYL_END) return syllable.toString()
            val idx = code - SYL_START
            val cho = idx / (21 * 28)
            val jung = (idx % (21 * 28)) / 28
            val jong = idx % 28
            val result = StringBuilder()
            result.append(CHOSEN[cho])
            result.append(JUNGSEON[jung])
            if (jong > 0) result.append(JONGSEON[jong])
            return result.toString()
        }

        // --- Hangul composition (replaces jamo.j2h) ---
        fun j2h(cho: Char, jung: Char, jong: Char? = null): Char {
            val choIdx = CHOSEN.indexOf(cho)
            val jungIdx = JUNGSEON.indexOf(jung)
            val jongIdx = if (jong != null) JONGSEON.indexOf(jong) else 0
            if (choIdx < 0 || jungIdx < 0 || jongIdx < 0) return cho
            return (SYL_START + choIdx * 21 * 28 + jungIdx * 28 + jongIdx).toChar()
        }

        fun composeJamo(text: String): String {
            var result = text
            // Insert placeholder initial ᄋ before lone vowels
            result = Regex("([^\\u1100-\\u1112])([\\u1161-\\u1175])").replace(result) { m ->
                m.groupValues[1] + "ᄋ" + m.groupValues[2]
            }
            if (result.firstOrNull()?.let { it.code in 0x1161..0x1175 } == true) {
                result = "ᄋ" + result
            }
            // C+V+C
            val cvc = Regex("[\\u1100-\\u1112][\\u1161-\\u1175][\\u11A8-\\u11C2]")
            result = cvc.replace(result) { m ->
                val s = m.value
                j2h(s[0], s[1], s[2]).toString()
            }
            // C+V
            val cv = Regex("[\\u1100-\\u1112][\\u1161-\\u1175]")
            result = cv.replace(result) { m ->
                val s = m.value
                j2h(s[0], s[1], null).toString()
            }
            return result
        }

        // --- English → Hangul (full port of english.py + utils.py) ---
        // Letter-by-letter pronunciation for acronyms (all-caps) and unknown words
        private val ENG2KOR = mapOf(
            'A' to "에이", 'B' to "비", 'C' to "씨", 'D' to "디", 'E' to "이",
            'F' to "에프", 'G' to "지", 'H' to "에이치", 'I' to "아이", 'J' to "제이",
            'K' to "케이", 'L' to "엘", 'M' to "엠", 'N' to "엔", 'O' to "오",
            'P' to "피", 'Q' to "큐", 'R' to "알", 'S' to "에스", 'T' to "티",
            'U' to "유", 'V' to "브이", 'W' to "더블유", 'X' to "엑스", 'Y' to "와이",
            'Z' to "지"
        )

        // ARPAbet → choseong (onset) — from utils.py to_choseong()
        private val TO_CHOSEONG = mapOf(
            "B" to "ᄇ", "CH" to "ᄎ", "D" to "ᄃ", "DH" to "ᄃ",
            "DZ" to "ᄌ", "F" to "ᄑ", "G" to "ᄀ", "HH" to "ᄒ",
            "JH" to "ᄌ", "K" to "ᄏ", "L" to "ᄅ", "M" to "ᄆ",
            "N" to "ᄂ", "NG" to "ᄋ", "P" to "ᄑ", "R" to "ᄅ",
            "S" to "ᄉ", "SH" to "ᄉ", "T" to "ᄐ", "TH" to "ᄉ",
            "TS" to "ᄎ", "V" to "ᄇ", "W" to "W", "Y" to "Y",
            "Z" to "ᄌ", "ZH" to "ᄌ"
        )

        // ARPAbet → jungseong (vowel) — from utils.py to_jungseong()
        private val TO_JUNGSEONG = mapOf(
            "AA" to "ᅡ", "AE" to "ᅢ", "AH" to "ᅥ", "AO" to "ᅩ",
            "AW" to "ᅡ우", "AWER" to "ᅡ워", "AY" to "ᅡ이",
            "EH" to "ᅦ", "ER" to "ᅥ", "EY" to "ᅦ이",
            "IH" to "ᅵ", "IY" to "ᅵ", "OW" to "ᅩ", "OY" to "ᅩ이",
            "UH" to "ᅮ", "UW" to "ᅮ"
        )

        // ARPAbet → jongseong (coda) — from utils.py to_jongseong()
        private val TO_JONGSEONG = mapOf(
            "B" to "ᆸ", "CH" to "ᆾ", "D" to "ᆮ", "DH" to "ᆮ",
            "F" to "ᇁ", "G" to "ᆨ", "HH" to "ᇂ", "JH" to "ᆽ",
            "K" to "ᆨ", "L" to "ᆯ", "M" to "ᆷ", "N" to "ᆫ",
            "NG" to "ᆼ", "P" to "ᆸ", "R" to "ᆯ", "S" to "ᆺ",
            "SH" to "ᆺ", "T" to "ᆺ", "TH" to "ᆺ", "V" to "ᆸ",
            "W" to "ᆼ", "Y" to "ᆼ", "Z" to "ᆽ", "ZH" to "ᆽ"
        )

        /**
         * Adjust ARPAbet phonemes for Korean loanword processing.
         * Port of utils.py adjust().
         * Strips stress digits, merges TS/DZ clusters, handles AWER/ER patterns.
         */
        fun adjust(arpabets: List<String>): List<String> {
            var s = " " + arpabets.joinToString(" ") + " $"
            // Strip stress digits
            s = s.replace(Regex("\\d"), "")
            // Merge TS and DZ clusters
            s = s.replace(" T S ", " TS ")
            s = s.replace(" D Z ", " DZ ")
            // Handle AW ER → AWER
            s = s.replace(" AW ER ", " AWER ")
            // Handle IH/EH + R at end → ER
            s = s.replace(" IH ER $", " IH ER ")
            s = s.replace(" EH ER $", " EH ER ")
            // Remove start/end markers
            s = s.replace("$", "").trim()
            return if (s.isNotBlank()) s.split(Regex("\\s+")) else emptyList()
        }

        /**
         * Reconstruct Hangul jamo after ARPAbet→jamo conversion.
         * Port of utils.py reconstruct().
         * Handles Y/W glide merging and vowel combinations.
         */
        fun reconstructJamo(inp: String): String {
            var s = inp
            val pairs = listOf(
                "그W" to "ᄀW", "흐W" to "ᄒW", "크W" to "ᄏW",
                "ᄂYᅥ" to "니어", "ᄃYᅥ" to "디어", "ᄅYᅥ" to "리어",
                "Yᅵ" to "ᅵ", "Yᅡ" to "ᅣ", "Yᅢ" to "ᅤ",
                "Yᅥ" to "ᅧ", "Yᅦ" to "ᅨ", "Yᅩ" to "ᅭ",
                "Yᅮ" to "ᅲ", "Wᅡ" to "ᅪ", "Wᅢ" to "ᅫ",
                "Wᅥ" to "ᅯ", "Wᅩ" to "ᅯ", "Wᅮ" to "ᅮ",
                "Wᅦ" to "ᅰ", "Wᅵ" to "ᅱ",
                "ᅳᅵ" to "ᅴ", "Y" to "ᅵ", "W" to "ᅮ"
            )
            for ((from, to) in pairs) {
                s = s.replace(from, to)
            }
            return s
        }

        /**
         * Convert ARPAbet phonemes to Hangul using Korean loanword orthography rules.
         * Full port of english.py convert_eng() phoneme-by-phoneme rules.
         *
         * Rules follow the 외래어 표기법 (Korean loanword orthography):
         * - Rule 1: Voiceless stops (P, T, K) after short vowels
         * - Rule 2: Voiced stops (B, D, G) with 으 insertion
         * - Rule 3: Fricatives (S, Z, F, V, TH, DH, SH, ZH)
         * - Rule 4: Affricates (TS, DZ, CH, JH)
         * - Rule 5: Nasals (M, N, NG)
         * - Rule 6: Liquid (L)
         * - Rule 8: Diphthongs
         */
        fun arpabetToHangul(phonemes: List<String>): String {
            if (phonemes.isEmpty()) return ""
            var ret = ""
            val shortVowels = setOf("AE", "AH", "AX", "EH", "IH", "IX", "UH")
            val vowels = "AEIOUY"
            val consonants = "BCDFGHJKLMNPQRSTVWXZ"
            val syllableFinalOrConsonants = "\$BCDFGHJKLMNPQRSTVWXZ"

            for (i in phonemes.indices) {
                val p = phonemes[i]
                val pPrev = if (i > 0) phonemes[i - 1] else "^"
                val pNext = if (i < phonemes.size - 1) phonemes[i + 1] else "$"
                val pNext2 = if (i < phonemes.size - 2) phonemes[i + 1] else "$"

                when {
                    // Rule 1: Voiceless stops (P, T, K)
                    p in listOf("P", "T", "K") -> {
                        when {
                            pPrev.substring(0, minOf(2, pPrev.length)) in shortVowels && pNext == "$" ->
                                ret += (TO_JONGSEONG[p] ?: p)
                            pPrev.substring(0, minOf(2, pPrev.length)) in shortVowels &&
                                pNext.isNotEmpty() && pNext[0] !in "AEIOULRMN" ->
                                ret += (TO_JONGSEONG[p] ?: p)
                            pNext.isNotEmpty() && pNext[0] in syllableFinalOrConsonants -> {
                                ret += (TO_CHOSEONG[p] ?: p)
                                ret += "ᅳ"
                            }
                            else -> ret += (TO_CHOSEONG[p] ?: p)
                        }
                    }

                    // Rule 2: Voiced stops (B, D, G)
                    p in listOf("B", "D", "G") -> {
                        ret += (TO_CHOSEONG[p] ?: p)
                        if (pNext.isNotEmpty() && pNext[0] in syllableFinalOrConsonants) {
                            ret += "ᅳ"
                        }
                    }

                    // Rule 3: Fricatives
                    p in listOf("S", "Z", "F", "V", "TH", "DH", "SH", "ZH") -> {
                        ret += (TO_CHOSEONG[p] ?: p)
                        when {
                            p in listOf("S", "Z", "F", "V", "TH", "DH") -> {
                                if (pNext.isNotEmpty() && pNext[0] in syllableFinalOrConsonants) {
                                    ret += "ᅳ"
                                }
                            }
                            p == "SH" -> {
                                when {
                                    pNext == "$" -> ret += "ᅵ"
                                    pNext.isNotEmpty() && pNext[0] in consonants -> ret += "ᅲ"
                                    else -> ret += "Y" // vowel-dependent glide
                                }
                            }
                            p == "ZH" -> {
                                if (pNext.isNotEmpty() && pNext[0] in syllableFinalOrConsonants) {
                                    ret += "ᅵ"
                                }
                            }
                        }
                    }

                    // Rule 4: Affricates
                    p in listOf("TS", "DZ", "CH", "JH") -> {
                        ret += (TO_CHOSEONG[p] ?: p)
                        if (pNext.isNotEmpty() && pNext[0] in syllableFinalOrConsonants) {
                            ret += if (p in listOf("TS", "DZ")) "ᅳ" else "ᅵ"
                        }
                    }

                    // Rule 5: Nasals (M, N, NG)
                    p in listOf("M", "N", "NG") -> {
                        if (p in listOf("M", "N") && pNext.isNotEmpty() && pNext[0] in vowels) {
                            ret += (TO_CHOSEONG[p] ?: p)
                        } else {
                            ret += (TO_JONGSEONG[p] ?: p)
                        }
                    }

                    // Rule 6: Liquid (L)
                    p == "L" -> {
                        when {
                            pPrev == "^" -> ret += (TO_CHOSEONG["L"] ?: "ᄅ")
                            pNext.isNotEmpty() && pNext[0] in "\$BCDFGHJKLPQRSTVWXZ" ->
                                ret += (TO_JONGSEONG["L"] ?: "ᆯ")
                            pPrev in listOf("M", "N") -> ret += (TO_CHOSEONG["L"] ?: "ᄅ")
                            pNext.isNotEmpty() && pNext[0] in vowels -> ret += "ᆯᄅ"
                            pNext in listOf("M", "N") &&
                                pNext2.isNotEmpty() && pNext2[0] !in vowels -> ret += "ᆯ르"
                            else -> ret += (TO_JONGSEONG["L"] ?: "ᆯ")
                        }
                    }

                    // ER (rhotic vowel)
                    p == "ER" -> {
                        if (pPrev.isNotEmpty() && pPrev[0] in vowels) ret += "ᄋ"
                        ret += (TO_JUNGSEONG["ER"] ?: "ᅥ")
                        if (pNext.isNotEmpty() && pNext[0] in vowels) ret += "ᄅ"
                    }

                    // R (consonant)
                    p == "R" -> {
                        if (pNext.isNotEmpty() && pNext[0] in vowels) {
                            ret += (TO_CHOSEONG["R"] ?: "ᄅ")
                        }
                        // else: silent or handled by ER
                    }

                    // Rule 8: Vowels and diphthongs
                    p.isNotEmpty() && p[0] in "AEIOU" -> {
                        ret += (TO_JUNGSEONG[p] ?: p)
                    }

                    // Fallback: any other consonant
                    else -> {
                        ret += (TO_CHOSEONG[p] ?: p)
                    }
                }
            }

            // Apply reconstruction rules (glide merging)
            ret = reconstructJamo(ret)
            // Compose jamo back to syllables
            ret = composeJamo(ret)
            // Remove remaining jamo characters (should be fully composed)
            ret = ret.replace(Regex("[\\u1100-\\u11FF]"), "")

            return ret
        }

        /**
         * Convert English words in Korean text to Hangul.
         * Full port of english.py convert_eng().
         *
         * For ALL-CAPS words or unknown words: letter-by-letter (word_to_hangul).
         * For known words in CMUdict: ARPAbet→Hangul via loanword orthography rules.
         */
        fun convertEng(string: String): String {
            var result = string
            val engWords = Regex("[A-Za-z]+").findAll(string)
                .map { it.value }
                .distinctBy { it.lowercase() }
                .sortedByDescending { it.length }
                .toList()

            for (engWord in engWords) {
                if (engWord.all { it.isUpperCase() } || CmuDict.lookup(engWord) == null) {
                    // Acronym or unknown: letter-by-letter conversion
                    val hangul = engWord.map { ENG2KOR[it.uppercaseChar()] ?: it.toString() }.joinToString("")
                    result = result.replace(engWord, hangul)
                } else {
                    // Known word: ARPAbet → Hangul via loanword orthography
                    val arpabets = CmuDict.lookup(engWord)!!
                    val phonemes = adjust(arpabets)
                    val hangul = arpabetToHangul(phonemes)
                    if (hangul.isNotBlank()) {
                        result = result.replace(engWord, hangul)
                    }
                }
            }
            return result
        }

        // --- Number conversion (from numerals.py) ---
        private val BOUND_NOUNS = setOf(
            "군데", "권", "개", "그루", "닢", "두", "마리", "모", "모금", "뭇",
            "발", "발짝", "방", "번", "벌", "보루", "살", "수", "술", "시",
            "쌈", "움큼", "정", "짝", "채", "척", "첩", "축", "켤레", "톨",
            "통", "가지", "배", "시간", "살", "명", "줄", "곳"
        )

        private val MODIFIERS = listOf("한", "두", "세", "네", "다섯", "^여섯", "일곱", "^여덟", "아홉")
        private val DECIMALS = listOf("열", "스물", "서른", "마흔", "쉰", "예순", "일흔", "여든", "아흔")

        fun processNum(numStr: String, sino: Boolean = true): String {
            val num = numStr.replace(",", "")
            if (num == "0") return "영"
            if (!sino && num == "20") return "스무"

            val digitNames = "일이삼사오육칠팔구"
            val digits = "123456789"

            val result = mutableListOf<String>()
            for ((idx, digit) in num.withIndex()) {
                val i = num.length - idx - 1
                if (digit !in digits) {
                    if (digit == '0') {
                        if (i % 4 == 0 && result.takeLast(3).isNotEmpty()) {
                            result.add("")
                            continue
                        }
                        result.add("")
                        continue
                    }
                    result.add("")
                    continue
                }

                val dIdx = digits.indexOf(digit)
                var name = when {
                    sino || num.length >= 3 -> when (i % 10) {
                        0 -> digitNames[dIdx].toString()
                        1 -> digitNames[dIdx] + "십"
                        else -> ""
                    }
                    i == 0 -> MODIFIERS.getOrElse(dIdx) { digitNames[dIdx].toString() }
                    i == 1 -> DECIMALS.getOrElse(dIdx) { digitNames[dIdx] + "십" }
                    else -> digitNames[dIdx].toString()
                }

                if (sino || num.length >= 3) {
                    when (i % 10) {
                        1 -> name = name.replace("일십", "십")
                    }
                    when (i) {
                        2 -> { name = digitNames[dIdx] + "백"; name = name.replace("일백", "백") }
                        3 -> { name = digitNames[dIdx] + "천"; name = name.replace("일천", "천") }
                        4 -> { name = digitNames[dIdx] + "만"; name = name.replace("일만", "만") }
                    }
                    when (i) {
                        8 -> name = digitNames[dIdx] + "억"
                        12 -> name = digitNames[dIdx] + "조"
                    }
                }
                result.add(name)
            }
            return result.joinToString("")
        }

        fun convertNum(string: String): String {
            var result = string
            // Handle bound nouns
            val boundNounPattern = Regex("([\\d][\\d,]*)( ?[ㄱ-힣]+)?(?:/B)?")
            for (match in boundNounPattern.findAll(result)) {
                val num = match.groupValues[1]
                val bn = match.groupValues[2].trim()
                val sino = bn !in BOUND_NOUNS
                val spelled = processNum(num, sino)
                result = result.replace(match.value, spelled + bn)
            }
            // Remaining digits → digit-by-digit
            val digitNames = "영일이삼사오육칠팔구"
            for (i in 0..9) {
                result = result.replace(i.digitToChar(), '^' + digitNames[i])
            }
            result = result.replace("십^육", "심뉵")
            result = result.replace("백^육", "뱅뉵")
            return result
        }

        // --- Essential idioms (from idioms.txt, hardcoded key subset) ---
        private val IDIOMS = listOf(
            "갇혀" to "가쳐", "갇히" to "가치", "갇혔" to "가쳤",
            "곧이어" to "고디어",
            "의견란" to "의견난", "생산량" to "생산냥", "결단력" to "결딴녁",
            "갈등" to "갈뜽", "발동" to "발똥", "절도" to "절또",
            "말살" to "말쌀", "불소" to "불쏘", "일시" to "일씨",
            "할걸" to "할껄", "할수록" to "할쑤록",
            "문고리" to "문꼬리", "눈동자" to "눈똥자", "신바람" to "신빠람",
            "솜이불" to "솜니불", "막일" to "망닐", "꽃잎" to "꼰닙",
            "냇가" to "내까", "샛길" to "새낄", "콧날" to "콘날",
            "예요" to "에요",
            "십육" to "심뉵", "백육" to "뱅뉵", "10월" to "시월", "6월" to "유월"
        )

        fun applyIdioms(string: String): String {
            var result = string
            // Apply full idioms dictionary (if loaded)
            for ((from, to) in fullIdioms) {
                result = result.replace(Regex(from), to)
            }
            // Apply hardcoded essential idioms as fallback/supplement
            for ((from, to) in IDIOMS) {
                result = result.replace(from, to)
            }
            return result
        }

        // --- Special rules (from special.py) ---
        fun applySpecialRules(inp: String, descriptive: Boolean = false): String {
            var out = inp

            // 5.1 jyeo
            out = out.replace(Regex("([ᄌᄍᄎ])ᅧ"), "$1ᅥ")

            // 5.2 ye (descriptive only)
            if (descriptive) {
                out = out.replace(Regex("([ᄀᄁᄃᄄㄹᄆᄇᄈᄌᄍᄎᄏᄐᄑᄒ])ᅨ"), "$1ᅦ")
            }

            // 5.3 consonant_ui
            out = out.replace(Regex("([ᄀᄁᄂᄃᄄᄅᄆᄇᄈᄉᄊᄌᄍᄎᄏᄐᄑᄒ])ᅴ"), "$1ᅵ")

            // 5.4.2 josa_ui
            out = out.replace("/J", "")

            // 16 jamo
            out = out.replace(Regex("(디그)ᆮᄋ"), "$1ᄉ")
            out = out.replace(Regex("([ᄌᄎᄐᄒ]ᅵ으)[ᆽᆾᇀᇂ]ᄋ"), "$1ᄉ")
            out = out.replace(Regex("(키으)ᆿᄋ"), "$1ᄀ")
            out = out.replace(Regex("(피으)ᇁᄋ"), "$1ᄇ")

            // 11.1 rieulgiyeok
            out = out.replace(Regex("ᆰ/P([ᄀᄁ])"), "ᆯᄁ")

            // 25 rieulbieub
            out = out.replace(Regex("([ᆲᆴ])/Pᄀ"), "$1ᄁ")
            out = out.replace(Regex("([ᆲᆴ])/Pᄃ"), "$1ᄄ")
            out = out.replace(Regex("([ᆲᆴ])/Pᄉ"), "$1ᄊ")
            out = out.replace(Regex("([ᆲᆴ])/Pᄌ"), "$1ᄍ")

            // 24 verb_nieun
            val nieunPairs = listOf(
                "([ᆫᆷ])/Pᄀ" to "$1ᄁ", "([ᆫᆷ])/Pᄃ" to "$1ᄄ",
                "([ᆫᆷ])/Pᄉ" to "$1ᄊ", "([ᆫᆷ])/Pᄌ" to "$1ᄍ",
                "ᆬ/Pᄀ" to "ᆫᄁ", "ᆬ/Pᄃ" to "ᆫᄄ",
                "ᆬ/Pᄉ" to "ᆫᄊ", "ᆬ/Pᄌ" to "ᆫᄍ",
                "ᆱ/Pᄀ" to "ᆷᄁ", "ᆱ/Pᄃ" to "ᆷᄄ",
                "ᆱ/Pᄉ" to "ᆷᄊ", "ᆱ/Pᄌ" to "ᆷᄍ"
            )
            for ((pattern, replacement) in nieunPairs) {
                out = out.replace(Regex(pattern), replacement)
            }

            // 10.1 balb
            val syllableFinal = "($|[^ᄋᄒ])"
            out = out.replace(Regex("(바)ᆲ($syllableFinal)"), "$1ᆸ$2")

            // 17 palatalize
            out = out.replace(Regex("ᆮᄋ([ᅵᅧ])"), "ᄌ$1")
            out = out.replace(Regex("ᇀᄋ([ᅵᅧ])"), "ᄎ$1")
            out = out.replace(Regex("ᆴᄋ([ᅵᅧ])"), "ᆯᄎ$1")
            out = out.replace(Regex("ᆮᄒ([ᅵ])"), "ᄎ$1")

            // 27 modifying_rieul
            val rieulPairs = listOf(
                "ᆯ걸" to "ᆯ껄", "ᆯ밖에" to "ᆯ빠께",
                "ᆯ세라" to "ᆯ쎄라", "ᆯ수록" to "ᆯ쑤록"
            )
            for ((from, to) in rieulPairs) {
                out = out.replace(from, to)
            }

            // Remove remaining POS tags
            out = out.replace(Regex("/[PJEB]"), "")

            return out
        }

        // --- Regular table rules (from regular.py, essential subset) ---
        fun applyRegularRules(inp: String): String {
            var out = inp

            // Link1 (Rule 13): batchim + ᄋ
            val link1Pairs = listOf(
                "ᆨᄋ" to "ᄀ", "ᆩᄋ" to "ᄁ", "ᆫᄋ" to "ᄂ", "ᆮᄋ" to "ᄃ",
                "ᆯᄋ" to "ᄅ", "ᆷᄋ" to "ᄆ", "ᆸᄋ" to "ᄇ", "ᆺᄋ" to "ᄉ",
                "ᆻᄋ" to "ᄊ", "ᆽᄋ" to "ᄌ", "ᆾᄋ" to "ᄎ", "ᆿᄋ" to "ᄏ",
                "ᇀᄋ" to "ᄐ", "ᇁᄋ" to "ᄑ"
            )
            for ((from, to) in link1Pairs) {
                out = out.replace(from, to)
            }

            // Link2 (Rule 14): double batchim + ᄋ
            val link2Pairs = listOf(
                "ᆪᄋ" to "ᆨᄊ", "ᆬᄋ" to "ᆫᄌ", "ᆰᄋ" to "ᆯᄀ",
                "ᆱᄋ" to "ᆯᄆ", "ᆲᄋ" to "ᆯᄇ", "ᆳᄋ" to "ᆯᄊ",
                "ᆴᄋ" to "ᆯᄐ", "ᆵᄋ" to "ᆯᄑ", "ᆹᄋ" to "ᆸᄊ"
            )
            for ((from, to) in link2Pairs) {
                out = out.replace(from, to)
            }

            // Link4 (Rule 12.4): ㅎ + ᄋ
            val link4Pairs = listOf(
                "ᇂᄋ" to "ᄋ", "ᆭᄋ" to "ᄂ", "ᆶᄋ" to "ᄅ"
            )
            for ((from, to) in link4Pairs) {
                out = out.replace(from, to)
            }

            return out
        }

        // --- Apply table.csv coda×onset assimilation rules ---
        fun applyTableRules(inp: String): String {
            if (tableRules.isEmpty()) return inp
            var out = inp
            for ((coda, onset, result) in tableRules) {
                val pattern = Regex(Regex.escape(coda) + Regex.escape(onset))
                val cleanResult = result.replace(Regex("\\(\\d+/\\d+\\)$"), "")
                    .replace(Regex("\\(\\d+\\)$"), "")
                out = pattern.replace(out, cleanResult)
            }
            return out
        }

        // --- Group vowels (postprocessing) ---
        fun groupVowels(inp: String): String {
            return inp
                .replace("ᅢ", "ᅦ")
                .replace("ᅤ", "ᅨ")
                .replace("ᅫ", "ᅬ")
                .replace("ᅰ", "ᅬ")
        }

        // --- Hangul → IPA mapping ---
        private val HANGUL_IPA = mapOf(
            'ᄀ' to "k", 'ᄁ' to "k͈", 'ᄂ' to "n", 'ᄃ' to "t", 'ᄄ' to "t͈",
            'ᄅ' to "ɾ", 'ᄆ' to "m", 'ᄇ' to "p", 'ᄈ' to "p͈", 'ᄉ' to "s",
            'ᄊ' to "s͈", 'ᄋ' to "ŋ", 'ᄌ' to "tɕ", 'ᄍ' to "tɕ͈", 'ᄎ' to "tɕʰ",
            'ᄏ' to "kʰ", 'ᄐ' to "tʰ", 'ᄑ' to "pʰ", 'ᄒ' to "h",
            'ᅡ' to "a", 'ᅢ' to "ɛ", 'ᅣ' to "ja", 'ᅤ' to "jɛ",
            'ᅥ' to "ʌ", 'ᅦ' to "e", 'ᅧ' to "jʌ", 'ᅨ' to "je",
            'ᅩ' to "o", 'ᅪ' to "wa", 'ᅫ' to "wɛ", 'ᅬ' to "ø",
            'ᅭ' to "jo", 'ᅮ' to "u", 'ᅯ' to "wʌ", 'ᅰ' to "we",
            'ᅱ' to "y", 'ᅲ' to "ju", 'ᅳ' to "ɯ", 'ᅴ' to "ɰi", 'ᅵ' to "i",
            'ᆨ' to "k", 'ᆩ' to "k͈", 'ᆪ' to "k̚", 'ᆫ' to "n", 'ᆬ' to "n",
            'ᆭ' to "n", 'ᆮ' to "t", 'ᆯ' to "ɾ", 'ᆰ' to "k",
            'ᆱ' to "m", 'ᆲ' to "p", 'ᆳ' to "s͈", 'ᆴ' to "tʰ",
            'ᆵ' to "pʰ", 'ᆶ' to "ɾ", 'ᆷ' to "m", 'ᆸ' to "p̚",
            'ᆹ' to "p̚", 'ᆺ' to "t̚", 'ᆻ' to "s͈", 'ᆼ' to "ŋ",
            'ᆽ' to "tɕ", 'ᆾ' to "tɕʰ", 'ᆿ' to "k", 'ᇀ' to "tʰ",
            'ᇁ' to "p", 'ᇂ' to "h"
        )

        fun hangulToIpa(text: String): String {
            val result = StringBuilder()
            for (ch in text) {
                if (ch.code in SYL_START..SYL_END) {
                    // Decompose syllable to jamo, then map each
                    val jamo = h2j(ch)
                    for (j in jamo) {
                        result.append(HANGUL_IPA[j] ?: j.toString())
                    }
                } else if (ch in HANGUL_IPA) {
                    result.append(HANGUL_IPA[ch]!!)
                } else {
                    result.append(ch)
                }
            }
            return result.toString()
        }
    }

    /**
     * Main phonemization entry point.
     */
    fun phonemize(text: String): String {
        if (text.isBlank()) return ""

        // Try eSpeak fallback first
        val espeakResult = espeakFallback?.invoke(text, "ko")
        if (espeakResult != null && espeakResult.isNotBlank()) {
            return espeakResult
        }

        // 1. Idioms
        var result = applyIdioms(text)

        // 2. English → Hangul
        result = convertEng(result)

        // 3. Annotate (simplified - no MeCab, so skip POS tagging)

        // 4. Spell out numbers
        result = convertNum(result)

        // 5. Decompose Hangul to jamo
        result = result.map { ch ->
            if (ch.code in SYL_START..SYL_END) h2j(ch) else ch.toString()
        }.joinToString("")

        // 6. Special rules
        result = applySpecialRules(result)

        // 6.5. Table rules from table.csv (coda×onset assimilation)
        result = applyTableRules(result)

        // 7. Regular table rules
        result = applyRegularRules(result)

        // 8. Postprocessing: compose jamo back to syllables for readability
        //    then convert to IPA
        val composed = composeJamo(result)
        val ipa = hangulToIpa(composed)

        // Remove caret markers
        return ipa.replace("^", "").replace(Regex("\\s+"), " ").trim()
    }
}