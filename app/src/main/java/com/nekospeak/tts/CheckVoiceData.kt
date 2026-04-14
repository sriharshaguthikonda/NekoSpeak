package com.nekospeak.tts

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * Activity to check if TTS voice data is available.
 * Called by the system when checking TTS engine status.
 *
 * Returns all supported locales so that third-party apps (ReadEra, Livio dictionaries, etc.)
 * can recognize NekoSpeak as a valid TTS engine for these languages.
 *
 * Fixes: https://github.com/siva-sub/NekoSpeak/issues/11 (system-wide TTS recognition)
 */
class CheckVoiceData : Activity() {
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Report available voices
        val result = Intent()
        
        // Expanded list of supported voice locales
        // Includes English variants + languages supported by Piper/eSpeak voices
        val locales = listOf(
            // English variants
            Locale.US,
            Locale.UK,
            Locale("en", "AU"),
            Locale("en", "IN"),
            Locale("en", "SG"),
            Locale("en", "CA"),
            Locale("en", "PH"),
            Locale("en", "NZ"),
            Locale("en", "ZA"),
            Locale("en", "IE"),
            Locale("en", "NG"),
            // Languages supported by Piper/eSpeak voices
            Locale("de", "DE"),   // German
            Locale("fr", "FR"),   // French
            Locale("es", "ES"),   // Spanish
            Locale("it", "IT"),   // Italian
            Locale("pt", "BR"),   // Portuguese (Brazil)
            Locale("ru", "RU"),   // Russian
            Locale("nl", "NL"),   // Dutch
            Locale("pl", "PL"),   // Polish
            Locale("sv", "SE"),   // Swedish
            Locale("cs", "CZ"),   // Czech
            Locale("fi", "FI"),   // Finnish
            Locale("el", "GR"),   // Greek
            Locale("hu", "HU"),   // Hungarian
            Locale("tr", "TR"),   // Turkish
            Locale("ar", "SA"),   // Arabic
            Locale("zh", "CN"),   // Chinese (Mandarin)
            Locale("ja", "JP"),   // Japanese
            Locale("ko", "KR"),   // Korean
            Locale("vi", "VN"),   // Vietnamese
            Locale("ta", "IN"),   // Tamil
        )
        
        val availableVoices = ArrayList<String>()
        for (locale in locales) {
            try {
                // Format: eng-USA, eng-SGP, etc. (ISO3 language-ISO3 country)
                val lang = locale.isO3Language
                val country = locale.isO3Country
                if (lang.isNotEmpty() && country.isNotEmpty()) {
                    availableVoices.add("$lang-$country")
                }
            } catch (e: Exception) {
                // Ignore locales with missing ISO3 codes
            }
        }
        
        result.putStringArrayListExtra(
            TextToSpeech.Engine.EXTRA_AVAILABLE_VOICES,
            availableVoices
        )
        
        // No unavailable voices
        result.putStringArrayListExtra(
            TextToSpeech.Engine.EXTRA_UNAVAILABLE_VOICES,
            arrayListOf()
        )
        
        setResult(TextToSpeech.Engine.CHECK_VOICE_DATA_PASS, result)
        finish()
    }
}