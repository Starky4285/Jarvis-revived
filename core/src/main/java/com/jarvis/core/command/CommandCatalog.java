package com.jarvis.core.command;

import com.jarvis.core.command.CommandSpec.Arg;
import com.jarvis.core.command.CommandSpec.Risk;
import com.jarvis.core.command.CommandSpec.Status;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything Jarvis can be asked to do. The model can only pick one of these
 * (the grammar forces it); anything else is "none" and goes to normal chat.
 *
 * PLANNED commands are listed on purpose: Jarvis then recognizes the request
 * and says honestly that it is coming, instead of the model inventing an answer.
 */
public final class CommandCatalog {

    public static final String NONE = "none";

    private static final Map<String, CommandSpec> SPECS = new LinkedHashMap<>();

    private static void add(CommandSpec s) {
        SPECS.put(s.name, s);
    }

    static {
        // ---------------------------------------------------------- available now
        add(new CommandSpec("phone.call", "call a contact or number", Risk.CONFIRM, Status.AVAILABLE, null,
                Arg.text("who", true, "")));
        add(new CommandSpec("app.open", "open an app", Risk.SAFE, Status.AVAILABLE, null,
                Arg.text("app", true, "")));
        add(new CommandSpec("alarm.set", "set an alarm", Risk.SAFE, Status.AVAILABLE, null,
                Arg.number("hour", true, 0, 23, "0-23"),
                Arg.number("minute", false, 0, 59, "")));
        add(new CommandSpec("timer.set", "start a timer, e.g. duration \"10 minutes\"", Risk.SAFE, Status.AVAILABLE, null,
                Arg.text("duration", true, "")));
        add(new CommandSpec("maps.directions", "directions to a place", Risk.SAFE, Status.AVAILABLE,
                null,
                Arg.text("place", true, ""),
                Arg.choice("mode", false, "driving", "walking", "two_wheeler", "transit"),
                Arg.choice("navigate", false, "yes", "no")));
        add(new CommandSpec("info.time", "the time", Risk.SAFE, Status.AVAILABLE, null));
        add(new CommandSpec("info.date", "today's date", Risk.SAFE, Status.AVAILABLE, null));
        add(new CommandSpec("info.battery", "battery level", Risk.SAFE, Status.AVAILABLE, null));

        // ------------------------------------------------ understood, built later
        add(new CommandSpec("camera.photo", "take photos", Risk.SAFE, Status.PLANNED,
                "Taking photos for you comes in the next update. For now, say: open camera.",
                Arg.number("delay_seconds", false, 0, 600, ""),
                Arg.number("count", false, 1, 20, "")));
        add(new CommandSpec("camera.video", "record video", Risk.SAFE, Status.PLANNED,
                "Recording video for you comes in the next update. For now, say: open camera.",
                Arg.number("delay_seconds", false, 0, 600, ""),
                Arg.number("duration_seconds", false, 1, 3600, "")));
        add(new CommandSpec("recorder.start", "start voice recording", Risk.SAFE, Status.PLANNED,
                "Voice recording comes in the next update."));
        add(new CommandSpec("recordings.find", "find voice recordings", Risk.SAFE, Status.PLANNED,
                "Searching your recordings comes in the next update.",
                Arg.number("min_minutes", false, 0, 600, ""),
                Arg.text("query", false, "")));
        add(new CommandSpec("files.open", "open a file or spreadsheet", Risk.SAFE, Status.PLANNED,
                "Opening files and sheets comes in the next update.",
                Arg.text("name", true, ""),
                Arg.text("app", false, ""),
                Arg.text("sheet", false, "")));
        add(new CommandSpec("storage.analyze", "what is using storage", Risk.SAFE, Status.PLANNED,
                "The storage check comes in the next update."));
        add(new CommandSpec("place.save_here", "save current location as a place", Risk.SAFE,
                Status.PLANNED,
                "Saving your current location comes in the next update. For now, tell me the address: "
                        + "remember, name of the place, is, and the address.",
                Arg.text("name", true, "")));
        add(new CommandSpec("messages.compose", "write a message",
                Risk.CONFIRM, Status.PLANNED,
                "Writing messages for you comes in the next update.",
                Arg.choice("app", false, "sms", "whatsapp"),
                Arg.text("who", true, ""),
                Arg.text("text", false, "")));
        add(new CommandSpec("mail.summarize", "summarize e-mail", Risk.SAFE, Status.PLANNED,
                "E-mail comes with the mail bridge, after the phone tasks.",
                Arg.choice("scope", false, "unread", "today", "all")));
        add(new CommandSpec("mail.search", "search e-mail", Risk.SAFE, Status.PLANNED,
                "E-mail comes with the mail bridge, after the phone tasks.",
                Arg.text("query", true, "")));
        add(new CommandSpec("photos.find", "find photos", Risk.SAFE, Status.PLANNED,
                "Finding photos by person or date comes later, once the private photo index is built.",
                Arg.text("person", false, ""),
                Arg.choice("order", false, "oldest", "newest"),
                Arg.text("query", false, "")));
        add(new CommandSpec("ride.book", "book a ride", Risk.CONFIRM, Status.PLANNED,
                "Booking rides comes later. For now, say: open Rapido.",
                Arg.choice("app", false, "rapido", "uber", "ola"),
                Arg.choice("vehicle", false, "bike", "auto", "cab"),
                Arg.text("place", true, "")));
        add(new CommandSpec("train.search", "find trains", Risk.SAFE, Status.PLANNED,
                "Train search comes later. For now, say: open Where is my Train.",
                Arg.text("to", true, ""),
                Arg.text("from", false, "")));
    }

    private CommandCatalog() {
    }

    public static CommandSpec get(String name) {
        return SPECS.get(name);
    }

    public static List<CommandSpec> all() {
        return Collections.unmodifiableList(new ArrayList<>(SPECS.values()));
    }
}
