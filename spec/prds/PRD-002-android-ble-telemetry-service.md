# PRD-002: Android BLE Telemetry & Foreground Service

## 1. Problem Statement & Motivation
For continuous seizure monitoring, intermittent data reception is equivalent to zero monitoring. The native Android application (`android-app`) is required to maintain continuous, 24/7 connectivity with the wearable wristband while actively minimizing battery consumption on both devices.

Polar-Seizure targets the **Polar Loop Gen2** wristband, interacting via the **official Polar BLE SDK** and mirroring the proven, low-power telemetry streaming approach established by **OpenSeizureDetector**.

---

## 2. Technical Requirements

### 2.1 Android Foreground Service & Power Management
* **Service Type:** `foregroundServiceType="connectedDevice|dataSync"`
* **Lifecycle:** Managed as `START_STICKY` so the Android runtime automatically restarts the service if briefly killed under extreme memory pressure.
* **Persistent Notification:**
  * Displays live connection state, current seizure status (`NORMAL`, `WARNING`, `ALARM`), and wearable battery percentage.
  * Prominent quick-action button: `"Cancel / I Am OK"` to abort false warnings immediately.
* **WakeLock Management:** Partial wake lock (`PowerManager.PARTIAL_WAKE_LOCK`) retained during active monitoring sessions to ensure CPU executes FFT calculation loops while the phone screen is dark.
* **Doze Exemption:** Prompts user to grant battery optimization exemption via `Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`.

### 2.2 Bluetooth Low Energy (BLE) Client & Official Polar SDK
* **Primary Target Wearable:** **Polar Loop Gen2** (Wristband activity tracker with internal 3-axis accelerometer and optical LED display).
  * Secondary / Fallback devices: Polar Verity Sense, Polar H10.
* **Official Polar BLE SDK:**
  * Dependency: `com.github.polarofficial:polar-ble-sdk:5.+` (or latest 6.x).
  * Implementation: `PolarBleApiDefaultImpl.defaultImplementation(context, features)`
  * Configured Features:
    * `PolarBleApi.FEATURE_POLAR_ONLINE_STREAMING` (PMD service for live ACC stream)
    * `PolarBleApi.FEATURE_BATTERY_INFO` (Standard Battery GATT Service `0x180F`)
    * `PolarBleApi.FEATURE_DEVICE_INFO` (Device Information GATT Service `0x180A`)
* **Streaming Negotiation & Settings:**
  1. Listen for device connection: `setApiCallback` triggers `streamingFeaturesReady(deviceId, features)`.
  2. Query supported stream settings: `requestStreamSettings(deviceId, DeviceStreamingFeature.ACC)`.
  3. Configure stream parameters:
     * **Sample Rate:** `25 Hz` (mirrored from OpenSeizureDetector standard; captures up to 12.5 Hz Nyquist frequency, which fully encompasses the 3–8 Hz seizure band while using half the power of 50 Hz).
     * **Resolution:** `16 bit`.
     * **Range:** `±8g`.
  4. Initiate streaming: `startAccStreaming(deviceId, settings)` receiving reactive `PolarAccData` samples with microsecond timestamps.
* **Wristband Battery Telemetry:**
  * Subscribed via `observeBatteryLevel(deviceId)`.
  * Emits battery warnings if wristband level drops below 20% and critical alert at 10%.

### 2.4 Automatic BLE Reconnection & Continuous Background Re-attachment
* **Remembered Device Binding:**
  * When a Polar wearable successfully connects, its ID is persisted to app preferences (`last_connected_device_id`).
* **Adaptive Reconnection Backoff Engine:**
  * If the device drops connection or an active connection is lost, the daemon initiates immediate reconnection to the remembered sensor alongside a 20-second discovery scan window.
  * If re-attachment does not succeed on the first attempt, the system schedules sequential reconnection retries using an adaptive backoff cadence starting at 5s and doubling (5s -> 10s -> 20s -> 40s -> 60s max) to balance rapid reconnection with Android BLE radio stability.
  * Each reconnect window directly invokes `api.connectToDevice(rememberedId)` and starts BLE scanning, stopping the scan timeout and resetting backoff immediately once a valid connection is re-established.
* **Continuous Background Capabilities & Doze Exemption:**
  * The monitoring daemon operates inside an Android Foreground Service (`foregroundServiceType="connectedDevice|dataSync"`) backed by a persistent `PARTIAL_WAKE_LOCK`.
  * The application prompts for and provides an in-app control to grant Battery Optimization Exemption (`Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`), preventing Android Doze mode from restricting background BLE scanning and sensor telemetry streaming when the screen is dark.

### 2.5 Diagnostic Log Management
* **In-App Deletion Controls:**
  * **Daily Telemetry Archives:** Each daily `.json.gz` archive in the Diagnostics tab features an individual `Delete` button, cleanly removing the compressed file from disk and updating available storage.
  * **Live Console Logs:** Individual log entries in the live diagnostic console include a quick deletion action (`×`) to purge specific messages alongside the global `Clear` button.
To balance continuous 24-hour monitoring with near-real-time alarm latency, the system implements OpenSeizureDetector's multi-tier battery preservation techniques:

1. **Wristband Battery Optimization:**
   * **25 Hz Optimal Sampling:** Avoids the excessive power drain of 50 Hz, 100 Hz, or 200 Hz sampling. 25 Hz provides the exact Nyquist headroom ($12.5\text{ Hz}$) needed for 3–8 Hz seizure detection while preserving 24+ hour battery life on the wristband.
   * **BLE Packet Batching & High ATT MTU:** Samples are batched and transmitted across optimized BLE notification frames (negotiating MTU up to 247 bytes), minimizing Bluetooth radio transmission bursts and maximizing wristband MCU sleep states.
   * **Offloaded Computation:** The wristband performs zero mathematical processing or FFT calculation; it functions purely as an efficient sensor node.
2. **Android Phone App Battery Optimization:**
   * **Stillness / Quiet Gate:** The phone evaluates acceleration magnitude variance $|a|$ over a short window before running the FFT. If the user is resting or asleep (variance $<0.05g$), the computationally expensive FFT loop is bypassed, keeping CPU usage minimal ($\le 3.5\%$ per hour).
   * **Optimized Coroutine Processing:** Sensor packet ingestion and sliding window processing execute on lightweight Kotlin coroutines (`Dispatchers.Default`) without allocating unnecessary temporary objects.

### 2.4 Adaptive Dual-Mode Optical HR/PPG Sampling & Incident Data Preservation
The continuous optical photodiode LED on optical wristbands (such as Polar Loop / Polar 360) is one of the highest consumers of battery power. To ensure multi-day battery life while preserving life-critical physiological telemetry, the Android daemon operates an **Adaptive Dual-Mode Sampling Controller**:

1. **Baseline / Low-Power Monitoring Mode (Routine State):**
   * **Accelerometer (ACC):** Streams continuously at 25 Hz (minimal radio/sensor power).
   * **Optical Heart Rate (HR/PPG):** Operates on **intermittent duty-cycled sample intervals** rather than continuous 22 Hz streaming:
     * *Interval Duty Cycle:* Active optical sample window of 15 seconds every 2 to 5 minutes (configurable in settings).
     * *Battery Preservation:* Enables the optical LEDs on the Polar Loop to shut off for >80% of the monitoring cycle, dramatically reducing optical power draw and extending wearable battery life.
2. **Incident Escalation Mode (Motion / ACC Suspect Trigger):**
   * **Trigger Condition:** The moment the ACC signal processing engine detects elevated movement or suspect spectral energy approaching the pre-warning threshold ($\text{Power}_{\text{ROI}} \ge 0.5 \times \text{AlarmThresh}$ or $\text{Variance}(|a|) > 0.15g$):
   * **Instant Optical Escalation:** The Android daemon immediately turns on continuous, high-frequency optical PPG streaming ($22\text{ Hz}$ or $50\text{ Hz}$).
   * **Biometric Cross-Validation:** High-rate optical data is ingested immediately to extract fine-grained Heart Rate (BPM), beat-to-beat intervals, and perfusion amplitude to confirm whether autonomic tachycardia/bradycardia accompanies the rhythmic motion.
3. **Circular Incident Data Buffer & Forensic Preservation:**
   * **Pre-Incident Rolling Ring Buffer:** The Android daemon retains a circular ring buffer in RAM of the last 60 seconds of raw ACC vectors and HR/PPG frames.
   * **Event Persistence on Incident / Warning / Alarm:** When an incident trigger or warning occurs, the 60 seconds of pre-incident data plus the entire duration of the active incident (and 60 seconds post-incident recovery) are permanently saved to local SQLite/disk storage (`IncidentCaptureRecord`) and staged for cloud telemetry dispatch.
   * **Neurological Review Trail:** Ensures that even with baseline duty-cycling, zero data is lost for clinical review when a true seizure or suspicious event occurs.

---

## 3. Sensor Telemetry Dead Man's Handle [SPECIFIED / DEFERRED IMPLEMENTATION]
> [!IMPORTANT]
> **Status: Architectural Specification / Deferred Implementation — Do NOT implement in active code yet.**
> This section formalizes the requirement for future activation.

### 3.1 Motivation & Rationale
If the Polar Loop Gen2 battery depletes, the Bluetooth radio drops, or the band slips off the wearer's wrist, the monitoring system must not fail silently. In life-critical medical telemetry, missing data must fail closed.

### 3.2 Watchdog Behavior & Thresholds
* **Telemetry Feed Watchdog:** A hardware-backed countdown timer is reset on every valid batch of accelerometer samples received from `PolarBleApi`.
* **Escalation Stages:**
  1. **Stage 1 — Telemetry Disruption Warning ($T = 15\text{ seconds}$):**
     * If no BLE packets arrive for 15 seconds, the foreground service status changes to `WARNING_SENSOR_LOST`.
     * Triggers a subtle, non-panicking local chime on the phone and updates the persistent notification to amber.
  2. **Stage 2 — Critical Dead Man's Trigger ($T = 30\text{ seconds}$):**
     * If data remains unavailable after 30 seconds, the system transitions to `CRITICAL_DEADMAN_TRIGGER`.
     * Emits a high-volume audible local alarm tone (`USAGE_ALARM`) to alert the wearer and proximate carers that monitoring has ceased.
     * Displays a prominent `"Reconnect / Pause Monitoring"` interface.
* **Safe Pause Mode:**
  * When the wearer intentionally removes the band for bathing or charging, they must explicitly activate "Pause Monitoring" mode in the app. This suspends the Dead Man's Handle without triggering an emergency response.

---

## 4. Native On-Device Emergency SMS & GPS Dispatch [Phase 1 Standalone]

### 4.1 Rationale & Autonomy
To protect patient safety in Phase 1 without requiring an active cloud backend or Wi-Fi connectivity, the Android app provides direct, autonomous caregiver alerting via cellular SMS and GPS:
1. Operates over standard cellular carrier networks (2G/3G/4G/5G SMS) without internet dependence.
2. Dispatches alerts directly to configured caregiver phone numbers using the Android telephony stack.
3. Automatically embeds real-time geographical coordinates via a universal Google Maps navigation link.

### 4.2 Telephony Integration (`android.telephony.SmsManager`)
* **API Access:**
  * Uses `context.getSystemService(SmsManager::class.java)` (API 31+ / Android 12 through Android 15).
  * Multi-SIM awareness: Supports `smsManager.createForSubscriptionId(subId)` to select the user's primary active cellular subscription.
* **Message Handling:**
  * Uses `smsManager.divideMessage(text)` and `smsManager.sendMultipartTextMessage(...)` to handle messages exceeding standard 160-character GSM limits without truncation.
  * Registers a `BroadcastReceiver` listening to `PendingIntent` callbacks (`SMS_SENT_ACTION` and `SMS_DELIVERED_ACTION`) to confirm carrier transmission and log delivery status.

### 4.3 Location Acquisition (`FusedLocationProviderClient`)
* **Location Service:**
  * Integrates Google Play Services `FusedLocationProviderClient` (with fallback to `LocationManager.GPS_PROVIDER` for non-GMS environments).
  * Foreground Service declaration updated to: `android:foregroundServiceType="connectedDevice|dataSync|location"`.
* **Two-Stage Dispatch Protocol (Mirrored from OpenSeizureDetector):**
  * **Stage 1 — Instant Seizure Alert ($T = 0\text{s}$ post-countdown):**
    * Dispatched immediately upon alarm confirmation (does not block waiting for a cold GPS fix).
    * If cached location is recent ($< 5\text{ minutes}$ old), it is included; otherwise notes GPS acquisition in progress:
      > *"EMERGENCY: Polar-Seizure detected possible tonic-clonic seizure for [Patient Name] at [Time]. Acquiring fresh GPS fix..."*
  * **Stage 2 — High-Accuracy Location Update ($T \approx +10\text{s}$ to $30\text{s}$):**
    * `fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cancellationToken)` requests a high-precision fix with a 15-second timeout.
    * Once locked, dispatches the follow-up location SMS:
      > *"LOCATION UPDATE: [Patient Name] is located at: https://maps.google.com/?q={lat},{lng} (Accuracy: ±{acc}m)."*

### 4.4 Permissions & Google Play Safety Exemption
* **Required Manifest Permissions:**
  * `android.permission.SEND_SMS` (Direct SMS transmission)
  * `android.permission.READ_PHONE_STATE` (SIM state / carrier availability)
  * `android.permission.ACCESS_FINE_LOCATION` (GPS satellite precision)
  * `android.permission.ACCESS_COARSE_LOCATION` (Network/Wi-Fi trilateration)
* **Google Play Policy Exemption:**
  * Qualifies under Google Play's restricted permissions policy exemption: **"Physical safety / emergency alert apps (e.g., apps that send SMS alerts in emergencies)"**.
  * Declared in the Google Play Console Declaration Form with an automated emergency safety justification.

---

## 5. Offline Telemetry Cache
* In the event of cellular or Wi-Fi disconnection, all telemetry frames, FFT power metrics, and warning states are buffered locally in an on-device SQLite / Room database.
* When future Phase 2 backend integration is activated, buffered frames synchronize with `backend-container` with original device timestamps preserved.

---

## 6. Candidate Feature Enhancements [Tentative for Discussion]

### 6.1 In-App Daily Archive Browser & System Share Sheet (Tentative for Discussion)
* **Rationale:** The foreground daemon produces continuous compressed telemetry archives (`ps_telemetry_YYYY-MM-DD.json.gz`). Users and clinicians currently have to pull these manually via ADB.
* **Requirements:**
  1. **Archive Browser Tab / Screen:** A dedicated UI screen listing all locally stored daily `.json.gz` archives with file size, recording duration, and incident count flags.
  2. **Daily Inspection View:** Tap an archive date to view quick summary statistics (total hours worn, mean resting HR, peak 3–8 Hz motion events, incident triggers).
  3. **Android System Share Sheet Integration:** One-tap export via `FileProvider` allowing direct sharing of compressed `.json.gz` archives or summarized summaries to email (neurologist/GP), Google Drive, cloud backup, or local file manager.
  4. **Retention Management:** Configurable storage quotas (e.g. keep last 7, 14, or 30 days) with automatic pruning of oldest non-incident logs when disk space is constrained.

### 6.2 Silent BLE Keep-Alive & Radio Soft-Reset (Tentative for Discussion)
* **Rationale:** Android Bluetooth daemons can occasionally hang or fail to surface incoming BLE packets after hours of continuous streaming, especially across radio sleep transitions.
* **Requirements:**
  1. **Telemetry Stream Health Watcher:** If no valid BLE packet (ACC or HR/PPG) arrives within 25 seconds during an active monitoring session, trigger an internal radio health probe.
  2. **GATT Reconnect Cycle:** Attempt graceful `gatt.disconnect()` and `gatt.close()` followed by targeted re-connection to the saved device ID.
  3. **Bluetooth Adapter Soft-Cycle (Fail-Closed):** If the connection remains unresponsive after 2 retry loops, prompt the user or toggle Bluetooth adapter state (subject to Android OS permission constraints) while logging the anomaly in diagnostics.
