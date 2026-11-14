package org.openseizuredetector.polar.dsp

import org.openseizuredetector.polar.model.SeizureAlarmState
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * 3-8 Hz Spectral Power Seizure Detection Engine.
 * Operates on acceleration vector magnitude: |a| = sqrt(x^2 + y^2 + z^2) - 1.0g.
 */
class SeizureFftEngine(
    val windowSize: Int = 128,
    val sampleRateHz: Double = 25.0,
    val warningRatioThreshold: Float = 0.55f,
    val minMovementPowerThreshold: Float = 0.05f
) {
    private val buffer = DoubleArray(windowSize)
    private var writeIndex = 0
    private var filled = false

    private var consecutiveWarningWindows = 0
    val warningWindowLimit = 2 // ~5 seconds at 50% overlap of 128 samples
    val alarmWindowLimit = 5   // ~12.5 seconds (calibrated to reject transient false alarms)

    private var lastBandPower: Double = 0.0
    private var lastTotalPower: Double = 0.0
    private var lastAnalysisResult: SeizureAnalysisResult? = null

    fun getLastBandPower(): Double = lastBandPower
    fun getLastTotalPower(): Double = lastTotalPower
    fun getLastAnalysis(): SeizureAnalysisResult? = lastAnalysisResult

    fun addSample(x: Double, y: Double, z: Double): SeizureAnalysisResult? {
        val magnitude = sqrt(x * x + y * y + z * z) - 1.0
        buffer[writeIndex] = magnitude
        writeIndex++

        if (writeIndex >= windowSize) {
            writeIndex = 0
            filled = true
        }

        if (filled && writeIndex % (windowSize / 2) == 0) {
            val result = processWindow()
            lastAnalysisResult = result
            return result
        }
        return null
    }

    fun processWindow(): SeizureAnalysisResult {
        val real = DoubleArray(windowSize)
        val imag = DoubleArray(windowSize)

        // Apply Hann window and copy
        for (i in 0 until windowSize) {
            val idx = (writeIndex + i) % windowSize
            val hann = 0.5 * (1.0 - cos(2.0 * PI * i / (windowSize - 1)))
            real[i] = buffer[idx] * hann
            imag[i] = 0.0
        }

        FastFourierTransform.fft(real, imag)

        val nyquistIndex = windowSize / 2
        val freqBinWidth = sampleRateHz / windowSize

        var totalPower = 0.0
        var seizurePower = 0.0
        var maxBinPower = 0.0
        var peakFreq = 0.0

        for (k in 1 until nyquistIndex) {
            val freq = k * freqBinWidth
            val power = real[k] * real[k] + imag[k] * imag[k]
            totalPower += power

            if (freq in 3.0..8.0) {
                seizurePower += power
                if (power > maxBinPower) {
                    maxBinPower = power
                    peakFreq = freq
                }
            }
        }

        lastBandPower = seizurePower
        lastTotalPower = totalPower

        val ratio = if (totalPower > 1e-6) (seizurePower / totalPower).toFloat() else 0.0f
        val totalPowerFloat = totalPower.toFloat()

        val state = evaluateState(ratio, totalPowerFloat)

        return SeizureAnalysisResult(
            fftRatio = ratio,
            peakFrequencyHz = peakFreq.toFloat(),
            totalPower = totalPowerFloat,
            state = state
        )
    }

    private fun evaluateState(ratio: Float, totalPower: Float): SeizureAlarmState {
        if (totalPower < minMovementPowerThreshold) {
            consecutiveWarningWindows = 0
            return SeizureAlarmState.NORMAL
        }

        if (ratio >= warningRatioThreshold) {
            consecutiveWarningWindows++
            return if (consecutiveWarningWindows >= alarmWindowLimit) {
                SeizureAlarmState.ALARM
            } else if (consecutiveWarningWindows >= warningWindowLimit) {
                SeizureAlarmState.WARNING
            } else {
                SeizureAlarmState.NORMAL
            }
        } else {
            consecutiveWarningWindows = 0
            return SeizureAlarmState.NORMAL
        }
    }
}

data class SeizureAnalysisResult(
    val fftRatio: Float,
    val peakFrequencyHz: Float,
    val totalPower: Float,
    val state: SeizureAlarmState
)
