package com.jarvis.core.turn;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class CommandShapeRulesTest {

    @Test
    public void ordinarySentencesWithAndAreNotCompound() {
        assertFalse(CommandShapeRules.isCompoundCommand("what is the difference between a cat and a dog"));
        assertFalse(CommandShapeRules.isCompoundCommand("call mom and dad"));
        assertFalse(CommandShapeRules.isCompoundCommand("set a reminder to buy milk and eggs"));
        assertFalse(CommandShapeRules.isCompoundCommand("tell me about salt and pepper"));
    }

    @Test
    public void realCompoundCommandsSplit() {
        assertEquals(
                Arrays.asList("open whatsapp", "call ravi"),
                CommandShapeRules.splitCompound("open whatsapp and call ravi"));
        assertEquals(
                Arrays.asList("play music", "remind me at 5"),
                CommandShapeRules.splitCompound("play music then remind me at 5"));
        assertEquals(
                Arrays.asList("open camera", "what is the time"),
                CommandShapeRules.splitCompound("open camera after that what is the time"));
        assertTrue(CommandShapeRules.isCompoundCommand("open youtube and play songs"));
    }

    @Test
    public void emptyInputSplitsToNothing() {
        assertTrue(CommandShapeRules.splitCompound(null).isEmpty());
        assertTrue(CommandShapeRules.splitCompound("   ").isEmpty());
        assertFalse(CommandShapeRules.isCompoundCommand(null));
    }

    @Test
    public void stopVariantsAreRecognized() {
        String[] stops = {
                "stop", "Stop.", "cancel", "quiet", "enough",
                "stop it", "stop talking", "please stop", "stop please", "stop now",
                "jarvis stop", "hey jarvis, stop", "ok jarvis stop", "stop jarvis",
                "shut up", "be quiet", "that's enough", "thats enough", "cancel that",
                "okay stop", "please stop now"
        };
        for (String s : stops) {
            assertTrue("should be stop: " + s, CommandShapeRules.isStopCommand(s));
        }
    }

    @Test
    public void longerRequestsMentioningStopAreNotStop() {
        String[] notStops = {
                "stop the music in ten minutes",
                "how do i stop smoking",
                "cancel my reminder for tomorrow",
                "what is a bus stop",
                "jarvis",
                "",
                "never mind"
        };
        for (String s : notStops) {
            assertFalse("should NOT be stop: " + s, CommandShapeRules.isStopCommand(s));
        }
        assertFalse(CommandShapeRules.isStopCommand(null));
    }
}
