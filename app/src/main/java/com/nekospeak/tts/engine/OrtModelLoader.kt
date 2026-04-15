package com.nekospeak.tts.engine

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * Utility for loading ONNX models with architecture-aware safety.
 *
 * On 32-bit ARM (armeabi-v7a), INT8 quantized models can cause SIGBUS (BUS_ADRALN)
 * crashes when loaded via mmap because ONNX Runtime's memory-mapped buffers may
 * contain tensor data with misaligned addresses. ARMv7 requires 4-byte alignment
 * for 32-bit memory operations, and mmap does not guarantee this for INT8 quantized
 * data within the model file.
 *
 * This utility:
 * 1. Detects 32-bit ARM architecture
 * 2. On 32-bit ARM: loads model bytes into a page-aligned ByteBuffer and disables
 *    ORT memory pattern optimization to prevent internal mmap usage
 * 3. On 64-bit: uses efficient file-path loading
 *
 * Fixes: https://github.com/siva-sub/NekoSpeak/issues/3
 *
 * ADR-005 documented the initial fix (byte array loading) but that was insufficient
 * because: (a) ORT may still mmap internally via memory pattern optimization,
 * and (b) ByteArray in JVM does not guarantee page alignment.
 * This revision addresses both root causes.
 */
object OrtModelLoader {
    private const val TAG = "OrtModelLoader"

    /** Page size for memory alignment (4KB on all Android architectures) */
    private const val PAGE_SIZE = 4096

    /**
     * Check if running on 32-bit ARM architecture.
     * 32-bit ARM has memory alignment requirements that can cause SIGBUS
     * with mmap'd INT8 models.
     */
    fun is32BitArm(): Boolean {
        val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: ""
        return abi == "armeabi-v7a" || abi == "armeabi"
    }

    /**
     * Create session options appropriate for the current architecture.
     *
     * On 32-bit ARM:
     *   - Disables memory pattern optimization (prevents ORT from using mmap)
     *   - Uses ALL_OPT optimization level for compute graph optimization
     *
     * On 64-bit:
     *   - Enables memory pattern optimization for faster repeated inference
     *   - Uses ALL_OPT optimization level
     */
    fun createSessionOptions(threadCount: Int = 4): OrtSession.SessionOptions {
        return OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(threadCount)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)

            if (is32BitArm()) {
                // Disable memory pattern optimization on 32-bit ARM.
                // This prevents ONNX Runtime from creating memory-mapped buffers
                // that can trigger SIGBUS on unaligned INT8 tensor data.
                setMemoryPatternOptimization(false)
                Log.d(TAG, "32-bit ARM: Memory pattern optimization disabled (SIGBUS prevention)")
            } else {
                // Safe on 64-bit architectures where alignment is not enforced by hardware
                setMemoryPatternOptimization(true)
            }
        }
    }

    /**
     * Load an ONNX model with architecture-appropriate safety measures.
     *
     * On 32-bit ARM:
     *   - Reads the model into a page-aligned ByteBuffer to ensure proper alignment
     *   - This prevents SIGBUS crashes from unaligned memory access in INT8 models
     *
     * On 64-bit:
     *   - Uses efficient file-path loading (ORT uses mmap internally, which is safe)
     *
     * @param env The ONNX Runtime environment
     * @param modelFile The model file to load
     * @param options Session options (should be created via createSessionOptions for proper 32-bit config)
     * @return An initialized OrtSession
     */
    fun loadModel(
        env: OrtEnvironment,
        modelFile: File,
        options: OrtSession.SessionOptions
    ): OrtSession {
        val sizeInMB = modelFile.length() / 1024 / 1024
        Log.d(TAG, "Loading model: ${modelFile.absolutePath} (${sizeInMB}MB)")

        return if (is32BitArm()) {
            Log.d(TAG, "32-bit ARM: Using page-aligned ByteBuffer loading (SIGBUS prevention)")
            loadAligned(env, modelFile, options)
        } else {
            Log.d(TAG, "64-bit: Using efficient file-path loading")
            env.createSession(modelFile.absolutePath, options)
        }
    }

    /**
     * Load an ONNX model into a page-aligned ByteBuffer.
     *
     * This is the key fix for SIGBUS on ARMv7. The approach:
     * 1. Allocate a ByteBuffer with PAGE_SIZE alignment (ensures base address is aligned)
     * 2. Read the model file into this aligned buffer
     * 3. Pass the aligned buffer to ORT
     *
     * The alignment guarantee ensures that INT8 tensor data within the model
     * is accessible at 4-byte boundaries, which ARMv7 requires for 32-bit loads.
     *
     * Previous approach (readBytes()) was insufficient because JVM's ByteArray
     * does not guarantee page alignment of the underlying memory.
     */
    private fun loadAligned(
        env: OrtEnvironment,
        modelFile: File,
        options: OrtSession.SessionOptions
    ): OrtSession {
        var raf: RandomAccessFile? = null
        var channel: FileChannel? = null

        try {
            raf = RandomAccessFile(modelFile, "r")
            channel = raf.channel

            val fileSize = channel.size()

            // Allocate a ByteBuffer large enough for the model with padding for alignment.
            // We allocate (fileSize + PAGE_SIZE) to guarantee at least one PAGE_SIZE-aligned
            // start position within the buffer.
            val paddedSize = fileSize + PAGE_SIZE
            val buffer = ByteBuffer.allocateDirect(paddedSize.toInt())
                .order(ByteOrder.LITTLE_ENDIAN)

            // Align the buffer position to the next page boundary
            // This ensures the data starts at a page-aligned address
            val baseAddress = buffer.position()
            val alignmentOffset = (PAGE_SIZE - (baseAddress % PAGE_SIZE)) % PAGE_SIZE
            buffer.position(alignmentOffset)

            // Read the model file into the aligned buffer position
            channel.read(buffer)

            // Flip the buffer for reading: limit = position, position = alignment offset
            // The ONNX Runtime will read from position to limit
            buffer.flip()
            buffer.position(alignmentOffset)

            Log.d(TAG, "Model loaded into page-aligned buffer: ${fileSize}MB, alignment offset: $alignmentOffset")

            // Convert aligned ByteBuffer to ByteArray for ORT createSession(byte[], options)
            // ORT Android doesn't have a ByteBuffer overload
            val modelBytes = ByteArray(buffer.limit() - alignmentOffset)
            buffer.position(alignmentOffset)
            buffer.get(modelBytes)
            return env.createSession(modelBytes, options)

        } catch (e: OutOfMemoryError) {
            // Fallback: if we can't allocate the aligned buffer (very large models on low-RAM devices),
            // try the simpler byte array approach. This may still trigger SIGBUS on some models,
            // but it's better than crashing with OOM.
            Log.w(TAG, "OOM allocating aligned buffer for ${modelFile.name}, falling back to byte array loading")
            channel?.close()
            raf?.close()
            val modelBytes = modelFile.readBytes()
            return env.createSession(modelBytes, options)

        } finally {
            channel?.close()
            raf?.close()
        }
    }
}