package com.jarvis.core.turn;

import com.jarvis.core.command.Command;
import com.jarvis.core.command.CommandRules;
import com.jarvis.core.command.CommandTranslator;
import com.jarvis.core.llm.AnswerGuard;
import com.jarvis.core.llm.ChatModel;
import com.jarvis.core.llm.ConversationWindow;
import com.jarvis.core.llm.PromptBuilder;
import com.jarvis.core.memory.Fact;
import com.jarvis.core.memory.MemoryEngine;
import com.jarvis.core.memory.MemoryExtractor;
import com.jarvis.core.memory.MemoryResult;
import com.jarvis.core.memory.PromptMemory;
import com.jarvis.core.text.TextTools;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Decides who answers one user turn. Fixed order:
 *
 *   0. an open "… — yes or no?" question  → run or cancel that action
 *   1. stop / cancel                       → STOP   (app silences TTS, aborts the model)
 *   2. phone task, rules (instant)         → ACTION / LOCAL / CONFIRM / PLANNED
 *   3. memory (save / recall / forget)     → MEMORY
 *   4. phone task, model as translator     → same as 2 (grammar-forced, validated)
 *   5. on-device model chat                → MODEL
 *
 * The model never executes anything: at step 4 it only names a catalog command,
 * which is validated and then run by this class through {@link DeviceActions};
 * calls (and later sending, deleting, booking) wait for the user's "yes".
 * Call {@link #handle} from ONE background thread.
 */
public final class TurnRouter {

    public enum Route { NONE, STOP, LOCAL, ACTION, CONFIRM, PLANNED, MEMORY, MODEL, FALLBACK }

    public static final class Result {
        public final Route route;
        public final String reply;
        /** True when the reply was already delivered sentence-by-sentence through the partial listener. */
        public final boolean streamed;
        /** True when Jarvis just asked "… — yes or no?" (the screen shows Confirm / Cancel). */
        public final boolean awaitingConfirmation;
        /** The understood command's name and source ("maps.directions [rules]"), or "". Never the user's words. */
        public final String command;

        Result(Route route, String reply, boolean streamed) {
            this(route, reply, streamed, false, "");
        }

        Result(Route route, String reply, boolean streamed, boolean awaitingConfirmation, String command) {
            this.route = route;
            this.reply = reply == null ? "" : reply;
            this.streamed = streamed;
            this.awaitingConfirmation = awaitingConfirmation;
            this.command = command == null ? "" : command;
        }

        @Override
        public String toString() {
            return route + (streamed ? " (streamed)" : "") + (command.isEmpty() ? "" : " <" + command + ">")
                    + ": " + reply;
        }
    }

    /** ~2 short sentences. Each extra token costs ~0.2 s on a Galaxy A15. */
    static final int MODEL_MAX_TOKENS = 72;
    static final int MODEL_MAX_SENTENCES = 2;
    static final int EXTRACT_MAX_TOKENS = 64;
    /** A "yes" more than this long after the question no longer counts. */
    static final long CONFIRM_WINDOW_MS = 60_000L;

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH);
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter NOW_FMT = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy, h:mm a", Locale.ENGLISH);

    private final MemoryEngine memory;
    private final DeviceActions actions;
    private final ChatModel model;
    private final LongSupplier clock;
    private final ZoneId zone;
    private final String assistantName;
    private final ConversationWindow window = new ConversationWindow(3, 900);

    /** An action waiting for "yes". Only touched on the brain thread. */
    private Pending pending;

    private static final class Pending {
        final Supplier<String> action;
        final String cancelReply;
        final String command;
        final long expiresAt;

        Pending(Supplier<String> action, String cancelReply, String command, long expiresAt) {
            this.action = action;
            this.cancelReply = cancelReply;
            this.command = command;
            this.expiresAt = expiresAt;
        }
    }

    public TurnRouter(MemoryEngine memory, DeviceActions actions, ChatModel model,
                      LongSupplier clock, ZoneId zone, String assistantName) {
        this.memory = memory;
        this.actions = actions;
        this.model = model;
        this.clock = clock;
        this.zone = zone;
        this.assistantName = assistantName;

        if (model != null) {
            memory.setExtractor(sentence -> model.state() == ChatModel.State.READY
                    ? model.generateJson(MemoryExtractor.buildPrompt(sentence), MemoryExtractor.GRAMMAR,
                    EXTRACT_MAX_TOKENS)
                    : "");
        }
    }

    public Result handle(String utterance, ChatModel.PartialListener partials) {
        String text = TextTools.trimPunctuation(utterance);
        if (text.isEmpty()) return new Result(Route.NONE, "", false);

        if (pending != null) {
            Result answered = resolvePending(text);
            if (answered != null) return answered;
        }

        if (CommandShapeRules.isStopCommand(text)) {
            memory.cancelPendingConfirmation();
            if (model != null) model.abort();
            return new Result(Route.STOP, "", false);
        }

        List<String> parts = CommandShapeRules.splitCompound(text);
        if (parts.size() <= 1) {
            return single(text, partials);
        }

        // "open camera and call Ravi": run each part, speak one combined reply.
        List<String> replies = new ArrayList<>();
        Route route = Route.NONE;
        boolean confirm = false;
        for (String part : parts) {
            Result r = single(part, null);
            if (!r.reply.isEmpty()) replies.add(r.reply);
            if (route == Route.NONE || r.route == Route.MODEL) route = r.route;
            confirm |= r.awaitingConfirmation;
        }
        return new Result(confirm ? Route.CONFIRM : route, String.join(" ", replies), false, confirm, "");
    }

    /** Clears the short-term conversation (e.g. when the user starts a new session). */
    public void resetConversation() {
        window.clear();
        pending = null;
    }

    // ------------------------------------------------------------------ turn

    private Result single(String text, ChatModel.PartialListener partials) {
        Command rule = CommandRules.parse(text);
        if (rule != null) {
            memory.recordUserTurn(text);
            Result r = run(rule);
            memory.recordAssistantTurn(r.reply);
            return r;
        }

        MemoryResult mem = memory.handle(text);
        if (mem.handled()) {
            memory.recordAssistantTurn(mem.reply);
            // Not added to the model's conversation window: memory already holds it,
            // and every extra history token makes the next model answer slower.
            return new Result(Route.MEMORY, mem.reply, false);
        }

        Command translated = translate(text);
        if (translated != null) {
            Result r = run(translated);
            memory.recordAssistantTurn(r.reply);
            return r;
        }

        Result answer = askModel(text, partials);
        memory.recordAssistantTurn(answer.reply);
        if (answer.route == Route.MODEL) window.add(text, answer.reply);
        return answer;
    }

    private Command translate(String text) {
        if (model == null || model.state() != ChatModel.State.READY) return null;
        if (!CommandTranslator.looksLikeCommand(text)) return null;
        String out = model.generateJson(CommandTranslator.prompt(text), CommandTranslator.grammar(),
                CommandTranslator.MAX_TOKENS);
        return CommandTranslator.parse(out, text);
    }

    // --------------------------------------------------------------- actions

    private Result run(Command cmd) {
        String tag = cmd.tag(); // name only: the app logs this, and must never log what the user said
        if (!cmd.spec.isAvailable()) {
            return new Result(Route.PLANNED, cmd.spec.plannedReply, false, false, tag);
        }
        ZonedDateTime now = Instant.ofEpochMilli(clock.getAsLong()).atZone(zone);
        switch (cmd.name()) {
            case "info.time":
                return new Result(Route.LOCAL, "It's " + TIME_FMT.format(now) + ".", false, false, tag);
            case "info.date":
                return new Result(Route.LOCAL, "Today is " + DATE_FMT.format(now) + ".", false, false, tag);
            case "info.battery":
                return new Result(Route.ACTION, actions.batteryStatus(), false, false, tag);
            case "app.open":
                return new Result(Route.ACTION, actions.openApp(cmd.text("app")), false, false, tag);
            case "alarm.set":
                return new Result(Route.ACTION, actions.setAlarm(cmd.number("hour", 0), cmd.number("minute", 0)),
                        false, false, tag);
            case "timer.set": {
                int secs = CommandRules.durationSeconds(cmd.text("duration"));
                if (secs <= 0) {
                    return new Result(Route.ACTION,
                            "I didn't catch how long. Say, for example: set a timer for 10 minutes.", false, false, tag);
                }
                return new Result(Route.ACTION, actions.setTimer(secs), false, false, tag);
            }
            case "maps.directions":
                return directions(cmd, tag);
            case "phone.call":
                return askBeforeCalling(cmd, tag);
            default:
                return new Result(Route.PLANNED, "I can't do that yet.", false, false, tag);
        }
    }

    private Result directions(Command cmd, String tag) {
        String place = cmd.text("place");
        Fact saved = memory.placeFor(place);
        String query = saved != null ? saved.value : place;
        String spoken = saved != null ? saved.label : place;
        String reply = actions.directions(query, spoken, cmd.text("mode"), "yes".equals(cmd.text("navigate")));
        return new Result(Route.ACTION, reply, false, false, tag);
    }

    private Result askBeforeCalling(Command cmd, String tag) {
        String who = cmd.text("who");
        CallTarget target = actions.findCallTarget(who);
        switch (target.status) {
            case NEEDS_PERMISSION:
                return new Result(Route.ACTION, "I need contacts permission to find " + who
                        + ". You can allow it from Jarvis' settings.", false, false, tag);
            case NOT_FOUND:
                return new Result(Route.ACTION, "I couldn't find " + who + " in your contacts.", false, false, tag);
            default:
                pending = new Pending(() -> actions.call(target), "Okay, I won't call " + target.label + ".", tag,
                        clock.getAsLong() + CONFIRM_WINDOW_MS);
                return new Result(Route.CONFIRM, "Call " + target.label + "? Say yes or no.", false, true, tag);
        }
    }

    /** Returns null when the text is not an answer to the open question (it is then handled normally). */
    private Result resolvePending(String text) {
        Pending p = pending;
        pending = null;
        if (clock.getAsLong() > p.expiresAt) return null;

        if (YesNo.isYes(text)) {
            memory.recordUserTurn(text);
            String reply = p.action.get();
            memory.recordAssistantTurn(reply);
            return new Result(Route.ACTION, reply, false, false, p.command);
        }
        if (YesNo.isNo(text) || CommandShapeRules.isStopCommand(text)) {
            memory.recordUserTurn(text);
            memory.recordAssistantTurn(p.cancelReply);
            return new Result(Route.ACTION, p.cancelReply, false, false, p.command);
        }
        return null;
    }

    // ------------------------------------------------------------------ chat

    private Result askModel(String text, ChatModel.PartialListener partials) {
        if (model == null || model.state() == ChatModel.State.NO_MODEL) {
            return new Result(Route.FALLBACK,
                    "I don't have a language model yet. Open Jarvis and tap Import model to add one.", false);
        }
        if (model.state() == ChatModel.State.LOADING) {
            return new Result(Route.FALLBACK, "I'm still waking up. Ask me again in a few seconds.", false);
        }
        if (model.state() == ChatModel.State.FAILED) {
            return new Result(Route.FALLBACK,
                    "My language model failed to load. Open Jarvis to check the model file.", false);
        }

        String nowText = NOW_FMT.format(Instant.ofEpochMilli(clock.getAsLong()).atZone(zone));
        String memoryContext = PromptMemory.contextFor(text, memory.store(), PromptMemory.DEFAULT_BUDGET_CHARS);

        final AtomicBoolean streamed = new AtomicBoolean(false);
        ChatModel.PartialListener relay = partials == null ? null : chunk -> {
            String clean = AnswerGuard.cleanPartial(chunk);
            if (!clean.isEmpty()) {
                streamed.set(true);
                partials.onPartial(clean);
            }
        };

        String answer = AnswerGuard.clean(model.generate(
                PromptBuilder.chat(text, memoryContext, window.recent(), assistantName, nowText),
                MODEL_MAX_TOKENS, MODEL_MAX_SENTENCES, relay));
        if (AnswerGuard.isGood(answer)) {
            return new Result(Route.MODEL, answer, streamed.get());
        }
        if (streamed.get()) {
            // Something was already spoken; don't talk over it with a retry.
            return new Result(Route.MODEL, answer, true);
        }

        // One quiet retry without history, asking for a single sentence.
        String retry = AnswerGuard.clean(model.generate(
                PromptBuilder.chat(text + " (Answer in one short sentence.)", memoryContext, null, assistantName, nowText),
                40, 1, null));
        if (AnswerGuard.isGood(retry)) {
            return new Result(Route.MODEL, retry, false);
        }
        return new Result(Route.FALLBACK, "Sorry, I couldn't come up with a good answer to that.", false);
    }

    /** Visible for tests: is a "yes or no" question open right now? */
    boolean hasPendingConfirmation() {
        return pending != null && clock.getAsLong() <= pending.expiresAt;
    }
}
