package com.nekospeak.tts.engine

/**
 * OmniVoice voice design and cloning system.
 *
 * Port of vocoloco_tts/app.js voice system — provides two voice modes:
 *
 * 1. Voice Design (instruct-based): Use text instructions like "female, low pitch"
 *    to control voice characteristics. The model generates a voice matching
 *    the description. Deterministic seed from instruct hash for reproducibility.
 *
 * 2. Voice Cloning: Provide 5-10s reference audio + optional transcript.
 *    The encoder converts audio to 8-codebook tokens which condition generation.
 *
 * Voice Design parameters:
 * - Gender: male, female, neutral
 * - Pitch: low, medium, high
 * - These combine into an instruct string like "female, low pitch"
 * - The instruct is hashed to a deterministic seed for consistent voice per design
 *
 * Reference: https://github.com/Magkino/vocoloco_tts/blob/main/app.js
 */
object OmniVoiceVoiceDesign {

    enum class Gender(val label: String, val value: String) {
        FEMALE("Female", "female"),
        MALE("Male", "male"),
        NEUTRAL("Neutral", "neutral")
    }

    enum class Pitch(val label: String, val value: String) {
        LOW("Low", "low pitch"),
        MEDIUM("Medium", ""),
        HIGH("High", "high pitch")
    }

    /**
     * Build an instruct string from voice design parameters.
     * Format: "gender, pitch" (e.g. "female, low pitch")
     * Empty parts are filtered out.
     */
    fun buildInstruct(gender: Gender, pitch: Pitch): String? {
        val parts = listOf(gender.value, pitch.value).filter { it.isNotBlank() }
        return if (parts.isNotEmpty()) parts.joinToString(", ") else null
    }

    /**
     * Hash an instruct string to a deterministic integer seed.
     * This ensures the same voice design parameters always produce the same voice.
     * Port of vocoloco_tts hashInstruct().
     */
    fun hashInstruct(instruct: String?): Int {
        if (instruct == null) return 0
        var hash = 0
        for (ch in instruct) {
            hash = ((hash shl 5) - hash) + ch.code
            hash = hash and hash.toInt()  // Convert to 32-bit integer
        }
        return kotlin.math.abs(hash)
    }

    /**
     * Pre-built voice designs matching common voice presets.
     * These provide convenient named voices that users can select.
     */
    data class VoicePreset(
        val id: String,
        val name: String,
        val gender: Gender,
        val pitch: Pitch,
        val lang: String = "en"
    ) {
        val instruct: String? get() = buildInstruct(gender, pitch)
        val seed: Int get() = hashInstruct(instruct)
    }

    /** Built-in voice presets. */
    val VOICE_PRESETS = listOf(
        // Female voices
        VoicePreset("ov_alex", "Alex (Female, Low)", Gender.FEMALE, Pitch.LOW),
        VoicePreset("ov_bella", "Bella (Female, Medium)", Gender.FEMALE, Pitch.MEDIUM),
        VoicePreset("ov_clara", "Clara (Female, High)", Gender.FEMALE, Pitch.HIGH),
        // Male voices
        VoicePreset("ov_daniel", "Daniel (Male, Low)", Gender.MALE, Pitch.LOW),
        VoicePreset("ov_eric", "Eric (Male, Medium)", Gender.MALE, Pitch.MEDIUM),
        VoicePreset("ov_felix", "Felix (Male, High)", Gender.MALE, Pitch.HIGH),
        // Neutral voices
        VoicePreset("ov_glen", "Glen (Neutral, Low)", Gender.NEUTRAL, Pitch.LOW),
        VoicePreset("ov_hayden", "Hayden (Neutral, Medium)", Gender.NEUTRAL, Pitch.MEDIUM),
        VoicePreset("ov_iris", "Iris (Neutral, High)", Gender.NEUTRAL, Pitch.HIGH),
        // Multilingual presets
        VoicePreset("ov_zh_female", "中文女声", Gender.FEMALE, Pitch.MEDIUM, "zh"),
        VoicePreset("ov_zh_male", "中文男声", Gender.MALE, Pitch.MEDIUM, "zh"),
        VoicePreset("ov_ja_female", "日本語女声", Gender.FEMALE, Pitch.HIGH, "ja"),
        VoicePreset("ov_ja_male", "日本語男声", Gender.MALE, Pitch.LOW, "ja"),
        VoicePreset("ov_ko_female", "한국어 여성", Gender.FEMALE, Pitch.MEDIUM, "ko"),
        VoicePreset("ov_ko_male", "한국어 남성", Gender.MALE, Pitch.MEDIUM, "ko"),
        VoicePreset("ov_fr_female", "Française", Gender.FEMALE, Pitch.MEDIUM, "fr"),
        VoicePreset("ov_de_female", "Deutsch", Gender.FEMALE, Pitch.MEDIUM, "de"),
        VoicePreset("ov_es_female", "Español", Gender.FEMALE, Pitch.MEDIUM, "es"),
        VoicePreset("ov_ar_male", "العربية", Gender.MALE, Pitch.LOW, "ar"),
        VoicePreset("ov_ru_male", "Русский", Gender.MALE, Pitch.LOW, "ru"),
        VoicePreset("ov_hi_female", "हिन्दी", Gender.FEMALE, Pitch.MEDIUM, "hi"),
    )

    /** Get a preset by ID. */
    fun getPreset(id: String): VoicePreset? = VOICE_PRESETS.find { it.id == id }

    /** Get all voice IDs (preset IDs + "clone" for cloned voices). */
    fun getVoiceIds(): List<String> = VOICE_PRESETS.map { it.id }
}