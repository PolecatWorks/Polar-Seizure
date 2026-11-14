package org.openseizuredetector.polar.ble

import android.content.Context
import com.polar.sdk.api.PolarBleApi
import com.polar.sdk.api.PolarBleApiCallback
import com.polar.sdk.api.PolarBleApiDefaultImpl
import com.polar.sdk.api.model.PolarAccelerometerData
import com.polar.sdk.api.model.PolarDeviceInfo
import com.polar.sdk.api.model.PolarSensorSetting
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.disposables.Disposable
import java.util.UUID
import org.openseizuredetector.polar.dsp.HighGFallDetector
import org.openseizuredetector.polar.dsp.OpticalPulseEngine
import org.openseizuredetector.polar.dsp.SeizureFftEngine
import org.openseizuredetector.polar.dsp.WatchTapDetector
import org.openseizuredetector.polar.incident.DailyTelemetryLogger
import org.openseizuredetector.polar.incident.IncidentDataRecorder
import org.openseizuredetector.polar.model.SeizureAlarmState

class PolarBleClient(
    private val context: Context,
    private val onAlarmStateChanged: (SeizureAlarmState) -> Unit,
    private val onHighGFallDetected: () -> Unit = {},
    private val onWatchTripleTapDetected: () -> Unit = {}
) {
    private val disposables = CompositeDisposable()
    private var scanDisposable: Disposable? = null
    private var accStreamDisposable: Disposable? = null
    private var hrStreamDisposable: Disposable? = null
    private var ppiStreamDisposable: Disposable? = null
    private var ppgStreamDisposable: Disposable? = null

    private val fftEngine = SeizureFftEngine()
    private val fallDetector = HighGFallDetector()
    private val tapDetector = WatchTapDetector()
    private val pulseEngine = OpticalPulseEngine()
    val incidentRecorder = IncidentDataRecorder(context)
    val dailyLogger = DailyTelemetryLogger(context)
    private var connectedDeviceId: String? = null
    private var lastPacketTimestamp: Long = 0
    private var packetCount: Long = 0
    private var lastChartSampleTime: Long = 0

    private var lastScanAttemptTime: Long = 0
    private var isScanRetryScheduled: Boolean = false
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    // Adaptive Optical HR Duty Cycle & Escalation state
    private var isPpgDutyCycleActive = false
    private var isEscalatedForIncident = false
    private var isQueryingStreams = false
    private var hasPpgCapability = false
    private var lastEscalationTriggerTime: Long = 0
    private var consecutivePreSeizureWindows = 0
    private var dutyCycleStartTime: Long = 0
    private var lastMovementTime: Long = 0

    // Periodic Disconnected Scanner (every 5 minutes)
    private var isAutoReconnectScanning = false
    private val disconnectedScanRunnable = object : Runnable {
        override fun run() {
            if (connectedDeviceId == null) {
                DiagnosticManager.log(
                    LogLevel.INFO,
                    "Periodic reconnection check: device is disconnected. Scanning for previously used Polar device..."
                )
                startDisconnectedScan()
                handler.postDelayed(this, DISCONNECTED_SCAN_INTERVAL_MS)
            } else {
                stopDisconnectedScan()
            }
        }
    }

    private val ppgDutyCycleRunnable = object : Runnable {
        override fun run() {
            val deviceId = connectedDeviceId
            if (deviceId == null || !hasPpgCapability) return

            if (isEscalatedForIncident) {
                // If escalated, maintain continuous PPG sampling and re-check after duty window
                handler.postDelayed(this, PPG_DUTY_ACTIVE_DURATION_MS)
                return
            }

            val now = System.currentTimeMillis()

            if (isPpgDutyCycleActive) {
                // Active sampling window finished -> stop PPG stream to let LEDs rest
                DiagnosticManager.log(LogLevel.INFO, "PPG duty window complete. Stopping optical stream to preserve battery.")
                stopPpgStreaming()
                isPpgDutyCycleActive = false
                DiagnosticManager.updateTelemetry { it.copy(isPpgDutyCycleActive = false, isStreamingHr = false) }
                handler.postDelayed(this, PPG_DUTY_SLEEP_INTERVAL_MS)
            } else {
                // Resting interval complete. Check stillness gate:
                // If patient has been quietly resting / sleeping with no movement for > 10 minutes,
                // skip optical LED activation for this cycle to save battery.
                val timeSinceMovement = now - lastMovementTime
                if (lastMovementTime > 0 && timeSinceMovement >= STILLNESS_SKIP_INTERVAL_MS) {
                    DiagnosticManager.log(LogLevel.INFO, "Patient resting quietly for ${timeSinceMovement / 60000}m. Skipping routine PPG duty cycle to conserve battery.")
                    handler.postDelayed(this, PPG_DUTY_SLEEP_INTERVAL_MS)
                    return
                }

                // Start PPG sampling window
                DiagnosticManager.log(LogLevel.INFO, "PPG duty cycle starting optical sample window (auto-stops once pulse locks)...")
                isPpgDutyCycleActive = true
                dutyCycleStartTime = now
                DiagnosticManager.updateTelemetry { it.copy(isPpgDutyCycleActive = true) }
                startPpgStreaming(deviceId)
                handler.postDelayed(this, PPG_DUTY_ACTIVE_DURATION_MS)
            }
        }
    }

    companion object {
        private const val MIN_SCAN_INTERVAL_MS = 3000L
        private const val SCAN_RETRY_DELAY_MS = 5000L
        // Periodic auto-reconnect scan cadence when disconnected: 5 minutes (300 seconds)
        private const val DISCONNECTED_SCAN_INTERVAL_MS = 300_000L
        // Chart window: 5 minutes (300 seconds)
        // Record 1 point every 500ms = 600 points for a smooth 5-minute accelerometer & energy timeline
        private const val CHART_SAMPLE_INTERVAL_MS = 500L
        private const val CHART_MAX_POINTS = 600
        // HR usually reports 1 Hz; retain up to 5 minutes of HR history (300 points)
        private const val HR_MAX_POINTS = 300

        // Optical HR Duty Cycle: Active for up to 12s, then sleep for 300s (5 minutes)
        private const val PPG_DUTY_ACTIVE_DURATION_MS = 12_000L
        private const val PPG_DUTY_SLEEP_INTERVAL_MS = 300_000L
        // Minimum active time before early shutoff on pulse lock
        private const val PPG_MIN_LOCK_SAMPLE_MS = 5_000L
        // Stillness skip threshold: if resting with no movement for 10 min, skip routine PPG cycle
        private const val STILLNESS_SKIP_INTERVAL_MS = 600_000L
        // Hold time for incident escalation before reverting to duty-cycle (90s post-incident window)
        private const val INCIDENT_HOLD_DURATION_MS = 90_000L
        // Seizure-level spectral threshold: requires genuine sustained rhythmic energy in 3-8 Hz band
        // Full seizure alarm requires ratio >= 50% for 2+ windows (Power >= 0.05).
        // Incident escalation requires ratio >= 45% sustained for 2 consecutive windows (or active WARNING/ALARM).
        private const val SEIZURE_MIN_POWER = 0.10
        private const val SEIZURE_RATIO_PERCENT = 45.0
        private const val REQUIRED_CONSECUTIVE_WINDOWS = 2

        private const val PREFS_NAME = "polar_seizure_prefs"
        private const val KEY_LAST_CONNECTED_DEVICE_ID = "last_connected_device_id"
    }

    private val sharedPrefs by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun getLastConnectedDeviceId(): String? {
        return sharedPrefs.getString(KEY_LAST_CONNECTED_DEVICE_ID, null)
    }

    private fun saveLastConnectedDeviceId(deviceId: String) {
        sharedPrefs.edit().putString(KEY_LAST_CONNECTED_DEVICE_ID, deviceId).apply()
        DiagnosticManager.log(LogLevel.INFO, "Remembered last connected device: $deviceId")
    }

    val api: PolarBleApi = PolarBleApiDefaultImpl.defaultImplementation(
        context.applicationContext,
        setOf(
            PolarBleApi.PolarBleSdkFeature.FEATURE_HR,
            PolarBleApi.PolarBleSdkFeature.FEATURE_BATTERY_INFO,
            PolarBleApi.PolarBleSdkFeature.FEATURE_DEVICE_INFO,
            PolarBleApi.PolarBleSdkFeature.FEATURE_POLAR_ONLINE_STREAMING,
            PolarBleApi.PolarBleSdkFeature.FEATURE_POLAR_SDK_MODE
        )
    )

    init {
        setupCallbacks()
    }

    private fun setupCallbacks() {
        api.setApiCallback(object : PolarBleApiCallback() {
            override fun blePowerStateChanged(powered: Boolean) {
                DiagnosticManager.log(
                    if (powered) LogLevel.SUCCESS else LogLevel.WARN,
                    "Bluetooth radio powered state: ${if (powered) "ON" else "OFF"}"
                )
            }

            override fun hrNotificationReceived(identifier: String, sample: com.polar.sdk.api.model.PolarHrData.PolarHrSample) {
                val now = System.currentTimeMillis()
                DiagnosticManager.updateTelemetry { current ->
                    val updatedHrHistory = current.hrHistory.toMutableList()
                    updatedHrHistory.add(HrHistoryPoint(timestamp = now, bpm = sample.hr))
                    if (updatedHrHistory.size > HR_MAX_POINTS) {
                        updatedHrHistory.removeAt(0)
                    }
                    current.copy(
                        isStreamingHr = true,
                        heartRateBpm = sample.hr,
                        hrHistory = updatedHrHistory
                    )
                }
            }

            override fun deviceConnecting(polarDeviceInfo: PolarDeviceInfo) {
                DiagnosticManager.log(
                    LogLevel.INFO,
                    "Connecting to device: ${polarDeviceInfo.name} (${polarDeviceInfo.deviceId})..."
                )
                DiagnosticManager.updateTelemetry {
                    it.copy(
                        connectionState = "CONNECTING",
                        connectedDeviceId = polarDeviceInfo.deviceId,
                        connectedDeviceName = polarDeviceInfo.name
                    )
                }
            }

            override fun deviceConnected(polarDeviceInfo: PolarDeviceInfo) {
                connectedDeviceId = polarDeviceInfo.deviceId
                saveLastConnectedDeviceId(polarDeviceInfo.deviceId)
                stopDisconnectedScan()
                DiagnosticManager.log(
                    LogLevel.SUCCESS,
                    "Device connected: ${polarDeviceInfo.name} (${polarDeviceInfo.deviceId})"
                )
                DiagnosticManager.updateTelemetry {
                    it.copy(
                        connectionState = "CONNECTED",
                        connectedDeviceId = polarDeviceInfo.deviceId,
                        connectedDeviceName = polarDeviceInfo.name
                    )
                }
            }

            override fun deviceDisconnected(polarDeviceInfo: PolarDeviceInfo) {
                DiagnosticManager.log(
                    LogLevel.WARN,
                    "Device disconnected: ${polarDeviceInfo.name} (${polarDeviceInfo.deviceId})"
                )
                connectedDeviceId = null
                isQueryingStreams = false
                stopAccStreaming()
                stopHrStreaming()
                stopPpiStreaming()
                stopPpgStreaming()
                stopPpgDutyCycle()
                incidentRecorder.onIncidentDeescalated()
                pulseEngine.reset()
                DiagnosticManager.updateTelemetry {
                    it.copy(
                        connectionState = "DISCONNECTED",
                        connectedDeviceId = null,
                        connectedDeviceName = null,
                        batteryLevel = null,
                        heartRateBpm = null,
                        isStreamingAcc = false,
                        isStreamingHr = false,
                        isPpgDutyCycleActive = false,
                        isIncidentRecording = false
                    )
                }
                onAlarmStateChanged(SeizureAlarmState.DISCONNECTED)
                scheduleDisconnectedPeriodicScan()
            }

            override fun bleSdkFeatureReady(identifier: String, feature: PolarBleApi.PolarBleSdkFeature) {
                DiagnosticManager.log(
                    LogLevel.INFO,
                    "BLE feature ready for $identifier: $feature"
                )
                when (feature) {
                    PolarBleApi.PolarBleSdkFeature.FEATURE_POLAR_ONLINE_STREAMING -> {
                        queryAvailableStreamsAndStart(identifier)
                    }
                    PolarBleApi.PolarBleSdkFeature.FEATURE_HR -> {
                        // Delay HR start slightly so ACC PMD negotiation completes first without control point contention
                        handler.postDelayed({
                            startHrStreaming(identifier)
                        }, 1000L)
                    }
                    else -> {}
                }
            }

            override fun batteryLevelReceived(identifier: String, level: Int) {
                // Polar Loop with 25 Hz ACC, early pulse lock (~5-8s), and 5-min duty cycling achieves ~60+ hours (2.5+ days) from 100%.
                val estimatedHours = (level / 100.0) * 60.0
                DiagnosticManager.log(
                    LogLevel.INFO,
                    "Battery level received for $identifier: $level% (~${"%.1f".format(estimatedHours)}h remaining)"
                )
                DiagnosticManager.updateTelemetry {
                    it.copy(
                        batteryLevel = level,
                        batteryRemainingHoursEstimate = estimatedHours
                    )
                }
            }

            override fun disInformationReceived(identifier: String, uuid: UUID, value: String) {
                DiagnosticManager.log(
                    LogLevel.INFO,
                    "Device Info ($identifier): UUID $uuid = $value"
                )
            }

            override fun disInformationReceived(identifier: String, disInfo: com.polar.androidcommunications.api.ble.model.DisInfo) {
                DiagnosticManager.log(
                    LogLevel.INFO,
                    "Device Info ($identifier): ${disInfo.key} = ${disInfo.value}"
                )
            }

            override fun htsNotificationReceived(identifier: String, data: com.polar.sdk.api.model.PolarHealthThermometerData) {
                // Not used
            }
        })
    }

    fun startScan(isRetry: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!isRetry && (now - lastScanAttemptTime) < MIN_SCAN_INTERVAL_MS) {
            DiagnosticManager.log(LogLevel.WARN, "Scan request throttled (last scan was ${(now - lastScanAttemptTime)}ms ago). Please wait.")
            return
        }
        lastScanAttemptTime = now

        stopScan()
        if (!isRetry) {
            DiagnosticManager.clearDevices()
        }
        DiagnosticManager.log(LogLevel.INFO, "Starting BLE scan for nearby Polar devices...")

        scanDisposable = api.searchForDevice()
            .subscribe(
                { deviceInfo ->
                    DiagnosticManager.addOrUpdateDevice(
                        DiscoveredDevice(
                            deviceId = deviceInfo.deviceId,
                            name = deviceInfo.name.ifEmpty { "Polar Device" },
                            rssi = deviceInfo.rssi,
                            isConnectable = deviceInfo.isConnectable
                        )
                    )
                    DiagnosticManager.log(
                        LogLevel.INFO,
                        "Discovered: ${deviceInfo.name} (${deviceInfo.deviceId}) RSSI: ${deviceInfo.rssi} dBm"
                    )

                    val rememberedId = getLastConnectedDeviceId()
                    if (rememberedId != null && deviceInfo.deviceId.equals(rememberedId, ignoreCase = true) && connectedDeviceId == null) {
                        DiagnosticManager.log(
                            LogLevel.SUCCESS,
                            "Discovered remembered device $rememberedId! Auto-connecting..."
                        )
                        connect(deviceInfo.deviceId)
                    }
                },
                { error ->
                    val errorMsg = error.localizedMessage ?: error.javaClass.simpleName
                    val isRegistrationError = errorMsg.contains("errorCode: 2") || errorMsg.contains("SCAN_FAILED_APPLICATION_REGISTRATION_FAILED")

                    if (isRegistrationError) {
                        DiagnosticManager.log(
                            LogLevel.ERROR,
                            "BLE scanner registration busy/throttled (ScanCallback errorCode: 2)."
                        )
                        if (!isScanRetryScheduled) {
                            isScanRetryScheduled = true
                            DiagnosticManager.log(
                                LogLevel.WARN,
                                "Android BT daemon busy. Automatically retrying scan in ${SCAN_RETRY_DELAY_MS / 1000}s. You can also connect directly by device ID or toggle Bluetooth OFF/ON in Android Settings."
                            )
                            handler.postDelayed({
                                isScanRetryScheduled = false
                                startScan(isRetry = true)
                            }, SCAN_RETRY_DELAY_MS)
                        }
                    } else {
                        DiagnosticManager.log(
                            LogLevel.ERROR,
                            "BLE Scan error: $errorMsg"
                        )
                    }
                }
            )
        disposables.add(scanDisposable!!)
    }

    fun stopScan() {
        handler.removeCallbacksAndMessages(null)
        isScanRetryScheduled = false
        scanDisposable?.let {
            if (!it.isDisposed) {
                it.dispose()
                DiagnosticManager.log(LogLevel.INFO, "BLE scan stopped.")
            }
        }
        scanDisposable = null
    }

    /**
     * Schedules periodic BLE scan execution every 5 minutes while the device remains disconnected.
     */
    fun scheduleDisconnectedPeriodicScan() {
        if (connectedDeviceId != null) return
        handler.removeCallbacks(disconnectedScanRunnable)
        DiagnosticManager.log(
            LogLevel.INFO,
            "Device disconnected. Scheduling periodic BLE background scan every ${DISCONNECTED_SCAN_INTERVAL_MS / 60000} minutes."
        )
        handler.postDelayed(disconnectedScanRunnable, DISCONNECTED_SCAN_INTERVAL_MS)
    }

    private fun startDisconnectedScan() {
        if (connectedDeviceId != null) {
            stopDisconnectedScan()
            return
        }
        isAutoReconnectScanning = true
        startScan(isRetry = false)

        // Run disconnected scan for 20 seconds, then stop and wait for next 5-minute tick
        handler.postDelayed({
            if (isAutoReconnectScanning && connectedDeviceId == null) {
                DiagnosticManager.log(
                    LogLevel.INFO,
                    "Periodic 20s scan window complete. Waiting ${DISCONNECTED_SCAN_INTERVAL_MS / 60000} mins for next scan..."
                )
                stopScan()
                isAutoReconnectScanning = false
            }
        }, 20_000L)
    }

    fun stopDisconnectedScan() {
        handler.removeCallbacks(disconnectedScanRunnable)
        isAutoReconnectScanning = false
    }

    fun autoConnectIfRemembered(): Boolean {
        val rememberedId = getLastConnectedDeviceId()
        if (!rememberedId.isNullOrEmpty() && connectedDeviceId == null) {
            DiagnosticManager.log(
                LogLevel.INFO,
                "Auto-connect target identified: $rememberedId. Initiating direct connection..."
            )
            connect(rememberedId)
            return true
        }
        return false
    }

    fun connect(deviceId: String) {
        stopScan()
        try {
            DiagnosticManager.log(LogLevel.INFO, "Requesting connection to $deviceId...")
            api.connectToDevice(deviceId)
        } catch (e: Exception) {
            DiagnosticManager.log(LogLevel.ERROR, "Failed to connect to $deviceId: ${e.localizedMessage}")
        }
    }

    fun disconnect(deviceId: String) {
        DiagnosticManager.log(LogLevel.INFO, "Disconnect requested for $deviceId. Stopping streams...")
        stopAccStreaming()
        stopHrStreaming()
        stopPpiStreaming()
        stopPpgStreaming()
        stopPpgDutyCycle()
        incidentRecorder.onIncidentDeescalated()
        try {
            DiagnosticManager.log(LogLevel.INFO, "Calling api.disconnectFromDevice($deviceId)...")
            api.disconnectFromDevice(deviceId)
            // Proactively update telemetry so UI state reflects immediate disconnection intent
            if (connectedDeviceId == deviceId) {
                connectedDeviceId = null
                DiagnosticManager.updateTelemetry {
                    it.copy(
                        connectionState = "DISCONNECTED",
                        connectedDeviceId = null,
                        connectedDeviceName = null,
                        batteryLevel = null,
                        heartRateBpm = null,
                        isStreamingAcc = false,
                        isStreamingHr = false,
                        isPpgDutyCycleActive = false,
                        isIncidentRecording = false
                    )
                }
                scheduleDisconnectedPeriodicScan()
            }
        } catch (e: Exception) {
            DiagnosticManager.log(LogLevel.ERROR, "Failed to disconnect $deviceId: ${e.localizedMessage}")
        }
    }

    private fun queryAvailableStreamsAndStart(deviceId: String) {
        // Guard against duplicate callback executions during BLE GATT negotiation
        if (isQueryingStreams || accStreamDisposable != null || isPpgDutyCycleActive || ppgStreamDisposable != null) {
            DiagnosticManager.log(LogLevel.INFO, "Streams already initializing or active for $deviceId. Skipping redundant query.")
            return
        }
        isQueryingStreams = true
        val queryDisposable = api.getAvailableOnlineStreamDataTypes(deviceId)
            .subscribe(
                { availableTypes ->
                    isQueryingStreams = false
                    DiagnosticManager.log(
                        LogLevel.SUCCESS,
                        "Online stream data types supported by $deviceId: $availableTypes"
                    )
                    if (availableTypes.contains(PolarBleApi.PolarDeviceDataType.ACC)) {
                        startAccStreaming(deviceId)
                    } else {
                        DiagnosticManager.log(
                            LogLevel.WARN,
                            "$deviceId does not support real-time ACC online streaming (available: $availableTypes)."
                        )
                    }
                    // For optical wrist devices like Polar Loop / Polar 360 in SDK Mode, PPI/HR streams are disallowed by device firmware.
                    // Prioritize raw optical PPG stream (22 Hz central green channels) to compute pulse rate on-phone.
                    if (availableTypes.contains(PolarBleApi.PolarDeviceDataType.PPG)) {
                        hasPpgCapability = true
                        handler.postDelayed({
                            startPpgDutyCycle(deviceId)
                        }, 2500L)
                    } else if (availableTypes.contains(PolarBleApi.PolarDeviceDataType.PPI)) {
                        handler.postDelayed({
                            startPpiStreaming(deviceId)
                        }, 2500L)
                    }
                },
                { error ->
                    isQueryingStreams = false
                    DiagnosticManager.log(
                        LogLevel.WARN,
                        "Could not query available stream types ($error). Attempting direct ACC stream..."
                    )
                    startAccStreaming(deviceId)
                }
            )
        disposables.add(queryDisposable)
    }

    private fun startAccStreaming(deviceId: String) {
        stopAccStreaming()
        DiagnosticManager.log(LogLevel.INFO, "Enabling SDK mode for $deviceId to configure ACC stream...")

        val sdkModeDisposable = api.enableSDKMode(deviceId)
            .onErrorComplete()
            .subscribe(
                {
                    DiagnosticManager.log(LogLevel.INFO, "SDK mode enabled (or skipped). Requesting ACC stream settings for $deviceId...")
                    requestAndStartAccStream(deviceId)
                },
                { error ->
                    DiagnosticManager.log(LogLevel.WARN, "enableSDKMode error: ${error.localizedMessage}. Proceeding to request ACC settings directly...")
                    requestAndStartAccStream(deviceId)
                }
            )
        disposables.add(sdkModeDisposable)
    }

    private fun requestAndStartAccStream(deviceId: String) {
        val streamSettingsDisposable = api.requestStreamSettings(deviceId, PolarBleApi.PolarDeviceDataType.ACC)
            .subscribe(
                { settings ->
                    DiagnosticManager.log(
                        LogLevel.SUCCESS,
                        "Supported ACC settings received from $deviceId: ${settings.settings}"
                    )

                    val desiredSettings = settings.maxSettings()
                    DiagnosticManager.log(
                        LogLevel.INFO,
                        "Starting ACC stream with settings: ${desiredSettings.settings}..."
                    )

                    accStreamDisposable = api.startAccStreaming(deviceId, desiredSettings)
                        .subscribe(
                            { accData ->
                                onAccDataReceived(accData)
                            },
                            { error ->
                                DiagnosticManager.log(
                                    LogLevel.ERROR,
                                    "ACC streaming error: ${error.localizedMessage ?: error.javaClass.simpleName}"
                                )
                                DiagnosticManager.updateTelemetry { it.copy(isStreamingAcc = false) }
                            }
                        )
                    disposables.add(accStreamDisposable!!)
                    DiagnosticManager.updateTelemetry { it.copy(isStreamingAcc = true) }
                },
                { error ->
                    DiagnosticManager.log(
                        LogLevel.WARN,
                        "Could not query ACC settings ($error). Attempting direct default stream..."
                    )
                    accStreamDisposable = api.startAccStreaming(deviceId, PolarSensorSetting(emptyMap()))
                        .subscribe(
                            { accData -> onAccDataReceived(accData) },
                            { streamErr ->
                                DiagnosticManager.log(
                                    LogLevel.ERROR,
                                    "Default ACC stream failed: ${streamErr.localizedMessage}"
                                )
                            }
                        )
                    disposables.add(accStreamDisposable!!)
                }
            )
        disposables.add(streamSettingsDisposable)
    }

    private fun onAccDataReceived(accData: PolarAccelerometerData) {
        val now = System.currentTimeMillis()
        val latency = if (lastPacketTimestamp > 0) now - lastPacketTimestamp else 0
        lastPacketTimestamp = now
        packetCount++

        for (sample in accData.samples) {
            val gx = sample.x / 1000.0
            val gy = sample.y / 1000.0
            val gz = sample.z / 1000.0

            val analysis = fftEngine.addSample(gx, gy, gz)
            if (analysis != null) {
                onAlarmStateChanged(analysis.state)
            }

            // High-G fall / impact detection (PRD-003 §7.2)
            val fallConfirmed = fallDetector.addSample(now, gx, gy, gz)
            if (fallConfirmed) {
                DiagnosticManager.log(LogLevel.WARN, "High-G Fall impact and stillness confirmed (${String.format(java.util.Locale.US, "%.1fg", fallDetector.getPeakImpactG())})")
                onHighGFallDetected()
            }

            // Wearer Triple-Tap Panic gesture detection on watch casing
            val tripleTapConfirmed = tapDetector.addSample(now, gx, gy, gz)
            if (tripleTapConfirmed) {
                DiagnosticManager.log(LogLevel.WARN, "Deliberate Watch Triple-Tap detected! Triggering immediate wearer panic alert.")
                onWatchTripleTapDetected()
            }

            val magnitude = Math.sqrt(gx * gx + gy * gy + gz * gz) - 1.0
            incidentRecorder.recordAccel(now, gx.toFloat(), gy.toFloat(), gz.toFloat(), magnitude.toFloat())
            dailyLogger.logAccel(now, gx.toFloat(), gy.toFloat(), gz.toFloat(), magnitude.toFloat())

            val bandPower = fftEngine.getLastBandPower()
            val totalPower = fftEngine.getLastTotalPower()
            val ratio = if (totalPower > 0.0) (bandPower / totalPower) * 100.0 else 0.0

            if (totalPower > 0.02) {
                lastMovementTime = now
            }

            if (analysis != null) {
                val bpm = pulseEngine.getLastBpm().takeIf { it > 0 }
                dailyLogger.logDsp(now, bpm, ratio.toFloat(), bandPower, totalPower, analysis.state.name)
            }

            // Evaluate incident escalation on FFT window analysis (or check cooldown)
            if (analysis != null || isEscalatedForIncident) {
                checkAndHandleIncidentEscalation(bandPower, totalPower, ratio, analysis?.state)
            }

            // Time-based chart sampling: record 1 point every 100ms for a true 30-second window (300 points)
            val shouldRecordChartPoint = (now - lastChartSampleTime) >= CHART_SAMPLE_INTERVAL_MS
            val historyPoint = if (shouldRecordChartPoint) {
                lastChartSampleTime = now
                AccelHistoryPoint(
                    timestamp = now,
                    x = gx.toFloat(),
                    y = gy.toFloat(),
                    z = gz.toFloat(),
                    magnitude = magnitude.toFloat(),
                    energyRatio = ratio.toFloat()
                )
            } else null

            DiagnosticManager.updateTelemetry { current ->
                val newHistory = if (historyPoint != null) {
                    val updated = current.accelHistory.toMutableList()
                    updated.add(historyPoint)
                    if (updated.size > CHART_MAX_POINTS) {
                        updated.removeAt(0)
                    }
                    updated
                } else {
                    current.accelHistory
                }

                current.copy(
                    isStreamingAcc = true,
                    lastPacketLatencyMs = latency,
                    lastAccX = gx,
                    lastAccY = gy,
                    lastAccZ = gz,
                    lastMagnitude = magnitude,
                    seizurePower3to8Hz = bandPower,
                    totalPower = totalPower,
                    energyRatioPercent = ratio,
                    isStillnessGated = bandPower == 0.0 && totalPower == 0.0,
                    accelHistory = newHistory
                )
            }
        }
    }

    private fun startHrStreaming(deviceId: String) {
        stopHrStreaming()
        DiagnosticManager.log(LogLevel.INFO, "Starting HR stream for $deviceId...")

        hrStreamDisposable = api.startHrStreaming(deviceId)
            .subscribe(
                { hrData ->
                    val now = System.currentTimeMillis()
                    for (sample in hrData.samples) {
                        DiagnosticManager.updateTelemetry { current ->
                            val updatedHrHistory = current.hrHistory.toMutableList()
                            updatedHrHistory.add(HrHistoryPoint(timestamp = now, bpm = sample.hr))
                            if (updatedHrHistory.size > HR_MAX_POINTS) {
                                updatedHrHistory.removeAt(0)
                            }
                            current.copy(
                                isStreamingHr = true,
                                heartRateBpm = sample.hr,
                                hrHistory = updatedHrHistory
                            )
                        }
                    }
                },
                { error ->
                    DiagnosticManager.log(
                        LogLevel.WARN,
                        "HR stream unavailable or failed for $deviceId: ${error.localizedMessage ?: error.javaClass.simpleName}"
                    )
                    DiagnosticManager.updateTelemetry { it.copy(isStreamingHr = false) }
                }
            )
        disposables.add(hrStreamDisposable!!)
    }

    fun stopHrStreaming() {
        hrStreamDisposable?.let {
            if (!it.isDisposed) {
                it.dispose()
                DiagnosticManager.log(LogLevel.INFO, "HR stream stopped.")
            }
        }
        hrStreamDisposable = null
    }

    private fun startPpiStreaming(deviceId: String) {
        stopPpiStreaming()
        DiagnosticManager.log(LogLevel.INFO, "Starting PPI optical stream for $deviceId...")

        ppiStreamDisposable = api.startPpiStreaming(deviceId)
            .subscribe(
                { ppiData ->
                    val now = System.currentTimeMillis()
                    for (sample in ppiData.samples) {
                        // Accept samples that are not marked as motion blocker artifacts and have valid HR
                        if (sample.hr > 0 && !sample.blockerBit) {
                            DiagnosticManager.updateTelemetry { current ->
                                val updatedHrHistory = current.hrHistory.toMutableList()
                                updatedHrHistory.add(HrHistoryPoint(timestamp = now, bpm = sample.hr))
                                if (updatedHrHistory.size > HR_MAX_POINTS) {
                                    updatedHrHistory.removeAt(0)
                                }
                                current.copy(
                                    isStreamingHr = true,
                                    heartRateBpm = sample.hr,
                                    hrHistory = updatedHrHistory
                                )
                            }
                        }
                    }
                },
                { error ->
                    DiagnosticManager.log(
                        LogLevel.WARN,
                        "PPI stream failed for $deviceId: ${error.localizedMessage ?: error.javaClass.simpleName}"
                    )
                }
            )
        disposables.add(ppiStreamDisposable!!)
    }

    fun stopPpiStreaming() {
        ppiStreamDisposable?.let {
            if (!it.isDisposed) {
                it.dispose()
                DiagnosticManager.log(LogLevel.INFO, "PPI stream stopped.")
            }
        }
        ppiStreamDisposable = null
    }

    fun triggerIncidentHold(reason: String) {
        val now = System.currentTimeMillis()
        lastEscalationTriggerTime = now
        isEscalatedForIncident = true
        incidentRecorder.onIncidentEscalationTriggered(reason)
        DiagnosticManager.updateTelemetry { it.copy(isIncidentRecording = true) }

        val deviceId = connectedDeviceId
        if (deviceId != null && hasPpgCapability && ppgStreamDisposable == null) {
            DiagnosticManager.log(LogLevel.INFO, "Escalating optical PPG continuous streaming for incident: $reason")
            startPpgStreaming(deviceId)
        }
    }

    private fun checkAndHandleIncidentEscalation(
        bandPower: Double,
        totalPower: Double,
        ratioPercent: Double,
        state: SeizureAlarmState?
    ) {
        val now = System.currentTimeMillis()
        // Strict seizure-level condition: requires real movement power and high concentration in 3-8 Hz
        val isSustainedRhythmic = totalPower >= SEIZURE_MIN_POWER && ratioPercent >= SEIZURE_RATIO_PERCENT
        if (isSustainedRhythmic) {
            consecutivePreSeizureWindows++
        } else if (state != SeizureAlarmState.WARNING && state != SeizureAlarmState.ALARM) {
            consecutivePreSeizureWindows = 0
        }

        val isSuspectMovement = consecutivePreSeizureWindows >= REQUIRED_CONSECUTIVE_WINDOWS ||
                state == SeizureAlarmState.WARNING ||
                state == SeizureAlarmState.ALARM

        if (isSuspectMovement) {
            lastEscalationTriggerTime = now
            if (!isEscalatedForIncident) {
                isEscalatedForIncident = true
                val reason = when (state) {
                    SeizureAlarmState.ALARM -> "Seizure ALARM"
                    SeizureAlarmState.WARNING -> "Seizure WARNING"
                    else -> "Sustained 3-8 Hz seizure rhythm: ${"%.1f".format(ratioPercent)}% (Power: ${"%.3f".format(bandPower)})"
                }
                incidentRecorder.onIncidentEscalationTriggered(reason)
                DiagnosticManager.updateTelemetry { it.copy(isIncidentRecording = true) }

                // Escalate optical PPG streaming immediately if device has PPG support
                val deviceId = connectedDeviceId
                if (deviceId != null && hasPpgCapability && ppgStreamDisposable == null) {
                    DiagnosticManager.log(LogLevel.INFO, "Sustained seizure spectral energy detected ($reason). Escalating PPG to continuous stream...")
                    startPpgStreaming(deviceId)
                }
            }
        } else if (isEscalatedForIncident) {
            // Check if cooldown period elapsed without further suspect movements
            if (now - lastEscalationTriggerTime >= INCIDENT_HOLD_DURATION_MS) {
                isEscalatedForIncident = false
                consecutivePreSeizureWindows = 0
                DiagnosticManager.log(LogLevel.INFO, "Incident hold period ended. De-escalating and flushing forensic record.")
                incidentRecorder.onIncidentDeescalated()
                DiagnosticManager.updateTelemetry { it.copy(isIncidentRecording = false) }

                // If currently outside an active duty-cycle window, stop PPG stream to resume battery savings
                if (!isPpgDutyCycleActive) {
                    stopPpgStreaming()
                    DiagnosticManager.updateTelemetry { it.copy(isStreamingHr = false) }
                }
            }
        }
    }

    private fun startPpgDutyCycle(deviceId: String) {
        stopPpgDutyCycle()
        DiagnosticManager.log(LogLevel.INFO, "Starting adaptive PPG duty cycle (15s active every 2 mins)...")
        isPpgDutyCycleActive = true
        dutyCycleStartTime = System.currentTimeMillis()
        DiagnosticManager.updateTelemetry { it.copy(isPpgDutyCycleActive = true) }
        startPpgStreaming(deviceId)
        handler.postDelayed(ppgDutyCycleRunnable, PPG_DUTY_ACTIVE_DURATION_MS)
    }

    private fun stopPpgDutyCycle() {
        handler.removeCallbacks(ppgDutyCycleRunnable)
        isPpgDutyCycleActive = false
        isEscalatedForIncident = false
    }

    private fun startPpgStreaming(deviceId: String) {
        stopPpgStreaming()
        DiagnosticManager.log(LogLevel.INFO, "Requesting PPG stream settings for $deviceId...")

        val ppgSettingsDisposable = api.requestStreamSettings(deviceId, PolarBleApi.PolarDeviceDataType.PPG)
            .subscribe(
                { settings ->
                    DiagnosticManager.log(LogLevel.INFO, "Supported PPG settings for $deviceId: ${settings.settings}. Starting PPG stream...")
                    val sampleRate = settings.settings[PolarSensorSetting.SettingType.SAMPLE_RATE]?.firstOrNull()?.toDouble() ?: 100.0
                    pulseEngine.setSampleRate(sampleRate)
                    ppgStreamDisposable = api.startPpgStreaming(deviceId, settings)
                        .subscribe(
                            { ppgData -> onPpgDataReceived(ppgData) },
                            { error ->
                                DiagnosticManager.log(LogLevel.WARN, "PPG stream error for $deviceId: ${error.localizedMessage}")
                            }
                        )
                    disposables.add(ppgStreamDisposable!!)
                },
                { error ->
                    DiagnosticManager.log(LogLevel.WARN, "Could not query PPG settings ($error). Attempting direct default PPG stream...")
                    ppgStreamDisposable = api.startPpgStreaming(deviceId, PolarSensorSetting(emptyMap()))
                        .subscribe(
                            { ppgData -> onPpgDataReceived(ppgData) },
                            { streamErr ->
                                DiagnosticManager.log(LogLevel.WARN, "Default PPG stream failed for $deviceId: ${streamErr.localizedMessage}")
                            }
                        )
                    disposables.add(ppgStreamDisposable!!)
                }
            )
        disposables.add(ppgSettingsDisposable)
    }

    private fun onPpgDataReceived(ppgData: com.polar.sdk.api.model.PolarPpgData) {
        val now = System.currentTimeMillis()
        var latestBpm: Int? = null

        val sampleCount = ppgData.samples.size
        // 100 Hz = 10ms per sample; derive sample dt
        val dtMs = if (sampleCount > 0) 10.0 else 10.0
        val batchStartTime = now - ((sampleCount - 1) * dtMs).toLong()

        for ((index, sample) in ppgData.samples.withIndex()) {
            val sampleTimeMs = batchStartTime + (index * dtMs).toLong()
            // Polar 360 / Loop streams 2 green optical photodiode channels
            val firstChannel = sample.channelSamples.firstOrNull()?.toLong() ?: continue
            val secondChannel = sample.channelSamples.getOrNull(1)?.toLong() ?: firstChannel
            val bpm = pulseEngine.processSample(firstChannel, sampleTimeMs)
            if (bpm != null) {
                latestBpm = bpm
            }
            incidentRecorder.recordPpg(sampleTimeMs, firstChannel, secondChannel, bpm)
            dailyLogger.logPpg(sampleTimeMs, firstChannel, secondChannel, bpm)
        }

        if (latestBpm != null && latestBpm > 0) {
            DiagnosticManager.updateTelemetry { current ->
                val updatedHrHistory = current.hrHistory.toMutableList()
                val lastEntry = updatedHrHistory.lastOrNull()
                // Record into graph at ~1 Hz (or when BPM value changes)
                if (lastEntry == null || (now - lastEntry.timestamp) >= 1000L || lastEntry.bpm != latestBpm) {
                    updatedHrHistory.add(HrHistoryPoint(timestamp = now, bpm = latestBpm))
                    if (updatedHrHistory.size > HR_MAX_POINTS) {
                        updatedHrHistory.removeAt(0)
                    }
                }
                current.copy(
                    isStreamingHr = true,
                    heartRateBpm = latestBpm,
                    hrHistory = updatedHrHistory
                )
            }

            // Early pulse lock deactivation:
            // If in routine duty cycle (not in an active emergency incident) and pulse lock is achieved
            // after the minimum required sampling duration (5s), shut off optical LEDs early to save battery.
            if (isPpgDutyCycleActive && !isEscalatedForIncident && pulseEngine.isPulseLocked()) {
                val activeElapsed = now - dutyCycleStartTime
                if (activeElapsed >= PPG_MIN_LOCK_SAMPLE_MS) {
                    DiagnosticManager.log(
                        LogLevel.INFO,
                        "Pulse locked at $latestBpm BPM after ${activeElapsed / 1000}s. Shutting off optical LEDs early to conserve battery."
                    )
                    handler.removeCallbacks(ppgDutyCycleRunnable)
                    stopPpgStreaming()
                    isPpgDutyCycleActive = false
                    DiagnosticManager.updateTelemetry { it.copy(isPpgDutyCycleActive = false, isStreamingHr = false) }
                    handler.postDelayed(ppgDutyCycleRunnable, PPG_DUTY_SLEEP_INTERVAL_MS)
                }
            }
        }
    }

    fun stopPpgStreaming() {
        ppgStreamDisposable?.let {
            if (!it.isDisposed) {
                it.dispose()
                DiagnosticManager.log(LogLevel.INFO, "PPG stream stopped.")
            }
        }
        ppgStreamDisposable = null
    }

    fun stopAccStreaming() {
        accStreamDisposable?.let {
            if (!it.isDisposed) {
                it.dispose()
                DiagnosticManager.log(LogLevel.INFO, "ACC stream stopped.")
            }
        }
        accStreamDisposable = null
    }

    fun shutDown() {
        stopDisconnectedScan()
        stopScan()
        stopAccStreaming()
        stopHrStreaming()
        stopPpiStreaming()
        stopPpgStreaming()
        stopPpgDutyCycle()
        incidentRecorder.onIncidentDeescalated()
        dailyLogger.closeCurrentWriter()
        disposables.clear()
        api.shutDown()
        DiagnosticManager.log(LogLevel.INFO, "PolarBleClient shut down.")
    }
}
