package com.jarvis.core.turn;

import com.jarvis.core.llm.ChatModel;
import com.jarvis.core.memory.MemoryEngine;
import com.jarvis.core.memory.TestStores;

import org.junit.Before;
import org.junit.Test;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class TurnRouterTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    static final class FakeActions implements DeviceActions {
        final List<String> log = new ArrayList<>();
        @Override public String call(String who) { log.add("call:" + who); return "Calling " + who + "."; }
        @Override public String openApp(String app) { log.add("open:" + app); return "Opening " + app + "."; }
        @Override public String setAlarm(int h, int m) { log.add("alarm:" + h + ":" + m); return "Alarm set."; }
        @Override public String setTimer(int s) { log.add("timer:" + s); return "Timer set."; }
        @Override public String batteryStatus() { log.add("battery"); return "Battery is at 80 percent."; }
    }

    static final class FakeModel implements ChatModel {
        State state = State.READY;
        final List<String> prompts = new ArrayList<>();
        final List<String> outputs = new ArrayList<>();
        boolean aborted;
        boolean streamChunks;
        @Override public State state() { return state; }
        @Override public String generate(String prompt, int maxTokens, int maxSentences, PartialListener l) {
            prompts.add(prompt);
            String out = outputs.isEmpty() ? "" : outputs.remove(0);
            if (streamChunks && l != null && !out.isEmpty()) l.onPartial(out);
            return out;
        }
        @Override public String generateJson(String prompt, int maxTokens) { return ""; }
        @Override public void abort() { aborted = true; }
    }

    private FakeActions actions;
    private FakeModel model;
    private TurnRouter router;
    private long now;

    @Before
    public void setUp() {
        actions = new FakeActions();
        model = new FakeModel();
        now = ZonedDateTime.of(2026, 10, 14, 21, 41, 0, 0, IST).toInstant().toEpochMilli();
        MemoryEngine memory = new MemoryEngine(TestStores.inMemory(), () -> now, IST);
        router = new TurnRouter(memory, actions, model, () -> now, IST, "Jarvis");
    }

    private TurnRouter.Result say(String text) {
        now += 1000;
        return router.handle(text, null);
    }

    @Test
    public void stopAbortsTheModelAndCancelsPendingQuestions() {
        say("my name is stark");
        say("forget everything");
        TurnRouter.Result r = say("cancel");
        assertEquals(TurnRouter.Route.STOP, r.route);
        assertTrue(model.aborted);
        say("yes");
        assertTrue(say("what is my name").reply.contains("Stark"));
    }

    @Test
    public void timeAndDateAreAnsweredLocally() {
        assertEquals("It's 9:41 PM.", say("what time is it").reply);
        assertEquals("Today is Wednesday, 14 October 2026.", say("what's the date today").reply);
        assertTrue(model.prompts.isEmpty());
    }

    @Test
    public void deviceCommandsGoToActions() {
        say("call ravi");
        say("jarvis open youtube");
        say("set an alarm for 6:30 am");
        say("wake me up at 7 pm");
        say("set a timer for 1 hour 30 minutes");
        say("what's my battery level");
        assertEquals(java.util.Arrays.asList("call:ravi", "open:youtube", "alarm:6:30", "alarm:19:0",
                "timer:5400", "battery"), actions.log);
        assertTrue(model.prompts.isEmpty());
    }

    @Test
    public void callMeIsNamingNotCalling() {
        TurnRouter.Result r = say("call me Stark");
        assertEquals(TurnRouter.Route.MEMORY, r.route);
        assertTrue(actions.log.isEmpty());
    }

    @Test
    public void memoryAnswersBeforeTheModel() {
        say("my name is stark");
        TurnRouter.Result r = say("what is my name");
        assertEquals(TurnRouter.Route.MEMORY, r.route);
        assertTrue(model.prompts.isEmpty());
    }

    @Test
    public void modelSeesFencedMemoryAndRecentTurns() {
        say("my name is stark");
        model.outputs.add("Hello Stark, how can I help?");
        say("hello there");
        model.outputs.add("Try Bruno, it is a friendly name.");
        TurnRouter.Result r = say("suggest a name for my dog");
        assertEquals(TurnRouter.Route.MODEL, r.route);
        String prompt = model.prompts.get(model.prompts.size() - 1);
        assertTrue(prompt, prompt.contains("<memory>"));
        assertTrue(prompt, prompt.contains("Stark") || prompt.contains("stark"));
        assertTrue(prompt, prompt.contains("hello there"));
        assertTrue(prompt, prompt.endsWith("<|assistant|>\n"));
    }

    @Test
    public void promptStartsWithTheSameCacheablePrefixEveryTime() {
        String prefix = com.jarvis.core.llm.PromptBuilder.staticPrefix("Jarvis");
        model.outputs.add("Hello there, nice to meet you.");
        say("hello");
        say("my name is arjun");           // memory turn: must not enter the model window
        now += 3_600_000L;                  // an hour later: time in the prompt changes
        model.outputs.add("It is a fine evening.");
        say("how is the evening");
        assertEquals(2, model.prompts.size());
        for (String p : model.prompts) {
            assertTrue(p.startsWith(prefix));
        }
        String second = model.prompts.get(1);
        assertTrue(second, second.contains("hello"));
        assertFalse("memory turns must not be replayed as chat history", second.contains("Got it."));
        assertTrue(second, second.contains("Arjun") || second.contains("arjun"));
    }

    @Test
    public void whoAmINeverReachesTheModel() {
        say("my name is arjun");
        TurnRouter.Result r = say("who am I");
        assertEquals(TurnRouter.Route.MEMORY, r.route);
        assertEquals("Your name is Arjun.", r.reply);
        assertTrue(model.prompts.isEmpty());
    }

    @Test
    public void modelTextIsNeverExecuted() {
        model.outputs.add("Sure, I am calling Ravi now and opening WhatsApp.");
        say("what do you think about ravi");
        assertTrue(actions.log.isEmpty());
    }

    @Test
    public void badModelOutputIsRetriedThenFallsBack() {
        model.outputs.add("");
        model.outputs.add("Paris is the capital of France.");
        assertEquals("Paris is the capital of France.", say("what is the capital of france").reply);

        model.outputs.add("{\"action\":\"call\"}");
        model.outputs.add("<|end|>");
        TurnRouter.Result r = say("what is the capital of peru");
        assertEquals(TurnRouter.Route.FALLBACK, r.route);
    }

    @Test
    public void streamedAnswersAreFlagged() {
        model.streamChunks = true;
        model.outputs.add("Pi is about 3.14.");
        List<String> spoken = new ArrayList<>();
        now += 1000;
        TurnRouter.Result r = router.handle("what is pi", spoken::add);
        assertTrue(r.streamed);
        assertEquals(java.util.Collections.singletonList("Pi is about 3.14."), spoken);
    }

    @Test
    public void missingModelGivesAHelpfulFallback() {
        model.state = ChatModel.State.NO_MODEL;
        TurnRouter.Result r = say("tell me a joke");
        assertEquals(TurnRouter.Route.FALLBACK, r.route);
        assertTrue(r.reply.contains("Import model"));
    }

    @Test
    public void compoundCommandsRunEachPart() {
        TurnRouter.Result r = say("open camera and call ravi");
        assertEquals(java.util.Arrays.asList("open:camera", "call:ravi"), actions.log);
        assertTrue(r.reply, r.reply.contains("Opening camera") && r.reply.contains("Calling ravi"));
    }

    @Test
    public void ordinaryAndQuestionsGoToTheModelWhole() {
        model.outputs.add("Cats are independent, dogs are more social.");
        say("what is the difference between a cat and a dog");
        assertEquals(1, model.prompts.size());
        assertTrue(model.prompts.get(0).contains("a cat and a dog"));
        assertFalse(actions.log.contains("call:"));
    }
}
