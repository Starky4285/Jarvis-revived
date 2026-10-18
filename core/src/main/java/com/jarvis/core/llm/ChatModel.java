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
     * Chat reply (cache slot "chat"). Blocking; never called on the main thread.
     *
     * @param maxSentences stop after this many complete sentences
     */
    String generate(ChatPrompt prompt, int maxTokens, int maxSentences, PartialListener listener);

    /**
     * Structured output (cache slot "tools"): the command translator and the
     * memory extractor. When {@code grammar} (GBNF) is given, the model can only
     * produce text that matches it. Stops when the JSON object closes.
     */
    String generateJson(ChatPrompt prompt, String grammar, int maxTokens);

    /** Stops the current generation as soon as possible. Safe from any thread. */
    void abort();

    /** Human-readable timing of the last generation (for tuning on the phone). "" if unknown. */
    default String lastStats() {
        return "";
    }
}
