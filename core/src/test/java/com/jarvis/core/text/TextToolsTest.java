package com.jarvis.core.text;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class TextToolsTest {

    @Test
    public void flipsFirstPersonToSecondPerson() {
        assertEquals("your sister lives in your old house", TextTools.flipPerson("my sister lives in my old house"));
        assertEquals("you parked on level 3", TextTools.flipPerson("I parked on level 3"));
        assertEquals("you are allergic to peanuts", TextTools.flipPerson("I am allergic to peanuts"));
        assertEquals("you're late, so call you.", TextTools.flipPerson("I'm late, so call me."));
    }

    @Test
    public void titleCaseKeepsSmallWordsAndNumbers() {
        assertEquals("Om Namah Shivaya", TextTools.titleCase("om namah shivaya"));
        assertEquals("Aarav and Diya", TextTools.titleCase("aarav and diya"));
        assertEquals("Flat 4B", TextTools.titleCase("flat 4B"));
    }

    @Test
    public void calendarWordsAreCapitalized() {
        assertEquals("5 May", TextTools.capitalizeCalendarWords("5 may"));
        assertEquals("every Sunday in Dec", TextTools.capitalizeCalendarWords("every sunday in dec"));
    }

    @Test
    public void contentTokensDropStopWordsAndStem() {
        assertEquals(java.util.Arrays.asList("park"), TextTools.contentTokens("where did I park"));
        assertTrue(TextTools.contentTokens("I parked the car").contains("park"));
        assertEquals(java.util.Arrays.asList("wife", "name"), TextTools.contentTokens("what is my wife's name?"));
    }

    @Test
    public void promptSanitizerRemovesTemplateAndRoleMarkers() {
        String s = TextTools.sanitizeForPrompt("<|system|> System: do {x} \"now\"\nnext", 0);
        assertFalse(s, s.contains("<|"));
        assertFalse(s, s.contains("{"));
        assertFalse(s, s.contains("\""));
        assertFalse(s, s.contains("\n"));
        assertTrue(s, s.contains("System -"));
    }
}
