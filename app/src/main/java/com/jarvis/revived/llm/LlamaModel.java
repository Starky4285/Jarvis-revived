package com.jarvis.revived.llm;

import android.content.Context;
import android.util.Log;

import com.jarvis.core.llm.ChatModel;

import java.io.File;

/**
 * {@link ChatModel} backed by llama.cpp. One model, loaded once, on its own
 * thread; generation calls are serialized by the native mutex.
 */
public final class LlamaModel implements ChatModel {

    private static final String TAG = "LlamaModel";

    /** Phi-3-mini KV cache at 1024 tokens is ~400 MB; safe on a 4-6 GB phone. */
    private static final int CONTEXT_TOKENS = 1024;
    /** Galaxy A15 = 2 big + 6 little cores; 4 threads beat 8 in practice. */
    private static final int THREADS = 4;

    public interface Listener {
        void onStateChanged(State state, String detail);
    }

    private final Context appContext;
    private volatile State state = State.NO_MODEL;
    private volatile String detail = "";
    private volatile Listener listener;

    public LlamaModel(Context context) {
        this.appContext = context.getApplicationContext();
        if (!LlamaBridge.LIBRARY_LOADED) {
            setState(State.FAILED, "Native library missing");
        } else if (ModelFiles.hasModel(appContext)) {
            setState(State.NO_MODEL, "Model found, not loaded yet");
        } else {
            setState(State.NO_MODEL, "No model imported");
        }
    }

    public void setListener(Listener l) {
        listener = l;
        if (l != null) l.onStateChanged(state, detail);
    }

    @Override
    public State state() {
        return state;
    }

    public String detail() {
        return detail;
    }

    /** Loads the imported model in the background if it isn't loaded or loading. */
    public synchronized void loadAsync() {
        if (!LlamaBridge.LIBRARY_LOADED) return;
        if (state == State.LOADING || state == State.READY) return;
        File file = ModelFiles.modelFile(appContext);
        if (!ModelFiles.hasModel(appContext)) {
            setState(State.NO_MODEL, "No model imported");
            return;
        }
        setState(State.LOADING, "Loading " + (file.length() / (1024 * 1024)) + " MB model…");
        Thread t = new Thread(() -> {
            long start = System.currentTimeMillis();
            boolean ok;
            try {
                ok = LlamaBridge.nativeLoadModel(file.getAbsolutePath(), CONTEXT_TOKENS, THREADS);
            } catch (Throwable e) {
                Log.e(TAG, "Model load crashed", e);
                ok = false;
            }
            long secs = (System.currentTimeMillis() - start) / 1000;
            if (ok) {
                setState(State.READY, "Model ready (" + secs + " s to load)");
            } else {
                setState(State.FAILED, "Model failed to load. Try a smaller GGUF (Q4_K_M).");
            }
        }, "jarvis-model-load");
        t.setPriority(Thread.NORM_PRIORITY - 1);
        t.start();
    }

    /** Frees the current model (before importing a new one). Blocks until any generation ends. */
    public synchronized void unload() {
        if (!LlamaBridge.LIBRARY_LOADED) return;
        try {
            LlamaBridge.nativeAbort();
            LlamaBridge.nativeFreeModel();
        } catch (Throwable e) {
            Log.e(TAG, "Unload failed", e);
        }
        setState(State.NO_MODEL, "Model unloaded");
    }

    @Override
    public String generate(String prompt, int maxTokens, int maxSentences, PartialListener partials) {
        if (state != State.READY) return "";
        try {
            LlamaBridge.StreamCallback cb = partials == null ? null : partials::onPartial;
            String out = LlamaBridge.nativeGenerate(prompt, maxTokens, LlamaBridge.MODE_CHAT, maxSentences, cb);
            return out == null ? "" : out;
        } catch (Throwable e) {
            Log.e(TAG, "Generation failed", e);
            return "";
        }
    }

    @Override
    public String generateJson(String prompt, int maxTokens) {
        if (state != State.READY) return "";
        try {
            String out = LlamaBridge.nativeGenerate(prompt, maxTokens, LlamaBridge.MODE_JSON, 0, null);
            return out == null ? "" : out;
        } catch (Throwable e) {
            Log.e(TAG, "JSON generation failed", e);
            return "";
        }
    }

    @Override
    public void abort() {
        if (!LlamaBridge.LIBRARY_LOADED) return;
        try {
            LlamaBridge.nativeAbort();
        } catch (Throwable ignored) {
            // nothing to abort
        }
    }

    public String nativeStatus() {
        if (!LlamaBridge.LIBRARY_LOADED) return "native library missing";
        try {
            return LlamaBridge.nativeStatus();
        } catch (Throwable e) {
            return "status unavailable";
        }
    }

    private void setState(State s, String d) {
        state = s;
        detail = d;
        Log.d(TAG, s + " | " + d);
        Listener l = listener;
        if (l != null) l.onStateChanged(s, d);
    }
}
