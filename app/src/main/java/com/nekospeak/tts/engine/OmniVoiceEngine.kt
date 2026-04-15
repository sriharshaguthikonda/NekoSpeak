package com.nekospeak.tts.engine

import android.content.Context
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.log
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round

/**
 * OmniVoice diffusion-based TTS engine.
 *
 * Port of vocoloco_tts/workers/tts-worker.js — runs the OmniVoice iterative
 * masked diffusion model via ONNX Runtime for Android.
 *
 * Architecture:
 * - Main model: Qwen3-0.6B backbone, iterative diffusion transformer (2.3 GB sharded)
 * - Audio decoder: HiggsAudioV2, token→waveform (83 MB)
 * - Audio encoder: HiggsAudioV2, waveform→token for voice cloning (624 MB, optional)
 * - Tokenizer: Qwen2 BPE (loaded via SentencePiece)
 *
 * Generation pipeline:
 * 1. Text tokenization via SentencePiece (Qwen2 BPE)
 * 2. Iterative masked diffusion: 8-32 denoising steps with CFG
 * 3. Post-processing: log-softmax + CFG fusion + argmax (CPU path)
 * 4. Audio decoding: HiggsAudioV2 decoder converts tokens to 24kHz PCM
 * 5. Post-processing: trim silence, normalize amplitude
 *
 * Key patterns from vocoloco_tts:
 * - Seeded PRNG (mulberry32) for deterministic generation
 * - DurationEstimator for target token count prediction
 * - SentenceBuffer for smart text chunking
 * - CPU post-processing with pre-allocated buffers
 * - Voice cloning via audio encoder
 *
 * Model download: models are fetched from HuggingFace on first use
 * and cached to app storage.
 *
 * Reference: https://github.com/Magkino/vocoloco_tts
 * Model: https://huggingface.co/Gigsu/vocoloco-onnx
 * Original: https://github.com/k2-fsa/OmniVoice (Xiaomi/k2-fsa)
 */
class OmniVoiceEngine(private val context: Context) : TtsEngine {
    companion object {
        private const val TAG = "OmniVoiceEngine"
        const val SAMPLE_RATE = 24000
        private const val HF_BASE = "https://huggingface.co/Gigsu/vocoloco-onnx/resolve/main"

        // Model config defaults (from omnivoice-config.json)
        private const val NUM_AUDIO_CODEBOOK = 8
        private const val AUDIO_VOCAB_SIZE = 1025
        private const val AUDIO_MASK_ID = 1024
        private const val FRAME_RATE = 75

        // Default generation parameters
        private const val DEFAULT_NUM_STEPS = 20
        private const val DEFAULT_GUIDANCE_SCALE = 4.0f
        private const val DEFAULT_T_SHIFT = 0.05f
        private const val DEFAULT_LAYER_PENALTY = 5.0f
        private const val DEFAULT_POS_TEMP = 5.0f

        // Model files
        private val MODEL_FILES = listOf(
            "omnivoice-main-split.onnx",
            "omnivoice-decoder.onnx",
            "omnivoice-encoder-fixed.onnx"  // Optional — for voice cloning
        )
        private val DATA_SHARDS = (0..4).map { "omnivoice-main.onnx_data_$it" }
    }

    // ONNX sessions
    private var ortEnv: OrtEnvironment? = null
    private var mainSession: OrtSession? = null
    private var decoderSession: OrtSession? = null
    private var encoderSession: OrtSession? = null  // Optional — voice cloning

    // Tokenizer
    private var tokenizer: Qwen2Tokenizer? = null

    // Config
    private var numAudioCodebook = NUM_AUDIO_CODEBOOK
    private var audioVocabSize = AUDIO_VOCAB_SIZE
    private var audioMaskId = AUDIO_MASK_ID

    // State
    private var initialized = false
    private var stopRequested = false

    // Voice cloning state: voiceName → encoded reference [codebook][tokens]
    private var clonedVoices = mutableMapOf<String, ClonedVoice>()

    /** Encoded reference voice for cloning. */
    data class ClonedVoice(
        val name: String,
        val tokens: Array<LongArray>,  // [codebook][T]
        val refText: String? = null,
        val durationSec: Float = 0f
    )

    // ========================================================================
    // TtsEngine interface
    // ========================================================================

    override suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        if (initialized) return@withContext true

        try {
            ortEnv = OrtEnvironment.getEnvironment()

            // Check if model files exist
            val modelDir = File(context.filesDir, "omnivoice")
            if (!areModelsDownloaded(modelDir)) {
                Log.e(TAG, "OmniVoice models not downloaded. Need download step first.")
                return@withContext false
            }

            // Load config
            loadConfig(modelDir)

            // Load Qwen2 BPE tokenizer
            Log.i(TAG, "Loading Qwen2 tokenizer...")
            tokenizer = Qwen2Tokenizer.getInstance(context)
            if (!tokenizer!!.loaded) {
                Log.e(TAG, "Tokenizer failed to load")
                return@withContext false
            }
            Log.i(TAG, "Tokenizer loaded")

            // Create session options with SIGBUS prevention
            val opts = OrtModelLoader.createSessionOptions(threadCount = 4)

            // Load main model (sharded)
            val mainFile = File(modelDir, "omnivoice-main-split.onnx")
            Log.i(TAG, "Loading main model...")
            mainSession = OrtModelLoader.loadModel(ortEnv!!, mainFile, opts)
            Log.i(TAG, "Main model loaded")

            // Load decoder
            val decoderFile = File(modelDir, "omnivoice-decoder.onnx")
            Log.i(TAG, "Loading decoder...")
            decoderSession = OrtModelLoader.loadModel(ortEnv!!, decoderFile, opts)
            Log.i(TAG, "Decoder loaded")

            // Load encoder (optional — for voice cloning)
            val encoderFile = File(modelDir, "omnivoice-encoder-fixed.onnx")
            if (encoderFile.exists() && encoderFile.length() > 1024) {
                try {
                    Log.i(TAG, "Loading encoder...")
                    encoderSession = OrtModelLoader.loadModel(ortEnv!!, encoderFile, opts)
                    Log.i(TAG, "Encoder loaded — voice cloning available")
                } catch (e: Exception) {
                    Log.w(TAG, "Encoder load failed (voice cloning unavailable): ${e.message}")
                    encoderSession = null
                }
            } else {
                Log.i(TAG, "Encoder not present — voice cloning unavailable")
            }

            // Load persisted cloned voices from disk
            loadClonedVoices()

            initialized = true
            Log.i(TAG, "OmniVoice engine initialized successfully")
            return@withContext true

        } catch (e: Exception) {
            Log.e(TAG, "Initialization failed", e)
            release()
            return@withContext false
        }
    }

    override suspend fun generate(
        text: String,
        speed: Float,
        voice: String?,
        callback: (FloatArray) -> Unit
    ) {
        withContext(Dispatchers.IO) {
            if (!initialized) throw IllegalStateException("OmniVoice not initialized")
            stopRequested = false

            val mainSess = mainSession ?: throw IllegalStateException("Main session null")
            val decoderSess = decoderSession ?: throw IllegalStateException("Decoder session null")
            val env = ortEnv ?: throw IllegalStateException("ORT env null")
            val tok = tokenizer ?: throw IllegalStateException("Tokenizer not loaded")

            try {
                // 1. Resolve voice design or clone
                val preset = voice?.let { OmniVoiceVoiceDesign.getPreset(it) }
                val clonedVoice = voice?.let { clonedVoices[it] }

                val instruct = preset?.instruct  // e.g. "female, low pitch"
                val lang = preset?.lang ?: detectLanguage(text)
                val seed = preset?.seed ?: OmniVoiceVoiceDesign.hashInstruct(instruct)

                // 2. Split text into sentences
                val sentences = SentenceBuffer(minChars = 20, maxChars = 250) { }.split(text)
                Log.d(TAG, "Split into ${sentences.size} sentences, voice=$voice, lang=$lang")

                // 3. Process each sentence
                for (sentence in sentences) {
                    if (stopRequested) break
                    if (sentence.isBlank()) continue

                    Log.d(TAG, "Processing: \"${sentence.take(60)}...\"")

                    // 3a. Estimate target token count
                    val numTargetTokens = DurationEstimator.estimateTargetTokens(
                        sentence, speed = speed
                    )
                    Log.d(TAG, "Estimated tokens: $numTargetTokens")

                    // 3b. Tokenize style tokens: <|denoise|><|lang_start|>{lang}<|lang_end|><|instruct_start|>{instruct}<|instruct_end|>
                    val styleIds = tok.encodeStyle(lang = lang, instruct = instruct)

                    // 3c. Tokenize text: <|text_start|>{text}<|text_end|>
                    val textIds = tok.encodeTextWithRef(sentence, clonedVoice?.refText)

                    // 3d. Prepare inference inputs
                    val inputs = prepareInferenceInputs(
                        styleIds = styleIds,
                        textIds = textIds,
                        numTargetTokens = numTargetTokens,
                        refAudioTokens = clonedVoice?.tokens
                    )

                    // 3e. Run iterative diffusion with seeded PRNG
                    val prefs = com.nekospeak.tts.data.PrefsManager(context)
                    val audioTokens = generateIterative(
                        inputs, mainSess, env,
                        numSteps = prefs.omnivoiceNumSteps,
                        guidanceScale = prefs.omnivoiceGuidanceScale,
                        seed = seed
                    )

                    if (stopRequested) break

                    // 3f. Decode audio tokens to PCM
                    val rawPcm = decodeTokens(audioTokens, numTargetTokens, decoderSess, env)

                    // 3g. Post-process: trim silence, normalize
                    val pcm = postProcessAudio(rawPcm)

                    callback(pcm)
                }

            } catch (e: Exception) {
                Log.e(TAG, "Generation failed", e)
                throw e
            }
        }
    }

    override fun getSampleRate(): Int = SAMPLE_RATE

    override fun getVoices(): List<String> {
        return OmniVoiceVoiceDesign.getVoiceIds() + clonedVoices.keys
    }

    override fun release() {
        try { mainSession?.close() } catch (_: Exception) {}
        try { decoderSession?.close() } catch (_: Exception) {}
        try { encoderSession?.close() } catch (_: Exception) {}
        mainSession = null
        decoderSession = null
        encoderSession = null
        initialized = false
        Log.i(TAG, "OmniVoice engine released")
    }

    override fun stop() {
        stopRequested = true
    }

    override fun isInitialized(): Boolean = initialized

    override fun supportsVoiceCloning(): Boolean = encoderSession != null

    override fun supportsVoiceDesign(): Boolean = true

    override suspend fun cloneVoice(audioPath: String, voiceName: String): String? {
        val enc = encoderSession ?: return null
        val env = ortEnv ?: return null

        return withContext(Dispatchers.IO) {
            try {
                // Read WAV file and extract PCM float samples
                val pcm = readWavAsFloat32(audioPath)
                if (pcm.isEmpty()) return@withContext null

                // Align to hop_length (960 for 24kHz)
                val hopLength = 960
                val clipLen = pcm.size - (pcm.size % hopLength)
                val aligned = pcm.copyOf(clipLen)

                // Run encoder
                val inputTensor = OnnxTensor.createTensor(env, aligned, longArrayOf(1, 1, aligned.size.toLong()))
                val result = enc.run(mapOf("input_values" to inputTensor))

                // Extract audio codes [1, C, T]
                val codesTensor = result["audio_codes"] ?: return@withContext null
                val shape = codesTensor.info.shape
                val T = shape[2].toInt()
                val C = shape[1].toInt()

                // Reshape to [C][T]
                val codesData = codesTensor.value as Array<LongArray>
                val tokens = Array(C) { c -> LongArray(T) { t -> codesData[c][t] } }

                val durationSec = clipLen.toFloat() / SAMPLE_RATE
                val voice = ClonedVoice(voiceName, tokens, refText = null, durationSec)
                clonedVoices[voiceName] = voice
                saveClonedVoice(voice)
                Log.i(TAG, "Voice cloned: $voiceName ($T tokens, ${"%.1f".format(durationSec)}s)")
                return@withContext voiceName

            } catch (e: Exception) {
                Log.e(TAG, "Voice cloning failed", e)
                null
            }
        }
    }

    override fun getClonedVoices(): List<String> = clonedVoices.keys.toList()

    override fun deleteClonedVoice(voiceId: String): Boolean {
        clonedVoices.remove(voiceId)
        // Also delete from disk
        val file = File(context.filesDir, "omnivoice/cloned_voices/$voiceId.bin")
        if (file.exists()) file.delete()
        return true
    }

    /**
     * Save a cloned voice to disk for persistence across app restarts.
     * Format: magic(4) + version(2) + reserved(2) + nameLen(4) + name +
     *          refTextLen(4) + refText + codebooks(4) + T(4) + durationSec(4) +
     *          codebook0(T*8) + codebook1(T*8) + ...
     */
    private fun saveClonedVoice(voice: ClonedVoice) {
        try {
            val voicesDir = File(context.filesDir, "omnivoice/cloned_voices")
            voicesDir.mkdirs()
            val nameBytes = voice.name.toByteArray(Charsets.UTF_8)
            val refTextBytes = (voice.refText ?: "").toByteArray(Charsets.UTF_8)
            val C = voice.tokens.size
            val T = voice.tokens[0].size

            val size = 4 + 2 + 2 + 4 + nameBytes.size + 4 + refTextBytes.size +
                       4 + 4 + 4 + (C * T * 8)
            val buf = java.nio.ByteBuffer.allocate(size).order(java.nio.ByteOrder.LITTLE_ENDIAN)

            buf.putInt(0x56564F4F)  // "OOVV" magic
            buf.putShort(1)         // version
            buf.putShort(0)         // reserved
            buf.putInt(nameBytes.size)
            buf.put(nameBytes)
            buf.putInt(refTextBytes.size)
            buf.put(refTextBytes)
            buf.putInt(C)
            buf.putInt(T)
            buf.putFloat(voice.durationSec)

            for (c in 0 until C) {
                for (t in 0 until T) {
                    buf.putLong(voice.tokens[c][t])
                }
            }

            File(voicesDir, "${voice.name}.bin").writeBytes(buf.array())
            Log.d(TAG, "Saved cloned voice: ${voice.name}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save cloned voice: ${voice.name}", e)
        }
    }

    /** Load all persisted cloned voices from disk. */
    private fun loadClonedVoices() {
        val voicesDir = File(context.filesDir, "omnivoice/cloned_voices")
        if (!voicesDir.exists()) return

        for (file in voicesDir.listFiles()?.filter { it.name.endsWith(".bin") } ?: emptyList()) {
            try {
                val buf = java.nio.ByteBuffer.wrap(file.readBytes())
                    .order(java.nio.ByteOrder.LITTLE_ENDIAN)

                val magic = buf.int
                if (magic != 0x56564F4F) continue  // Not an OmniVoice clone file

                val version = buf.short
                buf.short  // reserved
                val nameLen = buf.int
                val nameBytes = ByteArray(nameLen); buf.get(nameBytes)
                val name = String(nameBytes, Charsets.UTF_8)
                val refTextLen = buf.int
                val refTextBytes = ByteArray(refTextLen); buf.get(refTextBytes)
                val refText = String(refTextBytes, Charsets.UTF_8).ifBlank { null }
                val C = buf.int
                val T = buf.int
                val durationSec = buf.float

                val tokens = Array(C) { c ->
                    LongArray(T) { t -> buf.long }
                }

                clonedVoices[name] = ClonedVoice(name, tokens, refText, durationSec)
                Log.d(TAG, "Loaded cloned voice: $name ($C codebooks, $T tokens)")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load cloned voice: ${file.name}", e)
            }
        }
    }

    // ========================================================================
    // Internal: model loading
    // ========================================================================

    private fun areModelsDownloaded(modelDir: File): Boolean {
        val mainFile = File(modelDir, "omnivoice-main-split.onnx")
        val decoderFile = File(modelDir, "omnivoice-decoder.onnx")
        if (!mainFile.exists() || mainFile.length() < 1024) return false
        if (!decoderFile.exists() || decoderFile.length() < 1024) return false
        // Check at least one data shard exists
        return DATA_SHARDS.any { File(modelDir, it).exists() }
    }

    private fun loadConfig(modelDir: File) {
        val configFile = File(modelDir, "omnivoice-config.json")
        if (configFile.exists()) {
            try {
                val json = configFile.readText()
                // Simple JSON parsing (no Gson dependency)
                numAudioCodebook = Regex("\"num_audio_codebook\"\\s*:\\s*(\\d+)").find(json)?.groupValues?.get(1)?.toInt() ?: NUM_AUDIO_CODEBOOK
                audioVocabSize = Regex("\"audio_vocab_size\"\\s*:\\s*(\\d+)").find(json)?.groupValues?.get(1)?.toInt() ?: AUDIO_VOCAB_SIZE
                audioMaskId = Regex("\"audio_mask_id\"\\s*:\\s*(\\d+)").find(json)?.groupValues?.get(1)?.toInt() ?: AUDIO_MASK_ID
                Log.d(TAG, "Config: codebooks=$numAudioCodebook, vocab=$audioVocabSize, mask=$audioMaskId")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse config, using defaults: ${e.message}")
            }
        }
    }

    // ========================================================================
    // Internal: text tokenization
    // ========================================================================

    /** Language detection from text content (used for style tokens). */
    private fun detectLanguage(text: String): String {
        val hasCjk = text.any { it.code in 0x4E00..0x9FFF || it.code in 0x3040..0x309F }
        val hasHangul = text.any { it.code in 0xAC00..0xD7AF }
        val hasArabic = text.any { it.code in 0x0600..0x06FF }
        val hasCyrillic = text.any { it.code in 0x0400..0x04FF }
        return when {
            hasCjk -> "zh"
            hasHangul -> "ko"
            hasArabic -> "ar"
            hasCyrillic -> "ru"
            else -> "en"
        }
    }

    // ========================================================================
    // Internal: inference input preparation
    // ========================================================================

    data class InferenceInputs(
        val batchInputIds: LongArray,   // [2, C, maxLen]
        val batchAudioMask: BooleanArray, // [2, maxLen]
        val batchAttentionMask: BooleanArray, // [2*maxLen*maxLen]
        val totalLen: Int,
        val numTargetTokens: Int,
        val targetOff: Int,
        val maxLen: Int
    )

    /**
     * Prepare batched inference inputs following vocoloco_tts exactly.
     *
     * Sequence layout: [style | text | ref_audio? | target_masked]
     * Batch: [conditional_full, unconditional_target_only] for CFG
     */
    private fun prepareInferenceInputs(
        styleIds: LongArray,
        textIds: LongArray,
        numTargetTokens: Int,
        refAudioTokens: Array<LongArray>? = null
    ): InferenceInputs {
        val C = numAudioCodebook
        val maskId = audioMaskId.toLong()
        val refLen = refAudioTokens?.getOrNull(0)?.size ?: 0

        val totalLen = styleIds.size + textIds.size + refLen + numTargetTokens
        val maxLen = totalLen

        // Build input_ids: [2, C, maxLen]
        val batchInputIds = LongArray(2 * C * maxLen) { maskId }

        // Conditional: full sequence [style | text | ref_audio | target_mask]
        for (c in 0 until C) {
            var pos = 0
            // Style tokens (replicated across codebooks)
            for (s in styleIds) batchInputIds[c * maxLen + pos++] = s
            // Text tokens
            for (t in textIds) batchInputIds[c * maxLen + pos++] = t
            // Reference audio tokens
            if (refAudioTokens != null && c < refAudioTokens.size) {
                for (t in refAudioTokens[c]) batchInputIds[c * maxLen + pos++] = t
            }
            // Target = all mask
            // (already initialized to maskId)
        }

        // Unconditional: only target masked tokens
        for (c in 0 until C) {
            for (t in 0 until numTargetTokens) {
                batchInputIds[(C + c) * maxLen + t] = maskId
            }
        }

        val targetOff = styleIds.size + textIds.size + refLen

        // Audio mask: true for audio positions (ref + target)
        val batchAudioMask = BooleanArray(2 * maxLen)
        val audioStart = styleIds.size + textIds.size  // ref_audio starts here
        for (i in audioStart until totalLen) batchAudioMask[i] = true
        // Unconditional: target is audio
        for (t in 0 until numTargetTokens) batchAudioMask[maxLen + t] = true

        // Attention mask: block-causal for conditional, full for unconditional target
        val batchAttentionMask = BooleanArray(2 * maxLen * maxLen)
        // Conditional: full attention within valid length
        for (q in 0 until totalLen) {
            for (k in 0 until totalLen) {
                batchAttentionMask[q * maxLen + k] = true
            }
        }
        // Unconditional: full attention within target length
        for (q in 0 until numTargetTokens) {
            for (k in 0 until numTargetTokens) {
                batchAttentionMask[maxLen * maxLen + q * maxLen + k] = true
            }
        }
        // Padding positions attend to themselves
        for (p in numTargetTokens until maxLen) {
            batchAttentionMask[maxLen * maxLen + p * maxLen + p] = true
        }

        return InferenceInputs(
            batchInputIds, batchAudioMask, batchAttentionMask,
            totalLen, numTargetTokens, targetOff, maxLen
        )
    }

    // ========================================================================
    // Internal: iterative diffusion loop
    // ========================================================================

    private suspend fun generateIterative(
        inputs: InferenceInputs,
        session: OrtSession,
        env: OrtEnvironment,
        numSteps: Int = DEFAULT_NUM_STEPS,
        guidanceScale: Float = DEFAULT_GUIDANCE_SCALE,
        seed: Int = 42
    ): LongArray = withContext(Dispatchers.Default) {
        val C = numAudioCodebook
        val V = audioVocabSize
        val maskId = audioMaskId.toLong()
        val maxLen = inputs.maxLen
        val numTargetTokens = inputs.numTargetTokens
        val totalPositions = C * numTargetTokens

        // Token state: initially all masked
        val tokens = LongArray(totalPositions) { maskId }

        // Pre-allocated buffers for post-processing
        val condLogProbs = FloatArray(V)
        val uncondLogProbs = FloatArray(V)
        val guidedProbs = FloatArray(V)

        // Get unmasking schedule
        val timeSteps = getTimeSteps(0.0, 1.0, numSteps + 1, DEFAULT_T_SHIFT)
        val schedule = computeSchedule(totalPositions, numSteps, timeSteps)

        // Seeded PRNG for deterministic voice reproduction
        val rng = mulberry32(seed)

        for (step in 0 until numSteps) {
            if (stopRequested || !isActive) break
            val k = schedule[step]
            if (k <= 0) continue

            Log.v(TAG, "Step ${step + 1}/$numSteps: unmasking $k tokens")

            // Update batch input_ids with current token state
            val bIds = inputs.batchInputIds.copyOf()
            for (c in 0 until C) {
                for (t in 0 until numTargetTokens) {
                    val v = tokens[c * numTargetTokens + t]
                    bIds[c * maxLen + inputs.targetOff + t] = v
                    bIds[(C + c) * maxLen + t] = v
                }
            }

            // Run model inference
            val inputIdsTensor = OnnxTensor.createTensor(env, bIds, longArrayOf(2, C.toLong(), maxLen.toLong()))
            val audioMaskTensor = OnnxTensor.createTensor(env, inputs.batchAudioMask, longArrayOf(2, maxLen.toLong()))
            val attnShape = longArrayOf(2, 1, maxLen.toLong(), maxLen.toLong())
            val attnTensor = OnnxTensor.createTensor(env, inputs.batchAttentionMask, attnShape)

            val results = session.run(mapOf(
                "input_ids" to inputIdsTensor,
                "audio_mask" to audioMaskTensor,
                "attention_mask" to attnTensor
            ))

            val logitsTensor = results["audio_logits"] ?: break
            val logits = logitsTensor.value as Array<Array<Array<FloatArray>>>
            // logits shape: [2, C, maxLen, V]

            // Post-process: log-softmax + CFG fusion + argmax
            val pred = IntArray(totalPositions)
            val scores = FloatArray(totalPositions)

            cpuPostProcess(
                logits, C, maxLen, V, numTargetTokens, inputs.targetOff,
                audioMaskId, guidanceScale, DEFAULT_LAYER_PENALTY,
                pred, scores,
                condLogProbs, uncondLogProbs, guidedProbs
            )

            // Apply positional temperature + Gumbel noise to masked positions
            val invTemp = 1.0f / DEFAULT_POS_TEMP
            for (i in 0 until totalPositions) {
                if (tokens[i] != maskId) {
                    scores[i] = Float.NEGATIVE_INFINITY
                } else {
                    val gumbel = -log(-log(rng() + 1e-10f) + 1e-10f)
                    scores[i] = scores[i] * invTemp + gumbel
                }
            }

            // Top-k unmasking
            topKUnmask(scores, pred, tokens, totalPositions, k)
        }

        return@withContext tokens
    }

    // ========================================================================
    // Internal: time steps and schedule
    // ========================================================================

    /** Compute time steps with shift (port of _get_time_steps). */
    private fun getTimeSteps(tStart: Double, tEnd: Double, numStep: Int, tShift: Double): DoubleArray {
        val steps = DoubleArray(numStep + 1)
        for (i in 0..numStep) {
            var t = tStart + (tEnd - tStart) * (i.toDouble() / numStep)
            t = tShift * t / (1 + (tShift - 1) * t)
            steps[i] = t
        }
        return steps
    }

    /** Compute unmasking schedule from time steps. */
    private fun computeSchedule(totalMask: Int, numSteps: Int, timeSteps: DoubleArray): IntArray {
        val schedule = IntArray(numSteps)
        var remaining = totalMask
        for (s in 0 until numSteps) {
            val frac = if (s + 1 < timeSteps.size) (timeSteps[s + 1] - timeSteps[s]) else 0.0
            val n = if (s == numSteps - 1) remaining
                     else min(ceil(totalMask * frac).toInt(), remaining)
            schedule[s] = n
            remaining -= n
        }
        return schedule
    }

    // ========================================================================
    // Internal: CPU post-processing (log-softmax + CFG + argmax)
    // ========================================================================

    private fun cpuPostProcess(
        logits: Array<Array<Array<FloatArray>>>,
        C: Int, maxLen: Int, V: Int,
        numTargetTokens: Int, targetOff: Int, maskId: Int,
        guidanceScale: Float, layerPenalty: Float,
        pred: IntArray, scores: FloatArray,
        _cLP: FloatArray, _uLP: FloatArray, _g: FloatArray
    ) {
        val gScale1 = 1.0f + guidanceScale

        for (c in 0 until C) {
            val layerScore = layerPenalty * c
            for (t in 0 until numTargetTokens) {
                val cOff = (c * maxLen + targetOff + t) * V
                val uOff = ((C + c) * maxLen + t) * V

                // Get conditional logits (from batch index 0)
                val condRow = logits[0][c][targetOff + t]
                val uncondRow = logits[1][c][t]

                // Log-softmax for conditional
                logSoftmaxInto(condRow, _cLP)
                // Log-softmax for unconditional
                logSoftmaxInto(uncondRow, _uLP)

                // CFG fusion + find max for final log-softmax
                var gMax = Float.NEGATIVE_INFINITY
                for (v in 0 until V) {
                    _g[v] = gScale1 * _cLP[v] - guidanceScale * _uLP[v]
                    if (_g[v] > gMax) gMax = _g[v]
                }

                // Compute log-sum-exp
                var gSum = 0.0f
                for (v in 0 until V) gSum += exp(_g[v] - gMax)
                val gLse = gMax + log(gSum)

                // Argmax (skip maskId)
                var bestV = 0
                var bestS = Float.NEGATIVE_INFINITY
                for (v in 0 until V) {
                    if (v == maskId) continue
                    val lp = _g[v] - gLse
                    if (lp > bestS) { bestS = lp; bestV = v }
                }

                val idx = c * numTargetTokens + t
                pred[idx] = bestV
                scores[idx] = bestS - layerScore
            }
        }
    }

    /** In-place log-softmax. */
    private fun logSoftmaxInto(input: FloatArray, output: FloatArray) {
        var max = Float.NEGATIVE_INFINITY
        for (v in input) if (v > max) max = v
        var sum = 0.0f
        for (i in input.indices) sum += exp(input[i] - max)
        val lse = max + log(sum)
        for (i in input.indices) output[i] = input[i] - lse
    }

    /** Top-k partial unmasking. */
    private fun topKUnmask(scores: FloatArray, pred: IntArray, tokens: LongArray, n: Int, k: Int) {
        // Find top-k by partial selection sort
        val indices = IntArray(n) { it }
        var count = 0
        for (i in indices.indices) {
            if (scores[i] > Float.NEGATIVE_INFINITY) {
                indices[count++] = i
            }
        }
        val actualK = min(k, count)
        for (i in 0 until actualK) {
            var maxIdx = i
            for (j in i + 1 until count) {
                if (scores[indices[j]] > scores[indices[maxIdx]]) maxIdx = j
            }
            if (maxIdx != i) { val tmp = indices[i]; indices[i] = indices[maxIdx]; indices[maxIdx] = tmp }
            tokens[indices[i]] = pred[indices[i]].toLong()
        }
    }

    // ========================================================================
    // Internal: audio decoding
    // ========================================================================

    private fun decodeTokens(
        tokens: LongArray,
        numTargetTokens: Int,
        session: OrtSession,
        env: OrtEnvironment
    ): FloatArray {
        val C = numAudioCodebook
        val codes = LongArray(C * numTargetTokens)
        System.arraycopy(tokens, 0, codes, 0, min(tokens.size, codes.size))

        val codesTensor = OnnxTensor.createTensor(env, codes, longArrayOf(1, C.toLong(), numTargetTokens.toLong()))
        val result = session.run(mapOf("audio_codes" to codesTensor))
        val audioTensor = result["audio_values"] ?: return FloatArray(0)

        return audioTensor.value as FloatArray
    }

    // ========================================================================
    // Internal: audio post-processing
    // ========================================================================

    /** Trim silence and normalize amplitude (port of postProcessAudio). */
    private fun postProcessAudio(pcm: FloatArray): FloatArray {
        val thresh = 0.005f
        val margin = (SAMPLE_RATE * 0.02).toInt()

        var start = 0
        var end = pcm.size
        for (i in pcm.indices) {
            if (abs(pcm[i]) > thresh) { start = max(0, i - margin); break }
        }
        for (i in pcm.size - 1 downTo 0) {
            if (abs(pcm[i]) > thresh) { end = min(pcm.size, i + margin); break }
        }

        val out = pcm.copyOfRange(start, end)

        // Normalize to 0.5 peak
        var peak = 0f
        for (s in out) { val a = abs(s); if (a > peak) peak = a }
        if (peak > 1e-6f) {
            val scale = 0.5f / peak
            for (i in out.indices) out[i] *= scale
        }

        return out
    }

    // ========================================================================
    // Internal: utilities
    // ========================================================================

    /** Seeded PRNG (mulberry32) for deterministic generation. */
    private fun mulberry32(seed: Int): () -> Float {
        var s = seed
        return {
            s = (s + 0x6D2B79F5)
            var t = ((s xor (s ushr 15)) * (1 or s))
            t = (t + ((t xor (t ushr 7)) * (61 or t))) xor t
            ((t xor (t ushr 14)) ushr 0).toFloat() / 4294967296f
        }
    }

    /** Read a WAV file as float32 PCM samples. */
    private fun readWavAsFloat32(path: String): FloatArray {
        try {
            val file = File(path)
            if (!file.exists()) return FloatArray(0)
            val bytes = file.readBytes()
            // Parse WAV header (simple: find "data" chunk)
            var dataOffset = 12
            while (dataOffset < bytes.size - 8) {
                val chunkId = String(bytes, dataOffset, 4, Charsets.US_ASCII)
                val chunkSize = ByteBuffer.wrap(bytes, dataOffset + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
                if (chunkId == "data") {
                    dataOffset += 8
                    val numSamples = chunkSize / 2  // 16-bit samples
                    val samples = FloatArray(numSamples)
                    for (i in 0 until numSamples) {
                        val sampleOffset = dataOffset + i * 2
                        if (sampleOffset + 1 >= bytes.size) break
                        val sample = ByteBuffer.wrap(bytes, sampleOffset, 2).order(ByteOrder.LITTLE_ENDIAN).short
                        samples[i] = sample.toFloat() / 32768f
                    }
                    return samples
                }
                dataOffset += 8 + chunkSize
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read WAV: ${e.message}")
        }
        return FloatArray(0)
    }
}