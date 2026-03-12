package com.example.voicemcp;

/**
 * Common interface for on-device speech-to-text engines.
 */
public interface SttEngine {

    interface Listener {
        void onPartialResult(String text);
        void onFinalResult(String text);
        void onReady();
        void onError(String message);
    }

    /** Human-readable name shown in the engine dropdown. */
    String displayName();

    /** Unique key persisted in SharedPreferences. */
    String key();

    /** Begin asynchronous model loading. Call listener.onReady() or listener.onError(). */
    void initialize(android.content.Context context, Listener listener);

    /** @return true once initialize() has completed successfully. */
    boolean isReady();

    /** Feed a PCM-16 audio frame at 16 kHz mono. */
    void feedAudio(byte[] pcm, int length);

    /** Force a final result (e.g. on silence timeout). */
    void flush();

    /** Release all native resources. */
    void close();
}
