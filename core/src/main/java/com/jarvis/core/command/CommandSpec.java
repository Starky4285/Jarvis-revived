package com.jarvis.core.command;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * One thing Jarvis can be asked to do: its name, its arguments, how risky it
 * is and whether it is built yet. The fixed list lives in {@link CommandCatalog};
 * the model can only ever choose from it.
 */
public final class CommandSpec {

    /** SAFE runs straight away. CONFIRM asks "… — yes or no?" first (calls, sending, deleting, booking). */
    public enum Risk { SAFE, CONFIRM }

    /** PLANNED commands are understood but answered with "coming in step N" instead of guessing. */
    public enum Status { AVAILABLE, PLANNED }

    public enum ArgType { TEXT, NUMBER, CHOICE }

    public static final class Arg {
        public final String name;
        public final ArgType type;
        public final boolean required;
        public final List<String> choices;
        public final int min;
        public final int max;
        /** Short hint shown to the model, e.g. "0-23" (choices are listed automatically). */
        public final String hint;

        private Arg(String name, ArgType type, boolean required, List<String> choices, int min, int max, String hint) {
            this.name = name;
            this.type = type;
            this.required = required;
            this.choices = choices;
            this.min = min;
            this.max = max;
            this.hint = hint == null ? "" : hint;
        }

        public static Arg text(String name, boolean required, String hint) {
            return new Arg(name, ArgType.TEXT, required, Collections.emptyList(), 0, 0, hint);
        }

        public static Arg number(String name, boolean required, int min, int max, String hint) {
            return new Arg(name, ArgType.NUMBER, required, Collections.emptyList(), min, max, hint);
        }

        public static Arg choice(String name, boolean required, String... choices) {
            return new Arg(name, ArgType.CHOICE, required, Collections.unmodifiableList(Arrays.asList(choices)),
                    0, 0, "");
        }
    }

    public final String name;
    /** What it does, in a few words; shown to the model. */
    public final String summary;
    public final List<Arg> args;
    public final Risk risk;
    public final Status status;
    /** For PLANNED commands: what Jarvis says instead, e.g. "Taking photos for you comes in the next update." */
    public final String plannedReply;

    CommandSpec(String name, String summary, Risk risk, Status status, String plannedReply, Arg... args) {
        this.name = name;
        this.summary = summary;
        this.risk = risk;
        this.status = status;
        this.plannedReply = plannedReply == null ? "" : plannedReply;
        this.args = Collections.unmodifiableList(Arrays.asList(args));
    }

    public Arg arg(String argName) {
        for (Arg a : args) if (a.name.equals(argName)) return a;
        return null;
    }

    public boolean isAvailable() {
        return status == Status.AVAILABLE;
    }

    /** "maps.directions(place, mode driving/walking/two_wheeler/transit, navigate yes/no) - directions to a place" */
    String describeForModel() {
        StringBuilder sb = new StringBuilder(name).append('(');
        for (int i = 0; i < args.size(); i++) {
            Arg a = args.get(i);
            if (i > 0) sb.append(", ");
            sb.append(a.name);
            if (a.type == ArgType.CHOICE) {
                sb.append(' ').append(String.join("/", a.choices));
            } else if (!a.hint.isEmpty()) {
                sb.append(' ').append(a.hint);
            }
        }
        return sb.append(") - ").append(summary).toString();
    }
}
