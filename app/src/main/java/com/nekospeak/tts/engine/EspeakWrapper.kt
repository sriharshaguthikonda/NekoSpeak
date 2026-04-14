package com.nekospeak.tts.engine

import android.util.Log

class EspeakWrapper {
    companion object {
        private const val TAG = "EspeakWrapper"

        private val globalLock = Any()

        @Volatile private var isLibraryLoaded: Boolean = false
        @Volatile private var isNativeInitialized: Boolean = false
        @Volatile private var initResult: Int = Int.MIN_VALUE // unknown

        init {
            isLibraryLoaded = try {
                System.loadLibrary("nekospeak")
                true
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to load native lib 'nekospeak'", t)
                false
            }
        }

        fun isReady(): Boolean = isLibraryLoaded && isNativeInitialized
        fun lastInitResult(): Int = initResult

        /**
         * Strip eSpeak language-switch markers from phoneme output.
         *
         * eSpeak emits language change markers like "(en)", "(ru)", "(fr)" when
         * it detects multilingual text or switches phonemization language mid-sentence.
         * These markers are metadata, not phonemes — if passed to a TTS model, they
         * get spoken aloud as literal words ("en", "ru", etc.) instead of being
         * silently ignored.
         *
         * This regex removes:
         *   - Parenthesized language codes: (en), (en-us), (ru), (fr), etc.
         *   - eSpeak phoneme-mode flags: ˈen, ˈru (stress + lang markers)
         *   - eSpeak lang:xx attributes
         *
         * Ported from upstream Misaki's EspeakG2P which uses
         * `language_switch='remove-flags'` in phonemizer. Since our eSpeak JNI
         * doesn't support that parameter, we strip the markers post-hoc.
         *
         * Reference: https://github.com/hexgrad/misaki (espeak.py)
         * Fixes: https://github.com/siva-sub/NekoSpeak/issues/6
         */
        private val LANGUAGE_MARKER_REGEX = Regex(
            """\([a-z]{2}(?:-[a-z]{2,6})?\)|ˈ[a-z]{2}(?=[^\w]|$)|lang:[a-z]{2}(?:-[a-z]{2,6})?"""
        )

        /**
         * Clean eSpeak phoneme output by removing language-switch markers
         * and normalizing whitespace.
         */
        fun cleanPhonemes(rawPhonemes: String): String {
            return rawPhonemes
                .replace(LANGUAGE_MARKER_REGEX, "")
                .replace(Regex("\\s+"), " ")
                .trim()
        }
    }

    private external fun initialize(dataPath: String): Int
    private external fun textToPhonemes(text: String, language: String): String

    /**
     * Initialise eSpeak native layer. Safe to call multiple times.
     * Returns the original init result (eg sample rate) once initialised.
     */
    fun initializeSafe(dataPath: String): Int = synchronized(globalLock) {
        if (!isLibraryLoaded) {
            initResult = -1
            return initResult
        }

        if (isNativeInitialized) {
            return initResult
        }

        val res = try {
            initialize(dataPath)
        } catch (t: Throwable) {
            Log.e(TAG, "Native initialize() threw", t)
            -1
        }

        initResult = res
        if (res >= 0) {
            isNativeInitialized = true
            Log.i(TAG, "eSpeak initialized successfully, result=$res")
        } else {
            // keep false, allow retry
            isNativeInitialized = false
            Log.e(TAG, "eSpeak init failed, result=$res")
        }
        return initResult
    }

    fun textToPhonemesSafe(text: String, language: String): String = synchronized(globalLock) {
        if (!isLibraryLoaded) {
            Log.w(TAG, "textToPhonemesSafe: native library not loaded")
            return ""
        }
        if (!isNativeInitialized) {
            Log.w(TAG, "textToPhonemesSafe: eSpeak not initialized")
            return ""
        }
        return try {
            val raw = textToPhonemes(text, language)
            // Always strip eSpeak language markers to prevent them from being spoken aloud.
            // Fixes: https://github.com/siva-sub/NekoSpeak/issues/6
            cleanPhonemes(raw)
        } catch (t: Throwable) {
            Log.e(TAG, "Native textToPhonemes() threw", t)
            ""
        }
    }
}