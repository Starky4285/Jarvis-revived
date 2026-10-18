package com.jarvis.core.command;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A validated request to do one thing: a catalog command plus its arguments.
 * Created only by {@link CommandRules} (deterministic) or by
 * {@link CommandTranslator} after it has checked the model's JSON against the
 * catalog. Executing it is the app's job, never the model's.
 */
public final class Command {

    public final CommandSpec spec;
    private final Map<String, String> args;
    /** "rules" or "model": where it came from, for the debug line and tests. */
    public final String source;

    Command(CommandSpec spec, Map<String, String> args, String source) {
        this.spec = spec;
        this.args = Collections.unmodifiableMap(new LinkedHashMap<>(args));
        this.source = source;
    }

    public String name() {
        return spec.name;
    }

    /** The argument as text, or "" when it was not given. */
    public String text(String arg) {
        String v = args.get(arg);
        return v == null ? "" : v;
    }

    /** The argument as a number, or {@code fallback} when it was not given. */
    public int number(String arg, int fallback) {
        String v = args.get(arg);
        if (v == null || v.isEmpty()) return fallback;
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public boolean has(String arg) {
        String v = args.get(arg);
        return v != null && !v.isEmpty();
    }

    public Map<String, String> args() {
        return args;
    }

    /** "maps.directions [rules]": what was understood and by whom, WITHOUT the user's words. Safe to log. */
    public String tag() {
        return spec.name + " [" + source + "]";
    }

    /** Includes the arguments (the user's words): for tests and debugging, never for logs. */
    @Override
    public String toString() {
        return spec.name + args + " [" + source + "]";
    }

    /** Small builder used by the rules and the translator. */
    static final class Builder {
        private final CommandSpec spec;
        private final Map<String, String> args = new LinkedHashMap<>();
        private final String source;

        Builder(CommandSpec spec, String source) {
            this.spec = spec;
            this.source = source;
        }

        Builder put(String arg, String value) {
            if (value != null && !value.trim().isEmpty()) args.put(arg, value.trim());
            return this;
        }

        Builder put(String arg, int value) {
            args.put(arg, String.valueOf(value));
            return this;
        }

        Command build() {
            return new Command(spec, args, source);
        }
    }
}
