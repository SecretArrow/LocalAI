package com.localai.runtime.core.runtime.cpu

import android.util.Log

/**
 * JNI bridge to liblocalai_runtime.so (llama.cpp b4755).
 *
 * The native library is optional at runtime: if it cannot be loaded
 * (e.g. unsupported ABI), [available] becomes false and the CPU backend
 * reports itself as unavailable instead of crashing.
 */
object LlamaBridge {

    @Volatile
    var loadError: String? = null
        private set

    val available: Boolean by lazy {
        try {
            System.loadLibrary("localai_runtime")
            nativeInit()
            Log.i(TAG, "Native CPU runtime loaded")
            true
        } catch (t: Throwable) {
            loadError = t.message ?: t.javaClass.simpleName
            Log.w(TAG, "Native CPU runtime unavailable: $loadError")
            false
        }
    }

    private const val TAG = "LlamaBridge"

    init {
        // Trigger lazy load on first access of the object.
        available
    }

    /** Initialises the llama.cpp global backend. Safe to call once at startup. */
    private external fun nativeInit()

    /** Loads a GGUF model. Returns a native handle or 0 on failure. */
    external fun nativeLoadModel(path: String, nCtx: Int, nThreads: Int, useMmap: Boolean): Long

    /** Frees a model handle returned by [nativeLoadModel]. */
    external fun nativeFreeModel(modelPtr: Long)

    /** Actual context length of the loaded model (may differ from requested). */
    external fun nativeContextLength(modelPtr: Long): Int

    /** Number of tokens a text tokenises to; -1 on failure. Useful for token accounting. */
    external fun nativeTokenizeCount(modelPtr: Long, text: String): Int

    /**
     * Streaming generation. Invokes [onToken] for every generated piece;
     * when the lambda returns false, generation stops (cancellation).
     */
    external fun nativeGenerate(
        modelPtr: Long,
        roles: Array<String>,
        contents: Array<String>,
        temperature: Float,
        topP: Float,
        topK: Int,
        minP: Float,
        repeatPenalty: Float,
        seed: Int,
        maxTokens: Int,
        onToken: (String) -> Boolean,
    )

    /**
     * Benchmark: returns [promptTokensPerSec, generationTokensPerSec, promptMs, genMs]
     * or null on failure.
     */
    external fun nativeBenchmark(modelPtr: Long, nPromptTokens: Int, nGenTokens: Int): DoubleArray?
}
