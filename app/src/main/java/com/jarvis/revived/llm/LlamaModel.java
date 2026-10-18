package com.jarvis.revived.llm;

import android.content.Context;
import android.util.Log;

import com.jarvis.core.llm.ChatModel;
import com.jarvis.core.llm.ChatPrompt;
import com.jarvis.core.llm.ContextPlanner;

import java.io.File;
import java.util.List;
import java.util.Locale;

/**
 * {@link ChatModel} backed by llama.cpp. One model, loaded once, on its own
 * thread; generation calls are serialized by the native mutex.
 *
 * Prompts arrive as model-neutral messages and are formatted with the loaded
 * model's own chat template, so Phi-3, Qwen 2.5, Gemma and Llama 3.2 GGUFs all
 * work. The context window is sized from the model's shape (see
 * {@link ContextPlanner}): models with grouped-query attention get a bigger
 * window and two cache slots (chat + tools).
 */
public final class LlamaModel implements ChatModel {

    private static final String TAG = "LlamaModel";

    /** Default CPU threads. Galaxy A15 = 2 fast + 6 slow cores; tune with {@link #setThreads}. */
    public static final int DEFAULT_THREADS = 4;

    public interface Listener {
        void onStateChanged(State state, String detail);
    }

    private final Context appContext;
    private volatile State state = State.NO_MODEL;
    private volatile String detail = "";
    private volatile Listener listener;
    private volatile int threads = DEFAULT_THREADS;
    /** Decoded once after loading so each slot's fixed prefix is already in the KV cache. */
    private volatile ChatPrompt chatWarmup;
    private volatile ChatPrompt toolsWarmup;
    private volatile ContextPlanner.Plan plan;
    private volatile String description = "";
    private volatile boolean hasTemplate = false;

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

    /** Prefixes to keep warm: the chat system prompt and the command translator's prompt. */
    public void setWarmupPrompts(ChatPrompt chat, ChatPrompt tools) {
        chatWarmup = chat;
        toolsWarmup = tools;
    }

    public int threads() {
        return threads;
    }

    /** Applies immediately if a model is loaded; otherwise used at the next load. */
    public void setThreads(int n) {
        threads = Math.max(1, Math.min(8, n));
        if (LlamaBridge.LIBRARY_LOADED && state == State.READY) {
            try {
                LlamaBridge.nativeSetThreads(threads);
            } catch (Throwable e) {
                Log.e(TAG, "setThreads failed", e);
            }
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
        Thread t = new Thread(() -> load(file), "jarvis-model-load");
        t.setPriority(Thread.NORM_PRIORITY - 1);
        t.start();
    }

    private void load(File file) {
        long start = System.currentTimeMillis();
        try {
            if (!LlamaBridge.nativeLoadModel(file.getAbsolutePath(), threads)) {
                setState(State.FAILED, "Model failed to load. Try a smaller GGUF (Q4_K_M).");
                return;
            }
            int[] info = LlamaBridge.nativeModelInfo();
            description = LlamaBridge.nativeModelDesc();
            hasTemplate = info != null && info.length > 5 && info[5] == 1;
            ContextPlanner.Plan p = info == null || info.length < 5
                    ? ContextPlanner.plan(0, 0, 0, 0, 0, ContextPlanner.DEFAULT_BUDGET_BYTES)
                    : ContextPlanner.plan(info[0], info[1], info[2], info[3], info[4],
                    ContextPlanner.DEFAULT_BUDGET_BYTES);
            if (!LlamaBridge.nativeCreateContext(p.tokensPerSlot, p.slots)) {
                // Not enough memory for the planned window: one small slot.
                p = ContextPlanner.plan(0, 0, 0, 0, 0, ContextPlanner.DEFAULT_BUDGET_BYTES);
                if (!LlamaBridge.nativeCreateContext(p.tokensPerSlot, p.slots)) {
                    setState(State.FAILED, "Not enough memory for this model. Try a smaller GGUF.");
                    return;
                }
            }
            plan = p;
            Log.d(TAG, "Model " + description + " | context " + p + " | KV ~" + p.kvMegabytes() + " MB");

            long secs = (System.currentTimeMillis() - start) / 1000;
            setState(State.LOADING, "Loaded in " + secs + " s, warming up…");
            warm(chatWarmup, LlamaBridge.SLOT_CHAT);
            if (p.slots > 1) warm(toolsWarmup, LlamaBridge.SLOT_TOOLS);

            secs = (System.currentTimeMillis() - start) / 1000;
            setState(State.READY, String.format(Locale.US, "Model ready (%d s) · %s · %s%s · %d threads",
                    secs, shortName(description), p, hasTemplate ? "" : " · Phi-3 format", threads));
        } catch (Throwable e) {
            Log.e(TAG, "Model load crashed", e);
            setState(State.FAILED, "Model failed to load. Try a smaller GGUF (Q4_K_M).");
        }
    }

    private void warm(ChatPrompt prefix, int slot) {
        if (prefix == null) return;
        String text = format(prefix);
        if (text.isEmpty()) return;
        try {
            LlamaBridge.nativeGenerate(text, 0, LlamaBridge.MODE_FREE, 0, null, slot, null);
        } catch (Throwable e) {
            Log.e(TAG, "Warm-up failed", e);
        }
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
        plan = null;
        setState(State.NO_MODEL, "Model unloaded");
    }

    @Override
    public String generate(ChatPrompt prompt, int maxTokens, int maxSentences, PartialListener partials) {
        if (state != State.READY) return "";
        try {
            LlamaBridge.StreamCallback cb = partials == null ? null : partials::onPartial;
            String out = LlamaBridge.nativeGenerate(format(prompt), maxTokens, LlamaBridge.MODE_CHAT, maxSentences,
                    null, LlamaBridge.SLOT_CHAT, cb);
            return out == null ? "" : out;
        } catch (Throwable e) {
            Log.e(TAG, "Generation failed", e);
            return "";
        }
    }

    @Override
    public String generateJson(ChatPrompt prompt, String grammar, int maxTokens) {
        if (state != State.READY) return "";
        try {
            String out = LlamaBridge.nativeGenerate(format(prompt), maxTokens, LlamaBridge.MODE_JSON, 0, grammar,
                    LlamaBridge.SLOT_TOOLS, null);
            return out == null ? "" : out;
        } catch (Throwable e) {
            Log.e(TAG, "JSON generation failed", e);
            return "";
        }
    }

    /** The model's own chat format when it has one; Phi-3 format otherwise. */
    String format(ChatPrompt prompt) {
        List<ChatPrompt.Message> msgs = prompt.messages();
        if (hasTemplate && !msgs.isEmpty()) {
            String[] roles = new String[msgs.size()];
            String[] contents = new String[msgs.size()];
            for (int i = 0; i < msgs.size(); i++) {
                ChatPrompt.Message m = msgs.get(i);
                roles[i] = m.role == ChatPrompt.Role.SYSTEM ? "system"
                        : m.role == ChatPrompt.Role.USER ? "user" : "assistant";
                contents[i] = m.content;
            }
            try {
                String formatted = LlamaBridge.nativeFormatChat(roles, contents, prompt.addAssistantStart());
                if (formatted != null) return formatted;
            } catch (Throwable e) {
                Log.e(TAG, "Chat template failed", e);
            }
        }
        return prompt.toPhi3();
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

    @Override
    public String lastStats() {
        if (!LlamaBridge.LIBRARY_LOADED || state != State.READY) return "";
        try {
            return LlamaBridge.nativeLastStats() + " · " + threads + " threads";
        } catch (Throwable e) {
            return "";
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

    /** "qwen2 1.5B Q4_K - Medium" → "qwen2 1.5B". */
    static String shortName(String desc) {
        if (desc == null || desc.trim().isEmpty()) return "model";
        String[] parts = desc.trim().split("\\s+");
        return parts.length >= 2 ? parts[0] + " " + parts[1] : parts[0];
    }

    private void setState(State s, String d) {
        state = s;
        detail = d;
        Log.d(TAG, s + " | " + d);
        Listener l = listener;
        if (l != null) l.onStateChanged(s, d);
    }
}
