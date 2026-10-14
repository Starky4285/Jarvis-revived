package com.jarvis.core.turn;

import com.jarvis.core.llm.AnswerGuard;
import com.jarvis.core.llm.ChatModel;
import com.jarvis.core.llm.ConversationWindow;
import com.jarvis.core.llm.PromptBuilder;
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

/**
 * Decides who answers one user turn. Fixed order, no model involved until the
 * last step:
 *
 *   1. stop / cancel                → STOP   (app silences TTS, aborts the model)
 *   2. device command (strict)      → ACTION / LOCAL
 *   3. memory (save/recall/forget)  → MEMORY
 *   4. on-device model              → MODEL  (memory facts + recent turns as context)
 *
 * The model can only talk. Nothing it outputs is ever executed.
 * Call {@link #handle} from ONE background thread.
 */
public final class TurnRouter {

    public enum Route { NONE, STOP, LOCAL, ACTION, MEMORY, MODEL, FALLBACK }

    public static final class Result {
        public final Route route;
        public final String reply;
        /** True when the reply was already delivered sentence-by-sentence through the partial listener. */
        public final boolean streamed;

        Result(Route route, String reply, boolean streamed) {
            this.route = route;
            this.reply = reply == null ? "" : reply;
            this.streamed = streamed;
        }

        @Override
        public String toString() {
            return route + (streamed ? " (streamed)" : "") + ": " + reply;
        }
    }

    /** ~2 short sentences. Each extra token costs ~0.2 s on a Galaxy A15. */
    static final int MODEL_MAX_TOKENS = 72;
    static final int MODEL_MAX_SENTENCES = 2;
    static final int EXTRACT_MAX_TOKENS = 64;

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
                    ? model.generateJson(MemoryExtractor.buildPrompt(sentence), EXTRACT_MAX_TOKENS)
                    : "");
        }
    }

    public Result handle(String utterance, ChatModel.PartialListener partials) {
        String text = TextTools.trimPunctuation(utterance);
        if (text.isEmpty()) return new Result(Route.NONE, "", false);

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
        for (String part : parts) {
            Result r = single(part, null);
            if (!r.reply.isEmpty()) replies.add(r.reply);
            if (route == Route.NONE || r.route == Route.MODEL) route = r.route;
        }
        return new Result(route, String.join(" ", replies), false);
    }

    /** Clears the short-term conversation (e.g. when the user starts a new session). */
    public void resetConversation() {
        window.clear();
    }

    private Result single(String text, ChatModel.PartialListener partials) {
        Result local = local(text);
        if (local != null) {
            memory.recordUserTurn(text);
            memory.recordAssistantTurn(local.reply);
            return local;
        }

        MemoryResult mem = memory.handle(text);
        if (mem.handled()) {
            memory.recordAssistantTurn(mem.reply);
            // Not added to the model's conversation window: memory already holds it,
            // and every extra history token makes the next model answer slower.
            return new Result(Route.MEMORY, mem.reply, false);
        }

        Result answer = askModel(text, partials);
        memory.recordAssistantTurn(answer.reply);
        if (answer.route == Route.MODEL) window.add(text, answer.reply);
        return answer;
    }

    private Result local(String text) {
        LocalIntent intent = LocalIntent.parse(text);
        ZonedDateTime now = Instant.ofEpochMilli(clock.getAsLong()).atZone(zone);
        switch (intent.type) {
            case TIME:
                return new Result(Route.LOCAL, "It's " + TIME_FMT.format(now) + ".", false);
            case DATE:
                return new Result(Route.LOCAL, "Today is " + DATE_FMT.format(now) + ".", false);
            case BATTERY:
                return new Result(Route.ACTION, actions.batteryStatus(), false);
            case CALL:
                return new Result(Route.ACTION, actions.call(intent.target), false);
            case OPEN_APP:
                return new Result(Route.ACTION, actions.openApp(intent.target), false);
            case ALARM:
                return new Result(Route.ACTION, actions.setAlarm(intent.hour, intent.minute), false);
            case TIMER:
                return new Result(Route.ACTION, actions.setTimer(intent.seconds), false);
            default:
                return null;
        }
    }

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

        String prompt = PromptBuilder.chat(text, memoryContext, window.recent(), assistantName, nowText);
        String answer = AnswerGuard.clean(model.generate(prompt, MODEL_MAX_TOKENS, MODEL_MAX_SENTENCES, relay));
        if (AnswerGuard.isGood(answer)) {
            return new Result(Route.MODEL, answer, streamed.get());
        }
        if (streamed.get()) {
            // Something was already spoken; don't talk over it with a retry.
            return new Result(Route.MODEL, answer, true);
        }

        // One quiet retry without history, asking for a single sentence.
        String retryPrompt = PromptBuilder.chat(text + " (Answer in one short sentence.)",
                memoryContext, null, assistantName, nowText);
        String retry = AnswerGuard.clean(model.generate(retryPrompt, 40, 1, null));
        if (AnswerGuard.isGood(retry)) {
            return new Result(Route.MODEL, retry, false);
        }
        return new Result(Route.FALLBACK, "Sorry, I couldn't come up with a good answer to that.", false);
    }
}
