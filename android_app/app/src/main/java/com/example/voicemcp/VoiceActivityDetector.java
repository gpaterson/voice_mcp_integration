package com.example.voicemcp;

public class VoiceActivityDetector {
    private static final float RMS_THRESHOLD = 0.02f;
    private static final int SPEECH_START_FRAMES = 3;
    private static final int SPEECH_END_FRAMES = 10;

    private int silenceFrameCount = 0;
    private int speechFrameCount = 0;
    private boolean inSpeech = false;

    public interface VADListener {
        void onSpeechStart();
        void onSpeechEnd();
    }

    private VADListener listener;

    public VoiceActivityDetector(VADListener listener) {
        this.listener = listener;
    }

    public void processPCM(short[] audio) {
        float rms = calculateRMS(audio);
        boolean isSpeech = rms > RMS_THRESHOLD;

        if (isSpeech) {
            silenceFrameCount = 0;
            speechFrameCount++;

            if (speechFrameCount == SPEECH_START_FRAMES && !inSpeech) {
                inSpeech = true;
                if (listener != null) {
                    listener.onSpeechStart();
                }
            }
        } else {
            speechFrameCount = 0;
            silenceFrameCount++;

            if (silenceFrameCount == SPEECH_END_FRAMES && inSpeech) {
                inSpeech = false;
                if (listener != null) {
                    listener.onSpeechEnd();
                }
            }
        }
    }

    private float calculateRMS(short[] audio) {
        long sum = 0;
        for (short sample : audio) {
            sum += sample * sample;
        }
        double rms = Math.sqrt(sum / (double) audio.length) / 32768.0;
        return (float) rms;
    }

    public boolean isInSpeech() {
        return inSpeech;
    }

    public void reset() {
        inSpeech = false;
        silenceFrameCount = 0;
        speechFrameCount = 0;
    }
}
