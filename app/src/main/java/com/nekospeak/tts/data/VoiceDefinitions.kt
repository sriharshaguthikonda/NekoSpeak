package com.nekospeak.tts.data

data class VoiceDefinition(
    val id: String,
    val name: String,
    val gender: String, // "Male" or "Female"
    val region: String = "US",
    val description: String? = null,
    val sampleUrl: String? = null, // Link to HuggingFace sample
    val modelType: String // "pocket_v1", "kokoro_v1.0", "kitten_nano"
)

object VoiceDefinitions {
    
    // Pocket-TTS Standard Voices (Kyutai) - All speak English only
    val POCKET_VOICES = listOf(
        VoiceDefinition("alba", "Alba", "Male", "English", "Casual speaking style", "https://huggingface.co/kyutai/tts-voices/blob/main/alba-mackenna/casual.wav", "pocket_v1"),
        VoiceDefinition("marius", "Marius", "Male", "English", "Selfie (Voice Donation)", "https://huggingface.co/kyutai/tts-voices/blob/main/voice-donations/Selfie.wav", "pocket_v1"),
        VoiceDefinition("javert", "Javert", "Male", "English", "Butter (Voice Donation)", "https://huggingface.co/kyutai/tts-voices/blob/main/voice-donations/Butter.wav", "pocket_v1"),
        VoiceDefinition("jean", "Jean", "Male", "English", "EARS p010 speaker", "https://huggingface.co/kyutai/tts-voices/blob/main/ears/p010/freeform_speech_01.wav", "pocket_v1"),
        VoiceDefinition("fantine", "Fantine", "Female", "English", "VCTK speaker p244", "https://huggingface.co/kyutai/tts-voices/blob/main/vctk/p244_023.wav", "pocket_v1"),
        VoiceDefinition("cosette", "Cosette", "Female", "English", "Expresso - Confused", "https://huggingface.co/kyutai/tts-voices/blob/main/expresso/ex04-ex02_confused_001_channel1_499s.wav", "pocket_v1"),
        VoiceDefinition("eponine", "Eponine", "Female", "English", "VCTK speaker p262", "https://huggingface.co/kyutai/tts-voices/blob/main/vctk/p262_023.wav", "pocket_v1"),
        VoiceDefinition("azelma", "Azelma", "Female", "English", "VCTK speaker p303", "https://huggingface.co/kyutai/tts-voices/blob/main/vctk/p303_023.wav", "pocket_v1")
    )

    // Kokoro Standard Voices — expanded to include all voices from the ONNX model
    // The onnx-community/Kokoro-82M-ONNX voice pack includes these voice embeddings.
    // Fixes: https://github.com/siva-sub/NekoSpeak/issues/5
    val KOKORO_VOICES = listOf(
        // American Female voices
        VoiceDefinition("af_heart", "Heart ♡", "Female", "US", "Warm American female voice", null, "kokoro_v1.0"),
        VoiceDefinition("af_bella", "Bella", "Female", "US", "Clear American female voice", null, "kokoro_v1.0"),
        VoiceDefinition("af_nicole", "Nicole", "Female", "US", "Professional American female voice", null, "kokoro_v1.0"),
        VoiceDefinition("af_sarah", "Sarah", "Female", "US", "Soft American female voice", null, "kokoro_v1.0"),
        VoiceDefinition("af_sky", "Sky", "Female", "US", "Bright American female voice", null, "kokoro_v1.0"),
        
        // American Male voices
        VoiceDefinition("am_adam", "Adam", "Male", "US", "Deep American male voice", null, "kokoro_v1.0"),
        VoiceDefinition("am_michael", "Michael", "Male", "US", "Natural American male voice", null, "kokoro_v1.0"),
        
        // British Female voices
        VoiceDefinition("bf_emma", "Emma", "Female", "UK", "Elegant British female voice", null, "kokoro_v1.0"),
        VoiceDefinition("bf_isabella", "Isabella", "Female", "UK", "Refined British female voice", null, "kokoro_v1.0"),
        VoiceDefinition("bf_alice", "Alice", "Female", "UK", "Warm British female voice", null, "kokoro_v1.0"),
        VoiceDefinition("bf_lily", "Lily", "Female", "UK", "Young British female voice", null, "kokoro_v1.0"),
        
        // British Male voices
        VoiceDefinition("bm_george", "George", "Male", "UK", "Authoritative British male voice", null, "kokoro_v1.0"),
        VoiceDefinition("bm_lewis", "Lewis", "Male", "UK", "Friendly British male voice", null, "kokoro_v1.0"),
        
        // Additional voices from Kokoro v1.0 model
        VoiceDefinition("bf_dora", "Dora", "Female", "UK", "Expressive British female voice", null, "kokoro_v1.0"),
        VoiceDefinition("am_santa", "Santa", "Male", "US", "Deep festive American male voice", null, "kokoro_v1.0"),
        
        // French voices (Kokoro v1.0 multilingual)
        VoiceDefinition("ff_siwis", "Siwis", "Female", "FR", "French female voice (multilingual)", null, "kokoro_v1.0"),
        
        // Hindi voice (Kokoro v1.0 multilingual)
        VoiceDefinition("hf_alpha", "Alpha ♀", "Female", "IN", "Hindi female voice (multilingual)", null, "kokoro_v1.0"),
        VoiceDefinition("hm_beta", "Beta ♂", "Male", "IN", "Hindi male voice (multilingual)", null, "kokoro_v1.0"),
        
        // Japanese voices (Kokoro v1.0 multilingual)
        VoiceDefinition("jf_gongitsune", "Gongitsune", "Female", "JP", "Japanese female voice (multilingual)", null, "kokoro_v1.0"),
        VoiceDefinition("jf_nezumi", "Nezumi", "Female", "JP", "Japanese female voice (multilingual)", null, "kokoro_v1.0"),
        VoiceDefinition("jm_kumo", "Kumo", "Male", "JP", "Japanese male voice (multilingual)", null, "kokoro_v1.0"),
        
        // Mandarin Chinese voice (Kokoro v1.0 multilingual)
        VoiceDefinition("zf_xiaobei", "Xiaobei", "Female", "CN", "Mandarin Chinese female voice (multilingual)", null, "kokoro_v1.0"),
        
        // Korean voice (Kokoro v1.0 multilingual)
        VoiceDefinition("kf_xiaoyi", "Xiaoyi", "Female", "KR", "Korean female voice (multilingual)", null, "kokoro_v1.0"),
    )
    
    // Celebrity Voices (Downloaded on-demand from HuggingFace)
    // WARNING: Celebrity voice cloning raises ethical/legal concerns. Use responsibly.
    val CELEBRITY_VOICES = listOf(
        // Politicians
        VoiceDefinition("celebrity_trump", "Donald Trump", "Male", "US", "⭐ Former US President", null, "pocket_v1"),
        VoiceDefinition("celebrity_biden", "Joe Biden", "Male", "US", "⭐ US President", null, "pocket_v1"),
        VoiceDefinition("celebrity_obama", "Barack Obama", "Male", "US", "⭐ Former US President", null, "pocket_v1"),
        VoiceDefinition("celebrity_hillary", "Hillary Clinton", "Female", "US", "⭐ Former Secretary of State", null, "pocket_v1"),
        VoiceDefinition("celebrity_kamala", "Kamala Harris", "Female", "US", "⭐ US Vice President", null, "pocket_v1"),
        // Tech Leaders
        VoiceDefinition("celebrity_musk", "Elon Musk", "Male", "US", "⭐ Tech Entrepreneur", null, "pocket_v1"),
        VoiceDefinition("celebrity_bill_gates", "Bill Gates", "Male", "US", "⭐ Microsoft co-founder", null, "pocket_v1"),
        VoiceDefinition("celebrity_zuckerberg", "Mark Zuckerberg", "Male", "US", "⭐ Meta CEO", null, "pocket_v1"),
        VoiceDefinition("celebrity_jensen", "Jensen Huang", "Male", "US", "⭐ NVIDIA CEO", null, "pocket_v1"),
        // Media & Authors
        VoiceDefinition("celebrity_oprah", "Oprah Winfrey", "Female", "US", "⭐ Media Personality", null, "pocket_v1"),
        VoiceDefinition("celebrity_jk_rowling", "J.K. Rowling", "Female", "UK", "⭐ Harry Potter Author", null, "pocket_v1"),
        // Activists & Others
        VoiceDefinition("celebrity_greta", "Greta Thunberg", "Female", "Swedish", "⭐ Climate Activist", null, "pocket_v1"),
        VoiceDefinition("celebrity_tate", "Andrew Tate", "Male", "UK", "⭐ Internet Personality", null, "pocket_v1")
    )
    
    // OmniVoice preset voices — voice design system
    // These use instruct-based voice control (gender + pitch → text description)
    // The model generates voice characteristics from the description
    val OMNIVOICE_VOICES = listOf(
        // English voices
        VoiceDefinition("ov_alex", "Alex (F, Low)", "Female", "US", "Low-pitched female voice", null, "omnivoice"),
        VoiceDefinition("ov_bella", "Bella (F, Mid)", "Female", "US", "Medium-pitched female voice", null, "omnivoice"),
        VoiceDefinition("ov_clara", "Clara (F, High)", "Female", "US", "High-pitched female voice", null, "omnivoice"),
        VoiceDefinition("ov_daniel", "Daniel (M, Low)", "Male", "US", "Low-pitched male voice", null, "omnivoice"),
        VoiceDefinition("ov_eric", "Eric (M, Mid)", "Male", "US", "Medium-pitched male voice", null, "omnivoice"),
        VoiceDefinition("ov_felix", "Felix (M, High)", "Male", "US", "High-pitched male voice", null, "omnivoice"),
        VoiceDefinition("ov_glen", "Glen (N, Low)", "Male", "US", "Low-pitched neutral voice", null, "omnivoice"),
        VoiceDefinition("ov_hayden", "Hayden (N, Mid)", "Male", "US", "Medium-pitched neutral voice", null, "omnivoice"),
        VoiceDefinition("ov_iris", "Iris (N, High)", "Female", "US", "High-pitched neutral voice", null, "omnivoice"),
        // Multilingual voices
        VoiceDefinition("ov_zh_female", "中文女声", "Female", "CN", "Chinese female voice", null, "omnivoice"),
        VoiceDefinition("ov_zh_male", "中文男声", "Male", "CN", "Chinese male voice", null, "omnivoice"),
        VoiceDefinition("ov_ja_female", "日本語女声", "Female", "JP", "Japanese female voice", null, "omnivoice"),
        VoiceDefinition("ov_ja_male", "日本語男声", "Male", "JP", "Japanese male voice", null, "omnivoice"),
        VoiceDefinition("ov_ko_female", "한국어 여성", "Female", "KR", "Korean female voice", null, "omnivoice"),
        VoiceDefinition("ov_ko_male", "한국어 남성", "Male", "KR", "Korean male voice", null, "omnivoice"),
        VoiceDefinition("ov_fr_female", "Française", "Female", "FR", "French female voice", null, "omnivoice"),
        VoiceDefinition("ov_de_female", "Deutsch", "Female", "DE", "German female voice", null, "omnivoice"),
        VoiceDefinition("ov_es_female", "Español", "Female", "ES", "Spanish female voice", null, "omnivoice"),
        VoiceDefinition("ov_ar_male", "العربية", "Male", "SA", "Arabic male voice", null, "omnivoice"),
        VoiceDefinition("ov_ru_male", "Русский", "Male", "RU", "Russian male voice", null, "omnivoice"),
        VoiceDefinition("ov_hi_female", "हिन्दी", "Female", "IN", "Hindi female voice", null, "omnivoice"),
    )
    
    // Helper to get all available voices for a model
    fun getVoicesForModel(modelType: String): List<VoiceDefinition> {
        return when (modelType) {
            "pocket_v1" -> POCKET_VOICES + CELEBRITY_VOICES
            "kokoro_v1.0" -> KOKORO_VOICES
            "kitten_nano" -> listOf(VoiceDefinition("kitten_v1", "Kitten", "Female", "US", "Standard Kitten Voice", null, "kitten_nano"))
            "omnivoice" -> OMNIVOICE_VOICES
            else -> emptyList()
        }
    }
    
    // Check if a voice ID requires download (celebrity voices)
    fun requiresDownload(voiceId: String): Boolean {
        return CELEBRITY_VOICES.any { it.id == voiceId }
    }
}