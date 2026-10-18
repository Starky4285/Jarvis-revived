package com.jarvis.core.llm;

/**
 * Chooses the context window from the model's shape and a memory budget.
 *
 * The KV cache costs {@code 2 (K,V) × layers × kv_heads × head_dim × 2 bytes}
 * per token. Phi-3-mini has no grouped-query attention (32 KV heads), so it
 * pays ~384 KB per token and only fits ~1k tokens next to its weights on a
 * 4–6 GB phone. Models with grouped-query attention (Qwen 2.5, Llama 3.2,
 * Gemma) pay 30–110 KB per token, so they get a 4k window AND a second cache
 * slot: one keeps the chat system prompt warm, the other keeps the command
 * translator's prompt warm, so switching between them costs nothing.
 */
public final class ContextPlanner {

    /** KV cache budget. Leaves room for the model weights (memory-mapped) and the app. */
    public static final long DEFAULT_BUDGET_BYTES = 448L * 1024 * 1024;
    static final int MAX_TOKENS_PER_SLOT = 4096;
    static final int MIN_TWO_SLOT_TOKENS = 1024;
    static final int MIN_TOKENS = 512;
    static final int FALLBACK_TOKENS = 1024;
    private static final int STEP = 256;

    public static final class Plan {
        /** Tokens each slot can hold (prompt + reply). */
        public final int tokensPerSlot;
        /** 2 = chat and tools each keep their own cached prefix; 1 = they share one. */
        public final int slots;
        public final long kvBytesPerToken;

        Plan(int tokensPerSlot, int slots, long kvBytesPerToken) {
            this.tokensPerSlot = tokensPerSlot;
            this.slots = slots;
            this.kvBytesPerToken = kvBytesPerToken;
        }

        public int totalTokens() {
            return tokensPerSlot * slots;
        }

        public long kvMegabytes() {
            return kvBytesPerToken * totalTokens() / (1024 * 1024);
        }

        @Override
        public String toString() {
            return tokensPerSlot + (slots > 1 ? " × " + slots : "") + " tokens";
        }
    }

    private ContextPlanner() {
    }

    public static Plan plan(int nLayer, int nHead, int nHeadKv, int nEmbd, int nCtxTrain, long budgetBytes) {
        if (nLayer <= 0 || nHead <= 0 || nHeadKv <= 0 || nEmbd <= 0 || budgetBytes <= 0) {
            return new Plan(FALLBACK_TOKENS, 1, 0L);
        }
        long headDim = Math.max(1, nEmbd / nHead);
        long perToken = 2L * nLayer * nHeadKv * headDim * 2L;
        int cap = nCtxTrain > 0 ? Math.min(MAX_TOKENS_PER_SLOT, nCtxTrain) : MAX_TOKENS_PER_SLOT;

        int twoSlot = floorStep(budgetBytes / (2L * perToken));
        if (twoSlot >= MIN_TWO_SLOT_TOKENS) {
            return new Plan(Math.min(cap, twoSlot), 2, perToken);
        }
        int oneSlot = floorStep(budgetBytes / perToken);
        return new Plan(Math.max(MIN_TOKENS, Math.min(cap, oneSlot)), 1, perToken);
    }

    private static int floorStep(long tokens) {
        long t = (tokens / STEP) * STEP;
        return (int) Math.min(Integer.MAX_VALUE, Math.max(0, t));
    }
}
