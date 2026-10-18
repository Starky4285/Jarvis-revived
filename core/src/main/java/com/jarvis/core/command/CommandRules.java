package com.jarvis.core.command;

import com.jarvis.core.text.TextTools;
import com.jarvis.core.turn.LocalIntent;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic fast path: the common ways of asking for a phone task, matched
 * instantly without the model. Patterns are anchored to the whole utterance,
 * so a sentence that merely mentions "camera" or "train" is never executed.
 * Anything these rules miss can still be understood by {@link CommandTranslator}.
 */
public final class CommandRules {

    private static final String RULES = "rules";

    private static final Pattern LEAD = Pattern.compile(
            "(?i)^(?:(?:hey|hi|ok|okay)\\s+)?(?:jarvis[\\s,]+)?"
                    + "(?:(?:please|can you|could you|would you|will you|kindly)\\s+)?");
    private static final Pattern TAIL = Pattern.compile("(?i)(?:\\s+(?:please|for me|right now|now))+$");

    // A duration like "10s", "10 sec", "ten seconds", "a minute", "1.5 hours".
    private static final String NUM = "(?:\\d+(?:\\.\\d+)?|an?|one|two|three|four|five|six|seven|eight|nine|ten"
            + "|eleven|twelve|fifteen|twenty|thirty|forty|fifty|sixty|half an?)";
    private static final String DUR = NUM + "\\s*(?:hours?|hrs?|h|minutes?|mins?|m|seconds?|secs?|s)\\b";
    private static final Pattern DURATION_PART = Pattern.compile("(?i)(" + NUM + ")\\s*(hours?|hrs?|h|minutes?|mins?|m|seconds?|secs?|s)\\b");

    // ---- maps ----
    private static final Pattern DIRECTIONS = Pattern.compile(
            "(?i)^(?:open (?:google )?maps (?:and )?)?(?:(?:get|show|give|find|search|open)(?: me)?\\s+)?(?:the\\s+)?"
                    + "(?:directions?|route|way|path)\\s+(?:to|for|till|until)\\s+(.+)$");
    private static final Pattern NAVIGATE = Pattern.compile(
            "(?i)^(?:open (?:google )?maps (?:and )?)?(?:start\\s+)?(?:navigate|navigation|take me|drive me|guide me|lead me)"
                    + "\\s+(?:to|till)\\s+(.+)$");
    private static final Pattern HOW_TO_GET = Pattern.compile(
            "(?i)^how (?:do|can|should) i (?:get|go) to (.+)$|^how (?:do|can|should) i reach (.+)$");
    private static final Pattern NEAREST = Pattern.compile("(?i)^where(?:'s| is) the nearest (.+)$");

    // ---- rides, trains ----
    private static final Pattern RIDE = Pattern.compile(
            "(?i)^book\\s+(?:me\\s+)?(?:an?\\s+)?(rapido|uber|ola)?\\s*(bike|auto|cab|car|taxi|ride)?(?:\\s+ride)?"
                    + "(?:\\s+(?:on|in|using|via|from)\\s+(rapido|uber|ola))?\\s+(?:to|for|till)\\s+(.+)$");
    private static final Pattern TRAINS = Pattern.compile(
            "(?i)^(?:open where is my train (?:app )?(?:and )?)?(?:(?:find|search|show|check|look for|get)(?: me)?\\s+)?"
                    + "(?:the\\s+)?(?:available\\s+)?trains?(?:\\s+from\\s+(.+?))?\\s+(?:to|for|towards)\\s+(.+?)"
                    + "(?:\\s+from\\s+(.+))?$");

    // ---- files, storage ----
    private static final Pattern EXCEL_SHEET = Pattern.compile(
            "(?i)^open\\s+(excel|sheets|google sheets|wps)\\s+(?:and\\s+)?(?:view|show|open)\\s+(?:the\\s+)?"
                    + "(?:recently opened\\s+|last opened\\s+)?(sheet\\s*\\d+|[\\w ]+? sheet)\\s+(?:on|of|in|from)\\s+(?:the\\s+|my\\s+)?(.+?)"
                    + "(?:\\s+(?:data\\s+)?file)?$");
    private static final Pattern OPEN_FILE = Pattern.compile(
            "(?i)^(?:open|show|view)(?: me)?\\s+(?:the\\s+|my\\s+)?(.+?)\\s+(?:file|sheet|spreadsheet|workbook|document|doc|pdf)"
                    + "(?:\\s+in\\s+(excel|sheets|google sheets|wps|word|drive))?$");
    private static final Pattern OPEN_FILE_NAMED = Pattern.compile(
            "(?i)^(?:open|show|view)(?: me)?\\s+(?:the\\s+|my\\s+)?(?:excel file|excel sheet|spreadsheet|file|document|pdf)"
                    + "\\s+(?:named\\s+|called\\s+)?(.+)$");
    private static final Pattern STORAGE = Pattern.compile(
            "(?i)^(?:(?:analy[sz]e|check|scan|show|clean(?: up)?|free(?: up)?)(?: my| the)?(?: phone(?:'s)?)?\\s+(?:storage|space)"
                    + "|what(?:'s| is) (?:taking|using|eating)(?: up)?(?: my| the)?(?: phone(?:'s)?)? (?:storage|space)"
                    + "|which (?:files|apps) (?:are )?(?:taking|using)(?: up)?(?: (?:my|the))? (?:storage|space)"
                    + "|storage analy[sz]er)(?:\\s.*)?$");

    // ---- camera, audio ----
    private static final Pattern OPEN_CAMERA_PREFIX = Pattern.compile(
            "(?i)^open (?:the )?(?:front |back |selfie )?camera(?:\\s+(?:in|after)\\s+(" + DUR + "))?\\s+(?:and\\s+)?");
    private static final Pattern PHOTO = Pattern.compile(
            "(?i)^(?:take|click|capture|snap|shoot)\\s+(?:me\\s+)?(?:(an?|" + NUM + ")\\s+)?(?:continuous |burst |quick )?"
                    + "(?:photos?|pictures?|pics?|shots?|snaps?|selfies?|images?)(?:\\s+(?:continuously|in a row|quickly))?"
                    + "(?:\\s+(?:in|after)\\s+(" + DUR + "))?$");
    private static final Pattern VIDEO = Pattern.compile(
            "(?i)^(?:record|take|shoot|capture|start(?: recording)?)\\s+(?:an?\\s+)?(?:(" + DUR + ")\\s+)?(?:long\\s+)?videos?"
                    + "(?:\\s+(?:for|of)\\s+(" + DUR + "))?(?:\\s+(?:in|after)\\s+(" + DUR + "))?$");
    private static final Pattern RECORD_AUDIO = Pattern.compile(
            "(?i)^(?:start|begin)\\s+(?:an?\\s+)?(?:new\\s+)?(?:voice\\s+|audio\\s+|sound\\s+)?recording$"
                    + "|^record\\s+(?:my\\s+)?(?:voice|audio|sound|this)(?:\\s+now)?$");
    private static final Pattern FIND_RECORDINGS = Pattern.compile(
            "(?i)^(?:find|search|show|list|get)(?: me)?(?: (?:all|my|the))*\\s+(?:voice\\s+|audio\\s+|call\\s+)?recordings?"
                    + "(?:\\s+(?:over|longer than|more than|above|at least|of more than)\\s+(" + NUM + ")\\s*(?:min|mins|minutes))?"
                    + "(?:\\s+(?:about|with|on)\\s+(.+))?$");

    // ---- places ----
    private static final Pattern SAVE_HERE = Pattern.compile(
            "(?i)^(?:save|remember|mark|store)\\s+(?:this|current|my current|the current|my)\\s+(?:place|location|spot)\\s+as\\s+(.+)$");

    // ---- messages, mail, photos ----
    private static final Pattern MESSAGE = Pattern.compile(
            "(?i)^(?:send|write)\\s+(?:an?\\s+)?(?:(whatsapp|sms|text)\\s+)?message\\s+to\\s+(.+?)"
                    + "(?:\\s+(?:saying|that says|that)\\s+(.+))?$");
    private static final Pattern MESSAGE_SHORT = Pattern.compile(
            "(?i)^(whatsapp|text|sms|message)\\s+(.+?)\\s+(?:saying|that says)\\s+(.+)$");
    private static final Pattern MAIL_SUMMARY = Pattern.compile(
            "(?i)^(?:summari[sz]e|read(?: out)?|check|show)(?: me)?(?: all)?\\s+(?:my\\s+)?(new\\s+|unread\\s+|latest\\s+|today'?s\\s+)?"
                    + "(?:e-?mails?|mails?|gmail|inbox)(?:\\s+(?:from\\s+)?today)?$");
    private static final Pattern MAIL_SEARCH = Pattern.compile(
            "(?i)^(?:search|find)(?: in)?\\s+(?:my\\s+)?(?:e-?mails?|mails?|gmail|inbox)\\s+(?:for|about|from)\\s+(.+)$");
    private static final Pattern PHOTOS = Pattern.compile(
            "(?i)^(?:find|show|search(?: for)?|get)(?: me)?\\s+(?:my\\s+|the\\s+)?(oldest|first|earliest|newest|latest|recent|last)?\\s*"
                    + "(?:pics?|pictures?|photos?|images?|selfies?)(?:\\s+available)?"
                    + "(?:\\s+(?:on|in|from)\\s+(?:my\\s+|the\\s+)?gallery)?"
                    + "(?:\\s+(?:with|of)\\s+(?:me and\\s+)?(.+?))?"
                    + "(?:\\s+(?:on|in|from)\\s+(?:my\\s+|the\\s+)?gallery)?$");

    private static final Pattern PLACE_TRAILERS = Pattern.compile(
            "(?i)\\s+(?:from\\s+(?:my\\s+|the\\s+)?(?:current\\s+)?(?:location|position|place)|from here|right now)$"
                    + "|\\s+(?:that|which|where)\\s+i\\s+(?:always|usually|often|regularly|daily)?\\s*(?:visit|go(?:\\s+to)?)$"
                    + "|\\s+i\\s+(?:always|usually|often)\\s+(?:visit|go(?:\\s+to)?)$");
    private static final Pattern MODE_TRAILER = Pattern.compile(
            "(?i)\\s+(?:by|on|using|via|in)\\s+(?:a\\s+|my\\s+)?(foot|walk|walking|bike|scooter|scooty|two wheeler|two-wheeler"
                    + "|motorbike|motorcycle|car|bus|train|metro|public transport|transit)$"
                    + "|\\s+(walking|on foot|driving)$");

    private CommandRules() {
    }

    /** Returns the command for a recognized phone task, or null. */
    public static Command parse(String utterance) {
        String t = normalize(utterance);
        if (t.isEmpty()) return null;

        Command c = maps(t);
        if (c == null) c = ridesAndTrains(t);
        if (c == null) c = files(t);
        if (c == null) c = cameraAndAudio(t);
        if (c == null) c = placesMessagesMail(t);
        if (c == null) c = fromLocalIntent(utterance);
        return c;
    }

    static String normalize(String utterance) {
        String t = TextTools.trimPunctuation(utterance);
        t = TextTools.trimPunctuation(LEAD.matcher(t).replaceFirst(""));
        t = TAIL.matcher(t).replaceFirst("");
        return TextTools.trimPunctuation(t);
    }

    // ------------------------------------------------------------------ maps

    private static Command maps(String t) {
        Matcher m = NAVIGATE.matcher(t);
        boolean navigate = m.matches();
        if (!navigate) {
            m = DIRECTIONS.matcher(t);
            if (!m.matches()) {
                m = HOW_TO_GET.matcher(t);
                if (!m.matches()) {
                    m = NEAREST.matcher(t);
                    if (!m.matches()) return null;
                }
            }
        }
        String raw = m.group(1) != null ? m.group(1) : m.groupCount() >= 2 ? m.group(2) : null;
        if (raw == null) return null;
        if (m.pattern() == NEAREST) raw = raw + " near me";

        String mode = null;
        String place = cleanPlace(raw);
        Matcher mm = MODE_TRAILER.matcher(place);
        if (mm.find()) {
            String word = mm.group(1) != null ? mm.group(1) : mm.group(2);
            mode = travelMode(word);
            place = cleanPlace(place.substring(0, mm.start()));
        }
        if (place.isEmpty()) return null;

        Command.Builder b = new Command.Builder(CommandCatalog.get("maps.directions"), RULES)
                .put("place", place)
                .put("navigate", navigate ? "yes" : "no");
        if (mode != null) b.put("mode", mode);
        return b.build();
    }

    static String travelMode(String word) {
        String w = TextTools.lower(word);
        if (w.contains("foot") || w.startsWith("walk")) return "walking";
        if (w.contains("bike") || w.contains("scoot") || w.contains("wheeler") || w.startsWith("motor")) return "two_wheeler";
        if (w.equals("car") || w.startsWith("driv")) return "driving";
        if (w.equals("bus") || w.equals("train") || w.equals("metro") || w.contains("transit") || w.contains("public")) {
            return "transit";
        }
        return null;
    }

    /** Drops "from my location", "that I always visit", leading "the" and edge punctuation. */
    static String cleanPlace(String raw) {
        String p = TextTools.trimPunctuation(raw);
        String prev;
        do {
            prev = p;
            p = TextTools.trimPunctuation(PLACE_TRAILERS.matcher(p).replaceFirst(""));
        } while (!p.equals(prev));
        return p;
    }

    // ------------------------------------------------------- rides and trains

    private static Command ridesAndTrains(String t) {
        Matcher m = RIDE.matcher(t);
        if (m.matches()) {
            String app = m.group(1) != null ? m.group(1) : m.group(3);
            String vehicle = m.group(2);
            if (app == null && vehicle == null) return null; // "book a table for two" is not a ride
            String place = cleanPlace(m.group(4));
            if (place.isEmpty()) return null;
            Command.Builder b = new Command.Builder(CommandCatalog.get("ride.book"), RULES).put("place", place);
            if (app != null) b.put("app", TextTools.lower(app));
            String v = vehicle == null ? null : TextTools.lower(vehicle);
            if ("car".equals(v) || "taxi".equals(v)) v = "cab";
            if (v != null && !v.equals("ride")) b.put("vehicle", v);
            return b.build();
        }

        m = TRAINS.matcher(t);
        if (m.matches()) {
            String from = m.group(1) != null ? m.group(1) : m.group(3);
            String to = cleanPlace(m.group(2));
            if (to.isEmpty()) return null;
            Command.Builder b = new Command.Builder(CommandCatalog.get("train.search"), RULES).put("to", to);
            if (from != null) b.put("from", cleanPlace(from));
            return b.build();
        }
        return null;
    }

    // ----------------------------------------------------------------- files

    private static Command files(String t) {
        Matcher m = EXCEL_SHEET.matcher(t);
        if (m.matches()) {
            return new Command.Builder(CommandCatalog.get("files.open"), RULES)
                    .put("name", TextTools.trimPunctuation(m.group(3)))
                    .put("app", TextTools.lower(m.group(1)))
                    .put("sheet", TextTools.trimPunctuation(m.group(2)))
                    .build();
        }
        m = OPEN_FILE.matcher(t);
        if (m.matches()) {
            Command.Builder b = new Command.Builder(CommandCatalog.get("files.open"), RULES)
                    .put("name", TextTools.trimPunctuation(m.group(1)));
            if (m.group(2) != null) b.put("app", TextTools.lower(m.group(2)));
            return b.build();
        }
        m = OPEN_FILE_NAMED.matcher(t);
        if (m.matches()) {
            return new Command.Builder(CommandCatalog.get("files.open"), RULES)
                    .put("name", TextTools.trimPunctuation(m.group(1)))
                    .build();
        }
        if (STORAGE.matcher(t).matches()) {
            return new Command.Builder(CommandCatalog.get("storage.analyze"), RULES).build();
        }
        return null;
    }

    // ------------------------------------------------------- camera and audio

    private static Command cameraAndAudio(String t) {
        int prefixDelay = -1;
        String rest = t;
        boolean cameraPrefix = false;
        Matcher pm = OPEN_CAMERA_PREFIX.matcher(t);
        if (pm.find()) {
            cameraPrefix = true;
            if (pm.group(1) != null) prefixDelay = durationSeconds(pm.group(1));
            rest = t.substring(pm.end());
        }

        Matcher m = PHOTO.matcher(rest);
        if (m.matches()) {
            int count = m.group(1) == null ? 1 : number(m.group(1), 1);
            int delay = m.group(2) != null ? durationSeconds(m.group(2)) : Math.max(0, prefixDelay);
            return new Command.Builder(CommandCatalog.get("camera.photo"), RULES)
                    .put("count", Math.max(1, Math.min(20, count)))
                    .put("delay_seconds", Math.min(600, delay))
                    .build();
        }

        m = VIDEO.matcher(rest);
        if (m.matches()) {
            String lengthText = m.group(1) != null ? m.group(1) : m.group(2);
            int delay = m.group(3) != null ? durationSeconds(m.group(3)) : Math.max(0, prefixDelay);
            Command.Builder b = new Command.Builder(CommandCatalog.get("camera.video"), RULES)
                    .put("delay_seconds", Math.min(600, delay));
            if (lengthText != null) b.put("duration_seconds", Math.max(1, Math.min(3600, durationSeconds(lengthText))));
            return b.build();
        }

        if (cameraPrefix) return null; // "open camera and <something else>": leave it to the other rules

        if (RECORD_AUDIO.matcher(t).matches()) {
            return new Command.Builder(CommandCatalog.get("recorder.start"), RULES).build();
        }

        m = FIND_RECORDINGS.matcher(t);
        if (m.matches()) {
            Command.Builder b = new Command.Builder(CommandCatalog.get("recordings.find"), RULES);
            if (m.group(1) != null) b.put("min_minutes", number(m.group(1), 0));
            if (m.group(2) != null) b.put("query", TextTools.trimPunctuation(m.group(2)));
            return b.build();
        }
        return null;
    }

    // -------------------------------------------------- places, messages, mail

    private static Command placesMessagesMail(String t) {
        Matcher m = SAVE_HERE.matcher(t);
        if (m.matches()) {
            return new Command.Builder(CommandCatalog.get("place.save_here"), RULES)
                    .put("name", cleanPlace(m.group(1)))
                    .build();
        }

        m = MESSAGE.matcher(t);
        if (m.matches()) {
            Command.Builder b = new Command.Builder(CommandCatalog.get("messages.compose"), RULES)
                    .put("who", TextTools.trimPunctuation(m.group(2)));
            if (m.group(1) != null) b.put("app", TextTools.lower(m.group(1)).equals("whatsapp") ? "whatsapp" : "sms");
            if (m.group(3) != null) b.put("text", TextTools.trimPunctuation(m.group(3)));
            return b.build();
        }
        m = MESSAGE_SHORT.matcher(t);
        if (m.matches()) {
            return new Command.Builder(CommandCatalog.get("messages.compose"), RULES)
                    .put("app", TextTools.lower(m.group(1)).equals("whatsapp") ? "whatsapp" : "sms")
                    .put("who", TextTools.trimPunctuation(m.group(2)))
                    .put("text", TextTools.trimPunctuation(m.group(3)))
                    .build();
        }

        m = MAIL_SUMMARY.matcher(t);
        if (m.matches()) {
            String which = m.group(1) == null ? "" : TextTools.lower(m.group(1)).trim();
            String scope = which.startsWith("today") || TextTools.lower(t).endsWith("today") ? "today"
                    : which.isEmpty() ? null : "unread";
            Command.Builder b = new Command.Builder(CommandCatalog.get("mail.summarize"), RULES);
            if (scope != null) b.put("scope", scope);
            return b.build();
        }
        m = MAIL_SEARCH.matcher(t);
        if (m.matches()) {
            return new Command.Builder(CommandCatalog.get("mail.search"), RULES)
                    .put("query", TextTools.trimPunctuation(m.group(1)))
                    .build();
        }

        m = PHOTOS.matcher(t);
        if (m.matches()) {
            Command.Builder b = new Command.Builder(CommandCatalog.get("photos.find"), RULES);
            if (m.group(1) != null) {
                String o = TextTools.lower(m.group(1));
                b.put("order", o.equals("oldest") || o.equals("first") || o.equals("earliest") ? "oldest" : "newest");
            }
            if (m.group(2) != null) {
                b.put("person", TextTools.trimPunctuation(m.group(2)).replaceFirst("(?i)^my\\s+", ""));
            }
            return b.build();
        }
        return null;
    }

    // ------------------------------------------------- existing device intents

    private static Command fromLocalIntent(String utterance) {
        LocalIntent li = LocalIntent.parse(utterance);
        switch (li.type) {
            case CALL:
                return new Command.Builder(CommandCatalog.get("phone.call"), RULES).put("who", li.target).build();
            case OPEN_APP:
                return new Command.Builder(CommandCatalog.get("app.open"), RULES).put("app", li.target).build();
            case ALARM:
                return new Command.Builder(CommandCatalog.get("alarm.set"), RULES)
                        .put("hour", li.hour).put("minute", li.minute).build();
            case TIMER:
                return new Command.Builder(CommandCatalog.get("timer.set"), RULES)
                        .put("duration", li.seconds + " seconds").build();
            case TIME:
                return new Command.Builder(CommandCatalog.get("info.time"), RULES).build();
            case DATE:
                return new Command.Builder(CommandCatalog.get("info.date"), RULES).build();
            case BATTERY:
                return new Command.Builder(CommandCatalog.get("info.battery"), RULES).build();
            default:
                return null;
        }
    }

    // ---------------------------------------------------------------- numbers

    /** "10s", "ten seconds", "a minute", "1.5 hours", "2 minutes 30 seconds" → seconds (0 if none). */
    public static int durationSeconds(String text) {
        if (text == null) return 0;
        Matcher m = DURATION_PART.matcher(text);
        double total = 0;
        while (m.find()) {
            double n = numberValue(m.group(1));
            String unit = TextTools.lower(m.group(2));
            double mult = unit.startsWith("h") ? 3600 : unit.startsWith("m") ? 60 : 1;
            total += n * mult;
        }
        return (int) Math.round(total);
    }

    static int number(String word, int fallback) {
        double v = numberValue(word);
        return v < 0 ? fallback : (int) Math.round(v);
    }

    private static double numberValue(String word) {
        String w = TextTools.lower(TextTools.trimPunctuation(word));
        switch (w) {
            case "a": case "an": case "one": return 1;
            case "two": return 2;
            case "three": return 3;
            case "four": return 4;
            case "five": return 5;
            case "six": return 6;
            case "seven": return 7;
            case "eight": return 8;
            case "nine": return 9;
            case "ten": return 10;
            case "eleven": return 11;
            case "twelve": return 12;
            case "fifteen": return 15;
            case "twenty": return 20;
            case "thirty": return 30;
            case "forty": return 40;
            case "fifty": return 50;
            case "sixty": return 60;
            case "half a": case "half an": return 0.5;
            default:
                try {
                    return Double.parseDouble(w);
                } catch (NumberFormatException e) {
                    return -1;
                }
        }
    }
}
