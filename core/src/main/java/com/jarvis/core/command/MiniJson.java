package com.jarvis.core.command;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A tiny, strict JSON object reader for the model's command output. Supports
 * objects, strings (with escapes), integers/decimals, true/false/null. Arrays
 * are not needed by the command grammar and are rejected. Throws
 * {@link IllegalArgumentException} on anything malformed, so a broken model
 * reply is never half-executed.
 *
 * (org.json would work on Android, but the core stays dependency-free and
 * testable on any JVM.)
 */
final class MiniJson {

    private final String s;
    private int i;

    private MiniJson(String s) {
        this.s = s;
    }

    /** Parses the first JSON object found in {@code text}; text before it is ignored, text after it too. */
    static Map<String, Object> parseObject(String text) {
        if (text == null) throw new IllegalArgumentException("null");
        int start = text.indexOf('{');
        if (start < 0) throw new IllegalArgumentException("no object");
        MiniJson p = new MiniJson(text);
        p.i = start;
        return p.object(0);
    }

    private Map<String, Object> object(int depth) {
        if (depth > 4) throw new IllegalArgumentException("too deep");
        expect('{');
        Map<String, Object> out = new LinkedHashMap<>();
        ws();
        if (peek() == '}') {
            i++;
            return out;
        }
        while (true) {
            ws();
            String key = string();
            ws();
            expect(':');
            ws();
            out.put(key, value(depth));
            ws();
            char c = next();
            if (c == '}') return out;
            if (c != ',') throw new IllegalArgumentException("expected , or } at " + (i - 1));
        }
    }

    private Object value(int depth) {
        char c = peek();
        if (c == '"') return string();
        if (c == '{') return object(depth + 1);
        if (c == '-' || (c >= '0' && c <= '9')) return number();
        if (s.startsWith("true", i)) {
            i += 4;
            return Boolean.TRUE;
        }
        if (s.startsWith("false", i)) {
            i += 5;
            return Boolean.FALSE;
        }
        if (s.startsWith("null", i)) {
            i += 4;
            return null;
        }
        throw new IllegalArgumentException("unexpected '" + c + "' at " + i);
    }

    private String string() {
        expect('"');
        StringBuilder sb = new StringBuilder();
        while (true) {
            char c = next();
            if (c == '"') return sb.toString();
            if (c == '\\') {
                char e = next();
                switch (e) {
                    case '"': sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    case '/': sb.append('/'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    case 'u':
                        if (i + 4 > s.length()) throw new IllegalArgumentException("bad \\u escape");
                        try {
                            sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        } catch (NumberFormatException ex) {
                            throw new IllegalArgumentException("bad \\u escape");
                        }
                        i += 4;
                        break;
                    default:
                        throw new IllegalArgumentException("bad escape \\" + e);
                }
            } else if (c < 0x20) {
                throw new IllegalArgumentException("control character in string");
            } else {
                sb.append(c);
            }
            if (sb.length() > 2000) throw new IllegalArgumentException("string too long");
        }
    }

    private Number number() {
        int start = i;
        if (peek() == '-') i++;
        while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
        boolean decimal = false;
        if (i < s.length() && s.charAt(i) == '.') {
            decimal = true;
            i++;
            while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
        }
        String n = s.substring(start, i);
        if (n.isEmpty() || n.equals("-")) throw new IllegalArgumentException("bad number");
        try {
            if (decimal) return Double.parseDouble(n);
            return Long.parseLong(n);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("bad number");
        }
    }

    private void ws() {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
    }

    private char peek() {
        if (i >= s.length()) throw new IllegalArgumentException("unexpected end");
        return s.charAt(i);
    }

    private char next() {
        if (i >= s.length()) throw new IllegalArgumentException("unexpected end");
        return s.charAt(i++);
    }

    private void expect(char c) {
        if (next() != c) throw new IllegalArgumentException("expected '" + c + "' at " + (i - 1));
    }
}
