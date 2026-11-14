#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum FallState {
    Monitoring,
    PostImpactObservation,
    FallConfirmed,
}

#[derive(Debug, Clone)]
pub struct HighGFallDetector {
    pub impact_threshold_g: f64,
    pub immobility_variance_threshold_g2: f64,
    pub immobility_required_ms: i64,
    pub max_observation_window_ms: i64,

    current_state: FallState,
    impact_timestamp_ms: i64,
    peak_impact_g: f64,

    buffer_size: usize,
    magnitude_buffer: Vec<f64>,
    buffer_count: usize,
    write_idx: usize,
}

impl HighGFallDetector {
    pub fn new(
        impact_threshold_g: f64,
        immobility_variance_threshold_g2: f64,
        immobility_required_ms: i64,
        max_observation_window_ms: i64,
    ) -> Self {
        let buffer_size = 25;
        Self {
            impact_threshold_g,
            immobility_variance_threshold_g2,
            immobility_required_ms,
            max_observation_window_ms,
            current_state: FallState::Monitoring,
            impact_timestamp_ms: 0,
            peak_impact_g: 0.0,
            buffer_size,
            magnitude_buffer: vec![0.0; buffer_size],
            buffer_count: 0,
            write_idx: 0,
        }
    }

    pub fn default_detector() -> Self {
        Self::new(3.5, 0.025, 10_000, 15_000)
    }

    pub fn reset(&mut self) {
        self.current_state = FallState::Monitoring;
        self.impact_timestamp_ms = 0;
        self.peak_impact_g = 0.0;
        self.buffer_count = 0;
        self.write_idx = 0;
    }

    pub fn add_sample(&mut self, timestamp_ms: i64, gx: f64, gy: f64, gz: f64) -> bool {
        let magnitude = (gx * gx + gy * gy + gz * gz).sqrt();

        self.magnitude_buffer[self.write_idx] = magnitude;
        self.write_idx = (self.write_idx + 1) % self.buffer_size;
        if self.buffer_count < self.buffer_size {
            self.buffer_count += 1;
        }

        match self.current_state {
            FallState::Monitoring => {
                if magnitude >= self.impact_threshold_g {
                    self.current_state = FallState::PostImpactObservation;
                    self.impact_timestamp_ms = timestamp_ms;
                    self.peak_impact_g = magnitude;
                }
                false
            }
            FallState::PostImpactObservation => {
                if magnitude > self.peak_impact_g {
                    self.peak_impact_g = magnitude;
                }

                let elapsed_since_impact = timestamp_ms - self.impact_timestamp_ms;

                if elapsed_since_impact > self.max_observation_window_ms {
                    self.reset();
                    return false;
                }

                let variance = self.calculate_recent_variance();

                if variance > self.immobility_variance_threshold_g2 {
                    if elapsed_since_impact < self.immobility_required_ms {
                        // Intentional movement detected before immobility requirement
                        self.reset();
                        return false;
                    }
                }

                if elapsed_since_impact >= self.immobility_required_ms {
                    self.current_state = FallState::FallConfirmed;
                    return true;
                }

                false
            }
            FallState::FallConfirmed => false,
        }
    }

    fn calculate_recent_variance(&self) -> f64 {
        if self.buffer_count < 2 {
            return 0.0;
        }
        let mean: f64 = self.magnitude_buffer[..self.buffer_count].iter().sum::<f64>()
            / (self.buffer_count as f64);
        let sum_sq_diff: f64 = self.magnitude_buffer[..self.buffer_count]
            .iter()
            .map(|&v| {
                let diff = v - mean;
                diff * diff
            })
            .sum();
        sum_sq_diff / (self.buffer_count as f64)
    }
}
