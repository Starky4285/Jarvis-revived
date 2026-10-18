package com.jarvis.revived.speech;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Text-to-speech with a queue. {@link #setOnIdle} fires (on the main thread)
 * when everything queued has been spoken or stopped, which is when the
 * assistant may start listening again.
 */
public final class SpeechOutput {

    private static final String TAG = "SpeechOutput";

    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicInteger pending = new AtomicInteger(0);
    private final AtomicInteger ids = new AtomicInteger(0);
    private final List<String> queuedBeforeReady = new ArrayList<>();
    private final TextToSpeech tts;
    private volatile boolean ready = false;
    private volatile Boolean offlineVoice;
    private Runnable onIdle;

    private final String languageTag;

    public SpeechOutput(Context context, String languageTag) {
        this.languageTag = languageTag;
        // Callback arrives asynchronously, after the field below is assigned.
        tts = new TextToSpeech(context.getApplicationContext(), this::onInit);
    }

    private void onInit(int status) {
        if (status != TextToSpeech.SUCCESS) {
            Log.e(TAG, "TTS init failed: " + status);
            return;
        }
        Locale wanted = Locale.forLanguageTag(languageTag);
        int r = tts.setLanguage(wanted);
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
            wanted = Locale.US;
            tts.setLanguage(wanted);
        }
        offlineVoice = preferOfflineVoice(wanted);
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override
            public void onStart(String utteranceId) {
            }

            @Override
            public void onDone(String utteranceId) {
                finishOne();
            }

            @Override
            public void onError(String utteranceId) {
                finishOne();
            }

            @Override
            public void onStop(String utteranceId, boolean interrupted) {
                finishOne();
            }
        });
        main.post(() -> {
            ready = true;
            List<String> queued = new ArrayList<>(queuedBeforeReady);
            queuedBeforeReady.clear();
            pending.addAndGet(-queued.size());
            for (String s : queued) speak(s);
        });
    }

    /**
     * The speech engine (Google, Samsung…) is another app, and a "network"
     * voice would send what Jarvis says to the engine's server. Switches to an
     * installed offline voice for the language when the phone has one.
     *
     * @return true: the voice in use works offline; false: only an online voice; null: couldn't tell
     */
    private Boolean preferOfflineVoice(Locale wanted) {
        try {
            Voice current = tts.getVoice();
            if (current != null && !current.isNetworkConnectionRequired()) return Boolean.TRUE;
            Voice best = null;
            Set<Voice> voices = tts.getVoices();
            if (voices != null) {
                for (Voice v : voices) {
                    if (v == null || v.isNetworkConnectionRequired() || v.getLocale() == null) continue;
                    Set<String> features = v.getFeatures();
                    if (features != null && features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)) continue;
                    if (!wanted.getLanguage().equals(v.getLocale().getLanguage())) continue;
                    if (best == null || rank(v, wanted) > rank(best, wanted)) best = v;
                }
            }
            if (best != null && tts.setVoice(best) == TextToSpeech.SUCCESS) return Boolean.TRUE;
            return current == null ? null : Boolean.FALSE;
        } catch (RuntimeException e) {
            Log.e(TAG, "Voice check failed", e);
            return null;
        }
    }

    /** Same country first (Indian English for en-IN), then the engine's quality rating. */
    private static int rank(Voice v, Locale wanted) {
        int score = v.getQuality();
        if (wanted.getCountry().equalsIgnoreCase(v.getLocale().getCountry())) score += 10_000;
        return score;
    }

    /** True: replies use an offline voice. False: only an online voice is available. Null: unknown / not ready. */
    public Boolean offlineVoice() {
        return offlineVoice;
    }

    public void setOnIdle(Runnable r) {
        onIdle = r;
    }

    public boolean isSpeaking() {
        return pending.get() > 0;
    }

    /** Queues text (main thread). */
    public void speak(String text) {
        if (text == null || text.trim().isEmpty()) return;
        if (!ready) {
            queuedBeforeReady.add(text);
            pending.incrementAndGet();
            return;
        }
        pending.incrementAndGet();
        int r = tts.speak(text, TextToSpeech.QUEUE_ADD, new Bundle(), "u" + ids.incrementAndGet());
        if (r != TextToSpeech.SUCCESS) finishOne();
    }

    /** Silences everything immediately. */
    public void stop() {
        queuedBeforeReady.clear();
        if (ready) tts.stop();
        if (pending.getAndSet(0) > 0) postIdle();
    }

    public void shutdown() {
        stop();
        tts.shutdown();
    }

    private void finishOne() {
        int left = pending.decrementAndGet();
        if (left <= 0) {
            pending.set(0);
            postIdle();
        }
    }

    private void postIdle() {
        main.post(() -> {
            Runnable r = onIdle;
            if (r != null && pending.get() == 0) r.run();
        });
    }
}
