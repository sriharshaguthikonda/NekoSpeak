package com.nekospeak.tts.ui.viewmodel

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nekospeak.tts.engine.KokoroEngine
import com.nekospeak.tts.data.PrefsManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.nekospeak.tts.data.VoiceDefinitions
import com.nekospeak.tts.engine.pocket.PocketVoiceState
import com.nekospeak.tts.support.SupportLogStore
import java.io.File

data class Voice(
    val id: String,
    val name: String,
    val language: String,
    val gender: String, // "Male", "Female", "Neutral", or "Cloned"
    val region: String, // "US", "UK", "CN", etc.
    val downloadState: com.nekospeak.tts.data.DownloadState = com.nekospeak.tts.data.DownloadState.Downloaded,
    val downloadProgress: Float = 0f,
    val metadata: com.nekospeak.tts.data.VoiceInfo? = null,
    val isCloned: Boolean = false, // True for user-cloned voices that can be deleted
    val modelType: String = ""      // Engine model ID: "kokoro_v1.0", "pocket_v1", "piper_...", "omnivoice", "kitten_nano"
)

enum class ViewMode {
    LIST, GRID
}

enum class VoiceSortOption(val displayName: String) {
    NAME("Name"),
    LANGUAGE("Language")
}

class VoicesViewModel(application: Application) : AndroidViewModel(application) {
    
    data class UiState(
        val voices: List<Voice> = emptyList(),
        val filteredVoices: List<Voice> = emptyList(),
        val isLoading: Boolean = true,
        val searchQuery: String = "",
        val showFilters: Boolean = false,
        val viewMode: ViewMode = ViewMode.LIST,
        val selectedVoiceId: String? = "af_heart", // Default
        
        // Filters
        val selectedLanguage: String? = null,
        val selectedGender: String? = null,
        val selectedRegion: String? = null,
        val selectedQuality: String? = null, // For Piper: x_low, low, medium, high
        val sortBy: VoiceSortOption = VoiceSortOption.NAME,
        
        val availableLanguages: List<String> = emptyList(),
        val availableRegions: List<String> = emptyList(),
        val availableQualities: List<String> = emptyList(),
        
        // Processing status for voice encoding feedback
        val processingStatus: String? = null, // e.g., "Encoding celebrity voice: Oprah Winfrey..."
        val cloneErrorMessage: String? = null
    )
    
    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()
    
    private val context: Context get() = getApplication<Application>().applicationContext
    private val voiceRepo by lazy { com.nekospeak.tts.data.VoiceRepository(context) }
    private val voiceDownloader by lazy { com.nekospeak.tts.data.VoiceDownloader(context) }
    
    private val kokoroVoices = listOf(
        Voice("af_heart", "Heart", "en-us", "Female", "US", modelType = "kokoro_v1.0"),
        Voice("af_alloy", "Alloy", "en-us", "Female", "US", modelType = "kokoro_v1.0"),
        Voice("af_aoede", "Aoede", "en-us", "Female", "US", modelType = "kokoro_v1.0"),
        Voice("af_bella", "Bella", "en-us", "Female", "US", modelType = "kokoro_v1.0"),
        Voice("af_jessica", "Jessica", "en-us", "Female", "US", modelType = "kokoro_v1.0"),
        Voice("af_kore", "Kore", "en-us", "Female", "US", modelType = "kokoro_v1.0"),
        Voice("af_nicole", "Nicole", "en-us", "Female", "US", modelType = "kokoro_v1.0"),
        Voice("af_nova", "Nova", "en-us", "Female", "US", modelType = "kokoro_v1.0"),
        Voice("af_river", "River", "en-us", "Female", "US", modelType = "kokoro_v1.0"),
        Voice("af_sarah", "Sarah", "en-us", "Female", "US", modelType = "kokoro_v1.0"),
        Voice("af_sky", "Sky", "en-us", "Female", "US", modelType = "kokoro_v1.0"),
        
        Voice("am_adam", "Adam", "en-us", "Male", "US", modelType = "kokoro_v1.0"),
        Voice("am_echo", "Echo", "en-us", "Male", "US", modelType = "kokoro_v1.0"),
        Voice("am_eric", "Eric", "en-us", "Male", "US", modelType = "kokoro_v1.0"),
        Voice("am_fenrir", "Fenrir", "en-us", "Male", "US", modelType = "kokoro_v1.0"),
        Voice("am_liam", "Liam", "en-us", "Male", "US", modelType = "kokoro_v1.0"),
        Voice("am_michael", "Michael", "en-us", "Male", "US", modelType = "kokoro_v1.0"),
        Voice("am_onyx", "Onyx", "en-us", "Male", "US", modelType = "kokoro_v1.0"),
        Voice("am_puck", "Puck", "en-us", "Male", "US", modelType = "kokoro_v1.0"),
        Voice("am_santa", "Santa", "en-us", "Male", "US", modelType = "kokoro_v1.0"),

        Voice("bf_alice", "Alice", "en-gb", "Female", "UK", modelType = "kokoro_v1.0"),
        Voice("bf_emma", "Emma", "en-gb", "Female", "UK", modelType = "kokoro_v1.0"),
        Voice("bf_isabella", "Isabella", "en-gb", "Female", "UK", modelType = "kokoro_v1.0"),
        Voice("bf_lily", "Lily", "en-gb", "Female", "UK", modelType = "kokoro_v1.0"),

        Voice("bm_daniel", "Daniel", "en-gb", "Male", "UK", modelType = "kokoro_v1.0"),
        Voice("bm_fable", "Fable", "en-gb", "Male", "UK", modelType = "kokoro_v1.0"),
        Voice("bm_george", "George", "en-gb", "Male", "UK", modelType = "kokoro_v1.0"),
        Voice("bm_lewis", "Lewis", "en-gb", "Male", "UK", modelType = "kokoro_v1.0")
    )
    
    private val kittenVoices = listOf(
        Voice("expr-voice-2-f", "Kitten F2", "en-us", "Female", "US", modelType = "kitten_nano"),
        Voice("expr-voice-2-m", "Kitten M2", "en-us", "Male", "US", modelType = "kitten_nano"),
        Voice("expr-voice-3-f", "Kitten F3", "en-us", "Female", "US", modelType = "kitten_nano"),
        Voice("expr-voice-3-m", "Kitten M3", "en-us", "Male", "US", modelType = "kitten_nano"),
        Voice("expr-voice-4-f", "Kitten F4", "en-us", "Female", "US", modelType = "kitten_nano"),
        Voice("expr-voice-4-m", "Kitten M4", "en-us", "Male", "US", modelType = "kitten_nano"),
        Voice("expr-voice-5-f", "Kitten F5", "en-us", "Female", "US", modelType = "kitten_nano"),
        Voice("expr-voice-5-m", "Kitten M5", "en-us", "Male", "US", modelType = "kitten_nano"),
    )

    // Dynamic source
    private var allVoices: List<Voice> = kokoroVoices
    private var lastLoadedModel: String? = null
    
    init {
        loadVoices()
        startPollLoop()
        observePrefsChanges()
        observeEncodingStatus()
    }
    
    private fun observePrefsChanges() {
        viewModelScope.launch {
            while (true) {
                kotlinx.coroutines.delay(500)
                val prefs = PrefsManager(context)
                val currentModel = prefs.currentModel
                if (lastLoadedModel != null && lastLoadedModel != currentModel) {
                    // Model changed externally (e.g., from Settings), reload voices
                    loadVoices()
                }
            }
        }
    }
    
    // Collecting status from PocketVoiceRepository (singleton)
    private fun observeEncodingStatus() {
        viewModelScope.launch {
            com.nekospeak.tts.data.PocketVoiceRepository.encodingStatus.collect { status ->
                _uiState.update { it.copy(processingStatus = status) }
            }
        }
    }
    
    private fun startPollLoop() {
        viewModelScope.launch {
            while(true) {
                kotlinx.coroutines.delay(1000)
                // Refresh status only if Piper mode
                val prefs = PrefsManager(context)
                if (prefs.currentModel.startsWith("piper")) {
                    refreshPiperStatuses()
                }
            }
        }
    }
    
    private fun refreshPiperStatuses() {
        // Check only downloading or not-downloaded items? Or all?
        // Checking 500+ items is slow.
        // But the user wants to see "Download started", "Level", "Failed".
        // VoiceDownloader only tracks Active/Recent downloads via prefs.
        // Repo checks file existence.
        
        val updatedVoices = allVoices.map { voice ->
            if (voice.id.startsWith("piper") || voice.metadata != null) {
                // It's a piper voice
                val status = voiceDownloader.getDownloadStatus(voice.id)
                if (status.state != com.nekospeak.tts.data.DownloadState.NotDownloaded) {
                    // Active or stored download info
                    voice.copy(downloadState = status.state, downloadProgress = status.progress)
                } else {
                    // Fallback to Repo check (Disk existence)
                    val diskState = voiceRepo.getDownloadState(voice.id)
                    voice.copy(downloadState = diskState, downloadProgress = if (diskState == com.nekospeak.tts.data.DownloadState.Downloaded) 1f else 0f)
                }
            } else {
                voice
            }
        }
        
        if (updatedVoices != allVoices) {
            allVoices = updatedVoices
            applyFilters()
        }
    }
    
    fun downloadVoice(voice: Voice) {
        // Handle celebrity voices
        if (VoiceDefinitions.requiresDownload(voice.id)) {
            downloadCelebrityVoice(voice.id)
            return
        }
        
        // Handle Piper voices
        val meta = voice.metadata ?: return
        viewModelScope.launch {
            voiceDownloader.downloadVoice(voice.id, meta.onnxUrl, meta.jsonUrl)
            refreshPiperStatuses() // Optimistic update
        }
    }
    
    fun loadVoices() {
        val prefs = PrefsManager(context)
        val currentModel = prefs.currentModel
        
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            allVoices = when {
                currentModel == "kitten_nano" -> kittenVoices
                currentModel.startsWith("piper") -> {
                    // Load ALL piper voices from repo
                    voiceRepo.availableVoices.map { info ->
                        // Parse language name to separate language from country
                        // Format: "English (United States)" -> language="English", region="United States"
                        val languageName = info.languageName
                        val pureLang = if (languageName.contains("(")) {
                            languageName.substringBefore("(").trim()
                        } else {
                            languageName
                        }
                        
                        val countryPart = if (languageName.contains("(")) {
                            languageName.substringAfter("(").substringBefore(")").trim()
                        } else {
                            info.region // Fallback to region code
                        }
                        
                        Voice(
                            id = info.id, // e.g. en_US-amy-low
                            name = "${info.name} (${info.quality})", // e.g. Amy (low)
                            language = pureLang, // e.g. "English"
                            gender = "Unknown", // Metadata doesn't strictly have gender.
                            region = countryPart, // e.g. "United States"
                            metadata = info,
                            downloadState = com.nekospeak.tts.data.DownloadState.NotDownloaded, // Initial, will refresh
                            modelType = "piper_${info.id}"
                        )
                    }
                }
                currentModel == "pocket_v1" -> {
                    val standardVoices = VoiceDefinitions.POCKET_VOICES.map { def ->
                        Voice(
                            id = def.id,
                            name = def.name,
                            language = if (def.region.contains("French")) "fr-fr" else "en-us",
                            gender = def.gender,
                            region = def.region,
                            downloadState = com.nekospeak.tts.data.DownloadState.Downloaded,
                            modelType = "pocket_v1"
                        )
                    }
                    
                    // Celebrity voices from HuggingFace (download on-demand)
                    val celebrityVoices = VoiceDefinitions.CELEBRITY_VOICES.map { def ->
                        val isDownloaded = com.nekospeak.tts.data.PocketVoiceRepository.isDownloaded(context, def.id)
                        Voice(
                            id = def.id,
                            name = def.name,
                            language = "en-us",
                            gender = def.gender,
                            region = def.region,
                            downloadState = if (isDownloaded) 
                                com.nekospeak.tts.data.DownloadState.Downloaded 
                            else 
                                com.nekospeak.tts.data.DownloadState.NotDownloaded,
                            modelType = "pocket_v1"
                        )
                    }
                    
                    // Cloned voices from local storage
                    val clonedVoices = mutableListOf<Voice>()
                    val clonedDir = File(context.filesDir, "pocket/cloned_voices")
                    if (clonedDir.exists()) {
                        clonedDir.listFiles()?.filter { it.extension == "bin" }?.forEach { file ->
                            try {
                                val state = PocketVoiceState.fromBytes(file.readBytes())
                                clonedVoices.add(
                                    Voice(
                                        id = state.id,
                                        name = "${state.displayName} (Cloned)",
                                        language = "en-us",
                                        gender = "Cloned",
                                        region = "Custom",
                                        downloadState = com.nekospeak.tts.data.DownloadState.Downloaded,
                                        isCloned = true,
                                        modelType = "pocket_v1"
                                    )
                                )
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }
                    }
                    
                    standardVoices + celebrityVoices + clonedVoices
                }
                currentModel == "omnivoice" -> {
                    // OmniVoice preset voices (voice design)
                    val presetVoices = VoiceDefinitions.OMNIVOICE_VOICES.map { def ->
                        Voice(
                            id = def.id,
                            name = def.name,
                            language = when (def.region) {
                                "CN" -> "zh-cn"
                                "JP" -> "ja-jp"
                                "KR" -> "ko-kr"
                                "FR" -> "fr-fr"
                                "DE" -> "de-de"
                                "ES" -> "es-es"
                                "SA" -> "ar-sa"
                                "RU" -> "ru-ru"
                                "IN" -> "hi-in"
                                else -> "en-us"
                            },
                            gender = def.gender,
                            region = def.region,
                            downloadState = com.nekospeak.tts.data.DownloadState.Downloaded,
                            modelType = "omnivoice"
                        )
                    }

                    // OmniVoice cloned voices from local storage
                    val clonedVoices = mutableListOf<Voice>()
                    val clonedDir = File(context.filesDir, "omnivoice/cloned_voices")
                    if (clonedDir.exists()) {
                        clonedDir.listFiles()?.filter { it.extension == "bin" }?.forEach { file ->
                            try {
                                val name = file.nameWithoutExtension
                                clonedVoices.add(
                                    Voice(
                                        id = name,
                                        name = "$name (Cloned)",
                                        language = "multi",
                                        gender = "Cloned",
                                        region = "Custom",
                                        downloadState = com.nekospeak.tts.data.DownloadState.Downloaded,
                                        isCloned = true,
                                        modelType = "omnivoice"
                                    )
                                )
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }
                    }

                    presetVoices + clonedVoices
                }
                else -> kokoroVoices
            }
            
            // Initial status refresh
            if (currentModel.startsWith("piper")) {
                refreshPiperStatuses()
            }
            
            // Auto-fix filters if they are invalid for the current model
            val currentRegion = _uiState.value.selectedRegion
            if (currentRegion == "UK" && allVoices.none { it.region == "UK" }) {
                 _uiState.update { it.copy(selectedRegion = null) }
            }
            
            // Validate selected voice
            val currentVoiceId = prefs.currentVoice
            val isValid = allVoices.any { it.id == currentVoiceId }
            
            val voiceToSelect = if (isValid) {
                currentVoiceId
            } else {
                // Determine sensible default
                val defaultId = when {
                    currentModel == "kitten_nano" -> "expr-voice-2-f" 
                    currentModel.startsWith("piper") -> {
                         // Default to amy-low if available, else first bundled
                         "en_US-amy-low"
                    }
                    else -> "af_heart"
                }
                // Update persistent storage
                prefs.currentVoice = defaultId
                defaultId
            }
            
            _uiState.update { 
                it.copy(
                    isLoading = false, 
                    voices = emptyList(), // Use filtered list primarily
                    // filteredVoices will be updated by applyFilters
                    availableLanguages = allVoices.map { v -> v.language }.distinct().sorted(),
                    availableRegions = allVoices.map { v -> v.region }.distinct().sorted(),
                    // Extract quality from Piper voice names (e.g., "Amy (low)" -> "low")
                    availableQualities = allVoices
                        .mapNotNull { v -> 
                            val match = Regex("\\(([^)]+)\\)").find(v.name)
                            match?.groupValues?.getOrNull(1)
                        }
                        .distinct()
                        .sortedBy { 
                            when(it) { "x_low" -> 0; "low" -> 1; "medium" -> 2; "high" -> 3; else -> 4 }
                        },
                    selectedVoiceId = voiceToSelect
                ) 
            }
            applyFilters()
            lastLoadedModel = currentModel
        }
    }
    
    fun updateSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
        applyFilters()
    }
    
    fun toggleFilters() {
        _uiState.update { it.copy(showFilters = !it.showFilters) }
    }
    
    fun setViewMode(mode: ViewMode) {
        _uiState.update { it.copy(viewMode = mode) }
    }
    
    fun selectVoice(voiceId: String) {
        val voice = allVoices.find { it.id == voiceId }
        
        // Check if this is a celebrity voice that needs download
        if (VoiceDefinitions.requiresDownload(voiceId)) {
            if (!com.nekospeak.tts.data.PocketVoiceRepository.isDownloaded(context, voiceId)) {
                // Trigger download for celebrity voice
                downloadCelebrityVoice(voiceId)
                return
            }
        }
        
        // Only select if downloaded
        if (voice != null && voice.downloadState == com.nekospeak.tts.data.DownloadState.Downloaded) {
            _uiState.update { it.copy(selectedVoiceId = voiceId) }
            val prefs = PrefsManager(context)
            prefs.currentVoice = voiceId
            
            // Use voice's modelType for routing (reliable, not prefix-based)
            val voiceObj = allVoices.find { it.id == voiceId }
            val newModel = voiceObj?.modelType?.ifBlank { null } ?: when {
                // Legacy fallback for voices without modelType
                voiceId.startsWith("expr-voice-") -> "kitten_nano"
                voiceId.startsWith("ov_") -> "omnivoice"
                else -> "piper_$voiceId"
            }
            android.util.Log.i("VoicesViewModel", "selectVoice($voiceId) -> model=$newModel (modelType=${voiceObj?.modelType})")
            prefs.currentModel = newModel
        }
    }
    
    /**
     * Download a celebrity voice from HuggingFace.
     */
    fun downloadCelebrityVoice(voiceId: String) {
        viewModelScope.launch {
            _uiState.update { 
                val updated = allVoices.map { v ->
                    if (v.id == voiceId) v.copy(downloadState = com.nekospeak.tts.data.DownloadState.Downloading)
                    else v
                }
                it.copy(voices = updated)
            }
            
            com.nekospeak.tts.data.PocketVoiceRepository.downloadVoice(context, voiceId) { success ->
                viewModelScope.launch {
                    if (success) {
                        // Reload voices to pick up the new download
                        loadVoices()
                    } else {
                        // Reset download state on failure
                        _uiState.update { 
                            val updated = allVoices.map { v ->
                                if (v.id == voiceId) v.copy(downloadState = com.nekospeak.tts.data.DownloadState.NotDownloaded)
                                else v
                            }
                            it.copy(voices = updated)
                        }
                    }
                }
            }
        }
    }
    
    fun selectLanguage(language: String?) {
        _uiState.update { it.copy(selectedLanguage = language) }
        applyFilters()
    }
    
    fun selectGender(gender: String?) {
        _uiState.update { it.copy(selectedGender = gender) }
        applyFilters()
    }
    
    fun selectRegion(region: String?) {
        _uiState.update { it.copy(selectedRegion = region) }
        applyFilters()
    }
    
    fun clearFilters() {
        _uiState.update { 
            it.copy(
                searchQuery = "",
                selectedLanguage = null,
                selectedGender = null,
                selectedRegion = null,
                selectedQuality = null
            )
        }
        applyFilters()
    }
    
    /**
     * Set processing status message to show in the UI.
     * Pass null to clear the status.
     */
    fun setProcessingStatus(status: String?) {
        _uiState.update { it.copy(processingStatus = status) }
    }
    
    fun selectQuality(quality: String?) {
        _uiState.update { it.copy(selectedQuality = quality) }
        applyFilters()
    }
    
    private fun applyFilters() {
        val currentState = _uiState.value
        val query = currentState.searchQuery.lowercase()
        
        val filtered = allVoices.filter { voice ->
            val matchesSearch = voice.name.lowercase().contains(query) || 
                              voice.language.contains(query)
            
            val matchesLang = currentState.selectedLanguage?.let { it == voice.language } ?: true
            val matchesGender = currentState.selectedGender?.let { it == voice.gender } ?: true
            val matchesRegion = currentState.selectedRegion?.let { it == voice.region } ?: true
            
            // Quality filter for Piper voices
            val matchesQuality = currentState.selectedQuality?.let { quality ->
                voice.name.contains("($quality)", ignoreCase = true)
            } ?: true
            
            matchesSearch && matchesLang && matchesGender && matchesRegion && matchesQuality
        }
        
        val sorted = when(currentState.sortBy) {
            VoiceSortOption.NAME -> filtered.sortedBy { it.name }
            VoiceSortOption.LANGUAGE -> filtered.sortedBy { it.language }
        }
        
        _uiState.update { it.copy(filteredVoices = sorted) }
    }
    fun getSampleTextForVoice(voiceId: String?): String {
        val voice = allVoices.find { it.id == voiceId } ?: return "Hello, I am NekoSpeak."
        val lang = voice.language.lowercase() // e.g. "English (United States)" or "en_US"
        
        return when {
            lang.contains("tamil") || lang.contains("ta_in") -> "வணக்கம், நான் NekoSpeak."
            lang.contains("spanish") || lang.contains("es_") -> "Hola, soy NekoSpeak."
            lang.contains("french") || lang.contains("fr_") -> "Bonjour, je suis NekoSpeak."
            lang.contains("german") || lang.contains("de_") -> "Hallo, ich bin NekoSpeak."
            lang.contains("japanese") || lang.contains("ja_") -> "こんにちは、NekoSpeakです。"
            lang.contains("chinese") || lang.contains("zh_") -> "你好，我是NekoSpeak。"
            else -> "Hello, I am NekoSpeak."
        }
    }

    fun cloneVoice(path: String, name: String, transcript: String = "") {
        android.util.Log.i("VoicesViewModel", "cloneVoice called: path=$path, name=$name")
        SupportLogStore.log(context, "VoicesViewModel", "Clone requested for voice='$name'")
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            _uiState.update {
                it.copy(
                    isLoading = true,
                    processingStatus = "Processing voice: $name...",
                    cloneErrorMessage = null
                )
            }
            try {
                // Check if file exists
                val sourceFile = java.io.File(path)
                if (!sourceFile.exists()) {
                    android.util.Log.e("VoicesViewModel", "Audio file does not exist: $path")
                    SupportLogStore.log(context, "VoicesViewModel", "Clone failed: source audio file missing")
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            processingStatus = null,
                            cloneErrorMessage = "Voice cloning failed. Audio file could not be found."
                        )
                    }
                    return@launch
                }
                android.util.Log.i("VoicesViewModel", "Audio file exists: ${sourceFile.length()} bytes")
                
                // CRITICAL: Copy file to permanent location BEFORE engine init
                // The engine init takes ~10s, during which cache files may be deleted
                val modelPrefix = PrefsManager(context).currentModel.takeIf { it == "omnivoice" } ?: "pocket"
                val voicesDir = java.io.File(context.filesDir, "$modelPrefix/voice_clone_input")
                voicesDir.mkdirs()
                val permanentFile = java.io.File(voicesDir, "input_${System.currentTimeMillis()}.wav")
                sourceFile.copyTo(permanentFile, overwrite = true)
                android.util.Log.i("VoicesViewModel", "Copied to permanent location: ${permanentFile.absolutePath}")
                
                // Update status: Initializing engine
                _uiState.update { it.copy(processingStatus = "Encoding voice: $name (this may take a moment)...") }
                
                // Initialize a clone-capable engine — use the current model's engine
                val prefs = PrefsManager(context)
                val currentModel = prefs.currentModel
                val engine: com.nekospeak.tts.engine.TtsEngine = when (currentModel) {
                    "omnivoice" -> {
                        android.util.Log.i("VoicesViewModel", "Initializing OmniVoiceEngine for cloning...")
                        com.nekospeak.tts.engine.OmniVoiceEngine(context)
                    }
                    else -> {
                        android.util.Log.i("VoicesViewModel", "Initializing PocketTtsEngine (clone-only) for cloning...")
                        com.nekospeak.tts.engine.pocket.PocketTtsEngine(context, cloneOnly = true)
                    }
                }
                try {
                    if (engine.initialize()) {
                        android.util.Log.i("VoicesViewModel", "Engine initialized, calling cloneVoice...")
                        SupportLogStore.log(context, "VoicesViewModel", "Clone engine initialized for model=$currentModel")
                        // TODO: Use transcript for text conditioning in voice cloning
                        val voiceId = engine.cloneVoice(permanentFile.absolutePath, name)
                        if (voiceId != null) {
                            android.util.Log.i("VoicesViewModel", "Voice cloned successfully: $voiceId")
                            SupportLogStore.log(context, "VoicesViewModel", "Clone succeeded: voiceId='$voiceId'")
                            _uiState.update { it.copy(cloneErrorMessage = null) }
                        } else {
                            android.util.Log.e("VoicesViewModel", "cloneVoice returned null")
                            SupportLogStore.log(context, "VoicesViewModel", "Clone failed: cloneVoice returned null")
                            _uiState.update {
                                it.copy(cloneErrorMessage = "Voice cloning failed. Please try again with a clearer recording.")
                            }
                        }

                        loadVoices() // Refresh list to show new voice
                    } else {
                        android.util.Log.e("VoicesViewModel", "Failed to initialize engine for cloning")
                        SupportLogStore.log(context, "VoicesViewModel", "Clone failed: engine initialization failed")
                        _uiState.update {
                            it.copy(cloneErrorMessage = "Voice cloning failed. The cloning engine could not be initialized.")
                        }
                    }
                } finally {
                    engine.release()
                    // Clean up temporary input file
                    permanentFile.delete()
                }
            } catch (e: Exception) {
                android.util.Log.e("VoicesViewModel", "Error in cloneVoice", e)
                e.printStackTrace()
                SupportLogStore.log(context, "VoicesViewModel", "Clone failed with exception", e)
                _uiState.update {
                    it.copy(cloneErrorMessage = "Voice cloning failed. Please try again.")
                }
            }
            _uiState.update { it.copy(isLoading = false, processingStatus = null) }
        }
    }

    fun clearCloneError() {
        _uiState.update { it.copy(cloneErrorMessage = null) }
    }
    
    /**
     * Delete a cloned voice.
     */
    fun deleteClonedVoice(voiceId: String) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                // Delete the voice file
                val voiceFile = File(context.filesDir, "pocket/cloned_voices/$voiceId.bin")
                if (voiceFile.exists()) {
                    voiceFile.delete()
                    android.util.Log.i("VoicesViewModel", "Deleted cloned voice: $voiceId")
                }
                
                // Also delete cached embedding if exists
                val cacheFile = File(context.cacheDir, "pocket/$voiceId.emb")
                if (cacheFile.exists()) {
                    cacheFile.delete()
                }
                
                // Refresh the voice list
                loadVoices()
            } catch (e: Exception) {
                android.util.Log.e("VoicesViewModel", "Error deleting cloned voice", e)
            }
        }
    }
}
