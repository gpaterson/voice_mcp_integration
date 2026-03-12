package com.example.voicemcp;

import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;

import java.io.OutputStream;
import java.net.Socket;
import java.util.ArrayList;

public class SpeechService {

    private static final long RESTART_DELAY_MS = 75;
    private static final long BUSY_RETRY_DELAY_MS = 200;
    private static final long TIMEOUT_RESTART_DELAY_MS = 20;
    private static final long UNMUTE_DELAY_MS = 600;
    private static final int LEGACY_COMPLETE_SILENCE_LENGTH_MS = 1800;
    private static final int LEGACY_POSSIBLY_COMPLETE_SILENCE_LENGTH_MS = 1200;
    private static final int LEGACY_MINIMUM_UTTERANCE_LENGTH_MS = 10000;
    private static final int COMPLETE_SILENCE_LENGTH_MS = 5000;
    private static final int POSSIBLY_COMPLETE_SILENCE_LENGTH_MS = 3500;
    private static final int MINIMUM_UTTERANCE_LENGTH_MS = 15000;

    public interface TranscriptListener {
        void onTranscript(String text, boolean isFinal);
        void onRecognitionError(int errorCode);
    }

    private Context context;
    private SpeechRecognizer recognizer;
    private Socket socket;
    private OutputStream out;
    private boolean alwaysOn = false;
    private String serverHost = "10.23.23.29";
    private int serverPort = 4000;
    private TranscriptListener transcriptListener;
    private final Handler restartHandler = new Handler(Looper.getMainLooper());
    private AudioManager audioManager;
    private boolean restartScheduled = false;
    private String lastPartialText = "";
    private String lastSentText = "";
    private boolean recoveryEnabled = true;

    public SpeechService(Context ctx) {
        context = ctx;
        audioManager = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        recognizer = SpeechRecognizer.createSpeechRecognizer(context);
        recognizer.setRecognitionListener(new RecognitionListener() {
            public void onReadyForSpeech(Bundle params) {}
            public void onBeginningOfSpeech() {}
            public void onRmsChanged(float rmsdB) {}
            public void onBufferReceived(byte[] buffer) {}
            public void onEndOfSpeech() {
                // Mute now to cover the end beep; stays muted through any restart start beep
                muteBeepStreams();
                // Capture what we have so phrase segments around pauses are not dropped.
                if (recoveryEnabled) {
                    maybeSendPendingPartial();
                }
            }
            public void onError(int error) {
                muteBeepStreams();
                if (recoveryEnabled) {
                    maybeSendPartialFallback(error);
                }
                if (transcriptListener != null) {
                    transcriptListener.onRecognitionError(error);
                }
                if (alwaysOn) {
                    // Busy means the recognizer is still finalizing the prior utterance.
                    if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) {
                        scheduleRestart(BUSY_RETRY_DELAY_MS);
                    } else if (recoveryEnabled && error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                        // Timeout is common in always-on mode; restart nearly immediately.
                        scheduleRestart(TIMEOUT_RESTART_DELAY_MS);
                    } else {
                        scheduleRestart(RESTART_DELAY_MS);
                    }
                } else {
                    restartHandler.postDelayed(SpeechService.this::unmuteBeepStreams, UNMUTE_DELAY_MS);
                }
            }
            public void onEvent(int eventType, Bundle params) {}
            public void onPartialResults(Bundle results) {
                // Show in UI only — don't send empty partials to server
                ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (matches != null && !matches.isEmpty() && !matches.get(0).isEmpty()) {
                    lastPartialText = matches.get(0).trim();
                    if (transcriptListener != null) transcriptListener.onTranscript(lastPartialText, false);
                }
            }
            public void onResults(Bundle results) {
                muteBeepStreams();
                sendFinalText(results);
                if (alwaysOn) {
                    scheduleRestart(RESTART_DELAY_MS);
                } else {
                    restartHandler.postDelayed(SpeechService.this::unmuteBeepStreams, UNMUTE_DELAY_MS);
                }
            }
        });

        connectSocket();
    }

    public void setTranscriptListener(TranscriptListener listener) {
        transcriptListener = listener;
    }

    public void setServerAddress(String host, int port) {
        serverHost = host;
        serverPort = port;
        connectSocket();
    }

    public void setRecoveryEnabled(boolean enabled) {
        recoveryEnabled = enabled;
    }

    private void connectSocket() {
        try {
            if (socket != null && !socket.isClosed()) socket.close();
            socket = new Socket(serverHost, serverPort);
            out = socket.getOutputStream();
        } catch (Exception e) {
            out = null;
            e.printStackTrace();
        }
    }

    private void sendFinalText(Bundle results) {
        ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (matches == null || matches.isEmpty()) return;
        String text = matches.get(0).trim();
        if (text.isEmpty()) return;
        lastPartialText = "";
        if (text.equals(lastSentText)) return;
        lastSentText = text;
        if (transcriptListener != null) transcriptListener.onTranscript(text, true);
        sendTextToServer(text);
    }

    private void sendTextToServer(String text) {
        new Thread(() -> {
            try {
                if (out == null || socket == null || socket.isClosed()) connectSocket();
                if (out != null) {
                    out.write((text + "\n").getBytes());
                    out.flush();
                }
            } catch (Exception e) {
                out = null;
                e.printStackTrace();
            }
        }).start();
    }

    private void maybeSendPendingPartial() {
        String partial = lastPartialText.trim();
        if (partial.isEmpty() || partial.equals(lastSentText)) {
            return;
        }
        lastPartialText = "";
        lastSentText = partial;
        if (transcriptListener != null) {
            transcriptListener.onTranscript(partial, true);
        }
        sendTextToServer(partial);
    }

    private void maybeSendPartialFallback(int errorCode) {
        if (errorCode != SpeechRecognizer.ERROR_NO_MATCH && errorCode != SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
            return;
        }
        String partial = lastPartialText.trim();
        if (partial.isEmpty()) {
            return;
        }
        if (partial.equals(lastSentText)) {
            lastPartialText = "";
            return;
        }
        lastPartialText = "";
        lastSentText = partial;
        if (transcriptListener != null) {
            transcriptListener.onTranscript(partial, true);
        }
        sendTextToServer(partial);
    }

    private void scheduleRestart(long delayMs) {
        if (restartScheduled) {
            return;
        }
        restartScheduled = true;
        // Stay muted through the restart; startPushToTalk will re-arm the unmute timer.
        restartHandler.postDelayed(() -> {
            restartScheduled = false;
            startPushToTalk();
        }, delayMs);
    }

    private static final int[] MUTED_STREAMS = {
        AudioManager.STREAM_MUSIC,
        AudioManager.STREAM_RING,
        AudioManager.STREAM_NOTIFICATION,
        AudioManager.STREAM_SYSTEM
    };

    private void muteBeepStreams() {
        for (int stream : MUTED_STREAMS)
            audioManager.adjustStreamVolume(stream, AudioManager.ADJUST_MUTE, 0);
    }

    private void unmuteBeepStreams() {
        for (int stream : MUTED_STREAMS)
            audioManager.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, 0);
    }

    public void startPushToTalk() {
        muteBeepStreams();
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        int completeSilenceMs = recoveryEnabled
            ? COMPLETE_SILENCE_LENGTH_MS
            : LEGACY_COMPLETE_SILENCE_LENGTH_MS;
        int possibleCompleteSilenceMs = recoveryEnabled
            ? POSSIBLY_COMPLETE_SILENCE_LENGTH_MS
            : LEGACY_POSSIBLY_COMPLETE_SILENCE_LENGTH_MS;
        int minUtteranceMs = recoveryEnabled
            ? MINIMUM_UTTERANCE_LENGTH_MS
            : LEGACY_MINIMUM_UTTERANCE_LENGTH_MS;

        // Keep sessions alive through natural pauses so first words of follow-up phrases are not clipped.
        intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, completeSilenceMs);
        intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, possibleCompleteSilenceMs);
        intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, minUtteranceMs);
        recognizer.startListening(intent);
        // Unmute 600ms after start — covers both start beep and any immediate end beep on quick sessions
        restartHandler.postDelayed(this::unmuteBeepStreams, UNMUTE_DELAY_MS);
    }

    public void startAlwaysOn() {
        alwaysOn = true;
        startPushToTalk();
    }

    public void stopAlwaysOn() {
        alwaysOn = false;
        restartScheduled = false;
        restartHandler.removeCallbacksAndMessages(null);
        recognizer.stopListening();
        unmuteBeepStreams();
    }
}
