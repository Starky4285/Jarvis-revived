package com.jarvis.core.command;

import com.jarvis.core.llm.ChatPrompt;
import com.jarvis.core.text.TextTools;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Turns phrasings the rules don't know ("show me how to reach the bus stand on
 * foot") into a catalog command, using the on-device model as a TRANSLATOR,
 * not a decision maker:
 *
 *   1. a cheap gate decides whether the sentence looks like a request at all;
 *   2. the model answers under a grammar, so it can only name a real command;
 *   3. {@link #parse} validates types, ranges and choices, and checks that every
 *      word of a text argument was actually said by the user (no invented
 *      contacts or places).
 *
 * Anything that fails becomes "none" and goes to normal chat.
 */
public final class CommandTranslator {

    public static final int MAX_TOKENS = 72;
    private static final String SOURCE = "model";

    private static final Pattern LEAD = Pattern.compile(
            "(?i)^(?:(?:hey|hi|ok|okay)\\s+)?(?:jarvis[\\s,]+)?(?:(?:please|can you|could you|would you|will you|kindly)\\s+)?"
                    + "(?:(?:i (?:want|need|would like) (?:you )?to|let's|lets|help me)\\s+)?");
    private static final Pattern IMPERATIVE = Pattern.compile(
            "(?i)^(?:open|launch|start|take|click|capture|snap|shoot|record|find|search|show|look|locate|get|give|book"
                    + "|order|call|dial|ring|phone|text|message|whatsapp|send|write|play|navigate|guide|drive|set|remind"
                    + "|delete|remove|clean|free|analy[sz]e|scan|check|list|save|mark|read|summari[sz]e|go)\\b");
    private static final Pattern KEYWORDS = Pattern.compile(
            "(?i)\\b(?:camera|photos?|pictures?|pics?|selfies?|videos?|recordings?|recorder|gallery|maps?|directions?"
                    + "|rapido|uber|ola|trains?|alarm|timer|whatsapp|excel|sheets?|spreadsheet|files?|storage|e-?mails?"
                    + "|gmail|inbox)\\b");
    private static final Pattern QUESTION = Pattern.compile(
            "(?i)^(?:what|who|whom|whose|why|how|when|where|which|is|are|am|was|were|do|does|did|can i|should|will|would)\\b");

    private static final String GRAMMAR = CommandGrammar.build(CommandCatalog.all());

    private CommandTranslator() {
    }

    /**
     * Cheap pre-check so ordinary questions ("what is the capital of France") go
     * straight to chat without paying for a translation.
     */
    public static boolean looksLikeCommand(String utterance) {
        String t = TextTools.trimPunctuation(LEAD.matcher(TextTools.trimPunctuation(utterance)).replaceFirst(""));
        if (t.isEmpty()) return false;
        int words = t.split("\\s+").length;
        if (words > 20) return false;
        if (IMPERATIVE.matcher(t).find()) return true;
        if (QUESTION.matcher(t).find()) return false;
        return KEYWORDS.matcher(t).find() && words <= 12;
    }

    public static String grammar() {
        return GRAMMAR;
    }

    /** The fixed part (system + examples). The app decodes it once so it stays in the model's cache. */
    public static ChatPrompt warmup() {
        return withExamples(ChatPrompt.builder()).withoutAssistantStart().build();
    }

    public static ChatPrompt prompt(String utterance) {
        return withExamples(ChatPrompt.builder())
                .user(TextTools.sanitizeForPrompt(utterance, 240))
                .build();
    }

    private static ChatPrompt.Builder withExamples(ChatPrompt.Builder b) {
        StringBuilder sys = new StringBuilder()
                .append("Turn the user's request into one JSON command. Reply with JSON only.\n")
                .append("Commands:\n");
        for (CommandSpec s : CommandCatalog.all()) sys.append(s.describeForModel()).append('\n');
        sys.append("none - anything else (questions, chat, remembering)\n")
                .append("Copy text values from the user's words; use null when not said.");
        return b.system(sys.toString())
                .user("show me the way to the railway station on foot")
                .assistant("{\"cmd\":\"maps.directions\",\"args\":{\"place\":\"the railway station\",\"mode\":\"walking\","
                        + "\"navigate\":\"no\"}}")
                .user("ring my brother")
                .assistant("{\"cmd\":\"phone.call\",\"args\":{\"who\":\"my brother\"}}")
                .user("what is the capital of france")
                .assistant("{\"cmd\":\"none\"}");
    }

    /**
     * Validates the model's JSON against the catalog. Returns null for "none",
     * malformed output, unknown commands, bad values or ungrounded text.
     */
    public static Command parse(String modelOutput, String utterance) {
        Map<String, Object> obj;
        try {
            obj = MiniJson.parseObject(modelOutput);
        } catch (IllegalArgumentException e) {
            return null;
        }
        Object name = obj.get("cmd");
        if (!(name instanceof String) || CommandCatalog.NONE.equals(name)) return null;
        CommandSpec spec = CommandCatalog.get((String) name);
        if (spec == null) return null;

        Object rawArgs = obj.get("args");
        @SuppressWarnings("unchecked")
        Map<String, Object> args = rawArgs instanceof Map ? (Map<String, Object>) rawArgs : Collections.emptyMap();

        Set<String> said = groundTokens(utterance);
        Command.Builder b = new Command.Builder(spec, SOURCE);
        for (CommandSpec.Arg a : spec.args) {
            Object v = args.get(a.name);
            if (v == null) {
                if (a.required) return null;
                continue;
            }
            switch (a.type) {
                case NUMBER: {
                    long n;
                    if (v instanceof Number) {
                        n = ((Number) v).longValue();
                    } else {
                        try {
                            n = Long.parseLong(String.valueOf(v).trim());
                        } catch (NumberFormatException e) {
                            return null;
                        }
                    }
                    if (n < a.min || n > a.max) return null;
                    b.put(a.name, (int) n);
                    break;
                }
                case CHOICE: {
                    String c = TextTools.lower(String.valueOf(v)).trim();
                    if (!a.choices.contains(c)) {
                        if (a.required) return null;
                        continue;
                    }
                    b.put(a.name, c);
                    break;
                }
                default: {
                    String text = cleanText(String.valueOf(v));
                    if (text.isEmpty()) {
                        if (a.required) return null;
                        continue;
                    }
                    if (!said.containsAll(groundTokens(text))) return null;
                    b.put(a.name, text);
                }
            }
        }
        return b.build();
    }

    static String cleanText(String v) {
        String t = ChatPrompt.sanitize(v).replaceAll("\\s+", " ");
        t = TextTools.trimPunctuation(t);
        return t.length() > CommandGrammar.MAX_TEXT_CHARS ? t.substring(0, CommandGrammar.MAX_TEXT_CHARS).trim() : t;
    }

    /** Content words that must have been said. Numbers are skipped: their range is checked by type. */
    static Set<String> groundTokens(String text) {
        List<String> tokens = TextTools.contentTokens(text);
        Set<String> out = new HashSet<>();
        for (String t : tokens) {
            if (!t.matches("\\d+")) out.add(t);
        }
        return out;
    }
}
