package com.jarvis.revived.assistant;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.jarvis.core.command.CommandTranslator;
import com.jarvis.core.llm.ChatModel;
import com.jarvis.core.llm.PromptBuilder;
import com.jarvis.core.memory.DeferredMemoryStore;
import com.jarvis.core.memory.MemoryEngine;
import com.jarvis.core.turn.TurnRouter;
import com.jarvis.revived.actions.ActionLauncher;
import com.jarvis.revived.actions.AndroidActions;
import com.jarvis.revived.llm.LlamaModel;
import com.jarvis.revived.llm.ModelFiles;
import com.jarvis.revived.memory.MemoryVault;
import com.jarvis.revived.speech.SpeechInput;
import com.jarvis.revived.speech.SpeechOutput;

import java.time.ZoneId;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The conversation loop: listen → think (background) → speak → (listen again
 * when hands-free). Owns speech in/out, the model and memory.
 *
 * Public methods are called on the main thread. All "thinking" (memory,
 * model, actions) runs on ONE background thread, so the UI never blocks and
 * turns are processed strictly in order.
 */
public final class AssistantController {

    private static final String TAG = "Assistant";
    /** Indian English recognizes Indian names and accents better than en-US. */
    private static final String LANGUAGE = "en-IN";
    private static final String ASSISTANT_NAME = "Jarvis";

    public enum Status { IDLE, LISTENING, THINKING, SPEAKING }

    public interface Listener {
        void onStatus(Status status, String detail);

        void onTranscript(boolean fromUser, String text);

        void onModelState(ChatModel.State state, String detail);

        /** Jarvis' reply so far while the model is still writing it (replaced by the final transcript). */
        default void onReplyProgress(String textSoFar) {
        }

        /** Jarvis asked "… — yes or no?" (show Confirm / Cancel) or the question is over. */
        default void onAwaitingConfirmation(boolean waiting) {
        }

        /** No on-device speech recognizer and online speech not allowed: offer typing / the opt-in. */
        default void onSpeechUnavailable() {
        }
    }

    /** If the model hasn't finished after this long, stop it and say so. */
    private static final long THINKING_LIMIT_MS = 90_000L;
    private static final String PREFS = "jarvis_settings";
    private static final String KEY_THREADS = "llm_threads";
    private static final String KEY_ONLINE_SPEECH = "allow_online_speech";
    /** Must match TurnRouter.CONFIRM_WINDOW_MS: the buttons disappear when the question expires. */
    private static final long CONFIRM_WINDOW_MS = 60_000L;

    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService brain = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "jarvis-brain");
        t.setPriority(Thread.NORM_PRIORITY);
        return t;
    });
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    /** Recent conversation kept for the screen (main thread only). Not persisted: memory is the durable record. */
    private final java.util.ArrayDeque<Object[]> history = new java.util.ArrayDeque<>();
    private static final int HISTORY_LIMIT = 60;

    private final MemoryEngine memory;
    /** Null while the vault is still being opened; false only for the RAM-only fallback. */
    private volatile Boolean memoryEncrypted;
    private final LlamaModel model;
    private final TurnRouter router;
    private final SpeechOutput tts;
    private SpeechInput stt;

    private boolean awaitingConfirmation = false;
    private final Runnable confirmExpired = () -> setAwaitingConfirmation(false);
    private Status status = Status.IDLE;
    private boolean busy = false;
    private boolean handsFree = false;
    private int failedListens = 0;
    private volatile boolean uiVisible = false;
    private long thinkingSince = 0L;
    private final StringBuilder liveReply = new StringBuilder();
    private final Runnable thinkingTick = new Runnable() {
        @Override
        public void run() {
            if (!busy) return;
            long secs = (System.currentTimeMillis() - thinkingSince) / 1000;
            if (System.currentTimeMillis() - thinkingSince > THINKING_LIMIT_MS) {
                Log.w(TAG, "Thinking limit reached; aborting the model");
                model.abort();
            }
            if (status == Status.THINKING) setStatus(Status.THINKING, "Thinking… " + secs + " s");
            main.postDelayed(this, 1000);
        }
    };

    public AssistantController(Context context) {
        this.app = context.getApplicationContext();

        // Everything Jarvis remembers lives in one SQLCipher-encrypted vault. Unlocking it (Keystore,
        // decryption, a one-time migration) is the brain thread's first job, so launch never waits for it;
        // all memory work runs on that thread, after it.
        DeferredMemoryStore vaultStore = new DeferredMemoryStore(() -> {
            MemoryVault.Opened vault = MemoryVault.open(app);
            memoryEncrypted = vault.encrypted;
            if (!vault.notice.isEmpty()) main.post(() -> transcript(false, vault.notice));
            return vault.store;
        });
        memory = new MemoryEngine(vaultStore, System::currentTimeMillis, ZoneId.systemDefault());
        brain.execute(vaultStore::open);

        model = new LlamaModel(app);
        model.setThreads(app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt(KEY_THREADS, LlamaModel.DEFAULT_THREADS));
        // Fixed prefixes kept in the model's cache: the chat system prompt and the command translator.
        model.setWarmupPrompts(PromptBuilder.warmup(ASSISTANT_NAME), CommandTranslator.warmup());
        model.setListener((state, detail) -> main.post(() -> {
            for (Listener l : listeners) l.onModelState(state, detail);
        }));

        ActionLauncher launcher = new ActionLauncher(app, () -> uiVisible);
        AndroidActions actions = new AndroidActions(app, launcher);
        router = new TurnRouter(memory, actions, model, System::currentTimeMillis, ZoneId.systemDefault(),
                ASSISTANT_NAME);

        tts = new SpeechOutput(app, LANGUAGE);
        tts.setOnIdle(this::afterSpeaking);

        transcript(false, "Hi, I'm " + ASSISTANT_NAME + ". I run fully on this phone and nothing leaves it. Try: "
                + "\"directions to Domino's\", \"set a timer for 5 minutes\", \"call Amma\", "
                + "\"remember Shiva temple is …\", or ask me anything.");
        model.loadAsync();
    }

    // ------------------------------------------------------------ listeners

    /** Registers a listener and replays the recent conversation to it. */
    public void addListener(Listener l) {
        listeners.addIfAbsent(l);
        for (Object[] entry : history) l.onTranscript((Boolean) entry[0], (String) entry[1]);
        l.onStatus(status, "");
        l.onModelState(model.state(), model.detail());
        l.onAwaitingConfirmation(awaitingConfirmation);
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    public void setUiVisible(boolean visible) {
        uiVisible = visible;
    }

    public MemoryEngine memory() {
        return memory;
    }

    public ExecutorService brainThread() {
        return brain;
    }

    public boolean isHandsFree() {
        return handsFree;
    }

    public int threads() {
        return model.threads();
    }

    // ------------------------------------------------------------- privacy

    /** True: encrypted vault. False: secure storage failed, memory is RAM-only this session. Null: still opening. */
    public Boolean memoryEncrypted() {
        return memoryEncrypted;
    }

    public boolean allowOnlineSpeech() {
        return app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ONLINE_SPEECH, false);
    }

    /** Opt-in: lets the phone's normal recognizer (which may send audio to Google) be used. */
    public void setAllowOnlineSpeech(boolean allow) {
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ONLINE_SPEECH, allow).apply();
        if (stt != null && !stt.isActive()) {
            stt.destroy();
            stt = null;
        }
    }

    public SpeechInput.Mode speechMode() {
        return SpeechInput.availableMode(app, allowOnlineSpeech());
    }

    /** True: replies use an offline voice. False: only an online voice. Null: unknown. */
    public Boolean offlineVoice() {
        return tts.offlineVoice();
    }

    public String modelDetail() {
        return model.state() + " · " + model.detail();
    }

    /** Cycles 2 → 4 → 6 → 8 threads (for finding the fastest setting on this phone). */
    public int cycleThreads() {
        int[] options = {2, 4, 6, 8};
        int next = options[0];
        for (int i = 0; i < options.length; i++) {
            if (options[i] == model.threads()) {
                next = options[(i + 1) % options.length];
                break;
            }
        }
        final int n = next;
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_THREADS, n).apply();
        brain.execute(() -> model.setThreads(n)); // waits for any running generation
        return n;
    }

    // ---------------------------------------------------------- user controls

    /** One question, then back to idle. */
    public void listenOnce() {
        handsFree = false;
        startListening();
    }

    /** Keeps listening after every reply, with a foreground service so it survives the screen turning off. */
    public void startHandsFree() {
        if (handsFree) return;
        handsFree = true;
        failedListens = 0;
        try {
            app.startForegroundService(new Intent(app, AssistantService.class));
        } catch (RuntimeException e) {
            Log.e(TAG, "Could not start the listening service", e);
        }
        startListening();
    }

    /** Silences everything and ends hands-free mode. */
    public void stopAll() {
        boolean wasHandsFree = handsFree;
        handsFree = false;
        if (stt != null) stt.cancel();
        tts.stop();
        model.abort();
        setStatus(busy ? Status.THINKING : Status.IDLE, "");
        if (wasHandsFree) app.stopService(new Intent(app, AssistantService.class));
    }

    /** Typed input: same pipeline as speech. */
    public void submitText(String text) {
        if (text == null || text.trim().isEmpty()) return;
        if (stt != null && stt.isActive()) stt.cancel();
        process(text.trim());
    }

    /** Imports a picked .gguf, replacing the current model. Progress is reported as model state. */
    public void importModel(Uri uri) {
        String name = ModelFiles.displayName(app, uri);
        new Thread(() -> {
            model.unload();
            post(ChatModel.State.LOADING, "Copying " + (name.isEmpty() ? "model" : name) + "…");
            try {
                final long[] lastReport = {0L};
                ModelFiles.importFrom(app, uri, (copied, total) -> {
                    long nowMs = System.currentTimeMillis();
                    if (nowMs - lastReport[0] < 500) return;
                    lastReport[0] = nowMs;
                    String pct = total > 0 ? (copied * 100 / total) + "%" : (copied >> 20) + " MB";
                    post(ChatModel.State.LOADING, "Copying model… " + pct);
                });
                model.loadAsync();
            } catch (Exception e) {
                Log.e(TAG, "Model import failed", e);
                post(ChatModel.State.NO_MODEL, "Import failed: " + e.getMessage());
                // The old model file is only replaced after a complete copy, so reload it.
                if (ModelFiles.hasModel(app)) model.loadAsync();
            }
        }, "jarvis-model-import").start();
    }

    private void post(ChatModel.State state, String detail) {
        main.post(() -> {
            for (Listener l : listeners) l.onModelState(state, detail);
        });
    }

    // ------------------------------------------------------------- the loop

    private void startListening() {
        if (busy || status == Status.SPEAKING) return;
        SpeechInput.Mode mode = SpeechInput.availableMode(app, allowOnlineSpeech());
        if (mode == SpeechInput.Mode.NONE) {
            transcript(false, "This phone has no offline speech recognition, and I don't send your voice online "
                    + "unless you allow it. You can type, or allow online speech in Privacy.");
            boolean wasHandsFree = handsFree;
            handsFree = false;
            if (wasHandsFree) app.stopService(new Intent(app, AssistantService.class));
            setStatus(Status.IDLE, "");
            for (Listener l : listeners) l.onSpeechUnavailable();
            return;
        }
        if (stt != null && stt.mode() != mode) {
            stt.destroy();
            stt = null;
        }
        if (stt == null) {
            stt = new SpeechInput(app, LANGUAGE, mode, new SpeechInput.Listener() {
                @Override
                public void onListening() {
                    setStatus(Status.LISTENING, "");
                }

                @Override
                public void onPartialText(String text) {
                    setStatus(Status.LISTENING, text);
                }

                @Override
                public void onFinalText(String text) {
                    failedListens = 0;
                    process(text);
                }

                @Override
                public void onNoResult(boolean silence, String reason) {
                    onListenFailed(silence, reason);
                }

                @Override
                public void onNotice(String message) {
                    transcript(false, message);
                }
            });
        }
        setStatus(Status.LISTENING, "");
        stt.start();
    }

    private void onListenFailed(boolean silence, String reason) {
        if (!handsFree) {
            setStatus(Status.IDLE, silence ? "" : reason);
            return;
        }
        failedListens++;
        // Back off when the recognizer keeps failing so we don't spin the mic.
        long delay = silence ? 250L : Math.min(8000L, 800L * failedListens);
        if (failedListens >= 12) {
            transcript(false, "I stopped listening because I kept hearing nothing. Tap Hands-free to start again.");
            stopAll();
            return;
        }
        setStatus(Status.IDLE, silence ? "" : reason);
        main.postDelayed(() -> {
            if (handsFree && !busy && status != Status.SPEAKING) startListening();
        }, delay);
    }

    private void process(String text) {
        transcript(true, text);
        busy = true;
        liveReply.setLength(0);
        thinkingSince = System.currentTimeMillis();
        setStatus(Status.THINKING, "");
        main.removeCallbacks(thinkingTick);
        main.postDelayed(thinkingTick, 1000);
        brain.execute(() -> {
            TurnRouter.Result result;
            try {
                result = router.handle(text, chunk -> main.post(() -> {
                    if (liveReply.length() > 0) liveReply.append(' ');
                    liveReply.append(chunk);
                    for (Listener l : listeners) l.onReplyProgress(liveReply.toString());
                    setStatus(Status.SPEAKING, "");
                    tts.speak(chunk);
                }));
            } catch (RuntimeException e) {
                Log.e(TAG, "Turn failed", e);
                result = null;
            }
            final TurnRouter.Result r = result;
            main.post(() -> onResult(r));
        });
    }

    private void onResult(TurnRouter.Result r) {
        busy = false;
        main.removeCallbacks(thinkingTick);
        long tookMs = System.currentTimeMillis() - thinkingSince;
        if (r != null && (r.route == TurnRouter.Route.MODEL || r.route == TurnRouter.Route.FALLBACK)
                && model.state() == ChatModel.State.READY) {
            String stats = model.lastStats();
            String line = String.format(Locale.US, "Last answer %.1f s", tookMs / 1000.0)
                    + (stats.isEmpty() ? "" : " · " + stats);
            for (Listener l : listeners) l.onModelState(ChatModel.State.READY, line);
        }
        if (r == null) {
            transcript(false, "Something went wrong on my side. Please try again.");
            tts.speak("Something went wrong on my side.");
            setStatus(Status.SPEAKING, "");
            return;
        }
        // Route and command name only ("maps.directions [rules]"): never log what the user said.
        Log.d(TAG, "route=" + r.route + " streamed=" + r.streamed + (r.command.isEmpty() ? "" : " cmd=" + r.command));

        if (r.route == TurnRouter.Route.STOP) {
            tts.stop();
            setStatus(Status.IDLE, "");
            if (handsFree) main.postDelayed(this::startListening, 400);
            return;
        }

        setAwaitingConfirmation(r.awaitingConfirmation);
        if (!r.reply.isEmpty()) transcript(false, r.reply);
        if (!r.streamed && !r.reply.isEmpty()) {
            setStatus(Status.SPEAKING, "");
            tts.speak(r.reply);
        }
        if (!tts.isSpeaking()) afterSpeaking();
    }

    /** Called when the TTS queue drains. */
    private void afterSpeaking() {
        if (busy) return; // partial sentences finished but the model is still generating
        if (status == Status.LISTENING) return;
        setStatus(Status.IDLE, "");
        if (handsFree) main.postDelayed(() -> {
            if (handsFree && !busy && status == Status.IDLE) startListening();
        }, 350);
    }

    // ---------------------------------------------------------------- events

    private void setAwaitingConfirmation(boolean waiting) {
        main.removeCallbacks(confirmExpired);
        if (waiting) main.postDelayed(confirmExpired, CONFIRM_WINDOW_MS);
        if (awaitingConfirmation == waiting) return;
        awaitingConfirmation = waiting;
        for (Listener l : listeners) l.onAwaitingConfirmation(waiting);
    }

    private void setStatus(Status s, String detail) {
        status = s;
        for (Listener l : listeners) l.onStatus(s, detail == null ? "" : detail);
    }

    private void transcript(boolean fromUser, String text) {
        history.addLast(new Object[]{fromUser, text});
        while (history.size() > HISTORY_LIMIT) history.removeFirst();
        for (Listener l : listeners) l.onTranscript(fromUser, text);
    }

    @Override
    public String toString() {
        return String.format(Locale.US, "Assistant{status=%s, handsFree=%s, model=%s}", status, handsFree, model.state());
    }
}
