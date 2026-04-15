package com.nekospeak.tts

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * Activity to check if TTS voice data is available.
 * Called by the system and third-party apps (ReadEra, Librera, Voice Aloud Reader, etc.)
 * when checking TTS engine status via ACTION_CHECK_TTS_DATA.
 *
 * Returns all supported locales with ISO3 codes so that apps recognize NekoSpeak
 * as a valid TTS engine for these languages.
 *
 * Key contract:
 * - EXTRA_AVAILABLE_VOICES: list of "lang-COUNTRY" strings (ISO3 format)
 * - EXTRA_UNAVAILABLE_VOICES: empty (we claim support for all listed locales)
 * - Result: CHECK_VOICE_DATA_PASS
 *
 * Apps like ReadEra query this to determine which engines are available for a
 * given language. If the engine doesn't report a matching locale, the app won't
 * offer it as a TTS option.
 *
 * Fixes: https://github.com/siva-sub/NekoSpeak/issues/11
 */
class CheckVoiceData : Activity() {
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val result = Intent()
        
        // Comprehensive list of supported voice locales.
        // Uses ISO3 language codes (eng, kor, spa, etc.) because apps like
        // ReadEra and Livio dictionaries query with ISO3 codes.
        val locales = listOf(
            // English variants — most commonly requested
            Locale.US,                          // eng-USA
            Locale.UK,                          // eng-GBR
            Locale("en", "AU"),                 // eng-AUS
            Locale("en", "IN"),                 // eng-IND
            Locale("en", "SG"),                 // eng-SGP
            Locale("en", "CA"),                 // eng-CAN
            Locale("en", "PH"),                 // eng-PHL
            Locale("en", "NZ"),                 // eng-NZL
            Locale("en", "ZA"),                 // eng-ZAF
            Locale("en", "IE"),                 // eng-IRL
            Locale("en", "NG"),                 // eng-NGA
            // European languages
            Locale("de", "DE"),   // deu-DEU — German
            Locale("fr", "FR"),   // fra-FRA — French
            Locale("es", "ES"),   // spa-ESP — Spanish
            Locale("it", "IT"),   // ita-ITA — Italian
            Locale("pt", "BR"),   // por-BRA — Portuguese (Brazil)
            Locale("ru", "RU"),   // rus-RUS — Russian
            Locale("nl", "NL"),   // nld-NLD — Dutch
            Locale("pl", "PL"),   // pol-POL — Polish
            Locale("sv", "SE"),   // swe-SWE — Swedish
            Locale("cs", "CZ"),   // ces-CZE — Czech
            Locale("fi", "FI"),   // fin-FIN — Finnish
            Locale("el", "GR"),   // ell-GRC — Greek
            Locale("hu", "HU"),   // hun-HUN — Hungarian
            Locale("tr", "TR"),   // tur-TUR — Turkish
            // CJK languages — critical for ReadEra
            Locale("zh", "CN"),   // zho-CHN — Chinese (Mandarin)
            Locale("ja", "JP"),   // jpn-JPN — Japanese
            Locale("ko", "KR"),   // kor-KOR — Korean
            // Other languages
            Locale("vi", "VN"),   // vie-VNM — Vietnamese
            Locale("ar", "SA"),   // ara-SAU — Arabic
            Locale("hi", "IN"),   // hin-IND — Hindi
            Locale("ta", "IN"),   // tam-IND — Tamil
        )
        
        val availableVoices = ArrayList<String>()
        for (locale in locales) {
            try {
                // Format: ISO3_language-ISO3_country (e.g. "eng-USA", "kor-KOR")
                // This format is what ReadEra and other apps expect from CHECK_VOICE_DATA
                val lang = locale.isO3Language
                val country = locale.isO3Country
                if (lang.isNotEmpty() && country.isNotEmpty()) {
                    availableVoices.add("$lang-$country")
                }
            } catch (e: Exception) {
                // Ignore locales with missing ISO3 codes (shouldn't happen with standard locales)
            }
        }
        
        result.putStringArrayListExtra(
            TextToSpeech.Engine.EXTRA_AVAILABLE_VOICES,
            availableVoices
        )
        
        // No unavailable voices — we claim support for all listed locales
        result.putStringArrayListExtra(
            TextToSpeech.Engine.EXTRA_UNAVAILABLE_VOICES,
            arrayListOf()
        )
        
        setResult(TextToSpeech.Engine.CHECK_VOICE_DATA_PASS, result)
        finish()
    }
}