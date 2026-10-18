package com.jarvis.revived.speech;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.Log;

import java.util.ArrayList;

/**
 * Thin wrapper over Android's SpeechRecognizer. Main thread only.
 *
 * Privacy: by default speech is recognized ON THE PHONE
 * ({@link SpeechRecognizer#createOnDeviceSpeechRecognizer}, Android 12+). The
 * ordinary recognizer belongs to another app (Google) and may send audio to
 * its servers even though Jarvis itself has no internet permission, so it is
 * only used when the user explicitly allows online speech.
 *
 * Lessons carried over from the old GoogleSpeechManager: ignore callbacks from
 * a session that was already closed, recreate the recognizer after BUSY/CLIENT
 * errors, and treat "no match" / "speech timeout" as silence, not failure.
 */
public final class SpeechInput {

    private static final String TAG = "SpeechInput";

    /** Where speech is turned into text. */
    public enum Mode { ON_DEVICE, ONLINE, NONE }

    public interface Listener {
        void onListening();

        void onPartialText(String text);

        void onFinalText(String text);

        /** {@code silence} is true for "heard nothing"; false for real errors. */
        void onNoResult(boolean silence, String reason);

        /** Something the user should know (offline pack downloading, language switched). */
        default void onNotice(String message) {
        }
    }

    private final Context context;
    private final Listener listener;
    private final Mode mode;
    private String languageTag;
    private boolean languageFallbackTried = false;
    private SpeechRecognizer recognizer;
    private int session = 0;
    private boolean active = false;

    public SpeechInput(Context context, String languageTag, Mode mode, Listener listener) {
        this.context = context.getApplicationContext();
        this.languageTag = languageTag;
        this.mode = mode;
        this.listener = listener;
    }

    /** On-device when the phone has it; online only if allowed; otherwise NONE. */
    public static Mode availableMode(Context context, boolean allowOnline) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
            return Mode.ON_DEVICE;
        }
        if (allowOnline && SpeechRecognizer.isRecognitionAvailable(context)) return Mode.ONLINE;
        return Mode.NONE;
    }

    public Mode mode() {
        return mode;
    }

    public boolean isActive() {
        return active;
    }

    public void start() {
        if (mode == Mode.NONE) {
            listener.onNoResult(false, "speech unavailable");
            return;
        }
        if (recognizer == null) {
            recognizer = mode == Mode.ON_DEVICE && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    ? SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                    : SpeechRecognizer.createSpeechRecognizer(context);
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
                if (handleLanguageError(error)) return;
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

        Intent intent = recognizerIntent();

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

    private Intent recognizerIntent() {
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag);
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        intent.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.getPackageName());
        intent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true);
        return intent;
    }

    /**
     * On-device recognition needs a language pack. "Not supported" → try US
     * English once; "not downloaded" → ask the system to download it (Android 13+).
     */
    private boolean handleLanguageError(int error) {
        if (mode != Mode.ON_DEVICE || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false;
        if (error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED && !languageFallbackTried
                && !"en-US".equals(languageTag)) {
            languageFallbackTried = true;
            languageTag = "en-US";
            listener.onNotice("Offline speech doesn't have English (India) here, so I'm using US English.");
            recreate();
            start();
            return true;
        }
        if (error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE
                || error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && recognizer != null) {
                try {
                    recognizer.triggerModelDownload(recognizerIntent());
                    listener.onNotice("Downloading the offline speech pack. Try again in a minute, or type.");
                } catch (RuntimeException e) {
                    listener.onNotice("Offline speech needs its language pack. Install it in the phone's "
                            + "speech settings, or type.");
                }
            } else {
                listener.onNotice("Offline speech needs its language pack. Install it in the phone's "
                        + "speech settings, or type.");
            }
            listener.onNoResult(false, "offline speech pack missing");
            return true;
        }
        return false;
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
            case 11: return "speech service disconnected";
            case 12: return "language not supported offline";
            case 13: return "offline language pack missing";
            default: return "error " + error;
        }
    }
}
