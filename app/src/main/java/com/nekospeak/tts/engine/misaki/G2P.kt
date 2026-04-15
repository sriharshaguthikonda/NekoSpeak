package com.nekospeak.tts.engine.misaki

import android.util.Log
import java.text.Normalizer
import java.util.regex.Pattern

/**
 * Faithful port of upstream Misaki's G2P class.
 * Reference: https://github.com/hexgrad/misaki/blob/main/misaki/en.py (G2P class)
 *
 * Full English G2P pipeline:
 * 1. Preprocess (markdown link removal, number normalization)
 * 2. Tokenize (simple heuristic tokenizer since we don't use spaCy on Android)
 * 3. Fold left (merge non-head tokens with preceding head)
 * 4. Retokenize (subtokenize each word using regex for compound splitting)
 * 5. G2P resolution (lexicon lookup → stemming fallback → eSpeak fallback)
 * 6. Resolve tokens (adjust stress for multi-token compounds)
 * 7. Assemble output string
 *
 * The Android port replaces:
 * - spaCy tokenizer → simple heuristic tokenizer with regex subtokenize
 * - BART fallback network → eSpeak fallback (already available via JNI)
 *
 * OutputMode controls post-processing:
 * - KOKORO: ɾ→T, ʔ→t (Kokoro/Kitten TTS models)
 * - IPA: Standard IPA (for Piper TTS models)
 */
enum class OutputMode {
    KOKORO,
    IPA
}

class G2P(
    private val lexicon: Lexicon,
    private val fallback: ((String) -> String?)? = null,
    private val unk: String = "❓"
) {
    companion object {
        private const val TAG = "G2P"

        // --- Constants from en.py ---
        private val SUBTOKEN_REGEX = Pattern.compile(
            "^['\u2018\u2019]+|\\p{Lu}(?=\\p{Lu}\\p{Ll})|(?:^-)?(?:\\d?[,.]?\\d)+|[-_]+|['\u2018\u2019]{2,}|\\p{L}*?(?:['\u2018\u2019]\\p{L})*?\\p{Ll}(?=\\p{Lu})|\\p{L}+(?:['\u2018\u2019]\\p{L})*|[^-_\\p{L}'\u2018\u2019\\d]|['\u2018\u2019]+\$"
        )

        private val LINK_REGEX = Regex("\\[([^\\]]+)\\]\\(([^)]*)\\)")
        private val NUMBER_REGEX = Pattern.compile("\\d+")

        private val SUBTOKEN_JUNKS = setOf('\'', ',', '-', '.', '_', '\u2018', '\u2019', '/')
        private val PUNCTS = setOf(';', ':', ',', '.', '!', '?', '—', '…', '"', '\u201C', '\u201D')
        private val NON_QUOTE_PUNCTS = PUNCTS.filter { it !in "\"\u201C\u201D" }.toSet()
        private val PUNCT_TAGS = setOf(".", ",", "-LRB-", "-RRB-", "``", "\"\"", "''", ":", "$", "#", "NFP")
        private val PUNCT_TAG_PHONEMES = mapOf(
            "-LRB-" to "(", "-RRB-" to ")",
            "``" to "\u201C", "\"\"" to "\u201D", "''" to "\u201D"
        )
        private val CONSONANTS = setOf('b','d','f','h','j','k','l','m','n','p','s','t','v','w','z','ð','ŋ','ɡ','ɹ','ɾ','ʃ','ʒ','ʤ','ʧ','θ')
        private val VOWELS = setOf('A','I','O','Q','W','Y','a','i','u','æ','ɑ','ɒ','ɔ','ə','ɛ','ɜ','ɪ','ʊ','ʌ','ᵻ')
        private val STRESSES = setOf('ˌ', 'ˈ')
        private val PRIMARY_STRESS = 'ˈ'
        private val SECONDARY_STRESS = 'ˌ'
        private val CURRENCIES = mapOf(
            '$' to ("dollar" to "cent"),
            '£' to ("pound" to "pence"),
            '€' to ("euro" to "cent")
        )
        private val ADD_SYMBOLS = mapOf('.' to "dot", '/' to "slash")
        private val SYMBOLS = mapOf('%' to "percent", '&' to "and", '+' to "plus", '@' to "at")
        private val ORDINALS = setOf("st", "nd", "rd", "th")
    }

    // --- merge_tokens ---
    private fun mergeTokens(tokens: List<MToken>): MToken {
        val stresses = tokens.mapNotNull { it.attributes.stress }.toSet()
        val currencies = tokens.mapNotNull { it.attributes.currency }.toSet()
        val ratings: List<Int> = tokens.mapNotNull { it.attributes.rating }

        val phonemes = if (unk == null) null
        else {
            buildString {
                for (tk in tokens) {
                    if (tk.attributes.prespace && isNotEmpty() && !last().isWhitespace() && tk.phonemes != null) {
                        append(' ')
                    }
                    append(if (tk.phonemes == null) unk else tk.phonemes)
                }
            }
        }

        val text = tokens.dropLast(1).joinToString("") { it.text + it.whitespace } + tokens.last().text
        val tag = tokens.maxByOrNull { tk -> tk.text.count { it.isUpperCase() } }?.tag ?: ""
        val combinedNumFlags = tokens.flatMap { it.attributes.numFlags.toList() }.toSet().sorted().joinToString("")

        return MToken(
            text = text,
            tag = tag,
            whitespace = tokens.last().whitespace,
            phonemes = phonemes,
            startTs = tokens.first().startTs,
            endTs = tokens.last().endTs,
            attributes = MToken.Underscore(
                isHead = tokens.first().attributes.isHead,
                alias = null,
                stress = stresses.singleOrNull(),
                currency = currencies.maxOrNull(),
                numFlags = combinedNumFlags,
                prespace = tokens.first().attributes.prespace,
                rating = ratings.minOrNull()
            )
        )
    }

    // --- stress_weight ---
    private fun stressWeight(ps: String?): Int {
        if (ps == null) return 0
        val diphthongs = setOf('A','I','O','Q','W','Y','ʤ','ʧ')
        return ps.sumOf { if (it in diphthongs) 2L else 1L }.toInt()
    }

    // --- subtokenize ---
    private fun subtokenize(word: String): List<String> {
        val matcher = SUBTOKEN_REGEX.matcher(word)
        val results = mutableListOf<String>()
        while (matcher.find()) {
            results.add(matcher.group())
        }
        return results.ifEmpty { listOf(word) }
    }

    // --- preprocess ---
    private fun preprocess(text: String): Triple<String, List<String>, Map<Int, Any>> {
        // 1. Remove markdown links [text](url) → text
        var processedText = text.trimStart()
        val tokens = mutableListOf<String>()
        val features = mutableMapOf<Int, Any>()
        var lastEnd = 0

        for (match in LINK_REGEX.findAll(processedText)) {
            tokens.addAll(processedText.substring(lastEnd, match.range.first).split(" ").filter { it.isNotEmpty() })
            val linkText = match.groupValues[1]
            val linkUrl = match.groupValues[2]

            // Parse feature from URL
            val f: Any? = when {
                linkUrl.matches(Regex("^\\d.*")) && linkUrl.all { it.isDigit() || it in "+-" } ->
                    linkUrl.toIntOrNull()
                linkUrl in listOf("0.5", "+0.5") -> 0.5
                linkUrl == "-0.5" -> -0.5
                linkUrl.startsWith("/") && linkUrl.endsWith("/") && linkUrl.length > 1 ->
                    "/" + linkUrl.drop(1).trimEnd('/')
                linkUrl.startsWith("#") && linkUrl.endsWith("#") && linkUrl.length > 1 ->
                    "#" + linkUrl.drop(1).trimEnd('#')
                else -> null
            }

            if (f != null) {
                features[tokens.size] = f
            }

            tokens.add(linkText)
            lastEnd = match.range.last + 1
        }

        if (lastEnd < processedText.length) {
            tokens.addAll(processedText.substring(lastEnd).split(" ").filter { it.isNotEmpty() })
        }

        // Rebuild text without URLs
        processedText = LINK_REGEX.replace(processedText) { it.groupValues[1] }

        // 2. Normalize numbers → words
        val numMatcher = NUMBER_REGEX.matcher(processedText)
        val sb = StringBuffer()
        while (numMatcher.find()) {
            val numStr = numMatcher.group()
            if (numStr.length < 18) {
                val num = numStr.toLongOrNull()
                if (num != null) {
                    val words = Num2Words.convert(num)
                    numMatcher.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(words))
                }
            }
        }
        numMatcher.appendTail(sb)
        processedText = sb.toString()

        return Triple(processedText, tokens, features)
    }

    // --- Simple tokenizer (Android replacement for spaCy) ---
    private fun simpleTokenize(text: String): List<MToken> {
        val tokens = mutableListOf<MToken>()
        // Split by whitespace, then split punctuation off words
        val rawParts = text.split(Regex("(?<=\\s)|(?=\\s)"))
        for (raw in rawParts) {
            val t = raw.trim()
            if (t.isEmpty()) continue
            val parts = splitPunctuation(t)
            tokens.addAll(parts.map {
                MToken(
                    text = it,
                    tag = guessTag(it),
                    whitespace = if (raw.endsWith(" ")) " " else "",
                    attributes = MToken.Underscore(isHead = true, numFlags = "", prespace = false)
                )
            })
        }
        // Fix whitespace attribution
        for (i in 0 until tokens.size - 1) {
            val old = tokens[i]
            tokens[i] = MToken(
                old.text, old.tag, " ", old.phonemes, old.startTs, old.endTs, old.attributes
            )
        }
        if (tokens.isNotEmpty()) {
            val last = tokens.last()
            tokens[tokens.lastIndex] = MToken(
                last.text, last.tag, "", last.phonemes, last.startTs, last.endTs, last.attributes
            )
        }
        return tokens
    }

    private fun splitPunctuation(word: String): List<String> {
        if (word.length == 1) return listOf(word)
        val p = Pattern.compile("^(\\p{Punct}*)(.*?)(\\p{Punct}*)$")
        val m = p.matcher(word)
        if (m.find()) {
            val pre = m.group(1) ?: ""
            val mid = m.group(2) ?: ""
            val post = m.group(3) ?: ""
            val res = mutableListOf<String>()
            if (pre.isNotEmpty()) res.add(pre)
            if (mid.isNotEmpty()) res.add(mid)
            if (post.isNotEmpty()) res.add(post)
            return res
        }
        return listOf(word)
    }

    private fun guessTag(word: String): String {
        if (word.all { !it.isLetterOrDigit() }) {
            if (word in PUNCT_TAG_PHONEMES) return word
            if (word == ".") return "."
            if (word == ",") return ","
            return ":"
        }
        if (word.all { it.isDigit() }) return "CD"
        if (word.equals("the", ignoreCase = true)) return "DT"
        if (word.equals("a", ignoreCase = true) || word.equals("an", ignoreCase = true)) return "DT"
        if (word.endsWith("ing")) return "VBG"
        if (word.endsWith("ed")) return "VBD"
        if (word.endsWith("ly")) return "RB"
        if (word.isNotEmpty() && word[0].isUpperCase()) return "NNP"
        return "NN"
    }

    // --- fold_left ---
    private fun foldLeft(tokens: List<MToken>): List<MToken> {
        val result = mutableListOf<MToken>()
        for (tk in tokens) {
            if (result.isNotEmpty() && !tk.attributes.isHead) {
                result[result.lastIndex] = mergeTokens(listOf(result.last(), tk))
            } else {
                result.add(tk)
            }
        }
        return result
    }

    // --- retokenize ---
    private fun retokenize(tokens: List<MToken>): List<Any> {
        // Returns list of MToken or List<MToken> (for compound resolution)
        val words = mutableListOf<Any>()
            var currency: Char? = null

            for ((i, token) in tokens.withIndex()) {
                val tks = (if (token.attributes.alias == null && token.phonemes == null) {
                    subtokenize(token.text).mapIndexed { j, sub ->
                        val newAttrs = MToken.Underscore(
                            isHead = j == 0,
                            numFlags = token.attributes.numFlags,
                            stress = token.attributes.stress,
                            prespace = false
                        )
                        MToken(sub, token.tag, "", null, token.startTs, token.endTs, newAttrs)
                    }
                } else {
                    listOf(token)
                }).toMutableList()
                // Last subtoken inherits whitespace
                if (tks.isNotEmpty()) {
                    val lastTk = tks.last()
                    tks[tks.lastIndex] = replaceMToken(lastTk, whitespace = token.whitespace)
                }

            for ((j, tk) in tks.withIndex()) {
                when {
                    tk.attributes.alias != null || tk.phonemes != null -> {
                        // Already resolved
                    }
                    tk.tag == "$" && tk.text.singleOrNull() in CURRENCIES -> {
                        currency = tk.text.single()
                        tk.phonemes = ""
                        tk.attributes.rating = 4
                    }
                    tk.tag == ":" && tk.text in setOf("-", "–") -> {
                        tk.phonemes = "—"
                        tk.attributes.rating = 3
                    }
                    tk.tag in PUNCT_TAGS && !tk.text.all { it.lowercaseChar() in 'a'..'z' } -> {
                        tk.phonemes = PUNCT_TAG_PHONEMES[tk.tag]
                            ?: tk.text.filter { it in PUNCTS }
                        tk.attributes.rating = 4
                    }
                    currency != null -> {
                        if (tk.tag != "CD") {
                            currency = null
                        } else if (j == tks.lastIndex &&
                            (i == tokens.lastIndex || tokens[i + 1].tag != "CD")) {
                            tk.attributes.currency = currency.toString()
                        }
                    }
                    j in 1 until tks.lastIndex && tk.text == "2" &&
                        (tks[j - 1].text.last().isLetter()) && (tks[j + 1].text.first().isLetter()) -> {
                        tk.attributes.alias = "to"
                    }
                }

                if (tk.attributes.alias != null || tk.phonemes != null) {
                    words.add(tk)
                } else if (words.isNotEmpty() && words.last() is List<*> &&
                    !(words.last() as List<*>).lastMToken().whitespace.isNotEmpty()) {
                    tk.attributes.isHead = false
                    @Suppress("UNCHECKED_CAST")
                    (words.last() as MutableList<MToken>).add(tk)
                } else {
                    if (tk.whitespace.isNotEmpty()) words.add(tk)
                    else words.add(mutableListOf(tk))
                }
            }
        }

        // Unwrap single-element lists
        return words.map { w ->
            if (w is List<*> && w.size == 1) w[0]!! else w
        }
    }

    private fun Any.lastMToken(): MToken = when (this) {
        is MToken -> this
        is List<*> -> this.last() as MToken
        else -> throw IllegalArgumentException()
    }

    // --- token_context ---
    private fun tokenContext(ctx: TokenContext, ps: String?, token: MToken): TokenContext {
        var vowel = ctx.futureVowel
        if (ps != null) {
            for (c in ps) {
                if (c in VOWELS) { vowel = true; break }
                else if (c in CONSONANTS) { vowel = false; continue }
                else if (c in NON_QUOTE_PUNCTS) { vowel = null; continue }
                else continue
            }
        }
        val futureTo = token.text in setOf("to", "To") ||
            (token.text == "TO" && token.tag in setOf("TO", "IN"))
        return TokenContext(futureVowel = vowel, futureTo = futureTo)
    }

    // --- resolve_tokens ---
    private fun resolveTokens(tokens: List<MToken>) {
        val text = tokens.dropLast(1).joinToString("") { it.text + it.whitespace } + tokens.last().text
        val prespace = ' ' in text || '/' in text ||
            run {
                val filtered = text.filter { it !in SUBTOKEN_JUNKS }
                val groupMap = mutableMapOf<Int, Int>()
                for (c in filtered) {
                    val key = if (c.isLetter()) 0 else if (c.isDigit()) 1 else 2
                    groupMap[key] = (groupMap[key] ?: 0) + 1
                }
                groupMap.size > 1
            }

        for ((i, tk) in tokens.withIndex()) {
            if (tk.phonemes == null) {
                if (i == tokens.lastIndex && tk.text.any { it in NON_QUOTE_PUNCTS }) {
                    tk.phonemes = tk.text
                    tk.attributes.rating = 3
                } else if (tk.text.all { it in SUBTOKEN_JUNKS }) {
                    tk.phonemes = ""
                    tk.attributes.rating = 3
                }
            } else if (i > 0) {
                tk.attributes.prespace = prespace
            }
        }

        if (prespace) return

        val indices = tokens.mapIndexedNotNull { i, tk ->
            if (tk.phonemes != null) Triple(PRIMARY_STRESS in (tk.phonemes ?: ""), stressWeight(tk.phonemes), i)
            else null
        }

        if (indices.size == 2 && tokens[indices[0].third].text.length == 1) {
            val i = indices[1].third
            tokens[i].phonemes = lexicon.applyStress(tokens[i].phonemes, -0.5)
            return
        }
        if (indices.size < 2 || indices.count { it.first } <= (indices.size + 1) / 2) return

        val sorted = indices.sortedBy { (if (it.first) 1 else 0) + it.second }
        for ((_, _, i) in sorted.take(indices.size / 2)) {
            tokens[i].phonemes = lexicon.applyStress(tokens[i].phonemes, -0.5)
        }
    }

    // --- Main phonemize entry point ---
    fun phonemize(text: String, mode: OutputMode = OutputMode.KOKORO): String {
        // 1. Preprocess
        val (preprocessedText, _, features) = preprocess(text)

        // 2. Tokenize
        var mTokens = simpleTokenize(preprocessedText)

        // 3. Fold left
        mTokens = foldLeft(mTokens)

        // 4. Retokenize
        val retokenized = retokenize(mTokens)

        // 5. G2P resolution (iterate in reverse for context lookahead)
        var ctx = TokenContext()
        for ((i, w) in retokenized.withIndex().reversed()) {
            if (w !is List<*>) {
                val token = w as MToken
                if (token.phonemes == null) {
                    val (ps, rating) = lexicon(replaceMToken(token), ctx)
                    token.phonemes = ps
                    if (rating != null) token.attributes.rating = rating
                }
                if (token.phonemes == null && fallback != null) {
                    val fbPs = fallback.invoke(token.text)
                    if (fbPs != null) {
                        token.phonemes = fbPs
                        token.attributes.rating = 1
                    }
                }
                ctx = tokenContext(ctx, token.phonemes, token)
                continue
            }

            // Complex word: try to resolve as a compound
            @Suppress("UNCHECKED_CAST")
            val wordTokens = w as List<MToken>
            var left = 0
            var right = wordTokens.size
            var shouldFallback = false

            while (left < right) {
                val subList = wordTokens.subList(left, right)
                val hasResolved = subList.any { it.attributes.alias != null || it.phonemes != null }

                val merged = if (!hasResolved) mergeTokens(subList) else null
                val (ps, rating) = if (merged != null) lexicon(merged, ctx) else Pair(null, null)

                if (ps != null) {
                    wordTokens[left].phonemes = ps
                    wordTokens[left].attributes.rating = rating
                    for (x in left + 1 until right) {
                        wordTokens[x].phonemes = ""
                        wordTokens[x].attributes.rating = rating
                    }
                    ctx = tokenContext(ctx, ps, merged ?: wordTokens[left])
                    right = left
                    left = 0
                } else if (left + 1 < right) {
                    left++
                } else {
                    right--
                    val tk = wordTokens[right]
                    if (tk.phonemes == null) {
                        if (tk.text.all { it in SUBTOKEN_JUNKS }) {
                            tk.phonemes = ""
                            tk.attributes.rating = 3
                        } else if (fallback != null) {
                            shouldFallback = true
                            break
                        }
                    }
                    left = 0
                }
            }

            if (shouldFallback) {
                val merged = mergeTokens(wordTokens)
                val fbPs = fallback?.invoke(merged.text)
                if (fbPs != null) {
                    wordTokens[0].phonemes = fbPs
                    wordTokens[0].attributes.rating = 1
                    for (j in 1 until wordTokens.size) {
                        wordTokens[j].phonemes = ""
                        wordTokens[j].attributes.rating = 1
                    }
                }
            } else {
                resolveTokens(wordTokens)
            }
        }

        // 6. Merge all tokens
        val finalTokens = retokenized.map { w ->
            if (w is List<*>) mergeTokens(w.filterIsInstance<MToken>()) else w as MToken
        }

        // 7. Assemble output
        val result = StringBuilder()
        for (tk in finalTokens) {
            val ps = if (tk.phonemes == null) unk else tk.phonemes
            result.append(ps)
            result.append(tk.whitespace)
        }

        val out = result.toString().trim()

        return when (mode) {
            OutputMode.KOKORO -> out.replace('ɾ', 'T').replace('ʔ', 't')
            OutputMode.IPA -> out
        }
    }
}