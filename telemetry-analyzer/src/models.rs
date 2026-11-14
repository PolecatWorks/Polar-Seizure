use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AccelSample {
    pub t: i64,
    pub x: f64,
    pub y: f64,
    pub z: f64,
    #[serde(default)]
    pub m: f64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PpgSample {
    pub t: i64,
    #[serde(default)]
    pub c0: i64,
    #[serde(default)]
    pub c1: i64,
    pub bpm: Option<i32>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct IncidentFile {
    pub incident_id: String,
    pub saved_at: i64,
    #[serde(default)]
    pub acc_sample_count: usize,
    #[serde(default)]
    pub ppg_sample_count: usize,
    #[serde(default)]
    pub acc_samples: Vec<AccelSample>,
    #[serde(default)]
    pub ppg_samples: Vec<PpgSample>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(tag = "type")]
pub enum DailyLine {
    #[serde(rename = "acc")]
    Acc {
        t: i64,
        x: f64,
        y: f64,
        z: f64,
        #[serde(default)]
        m: f64,
    },
    #[serde(rename = "ppg")]
    Ppg {
        t: i64,
        #[serde(default)]
        c0: i64,
        #[serde(default)]
        c1: i64,
        bpm: Option<i32>,
    },
    #[serde(rename = "dsp")]
    Dsp {
        t: i64,
        bpm: Option<i32>,
        ratio: f64,
        band_power: f64,
        total_power: f64,
        state: String,
    },
    #[serde(other)]
    Other,
}
