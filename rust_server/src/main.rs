use std::collections::VecDeque;
use std::fs::{self, OpenOptions};
use std::io::{BufRead, BufReader, Write};
use std::net::TcpListener;
use std::path::{Path, PathBuf};

use chrono::Local;
use crossbeam_channel::unbounded;
use serde::Deserialize;

const DEFAULT_MAX_STEERING_LINES: usize = 100;
const DEFAULT_BIND_ADDRESS: &str = "0.0.0.0:4000";

#[derive(Debug, Deserialize)]
struct AppConfig {
    output_path: Option<String>,
    max_steering_lines: Option<usize>,
    bind_address: Option<String>,
}

impl AppConfig {
    fn merge(self, override_config: AppConfig) -> AppConfig {
        AppConfig {
            output_path: override_config.output_path.or(self.output_path),
            max_steering_lines: override_config
                .max_steering_lines
                .or(self.max_steering_lines),
            bind_address: override_config.bind_address.or(self.bind_address),
        }
    }
}

struct ResolvedConfig {
    output_path: PathBuf,
    max_steering_lines: usize,
    bind_address: String,
}

fn config_file_path() -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR")).join("config.toml")
}

fn local_config_file_path() -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR")).join("config.local.toml")
}

fn default_output_path() -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR"))
        .parent()
        .expect("rust_server should live under the repository root")
        .join("steering.txt")
}

fn resolve_output_path(raw: Option<&str>) -> PathBuf {
    match raw {
        Some(path) if !path.trim().is_empty() => {
            let path = PathBuf::from(path);
            if path.is_absolute() {
                path
            } else {
                Path::new(env!("CARGO_MANIFEST_DIR"))
                    .parent()
                    .expect("rust_server should live under the repository root")
                    .join(path)
            }
        }
        _ => default_output_path(),
    }
}

fn load_config() -> std::io::Result<ResolvedConfig> {
    let config_path = config_file_path();
    let config_text = fs::read_to_string(&config_path)?;
    let config: AppConfig = toml::from_str(&config_text).map_err(|err| {
        std::io::Error::new(
            std::io::ErrorKind::InvalidData,
            format!("Invalid config file {}: {}", config_path.display(), err),
        )
    })?;

    let local_config_path = local_config_file_path();
    let config = if local_config_path.exists() {
        let local_text = fs::read_to_string(&local_config_path)?;
        let local_config: AppConfig = toml::from_str(&local_text).map_err(|err| {
            std::io::Error::new(
                std::io::ErrorKind::InvalidData,
                format!("Invalid local config file {}: {}", local_config_path.display(), err),
            )
        })?;
        config.merge(local_config)
    } else {
        config
    };

    let max_steering_lines = config
        .max_steering_lines
        .unwrap_or(DEFAULT_MAX_STEERING_LINES)
        .max(1);

    Ok(ResolvedConfig {
        output_path: resolve_output_path(config.output_path.as_deref()),
        max_steering_lines,
        bind_address: config
            .bind_address
            .unwrap_or_else(|| DEFAULT_BIND_ADDRESS.to_string()),
    })
}

fn load_recent_lines(path: &Path, max_steering_lines: usize) -> VecDeque<String> {
    let mut lines: VecDeque<String> = fs::read_to_string(path)
        .ok()
        .map(|content| content.lines().map(str::to_owned).collect())
        .unwrap_or_default();

    while lines.len() > max_steering_lines {
        lines.pop_front();
    }

    lines
}

fn write_recent_lines(path: &Path, lines: &VecDeque<String>) -> std::io::Result<()> {
    let mut output_file = OpenOptions::new()
        .create(true)
        .write(true)
        .truncate(true)
        .open(path)?;

    for line in lines {
        writeln!(output_file, "{}", line)?;
    }

    output_file.flush()
}

fn main() -> std::io::Result<()> {
    let config = load_config()?;

    let writer_output_path = config.output_path.clone();
    let recent_lines = load_recent_lines(&config.output_path, config.max_steering_lines);
    write_recent_lines(&config.output_path, &recent_lines)?;
    let max_steering_lines = config.max_steering_lines;

    let (tx, rx) = unbounded::<String>();
    
    // Steering writer thread
    std::thread::spawn(move || {
        let mut recent_lines = recent_lines;

        while let Ok(command) = rx.recv() {
            let command = command.trim();
            if command.is_empty() {
                continue;
            }

            let timestamp = Local::now().format("%Y-%m-%d %H:%M:%S");
            println!("Steering agent with: {}", command);

            recent_lines.push_back(format!("[{}] {}", timestamp, command));
            while recent_lines.len() > max_steering_lines {
                recent_lines.pop_front();
            }

            if let Err(error) = write_recent_lines(&writer_output_path, &recent_lines) {
                eprintln!(
                    "Failed to write steering text to {}: {}",
                    writer_output_path.display(),
                    error
                );
            }
        }
    });

    let listener = TcpListener::bind(&config.bind_address)?;
    println!("Listening for Android STT input on {}", config.bind_address);
    println!("Writing steering text to {}", config.output_path.display());
    println!("Keeping last {} steering lines", config.max_steering_lines);

    for stream in listener.incoming() {
        if let Ok(stream) = stream {
            let peer = stream.peer_addr()?;
            println!("Client connected: {}", peer);

            let reader = BufReader::new(stream);
            let tx = tx.clone();

            std::thread::spawn(move || {
                for line in reader.lines() {
                    if let Ok(text) = line {
                        tx.send(text).unwrap();
                    }
                }
            });
        }
    }

    Ok(())
}

