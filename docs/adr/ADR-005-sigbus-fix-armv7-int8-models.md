# ADR-005: Fix SIGBUS Crash on 32-bit ARMv7 for INT8 ONNX Models

## Status
**Accepted** - 2026-02-02, **Revised** - 2026-04-14

## Context
Users reported a native crash (SIGBUS, code BUS_ADRALN) when loading INT8 quantized ONNX models on 32-bit ARMv7 (armeabi-v7a) Android devices. The crash occurred during `OrtSession.createSession()` before any inference was executed.

**Error signature:**
```
Fatal signal 7 (SIGBUS), code 1 (BUS_ADRALN), fault addr 0xc41dcc11
```

The fault address ending in `0x...1` confirmed misaligned memory access (not 4-byte aligned).

### First Fix Attempt (v1.4.1)
The initial fix used `modelFile.readBytes()` to load models into a byte array on 32-bit ARM. This was **insufficient** — users continued to experience SIGBUS crashes because:
1. JVM's `ByteArray` does not guarantee page-aligned memory allocation
2. ONNX Runtime's `setMemoryPatternOptimization(true)` internally uses mmap even when given a byte array, bypassing the alignment guarantee
3. The `createSession(byte[])` overload still allows ORT to create internal mmap'd buffers during session optimization

### Root Cause (Revised)
Two distinct causes for SIGBUS on 32-bit ARM:
1. **Unaligned tensor data in mmap'd model files**: INT8 quantized tensors can have 1-byte aligned offsets. When ORT mmaps the model file, these land at unaligned addresses.
2. **ORT memory pattern optimization**: `setMemoryPatternOptimization(true)` causes ORT to create internal memory-mapped buffers for optimization, which can also trigger SIGBUS on ARMv7.

## Decision
Implement **comprehensive architecture-aware model loading** via a shared `OrtModelLoader` utility:

1. **On 32-bit ARM**:
   - Load models into **page-aligned `ByteBuffer`s** using `ByteBuffer.allocateDirect()` with `PAGE_SIZE` alignment offset, ensuring the data starts at a 4096-byte boundary
   - **Disable ORT memory pattern optimization** via `setMemoryPatternOptimization(false)` to prevent internal mmap usage
   - Fallback to `readBytes()` if the aligned buffer allocation fails (OOM), which is better than crashing

2. **On 64-bit**:
   - Use efficient mmap-based file path loading (`createSession(String)`)
   - Enable memory pattern optimization for faster repeated inference

```kotlin
object OrtModelLoader {
    private const val PAGE_SIZE = 4096

    fun is32BitArm(): Boolean {
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: ""
        return abi == "armeabi-v7a" || abi == "armeabi"
    }

    fun createSessionOptions(threadCount: Int = 4): OrtSession.SessionOptions {
        return OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(threadCount)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            if (is32BitArm()) {
                setMemoryPatternOptimization(false)  // Prevent internal mmap on ARMv7
            } else {
                setMemoryPatternOptimization(true)
            }
        }
    }

    fun loadModel(env: OrtEnvironment, modelFile: File, options: OrtSession.SessionOptions): OrtSession {
        return if (is32BitArm()) {
            loadAligned(env, modelFile, options)  // Page-aligned ByteBuffer
        } else {
            env.createSession(modelFile.absolutePath, options)  // Efficient mmap
        }
    }

    private fun loadAligned(env: OrtEnvironment, modelFile: File, options: OrtSession.SessionOptions): OrtSession {
        // Allocate direct ByteBuffer with PAGE_SIZE alignment
        // Falls back to readBytes() on OOM
    }
}
```

## Consequences

### Positive
- Resolves SIGBUS crash on 32-bit ARMv7 devices (both root causes)
- No performance impact on 64-bit devices (still uses mmap + pattern optimization)
- Centralized in `OrtModelLoader` — all engines use the same safe loading logic
- OOM fallback prevents secondary crashes on low-memory devices

### Negative
- Increased memory usage on 32-bit devices (model bytes in aligned direct buffer)
- `setMemoryPatternOptimization(false)` slightly reduces inference speed on ARMv7 (patterns must be recomputed each session instead of cached)
- For 72MB INT8 model, this adds ~72MB heap allocation during load

### Trade-off Accepted
The memory and performance costs are acceptable because:
1. 32-bit ARMv7 devices are increasingly rare (most devices since 2015 are 64-bit)
2. A working app with higher memory usage is better than a crashing app
3. Memory is freed after session creation completes
4. The slight inference speed reduction from disabled memory patterns is negligible compared to the ARMv7 device's already limited CPU

## Files Changed
- `OrtModelLoader.kt` - **NEW**: Shared utility for architecture-aware model loading
- `PocketTtsEngine.kt` - Use `OrtModelLoader` instead of inline `is32BitArm()` + `readBytes()`
- `KokoroEngine.kt` - Use `OrtModelLoader` instead of inline alignment logic
- `PiperEngine.kt` - Use `OrtModelLoader` instead of inline alignment logic
- `GtcrnDenoiser.kt` - Use `OrtModelLoader` instead of inline alignment logic

## References
- [GitHub Issue #3](https://github.com/siva-sub/NekoSpeak/issues/3) - Original SIGBUS report and v1.4.1 follow-up
- ARM Architecture Reference Manual - Memory alignment requirements
- ONNX Runtime source - Memory pattern optimization and mmap usage