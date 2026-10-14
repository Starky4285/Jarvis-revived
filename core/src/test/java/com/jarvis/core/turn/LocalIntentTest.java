package com.jarvis.core.turn;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class LocalIntentTest {

    @Test
    public void alarms() {
        LocalIntent a = LocalIntent.parse("set an alarm for 6:30 am");
        assertEquals(LocalIntent.Type.ALARM, a.type);
        assertEquals(6, a.hour);
        assertEquals(30, a.minute);
        assertEquals(19, LocalIntent.parse("wake me up at 7 pm").hour);
        assertEquals(0, LocalIntent.parse("alarm at 12 am").hour);
        assertEquals(21, LocalIntent.parse("set alarm for 9 tonight").hour);
        assertEquals(LocalIntent.Type.NONE, LocalIntent.parse("set an alarm for 25:00").type);
    }

    @Test
    public void timers() {
        assertEquals(600, LocalIntent.parse("set a timer for 10 minutes").seconds);
        assertEquals(5400, LocalIntent.parse("timer 1 hour 30 minutes").seconds);
        assertEquals(1800, LocalIntent.parse("set a timer for half an hour").seconds);
        assertEquals(90, LocalIntent.parse("set timer for 1 minute 30 seconds").seconds);
    }

    @Test
    public void callsAndApps() {
        assertEquals("Mom", LocalIntent.parse("Call Mom").target);
        assertEquals("98480 12345", LocalIntent.parse("dial 98480 12345").target);
        assertEquals(LocalIntent.Type.NONE, LocalIntent.parse("call me Stark").type);
        assertEquals("whatsapp", LocalIntent.parse("please open whatsapp").target);
        assertEquals("google maps", LocalIntent.parse("launch the google maps app").target);
        assertEquals(LocalIntent.Type.NONE, LocalIntent.parse("open a new chapter in my life and stop worrying").type);
    }

    @Test
    public void questionsAreNotCommands() {
        assertEquals(LocalIntent.Type.NONE, LocalIntent.parse("why do people call it the big apple").type);
        assertEquals(LocalIntent.Type.NONE, LocalIntent.parse("what is a phone call").type);
        assertEquals(LocalIntent.Type.TIME, LocalIntent.parse("what's the time").type);
        assertEquals(LocalIntent.Type.BATTERY, LocalIntent.parse("how much battery do i have").type);
    }
}
