use rustfft::num_complex::Complex;
use rustfft::FftPlanner;
use std::f64::consts::PI;
use std::sync::Arc;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum AlarmState {
    Normal,
    Warning,
    Alarm,
}

impl AlarmState {
    pub fn as_str(&self) -> &'static str {
        match self {
            AlarmState::Normal => "NORMAL",
            AlarmState::Warning => "WARNING",
            AlarmState::Alarm => "ALARM",
        }
    }
}

#[derive(Debug, Clone)]
pub struct FftWindowResult {
    pub timestamp: i64,
    pub fft_ratio: f64,
    pub peak_frequency_hz: f64,
    pub total_power: f64,
    pub seizure_power: f64,
    pub state: AlarmState,
}

#[derive(Clone)]
pub struct SeizureFftEngine {
    pub window_size: usize,
    pub sample_rate_hz: f64,
    pub warning_ratio_threshold: f64,
    pub min_movement_power_threshold: f64,
    pub warning_window_limit: usize,
    pub alarm_window_limit: usize,

    buffer: Vec<f64>,
    timestamps: Vec<i64>,
    write_index: usize,
    filled: bool,
    consecutive_warning_windows: usize,

    last_band_power: f64,
    last_total_power: f64,
    fft: Arc<dyn rustfft::Fft<f64>>,
}

impl SeizureFftEngine {
    pub fn new(
        window_size: usize,
        sample_rate_hz: f64,
        warning_ratio_threshold: f64,
        min_movement_power_threshold: f64,
        warning_window_limit: usize,
        alarm_window_limit: usize,
    ) -> Self {
        let mut planner = FftPlanner::new();
        let fft = planner.plan_fft_forward(window_size);

        Self {
            window_size,
            sample_rate_hz,
            warning_ratio_threshold,
            min_movement_power_threshold,
            warning_window_limit,
            alarm_window_limit,
            buffer: vec![0.0; window_size],
            timestamps: vec![0; window_size],
            write_index: 0,
            filled: false,
            consecutive_warning_windows: 0,
            last_band_power: 0.0,
            last_total_power: 0.0,
            fft,
        }
    }

    pub fn default_engine() -> Self {
        Self::new(128, 25.0, 0.50, 0.05, 2, 4)
    }

    pub fn add_sample(&mut self, timestamp: i64, x: f64, y: f64, z: f64) -> Option<FftWindowResult> {
        let magnitude = (x * x + y * y + z * z).sqrt() - 1.0;
        self.buffer[self.write_index] = magnitude;
        self.timestamps[self.write_index] = timestamp;
        self.write_index += 1;

        if self.write_index >= self.window_size {
            self.write_index = 0;
            self.filled = true;
        }

        if self.filled && self.write_index % (self.window_size / 2) == 0 {
            let result = self.process_window(timestamp);
            return Some(result);
        }

        None
    }

    fn process_window(&mut self, timestamp: i64) -> FftWindowResult {
        let mut complex_buf: Vec<Complex<f64>> = (0..self.window_size)
            .map(|i| {
                let idx = (self.write_index + i) % self.window_size;
                let hann = 0.5 * (1.0 - (2.0 * PI * (i as f64) / ((self.window_size - 1) as f64)).cos());
                Complex::new(self.buffer[idx] * hann, 0.0)
            })
            .collect();

        self.fft.process(&mut complex_buf);

        let nyquist_index = self.window_size / 2;
        let freq_bin_width = self.sample_rate_hz / (self.window_size as f64);

        let mut total_power = 0.0;
        let mut seizure_power = 0.0;
        let mut max_bin_power = 0.0;
        let mut peak_freq = 0.0;

        for k in 1..nyquist_index {
            let freq = (k as f64) * freq_bin_width;
            let power = complex_buf[k].norm_sqr();
            total_power += power;

            if freq >= 3.0 && freq <= 8.0 {
                seizure_power += power;
                if power > max_bin_power {
                    max_bin_power = power;
                    peak_freq = freq;
                }
            }
        }

        self.last_band_power = seizure_power;
        self.last_total_power = total_power;

        let ratio = if total_power > 1e-6 {
            seizure_power / total_power
        } else {
            0.0
        };

        let state = self.evaluate_state(ratio, total_power);

        FftWindowResult {
            timestamp,
            fft_ratio: ratio,
            peak_frequency_hz: peak_freq,
            total_power,
            seizure_power,
            state,
        }
    }

    fn evaluate_state(&mut self, ratio: f64, total_power: f64) -> AlarmState {
        if total_power < self.min_movement_power_threshold {
            self.consecutive_warning_windows = 0;
            return AlarmState::Normal;
        }

        if ratio >= self.warning_ratio_threshold {
            self.consecutive_warning_windows += 1;
            if self.consecutive_warning_windows >= self.alarm_window_limit {
                AlarmState::Alarm
            } else if self.consecutive_warning_windows >= self.warning_window_limit {
                AlarmState::Warning
            } else {
                AlarmState::Normal
            }
        } else {
            self.consecutive_warning_windows = 0;
            AlarmState::Normal
        }
    }
}
