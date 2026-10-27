package io.freemytv;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;

import java.util.ArrayList;
import java.util.List;

/**
 * Voice search without the recognizer's own screen: one android.speech.SpeechRecognizer in this process (the TV's
 * default service, katniss on Google TV), so the words can show in our search field as they come and the transcript
 * can't be lost to an activity that never returns. Main thread only. One session at a time; the recognizer is created
 * on the first press (binding the service is the slow part on this TV's MediaTek) and kept until the home screen pauses.
 * When it can't work here (no microphone permission, no service, the service refuses us) `available` says no or the
 * session fails with UNAVAILABLE, and HomeView uses the recognizer-activity route instead.
 */
final class Voice {
    /** Why a session ended without words: nothing heard, no internet, or this route can't work (use the activity). */
    static final int MISSED = 0, OFFLINE = 1, UNAVAILABLE = 2;

    interface Listener {
        /** The microphone is open: speak now. */
        void onReady();
        /** Words so far (may change as more is heard). */
        void onPartial(String text);
        /** The final transcripts, best first (at least one). */
        void onFinal(List<String> alternatives);
        void onFail(int kind);
    }

    private static final long TIMEOUT_MS = 12_000, BUSY_RETRY_MS = 200;
    private static boolean broken;   // the service refused us once: not again until the app restarts

    private final Context context;
    private final Handler handler = Work.MAIN;
    private SpeechRecognizer recognizer;
    private Listener listener;
    private boolean ready, retried;
    private long startedAt, readyAt, endedAt;

    Voice(Context context) {
        this.context = context.getApplicationContext();
    }

    /**
     * Can the in-process recognizer run: the microphone permission (granted over adb when Free My TV mode was turned on;
     * a launcher on a TV can't show the prompt), a recognition service on the TV, and it has not refused us before.
     */
    static boolean available(Context c) {
        return !broken && c.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                && SpeechRecognizer.isRecognitionAvailable(c);
    }

    boolean listening() { return listener != null; }

    void start(Listener l) {
        if (listener != null) cancel();
        listener = l;
        ready = retried = false;
        startedAt = SystemClock.uptimeMillis();
        readyAt = endedAt = 0;
        begin();
        handler.postDelayed(timeout, TIMEOUT_MS);   // the service sometimes never answers (cold): stop and say so
    }

    /** Stops the session without a word to the listener (Back, the mic pressed again, the screen leaving). */
    void cancel() {
        if (listener == null) return;
        end();
        if (recognizer != null) {
            try {
                recognizer.cancel();
            } catch (RuntimeException e) {
                AppLog.w("voice cancel: " + e.getClass().getSimpleName());
            }
        }
    }

    private void end() {
        listener = null;
        handler.removeCallbacks(timeout);
        handler.removeCallbacks(again);
    }

    /** Lets go of the service connection (the home screen is paused or short of memory). */
    void destroy() {
        cancel();
        if (recognizer == null) return;
        try {
            recognizer.destroy();
        } catch (RuntimeException e) {
            AppLog.w("voice destroy: " + e.getClass().getSimpleName());
        }
        recognizer = null;
    }

    private void begin() {
        try {
            if (recognizer == null) {
                recognizer = SpeechRecognizer.createSpeechRecognizer(context);
                recognizer.setRecognitionListener(events);
            }
            recognizer.startListening(new android.content.Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_WEB_SEARCH)
                    .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                    .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.getPackageName()));
        } catch (RuntimeException e) {   // SecurityException when the service won't take us, IllegalStateException...
            AppLog.w("voice start: " + e.getClass().getSimpleName());
            broken = true;
            fail(UNAVAILABLE);
        }
    }

    private final Runnable timeout = () -> {
        if (listener == null) return;
        AppLog.w("voice: gave up after " + (SystemClock.uptimeMillis() - startedAt) + " ms");
        if (recognizer != null) recognizer.cancel();   // it never said it stopped
        fail(MISSED);
    };

    private final Runnable again = () -> { if (listener != null) begin(); };

    /** Ends the session (the service has already stopped) and tells the listener why. */
    private void fail(int kind) {
        Listener l = listener;
        end();
        if (l != null) l.onFail(kind);
    }

    private static String first(Bundle b) {
        List<String> list = b == null ? null : b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        return list == null || list.isEmpty() ? "" : list.get(0);
    }

    private final RecognitionListener events = new RecognitionListener() {
        @Override public void onReadyForSpeech(Bundle params) {
            if (listener == null) return;
            ready = true;
            readyAt = SystemClock.uptimeMillis();
            AppLog.w("voice: mic open " + (readyAt - startedAt) + " ms after the press");
            listener.onReady();
        }

        @Override public void onBeginningOfSpeech() { }

        @Override public void onRmsChanged(float rmsdB) { }

        @Override public void onBufferReceived(byte[] buffer) { }

        @Override public void onEndOfSpeech() {
            endedAt = SystemClock.uptimeMillis();
        }

        @Override public void onPartialResults(Bundle partial) {
            String text = first(partial);
            if (listener != null && !text.isEmpty()) listener.onPartial(text);
        }

        @Override public void onResults(Bundle results) {
            if (listener == null) return;
            List<String> list = results == null ? null : results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            long now = SystemClock.uptimeMillis();
            AppLog.w("voice: results " + (endedAt != 0 ? (now - endedAt) + " ms after the end of speech, " : "")
                    + (now - startedAt) + " ms in all, " + (list == null ? 0 : list.size()) + " alternatives");
            if (list == null || list.isEmpty() || list.get(0).trim().isEmpty()) {
                fail(MISSED);
                return;
            }
            Listener l = listener;
            end();   // the recognizer stays bound for the next press
            l.onFinal(new ArrayList<>(list));
        }

        @Override public void onError(int error) {
            if (listener == null) return;
            AppLog.w("voice: error " + error + " " + (SystemClock.uptimeMillis() - startedAt) + " ms in");
            switch (error) {
                case SpeechRecognizer.ERROR_NO_MATCH:
                case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                    fail(MISSED);
                    break;
                case SpeechRecognizer.ERROR_NETWORK:
                case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
                case SpeechRecognizer.ERROR_SERVER:
                    fail(OFFLINE);
                    break;
                case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                    if (retried) {
                        fail(MISSED);
                        break;
                    }
                    retried = true;   // a leftover session: clear it and ask once more
                    if (recognizer != null) recognizer.cancel();
                    handler.postDelayed(again, BUSY_RETRY_MS);
                    break;
                case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                    broken = true;
                    fail(UNAVAILABLE);
                    break;
                case SpeechRecognizer.ERROR_CLIENT:
                    // Before the mic opened it is the service that failed to bind or took us as a stranger.
                    if (!ready) broken = true;
                    fail(ready ? MISSED : UNAVAILABLE);
                    break;
                case 12:   // ERROR_LANGUAGE_NOT_SUPPORTED and ERROR_LANGUAGE_UNAVAILABLE (API 31): the activity may do better
                case 13:
                    fail(UNAVAILABLE);
                    break;
                case SpeechRecognizer.ERROR_AUDIO:
                    fail(ready ? MISSED : UNAVAILABLE);   // mic busy or missing: the activity route may fare better (not remembered)
                    break;
                default:
                    fail(MISSED);
            }
        }

        @Override public void onEvent(int eventType, Bundle params) { }
    };
}
