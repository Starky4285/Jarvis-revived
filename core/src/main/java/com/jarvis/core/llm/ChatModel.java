package com.jarvis.core.llm;

/** The on-device language model, as seen by the core. Implemented by the app (llama.cpp). */
public interface ChatModel {

    /** Receives speakable sentence chunks while the model is still generating. */
    interface PartialListener {
        void onPartial(String chunk);
    }

    enum State { NO_MODEL, LOADING, READY, FAILED }

    State state();

    /**
     * Runs one prompt and returns the raw text (may be empty). Blocking; never
     * called on the main thread.
     *
     * @param maxSentences stop after this many complete sentences (chat replies)
     */
    String generate(String prompt, int maxTokens, int maxSentences, PartialListener listener);

    /** Model-generated JSON (memory extraction); stops when the object closes. */
    String generateJson(String prompt, int maxTokens);

    /** Stops the current generation as soon as possible. Safe from any thread. */
    void abort();

    /** Human-readable timing of the last generation (for tuning on the phone). "" if unknown. */
    default String lastStats() {
        return "";
    }
}
