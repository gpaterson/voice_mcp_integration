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
            }
            public void onError(int error) {
                muteBeepStreams();
                if (transcriptListener != null) {
                    transcriptListener.onRecognitionError(error);
                }
                if (alwaysOn) {
                    scheduleRestart();
                } else {
                    restartHandler.postDelayed(SpeechService.this::unmuteBeepStreams, 600);
                }
            }
            public void onEvent(int eventType, Bundle params) {}
            public void onPartialResults(Bundle results) {
                // Show in UI only — don't send empty partials to server
                ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (matches != null && !matches.isEmpty() && !matches.get(0).isEmpty()) {
                    if (transcriptListener != null) transcriptListener.onTranscript(matches.get(0), false);
                }
            }
            public void onResults(Bundle results) {
                muteBeepStreams();
                sendFinalText(results);
                if (alwaysOn) {
                    scheduleRestart();
                } else {
                    restartHandler.postDelayed(SpeechService.this::unmuteBeepStreams, 600);
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
        if (transcriptListener != null) transcriptListener.onTranscript(text, true);
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

    private void scheduleRestart() {
        // Stay muted through the restart; startPushToTalk will re-arm the unmute timer
        restartHandler.postDelayed(this::startPushToTalk, 400);
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
        recognizer.startListening(intent);
        // Unmute 600ms after start — covers both start beep and any immediate end beep on quick sessions
        restartHandler.postDelayed(this::unmuteBeepStreams, 600);
    }

    public void startAlwaysOn() {
        alwaysOn = true;
        startPushToTalk();
    }

    public void stopAlwaysOn() {
        alwaysOn = false;
        restartHandler.removeCallbacksAndMessages(null);
        recognizer.stopListening();
        unmuteBeepStreams();
    }
}
