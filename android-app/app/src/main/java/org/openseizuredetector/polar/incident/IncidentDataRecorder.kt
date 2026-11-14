package org.openseizuredetector.polar.incident

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.openseizuredetector.polar.ble.DiagnosticManager
import org.openseizuredetector.polar.ble.LogLevel

data class AccelSampleRecord(
    val timestamp: Long,
    val x: Float,
    val y: Float,
    val z: Float,
    val magnitude: Float
)

data class PpgSampleRecord(
    val timestamp: Long,
    val channel0: Long,
    val channel1: Long,
    val derivedBpm: Int?
)

data class IncidentFileInfo(
    val file: File,
    val incidentId: String,
    val savedAt: Long,
    val sizeBytes: Long,
    val accSampleCount: Int,
    val ppgSampleCount: Int
)

/**
 * Detailed telemetry container parsed on-demand for a single incident.
 */
data class IncidentTelemetryDetail(
    val incidentId: String,
    val savedAt: Long,
    val accSamples: List<AccelSampleRecord>,
    val ppgSamples: List<PpgSampleRecord>
)

/**
 * IncidentDataRecorder maintains rolling circular ring buffers of raw ACC and PPG telemetry.
 * When a suspect movement event or seizure alarm occurs, it flushes pre-incident history
 * and continuous high-rate telemetry to disk.
 */
class IncidentDataRecorder(private val context: Context) {

    companion object {
        // 100 seconds at 25 Hz ACC = 2500 samples (guaranteeing >= 90s pre-alert window)
        private const val MAX_ACC_BUFFER_SIZE = 2500
        // 100 seconds at 22 Hz PPG = 2200 samples (guaranteeing >= 90s pre-alert window)
        private const val MAX_PPG_BUFFER_SIZE = 2200
        private val DATE_FORMAT = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
    }

    private val accRingBuffer = ArrayDeque<AccelSampleRecord>(MAX_ACC_BUFFER_SIZE)
    private val ppgRingBuffer = ArrayDeque<PpgSampleRecord>(MAX_PPG_BUFFER_SIZE)

    private var isRecordingActiveIncident = false
    private var currentIncidentId: String? = null
    private var activeIncidentAccSamples = mutableListOf<AccelSampleRecord>()
    private var activeIncidentPpgSamples = mutableListOf<PpgSampleRecord>()

    @Synchronized
    fun recordAccel(timestamp: Long, x: Float, y: Float, z: Float, magnitude: Float) {
        val sample = AccelSampleRecord(timestamp, x, y, z, magnitude)
        if (accRingBuffer.size >= MAX_ACC_BUFFER_SIZE) {
            accRingBuffer.removeFirst()
        }
        accRingBuffer.addLast(sample)

        if (isRecordingActiveIncident) {
            activeIncidentAccSamples.add(sample)
        }
    }

    @Synchronized
    fun recordPpg(timestamp: Long, channel0: Long, channel1: Long, bpm: Int?) {
        val sample = PpgSampleRecord(timestamp, channel0, channel1, bpm)
        if (ppgRingBuffer.size >= MAX_PPG_BUFFER_SIZE) {
            ppgRingBuffer.removeFirst()
        }
        ppgRingBuffer.addLast(sample)

        if (isRecordingActiveIncident) {
            activeIncidentPpgSamples.add(sample)
        }
    }

    @Synchronized
    fun onIncidentEscalationTriggered(reason: String): String {
        if (!isRecordingActiveIncident) {
            isRecordingActiveIncident = true
            currentIncidentId = "incident_${DATE_FORMAT.format(Date())}"
            DiagnosticManager.log(
                LogLevel.INFO,
                "Incident recorder activated [$reason]. Retaining pre-incident buffers (${accRingBuffer.size} ACC, ${ppgRingBuffer.size} PPG)."
            )
            // Pre-seed active incident buffers with pre-incident rolling history
            activeIncidentAccSamples = accRingBuffer.toMutableList()
            activeIncidentPpgSamples = ppgRingBuffer.toMutableList()
            // Immediately save initial snapshot to disk so the pre-trigger buffer is instantly viewable
            flushActiveIncidentSnapshot(currentIncidentId!!)
        }
        return currentIncidentId ?: "incident_${DATE_FORMAT.format(Date())}"
    }

    @Synchronized
    fun flushActiveIncidentSnapshot(incidentId: String = currentIncidentId ?: "incident_${System.currentTimeMillis()}"): File? {
        val dir = File(context.filesDir, "incidents").apply { if (!exists()) mkdirs() }
        val file = File(dir, "$incidentId.json")
        val accCopy = activeIncidentAccSamples.toList()
        val ppgCopy = activeIncidentPpgSamples.toList()
        return try {
            val json = buildString {
                append("{\n")
                append("  \"incidentId\": \"$incidentId\",\n")
                append("  \"savedAt\": ${System.currentTimeMillis()},\n")
                append("  \"accSampleCount\": ${accCopy.size},\n")
                append("  \"ppgSampleCount\": ${ppgCopy.size},\n")
                append("  \"accSamples\": [\n")
                accCopy.forEachIndexed { i, s ->
                    append("    {\"t\":${s.timestamp},\"x\":${s.x},\"y\":${s.y},\"z\":${s.z},\"m\":${s.magnitude}}")
                    if (i < accCopy.size - 1) append(",")
                    append("\n")
                }
                append("  ],\n")
                append("  \"ppgSamples\": [\n")
                ppgCopy.forEachIndexed { i, s ->
                    append("    {\"t\":${s.timestamp},\"c0\":${s.channel0},\"c1\":${s.channel1},\"bpm\":${s.derivedBpm ?: "null"}}")
                    if (i < ppgCopy.size - 1) append(",")
                    append("\n")
                }
                append("  ]\n")
                append("}\n")
            }
            file.writeText(json)
            file
        } catch (e: Exception) {
            DiagnosticManager.log(LogLevel.ERROR, "Failed to flush incident snapshot: ${e.message}")
            null
        }
    }

    @Synchronized
    fun onIncidentDeescalated(): File? {
        if (!isRecordingActiveIncident) return null
        val incidentId = currentIncidentId ?: "incident_${System.currentTimeMillis()}"
        isRecordingActiveIncident = false
        currentIncidentId = null

        val dir = File(context.filesDir, "incidents")
        if (!dir.exists()) {
            dir.mkdirs()
        }

        val file = File(dir, "${incidentId}.json")
        try {
            val json = buildString {
                append("{\n")
                append("  \"incidentId\": \"$incidentId\",\n")
                append("  \"savedAt\": ${System.currentTimeMillis()},\n")
                append("  \"accSampleCount\": ${activeIncidentAccSamples.size},\n")
                append("  \"ppgSampleCount\": ${activeIncidentPpgSamples.size},\n")
                append("  \"accSamples\": [\n")
                activeIncidentAccSamples.forEachIndexed { i, s ->
                    append("    {\"t\":${s.timestamp},\"x\":${s.x},\"y\":${s.y},\"z\":${s.z},\"m\":${s.magnitude}}")
                    if (i < activeIncidentAccSamples.size - 1) append(",")
                    append("\n")
                }
                append("  ],\n")
                append("  \"ppgSamples\": [\n")
                activeIncidentPpgSamples.forEachIndexed { i, s ->
                    append("    {\"t\":${s.timestamp},\"c0\":${s.channel0},\"c1\":${s.channel1},\"bpm\":${s.derivedBpm ?: "null"}}")
                    if (i < activeIncidentPpgSamples.size - 1) append(",")
                    append("\n")
                }
                append("  ]\n")
                append("}\n")
            }
            file.writeText(json)
            DiagnosticManager.log(
                LogLevel.SUCCESS,
                "Incident saved to ${file.name} (${activeIncidentAccSamples.size} ACC, ${activeIncidentPpgSamples.size} PPG samples)."
            )
            return file
        } catch (e: Exception) {
            DiagnosticManager.log(LogLevel.ERROR, "Failed to save incident data: ${e.message}")
            return null
        } finally {
            activeIncidentAccSamples.clear()
            activeIncidentPpgSamples.clear()
        }
    }

    fun isRecording(): Boolean = isRecordingActiveIncident

    fun getCurrentIncidentId(): String? = currentIncidentId

    /**
     * Fast non-blocking listing of incidents.
     * CRITICAL: Reads only the first few bytes (header) of each file or derives from filename and length.
     * NEVER reads the entire multi-megabyte JSON file into memory during listing!
     */
    @Synchronized
    fun listRecordedIncidents(): List<IncidentFileInfo> {
        val dir = File(context.filesDir, "incidents")
        if (!dir.exists()) return emptyList()
        val files = dir.listFiles { f -> f.extension == "json" } ?: return emptyList()
        return files.map { f ->
            // Fast derivation without parsing full JSON payload
            val incidentId = f.nameWithoutExtension
            val savedAt = f.lastModified()
            val sizeBytes = f.length()
            // Estimate sample counts from file size if header read not needed, or read first 1KB
            var accCount = 0
            var ppgCount = 0
            try {
                // Read just the first 1KB to extract counts
                val headerBuffer = ByteArray(1024)
                f.inputStream().use { stream ->
                    val readBytes = stream.read(headerBuffer)
                    if (readBytes > 0) {
                        val header = String(headerBuffer, 0, readBytes, Charsets.UTF_8)
                        accCount = "\"accSampleCount\":\\s*(\\d+)".toRegex().find(header)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                        ppgCount = "\"ppgSampleCount\":\\s*(\\d+)".toRegex().find(header)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    }
                }
            } catch (_: Exception) {
                // Fallback to size estimation
                accCount = (sizeBytes / 70).toInt()
                ppgCount = (sizeBytes / 180).toInt()
            }

            IncidentFileInfo(
                file = f,
                incidentId = incidentId,
                savedAt = savedAt,
                sizeBytes = sizeBytes,
                accSampleCount = accCount,
                ppgSampleCount = ppgCount
            )
        }.sortedByDescending { it.savedAt }
    }

    /**
     * Asynchronously / On-demand loads and parses full incident details for the telemetry viewer.
     */
    fun loadIncidentDetail(file: File): IncidentTelemetryDetail? {
        if (!file.exists()) return null
        return try {
            val text = file.readText()
            val incidentId = "\"incidentId\":\\s*\"([^\"]+)\"".toRegex().find(text)?.groupValues?.get(1) ?: file.nameWithoutExtension
            val savedAt = "\"savedAt\":\\s*(\\d+)".toRegex().find(text)?.groupValues?.get(1)?.toLongOrNull() ?: file.lastModified()

            val accList = mutableListOf<AccelSampleRecord>()
            val accRegex = "\\{\"t\":(\\d+),\"x\":([-0-9.]+),\"y\":([-0-9.]+),\"z\":([-0-9.]+),\"m\":([-0-9.]+)\\}".toRegex()
            accRegex.findAll(text).forEach { match ->
                val t = match.groupValues[1].toLong()
                val x = match.groupValues[2].toFloat()
                val y = match.groupValues[3].toFloat()
                val z = match.groupValues[4].toFloat()
                val m = match.groupValues[5].toFloat()
                accList.add(AccelSampleRecord(t, x, y, z, m))
            }

            val ppgList = mutableListOf<PpgSampleRecord>()
            val ppgRegex = "\\{\"t\":(\\d+),\"c0\":(\\d+),\"c1\":(\\d+),\"bpm\":([0-9]+|null)\\}".toRegex()
            ppgRegex.findAll(text).forEach { match ->
                val t = match.groupValues[1].toLong()
                val c0 = match.groupValues[2].toLong()
                val c1 = match.groupValues[3].toLong()
                val bpmStr = match.groupValues[4]
                val bpm = if (bpmStr == "null") null else bpmStr.toIntOrNull()
                ppgList.add(PpgSampleRecord(t, c0, c1, bpm))
            }

            IncidentTelemetryDetail(
                incidentId = incidentId,
                savedAt = savedAt,
                accSamples = accList,
                ppgSamples = ppgList
            )
        } catch (e: Exception) {
            DiagnosticManager.log(LogLevel.ERROR, "Failed to load incident detail: ${e.message}")
            null
        }
    }

    @Synchronized
    fun deleteIncident(file: File): Boolean {
        return try {
            if (file.exists()) {
                val deleted = file.delete()
                if (deleted) {
                    DiagnosticManager.log(LogLevel.INFO, "Deleted recorded incident: ${file.name}")
                }
                deleted
            } else {
                false
            }
        } catch (e: Exception) {
            DiagnosticManager.log(LogLevel.ERROR, "Failed to delete incident file: ${e.message}")
            false
        }
    }
}
