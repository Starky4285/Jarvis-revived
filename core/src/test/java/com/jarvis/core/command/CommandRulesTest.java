package com.jarvis.core.command;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** The phone tasks from the project brief, phrased the way people actually say them. */
public final class CommandRulesTest {

    private static Command parse(String s) {
        Command c = CommandRules.parse(s);
        assertNotNull("not understood: " + s, c);
        return c;
    }

    @Test
    public void directionsFromMyLocation() {
        Command c = parse("open maps and find direction to dominos from my location");
        assertEquals("maps.directions", c.name());
        assertEquals("dominos", c.text("place"));
        assertEquals("no", c.text("navigate"));
        assertFalse(c.has("mode"));

        assertEquals("the railway station", parse("Show me the way to the railway station").text("place"));
        assertEquals("Hitech City", parse("get directions to Hitech City please").text("place"));
        assertEquals("charminar", parse("how do I get to charminar").text("place"));
        assertEquals("kukatpally", parse("how can i reach kukatpally").text("place"));
    }

    @Test
    public void navigationAndTravelMode() {
        Command c = parse("take me to shiva temple");
        assertEquals("shiva temple", c.text("place"));
        assertEquals("yes", c.text("navigate"));

        c = parse("navigate to the airport by bike");
        assertEquals("the airport", c.text("place"));
        assertEquals("two_wheeler", c.text("mode"));
        assertEquals("walking", parse("directions to the park on foot").text("mode"));
        assertEquals("transit", parse("route to secunderabad by metro").text("mode"));
        assertEquals("hospital near me", parse("where is the nearest hospital").text("place"));
    }

    @Test
    public void cameraTimerAndBurst() {
        Command c = parse("open camera in 10s and take 3 continuous shots");
        assertEquals("camera.photo", c.name());
        assertEquals(10, c.number("delay_seconds", -1));
        assertEquals(3, c.number("count", -1));

        c = parse("take a photo in 5 seconds");
        assertEquals(5, c.number("delay_seconds", -1));
        assertEquals(1, c.number("count", -1));
        assertEquals(3, parse("click three pictures").number("count", -1));
        assertEquals("camera.photo", parse("take a selfie").name());

        c = parse("record a video");
        assertEquals("camera.video", c.name());
        c = parse("open camera and record a 30 second video");
        assertEquals(30, c.number("duration_seconds", -1));
        assertEquals(60, parse("record video for 1 minute in 10 seconds").number("duration_seconds", -1));
        assertEquals(10, parse("record video for 1 minute in 10 seconds").number("delay_seconds", -1));
    }

    @Test
    public void plainOpenCameraStillOpensTheApp() {
        Command c = parse("open camera");
        assertEquals("app.open", c.name());
        assertEquals("camera", c.text("app"));
    }

    @Test
    public void recordingsAndRecorder() {
        Command c = parse("search voice recordings over 5mins for me");
        assertEquals("recordings.find", c.name());
        assertEquals(5, c.number("min_minutes", -1));
        assertEquals("recordings.find", parse("show my recordings").name());
        assertEquals("recorder.start", parse("start recording").name());
        assertEquals("recorder.start", parse("Jarvis, start a voice recording").name());
        assertEquals("recorder.start", parse("record my voice").name());
    }

    @Test
    public void spreadsheetsAndFiles() {
        Command c = parse("open excel and view recently opened sheet1 on balances data file");
        assertEquals("files.open", c.name());
        assertEquals("balances", c.text("name"));
        assertEquals("sheet1", c.text("sheet"));
        assertEquals("excel", c.text("app"));

        c = parse("open the balances file");
        assertEquals("balances", c.text("name"));
        assertEquals("rent receipt", parse("open rent receipt pdf").text("name"));
        assertEquals("storage.analyze", parse("analyse my storage").name());
        assertEquals("storage.analyze", parse("what's taking up space").name());
        assertEquals("storage.analyze", parse("free up space").name());
    }

    @Test
    public void ridesTrainsAndPhotos() {
        Command c = parse("book rapido bike to shiva temple that i always visit");
        assertEquals("ride.book", c.name());
        assertEquals("rapido", c.text("app"));
        assertEquals("bike", c.text("vehicle"));
        assertEquals("shiva temple", c.text("place"));
        assertEquals("cab", parse("book a taxi to the airport").text("vehicle"));
        assertEquals("uber", parse("book an uber to office").text("app"));
        assertNull(CommandRules.parse("book a table for two"));

        c = parse("open where is my train and find trains to vijayawada");
        assertEquals("train.search", c.name());
        assertEquals("vijayawada", c.text("to"));
        c = parse("trains from hyderabad to vizag");
        assertEquals("hyderabad", c.text("from"));
        assertEquals("vizag", c.text("to"));

        c = parse("find my oldest pic available on gallery with my sister");
        assertEquals("photos.find", c.name());
        assertEquals("oldest", c.text("order"));
        assertEquals("sister", c.text("person"));
    }

    @Test
    public void placesMessagesMail() {
        assertEquals("shiva temple", parse("save this place as shiva temple").text("name"));
        Command m = parse("send a whatsapp message to ravi saying i'm on the way");
        assertEquals("messages.compose", m.name());
        assertEquals("whatsapp", m.text("app"));
        assertEquals("ravi", m.text("who"));
        assertEquals("i'm on the way", m.text("text"));
        assertEquals("mail.summarize", parse("summarize my emails").name());
        assertEquals("unread", parse("read my unread mails").text("scope"));
        assertEquals("electricity bill", parse("search my email for electricity bill").text("query"));
    }

    @Test
    public void existingDeviceIntentsStillWork() {
        assertEquals("phone.call", parse("call mom").name());
        assertEquals("mom", parse("call mom").text("who"));
        assertEquals("app.open", parse("open whatsapp").name());
        Command a = parse("set an alarm for 6:30 pm");
        assertEquals(18, a.number("hour", -1));
        assertEquals(30, a.number("minute", -1));
        assertEquals(600, CommandRules.durationSeconds(parse("set a timer for 10 minutes").text("duration")));
        assertEquals("info.time", parse("what time is it").name());
        assertEquals("info.battery", parse("battery level").name());
        assertEquals("app.open", parse("open where is my train").name());
    }

    @Test
    public void chatAndMemoryAreNotCommands() {
        String[] notCommands = {
                "what is the capital of france",
                "remember shiva temple is ramalingeswara temple kukatpally",
                "my name is stark",
                "tell me a joke",
                "show my memory",
                "the way you talk is nice",
                "i took three photos yesterday",
                "why do trains run late",
                "what did i tell you about the camera"
        };
        for (String s : notCommands) {
            assertNull(s, CommandRules.parse(s));
        }
    }

    @Test
    public void durations() {
        assertEquals(10, CommandRules.durationSeconds("10s"));
        assertEquals(10, CommandRules.durationSeconds("ten seconds"));
        assertEquals(60, CommandRules.durationSeconds("a minute"));
        assertEquals(150, CommandRules.durationSeconds("2 minutes 30 seconds"));
        assertEquals(5400, CommandRules.durationSeconds("1.5 hours"));
        assertEquals(0, CommandRules.durationSeconds("soon"));
        assertTrue(CommandRules.durationSeconds("half an hour") == 1800);
    }
}
