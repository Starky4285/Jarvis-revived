package com.jarvis.core.command;

import java.util.List;

/**
 * Builds a GBNF grammar (llama.cpp's grammar format) from the command catalog.
 * With it the model can only write
 *
 * <pre>{"cmd":"maps.directions","args":{"place":"Domino's","mode":null,"navigate":"no"}}</pre>
 *
 * for a command that exists, with arguments of the right type, or
 * {"cmd":"none"}. A small model cannot invent a command, misspell a key or
 * ramble; it can still pick the wrong command, which is why the result is
 * validated again by {@link CommandTranslator#parse}.
 */
public final class CommandGrammar {

    static final int MAX_TEXT_CHARS = 80;

    private CommandGrammar() {
    }

    public static String build(List<CommandSpec> specs) {
        StringBuilder rules = new StringBuilder();
        StringBuilder root = new StringBuilder("root ::= ");
        boolean first = true;

        for (CommandSpec spec : specs) {
            String rule = ruleName(spec.name);
            root.append(first ? "" : " | ").append(rule);
            first = false;

            rules.append(rule).append(" ::= \"{\" ws ").append(lit("\"cmd\":")).append(" ws ")
                    .append(lit("\"" + spec.name + "\""));
            if (!spec.args.isEmpty()) {
                rules.append(" ws \",\" ws ").append(lit("\"args\":")).append(" ws \"{\" ws ");
                for (int i = 0; i < spec.args.size(); i++) {
                    CommandSpec.Arg a = spec.args.get(i);
                    if (i > 0) rules.append(" ws \",\" ws ");
                    rules.append(lit("\"" + a.name + "\":")).append(" ws ").append(valueRule(a));
                }
                rules.append(" ws \"}\"");
            }
            rules.append(" ws \"}\"\n");
        }
        root.append(" | c-none\n");
        rules.append("c-none ::= \"{\" ws ").append(lit("\"cmd\":")).append(" ws ")
                .append(lit("\"" + CommandCatalog.NONE + "\"")).append(" ws \"}\"\n");

        rules.append("text ::= \"\\\"\" tchar{1,").append(MAX_TEXT_CHARS).append("} \"\\\"\"\n");
        rules.append("tchar ::= [^\"\\\\\\x00-\\x1f]\n");
        rules.append("num ::= [0-9]{1,5}\n");
        rules.append("null ::= \"null\"\n");
        rules.append("ws ::= \" \"?\n");
        return root + rules.toString();
    }

    private static String valueRule(CommandSpec.Arg a) {
        String base;
        switch (a.type) {
            case NUMBER:
                base = "num";
                break;
            case CHOICE: {
                StringBuilder sb = new StringBuilder("(");
                for (int i = 0; i < a.choices.size(); i++) {
                    if (i > 0) sb.append(" | ");
                    sb.append(lit("\"" + a.choices.get(i) + "\""));
                }
                base = sb.append(")").toString();
                break;
            }
            default:
                base = "text";
        }
        return a.required ? base : "(" + base + " | null)";
    }

    /** GBNF rule names allow letters, digits and '-'. */
    static String ruleName(String commandName) {
        return "c-" + commandName.replaceAll("[^A-Za-z0-9]", "-");
    }

    /** A GBNF string literal for {@code text} (quotes and backslashes escaped). */
    static String lit(String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
