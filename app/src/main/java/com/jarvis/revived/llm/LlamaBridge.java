package com.jarvis.revived.llm;

import android.util.Log;

/** JNI surface of jarvis_llm.cpp. Use {@link LlamaModel}; don't call this directly. */
final class LlamaBridge {

    private static final String TAG = "LlamaBridge";

    /** Must match kMode* in jarvis_llm.cpp. */
    static final int MODE_CHAT = 0;
    static final int MODE_JSON = 1;
    static final int MODE_FREE = 2;

    /** Cache slots (llama.cpp sequence ids). With a one-slot plan both map to 0. */
    static final int SLOT_CHAT = 0;
    static final int SLOT_TOOLS = 1;

    static final boolean LIBRARY_LOADED;

    static {
        boolean ok;
        try {
            System.loadLibrary("jarvis_llm");
            ok = true;
        } catch (Throwable t) {
            Log.e(TAG, "Native library failed to load", t);
            ok = false;
        }
        LIBRARY_LOADED = ok;
    }

    interface StreamCallback {
        /** Called from the generating thread with a complete, speakable chunk. */
        void onPartial(String chunk);
    }

    private LlamaBridge() {
    }

    /** Loads the weights only; follow with {@link #nativeModelInfo} and {@link #nativeCreateContext}. */
    static native boolean nativeLoadModel(String path, int threads);

    /** {n_layer, n_head, n_head_kv, n_embd, n_ctx_train, has_chat_template (0/1)}. */
    static native int[] nativeModelInfo();

    /** Short description, e.g. "qwen2 1.5B Q4_K - Medium". */
    static native String nativeModelDesc();

    /** Creates the KV cache: {@code slots} × {@code tokensPerSlot} tokens. */
    static native boolean nativeCreateContext(int tokensPerSlot, int slots);

    /**
     * Applies the model's chat template. Returns null if the model has none that
     * llama.cpp understands (caller falls back to the Phi-3 format).
     */
    static native String nativeFormatChat(String[] roles, String[] contents, boolean addAssistant);

    /**
     * @param maxNewTokens 0 = only decode the prompt into the slot's cache (warm-up)
     * @param grammar      GBNF for MODE_JSON, or null
     * @param slot         {@link #SLOT_CHAT} or {@link #SLOT_TOOLS}
     */
    static native String nativeGenerate(String prompt, int maxNewTokens, int mode, int maxSentences,
                                        String grammar, int slot, StreamCallback callback);

    static native void nativeAbort();

    static native void nativeResetContext();

    static native void nativeFreeModel();

    static native String nativeStatus();

    /** Timing of the last generation, e.g. "read 180 tok (150 cached) in 2.1 s, wrote 30 tok in 5.4 s". */
    static native String nativeLastStats();

    /** Changes CPU threads used for prompt reading and generation, without reloading. */
    static native void nativeSetThreads(int threads);
}
