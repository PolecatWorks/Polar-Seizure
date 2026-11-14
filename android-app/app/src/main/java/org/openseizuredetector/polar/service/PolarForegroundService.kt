package org.openseizuredetector.polar.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import org.openseizuredetector.polar.MainActivity
import org.openseizuredetector.polar.R
import org.openseizuredetector.polar.alert.AlertAudibleManager
import org.openseizuredetector.polar.alert.AlertHistoryManager
import org.openseizuredetector.polar.alert.CarerContactManager
import org.openseizuredetector.polar.alert.LocationProvider
import org.openseizuredetector.polar.alert.SmsAlertDispatcher
import org.openseizuredetector.polar.ble.DiagnosticManager
import org.openseizuredetector.polar.ble.LogLevel
import org.openseizuredetector.polar.ble.PolarBleClient
import org.openseizuredetector.polar.model.EmergencyAlertType
import org.openseizuredetector.polar.model.SeizureAlarmState
import org.openseizuredetector.polar.watchdog.DeadmanWatchdog

class PolarForegroundService : Service() {

    private var polarBleClient: PolarBleClient? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var watchdog: DeadmanWatchdog
    private var lastReportedState: SeizureAlarmState? = null

    private val carerManager by lazy { CarerContactManager(this) }
    private val locationProvider by lazy { LocationProvider(this) }
    private val smsDispatcher by lazy { SmsAlertDispatcher(this) }
    private val audibleManager by lazy { AlertAudibleManager(this) }
    private val alertHistoryManager by lazy { AlertHistoryManager(this) }

    private var lastSmsDispatchTimeMs: Long = 0
    private val mainHandler = Handler(Looper.getMainLooper())

    // 15-second abort countdown runnable
    private var pendingEmergencyType: EmergencyAlertType? = null
    private var remainingCountdownSeconds: Int = 0
    private var countdownRunnable: Runnable? = null

    companion object {
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "polar_seizure_continuous_monitoring"
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val ACTION_CANCEL_ALARM = "ACTION_CANCEL_ALARM"
        const val ACTION_CONNECT_DEVICE = "ACTION_CONNECT_DEVICE"
        const val ACTION_DISCONNECT_DEVICE = "ACTION_DISCONNECT_DEVICE"
        const val ACTION_START_SCAN = "ACTION_START_SCAN"
        const val ACTION_STOP_SCAN = "ACTION_STOP_SCAN"
        const val ACTION_TEST_SMS_ALERT = "ACTION_TEST_SMS_ALERT"
        const val ACTION_PANIC_ALERT = "ACTION_PANIC_ALERT"
        const val EXTRA_DEVICE_ID = "EXTRA_DEVICE_ID"
        private const val SMS_COOLDOWN_MS = 60_000L // Prevent SMS storming (1 min cooldown between alarms)
        private const val ABORT_COUNTDOWN_SECONDS = 15
    }

    private fun initPolarClient() {
        if (polarBleClient == null) {
            polarBleClient = PolarBleClient(
                context = this,
                onAlarmStateChanged = { alarmState ->
                    watchdog.feed()
                    if (alarmState != lastReportedState) {
                        lastReportedState = alarmState
                        when (alarmState) {
                            SeizureAlarmState.WARNING -> updateNotification("⚠️ WARNING: Rhythmic seizure motion detected")
                            SeizureAlarmState.ALARM -> {
                                updateNotification("🚨 ALARM: Prolonged seizure activity confirmed!")
                                initiateEscalationWithCountdown(EmergencyAlertType.SEIZURE)
                            }
                            SeizureAlarmState.DISCONNECTED -> updateNotification("Sensor disconnected")
                            SeizureAlarmState.NORMAL -> {
                                updateNotification("Monitoring active — Normal")
                            }
                        }
                    }
                },
                onHighGFallDetected = {
                    DiagnosticManager.log(LogLevel.WARN, "High-G Fall impact detected. Initiating countdown escalation.")
                    initiateEscalationWithCountdown(EmergencyAlertType.HIGH_G)
                },
                onWatchTripleTapDetected = {
                    DiagnosticManager.log(LogLevel.WARN, "Watch Triple-Tap Panic detected! Bypassing countdown and dispatching immediately.")
                    cancelPendingAlert("Watch Triple-Tap Panic override")
                    executeSmsDispatch(EmergencyAlertType.PANIC)
                }
            )
        }
    }

    /**
     * Initiates the 15-second local audible/haptic countdown before carer SMS dispatch (PRD-004 §4).
     * If wearer touches "Cancel / I Am OK", the alert is aborted.
     */
    private fun initiateEscalationWithCountdown(alertType: EmergencyAlertType) {
        val now = System.currentTimeMillis()
        if ((now - lastSmsDispatchTimeMs) < SMS_COOLDOWN_MS) {
            DiagnosticManager.log(LogLevel.WARN, "Escalation throttled: ${alertType.displayName} cooldown active.")
            return
        }

        // Cancel any existing countdown
        cancelPendingAlert(reason = "Overridden by new ${alertType.displayName} alert")

        pendingEmergencyType = alertType
        remainingCountdownSeconds = ABORT_COUNTDOWN_SECONDS
        audibleManager.startSirenAndHaptics()

        DiagnosticManager.updateTelemetry {
            it.copy(
                pendingEmergencyCountdownSeconds = remainingCountdownSeconds,
                pendingEmergencyType = alertType.displayName
            )
        }
        updateNotification("🚨 ${alertType.displayName.uppercase()}! Abort countdown: ${remainingCountdownSeconds}s")

        countdownRunnable = object : Runnable {
            override fun run() {
                remainingCountdownSeconds--
                if (remainingCountdownSeconds > 0) {
                    DiagnosticManager.updateTelemetry {
                        it.copy(pendingEmergencyCountdownSeconds = remainingCountdownSeconds)
                    }
                    updateNotification("🚨 ${alertType.displayName.uppercase()}! Abort countdown: ${remainingCountdownSeconds}s")
                    mainHandler.postDelayed(this, 1000L)
                } else {
                    // Countdown elapsed uncancelled -> dispatch SMS immediately
                    DiagnosticManager.log(LogLevel.WARN, "Countdown elapsed. Dispatching ${alertType.displayName} SMS to carers.")
                    audibleManager.stopSirenAndHaptics()
                    DiagnosticManager.updateTelemetry {
                        it.copy(pendingEmergencyCountdownSeconds = null, pendingEmergencyType = null)
                    }
                    executeSmsDispatch(alertType)
                }
            }
        }
        mainHandler.postDelayed(countdownRunnable!!, 1000L)
    }

    /**
     * Wearer aborted false alarm via UI or notification "Cancel / I Am OK" button.
     */
    private fun cancelPendingAlert(reason: String = "Cancelled by wearer") {
        countdownRunnable?.let { mainHandler.removeCallbacks(it) }
        countdownRunnable = null
        pendingEmergencyType = null
        remainingCountdownSeconds = 0
        audibleManager.stopSirenAndHaptics()
        DiagnosticManager.updateTelemetry {
            it.copy(pendingEmergencyCountdownSeconds = null, pendingEmergencyType = null)
        }
        updateNotification("Alarm aborted: $reason")
        DiagnosticManager.log(LogLevel.INFO, "Emergency alarm aborted: $reason")
    }

    private fun executeSmsDispatch(alertType: EmergencyAlertType) {
        val now = System.currentTimeMillis()
        if (alertType != EmergencyAlertType.TEST && (now - lastSmsDispatchTimeMs) < SMS_COOLDOWN_MS) {
            DiagnosticManager.log(LogLevel.WARN, "Alert SMS throttled (cooldown active).")
            return
        }
        if (alertType != EmergencyAlertType.TEST) {
            lastSmsDispatchTimeMs = now
        }

        DiagnosticManager.log(LogLevel.INFO, "Initiating ${alertType.displayName} emergency dispatch (locating + SMS)...")
        val contacts = carerManager.getCarerContacts()
        val patientName = carerManager.getPatientName()
        val telemetry = DiagnosticManager.telemetry.value

        // Ensure high-rate continuous incident recording is active so the ±90s buffer is captured
        polarBleClient?.triggerIncidentHold(alertType.displayName)
        val incidentId = polarBleClient?.incidentRecorder?.getCurrentIncidentId()
            ?: "incident_${System.currentTimeMillis()}"

        locationProvider.requestFreshPosition { position ->
            val sent = smsDispatcher.dispatchEmergencyAlert(
                alertType = alertType,
                contacts = contacts,
                patientName = patientName,
                telemetry = telemetry,
                position = position
            )
            DiagnosticManager.log(LogLevel.INFO, "${alertType.displayName} emergency dispatch completed: $sent SMS sent.")

            val locString = if (position != null) {
                "${position.latitude}, ${position.longitude} (±${position.accuracyMeters.toInt()}m)"
            } else {
                null
            }
            val incidentFile = "$incidentId.json"
            alertHistoryManager.recordAlert(
                alertType = alertType,
                smsSentCount = sent,
                heartRateBpm = telemetry.heartRateBpm,
                energyRatioPercent = telemetry.energyRatioPercent,
                seizurePower3to8Hz = telemetry.seizurePower3to8Hz,
                location = locString,
                incidentFileName = incidentFile
            )
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "PolarSeizure:ContinuousMonitoringWakeLock"
        ).apply {
            setReferenceCounted(false)
        }

        watchdog = DeadmanWatchdog { state ->
            if (state != lastReportedState) {
                lastReportedState = state
                updateNotification("Status: $state")
            }
        }

        initPolarClient()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        initPolarClient()
        when (intent?.action) {
            ACTION_STOP -> {
                DiagnosticManager.log(LogLevel.INFO, "Foreground service received STOP action.")
                cancelPendingAlert("Service stopped")
                stopMonitoring()
                stopSelf()
            }
            ACTION_CANCEL_ALARM -> {
                DiagnosticManager.log(LogLevel.INFO, "Alarm cancelled by user from notification or UI.")
                cancelPendingAlert("Wearer pressed Cancel")
            }
            ACTION_CONNECT_DEVICE -> {
                val deviceId = intent.getStringExtra(EXTRA_DEVICE_ID)
                if (deviceId != null) {
                    DiagnosticManager.log(LogLevel.INFO, "Service connecting to specified device: $deviceId")
                    polarBleClient?.connect(deviceId)
                }
            }
            ACTION_DISCONNECT_DEVICE -> {
                val deviceId = intent.getStringExtra(EXTRA_DEVICE_ID)
                if (deviceId != null) {
                    DiagnosticManager.log(LogLevel.INFO, "Service disconnecting device: $deviceId")
                    polarBleClient?.disconnect(deviceId)
                }
            }
            ACTION_START_SCAN -> {
                DiagnosticManager.log(LogLevel.INFO, "Manual scan requested by user.")
                polarBleClient?.startScan()
            }
            ACTION_STOP_SCAN -> {
                polarBleClient?.stopScan()
            }
            ACTION_TEST_SMS_ALERT -> {
                DiagnosticManager.log(LogLevel.INFO, "Manual test SMS alert requested by user.")
                executeSmsDispatch(EmergencyAlertType.TEST)
            }
            ACTION_PANIC_ALERT -> {
                DiagnosticManager.log(LogLevel.WARN, "Manual PANIC button pressed by user. Bypassing countdown and dispatching immediately!")
                cancelPendingAlert("Panic button override")
                executeSmsDispatch(EmergencyAlertType.PANIC)
            }
            ACTION_START -> {
                startMonitoring()
            }
            else -> {
                startMonitoring()
            }
        }
        return START_STICKY
    }

    private fun startMonitoring() {
        initPolarClient()
        DiagnosticManager.updateTelemetry { it.copy(isServiceRunning = true) }
        try {
            if (wakeLock?.isHeld != true) {
                wakeLock?.acquire(24 * 60 * 60 * 1000L) // 24 hours max
                DiagnosticManager.log(LogLevel.INFO, "Partial WakeLock acquired.")
            }
            val notification = buildNotification("Monitoring active — Scanning for Polar devices")

            val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            } else {
                0
            }

            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                fgsType
            )

            // If we have a previously connected device remembered, auto-connect immediately.
            Handler(Looper.getMainLooper()).postDelayed({
                val client = polarBleClient
                if (client != null) {
                    val attempted = client.autoConnectIfRemembered()
                    client.startScan()
                    if (!attempted) {
                        client.scheduleDisconnectedPeriodicScan()
                    }
                }
            }, 1200L)
        } catch (e: Exception) {
            DiagnosticManager.log(LogLevel.ERROR, "Error starting foreground service: ${e.localizedMessage}")
        }
    }

    private fun stopMonitoring() {
        DiagnosticManager.updateTelemetry {
            it.copy(
                isServiceRunning = false,
                connectionState = "DISCONNECTED",
                connectedDeviceId = null,
                connectedDeviceName = null,
                batteryLevel = null,
                heartRateBpm = null,
                isStreamingAcc = false,
                isStreamingHr = false,
                pendingEmergencyCountdownSeconds = null,
                pendingEmergencyType = null
            )
        }
        polarBleClient?.shutDown()
        polarBleClient = null

        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
            DiagnosticManager.log(LogLevel.INFO, "Partial WakeLock released.")
        }
    }

    private fun buildNotification(statusText: String): Notification {
        val launchIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val cancelAlarmIntent = Intent(this, PolarForegroundService::class.java).apply {
            action = ACTION_CANCEL_ALARM
        }
        val cancelPendingIntent = PendingIntent.getService(
            this,
            1,
            cancelAlarmIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val isCountdownActive = pendingEmergencyType != null
        val isAlarmActive = isCountdownActive || lastReportedState == SeizureAlarmState.ALARM || lastReportedState == SeizureAlarmState.WARNING

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(if (isCountdownActive) "🚨 ${pendingEmergencyType?.displayName?.uppercase()} DETECTED" else if (isAlarmActive) "⚠️ Seizure Motion Detected" else getString(R.string.service_notification_title))
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(if (isAlarmActive) NotificationCompat.PRIORITY_MAX else NotificationCompat.PRIORITY_LOW)

        if (isAlarmActive || isCountdownActive) {
            builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel / I Am OK", cancelPendingIntent)
        }

        return builder.build()
    }

    private fun updateNotification(statusText: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(statusText))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_monitoring_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.service_notification_desc)
                enableVibration(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cancelPendingAlert("Service destroyed")
        stopMonitoring()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
