package com.jarvis.revived.speech;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
            tts.setLanguage(Locale.US);
        }
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
