package com.example.voicemcp;

import android.app.Service;
import android.content.Intent;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Binder;
import android.os.IBinder;
import androidx.annotation.Nullable;

import java.io.OutputStream;
import java.net.Socket;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

public class AudioCaptureService extends Service {

    private static final int SAMPLE_RATE = 16000;
    private static final int BUFFER_SIZE = 4096;
    private static final int FRAME_SIZE = 512;

    private AudioRecord recorder;
    private VoiceActivityDetector vad;
    private Thread captureThread;
    private boolean isRecording = false;
    private Socket socket;
    private OutputStream audioStream;
    private String serverHost = "10.23.23.29";
    private int serverPort = 4000;

    // Pluggable STT engines
    private final Map<String, SttEngine> engines = new LinkedHashMap<>();
    private SttEngine activeEngine;
    private String activeEngineKey = "";
    private String engineStatusMessage = "";
    private String engineErrorMessage = "";

    public interface AudioListener {
        void onSpeechDetected(String message);
        void onTranscript(String text, boolean isFinal);
        void onError(String message);
    }

    private AudioListener audioListener;
    private final IBinder binder = new LocalBinder();

    public class LocalBinder extends Binder {
        AudioCaptureService getService() {
            return AudioCaptureService.this;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        vad = new VoiceActivityDetector(new VoiceActivityDetector.VADListener() {
            @Override
            public void onSpeechStart() {
                if (audioListener != null) {
                    audioListener.onSpeechDetected("Speech detected");
                }
            }

            @Override
            public void onSpeechEnd() {
                // Speech end is handled client-side for timing
            }
        });

        // Register available engines
        registerEngine(new VoskEngine());
        registerEngine(new SherpaOnnxEngine());
    }

    private void registerEngine(SttEngine engine) {
        engines.put(engine.key(), engine);
    }

    /** Returns available engine keys in registration order. */
    public String[] getEngineKeys() {
        return engines.keySet().toArray(new String[0]);
    }

    /** Returns display names in the same order as getEngineKeys(). */
    public String[] getEngineDisplayNames() {
        String[] names = new String[engines.size()];
        int i = 0;
        for (SttEngine e : engines.values()) { names[i++] = e.displayName(); }
        return names;
    }

    /** Returns the key of the currently active engine, or empty string if none. */
    public String getActiveEngineKey() {
        return activeEngineKey;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        android.util.Log.d("AudioCaptureService", "onStartCommand called");
        startAudioCapture();
        return START_STICKY;
    }

    public void setAudioListener(AudioListener listener) {
        this.audioListener = listener;
        if (audioListener != null) {
            if (!engineErrorMessage.isEmpty()) {
                audioListener.onError(engineErrorMessage);
            } else if (!engineStatusMessage.isEmpty()) {
                audioListener.onSpeechDetected(engineStatusMessage);
            }
        }
    }

    /** Switch to the engine identified by key. Initializes on first use. */
    public void setEngine(String engineKey) {
        if (engineKey == null || engineKey.isEmpty() || engineKey.equals(activeEngineKey)) {
            return;
        }
        SttEngine engine = engines.get(engineKey);
        if (engine == null) {
            return;
        }
        activeEngineKey = engineKey;
        activeEngine = engine;
        engineStatusMessage = "";
        engineErrorMessage = "";

        if (!engine.isReady()) {
            engine.initialize(this, new SttEngine.Listener() {
                @Override
                public void onPartialResult(String text) {
                    if (audioListener != null) audioListener.onTranscript(text, false);
                }
                @Override
                public void onFinalResult(String text) {
                    if (audioListener != null) audioListener.onTranscript(text, true);
                    sendTranscriptToServer(text);
                }
                @Override
                public void onReady() {
                    engineStatusMessage = engine.displayName() + " ready";
                    engineErrorMessage = "";
                    if (audioListener != null) audioListener.onSpeechDetected(engineStatusMessage);
                }
                @Override
                public void onError(String message) {
                    engineErrorMessage = message;
                    engineStatusMessage = "";
                    if (audioListener != null) audioListener.onError(message);
                }
            });
        }
    }

    public boolean isEngineReady() {
        return activeEngine != null && activeEngine.isReady();
    }

    public void setServerAddress(String host, int port) {
        this.serverHost = host;
        this.serverPort = port;
        connectSocket();
    }

    private void connectSocket() {
        new Thread(() -> {
            try {
                if (socket != null && !socket.isClosed()) {
                    socket.close();
                }
                socket = new Socket(serverHost, serverPort);
                audioStream = socket.getOutputStream();
            } catch (Exception e) {
                audioStream = null;
                e.printStackTrace();
            }
        }).start();
    }

    private void startAudioCapture() {
        if (isRecording) return;

        android.util.Log.d("AudioCaptureService", "startAudioCapture called");
        
        int minBufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        );

        recorder = new AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            Math.max(minBufferSize, BUFFER_SIZE)
        );

        if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
            android.util.Log.e("AudioCaptureService", "Failed to initialize AudioRecord");
            if (audioListener != null) {
                audioListener.onError("Failed to initialize AudioRecord");
            }
            return;
        }

        android.util.Log.d("AudioCaptureService", "AudioRecord initialized, starting recording");
        recorder.startRecording();
        isRecording = true;
        connectSocket();

        captureThread = new Thread(() -> captureAudio());
        captureThread.start();
    }

    private void captureAudio() {
        short[] audioBuffer = new short[FRAME_SIZE];
        long lastSpeechTime = 0;
        final long SILENCE_TIMEOUT_MS = 2000;

        while (isRecording) {
            int bytesRead = recorder.read(audioBuffer, 0, FRAME_SIZE);

            if (bytesRead > 0) {
                vad.processPCM(audioBuffer);

                boolean hasEngine = activeEngine != null && activeEngine.isReady();

                if (hasEngine) {
                    byte[] pcmBytes = shortToBytes(audioBuffer, bytesRead);
                    try {
                        activeEngine.feedAudio(pcmBytes, pcmBytes.length);
                    } catch (Exception e) {
                        if (audioListener != null) {
                            audioListener.onError("STT processing error: " + e.getMessage());
                        }
                    }
                }

                if (vad.isInSpeech()) {
                    lastSpeechTime = System.currentTimeMillis();
                    if (!hasEngine) {
                        sendAudioFrame(audioBuffer);
                    }
                } else if (lastSpeechTime > 0) {
                    long timeSinceSpeech = System.currentTimeMillis() - lastSpeechTime;
                    if (timeSinceSpeech > SILENCE_TIMEOUT_MS) {
                        if (hasEngine) {
                            activeEngine.flush();
                        } else {
                            sendEndOfUtterance();
                        }
                        lastSpeechTime = 0;
                    }
                }
            }
        }
    }

    private void sendTranscriptToServer(String text) {
        if (audioStream == null) {
            return;
        }
        new Thread(() -> {
            try {
                if (audioStream == null || socket == null || socket.isClosed()) {
                    connectSocket();
                }
                if (audioStream != null) {
                    audioStream.write((text + "\n").getBytes());
                    audioStream.flush();
                }
            } catch (Exception e) {
                audioStream = null;
                e.printStackTrace();
            }
        }).start();
    }

    private byte[] shortToBytes(short[] audio, int validSamples) {
        short[] copy = Arrays.copyOf(audio, validSamples);
        byte[] bytes = new byte[copy.length * 2];
        for (int i = 0; i < copy.length; i++) {
            bytes[i * 2] = (byte) (copy[i] & 0x00ff);
            bytes[i * 2 + 1] = (byte) ((copy[i] >> 8) & 0x00ff);
        }
        return bytes;
    }

    private void sendAudioFrame(short[] audioBuffer) {
        if (audioStream == null) {
            return;
        }
        new Thread(() -> {
            try {
                if (audioStream == null || socket == null || socket.isClosed()) {
                    connectSocket();
                }
                if (audioStream != null) {
                    // Send raw PCM frame prefixed with frame marker
                    audioStream.write("AUDIO\n".getBytes());
                    audioStream.flush();
                }
            } catch (Exception e) {
                audioStream = null;
                e.printStackTrace();
            }
        }).start();
    }

    private void sendEndOfUtterance() {
        if (audioStream == null) {
            return;
        }
        new Thread(() -> {
            try {
                if (audioStream == null || socket == null || socket.isClosed()) {
                    connectSocket();
                }
                if (audioStream != null) {
                    audioStream.write("EOU\n".getBytes());
                    audioStream.flush();
                }
            } catch (Exception e) {
                audioStream = null;
                e.printStackTrace();
            }
        }).start();
    }

    public void stopAudioCapture() {
        isRecording = false;
        if (recorder != null) {
            try {
                recorder.stop();
                recorder.release();
            } catch (Exception e) {
                e.printStackTrace();
            }
            recorder = null;
        }
        if (captureThread != null) {
            try {
                captureThread.join(5000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        stopAudioCapture();
        for (SttEngine engine : engines.values()) {
            engine.close();
        }
        if (socket != null) {
            try {
                socket.close();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        super.onDestroy();
    }
}
