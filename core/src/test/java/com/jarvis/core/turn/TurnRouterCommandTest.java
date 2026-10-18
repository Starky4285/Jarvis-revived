package com.jarvis.core.turn;

import com.jarvis.core.memory.MemoryEngine;
import com.jarvis.core.memory.TestStores;

import org.junit.Before;
import org.junit.Test;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Phone tasks end to end: rules, saved places, confirmations, planned tasks, model-as-translator. */
public final class TurnRouterCommandTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private TurnRouterTest.FakeActions actions;
    private TurnRouterTest.FakeModel model;
    private TurnRouter router;
    private long now;

    @Before
    public void setUp() {
        actions = new TurnRouterTest.FakeActions();
        model = new TurnRouterTest.FakeModel();
        now = ZonedDateTime.of(2026, 10, 18, 7, 30, 0, 0, IST).toInstant().toEpochMilli();
        MemoryEngine memory = new MemoryEngine(TestStores.inMemory(), () -> now, IST);
        router = new TurnRouter(memory, actions, model, () -> now, IST, "Jarvis");
    }

    private TurnRouter.Result say(String text) {
        now += 1000;
        return router.handle(text, null);
    }

    // -------------------------------------------------------------- maps

    @Test
    public void directionsOpenMapsWithoutTheModel() {
        TurnRouter.Result r = say("open maps and find direction to dominos from my location");
        assertEquals(TurnRouter.Route.ACTION, r.route);
        assertEquals(Collections.singletonList("directions:dominos|dominos||false"), actions.log);
        assertTrue(model.prompts.isEmpty());
        assertTrue(model.jsonPrompts.isEmpty());
        // The app logs r.command: it names the command, never what the user said.
        assertEquals("maps.directions [rules]", r.command);
    }

    @Test
    public void savedPlacesAreUsedForDirections() {
        say("remember shiva temple is ramalingeswara swamy temple kukatpally");
        TurnRouter.Result r = say("take me to the shiva temple");
        assertEquals(TurnRouter.Route.ACTION, r.route);
        assertEquals(Collections.singletonList(
                "directions:ramalingeswara swamy temple kukatpally|shiva temple||true"), actions.log);
    }

    @Test
    public void ambiguousSavedPlacesAreNotGuessed() {
        say("remember shiva temple is ramalingeswara temple kukatpally");
        say("remember hanuman temple is karmanghat");
        say("directions to the temple");
        assertEquals(Collections.singletonList("directions:the temple|the temple||false"), actions.log);
    }

    // ------------------------------------------------------- confirmations

    @Test
    public void callsWaitForYes() {
        TurnRouter.Result q = say("call ravi");
        assertEquals(TurnRouter.Route.CONFIRM, q.route);
        assertTrue(q.awaitingConfirmation);
        assertEquals("Call Ravi? Say yes or no.", q.reply);
        assertTrue(actions.log.isEmpty());

        TurnRouter.Result yes = say("yes");
        assertEquals(TurnRouter.Route.ACTION, yes.route);
        assertEquals(Collections.singletonList("call:ravi"), actions.log);
    }

    @Test
    public void noCancelsTheCall() {
        say("call ravi");
        TurnRouter.Result r = say("no");
        assertEquals("Okay, I won't call Ravi.", r.reply);
        assertTrue(actions.log.isEmpty());
        say("yes"); // a late yes does nothing
        assertTrue(actions.log.isEmpty());
    }

    @Test
    public void cancelIsAlsoANoWhileAQuestionIsOpen() {
        say("call ravi");
        assertEquals("Okay, I won't call Ravi.", say("cancel").reply);
        assertTrue(actions.log.isEmpty());
    }

    @Test
    public void staleYesIsIgnored() {
        say("call ravi");
        now += TurnRouter.CONFIRM_WINDOW_MS + 5_000L;
        say("yes");
        assertTrue(actions.log.isEmpty());
    }

    @Test
    public void somethingElseDropsTheQuestion() {
        say("call ravi");
        say("what time is it");
        assertFalse(router.hasPendingConfirmation());
        say("yes");
        assertTrue(actions.log.isEmpty());
    }

    @Test
    public void unknownContactIsSaidNotGuessed() {
        TurnRouter.Result r = say("call nobody");
        assertTrue(r.reply, r.reply.contains("couldn't find"));
        assertFalse(r.awaitingConfirmation);
    }

    // ------------------------------------------------------------ planned

    @Test
    public void plannedTasksAreAnsweredHonestly() {
        String[] asks = {
                "open camera in 10s and take 3 continuous shots",
                "book rapido bike to shiva temple that i always visit",
                "open where is my train and find trains to vijayawada",
                "find my oldest pic available on gallery with my sister",
                "search voice recordings over 5mins for me",
                "open excel and view recently opened sheet1 on balances data file",
                "analyse my storage",
                "summarize my emails"
        };
        for (String a : asks) {
            TurnRouter.Result r = say(a);
            assertEquals(a, TurnRouter.Route.PLANNED, r.route);
            assertFalse(a, r.reply.isEmpty());
        }
        assertTrue(actions.log.isEmpty());
        assertTrue("planned tasks must not reach the model", model.prompts.isEmpty());
        assertTrue(model.jsonPrompts.isEmpty());
    }

    // ---------------------------------------------------- model translator

    @Test
    public void unusualPhrasingIsTranslatedUnderTheGrammar() {
        model.jsonOutputs.add("{\"cmd\":\"maps.directions\",\"args\":{\"place\":\"the bus stand\",\"mode\":\"walking\","
                + "\"navigate\":\"no\"}}");
        TurnRouter.Result r = say("show me how to reach the bus stand on foot");
        assertEquals(TurnRouter.Route.ACTION, r.route);
        assertEquals(Collections.singletonList("directions:the bus stand|the bus stand|walking|false"), actions.log);
        assertEquals(1, model.jsonPrompts.size());
        assertTrue(model.grammars.get(0).startsWith("root ::= "));
        assertTrue(model.prompts.isEmpty());
        assertTrue(r.command.contains("[model]"));
    }

    @Test
    public void translatedCallsStillNeedYes() {
        model.jsonOutputs.add("{\"cmd\":\"phone.call\",\"args\":{\"who\":\"my brother\"}}");
        TurnRouter.Result r = say("jarvis give my brother a ring");
        assertEquals(TurnRouter.Route.CONFIRM, r.route);
        assertTrue(actions.log.isEmpty());
        say("yes");
        assertEquals(Collections.singletonList("call:my brother"), actions.log);
    }

    @Test
    public void inventedTargetsFallBackToChat() {
        model.jsonOutputs.add("{\"cmd\":\"phone.call\",\"args\":{\"who\":\"mom\"}}");
        model.outputs.add("I can't take pictures of you yet.");
        TurnRouter.Result r = say("take a picture of me");
        assertEquals(TurnRouter.Route.MODEL, r.route);
        assertTrue(actions.log.isEmpty());
    }

    @Test
    public void noneFallsBackToChat() {
        model.jsonOutputs.add("{\"cmd\":\"none\"}");
        model.outputs.add("Happy to help with that.");
        TurnRouter.Result r = say("find me a good name for a cat");
        assertEquals(TurnRouter.Route.MODEL, r.route);
        assertEquals(1, model.jsonPrompts.size());
    }

    @Test
    public void ordinaryQuestionsSkipTheTranslator() {
        model.outputs.add("Paris is the capital of France.");
        say("what is the capital of france");
        assertTrue(model.jsonPrompts.isEmpty());
        assertEquals(1, model.prompts.size());
    }

    @Test
    public void translatorIsSkippedWhileTheModelIsLoading() {
        model.state = com.jarvis.core.llm.ChatModel.State.LOADING;
        TurnRouter.Result r = say("show me how to reach the bus stand on foot");
        assertEquals(TurnRouter.Route.FALLBACK, r.route);
        assertTrue(model.jsonPrompts.isEmpty());
    }

    @Test
    public void compoundRequestsMixActionsAndQuestions() {
        TurnRouter.Result r = say("open youtube and call ravi");
        assertEquals(Arrays.asList("open:youtube"), actions.log);
        assertTrue(r.awaitingConfirmation);
        say("yes");
        assertEquals(Arrays.asList("open:youtube", "call:ravi"), actions.log);
    }
}
