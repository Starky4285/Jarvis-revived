package com.jarvis.core.llm;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class ContextPlannerTest {

    private static final long BUDGET = ContextPlanner.DEFAULT_BUDGET_BYTES;

    @Test
    public void phi3MiniKeepsOneSlotOf1024() {
        // 32 layers, 32 heads, 32 KV heads (no GQA), 3072 wide: ~384 KB per token.
        ContextPlanner.Plan p = ContextPlanner.plan(32, 32, 32, 3072, 4096, BUDGET);
        assertEquals(1, p.slots);
        assertEquals(1024, p.tokensPerSlot);
        assertEquals(393_216L, p.kvBytesPerToken);
    }

    @Test
    public void qwen25SmallGetsTwoFullSlots() {
        // Qwen2.5-1.5B: 28 layers, 12 heads, 2 KV heads, 1536 wide: 28 KB per token.
        ContextPlanner.Plan p = ContextPlanner.plan(28, 12, 2, 1536, 32768, BUDGET);
        assertEquals(2, p.slots);
        assertEquals(4096, p.tokensPerSlot);
        assertTrue(p.kvMegabytes() < 300);
    }

    @Test
    public void llama32ThreeBillionGetsTwoSmallerSlots() {
        // 28 layers, 24 heads, 8 KV heads, 3072 wide: 112 KB per token.
        ContextPlanner.Plan p = ContextPlanner.plan(28, 24, 8, 3072, 131072, BUDGET);
        assertEquals(2, p.slots);
        assertEquals(2048, p.tokensPerSlot);
    }

    @Test
    public void trainingContextCapsTheWindow() {
        ContextPlanner.Plan p = ContextPlanner.plan(16, 8, 2, 1024, 2048, BUDGET);
        assertEquals(2048, p.tokensPerSlot);
    }

    @Test
    public void unknownShapeFallsBackSafely() {
        ContextPlanner.Plan p = ContextPlanner.plan(0, 0, 0, 0, 0, BUDGET);
        assertEquals(1, p.slots);
        assertEquals(1024, p.tokensPerSlot);
    }
}
