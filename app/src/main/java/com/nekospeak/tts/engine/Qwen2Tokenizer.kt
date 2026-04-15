package com.nekospeak.tts.engine

import android.content.Context
import android.util.Log
import java.util.regex.Pattern

/**
 * Qwen2 BPE Tokenizer for OmniVoice TTS.
 *
 * Faithful port of the HuggingFace tokenizers BPE pipeline used in
 * vocoloco_tts (via @huggingface/transformers AutoTokenizer).
 *
 * The tokenizer uses:
 * - GPT-2-style byte-level BPE with 151,643 base vocab + 33 added tokens
 * - Regex pre-tokenization matching Qwen2's contractive + word pattern
 * - NFC normalization
 * - Special tokens for OmniVoice control: <|denoise|>, <|lang_start|>,
 *   <|lang_end|>, <|instruct_start|>, <|instruct_end|>, <|text_start|>,
 *   <|text_end|>
 *
 * Loads from two compact asset files:
 * - omnivoice_vocab.tsv: token→id mapping (151,642 lines, ~2.4MB)
 * - omnivoice_merges.txt: BPE merge rules (151,386 lines, ~1.6MB)
 *
 * Reference: https://github.com/Magkino/vocoloco_tts
 * Tokenizer: https://huggingface.co/Gigsu/vocoloco-onnx/blob/main/tokenizer.json
 */
class Qwen2Tokenizer private constructor() {
    companion object {
        private const val TAG = "Qwen2Tokenizer"

        // Special token IDs (from tokenizer.json added_tokens)
        const val TOKEN_DENOOSE = 151669L
        const val TOKEN_LANG_START = 151670L
        const val TOKEN_LANG_END = 151671L
        const val TOKEN_INSTRUCT_START = 151672L
        const val TOKEN_INSTRUCT_END = 151673L
        const val TOKEN_TEXT_START = 151674L
        const val TOKEN_TEXT_END = 151675L

        // GPT-2 byte-level encoding: maps byte values to unicode characters
        // This avoids the <0x00>...<0xFF> approach used by some tokenizers
        private val BYTE_ENCODER: Map<Int, Char> by lazy { buildByteEncoder() }
        private val BYTE_DECODER: Map<Char, Int> by lazy { BYTE_ENCODER.entries.associate { (k, v) -> v to k } }

        // Qwen2 pre-tokenization regex (same as GPT-2)
        // Matches: contractions, letter sequences, digit sequences,
        // non-letter/non-digit sequences, newlines, trailing spaces
        private val PAT = Pattern.compile(
            """(?i:'s|'t|'re|'ve|'m|'ll|'d)|[^\r\n\p{L}\p{N}]?\p{L}+|\p{N}| ?[^\s\p{L}\p{N}]+[\r\n]*|\s*[\r\n]+|\s+(?!\S)|\s+"""
        )

        private var instance: Qwen2Tokenizer? = null

        /** Get or create the singleton tokenizer instance. */
        fun getInstance(context: Context): Qwen2Tokenizer {
            if (instance == null || !instance!!.loaded) {
                instance = Qwen2Tokenizer().also { it.load(context) }
            }
            return instance!!
        }
    }

    // Vocab: token string → ID
    private val vocab = mutableMapOf<String, Long>()
    // Inverse vocab: ID → token string
    private val idToToken = mutableMapOf<Long, String>()
    // BPE merge ranks: "token1 token2" → rank (lower = higher priority)
    private val bpeRanks = mutableMapOf<String, Int>()
    // BPE cache: input word → BPE result
    private val bpeCache = mutableMapOf<String, String>()
    // Special tokens
    private val specialTokens = mapOf(
        "<|denoise|>" to TOKEN_DENOOSE,
        "<|lang_start|>" to TOKEN_LANG_START,
        "<|lang_end|>" to TOKEN_LANG_END,
        "<|instruct_start|>" to TOKEN_INSTRUCT_START,
        "<|instruct_end|>" to TOKEN_INSTRUCT_END,
        "<|text_start|>" to TOKEN_TEXT_START,
        "<|text_end|>" to TOKEN_TEXT_END,
        "<|im_start|>" to 151644L,
        "<|im_end|>" to 151645L
    )

    var loaded = false
        private set

    /** Load vocab and merges from assets. */
    fun load(context: Context) {
        if (loaded) return
        val startTime = System.currentTimeMillis()

        try {
            // Load vocab
            context.assets.open("omnivoice_vocab.tsv").bufferedReader().use { reader ->
                reader.forEachLine { line ->
                    val tabIdx = line.lastIndexOf('\t')
                    if (tabIdx < 0) return@forEachLine
                    val token = line.substring(0, tabIdx)
                        .replace("\\t", "\t").replace("\\n", "\n").replace("\\\\", "\\")
                    val id = line.substring(tabIdx + 1).toLongOrNull() ?: return@forEachLine
                    vocab[token] = id
                    idToToken[id] = token
                }
            }

            // Add special tokens to vocab
            for ((token, id) in specialTokens) {
                vocab[token] = id
                idToToken[id] = token
            }

            // Load merges
            context.assets.open("omnivoice_merges.txt").bufferedReader().use { reader ->
                var rank = 0
                reader.forEachLine { line ->
                    if (line.isNotBlank()) {
                        bpeRanks[line] = rank++
                    }
                }
            }

            loaded = true
            val elapsed = System.currentTimeMillis() - startTime
            Log.d(TAG, "Loaded: ${vocab.size} vocab, ${bpeRanks.size} merges (${elapsed}ms)")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to load tokenizer: ${e.message}")
            loaded = false
        }
    }

    /**
     * Encode text to token IDs using Qwen2 BPE.
     *
     * @param text Input text
     * @param addSpecialTokens Whether to add BOS/EOS (default: false for OmniVoice)
     * @return Array of token IDs
     */
    fun encode(text: String, addSpecialTokens: Boolean = false): LongArray {
        if (!loaded) return longArrayOf()
        if (text.isBlank()) return longArrayOf()

        val tokens = mutableListOf<Long>()

        // Pre-tokenize using Qwen2 regex
        val matcher = PAT.matcher(text)
        while (matcher.find()) {
            val word = matcher.group() ?: continue
            // Convert to byte-level representation
            val byteEncoded = word.map { ch -> BYTE_ENCODER[ch.code] ?: ch }.joinToString("")
            // Apply BPE
            val bpeResult = bpe(byteEncoded)
            // Look up each BPE token in vocab
            for (bpeToken in bpeResult.split(" ")) {
                if (bpeToken.isBlank()) continue
                val id = vocab[bpeToken]
                if (id != null) {
                    tokens.add(id)
                } else {
                    // Unknown: encode character by character using byte fallback
                    for (ch in bpeToken) {
                        val charId = vocab[ch.toString()]
                        if (charId != null) tokens.add(charId)
                    }
                }
            }
        }

        return tokens.toLongArray()
    }

    /**
     * Encode a special control string for OmniVoice.
     * Handles <|denoise|>, <|lang_start|>en<|lang_end|>, etc.
     *
     * Example: encodeStyle(lang="en", instruct="female, low pitch")
     * → <|denoise|><|lang_start|>en<|lang_end|><|instruct_start|>female, low pitch<|instruct_end|>
     */
    fun encodeStyle(lang: String? = null, instruct: String? = null): LongArray {
        val tokens = mutableListOf<Long>()
        tokens.add(TOKEN_DENOOSE)

        // Language marker
        tokens.add(TOKEN_LANG_START)
        if (lang != null && lang != "None") {
            tokens.addAll(encode(lang).toList())
        }
        tokens.add(TOKEN_LANG_END)

        // Instruct marker (voice design: gender, pitch, etc.)
        tokens.add(TOKEN_INSTRUCT_START)
        if (instruct != null && instruct != "None") {
            tokens.addAll(encode(instruct).toList())
        }
        tokens.add(TOKEN_INSTRUCT_END)

        return tokens.toLongArray()
    }

    /**
     * Encode text wrapped in <|text_start|>...<|text_end|> markers.
     */
    fun encodeText(text: String): LongArray {
        val tokens = mutableListOf<Long>()
        tokens.add(TOKEN_TEXT_START)
        tokens.addAll(encode(text).toList())
        tokens.add(TOKEN_TEXT_END)
        return tokens.toLongArray()
    }

    /**
     * Encode reference text + synthesis text together (for voice cloning).
     * Layout: <|text_start|>{refText} {text}<|text_end|>
     */
    fun encodeTextWithRef(text: String, refText: String? = null): LongArray {
        val fullText = if (refText != null) {
            (refText.trim() + " " + text.trim())
                .replace(Regex("[\\r\\n]+"), "")
                .replace(Regex("[ \\t]+"), " ")
        } else {
            text.trim()
                .replace(Regex("[\\r\\n]+"), "")
                .replace(Regex("[ \\t]+"), " ")
        }
        return encodeText(fullText)
    }

    /**
     * Decode token IDs back to text (for debugging).
     */
    fun decode(ids: LongArray): String {
        val sb = StringBuilder()
        for (id in ids) {
            val token = idToToken[id] ?: continue
            if (token in specialTokens.keys) {
                sb.append(token)
            } else {
                // Byte-level decode
                for (ch in token) {
                    val byteVal = BYTE_DECODER[ch]
                    if (byteVal != null) {
                        sb.append(byteVal.toInt().toChar())
                    } else {
                        sb.append(ch)
                    }
                }
            }
        }
        return sb.toString()
    }

    // ========================================================================
    // BPE algorithm
    // ========================================================================

    /** Apply BPE to a single word (already byte-level encoded). */
    private fun bpe(token: String): String {
        bpeCache[token]?.let { return it }

        val word = token.map { it.toString() }.toMutableList()
        if (word.size <= 1) {
            val result = word.joinToString(" ")
            bpeCache[token] = result
            return result
        }

        var wordList = word
        while (true) {
            // Find the pair with the lowest merge rank
            var bestPair = ""
            var bestRank = Int.MAX_VALUE
            var bestIdx = -1

            for (i in 0 until wordList.size - 1) {
                val pair = wordList[i] + " " + wordList[i + 1]
                val rank = bpeRanks[pair] ?: continue
                if (rank < bestRank) {
                    bestRank = rank
                    bestPair = pair
                    bestIdx = i
                }
            }

            if (bestIdx < 0) break  // No more merges possible

            // Merge the best pair
            val newWord = mutableListOf<String>()
            var i = 0
            while (i < wordList.size) {
                if (i < wordList.size - 1 && i == bestIdx && wordList[i] + " " + wordList[i + 1] == bestPair) {
                    newWord.add(wordList[i] + wordList[i + 1])
                    i += 2
                } else {
                    newWord.add(wordList[i])
                    i++
                }
            }
            wordList = newWord

            if (wordList.size == 1) break
        }

        val result = wordList.joinToString(" ")
        bpeCache[token] = result
        return result
    }

    // ========================================================================
    // Byte-level encoding (GPT-2 style)
    // ========================================================================

    /**
     * Build the byte→unicode mapping used by GPT-2 BPE.
     * Maps bytes 0-255 to unicode characters, avoiding control characters
     * and ensuring all bytes have a visible representation.
     */
    private fun buildByteEncoder(): Map<Int, Char> {
        // Start with printable ASCII ranges that map to themselves
        val bs = mutableListOf<Int>()
        // '!' to '~' (33-126)
        for (i in 33..126) bs.add(i)
        // '¡' to '¬' (161-172)
        for (i in 161..172) bs.add(i)
        // '®' to 'ÿ' (174-255)
        for (i in 174..255) bs.add(i)

        val cs = bs.toIntArray()
        var n = 0
        // Map remaining bytes (0-32, 127-160, 173) to 256+ unicode range
        for (b in 0..255) {
            if (b !in bs) {
                cs += (256 + n)
                n++
            }
        }

        // Build the mapping
        val encoder = mutableMapOf<Int, Char>()
        for (i in bs.indices) {
            encoder[bs[i]] = cs[i].toChar()
        }
        // Map the non-printable bytes
        n = 0
        for (b in 0..255) {
            if (b !in bs) {
                encoder[b] = (256 + n).toChar()
                n++
            }
        }
        return encoder
    }
}