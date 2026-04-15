package com.nekospeak.tts.engine

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.Normalizer
import java.util.*
import com.nekospeak.tts.engine.misaki.G2P
import com.nekospeak.tts.engine.misaki.Lexicon
import com.nekospeak.tts.engine.misaki.OutputMode
import com.nekospeak.tts.engine.misaki.JaG2P
import com.nekospeak.tts.engine.misaki.ZhG2P
import com.nekospeak.tts.engine.misaki.KoG2P
import com.nekospeak.tts.engine.misaki.CmuDict
import com.nekospeak.tts.engine.misaki.ViG2P
import com.nekospeak.tts.engine.misaki.HeG2P

class Phonemizer(private val context: Context) {
    
    companion object {
        private const val TAG = "Phonemizer"
        private const val MAX_PHONEME_LENGTH = 400
        
        // Arabic Unicode range for diacritization preprocessing
        // Fixes: https://github.com/siva-sub/NekoSpeak/issues/8
        private val ARABIC_REGEX = Regex("[\\u0600-\\u06FF\\u0750-\\u077F\\u08A0-\\u08FF\\uFB50-\\uFDFF\\uFE70-\\uFEFF]")
    }
    
    // Default vocabulary mapping phonemes to tokens
    // Official Kokoro/Kitten TTS Vocabulary (0-177)
    private val defaultVocab = mapOf(
        "$" to 0, ";" to 1, ":" to 2, "," to 3, "." to 4, "!" to 5, "?" to 6, "¡" to 7, "¿" to 8, "—" to 9,
        "…" to 10, "\"" to 11, "«" to 12, "»" to 13, """ to 14, """ to 15, " " to 16,
        "A" to 17, "B" to 18, "C" to 19, "D" to 20, "E" to 21, "F" to 22, "G" to 23, "H" to 24, "I" to 25,
        "J" to 26, "K" to 27, "L" to 28, "M" to 29, "N" to 30, "O" to 31, "P" to 32, "Q" to 33, "R" to 34,
        "S" to 35, "T" to 36, "U" to 37, "V" to 38, "W" to 39, "X" to 40, "Y" to 41, "Z" to 42,
        "a" to 43, "b" to 44, "c" to 45, "d" to 46, "e" to 47, "f" to 48, "g" to 49, "h" to 50, "i" to 51,
        "j" to 52, "k" to 53, "l" to 54, "m" to 55, "n" to 56, "o" to 57, "p" to 58, "q" to 59, "r" to 60,
        "s" to 61, "t" to 62, "u" to 63, "v" to 64, "w" to 65, "x" to 66, "y" to 67, "z" to 68,
        "ɑ" to 69, "ɐ" to 70, "ɒ" to 71, "æ" to 72, "ɓ" to 73, "ʙ" to 74, "β" to 75, "ɔ" to 76, "ɕ" to 77,
        "ç" to 78, "ɗ" to 79, "ɖ" to 80, "ð" to 81, "ʤ" to 82, "ə" to 83, "ɘ" to 84, "ɚ" to 85, "ɛ" to 86,
        "ɜ" to 87, "ɝ" to 88, "ɞ" to 89, "ɟ" to 90, "ʄ" to 91, "ɡ" to 92, "ɠ" to 93, "ɢ" to 94, "ʛ" to 95,
        "ɦ" to 96, "ɧ" to 97, "ħ" to 98, "ɥ" to 99, "ʜ" to 100, "ɨ" to 101, "ɪ" to 102, "ʝ" to 103, "ɭ" to 104,
        "ɬ" to 105, "ɫ" to 106, "ɮ" to 107, "ʟ" to 108, "ɱ" to 109, "ɯ" to 110, "ɰ" to 111, "ŋ" to 112, "ɳ" to 113,
        "ɲ" to 114, "ɴ" to 115, "ø" to 116, "ɵ" to 117, "ɸ" to 118, "θ" to 119, "œ" to 120, "ɶ" to 121, "ʘ" to 122,
        "ɹ" to 123, "ɺ" to 124, "ɾ" to 125, "ɻ" to 126, "ʀ" to 127, "ʁ" to 128, "ɽ" to 129, "ʂ" to 130, "ʃ" to 131,
        "ʈ" to 132, "ʧ" to 133, "ʉ" to 134, "ʊ" to 135, "ʋ" to 136, "ⱱ" to 137, "ʌ" to 138, "ɣ" to 139, "ɤ" to 140,
        "ʍ" to 141, "χ" to 142, "ʎ" to 143, "ʏ" to 144, "ʑ" to 145, "ʐ" to 146, "ʒ" to 147, "ʔ" to 148, "ʡ" to 149,
        "ʕ" to 150, "ʢ" to 151, "ǀ" to 152, "ǁ" to 153, "ǂ" to 154, "ǃ" to 155, "ˈ" to 156, "ˌ" to 157, "ː" to 158,
        "ˑ" to 159, "ʼ" to 160, "ʴ" to 161, "ʰ" to 162, "ʱ" to 163, "ʲ" to 164, "ʷ" to 165, "ˠ" to 166, "ˤ" to 167,
        "˞" to 168, "↓" to 169, "↑" to 170, "→" to 171, "↗" to 172, "↘" to 173, "'" to 176, "̩" to 175, "ᵻ" to 177
    )
    
    private lateinit var g2pUS: G2P
    private lateinit var g2pGB: G2P
    private lateinit var espeak: EspeakWrapper

    // Language-specific G2P engines (Misaki ports)
    private lateinit var jaG2P: JaG2P
    private lateinit var zhG2P: ZhG2P
    private lateinit var koG2P: KoG2P
    private lateinit var viG2P: ViG2P
    private lateinit var heG2P: HeG2P

    private var isLoaded = false

    suspend fun load() = withContext(Dispatchers.IO) {
        if (isLoaded) return@withContext
        try {
            // Espeak Initialization
            val dataDir = java.io.File(context.filesDir, "espeak-ng-data")
            if (!dataDir.exists()) {
                Log.i(TAG, "Extracting espeak-ng-data...")
                com.nekospeak.tts.utils.AssetUtils.extractAssets(context, "espeak-ng-data", context.filesDir)
            }
            
            espeak = EspeakWrapper()
            val initRes = espeak.initializeSafe(context.filesDir.absolutePath)
            if (initRes < 0) {
                 Log.e(TAG, "Espeak init failed: $initRes")
            } else {
                 Log.i(TAG, "Espeak initialized with data in ${context.filesDir.absolutePath}")
            }

            // English G2P (Misaki port)
            val usLexicon = Lexicon(context, british = false)
            usLexicon.load()
            g2pUS = G2P(usLexicon) { text -> 
                try { espeak.textToPhonemesSafe(text, "en-us") } catch (e: Exception) { null }
            }
            
            val gbLexicon = Lexicon(context, british = true)
            gbLexicon.load()
            g2pGB = G2P(gbLexicon) { text ->
                try { espeak.textToPhonemesSafe(text, "en-gb") } catch (e: Exception) { null } 
            }

            // eSpeak fallback function for language-specific G2P modules
            val espeakFallback: (String, String) -> String? = { text, lang ->
                try {
                    val ph = espeak.textToPhonemesSafe(text, lang)
                    if (ph.isNotBlank()) convertEspeakToKokoro(ph) else null
                } catch (e: Exception) { null }
            }

            // English callable for ZH (handles mixed English+Chinese text)
            val enCallable: (String) -> String = { text ->
                g2pUS.phonemize(text, OutputMode.KOKORO)
            }

            // Japanese G2P (Misaki JA port)
            jaG2P = JaG2P(espeakFallback = espeakFallback)
            JaG2P.loadDictionary(context)

            // Chinese G2P (Misaki ZH port)
            zhG2P = ZhG2P(espeakFallback = espeakFallback, enCallable = enCallable)
            zhG2P.load(context)
            ZhNormalization.loadCharConvert(context)

            // Korean G2P (Misaki KO port)
            koG2P = KoG2P(espeakFallback = espeakFallback)
            KoG2P.loadDictionaries(context)
            CmuDict.load(context) // ARPAbet dictionary for English→Hangul loanword conversion

            // Vietnamese G2P (Misaki VI port)
            viG2P = ViG2P(
                enG2P = { text -> g2pUS.phonemize(text, OutputMode.KOKORO) },
                espeakFallback = espeakFallback
            )
            ViCleaner.loadDictionaries(context)

            // Hebrew G2P (Misaki HE port)
            heG2P = HeG2P(espeakFallback = espeakFallback)
            
            isLoaded = true
            Log.i(TAG, "Loaded Misaki G2P engines (EN-US, EN-GB, JA, ZH, KO, VI, HE)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load G2P", e)
        }
    }

    /**
     * Determine the language code for phonemization.
     * Maps language tags from the TTS service to eSpeak/Misaki language codes.
     *
     * Supports: en, en-us, en-gb, fr, de, es, it, pt, ru, nl, pl, sv, cs, fi, el, hu, tr, ar, zh, ja, ko, vi, he, ta
     * Fixes: https://github.com/siva-sub/NekoSpeak/issues/5
     */
    private fun resolveLanguage(language: String): String {
        return when (language.lowercase()) {
            "en", "eng", "en-us", "a" -> "en-us"
            "en-gb", "en-uk", "b" -> "en-gb"
            "en-au" -> "en-au"
            "en-in" -> "en-in"
            "fr", "fra" -> "fr"
            "de", "deu" -> "de"
            "es", "spa" -> "es"
            "it", "ita" -> "it"
            "pt", "por", "pt-br" -> "pt-br"
            "ru", "rus" -> "ru"
            "nl", "nld" -> "nl"
            "pl", "pol" -> "pl"
            "sv", "swe" -> "sv"
            "cs", "ces" -> "cs"
            "fi", "fin" -> "fi"
            "el", "gre" -> "el"
            "hu", "hun" -> "hu"
            "tr", "tur" -> "tr"
            "ar", "ara" -> "ar"
            "zh", "zho", "cmn" -> "zh"
            "ja", "jpn" -> "ja"
            "ko", "kor" -> "ko"
            "vi", "vie" -> "vi"
            "he", "heb" -> "he"
            "ta", "tam" -> "ta"
            else -> "en-us"  // Default fallback
        }
    }

    /**
     * Detect if text contains Arabic characters.
     * Arabic text requires diacritization (tashkeel) before phonemization.
     * Prerequisite for Libtashkeel integration (Issue #8).
     */
    private fun containsArabic(text: String): Boolean {
        return ARABIC_REGEX.containsMatchIn(text)
    }

    fun phonemize(text: String, language: String = "en-us"): String {
        if (!isLoaded) {
             Log.w(TAG, "Phonemizer not loaded, returning empty string")
             return ""
        }
        return try {
            val langCode = resolveLanguage(language)
            
            // Arabic preprocessing hook (Issue #8 prerequisite)
            if (langCode == "ar" || containsArabic(text)) {
                Log.w(TAG, "Arabic text detected but diacritization (tashkeel) is not yet integrated. " +
                    "Arabic TTS quality will be limited. See: https://github.com/siva-sub/NekoSpeak/issues/8")
            }
            
            val phonemes = when (langCode) {
                "en-us", "a" -> g2pUS.phonemize(text, OutputMode.KOKORO)
                "en-gb", "b" -> g2pGB.phonemize(text, OutputMode.KOKORO)
                "ja" -> jaG2P.phonemize(text)
                "zh" -> zhG2P.phonemize(text)
                "ko" -> koG2P.phonemize(text)
                "vi" -> viG2P.phonemize(text)
                "he" -> heG2P.phonemize(text)
                else -> {
                    // For other languages, use eSpeak directly
                    // eSpeak supports 100+ languages and handles phonemization natively.
                    // The language markers are already stripped by EspeakWrapper.cleanPhonemes().
                    // Fixes: https://github.com/siva-sub/NekoSpeak/issues/5
                    try {
                        val espeakPhonemes = espeak.textToPhonemesSafe(text, langCode)
                        if (espeakPhonemes.isBlank()) {
                            Log.w(TAG, "eSpeak returned empty phonemes for language=$langCode, falling back to English")
                            g2pUS.phonemize(text, OutputMode.KOKORO)
                        } else {
                            // Convert eSpeak output to Kokoro-compatible phonemes
                            convertEspeakToKokoro(espeakPhonemes)
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "eSpeak failed for $langCode, falling back to English", e)
                        g2pUS.phonemize(text, OutputMode.KOKORO)
                    }
                }
            }
            
            phonemes.take(MAX_PHONEME_LENGTH)
        } catch (e: Exception) {
            Log.e(TAG, "Phonemization failed", e)
            text.filter { defaultVocab.containsKey(it.toString()) }.take(50)
        }
    }
    
    /**
     * Convert eSpeak phoneme output to Kokoro-compatible phoneme tokens.
     * 
     * eSpeak produces IPA-like output with stress markers (ˈ, ˌ), diphthongs,
     * and other symbols. This method normalizes them for the Kokoro vocabulary.
     * 
     * Key conversions (ported from upstream Misaki's EspeakG2P):
     * - Tie character ^ is removed (eSpeak ties diphthongs like e^ɪ → eɪ)
     * - Diphthong expansions: a^ɪ → AI, o^ʊ → OW, etc.
     * - Language markers already stripped by EspeakWrapper.cleanPhonemes()
     */
    private fun convertEspeakToKokoro(espeakPhonemes: String): String {
        var result = espeakPhonemes
        
        // Remove tie characters (eSpeak uses ^ to tie diphthong components)
        result = result.replace("^", "")
        
        // Remove hyphens (eSpeak sometimes outputs these)
        result = result.replace("-", "")
        
        // Convert common eSpeak diphthong patterns to Kokoro phoneme representation
        val diphthongMap = mapOf(
            "eɪ" to "AI",    // hey
            "aɪ" to "AY",    // high (Kokoro uses Y for aɪ)
            "oʊ" to "OW",    // go
            "aʊ" to "AW",    // how
            "ɔɪ" to "OY",    // soy
            "əʊ" to "OW",    // British go
            "eə" to "EH",    // British air
            "ɪə" to "IY",    // British here
        )
        
        for ((espeak, kokoro) in diphthongMap) {
            result = result.replace(espeak, kokoro)
        }
        
        return result
    }
    
    fun tokenize(phonemes: String): List<Int> {
        val tokens = mutableListOf<Int>()
        var i = 0
        while (i < phonemes.length && i < MAX_PHONEME_LENGTH) {
            var matched = false
            for (length in 3 downTo 1) {
                if (i + length <= phonemes.length) {
                    val substring = phonemes.substring(i, i + length)
                    val tokenId = defaultVocab[substring]
                    if (tokenId != null) {
                        tokens.add(tokenId)
                        i += length
                        matched = true
                        break
                    }
                }
            }
            if (!matched) i++
        }
        return tokens
    }
}