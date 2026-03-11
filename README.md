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

- Build and run the Rust server from `rust_server/`.
- Install the Android app from `android_app/` onto a device.
- Enter the host machine address in the app.
- Speak into the app and watch new timestamped lines appear in `steering.txt`.

More detailed server setup is documented in `rust_server/README.md`.Here's a description
