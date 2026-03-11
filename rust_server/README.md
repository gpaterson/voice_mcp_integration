# Rust MCP Server

This server runs on the **same machine that is running VS Code**.

## Overview

The server listens for TCP connections on the configured bind address (default `0.0.0.0:4000`) and receives speech-to-text (STT) output sent from the Android app. Each non-empty line received is written to the configured output file (default `steering.txt` in the repository root) with a timestamp prefix.

The file keeps only the most recent 100 lines. When a new line arrives after that, the oldest line is removed.

Settings are read from [rust_server/config.toml](rust_server/config.toml).

For machine-specific settings, create `rust_server/config.local.toml`. If present, it overrides keys from `config.toml` and is ignored by git.

## Prerequisites

- [Rust](https://www.rust-lang.org/tools/install) must be installed on the VS Code host machine:
  ```bash
  curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh
  ```

## Build & Run

From the `rust_server` directory:

```bash
# Run (builds automatically if needed)
cargo run

# Or build first, then run the binary
cargo build --release
./target/release/rust_mcp_server
```

With the default config, steering text is written to:

```text
/home/gavin/voice_mcp_integration/steering.txt
```

Configure shared defaults in [rust_server/config.toml](rust_server/config.toml).

For local-only changes that should never affect commits, use `rust_server/config.local.toml`, for example:

```toml
output_path = "steering.txt"
max_steering_lines = 100
bind_address = "0.0.0.0:4000"
```

Example output file contents:

```text
[2026-03-11 14:32:10] open terminal
[2026-03-11 14:32:18] show git status
```

Once running you will see:
```
Listening for Android STT input on 0.0.0.0:4000
Writing steering text to /home/gavin/voice_mcp_integration/steering.txt
Keeping last 100 steering lines
```

## Network

The server binds to `0.0.0.0:4000`, accepting connections on all network interfaces.

The Android app has a **server address field** in the UI where you enter the host IP and port (e.g. `192.168.1.x:4000`). This setting persists across app restarts. Use the IP address of the machine running VS Code, reachable from the phone over Wi-Fi or USB tethering.

If running inside WSL, the server is reachable from the phone via the Windows host IP. Add a port forward in PowerShell (run as Administrator):

```powershell
netsh interface portproxy add v4tov4 listenport=4000 listenaddress=0.0.0.0 connectport=4000 connectaddress=<WSL_IP>
netsh advfirewall firewall add rule name="VoiceMCP Port 4000" dir=in action=allow protocol=TCP localport=4000
```

Replace `<WSL_IP>` with the WSL internal IP (shown by `ip route get 1 | awk '{print $7}'` inside WSL).
