package com.example.voicemcp;

import android.Manifest;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.PowerManager;
import android.speech.SpeechRecognizer;
import android.view.View;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class MainActivity extends AppCompatActivity {

    private static final float DIM_SCREEN_BRIGHTNESS = 0.01f;
    private static final int REQUEST_RECORD_AUDIO = 1001;

    SpeechService speechService;
    AudioCaptureService audioCaptureService;
    private static final String PREFS = "voicemcp";
    private static final String PREF_ADDRESS = "server_address";
    private static final String PREF_RECOVERY_ENABLED = "recovery_enabled";
    private static final String PREF_CAPTURE_MODE = "capture_mode";
    private static final String PREF_CONTINUOUS_ENGINE = "continuous_engine";
    private static final String CAPTURE_MODE_RECOGNIZER = "recognizer";
    private static final String CAPTURE_MODE_CONTINUOUS = "continuous";

    private PowerManager.WakeLock wakeLock;
    private float previousBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
    private boolean audioCaptureServiceBound = false;

    private ServiceConnection audioCaptureConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            AudioCaptureService.LocalBinder binder = (AudioCaptureService.LocalBinder) service;
            audioCaptureService = binder.getService();
            audioCaptureServiceBound = true;

            SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
            String savedAddress = prefs.getString(PREF_ADDRESS, "10.23.23.29:4000");
            String engine = prefs.getString(PREF_CONTINUOUS_ENGINE, "");
            applyAddressToContinuousMode(savedAddress);
            if (audioCaptureService != null && !engine.isEmpty()) {
                audioCaptureService.setEngine(engine);
            }
            setupEngineSpinner();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            audioCaptureServiceBound = false;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        speechService = new SpeechService(this);
        requestMicPermissionIfNeeded();
        PowerManager powerManager = getSystemService(PowerManager.class);
        if (powerManager != null) {
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "voicemcp:always-on");
            wakeLock.setReferenceCounted(false);
        }

        // Bind to AudioCaptureService
        Intent audioCaptureIntent = new Intent(this, AudioCaptureService.class);
        bindService(audioCaptureIntent, audioCaptureConnection, BIND_AUTO_CREATE);

        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String savedAddress = prefs.getString(PREF_ADDRESS, "10.23.23.29:4000");
        boolean recoveryEnabled = prefs.getBoolean(PREF_RECOVERY_ENABLED, true);
        String captureMode = prefs.getString(PREF_CAPTURE_MODE, CAPTURE_MODE_RECOGNIZER);
        String continuousEngine = prefs.getString(PREF_CONTINUOUS_ENGINE, "");

        Switch modeSwitch = findViewById(R.id.modeSwitch);
        Button pttButton = findViewById(R.id.pttButton);
        Button modeToggleButton = findViewById(R.id.modeToggleButton);
        TextView modeStatusText = findViewById(R.id.modeStatusText);
        TextView engineStatusText = findViewById(R.id.engineStatusText);
        Button recoveryToggleButton = findViewById(R.id.recoveryToggleButton);
        TextView recoveryStatusText = findViewById(R.id.recoveryStatusText);
        TextView transcriptText = findViewById(R.id.transcriptText);
        EditText serverAddressField = findViewById(R.id.serverAddress);
        Button saveAddressButton = findViewById(R.id.saveAddressButton);

        serverAddressField.setText(savedAddress);
        applyAddress(savedAddress);
        speechService.setRecoveryEnabled(recoveryEnabled);
        updateRecoveryToggleText(recoveryToggleButton, recoveryEnabled);
        updateRecoveryStatusText(recoveryStatusText, recoveryEnabled);
        updateModeToggleText(modeToggleButton, captureMode);
        updateModeStatusText(modeStatusText, captureMode);

        saveAddressButton.setOnClickListener(v -> {
            String input = serverAddressField.getText().toString().trim();
            prefs.edit().putString(PREF_ADDRESS, input).apply();
            applyAddress(input);
            applyAddressToContinuousMode(input);
            Toast.makeText(this, "Address saved", Toast.LENGTH_SHORT).show();
        });

        // Toggle capture mode
        modeToggleButton.setOnClickListener(v -> {
            String currentMode = prefs.getString(PREF_CAPTURE_MODE, CAPTURE_MODE_RECOGNIZER);
            String newMode = currentMode.equals(CAPTURE_MODE_RECOGNIZER) ? CAPTURE_MODE_CONTINUOUS : CAPTURE_MODE_RECOGNIZER;
            prefs.edit().putString(PREF_CAPTURE_MODE, newMode).apply();
            updateModeToggleText(modeToggleButton, newMode);
            updateModeStatusText(modeStatusText, newMode);
            Toast.makeText(this, "Mode changed to " + (newMode.equals(CAPTURE_MODE_CONTINUOUS) ? "Continuous Audio" : "SpeechRecognizer"), Toast.LENGTH_SHORT).show();
        });

        // Engine spinner is populated after service binds (see setupEngineSpinner)

        recoveryToggleButton.setOnClickListener(v -> {
            boolean enabled = !prefs.getBoolean(PREF_RECOVERY_ENABLED, true);
            prefs.edit().putBoolean(PREF_RECOVERY_ENABLED, enabled).apply();
            speechService.setRecoveryEnabled(enabled);
            updateRecoveryToggleText(recoveryToggleButton, enabled);
            updateRecoveryStatusText(recoveryStatusText, enabled);
            Toast.makeText(this, enabled ? "Speech recovery ON" : "Speech recovery OFF", Toast.LENGTH_SHORT).show();
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
                if (errorCode == SpeechRecognizer.ERROR_NO_MATCH || errorCode == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                    return;
                }
                runOnUiThread(() -> transcriptText.setText(mapRecognitionError(errorCode)));
            }
        });

        // Toggle always-on
        modeSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            String captureMode1 = prefs.getString(PREF_CAPTURE_MODE, CAPTURE_MODE_RECOGNIZER);
            if (isChecked) {
                enableAlwaysOnMode();
                if (captureMode1.equals(CAPTURE_MODE_CONTINUOUS)) {
                    startContinuousAudioCapture();
                } else {
                    speechService.startAlwaysOn();
                }
            } else {
                if (captureMode1.equals(CAPTURE_MODE_CONTINUOUS)) {
                    stopContinuousAudioCapture();
                } else {
                    speechService.stopAlwaysOn();
                }
                disableAlwaysOnMode();
            }
        });

        // Push-to-talk
        pttButton.setOnClickListener(v -> speechService.startPushToTalk());
    }

    @Override
    protected void onDestroy() {
        disableAlwaysOnMode();
        if (audioCaptureServiceBound) {
            unbindService(audioCaptureConnection);
            audioCaptureServiceBound = false;
        }
        super.onDestroy();
    }

    private void startContinuousAudioCapture() {
        if (audioCaptureService != null) {
            android.util.Log.d("MainActivity", "Starting continuous audio capture");
            Toast.makeText(this, "Starting Continuous Audio", Toast.LENGTH_SHORT).show();
            Intent intent = new Intent(this, AudioCaptureService.class);
            startService(intent);
            SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
            String engine = prefs.getString(PREF_CONTINUOUS_ENGINE, "");
            if (!engine.isEmpty()) {
                audioCaptureService.setEngine(engine);
            }
            audioCaptureService.setAudioListener(new AudioCaptureService.AudioListener() {
                @Override
                public void onSpeechDetected(String message) {
                    TextView transcriptText = findViewById(R.id.transcriptText);
                    runOnUiThread(() -> transcriptText.setText("Listening: " + message));
                }

                @Override
                public void onTranscript(String text, boolean isFinal) {
                    TextView transcriptText = findViewById(R.id.transcriptText);
                    runOnUiThread(() -> transcriptText.setText((isFinal ? "Final: " : "Partial: ") + text));
                }

                @Override
                public void onError(String message) {
                    TextView transcriptText = findViewById(R.id.transcriptText);
                    runOnUiThread(() -> transcriptText.setText("Error: " + message));
                }
            });
        } else {
            android.util.Log.e("MainActivity", "AudioCaptureService not bound");
            Toast.makeText(this, "Audio service not ready", Toast.LENGTH_SHORT).show();
        }
    }

    private void stopContinuousAudioCapture() {
        if (audioCaptureService != null) {
            android.util.Log.d("MainActivity", "Stopping continuous audio capture");
            Toast.makeText(this, "Stopping Continuous Audio", Toast.LENGTH_SHORT).show();
            audioCaptureService.stopAudioCapture();
            Intent intent = new Intent(this, AudioCaptureService.class);
            stopService(intent);
        }
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

    private void applyAddressToContinuousMode(String address) {
        try {
            int colon = address.lastIndexOf(':');
            String host = colon > 0 ? address.substring(0, colon) : address;
            int port = colon > 0 ? Integer.parseInt(address.substring(colon + 1)) : 4000;
            if (audioCaptureService != null) {
                audioCaptureService.setServerAddress(host, port);
            }
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

    private void updateRecoveryToggleText(Button button, boolean enabled) {
        button.setText(enabled ? "Speech Recovery: ON" : "Speech Recovery: OFF");
    }

    private void updateRecoveryStatusText(TextView textView, boolean enabled) {
        textView.setText(enabled ? "Recovery mode is ON" : "Recovery mode is OFF");
    }

    private void updateModeToggleText(Button button, String mode) {
        button.setText(mode.equals(CAPTURE_MODE_CONTINUOUS) ? "Capture Mode: Continuous" : "Capture Mode: Recognizer");
    }

    private void updateModeStatusText(TextView textView, String mode) {
        textView.setText(mode.equals(CAPTURE_MODE_CONTINUOUS) ? "Using Continuous Audio Capture" : "Using SpeechRecognizer");
    }

    private void updateEngineStatusText(String engineDisplayName) {
        TextView engineStatusText = findViewById(R.id.engineStatusText);
        if (engineDisplayName == null || engineDisplayName.isEmpty()) {
            engineStatusText.setText("STT engine: none selected");
        } else {
            engineStatusText.setText("STT engine: " + engineDisplayName);
        }
    }

    private void requestMicPermissionIfNeeded() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_RECORD_AUDIO);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_RECORD_AUDIO) {
            if (grantResults.length == 0 || grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Microphone permission is required", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void setupEngineSpinner() {
        if (audioCaptureService == null) return;
        Spinner spinner = findViewById(R.id.engineSpinner);
        String[] keys = audioCaptureService.getEngineKeys();
        String[] names = audioCaptureService.getEngineDisplayNames();

        // Prepend a "None" option
        String[] displayItems = new String[names.length + 1];
        String[] keyItems = new String[keys.length + 1];
        displayItems[0] = "(none)";
        keyItems[0] = "";
        System.arraycopy(names, 0, displayItems, 1, names.length);
        System.arraycopy(keys, 0, keyItems, 1, keys.length);

        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, displayItems);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);

        // Restore saved selection
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String saved = prefs.getString(PREF_CONTINUOUS_ENGINE, "");
        for (int i = 0; i < keyItems.length; i++) {
            if (keyItems[i].equals(saved)) {
                spinner.setSelection(i);
                break;
            }
        }

        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String selectedKey = keyItems[position];
                prefs.edit().putString(PREF_CONTINUOUS_ENGINE, selectedKey).apply();
                if (audioCaptureService != null && !selectedKey.isEmpty()) {
                    audioCaptureService.setEngine(selectedKey);
                }
                updateEngineStatusText(displayItems[position]);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
    }
}
