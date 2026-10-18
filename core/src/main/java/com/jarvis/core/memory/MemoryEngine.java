package com.jarvis.core.memory;

import com.jarvis.core.text.TextTools;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * The single owner of memory. Every write goes through here:
 *
 *   parser / model PROPOSES  →  engine DECIDES  →  store WRITES
 *
 * and every write is said back to the user, so a misheard or misparsed fact is
 * corrected in the next breath ("no, it's blue" / "never mind").
 *
 * Thread-safety: all public methods are synchronized; the app calls them from
 * one background executor anyway.
 */
public final class MemoryEngine {

    /** "never mind" / "no, it's X" only apply to a save made this recently. */
    static final long UNDO_WINDOW_MS = 5 * 60_000L;
    static final long FORGET_ALL_CONFIRM_WINDOW_MS = 60_000L;
    static final int MAX_EPISODES = 1000;
    static final int LIST_SPOKEN_LIMIT = 5;

    private static final Set<String> YES = new HashSet<>(Arrays.asList(
            "yes", "yeah", "yep", "confirm", "yes delete", "yes forget", "do it", "sure", "ok", "okay", "yes please"));
    private static final Set<String> NO = new HashSet<>(Arrays.asList(
            "no", "nope", "cancel", "don't", "do not", "stop", "keep it", "no thanks"));

    /** Keys whose values are names/places and read better title-cased ("priya" → "Priya"). */
    private static final Set<String> PROPER_NOUN_KEY_WORDS = new HashSet<>(Arrays.asList(
            "name", "city", "hometown", "workplace", "wife", "husband", "mother", "father", "son",
            "daughter", "brother", "sister", "friend", "manager", "boss", "doctor", "teacher",
            "partner", "girlfriend", "boyfriend", "pet", "dog", "cat", "nickname", "mantra",
            "kid", "child", "children", "grandmother", "grandfather", "uncle", "aunt", "cousin"));

    private static final DateTimeFormatter DAY_FMT = DateTimeFormatter.ofPattern("d MMMM", Locale.ENGLISH);

    private final MemoryStore store;
    private final LongSupplier clock;
    private final ZoneId zone;
    private volatile MemoryExtractor extractor;

    // Last write, for "never mind" and "no, it's X".
    private long lastWriteAt = 0L;
    private long lastWriteFactId = -1L;
    private long lastWriteReplacedId = -1L;
    private String lastWriteKey = "";
    private String lastWriteLabel = "";
    private boolean lastWriteProtected = false;

    private long forgetAllRequestedAt = 0L;

    /** Counts user turns, so "never mind" / "no, it's X" only apply to the turn right after a save. */
    private long turnCounter = 0L;
    private long lastWriteTurn = -10L;

    public MemoryEngine(MemoryStore store, LongSupplier clock, ZoneId zone) {
        this.store = store;
        this.clock = clock;
        this.zone = zone;
    }

    public void setExtractor(MemoryExtractor extractor) {
        this.extractor = extractor;
    }

    public MemoryStore store() {
        return store;
    }

    /**
     * Handles one user utterance. Always logs the turn (redacted when it holds a
     * secret). Returns NOT_MEMORY when this is not a memory request.
     */
    public synchronized MemoryResult handle(String utterance) {
        long now = clock.getAsLong();
        String text = TextTools.trimPunctuation(utterance);
        if (text.isEmpty()) return MemoryResult.NOT_MEMORY;
        turnCounter++;

        MemoryResult pending = resolvePendingForgetAll(text, now);
        if (pending != null) {
            logUser(text, false, now);
            return pending;
        }

        MemoryCommand cmd = MemoryParser.parse(text);
        MemoryResult result;
        boolean secret = false;

        switch (cmd.type) {
            case REMEMBER: {
                String key = cmd.note ? "" : KeyNormalizer.canonical(cmd.keyPhrase);
                secret = !cmd.note && (SensitiveKeys.isSensitiveKey(key) || SensitiveKeys.looksLikeSensitiveValue(cmd.value));
                result = cmd.note ? rememberNote(cmd, now) : remember(cmd.keyPhrase, cmd.value, text, now);
                break;
            }
            case CORRECT_LAST:
                secret = lastWriteProtected;
                result = correctLast(cmd.value, text, now);
                break;
            case UNDO_LAST:
                result = undoLast(now);
                break;
            case FORGET:
                result = forget(cmd.keyPhrase);
                break;
            case FORGET_ALL:
                forgetAllRequestedAt = now;
                result = new MemoryResult(MemoryResult.Kind.FORGET_ALL_CONFIRM,
                        "This will erase everything I remember about you. Say yes to confirm, or no to keep it.", null);
                break;
            case RECALL:
                result = recall(cmd.keyPhrase);
                break;
            case RECALL_SOFT:
                result = recallSoft(cmd.keyPhrase, cmd.query);
                break;
            case RECALL_HISTORY:
                result = recallHistory(cmd.keyPhrase);
                break;
            case LIST:
                result = list();
                break;
            default:
                result = strongMatchForQuestion(text);
        }

        if (!secret && !result.handled()) {
            secret = mentionsSecret(text);
        }
        logUser(text, secret, now);
        return result;
    }

    /** Logs a user turn that was handled elsewhere (device commands). Secrets are redacted. */
    public synchronized void recordUserTurn(String utterance) {
        String text = TextTools.trimPunctuation(utterance);
        if (text.isEmpty()) return;
        turnCounter++;
        logUser(text, mentionsSecret(text), clock.getAsLong());
    }

    /** "stop" / "cancel" while a "forget everything?" question is open: drop the question. */
    public synchronized void cancelPendingConfirmation() {
        forgetAllRequestedAt = 0L;
    }

    /** Logs what Jarvis said, for "what did we talk about" style recall. */
    public synchronized void recordAssistantTurn(String reply) {
        String clean = TextTools.trimPunctuation(reply);
        if (clean.isEmpty()) return;
        store.appendEpisode(Episode.Role.ASSISTANT, clean, clock.getAsLong());
    }

    // =====================================================================
    // Writes
    // =====================================================================

    private MemoryResult remember(String keyPhrase, String rawValue, String source, long now) {
        String key = KeyNormalizer.canonical(keyPhrase);
        String label = labelFor(keyPhrase);
        String value = TemporalAnchor.anchor(TextTools.trimPunctuation(rawValue), now, zone);

        if (key.isEmpty() || value.isEmpty()) return MemoryResult.NOT_MEMORY;

        if (SensitiveKeys.isSensitiveKey(key) || SensitiveKeys.looksLikeSensitiveValue(value)) {
            if (!putProtectedSafely(key, label, value, now)) return couldNotProtect();
            markLastWrite(now, -1L, -1L, key, label, true);
            return new MemoryResult(MemoryResult.Kind.PROTECTED_SAVED,
                    "Saved " + spokenLabel(label) + " in protected memory. I won't say it out loud.", null);
        }

        Fact existing = store.activeFact(key);
        if (existing != null && existing.value.equalsIgnoreCase(value)) {
            return new MemoryResult(MemoryResult.Kind.ALREADY_KNOWN,
                    "I already have that. " + sentence(label, existing.value, key), existing);
        }

        final long[] ids = new long[]{-1L, -1L};
        store.inTransaction(() -> {
            if (existing != null) {
                store.supersedeFact(existing.id, now);
                ids[1] = existing.id;
            }
            ids[0] = store.insertFact(key, label, value, source, now);
        });
        markLastWrite(now, ids[0], ids[1], key, label, false);
        Fact saved = store.activeFact(key);

        if (existing != null) {
            return new MemoryResult(MemoryResult.Kind.UPDATED,
                    "Updated. " + capitalize(spokenLabel(label)) + " " + verb(label) + " now "
                            + displayValue(key, value) + ", not " + displayValue(key, existing.value) + ".",
                    saved);
        }
        return new MemoryResult(MemoryResult.Kind.SAVED, "Got it. " + sentence(label, value, key), saved);
    }

    private MemoryResult rememberNote(MemoryCommand cmd, long now) {
        String body = TextTools.trimPunctuation(cmd.value);

        MemoryExtractor ex = extractor;
        if (ex != null) {
            try {
                MemoryExtractor.Proposal p = MemoryExtractor.validate(ex.propose(body), body);
                if (p != null) {
                    return remember(p.key, p.value, cmd.query, now);
                }
            } catch (RuntimeException ignored) {
                // Model trouble never blocks saving: fall back to a note.
            }
        }

        String value = TemporalAnchor.anchor(body, now, zone);
        List<String> words = TextTools.contentTokens(body);
        String key = Fact.NOTE_PREFIX + String.join(" ", words.subList(0, Math.min(6, words.size())));
        if (words.isEmpty()) return MemoryResult.NOT_MEMORY;

        if (SensitiveKeys.looksLikeSensitiveValue(value) || mentionsSecret(value)) {
            if (!putProtectedSafely(key, body, value, now)) return couldNotProtect();
            markLastWrite(now, -1L, -1L, key, body, true);
            return new MemoryResult(MemoryResult.Kind.PROTECTED_SAVED,
                    "Saved that in protected memory. I won't say it out loud.", null);
        }

        Fact existing = store.activeFact(key);
        final long[] ids = new long[]{-1L, -1L};
        store.inTransaction(() -> {
            if (existing != null) {
                store.supersedeFact(existing.id, now);
                ids[1] = existing.id;
            }
            ids[0] = store.insertFact(key, body, value, cmd.query, now);
        });
        markLastWrite(now, ids[0], ids[1], key, body, false);
        return new MemoryResult(MemoryResult.Kind.SAVED,
                "Okay, I'll remember that " + TextTools.capitalizeCalendarWords(TextTools.flipPerson(value)) + ".",
                store.activeFact(key));
    }

    private MemoryResult correctLast(String newValue, String source, long now) {
        if (!recentWrite(now) || lastWriteKey.isEmpty()) {
            return MemoryResult.NOT_MEMORY;
        }
        String key = lastWriteKey;
        String label = lastWriteLabel;

        if (lastWriteProtected) {
            String value = TextTools.trimPunctuation(newValue);
            if (!putProtectedSafely(key, label, value, now)) return couldNotProtect();
            markLastWrite(now, -1L, -1L, key, label, true);
            return new MemoryResult(MemoryResult.Kind.PROTECTED_SAVED,
                    "Fixed. Updated " + spokenLabel(label) + " in protected memory.", null);
        }

        // The just-written row was a mistake: drop it rather than keeping it as history.
        final long wrongId = lastWriteFactId;
        final long replacedId = lastWriteReplacedId;
        String value = TemporalAnchor.anchor(TextTools.trimPunctuation(newValue), now, zone);
        final long[] newId = new long[]{-1L};
        store.inTransaction(() -> {
            if (wrongId > 0) store.deleteFactRow(wrongId);
            newId[0] = store.insertFact(key, label, value, source, now);
        });
        markLastWrite(now, newId[0], replacedId, key, label, false);

        if (key.startsWith(Fact.NOTE_PREFIX)) {
            return new MemoryResult(MemoryResult.Kind.UPDATED,
                    "Fixed. I'll remember " + TextTools.flipPerson(value) + ".", store.activeFact(key));
        }
        return new MemoryResult(MemoryResult.Kind.UPDATED, "Fixed. " + sentence(label, value, key), store.activeFact(key));
    }

    private MemoryResult undoLast(long now) {
        if (!recentWrite(now) || lastWriteKey.isEmpty()) {
            // Not a memory moment: let the router treat "never mind" as a normal cancel.
            return MemoryResult.NOT_MEMORY;
        }
        String label = lastWriteLabel;
        if (lastWriteProtected) {
            store.deleteProtected(lastWriteKey);
        } else {
            final long wrongId = lastWriteFactId;
            final long replacedId = lastWriteReplacedId;
            store.inTransaction(() -> {
                if (wrongId > 0) store.deleteFactRow(wrongId);
                if (replacedId > 0) store.reactivateFact(replacedId);
            });
        }
        clearLastWrite();
        String what = label.isEmpty() || lastWriteKeyIsNote(label) ? "that" : spokenLabel(label);
        return new MemoryResult(MemoryResult.Kind.UNDONE, "Okay, I won't remember " + what + ".", null);
    }

    private MemoryResult forget(String keyPhrase) {
        String key = KeyNormalizer.canonical(keyPhrase);
        String label = labelFor(keyPhrase);

        ProtectedItem secret = findProtected(key);
        if (secret != null) {
            store.deleteProtected(secret.key);
            clearLastWriteIf(secret.key);
            return new MemoryResult(MemoryResult.Kind.FORGOTTEN,
                    "Done. I've forgotten " + spokenLabel(secret.label) + ".", null);
        }

        Fact fact = findFact(key);
        if (fact == null) fact = findBySearch(keyPhrase);
        if (fact == null) {
            return new MemoryResult(MemoryResult.Kind.FORGET_MISS,
                    "I didn't have anything saved about " + spokenLabel(label) + ".", null);
        }
        store.deleteKey(fact.key);
        clearLastWriteIf(fact.key);
        String what = fact.isNote() ? "that" : spokenLabel(fact.label);
        return new MemoryResult(MemoryResult.Kind.FORGOTTEN, "Done. I've forgotten " + what + ".", fact);
    }

    private MemoryResult resolvePendingForgetAll(String text, long now) {
        if (forgetAllRequestedAt == 0L) return null;
        boolean fresh = now - forgetAllRequestedAt <= FORGET_ALL_CONFIRM_WINDOW_MS;
        forgetAllRequestedAt = 0L;
        if (!fresh) return null;

        String l = TextTools.lower(TextTools.trimPunctuation(text));
        if (YES.contains(l)) {
            store.deleteAll();
            clearLastWrite();
            return new MemoryResult(MemoryResult.Kind.FORGOT_ALL, "Done. I've erased everything I remembered.", null);
        }
        if (NO.contains(l)) {
            return new MemoryResult(MemoryResult.Kind.FORGET_ALL_CANCELLED, "Okay, I kept everything.", null);
        }
        return null;
    }

    /**
     * The Android store encrypts protected values with a Keystore key. If that
     * fails we must NOT fall back to plain storage, so the secret is dropped and
     * the user is told.
     */
    private boolean putProtectedSafely(String key, String label, String value, long now) {
        try {
            store.putProtected(key, label, value, now);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static MemoryResult couldNotProtect() {
        return new MemoryResult(MemoryResult.Kind.PROTECTED_FAILED,
                "I couldn't store that securely on this phone, so I didn't save it.", null);
    }

    // =====================================================================
    // Reads
    // =====================================================================

    private MemoryResult recall(String keyPhrase) {
        String key = KeyNormalizer.canonical(keyPhrase);
        String label = labelFor(keyPhrase);
        if (key.isEmpty()) return MemoryResult.NOT_MEMORY;

        ProtectedItem secret = findProtected(key);
        if (secret != null) {
            return new MemoryResult(MemoryResult.Kind.PROTECTED_RECALL,
                    capitalize(spokenLabel(secret.label)) + " is in protected memory. Open Jarvis to see it.", null);
        }

        Fact fact = findFact(key);
        if (fact == null) fact = findBySearch(keyPhrase);
        if (fact != null) {
            return new MemoryResult(MemoryResult.Kind.RECALLED, answerFor(fact), fact);
        }
        return new MemoryResult(MemoryResult.Kind.RECALL_MISS,
                "I don't have " + spokenLabel(label) + " saved yet. You can say: remember my "
                        + TextTools.lower(label) + " is ...", null);
    }

    private MemoryResult recallSoft(String phrase, String query) {
        Fact fact = findBySearch(phrase);
        if (fact != null) {
            return new MemoryResult(MemoryResult.Kind.RECALLED, answerFor(fact), fact);
        }

        Episode ep = searchEpisodes(phrase);
        if (ep != null) {
            String when = DAY_FMT.format(Instant.ofEpochMilli(ep.timestamp).atZone(zone));
            return new MemoryResult(MemoryResult.Kind.RECALLED,
                    "On " + when + " you said: " + TextTools.flipPerson(ep.text) + ".", null);
        }

        boolean askedAboutTellingMe = TextTools.lower(query).startsWith("what did i");
        if (askedAboutTellingMe) {
            return new MemoryResult(MemoryResult.Kind.RECALL_MISS,
                    "I don't remember you telling me about " + TextTools.flipPerson(phrase) + ".", null);
        }
        return MemoryResult.NOT_MEMORY;
    }

    /**
     * Last resort for questions without "my" ("when is rent due"): answer from
     * memory only when EVERY content word of the question (at least two) is in
     * one saved fact. Otherwise the question belongs to the model.
     */
    private MemoryResult strongMatchForQuestion(String text) {
        if (!looksLikeQuestion(text)) return MemoryResult.NOT_MEMORY;
        List<String> q = TextTools.contentTokens(text);
        if (q.size() < 2) return MemoryResult.NOT_MEMORY;
        for (Fact f : store.activeFacts()) {
            Set<String> hay = new HashSet<>(TextTools.contentTokens(f.key + " " + f.value));
            if (hay.containsAll(q)) {
                return new MemoryResult(MemoryResult.Kind.RECALLED, answerFor(f), f);
            }
        }
        return MemoryResult.NOT_MEMORY;
    }

    private MemoryResult recallHistory(String keyPhrase) {
        String key = KeyNormalizer.canonical(keyPhrase);
        String label = labelFor(keyPhrase);
        Fact current = findFact(key);
        if (current == null) {
            return new MemoryResult(MemoryResult.Kind.RECALL_MISS,
                    "I don't have " + spokenLabel(label) + " saved.", null);
        }
        List<Fact> history = store.history(current.key);
        if (history.size() < 2) {
            return new MemoryResult(MemoryResult.Kind.HISTORY,
                    "I only know one value: " + sentence(current.label, current.value, current.key), current);
        }
        Fact previous = history.get(1);
        return new MemoryResult(MemoryResult.Kind.HISTORY,
                "Before " + displayValue(current.key, current.value) + ", " + spokenLabel(current.label) + " "
                        + pastVerb(current.label) + " " + displayValue(current.key, previous.value) + ".",
                previous);
    }

    private MemoryResult list() {
        List<Fact> facts = store.activeFacts();
        int secrets = store.listProtected().size();
        if (facts.isEmpty() && secrets == 0) {
            return new MemoryResult(MemoryResult.Kind.LISTED,
                    "I don't remember anything about you yet. Tell me things like: remember my birthday is 5 May.", null);
        }
        StringBuilder sb = new StringBuilder();
        int total = facts.size() + secrets;
        sb.append("I remember ").append(total).append(total == 1 ? " thing" : " things").append(". ");
        int spoken = 0;
        for (Fact f : facts) {
            if (spoken >= LIST_SPOKEN_LIMIT) break;
            sb.append(f.isNote() ? capitalize(TextTools.flipPerson(f.value)) + ". " : sentence(f.label, f.value, f.key) + " ");
            spoken++;
        }
        int rest = total - spoken;
        if (rest > 0) {
            sb.append("And ").append(rest).append(" more").append(secrets > 0 ? ", including protected items" : "")
                    .append(". Open Jarvis to see everything.");
        }
        return new MemoryResult(MemoryResult.Kind.LISTED, sb.toString().trim(), null);
    }

    // =====================================================================
    // Lookup helpers
    // =====================================================================

    /** Exact canonical key, then token match against fact KEYS (notes excluded). */
    Fact findFact(String canonicalKey) {
        Fact exact = store.activeFact(canonicalKey);
        if (exact != null) return exact;

        List<String> q = TextTools.contentTokens(canonicalKey);
        if (q.isEmpty()) return null;

        Fact best = null;
        double bestScore = 0.0;
        for (Fact f : store.activeFacts()) {
            if (f.isNote()) continue;
            List<String> k = TextTools.contentTokens(f.key);
            if (k.isEmpty()) continue;
            double score;
            if (k.containsAll(q)) {
                score = 2.0 + (double) q.size() / k.size();
            } else if (q.containsAll(k)) {
                score = 1.5 + (double) k.size() / q.size();
            } else {
                score = jaccard(q, k);
            }
            if (score > bestScore) {
                bestScore = score;
                best = f;
            }
        }
        return bestScore >= 0.5 ? best : null;
    }

    /** Token search over key + value + source of every fact and note. */
    Fact findBySearch(String phrase) {
        List<String> q = TextTools.contentTokens(phrase);
        if (q.isEmpty()) return null;
        int needed = Math.max(1, (int) Math.ceil(q.size() * 0.6));

        Fact best = null;
        int bestHits = 0;
        for (Fact f : store.activeFacts()) {
            Set<String> hay = new HashSet<>(TextTools.contentTokens(f.key + " " + f.value + " " + f.source));
            int hits = 0;
            for (String t : q) if (hay.contains(t)) hits++;
            if (hits > bestHits) {
                bestHits = hits;
                best = f;
            }
        }
        return bestHits >= needed ? best : null;
    }

    private Episode searchEpisodes(String phrase) {
        List<String> q = TextTools.contentTokens(phrase);
        if (q.isEmpty()) return null;
        int needed = Math.max(1, (int) Math.ceil(q.size() * 0.6));
        for (Episode ep : store.recentEpisodes(300)) {
            if (ep.role != Episode.Role.USER) continue;
            Set<String> hay = new HashSet<>(TextTools.contentTokens(ep.text));
            int hits = 0;
            for (String t : q) if (hay.contains(t)) hits++;
            if (hits >= needed && !looksLikeQuestion(ep.text)) return ep;
        }
        return null;
    }

    private ProtectedItem findProtected(String canonicalKey) {
        ProtectedItem exact = store.getProtected(canonicalKey);
        if (exact != null) return exact;
        List<String> q = TextTools.contentTokens(canonicalKey);
        if (q.isEmpty()) return null;
        for (ProtectedItem p : store.listProtected()) {
            List<String> k = TextTools.contentTokens(p.key);
            if (!k.isEmpty() && (k.containsAll(q) || q.containsAll(k))) return p;
        }
        return null;
    }

    private static double jaccard(List<String> a, List<String> b) {
        Set<String> inter = new HashSet<>(a);
        inter.retainAll(b);
        Set<String> union = new HashSet<>(a);
        union.addAll(b);
        return union.isEmpty() ? 0.0 : (double) inter.size() / union.size();
    }

    private static boolean looksLikeQuestion(String text) {
        String l = TextTools.lower(text).trim();
        return l.endsWith("?") || l.matches("^(what|who|when|where|why|how|which|do|does|did|is|are|can|could)\\b.*");
    }

    // =====================================================================
    // Episodes / redaction
    // =====================================================================

    private void logUser(String text, boolean secret, long now) {
        store.appendEpisode(Episode.Role.USER, secret ? "[protected item mentioned]" : text, now);
        store.pruneEpisodes(MAX_EPISODES);
    }

    private static boolean mentionsSecret(String text) {
        if (SensitiveKeys.looksLikeSensitiveValue(text)) return true;
        String canonical = KeyNormalizer.canonical(text);
        return SensitiveKeys.isSensitiveKey(canonical) && text.matches(".*\\d.*|.*\\bis\\b.*");
    }

    // =====================================================================
    // Last-write tracking
    // =====================================================================

    private void markLastWrite(long now, long factId, long replacedId, String key, String label, boolean isProtected) {
        lastWriteTurn = turnCounter;
        lastWriteAt = now;
        lastWriteFactId = factId;
        lastWriteReplacedId = replacedId;
        lastWriteKey = key;
        lastWriteLabel = label;
        lastWriteProtected = isProtected;
    }

    private void clearLastWrite() {
        markLastWrite(0L, -1L, -1L, "", "", false);
    }

    private void clearLastWriteIf(String key) {
        if (key.equals(lastWriteKey)) clearLastWrite();
    }

    private boolean recentWrite(long now) {
        return lastWriteAt > 0L
                && now - lastWriteAt <= UNDO_WINDOW_MS
                && lastWriteTurn == turnCounter - 1;
    }

    private boolean lastWriteKeyIsNote(String label) {
        return lastWriteKey.startsWith(Fact.NOTE_PREFIX);
    }

    // =====================================================================
    // Wording
    // =====================================================================

    /** The user's own words for the key, lowercased, without a leading "my". */
    static String labelFor(String keyPhrase) {
        String l = TextTools.lower(TextTools.trimPunctuation(keyPhrase));
        l = l.replaceFirst("^(my|the|our)\\s+", "");
        return l.isEmpty() ? KeyNormalizer.canonical(keyPhrase) : l;
    }

    static String spokenLabel(String label) {
        String l = TextTools.lower(label);
        if (l.startsWith("your ") || l.startsWith("that")) return l;
        return "your " + TextTools.flipPerson(l);
    }

    private static boolean pluralLabel(String label) {
        String last = TextTools.lower(label).replaceAll(".* ", "");
        return last.endsWith("s") && !last.endsWith("ss") && !last.endsWith("'s") && last.length() > 3;
    }

    private static String verb(String label) {
        return pluralLabel(label) ? "are" : "is";
    }

    private static String pastVerb(String label) {
        return pluralLabel(label) ? "were" : "was";
    }

    String displayValue(String key, String value) {
        String v = TextTools.capitalizeCalendarWords(TextTools.flipPerson(value));
        for (String w : KeyNormalizer.canonical(key).split(" ")) {
            if (PROPER_NOUN_KEY_WORDS.contains(w) || PROPER_NOUN_KEY_WORDS.contains(TextTools.stem(w))) {
                return TextTools.titleCase(v);
            }
        }
        return v;
    }

    private String sentence(String label, String value, String key) {
        return capitalize(spokenLabel(label)) + " " + verb(label) + " " + displayValue(key, value) + ".";
    }

    private String answerFor(Fact fact) {
        if (fact.isNote()) {
            return "You told me " + TextTools.capitalizeCalendarWords(TextTools.flipPerson(fact.value)) + ".";
        }
        return sentence(fact.label, fact.value, fact.key);
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return "";
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /**
     * A remembered place for directions or rides: "shiva temple" → the address
     * the user saved with "remember shiva temple is Ramalingeswara temple,
     * Kukatpally". Returns null when nothing clearly matches (the phrase is then
     * used as a normal map search). Protected items and notes are never used.
     */
    public synchronized Fact placeFor(String phrase) {
        String cleaned = TextTools.trimPunctuation(phrase).replaceFirst("(?i)^(?:the|my|our)\\s+", "");
        String key = KeyNormalizer.canonical(cleaned);
        if (key.isEmpty()) return null;

        for (String candidate : new String[]{key, key + " address", key + " location"}) {
            Fact f = store.activeFact(candidate);
            if (f != null && !f.isNote() && !SensitiveKeys.isSensitiveKey(f.key)) return f;
        }

        // Every word of the phrase must be in the saved key ("temple" alone must not pick
        // "shiva temple" over "hanuman temple" by accident: ties are refused).
        List<String> q = TextTools.contentTokens(key);
        if (q.isEmpty()) return null;
        Fact best = null;
        int bestExtra = Integer.MAX_VALUE;
        boolean tie = false;
        for (Fact f : store.activeFacts()) {
            if (f.isNote() || SensitiveKeys.isSensitiveKey(f.key)) continue;
            List<String> k = TextTools.contentTokens(f.key);
            if (!k.containsAll(q)) continue;
            int extra = k.size() - q.size();
            for (String w : k) {
                if (w.equals("address") || w.equals("location") || w.equals("place")) extra--;
            }
            if (extra < bestExtra) {
                best = f;
                bestExtra = extra;
                tie = false;
            } else if (extra == bestExtra) {
                tie = true;
            }
        }
        return tie ? null : best;
    }

    /** For UI: one line per fact, newest first. Protected values are masked. */
    public synchronized List<String> describeAll() {
        List<String> out = new ArrayList<>();
        for (Fact f : store.activeFacts()) {
            out.add(f.isNote() ? "Note: " + f.value : f.label + ": " + f.value);
        }
        for (ProtectedItem p : store.listProtected()) {
            out.add(p.label + ": ••••••");
        }
        return out;
    }
}
