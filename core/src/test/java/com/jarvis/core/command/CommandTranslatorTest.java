package com.jarvis.core.command;

import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public final class CommandTranslatorTest {

    // ------------------------------------------------------------- the gate

    @Test
    public void requestsPassTheGateAndQuestionsDoNot() {
        String[] commands = {
                "show me how to reach the bus stand on foot",
                "can you find my oldest photo",
                "i want to go to dominos",
                "please book me something to the airport",
                "camera, three photos",
                "jarvis ring my brother"
        };
        for (String s : commands) assertTrue(s, CommandTranslator.looksLikeCommand(s));

        String[] chat = {
                "what is the capital of france",
                "how are you today",
                "who won the match yesterday",
                "i had a long day",
                "tell me about the history of trains in india and why they were built by the british in the 1850s"
        };
        for (String s : chat) assertFalse(s, CommandTranslator.looksLikeCommand(s));
    }

    // ----------------------------------------------------------- validation

    @Test
    public void validOutputBecomesACommand() {
        Command c = CommandTranslator.parse(
                "{\"cmd\":\"maps.directions\",\"args\":{\"place\":\"the bus stand\",\"mode\":\"walking\",\"navigate\":\"no\"}}",
                "show me how to reach the bus stand on foot");
        assertNotNull(c);
        assertEquals("maps.directions", c.name());
        assertEquals("the bus stand", c.text("place"));
        assertEquals("walking", c.text("mode"));
        assertEquals("model", c.source);
    }

    @Test
    public void noneMalformedAndUnknownAreRejected() {
        String u = "do something";
        assertNull(CommandTranslator.parse("{\"cmd\":\"none\"}", u));
        assertNull(CommandTranslator.parse("", u));
        assertNull(CommandTranslator.parse("Sure! I will call him.", u));
        assertNull(CommandTranslator.parse("{\"cmd\":\"phone.call\",\"args\":{\"who\":", u));
        assertNull(CommandTranslator.parse("{\"cmd\":\"system.reboot\",\"args\":{}}", u));
        assertNull(CommandTranslator.parse("{\"cmd\":42}", u));
    }

    @Test
    public void inventedTextIsRejected() {
        // The model may not call someone the user never mentioned.
        assertNull(CommandTranslator.parse("{\"cmd\":\"phone.call\",\"args\":{\"who\":\"mom\"}}",
                "take a picture of me"));
        assertNotNull(CommandTranslator.parse("{\"cmd\":\"phone.call\",\"args\":{\"who\":\"my brother\"}}",
                "jarvis ring my brother"));
    }

    @Test
    public void numbersAndChoicesAreRangeChecked() {
        String u = "take some photos after a while";
        assertNull(CommandTranslator.parse("{\"cmd\":\"camera.photo\",\"args\":{\"delay_seconds\":5,\"count\":500}}", u));
        Command ok = CommandTranslator.parse(
                "{\"cmd\":\"camera.photo\",\"args\":{\"delay_seconds\":5,\"count\":3}}", u);
        assertNotNull(ok);
        assertEquals(3, ok.number("count", -1));
        // An optional choice with a value outside the list is dropped, not fatal.
        Command c = CommandTranslator.parse(
                "{\"cmd\":\"maps.directions\",\"args\":{\"place\":\"the zoo\",\"mode\":\"teleport\",\"navigate\":null}}",
                "get me to the zoo");
        assertNotNull(c);
        assertFalse(c.has("mode"));
        // A missing required argument is fatal.
        assertNull(CommandTranslator.parse("{\"cmd\":\"maps.directions\",\"args\":{\"place\":null}}", "get me there"));
    }

    @Test
    public void templateTokensInValuesAreStripped() {
        Command c = CommandTranslator.parse(
                "{\"cmd\":\"app.open\",\"args\":{\"app\":\"whatsapp <|im_end|>\"}}", "open whatsapp now");
        assertNotNull(c);
        assertEquals("whatsapp", c.text("app"));
    }

    // -------------------------------------------------------------- prompt

    @Test
    public void promptListsEveryCommandAndEndsWithTheUser() {
        String p = CommandTranslator.prompt("show me the way home").toPhi3();
        for (CommandSpec s : CommandCatalog.all()) assertTrue(s.name, p.contains(s.name + "("));
        assertTrue(p.contains("none"));
        assertTrue(p, p.endsWith("<|user|>\nshow me the way home<|end|>\n<|assistant|>\n"));
        String warm = CommandTranslator.warmup().toPhi3();
        assertTrue("the warm-up must be a cache prefix of every prompt", p.startsWith(warm));
    }

    // -------------------------------------------------------------- grammar

    @Test
    public void grammarHasOneRulePerCommandAndNone() {
        String g = CommandTranslator.grammar();
        assertTrue(g.startsWith("root ::= "));
        for (CommandSpec s : CommandCatalog.all()) {
            assertTrue(s.name, g.contains(CommandGrammar.ruleName(s.name) + " ::= "));
            assertTrue(s.name, g.contains("\"\\\"" + s.name + "\\\"\""));
        }
        assertTrue(g.contains("c-none ::= "));
        assertTrue(g.contains("text ::= "));
        // Optional arguments may be null; required ones may not.
        assertTrue(g, g.contains("\"\\\"who\\\":\" ws text ws"));
        assertTrue(g, g.contains("\"\\\"mode\\\":\" ws ((\"\\\"driving\\\"\""));
        for (String line : g.split("\n")) {
            assertTrue("rule names must be GBNF-safe: " + line, line.matches("^[a-z][a-z0-9-]* ::= .+"));
        }
    }

    // ---------------------------------------------------------------- json

    @Test
    public void miniJsonReadsWhatTheGrammarProduces() {
        Map<String, Object> o = MiniJson.parseObject(
                "noise {\"cmd\":\"x\",\"args\":{\"a\":\"q\\\"u\\\\ote\\u00e9\",\"n\":12,\"d\":-1.5,\"t\":true,\"z\":null}} tail");
        assertEquals("x", o.get("cmd"));
        @SuppressWarnings("unchecked")
        Map<String, Object> args = (Map<String, Object>) o.get("args");
        assertEquals("q\"u\\oteé", args.get("a"));
        assertEquals(12L, args.get("n"));
        assertEquals(-1.5, (Double) args.get("d"), 1e-9);
        assertEquals(Boolean.TRUE, args.get("t"));
        assertTrue(args.containsKey("z"));
        assertNull(args.get("z"));
    }

    @Test
    public void miniJsonRejectsBrokenInput() {
        String[] bad = {"{", "{\"a\"}", "{\"a\":}", "{\"a\":[1]}", "{\"a\":\"x\\q\"}", "no json", "{\"a\":1,}"};
        for (String s : bad) {
            try {
                MiniJson.parseObject(s);
                fail("accepted: " + s);
            } catch (IllegalArgumentException expected) {
                // good
            }
        }
    }
}
