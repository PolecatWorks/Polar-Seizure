use anyhow::Result;
use chrono::{Local, TimeZone};
use clap::{Parser, Subcommand};
use colored::*;
use comfy_table::modifiers::UTF8_ROUND_CORNERS;
use comfy_table::presets::UTF8_FULL;
use comfy_table::{Cell, Color, Table};
use std::path::PathBuf;
use std::process::Command;

mod fall;
mod fft;
mod models;
mod reader;
mod tap;

use fall::HighGFallDetector;
use fft::{AlarmState, SeizureFftEngine};
use tap::WatchTapDetector;

#[derive(Parser, Debug)]
#[command(
    name = "telemetry-analyzer",
    author = "Polar-Seizure Team",
    version = "0.1.0",
    about = "Polar-Seizure local telemetry analysis & false-positive calibration tool"
)]
struct Cli {
    #[command(subcommand)]
    command: Commands,
}

#[derive(Subcommand, Debug)]
enum Commands {
    /// Pull incident records and telemetry archives directly from a connected Android device via ADB
    Pull {
        #[arg(short, long, default_value = "./telemetry_data")]
        out_dir: PathBuf,
    },

    /// Inspect an incident file: calculate summary statistics, peak G, spectral energy, and reason
    Inspect {
        file: PathBuf,
    },

    /// Replay raw accelerometer data through the FFT, Fall, and Triple-Tap detectors to pinpoint triggers
    Replay {
        file: PathBuf,
        /// Minimum FFT ratio to flag warning/alarm (default: 0.50)
        #[arg(long, default_value_t = 0.50)]
        ratio_threshold: f64,
        /// Minimum movement power threshold (default: 0.05)
        #[arg(long, default_value_t = 0.05)]
        min_power: f64,
        /// Warning window limit (default: 2)
        #[arg(long, default_value_t = 2)]
        warning_windows: usize,
        /// Alarm window limit (default: 4)
        #[arg(long, default_value_t = 4)]
        alarm_windows: usize,
        /// Tap jerk threshold in G (default: 0.55)
        #[arg(long, default_value_t = 0.55)]
        tap_jerk: f64,
        /// Tap magnitude threshold in G (default: 1.45)
        #[arg(long, default_value_t = 1.45)]
        tap_mag: f64,
    },

    /// Scan and benchmark multiple incident files or daily telemetry archives across threshold grids to evaluate false positive reduction
    Tune {
        #[arg(default_value = "./telemetry_data")]
        data_dir: PathBuf,
    },
}

fn format_ts(ts_ms: i64) -> String {
    let secs = ts_ms / 1000;
    let nsecs = ((ts_ms % 1000) * 1_000_000) as u32;
    if let Some(dt) = Local.timestamp_opt(secs, nsecs).single() {
        dt.format("%Y-%m-%d %H:%M:%S.%3f").to_string()
    } else {
        ts_ms.to_string()
    }
}

fn main() -> Result<()> {
    let cli = Cli::parse();

    match cli.command {
        Commands::Pull { out_dir } => handle_pull(out_dir)?,
        Commands::Inspect { file } => handle_inspect(file)?,
        Commands::Replay {
            file,
            ratio_threshold,
            min_power,
            warning_windows,
            alarm_windows,
            tap_jerk,
            tap_mag,
        } => handle_replay(
            file,
            ratio_threshold,
            min_power,
            warning_windows,
            alarm_windows,
            tap_jerk,
            tap_mag,
        )?,
        Commands::Tune { data_dir } => handle_tune(data_dir)?,
    }

    Ok(())
}

fn handle_pull(out_dir: PathBuf) -> Result<()> {
    println!("{}", "=== Pulling Telemetry Data from Android Device via ADB ===".bold().cyan());
    std::fs::create_dir_all(&out_dir)?;

    let incidents_dir = out_dir.join("incidents");
    std::fs::create_dir_all(&incidents_dir)?;

    let adb_check = Command::new("adb").arg("devices").output();
    if adb_check.is_err() {
        eprintln!("{}", "Error: 'adb' command not found in PATH.".red().bold());
        return Ok(());
    }

    println!("Listing files on device...");
    let files_output = Command::new("adb")
        .args(["shell", "run-as", "org.openseizuredetector.polar", "ls", "files/incidents"])
        .output()?;

    let stdout_str = String::from_utf8_lossy(&files_output.stdout);
    let files: Vec<&str> = stdout_str
        .lines()
        .map(|l| l.trim())
        .filter(|l| l.ends_with(".json"))
        .collect();

    println!("Found {} incident files on device.", files.len().to_string().green().bold());

    for f in &files {
        let dest = incidents_dir.join(f);
        print!("Pulling {} ... ", f);
        let pull_cmd = format!(
            "adb shell run-as org.openseizuredetector.polar cat files/incidents/{} > {}",
            f,
            dest.display()
        );
        let status = Command::new("sh").arg("-c").arg(&pull_cmd).status()?;
        if status.success() {
            println!("{}", "OK".green());
        } else {
            println!("{}", "FAILED".red());
        }
    }

    // Also pull SQLite database for alert context
    println!("Pulling SQLite alert database...");
    let db_cmd = format!(
        "adb shell run-as org.openseizuredetector.polar cat databases/polar_alerts.db > {}",
        out_dir.join("polar_alerts.db").display()
    );
    let _ = Command::new("sh").arg("-c").arg(&db_cmd).status();

    println!("{}", "\nData synchronization complete!".green().bold());
    println!("Saved to: {}", out_dir.display().to_string().cyan());
    Ok(())
}

fn handle_inspect(file: PathBuf) -> Result<()> {
    println!("{}", format!("=== Inspecting Incident: {:?} ===", file.file_name().unwrap_or_default()).bold().cyan());

    let incident = reader::load_incident_file(&file)?;

    let acc_count = incident.acc_samples.len();
    let ppg_count = incident.ppg_samples.len();

    let duration_secs = if acc_count >= 2 {
        let t_start = incident.acc_samples.first().unwrap().t;
        let t_end = incident.acc_samples.last().unwrap().t;
        (t_end - t_start) as f64 / 1000.0
    } else {
        0.0
    };

    let sample_rate = if duration_secs > 0.0 {
        (acc_count as f64) / duration_secs
    } else {
        0.0
    };

    let mut peak_g = 0.0f64;
    let mut min_g = 999.0f64;
    let mut avg_g = 0.0f64;
    let mut hr_values = Vec::new();

    for s in &incident.acc_samples {
        let g = (s.x * s.x + s.y * s.y + s.z * s.z).sqrt();
        if g > peak_g {
            peak_g = g;
        }
        if g < min_g {
            min_g = g;
        }
        avg_g += g;
    }
    if acc_count > 0 {
        avg_g /= acc_count as f64;
    } else {
        min_g = 0.0;
    }

    for p in &incident.ppg_samples {
        if let Some(bpm) = p.bpm {
            if bpm > 0 {
                hr_values.push(bpm);
            }
        }
    }

    let avg_hr = if !hr_values.is_empty() {
        hr_values.iter().sum::<i32>() as f64 / hr_values.len() as f64
    } else {
        0.0
    };

    let mut table = Table::new();
    table
        .load_preset(UTF8_FULL)
        .apply_modifier(UTF8_ROUND_CORNERS)
        .set_header(vec![
            Cell::new("Metric").fg(Color::Yellow),
            Cell::new("Value").fg(Color::Green),
        ]);

    table.add_row(vec!["Incident ID", &incident.incident_id]);
    table.add_row(vec!["Saved Timestamp", &format_ts(incident.saved_at)]);
    table.add_row(vec!["ACC Samples", &acc_count.to_string()]);
    table.add_row(vec!["PPG Samples", &ppg_count.to_string()]);
    table.add_row(vec!["Duration", &format!("{:.2} s", duration_secs)]);
    table.add_row(vec!["Effective ACC Sample Rate", &format!("{:.1} Hz", sample_rate)]);
    table.add_row(vec!["Peak Acceleration", &format!("{:.2} g", peak_g)]);
    table.add_row(vec!["Min Acceleration", &format!("{:.2} g", min_g)]);
    table.add_row(vec!["Average Acceleration", &format!("{:.2} g", avg_g)]);
    table.add_row(vec!["Average Heart Rate", &format!("{:.1} BPM", avg_hr)]);

    println!("{table}");
    Ok(())
}

fn handle_replay(
    file: PathBuf,
    ratio_threshold: f64,
    min_power: f64,
    warning_windows: usize,
    alarm_windows: usize,
    tap_jerk: f64,
    tap_mag: f64,
) -> Result<()> {
    println!("{}", format!("=== DSP Replay Simulation: {:?} ===", file.file_name().unwrap_or_default()).bold().cyan());
    println!("Config: Ratio Thresh={:.2}, Min Power={:.3}, Warn Windows={}, Alarm Windows={}, Tap Jerk={:.2}g, Tap Mag={:.2}g\n",
        ratio_threshold, min_power, warning_windows, alarm_windows, tap_jerk, tap_mag
    );

    let incident = reader::load_incident_file(&file)?;
    if incident.acc_samples.is_empty() {
        println!("{}", "No accelerometer samples found in file.".yellow());
        return Ok(());
    }

    let mut fft_engine = SeizureFftEngine::new(
        128,
        25.0,
        ratio_threshold,
        min_power,
        warning_windows,
        alarm_windows,
    );
    let mut tap_detector = WatchTapDetector::new(tap_mag, tap_jerk, 160, 650, 1800, 8000);
    let mut fall_detector = HighGFallDetector::default_detector();

    let mut fft_warnings = Vec::new();
    let mut fft_alarms = Vec::new();
    let mut tap_triggers = Vec::new();
    let mut fall_triggers = Vec::new();

    let mut prev_alarm_state = AlarmState::Normal;

    for s in &incident.acc_samples {
        let ts = s.t;
        let (gx, gy, gz) = (s.x, s.y, s.z);

        if let Some(analysis) = fft_engine.add_sample(ts, gx, gy, gz) {
            if analysis.state != prev_alarm_state {
                match analysis.state {
                    AlarmState::Warning => {
                        fft_warnings.push((ts, analysis.clone()));
                    }
                    AlarmState::Alarm => {
                        fft_alarms.push((ts, analysis.clone()));
                    }
                    AlarmState::Normal => {}
                }
                prev_alarm_state = analysis.state;
            }
        }

        if tap_detector.add_sample(ts, gx, gy, gz) {
            tap_triggers.push(ts);
        }

        if fall_detector.add_sample(ts, gx, gy, gz) {
            fall_triggers.push(ts);
        }
    }

    let mut table = Table::new();
    table
        .load_preset(UTF8_FULL)
        .apply_modifier(UTF8_ROUND_CORNERS)
        .set_header(vec![
            Cell::new("Timestamp").fg(Color::Yellow),
            Cell::new("Trigger Type").fg(Color::Cyan),
            Cell::new("Details").fg(Color::White),
        ]);

    for (ts, res) in &fft_warnings {
        table.add_row(vec![
            format_ts(*ts),
            "FFT WARNING".yellow().to_string(),
            format!(
                "Ratio: {:.1}% (Thresh: {:.0}%), Band Pwr: {:.3}, Peak: {:.1} Hz",
                res.fft_ratio * 100.0,
                ratio_threshold * 100.0,
                res.seizure_power,
                res.peak_frequency_hz
            ),
        ]);
    }

    for (ts, res) in &fft_alarms {
        table.add_row(vec![
            format_ts(*ts),
            "FFT ALARM (SEIZURE)".red().bold().to_string(),
            format!(
                "Ratio: {:.1}%, Band Pwr: {:.3}, Peak: {:.1} Hz",
                res.fft_ratio * 100.0,
                res.seizure_power,
                res.peak_frequency_hz
            ),
        ]);
    }

    for ts in &tap_triggers {
        table.add_row(vec![
            format_ts(*ts),
            "WATCH TRIPLE-TAP PANIC".purple().bold().to_string(),
            format!("Cadence matched 3 impulses (Jerk >= {:.2}g, Mag >= {:.2}g)", tap_jerk, tap_mag),
        ]);
    }

    for ts in &fall_triggers {
        table.add_row(vec![
            format_ts(*ts),
            "HIGH-G FALL CONFIRMED".red().to_string(),
            "Impact > 3.5g followed by immobility > 10s".to_string(),
        ]);
    }

    println!("{table}");

    println!("\n{}", "=== Replay Detection Summary ===".bold());
    println!("Total FFT Warnings: {}", fft_warnings.len().to_string().yellow());
    println!("Total FFT Seizure Alarms: {}", fft_alarms.len().to_string().red());
    println!("Total Watch Tap Panics: {}", tap_triggers.len().to_string().purple());
    println!("Total High-G Falls: {}", fall_triggers.len().to_string().blue());

    Ok(())
}

fn handle_tune(data_dir: PathBuf) -> Result<()> {
    println!("{}", "=== Threshold Tuning & False Positive Calibration ===".bold().cyan());

    let incidents_dir = data_dir.join("incidents");
    let mut files = Vec::new();

    if incidents_dir.exists() {
        for entry in std::fs::read_dir(incidents_dir)? {
            let path = entry?.path();
            if path.extension().map_or(false, |ext| ext == "json") {
                files.push(path);
            }
        }
    }

    if files.is_empty() {
        println!("{}", "No incident files found in data directory. Run 'pull' first.".yellow());
        return Ok(());
    }

    println!("Loaded {} incident telemetry recordings for calibration grid search.\n", files.len());

    // Grid search across:
    // FFT warning ratio threshold: 0.50, 0.55, 0.60, 0.65
    // Consecutive alarm windows: 4 (~10s), 5 (~12.5s), 6 (~15s)
    // Tap Jerk threshold: 0.55, 0.70, 0.85, 1.00
    // Tap Magnitude: 1.45, 1.65, 1.85

    let test_configs = vec![
        ("Current Baseline", 0.50, 4, 0.55, 1.45),
        ("Higher FFT Ratio (55%)", 0.55, 4, 0.55, 1.45),
        ("Higher FFT Ratio (60%)", 0.60, 4, 0.55, 1.45),
        ("Longer Window (5 wins / 12.5s)", 0.50, 5, 0.55, 1.45),
        ("Longer Window (6 wins / 15s)", 0.50, 6, 0.55, 1.45),
        ("Tuned Tap (Jerk 0.75g, Mag 1.65g)", 0.50, 4, 0.75, 1.65),
        ("Tuned Tap (Jerk 0.85g, Mag 1.75g)", 0.50, 4, 0.85, 1.75),
        ("Strict Combo (Ratio 60%, Win 5, Tap 0.80g)", 0.60, 5, 0.80, 1.70),
    ];

    let mut table = Table::new();
    table
        .load_preset(UTF8_FULL)
        .apply_modifier(UTF8_ROUND_CORNERS)
        .set_header(vec![
            Cell::new("Configuration").fg(Color::Yellow),
            Cell::new("Ratio Thresh").fg(Color::Cyan),
            Cell::new("Alarm Wins").fg(Color::Cyan),
            Cell::new("Tap Jerk").fg(Color::Cyan),
            Cell::new("Tap Mag").fg(Color::Cyan),
            Cell::new("FFT Alarms").fg(Color::Red),
            Cell::new("Tap Triggers").fg(Color::Magenta),
            Cell::new("Total Alerts").fg(Color::Green),
        ]);

    for (name, ratio, alarm_wins, tap_jerk, tap_mag) in test_configs {
        let mut total_fft_alarms = 0;
        let mut total_tap_triggers = 0;

        for f in &files {
            if let Ok(incident) = reader::load_incident_file(f) {
                let mut fft = SeizureFftEngine::new(128, 25.0, ratio, 0.05, 2, alarm_wins);
                let mut tap = WatchTapDetector::new(tap_mag, tap_jerk, 160, 650, 1800, 8000);

                let mut in_alarm = false;
                for s in &incident.acc_samples {
                    let (gx, gy, gz) = (s.x, s.y, s.z);
                    if let Some(res) = fft.add_sample(s.t, gx, gy, gz) {
                        if res.state == AlarmState::Alarm && !in_alarm {
                            total_fft_alarms += 1;
                            in_alarm = true;
                        } else if res.state != AlarmState::Alarm {
                            in_alarm = false;
                        }
                    }

                    if tap.add_sample(s.t, gx, gy, gz) {
                        total_tap_triggers += 1;
                    }
                }
            }
        }

        let total = total_fft_alarms + total_tap_triggers;
        table.add_row(vec![
            name.to_string(),
            format!("{:.0}%", ratio * 100.0),
            alarm_wins.to_string(),
            format!("{:.2}g", tap_jerk),
            format!("{:.2}g", tap_mag),
            total_fft_alarms.to_string(),
            total_tap_triggers.to_string(),
            total.to_string(),
        ]);
    }

    println!("{table}");
    Ok(())
}
