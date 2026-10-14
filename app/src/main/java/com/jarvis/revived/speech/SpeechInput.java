package com.jarvis.revived.speech;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.Log;

import java.util.ArrayList;

/**
 * Thin wrapper over Android's SpeechRecognizer. Main thread only.
 *
 * Lessons carried over from the old GoogleSpeechManager: ignore callbacks from
 * a session that was already closed, recreate the recognizer after BUSY/CLIENT
 * errors, and treat "no match" / "speech timeout" as silence, not failure.
 */
public final class SpeechInput {

    private static final String TAG = "SpeechInput";

    public interface Listener {
        void onListening();

        void onPartialText(String text);

        void onFinalText(String text);

        /** {@code silence} is true for "heard nothing"; false for real errors. */
        void onNoResult(boolean silence, String reason);
    }

    private final Context context;
    private final Listener listener;
    private final String languageTag;
    private SpeechRecognizer recognizer;
    private int session = 0;
    private boolean active = false;

    public SpeechInput(Context context, String languageTag, Listener listener) {
        this.context = context.getApplicationContext();
        this.languageTag = languageTag;
        this.listener = listener;
    }

    public static boolean isAvailable(Context context) {
        return SpeechRecognizer.isRecognitionAvailable(context);
    }

    public boolean isActive() {
        return active;
    }

    public void start() {
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(context);
        }
        final int mySession = ++session;
        recognizer.setRecognitionListener(new RecognitionListener() {
            private boolean stale() {
                return mySession != session;
            }

            @Override
            public void onReadyForSpeech(Bundle params) {
                if (stale()) return;
                listener.onListening();
            }

            @Override
            public void onBeginningOfSpeech() {
            }

            @Override
            public void onRmsChanged(float rmsdB) {
            }

            @Override
            public void onBufferReceived(byte[] buffer) {
            }

            @Override
            public void onEndOfSpeech() {
            }

            @Override
            public void onError(int error) {
                if (stale()) return;
                active = false;
                boolean silence = error == SpeechRecognizer.ERROR_NO_MATCH
                        || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT;
                if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY || error == SpeechRecognizer.ERROR_CLIENT) {
                    recreate();
                }
                listener.onNoResult(silence, describe(error));
            }

            @Override
            public void onResults(Bundle results) {
                if (stale()) return;
                active = false;
                ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                String text = matches == null || matches.isEmpty() ? "" : matches.get(0);
                if (text == null || text.trim().length() < 2) {
                    listener.onNoResult(true, "empty");
                } else {
                    listener.onFinalText(text.trim());
                }
            }

            @Override
            public void onPartialResults(Bundle partialResults) {
                if (stale()) return;
                ArrayList<String> matches = partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (matches != null && !matches.isEmpty() && matches.get(0) != null) {
                    listener.onPartialText(matches.get(0));
                }
            }

            @Override
            public void onEvent(int eventType, Bundle params) {
            }
        });

        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag);
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        intent.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.getPackageName());
        intent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true);

        try {
            active = true;
            recognizer.startListening(intent);
        } catch (RuntimeException e) {
            Log.e(TAG, "startListening failed", e);
            active = false;
            recreate();
            listener.onNoResult(false, "start failed");
        }
    }

    /** Stops without delivering a result. */
    public void cancel() {
        session++;
        active = false;
        if (recognizer != null) {
            try {
                recognizer.cancel();
            } catch (RuntimeException ignored) {
                // already stopped
            }
        }
    }

    public void destroy() {
        cancel();
        if (recognizer != null) {
            try {
                recognizer.destroy();
            } catch (RuntimeException ignored) {
                // already gone
            }
            recognizer = null;
        }
    }

    private void recreate() {
        if (recognizer != null) {
            try {
                recognizer.destroy();
            } catch (RuntimeException ignored) {
                // already gone
            }
        }
        recognizer = null;
    }

    private static String describe(int error) {
        switch (error) {
            case SpeechRecognizer.ERROR_NO_MATCH: return "no match";
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT: return "no speech";
            case SpeechRecognizer.ERROR_AUDIO: return "audio error";
            case SpeechRecognizer.ERROR_NETWORK: return "network error";
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT: return "network timeout";
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS: return "microphone permission missing";
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY: return "recognizer busy";
            case SpeechRecognizer.ERROR_CLIENT: return "client error";
            case SpeechRecognizer.ERROR_SERVER: return "server error";
            default: return "error " + error;
        }
    }
}
