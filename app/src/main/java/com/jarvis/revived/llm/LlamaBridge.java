package com.jarvis.revived.llm;

import android.util.Log;

/** JNI surface of jarvis_llm.cpp. Use {@link LlamaModel}; don't call this directly. */
final class LlamaBridge {

    private static final String TAG = "LlamaBridge";

    /** Must match kMode* in jarvis_llm.cpp. */
    static final int MODE_CHAT = 0;
    static final int MODE_JSON = 1;
    static final int MODE_FREE = 2;

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

    static native boolean nativeLoadModel(String path, int contextSize, int threads);

    static native String nativeGenerate(String prompt, int maxNewTokens, int mode, int maxSentences,
                                        StreamCallback callback);

    static native void nativeAbort();

    static native void nativeResetContext();

    static native void nativeFreeModel();

    static native String nativeStatus();
}
