package com.nekospeak.tts.engine.misaki

import android.content.Context
import android.util.Log
import java.util.Locale

/**
 * Faithful port of upstream Misaki's Lexicon class.
 * Reference: https://github.com/hexgrad/misaki/blob/main/misaki/en.py (Lexicon class)
 *
 * Handles English word → phoneme lookup with:
 * - Gold dictionary (high quality, curated)
 * - Silver dictionary (broader coverage)
 * - Special case handling (a/an/the/am/to/in/used, currency, symbols, abbreviations)
 * - Morphological stemming (-s, -ed, -ing)
 * - NNP (proper noun) fallback (spell out each letter)
 * - Number pronunciation (cardinal, ordinal, year, currency)
 * - Stress application (primary ˈ, secondary ˌ)
 * - Dictionary growing (auto-lowercase/capitalize variants)
 */
class Lexicon(private val context: Context, private val british: Boolean) {

    companion object {
        private const val TAG = "Lexicon"

        // --- Constants from en.py ---
        val STRESSES = "ˌˈ"
        val PRIMARY_STRESS = STRESSES[1].toString()   // ˈ
        val SECONDARY_STRESS = STRESSES[0].toString()   // ˌ
        val VOWELS = "AIOQWYaiuæɑɒɔəɛɜɪʊʌᵻ".toSet()
        val DIPHTHONGS = "AIOQWYʤʧ".toSet()
        val CONSONANTS = "bdfhjklmnpstvwzðŋɡɹɾʃʒʤʧθ".toSet()
        val US_TAUS = "AIOWYiuæɑəɛɪɹʊʌ".toSet()
        val PUNCTS = ";:,.!?—…\"\u201C\u201D".toSet()
        val NON_QUOTE_PUNCTS = PUNCTS.filter { it !in "\"\u201C\u201D" }.toSet()
        val PUNCT_TAGS = setOf(".", ",", "-LRB-", "-RRB-", "``", "\"\"", "''", ":", "$", "#", "NFP")
        val PUNCT_TAG_PHONEMES = mapOf(
            "-LRB-" to "(", "-RRB-" to ")",
            "``" to "\u201C", "\"\"" to "\u201D", "''" to "\u201D"
        )
        val SUBTOKEN_JUNKS = "',-._\u2018\u2019/".toSet()
        val LEXICON_ORDS = setOf(39, 45) + (65..90).toSet() + (97..122).toSet()
        val CURRENCIES = mapOf(
            "$" to ("dollar" to "cent"),
            "£" to ("pound" to "pence"),
            "€" to ("euro" to "cent")
        )
        val ORDINALS = setOf("st", "nd", "rd", "th")
        val ADD_SYMBOLS = mapOf("." to "dot", "/" to "slash")
        val SYMBOLS = mapOf("%" to "percent", "&" to "and", "+" to "plus", "@" to "at")
    }

    private val golds: MutableMap<String, Any> = mutableMapOf()
    private val silvers: MutableMap<String, Any> = mutableMapOf()
    private val capStresses = Pair(0.5, 2.0)

    suspend fun load() {
        Log.d(TAG, "Loading lexicons (british=$british)...")
        val goldRaw = loadJson(if (british) "gb_gold.json" else "us_gold.json")
        val silverRaw = loadJson(if (british) "gb_silver.json" else "us_silver.json")
        golds.putAll(growDictionary(goldRaw))
        silvers.putAll(growDictionary(silverRaw))
        Log.d(TAG, "Loaded golds: ${golds.size}, silvers: ${silvers.size}")
    }

    // --- grow_dictionary: add capitalize/lowercase variants ---
    private fun growDictionary(d: Map<String, Any>): Map<String, Any> {
        val extra = mutableMapOf<String, Any>()
        for ((k, v) in d) {
            if (k.length < 2) continue
            if (k == k.lowercase(Locale.ROOT)) {
                val cap = k.capitalize(Locale.ROOT)
                if (k != cap) extra[cap] = v
            } else if (k == k.lowercase(Locale.ROOT).capitalize(Locale.ROOT)) {
                extra[k.lowercase(Locale.ROOT)] = v
            }
        }
        return extra + d
    }

    private fun loadJson(filename: String): Map<String, Any> {
        val map = mutableMapOf<String, Any>()
        try {
            context.assets.open(filename).bufferedReader().use { reader ->
                android.util.JsonReader(reader).use { jr ->
                    jr.beginObject()
                    while (jr.hasNext()) {
                        val key = jr.nextName()
                        when (jr.peek()) {
                            android.util.JsonToken.BEGIN_OBJECT -> {
                                val variantMap = mutableMapOf<String, String?>()
                                jr.beginObject()
                                while (jr.hasNext()) {
                                    val vKey = jr.nextName()
                                    if (jr.peek() == android.util.JsonToken.NULL) {
                                        jr.nextNull()
                                        variantMap[vKey] = null
                                    } else {
                                        variantMap[vKey] = jr.nextString()
                                    }
                                }
                                jr.endObject()
                                map[key] = variantMap
                            }
                            android.util.JsonToken.STRING -> map[key] = jr.nextString()
                            else -> jr.skipValue()
                        }
                    }
                    jr.endObject()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load $filename", e)
        }
        return map
    }

    // --- apply_stress ---
    fun applyStress(ps: String?, stress: Double?): String? {
        if (ps == null) return null
        if (stress == null) return ps

        fun restress(p: String): String {
            val ips = p.mapIndexed { i, c -> i to c }.toMutableList()
            val stressPositions = mutableMapOf<Int, Int>()
            for ((i, pair) in ips.withIndex()) {
                if (pair.second in STRESSES) {
                    val nextVowelIdx = ips.subList(i, ips.size).indexOfFirst { it.second in VOWELS }
                    if (nextVowelIdx >= 0) stressPositions[i] = i + nextVowelIdx
                }
            }
            for ((i, j) in stressPositions) {
                ips[i] = (j - 0.5).toInt() to ips[i].second
            }
            return ips.sortedBy { it.first }.map { it.second }.joinToString("")
        }

        return when {
            stress < -1 -> ps.replace(PRIMARY_STRESS, "").replace(SECONDARY_STRESS, "")
            stress == -1.0 || (stress in listOf(0.0, -0.5) && PRIMARY_STRESS in ps) ->
                ps.replace(SECONDARY_STRESS, "").replace(PRIMARY_STRESS, SECONDARY_STRESS)
            stress in listOf(0.0, 0.5, 1.0) && STRESSES.none { it in ps } -> {
                if (VOWELS.none { it in ps }) ps
                else restress(SECONDARY_STRESS + ps)
            }
            stress >= 1.0 && PRIMARY_STRESS !in ps && SECONDARY_STRESS in ps ->
                ps.replace(SECONDARY_STRESS, PRIMARY_STRESS)
            stress > 1.0 && STRESSES.none { it in ps } -> {
                if (VOWELS.none { it in ps }) ps
                else restress(PRIMARY_STRESS + ps)
            }
            else -> ps
        }
    }

    private fun stressWeight(ps: String?): Int {
        if (ps == null) return 0
        return ps.sumOf { if (it in DIPHTHONGS) 2L else 1L }.toInt()
    }

    // --- get_parent_tag ---
    fun getParentTag(tag: String?): String? {
        if (tag == null) return null
        if (tag.startsWith("VB")) return "VERB"
        if (tag.startsWith("NN")) return "NOUN"
        if (tag.startsWith("ADV") || tag.startsWith("RB")) return "ADV"
        if (tag.startsWith("ADJ") || tag.startsWith("JJ")) return "ADJ"
        return tag
    }

    // --- get_NNP ---
    fun getNNP(word: String): Pair<String?, Int?> {
        val charPhonemes = word.filter { it.isLetter() }.map { golds[it.uppercaseChar().toString()] }
        if (charPhonemes.any { it == null }) return Pair(null, null)
        val joined = charPhonemes.joinToString("") { it as String }
        var ps = applyStress(joined, 0.0)!!
        val parts = ps.split(SECONDARY_STRESS, limit = 2)
        if (parts.size > 1) {
            ps = parts[0] + PRIMARY_STRESS + parts[1]
        }
        return Pair(ps, 3)
    }

    // --- get_special_case ---
    fun getSpecialCase(word: String, tag: String?, stress: Double?, ctx: TokenContext?): Pair<String?, Int?> {
        if (tag == "ADD" && word in ADD_SYMBOLS) {
            return lookup(ADD_SYMBOLS[word]!!, null, -0.5, ctx)
        }
        if (word in SYMBOLS) {
            return lookup(SYMBOLS[word]!!, null, null, ctx)
        }
        val stripped = word.trim('.')
        if ('.' in stripped && stripped.replace(".", "").all { it.isLetter() }) {
            val maxPart = stripped.split(".").maxByOrNull { it.length }
            if (maxPart != null && maxPart.length < 3) {
                return getNNP(word)
            }
        }
        when (word) {
            "a", "A" -> return Pair(if (tag == "DT") "ɐ" else "${PRIMARY_STRESS}A", 4)
            "am", "Am", "AM" -> {
                if (tag?.startsWith("NN") == true) return getNNP(word)
                if (ctx?.futureVowel == null || word != "am" || (stress != null && stress > 0)) {
                    val goldVal = golds["am"]
                    return Pair(goldVal as? String, 4)
                }
                return Pair("ɐm", 4)
            }
            "an", "An", "AN" -> {
                if (word == "AN" && tag?.startsWith("NN") == true) return getNNP(word)
                return Pair("ɐn", 4)
            }
        }
        if (word == "I" && tag == "PRP") return Pair("${SECONDARY_STRESS}I", 4)
        if (word in setOf("by", "By", "BY") && getParentTag(tag) == "ADV") return Pair("b${PRIMARY_STRESS}I", 4)
        if (word in setOf("to", "To") || (word == "TO" && tag in setOf("TO", "IN"))) {
            val toVal: Any? = golds["to"]
            val base = toVal as? String
            val resolved = when (ctx?.futureVowel) {
                null -> base
                false -> "tə"
                true -> "tʊ"
            }
            return Pair(resolved, 4)
        }
        if (word in setOf("in", "In") || (word == "IN" && tag != "NNP")) {
            val stressMark = if (ctx?.futureVowel == null || tag != "IN") PRIMARY_STRESS else ""
            return Pair(stressMark + "ɪn", 4)
        }
        if (word in setOf("the", "The") || (word == "THE" && tag == "DT")) {
            return Pair(if (ctx?.futureVowel == true) "ði" else "ðə", 4)
        }
        if (tag == "IN" && word.matches(Regex("(?i)vs\\.?$"))) {
            return lookup("versus", null, null, ctx)
        }
        if (word in setOf("used", "Used", "USED")) {
            val usedVal = golds["used"]
            if (usedVal is Map<*, *>) {
                @Suppress("UNCHECKED_CAST")
                val usedMap = usedVal as Map<String, String?>
                if (tag in setOf("VBD", "JJ") && ctx?.futureTo == true) {
                    return Pair(usedMap["VBD"], 4)
                }
                return Pair(usedMap["DEFAULT"], 4)
            }
        }
        return Pair(null, null)
    }

    // --- is_known ---
    fun isKnown(word: String, tag: String? = null): Boolean {
        if (word in golds || word in SYMBOLS || word in silvers) return true
        if (!word.all { it.isLetter() } && !word.all { it.code in LEXICON_ORDS }) return false
        if (word.length == 1) return true
        if (word == word.uppercase(Locale.ROOT) && word.lowercase(Locale.ROOT) in golds) return true
        return word.drop(1) == word.drop(1).uppercase(Locale.ROOT)
    }

    // --- lookup ---
    fun lookup(word: String, tag: String?, stress: Double?, ctx: TokenContext?): Pair<String?, Int> {
        var lookupWord = word
        var isNNP = false

        if (word == word.uppercase(Locale.ROOT) && word !in golds) {
            lookupWord = word.lowercase(Locale.ROOT)
            isNNP = tag == "NNP"
        }

        var ps: Any? = golds[lookupWord]
        var rating = 4
        if (ps == null && !isNNP) {
            ps = silvers[lookupWord]
            rating = 3
        }

        if (ps is Map<*, *>) {
            @Suppress("UNCHECKED_CAST")
            val psMap = ps as Map<String, String?>
            var currentTag = tag
            if (ctx?.futureVowel == null && psMap.containsKey("None")) {
                currentTag = "None"
            } else if (currentTag != null && currentTag !in psMap) {
                currentTag = getParentTag(currentTag)
            }
            ps = psMap[currentTag] ?: psMap["DEFAULT"]
        }

        var phonemes = ps as? String

        if (phonemes == null || (isNNP && PRIMARY_STRESS !in (phonemes ?: ""))) {
            val (nnpPs, nnpRating) = getNNP(lookupWord)
            if (nnpPs != null) return Pair(nnpPs, nnpRating ?: rating)
        }

        return Pair(applyStress(phonemes, stress), rating)
    }

    // --- _s ---
    private fun _s(stem: String?): String? {
        if (stem.isNullOrEmpty()) return null
        val last = stem.last()
        return when {
            last in "ptkfθ" -> stem + "s"
            last in "szʃʒʧʤ" -> stem + (if (british) "ɪ" else "ᵻ") + "z"
            else -> stem + "z"
        }
    }

    private fun stemS(word: String, tag: String?, stress: Double?, ctx: TokenContext?): Pair<String?, Int?> {
        if (word.length < 3 || !word.endsWith("s")) return Pair(null, null)
        var stem: String? = null
        if (!word.endsWith("ss") && isKnown(word.dropLast(1), tag)) {
            stem = word.dropLast(1)
        } else if ((word.endsWith("'s") || (word.length > 4 && word.endsWith("es") && !word.endsWith("ies"))) && isKnown(word.dropLast(2), tag)) {
            stem = word.dropLast(2)
        } else if (word.length > 4 && word.endsWith("ies") && isKnown(word.dropLast(3) + "y", tag)) {
            stem = word.dropLast(3) + "y"
        } else {
            return Pair(null, null)
        }
        val (lookupStem, rating) = lookup(stem!!, tag, stress, ctx)
        return Pair(_s(lookupStem), rating)
    }

    // --- _ed ---
    private fun _ed(stem: String?): String? {
        if (stem.isNullOrEmpty()) return null
        val last = stem.last()
        return when {
            last in "pkfθʃsʧ" -> stem + "t"
            last == 'd' -> stem + (if (british) "ɪ" else "ᵻ") + "d"
            last != 't' -> stem + "d"
            british || stem.length < 2 -> stem + "ɪd"
            stem[stem.length - 2] in US_TAUS -> stem.dropLast(1) + "ɾᵻd"
            else -> stem + "ᵻd"
        }
    }

    private fun stemEd(word: String, tag: String?, stress: Double?, ctx: TokenContext?): Pair<String?, Int?> {
        if (word.length < 4 || !word.endsWith("d")) return Pair(null, null)
        var stem: String? = null
        if (!word.endsWith("dd") && isKnown(word.dropLast(1), tag)) {
            stem = word.dropLast(1)
        } else if (word.length > 4 && word.endsWith("ed") && !word.endsWith("eed") && isKnown(word.dropLast(2), tag)) {
            stem = word.dropLast(2)
        } else {
            return Pair(null, null)
        }
        val (lookupStem, rating) = lookup(stem!!, tag, stress, ctx)
        return Pair(_ed(lookupStem), rating)
    }

    // --- _ing ---
    private fun _ing(stem: String?): String? {
        if (stem.isNullOrEmpty()) return null
        if (british) {
            if (stem.last() in "əː") return null
        } else if (stem.length > 1 && stem.last() == 't' && stem[stem.length - 2] in US_TAUS) {
            return stem.dropLast(1) + "ɾɪŋ"
        }
        return stem + "ɪŋ"
    }

    private fun stemIng(word: String, tag: String?, stress: Double?, ctx: TokenContext?): Pair<String?, Int?> {
        if (word.length < 5 || !word.endsWith("ing")) return Pair(null, null)
        var stem: String? = null
        if (word.length > 5 && isKnown(word.dropLast(3), tag)) {
            stem = word.dropLast(3)
        } else if (isKnown(word.dropLast(3) + "e", tag)) {
            stem = word.dropLast(3) + "e"
        } else if (word.length > 5 &&
            Regex("([bcdgklmnprstvxz])\\1ing$|cking$").containsMatchIn(word) &&
            isKnown(word.dropLast(4), tag)
        ) {
            stem = word.dropLast(4)
        } else {
            return Pair(null, null)
        }
        val (lookupStem, rating) = lookup(stem!!, tag, stress, ctx)
        return Pair(_ing(lookupStem), rating)
    }

    // --- get_word ---
    fun getWord(word: String, tag: String?, stress: Double?, ctx: TokenContext?): Pair<String?, Int?> {
        val (specialPs, specialRating) = getSpecialCase(word, tag, stress, ctx)
        if (specialPs != null) return Pair(specialPs, specialRating)

        val wl = word.lowercase(Locale.ROOT)
        var effectiveWord = word
        if (word.length > 1 && word.replace("'", "").all { it.isLetter() } &&
            word != word.lowercase(Locale.ROOT) &&
            (tag != "NNP" || word.length > 7) &&
            word !in golds && word !in silvers &&
            (word == word.uppercase(Locale.ROOT) || word.drop(1) == word.drop(1).lowercase(Locale.ROOT)) &&
            (wl in golds || wl in silvers ||
                stemS(wl, tag, stress, ctx).first != null ||
                stemEd(wl, tag, stress, ctx).first != null ||
                stemIng(wl, tag, stress, ctx).first != null)
        ) {
            effectiveWord = wl
        }

        if (isKnown(effectiveWord, tag)) {
            return lookup(effectiveWord, tag, stress, ctx)
        }
        if (effectiveWord.endsWith("s'") && isKnown(effectiveWord.dropLast(2) + "'s", tag)) {
            return lookup(effectiveWord.dropLast(2) + "'s", tag, stress, ctx)
        }
        if (effectiveWord.endsWith("'") && isKnown(effectiveWord.dropLast(1), tag)) {
            return lookup(effectiveWord.dropLast(1), tag, stress, ctx)
        }

        val sRes = stemS(effectiveWord, tag, stress, ctx)
        if (sRes.first != null) return sRes

        val edRes = stemEd(effectiveWord, tag, stress, ctx)
        if (edRes.first != null) return edRes

        val ingStress = if (stress == null) 0.5 else stress
        val ingRes = stemIng(effectiveWord, tag, ingStress, ctx)
        if (ingRes.first != null) return ingRes

        return Pair(null, null)
    }

    // --- Number handling ---
    fun isNumber(word: String, isHead: Boolean): Boolean {
        if (word.none { it.isDigit() }) return false
        val suffixes = listOf("ing", "'d", "ed", "'s") + ORDINALS.toList() + "s"
        var w = word
        for (s in suffixes) {
            if (w.endsWith(s)) {
                w = w.dropLast(s.length)
                break
            }
        }
        return w.all { c -> c.isDigit() || c in ",." || (isHead && c == '-' && w.indexOf(c) == 0) }
    }

    fun isCurrency(word: String): Boolean {
        if ('.' !in word) return true
        if (word.count { it == '.' } > 1) return false
        val cents = word.split(".").getOrNull(1) ?: return false
        return cents.length < 3 || cents.all { it == '0' }
    }

    private fun isDigit(text: String) = text.all { it.isDigit() }

    private fun numericIfNeeded(c: Char): Char {
        if (!c.isDigit()) return c
        val n = try { Character.getNumericValue(c) } catch (_: Exception) { return c }
        return if (n >= 0) n.toString()[0] else c
    }

    fun getNumber(word: String, currency: String?, isHead: Boolean, numFlags: String): Pair<String?, Int?> {
        var remaining = word
        var suffix: String? = null
        val suffixMatch = Regex("[a-z']+$").find(remaining)
        if (suffixMatch != null) {
            suffix = suffixMatch.value
            remaining = remaining.dropLast(suffix.length)
        }

        val result = mutableListOf<Pair<String?, Int>>()
        if (remaining.startsWith("-")) {
            result.add(lookup("minus", null, null, null))
            remaining = remaining.drop(1)
        }

        fun extendNum(num: String, first: Boolean = true, escape: Boolean = false) {
            val words = if (escape) {
                num.split(Regex("[^a-z]+"))
            } else {
                Num2Words.convert(num.toLong()).split(Regex("[^a-z]+"))
            }
            for ((i, w) in words.withIndex()) {
                if (w.isEmpty()) continue
                if (w != "and" || '&' in numFlags) {
                    if (first && i == 0 && words.size > 1 && w == "one" && 'a' in numFlags) {
                        result.add("ə" to 4)
                    } else {
                        result.add(lookup(w, null, if (w == "point") -2.0 else null, null))
                    }
                } else if (w == "and" && 'n' in numFlags && result.isNotEmpty()) {
                    val last = result.last()
                    result[result.lastIndex] = (last.first?.let { it + "ən" } ?: last.first) to last.second
                }
            }
        }

        when {
            isDigit(remaining) && suffix in ORDINALS ->
                extendNum(Num2Words.toOrdinal(remaining.toLong()), escape = true)
            result.isEmpty() && remaining.length == 4 && currency !in CURRENCIES && isDigit(remaining) ->
                extendNum(Num2Words.toYear(remaining.toLong()), escape = true)
            !isHead && '.' !in remaining -> {
                val num = remaining.replace(",", "")
                if (num.startsWith("0") || num.length > 3) {
                    num.forEach { extendNum(it.toString(), first = false) }
                } else if (num.length == 3 && !num.endsWith("00")) {
                    extendNum(num[0].toString())
                    if (num[1] == '0') {
                        result.add(lookup("O", null, -2.0, null))
                        extendNum(num[2].toString(), first = false)
                    } else {
                        extendNum(num.substring(1), first = false)
                    }
                } else {
                    extendNum(num)
                }
            }
            remaining.count { it == '.' } > 1 || !isHead -> {
                var first = true
                for (num in remaining.replace(",", "").split(".")) {
                    if (num.isEmpty()) { /* skip */ }
                    else if (num[0] == '0' || (num.length != 2 && num.any { it != '0' })) {
                        num.forEach { extendNum(it.toString(), first = false) }
                    } else {
                        extendNum(num, first = first)
                    }
                    first = false
                }
            }
            currency in CURRENCIES && isCurrency(remaining) -> {
                val (curName, curCents) = CURRENCIES[currency]!!
                val parts = remaining.replace(",", "").split(".")
                val pairs = parts.mapIndexed { i, p ->
                    Pair(if (p.isEmpty()) 0 else p.toInt(), if (i == 0) curName else curCents)
                }.toMutableList()
                if (pairs.size > 1) {
                    if (pairs[1].first == 0) pairs.removeAt(1)
                    else if (pairs[0].first == 0) pairs.removeAt(0)
                }
                for ((i, pair) in pairs.withIndex()) {
                    if (i > 0) result.add(lookup("and", null, null, null))
                    extendNum(pair.first.toString(), first = i == 0)
                    val unitWord = if (kotlin.math.abs(pair.first) != 1 && pair.second != "pence")
                        _s(pair.second + "s") ?: pair.second
                    else pair.second
                    result.add(lookup(unitWord ?: pair.second, null, null, null))
                }
            }
            else -> {
                val wordForm = when {
                    isDigit(remaining) -> Num2Words.convert(remaining.toLong())
                    '.' !in remaining -> {
                        val num = remaining.replace(",", "")
                        if (suffix in ORDINALS) Num2Words.toOrdinal(num.toLong())
                        else Num2Words.convert(num.toLong())
                    }
                    else -> {
                        val cleaned = remaining.replace(",", "")
                        if (cleaned.startsWith(".")) {
                            "point " + cleaned.drop(1).map { Num2Words.convert(it.digitToInt().toLong()) }.joinToString(" ")
                        } else {
                            Num2Words.toFloat(cleaned.toDouble())
                        }
                    }
                }
                extendNum(wordForm, escape = true)
            }
        }

        if (result.isEmpty()) return Pair(null, null)

        var phonemes = result.mapNotNull { it.first }.joinToString(" ")
        val rating = result.mapNotNull { it.second }.minOrNull() ?: 4

        when (suffix) {
            "s", "'s" -> { _s(phonemes)?.let { return Pair(it, rating) } }
            "ed", "'d" -> { _ed(phonemes)?.let { return Pair(it, rating) } }
            "ing" -> { _ing(phonemes)?.let { return Pair(it, rating) } }
        }

        return Pair(phonemes, rating)
    }

    // --- append_currency ---
    fun appendCurrency(ps: String?, currency: String?): String? {
        if (currency == null || ps == null) return ps
        val (curName, _) = CURRENCIES[currency] ?: return ps
        val curPlural = _s(curName + "s") ?: curName
        return "$ps $curPlural"
    }

    // --- Main entry point: __call__ ---
    operator fun invoke(tk: MToken, ctx: TokenContext?): Pair<String?, Int?> {
        var word = (tk.attributes.alias ?: tk.text)
            .replace('\u2018', '\'').replace('\u2019', '\'')
        word = java.text.Normalizer.normalize(word, java.text.Normalizer.Form.NFKC)
        word = word.map { numericIfNeeded(it) }.joinToString("")

        val stress = if (word == word.lowercase(Locale.ROOT)) null
            else capStresses.let { (low, high) -> if (word == word.uppercase(Locale.ROOT)) high else low }

        var (ps, rating) = getWord(word, tk.tag, stress, ctx)
        if (ps != null) {
            return Pair(applyStress(appendCurrency(ps, tk.attributes.currency), tk.attributes.stress), rating)
        }
        if (isNumber(word, tk.attributes.isHead)) {
            val (numPs, numRating) = getNumber(word, tk.attributes.currency, tk.attributes.isHead, tk.attributes.numFlags)
            return Pair(applyStress(numPs, tk.attributes.stress), numRating)
        }
        if (!word.all { it.code in LEXICON_ORDS }) {
            return Pair(null, null)
        }
        return Pair(null, null)
    }
}