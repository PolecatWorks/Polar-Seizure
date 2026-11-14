#[derive(Debug, Clone)]
pub struct WatchTapDetector {
    pub min_tap_magnitude_g: f64,
    pub min_jerk_g: f64,
    pub min_inter_tap_interval_ms: i64,
    pub max_inter_tap_interval_ms: i64,
    pub max_gesture_duration_ms: i64,
    pub gesture_cooldown_ms: i64,

    last_magnitude: f64,
    tap_timestamps: Vec<i64>,
    last_triggered_timestamp_ms: i64,
    last_tap_detected_time_ms: i64,
    tap_debounce_ms: i64,
}

impl WatchTapDetector {
    pub fn new(
        min_tap_magnitude_g: f64,
        min_jerk_g: f64,
        min_inter_tap_interval_ms: i64,
        max_inter_tap_interval_ms: i64,
        max_gesture_duration_ms: i64,
        gesture_cooldown_ms: i64,
    ) -> Self {
        Self {
            min_tap_magnitude_g,
            min_jerk_g,
            min_inter_tap_interval_ms,
            max_inter_tap_interval_ms,
            max_gesture_duration_ms,
            gesture_cooldown_ms,
            last_magnitude: 1.0,
            tap_timestamps: Vec::new(),
            last_triggered_timestamp_ms: -1,
            last_tap_detected_time_ms: -1,
            tap_debounce_ms: 120,
        }
    }

    pub fn default_detector() -> Self {
        Self::new(1.45, 0.55, 160, 650, 1800, 8000)
    }

    pub fn reset(&mut self) {
        self.tap_timestamps.clear();
        self.last_magnitude = 1.0;
        self.last_triggered_timestamp_ms = -1;
        self.last_tap_detected_time_ms = -1;
    }

    pub fn add_sample(&mut self, timestamp_ms: i64, gx: f64, gy: f64, gz: f64) -> bool {
        if self.last_triggered_timestamp_ms > 0
            && (timestamp_ms - self.last_triggered_timestamp_ms) < self.gesture_cooldown_ms
        {
            return false;
        }

        let magnitude = (gx * gx + gy * gy + gz * gz).sqrt();
        let jerk = (magnitude - self.last_magnitude).abs();
        self.last_magnitude = magnitude;

        // Prune expired taps
        self.tap_timestamps
            .retain(|&t| (timestamp_ms - t) <= self.max_gesture_duration_ms);

        // Check if current sample constitutes a physical tap impulse
        if magnitude >= self.min_tap_magnitude_g && jerk >= self.min_jerk_g {
            if self.last_tap_detected_time_ms > 0
                && (timestamp_ms - self.last_tap_detected_time_ms) < self.tap_debounce_ms
            {
                return false;
            }

            if let Some(&last_tap) = self.tap_timestamps.last() {
                let delta = timestamp_ms - last_tap;
                if delta < self.min_inter_tap_interval_ms {
                    // Impulses arrived too fast (vibration, shivering, clapping) -> reset cadence
                    self.tap_timestamps.clear();
                    self.last_tap_detected_time_ms = timestamp_ms;
                    return false;
                }
            }

            self.tap_timestamps.push(timestamp_ms);
            self.last_tap_detected_time_ms = timestamp_ms;

            if self.tap_timestamps.len() > 3 {
                // Too many rapid impulses -> likely clonic tremor or walking artifact, reset
                self.tap_timestamps.clear();
                return false;
            }

            if self.tap_timestamps.len() == 3 {
                let t1 = self.tap_timestamps[0];
                let t2 = self.tap_timestamps[1];
                let t3 = self.tap_timestamps[2];

                let interval_1 = t2 - t1;
                let interval_2 = t3 - t2;
                let total_duration = t3 - t1;

                if in_range(interval_1, self.min_inter_tap_interval_ms, self.max_inter_tap_interval_ms)
                    && in_range(interval_2, self.min_inter_tap_interval_ms, self.max_inter_tap_interval_ms)
                    && total_duration <= self.max_gesture_duration_ms
                {
                    self.tap_timestamps.clear();
                    self.last_triggered_timestamp_ms = timestamp_ms;
                    return true;
                } else {
                    self.tap_timestamps.remove(0);
                }
            }
        }

        false
    }
}

fn in_range(val: i64, min: i64, max: i64) -> bool {
    val >= min && val <= max
}
