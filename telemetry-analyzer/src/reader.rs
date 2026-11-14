use anyhow::{Context, Result};
use flate2::read::GzDecoder;
use std::fs::File;
use std::io::{BufRead, BufReader, Read};
use std::path::Path;

use crate::models::{DailyLine, IncidentFile};

pub fn load_incident_file<P: AsRef<Path>>(path: P) -> Result<IncidentFile> {
    let file = File::open(path.as_ref())
        .with_context(|| format!("Failed to open incident file {:?}", path.as_ref()))?;
    let reader = BufReader::new(file);
    let incident: IncidentFile = serde_json::from_reader(reader)
        .with_context(|| format!("Failed to parse incident JSON {:?}", path.as_ref()))?;
    Ok(incident)
}

pub fn load_daily_telemetry<P: AsRef<Path>>(path: P) -> Result<Vec<DailyLine>> {
    let path_ref = path.as_ref();
    let file = File::open(path_ref)
        .with_context(|| format!("Failed to open daily telemetry file {:?}", path_ref))?;

    let is_gz = path_ref.extension().map_or(false, |ext| ext == "gz");

    let mut lines = Vec::new();
    if is_gz {
        let decoder = GzDecoder::new(file);
        let reader = BufReader::new(decoder);
        parse_lines(reader, &mut lines)?;
    } else {
        let reader = BufReader::new(file);
        parse_lines(reader, &mut lines)?;
    }

    Ok(lines)
}

fn parse_lines<R: Read>(reader: BufReader<R>, output: &mut Vec<DailyLine>) -> Result<()> {
    for line_result in reader.lines() {
        let line = match line_result {
            Ok(l) => l,
            Err(_) => break, // Gracefully handle truncated gz stream or EOF
        };
        let trimmed = line.trim();
        if trimmed.is_empty() {
            continue;
        }
        if let Ok(entry) = serde_json::from_str::<DailyLine>(trimmed) {
            output.push(entry);
        }
    }
    Ok(())
}
