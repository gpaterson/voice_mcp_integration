package com.example.voicemcp;

import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.speech.SpeechRecognizer;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {

    private static final float DIM_SCREEN_BRIGHTNESS = 0.01f;

    SpeechService speechService;
    private static final String PREFS = "voicemcp";
    private static final String PREF_ADDRESS = "server_address";
    private PowerManager.WakeLock wakeLock;
    private float previousBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        speechService = new SpeechService(this);
        PowerManager powerManager = getSystemService(PowerManager.class);
        if (powerManager != null) {
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "voicemcp:always-on");
            wakeLock.setReferenceCounted(false);
        }

        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String savedAddress = prefs.getString(PREF_ADDRESS, "10.23.23.29:4000");

        Switch modeSwitch = findViewById(R.id.modeSwitch);
        Button pttButton = findViewById(R.id.pttButton);
        TextView transcriptText = findViewById(R.id.transcriptText);
        EditText serverAddressField = findViewById(R.id.serverAddress);
        Button saveAddressButton = findViewById(R.id.saveAddressButton);

        serverAddressField.setText(savedAddress);
        applyAddress(savedAddress);

        saveAddressButton.setOnClickListener(v -> {
            String input = serverAddressField.getText().toString().trim();
            prefs.edit().putString(PREF_ADDRESS, input).apply();
            applyAddress(input);
            Toast.makeText(this, "Address saved", Toast.LENGTH_SHORT).show();
        });

        speechService.setTranscriptListener(new SpeechService.TranscriptListener() {
            @Override
            public void onTranscript(String text, boolean isFinal) {
                runOnUiThread(() -> {
                    String prefix = isFinal ? "Final: " : "Partial: ";
                    transcriptText.setText(prefix + text);
                });
            }

            @Override
            public void onRecognitionError(int errorCode) {
                // These are normal between utterances in push-to-talk and always-on mode.
                if (errorCode == SpeechRecognizer.ERROR_NO_MATCH || errorCode == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                    return;
                }
                runOnUiThread(() -> transcriptText.setText(mapRecognitionError(errorCode)));
            }
        });

        // Toggle always-on
        modeSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                enableAlwaysOnMode();
                speechService.startAlwaysOn();
            } else {
                speechService.stopAlwaysOn();
                disableAlwaysOnMode();
            }
        });

        // Push-to-talk
        pttButton.setOnClickListener(v -> speechService.startPushToTalk());
    }

    @Override
    protected void onDestroy() {
        disableAlwaysOnMode();
        super.onDestroy();
    }

    private void enableAlwaysOnMode() {
        if (wakeLock != null && !wakeLock.isHeld()) {
            wakeLock.acquire();
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            );
        }

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        WindowManager.LayoutParams layoutParams = getWindow().getAttributes();
        previousBrightness = layoutParams.screenBrightness;
        layoutParams.screenBrightness = DIM_SCREEN_BRIGHTNESS;
        getWindow().setAttributes(layoutParams);
    }

    private void disableAlwaysOnMode() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(false);
            setTurnScreenOn(false);
        } else {
            getWindow().clearFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            );
        }

        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        WindowManager.LayoutParams layoutParams = getWindow().getAttributes();
        layoutParams.screenBrightness = previousBrightness;
        getWindow().setAttributes(layoutParams);
    }

    private void applyAddress(String address) {
        try {
            int colon = address.lastIndexOf(':');
            String host = colon > 0 ? address.substring(0, colon) : address;
            int port = colon > 0 ? Integer.parseInt(address.substring(colon + 1)) : 4000;
            speechService.setServerAddress(host, port);
        } catch (Exception e) {
            Toast.makeText(this, "Invalid address format", Toast.LENGTH_SHORT).show();
        }
    }

    private String mapRecognitionError(int errorCode) {
        switch (errorCode) {
            case SpeechRecognizer.ERROR_AUDIO:
                return "Mic audio error";
            case SpeechRecognizer.ERROR_CLIENT:
                return "Recognizer client error";
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                return "Microphone permission missing";
            case SpeechRecognizer.ERROR_NETWORK:
                return "Network error";
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
                return "Network timeout";
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                return "Recognizer busy (retrying)";
            case SpeechRecognizer.ERROR_SERVER:
                return "Speech service error";
            default:
                return "Recognition error: " + errorCode;
        }
    }
}
