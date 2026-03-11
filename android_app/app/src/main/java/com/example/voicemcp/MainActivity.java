package com.example.voicemcp;

import android.os.Bundle;
import android.widget.Button;
import android.widget.Switch;
import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {

    SpeechService speechService;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        speechService = new SpeechService(this);

        Switch modeSwitch = findViewById(R.id.modeSwitch);
        Button pttButton = findViewById(R.id.pttButton);

        // Toggle always-on
        modeSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) speechService.startAlwaysOn();
            else speechService.stopAlwaysOn();
        });

        // Push-to-talk
        pttButton.setOnClickListener(v -> speechService.startPushToTalk());
    }
}
