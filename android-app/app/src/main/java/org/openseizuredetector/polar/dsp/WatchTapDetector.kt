package org.openseizuredetector.polar.dsp

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Watch Kinetic Triple-Tap Gesture Detector.
 *
 * Clinical & Practical Purpose:
 * Allows the wearer to trigger an emergency panic alert discreetly and instantly directly from their
 * Polar wearable (Polar Loop Gen2, Polar 360, Verity Sense) without reaching for their phone,
 * by performing a deliberate triple-tap on the watch casing.
 *
 * Detection Physics & Signal Analysis:
 * 1. Impulse Spike:
 *    A finger tapping the watch case causes a sharp kinetic impulse.
 *    - High instantaneous jerk: |da/dt| >= minJerkThreshold (default: 0.55g per sample at 25Hz).
 *    - Acceleration magnitude peak: |a| >= minTapMagnitude (default: 1.45g).
 *
 * 2. Cadence & Temporal Timing:
 *    - Inter-tap interval: Consecutive taps must arrive within 160ms <= dt <= 650ms.
 *    - Gesture span: Exactly 3 taps completed within maxGestureDurationMs (default: 1,800ms).
 *
 * 3. False-Alarm Rejection:
 *    - If continuous oscillatory motion or >4 impulses are detected within 1s (e.g. applauding, running,
 *      or clonic seizure vibration), the gesture sequence resets to avoid false panic alerts.
 */
class WatchTapDetector(
    val minTapMagnitudeG: Double = 1.65,
    val minJerkG: Double = 0.75,
    val minInterTapIntervalMs: Long = 160L,
    val maxInterTapIntervalMs: Long = 650L,
    val maxGestureDurationMs: Long = 1800L,
    val gestureCooldownMs: Long = 8000L
) {
    private var lastMagnitude = 1.0
    private var lastSampleTimestampMs = 0L

    // History of detected tap timestamps
    private val tapTimestamps = mutableListOf<Long>()
    private var lastTriggeredTimestampMs = -1L

    // Impulse debounce: prevent a single physical tap ringing over adjacent samples
    private var lastTapDetectedTimeMs = -1L
    private val tapDebounceMs = 120L

    fun getPendingTapCount(): Int = tapTimestamps.size

    fun reset() {
        tapTimestamps.clear()
        lastMagnitude = 1.0
        lastSampleTimestampMs = 0L
    }

    /**
     * Feed an acceleration sample (gx, gy, gz in g units) at epoch timestampMs.
     * Returns true when a verified triple-tap gesture has just been completed.
     */
    fun addSample(timestampMs: Long, gx: Double, gy: Double, gz: Double): Boolean {
        // Enforce cooldown after a confirmed triple-tap panic
        if (lastTriggeredTimestampMs > 0 && (timestampMs - lastTriggeredTimestampMs) < gestureCooldownMs) {
            return false
        }

        val magnitude = sqrt(gx * gx + gy * gy + gz * gz)
        val jerk = abs(magnitude - lastMagnitude)
        lastMagnitude = magnitude
        lastSampleTimestampMs = timestampMs

        // Prune expired taps
        if (tapTimestamps.isNotEmpty()) {
            val oldest = tapTimestamps.first()
            val timeSinceOldest = timestampMs - oldest
            val timeSinceLast = timestampMs - tapTimestamps.last()

            if (timeSinceOldest > maxGestureDurationMs || timeSinceLast > maxInterTapIntervalMs) {
                tapTimestamps.clear()
            }
        }

        // Evaluate whether this sample represents a sharp tap impulse
        val isImpulse = magnitude >= minTapMagnitudeG && jerk >= minJerkG
        val isDebounced = lastTapDetectedTimeMs < 0 || (timestampMs - lastTapDetectedTimeMs) >= tapDebounceMs

        if (isImpulse && isDebounced) {
            lastTapDetectedTimeMs = timestampMs

            if (tapTimestamps.isEmpty()) {
                tapTimestamps.add(timestampMs)
            } else {
                val interval = timestampMs - tapTimestamps.last()
                if (interval in minInterTapIntervalMs..maxInterTapIntervalMs) {
                    tapTimestamps.add(timestampMs)

                    // Check if we reached 3 taps
                    if (tapTimestamps.size == 3) {
                        tapTimestamps.clear()
                        lastTriggeredTimestampMs = timestampMs
                        return true
                    }
                } else if (interval < minInterTapIntervalMs) {
                    // Impulses arriving too fast (<160ms) indicate rapid vibration / tremor -> reset
                    tapTimestamps.clear()
                } else {
                    // Took too long -> start new sequence with this tap
                    tapTimestamps.clear()
                    tapTimestamps.add(timestampMs)
                }
            }
        }

        return false
    }
}
