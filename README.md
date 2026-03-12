# Voice MCP Integration

Voice MCP Integration is a small end-to-end prototype for capturing speech on an Android device and turning it into timestamped steering text on the machine running VS Code.

The project has two parts:

- An Android app that uses Android's built-in `SpeechRecognizer` to capture push-to-talk or always-on speech input.
- A Rust server that listens for recognized text over TCP and writes each non-empty utterance to a local file.

In practice, this lets you speak short commands on a phone and have them land on the host machine as a rolling text feed that can be inspected, consumed, or integrated into other tooling.

## How It Works

1. The Android app performs speech-to-text locally on the device.
2. Final recognition results are sent to the host over TCP.
3. The Rust server timestamps each line and writes it to `steering.txt`.
4. The output file keeps only the most recent configured lines so it stays bounded.

## Repository Layout

- `android_app/`: Android client app for speech capture and transport.
- `rust_server/`: Rust TCP receiver and timestamped steering-file writer.
- `steering.txt`: Default output file written by the Rust server.

## Current Behavior

- The Android app lets you enter and persist the server address as `host:port`.
- Recognized text is shown in the app so speech results can be verified on-device.
- The Rust server reads shared settings from `rust_server/config.toml` and optional local overrides from `rust_server/config.local.toml`.
- By default, received text is written to `steering.txt` in the repository root with timestamps.

## Intended Use

This repository is best understood as an integration prototype rather than a finished product. It is useful for experimenting with:

- Phone-to-host speech command capture
- Low-friction voice steering input
- Simple bridging between Android speech recognition and local desktop tooling
- File-based handoff into other automation or editor workflows

## Getting Started

### Option A: Install the Pre-built APK

A pre-built APK is available on the [Releases](https://github.com/gpaterson/voice_mcp_integration/releases) page.

1. Download `app-debug.apk` from the latest release onto your Android device.
2. On your device, enable **Install from unknown sources** (Settings → Apps → Special app access → Install unknown apps, then allow your browser or file manager).
3. Open the downloaded APK to install it.
4. On first launch, grant the **Microphone** permission when prompted.

### Option B: Build from Source

#### Prerequisites

- Android SDK with API level 34 (or install via Android Studio).
- JDK 17 or later.
- The Vosk and Sherpa-ONNX speech models (see below).

#### Download Speech Models

The APK bundles two on-device speech-to-text models. They are not checked into git due to their size.

**Vosk model** (~68 MB):

```bash
cd android_app/app/src/main/assets
mkdir -p model-en-us
cd model-en-us
wget https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip
unzip vosk-model-small-en-us-0.15.zip
mv vosk-model-small-en-us-0.15/* .
rm -rf vosk-model-small-en-us-0.15 vosk-model-small-en-us-0.15.zip
cd ../../../../..
```

**Sherpa-ONNX model** (~68 MB):

```bash
cd android_app/app/src/main/assets
mkdir -p sherpa-onnx-model
cd sherpa-onnx-model
wget https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-streaming-zipformer-en-kroko-2025-08-06.tar.bz2
tar xjf sherpa-onnx-streaming-zipformer-en-kroko-2025-08-06.tar.bz2
mv sherpa-onnx-streaming-zipformer-en-kroko-2025-08-06/{encoder.onnx,decoder.onnx,joiner.onnx,tokens.txt} .
rm -rf sherpa-onnx-streaming-zipformer-en-kroko-2025-08-06 *.tar.bz2
cd ../../../../..
```

After downloading, your assets directory should look like:

```
android_app/app/src/main/assets/
├── model-en-us/          # Vosk model files
│   ├── am/
│   ├── conf/
│   ├── graph/
│   └── ...
└── sherpa-onnx-model/    # Sherpa-ONNX model files
    ├── encoder.onnx
    ├── decoder.onnx
    ├── joiner.onnx
    └── tokens.txt
```

#### Build the Android App

```bash
cd android_app
./gradlew assembleDebug
```

The APK will be at `android_app/app/build/outputs/apk/debug/app-debug.apk`.

Install via ADB:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Start the Rust Server

See [rust_server/README.md](rust_server/README.md) for full details.

```bash
cd rust_server
cargo run
```

### Connect the App

- Open the VoiceMCP app on your device.
- Enter the host machine address (e.g. `192.168.1.100:4000`).
- Select an STT engine from the dropdown (Vosk or Sherpa-ONNX).
- Set Capture Mode to **Continuous**.
- Toggle **Always-on mode** and start speaking.
- Transcribed text appears in `steering.txt` on the host machine.

## Speech Engines

The app supports two on-device speech-to-text engines, selectable via a dropdown:

| Engine | Model | Size | Notes |
|--------|-------|------|-------|
| **Vosk** | vosk-model-small-en-us-0.15 | ~68 MB | Lightweight, lower accuracy |
| **Sherpa-ONNX** | Kroko streaming zipformer (2025-08-06) | ~68 MB | Higher accuracy, streaming |

Both run entirely on-device with no internet required. All dependencies are Apache 2.0 or MIT licensed.

More detailed server setup is documented in `rust_server/README.md`.
