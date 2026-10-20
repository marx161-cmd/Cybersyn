package com.termux.cybersyn.core.network

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import com.termux.cybersyn.core.logging.AppLogger
import java.util.UUID

/**
 * Presence of comrade's BLE "home beacon" (~/bin/home-beacon on comrade; scope.md 2026-10-20).
 *
 * One low-power, hardware-filtered scan for a single 128-bit service UUID, so it works while the
 * Wi-Fi radio is off (Wi-Fi-off-outside habit). Results are batched, so the phone is not woken for
 * every advertisement. Only the time of the last sighting is kept; the steerer decides what it means.
 * The UUID is configuration (never committed): it comes in via the wifi.home_steer action.
 */
@SuppressLint("MissingPermission")
object HomeBeacon {
    private const val TAG = "HomeBeacon"
    private const val RETRY_MS = 60_000L
    private const val BATCH_MS = 15_000L

    @Volatile private var lastSeenMs = 0L     // SystemClock.elapsedRealtime of the newest sighting
    @Volatile private var scanning = false
    private var scanner: BluetoothLeScanner? = null
    private var uuid: String? = null
    private var startedAt = 0L
    private var lastTryAt = 0L

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = seen(listOf(result))
        override fun onBatchScanResults(results: MutableList<ScanResult>) = seen(results)
        override fun onScanFailed(errorCode: Int) {
            scanning = false
            AppLogger.warn(TAG, "scan failed: $errorCode")
        }
    }

    private fun seen(results: List<ScanResult>) {
        val newest = results.maxOfOrNull { it.timestampNanos } ?: return
        // ScanResult.timestampNanos is on the elapsedRealtime clock.
        lastSeenMs = maxOf(lastSeenMs, newest / 1_000_000L)
    }

    /** Milliseconds since the beacon was last seen, or null if it never was while we were scanning. */
    fun ageMs(now: Long): Long? = lastSeenMs.takeIf { it > 0 }?.let { now - it }

    /** True once the scanner has been running for at least [ms] (so "never seen" means something). */
    fun scanningFor(now: Long, ms: Long): Boolean = scanning && startedAt > 0 && now - startedAt >= ms

    val isScanning: Boolean get() = scanning

    /** Called every steerer tick; (re)starts the scan if it is not running. Cheap when it is. */
    fun ensure(app: Context, beaconUuid: String?, now: Long) {
        if (beaconUuid.isNullOrBlank()) { stop(); return }
        val adapter = app.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            if (scanning) { AppLogger.info(TAG, "Bluetooth off: scan stopped"); scanning = false }
            return
        }
        if (scanning && uuid == beaconUuid) return
        if (lastTryAt != 0L && now - lastTryAt < RETRY_MS) return
        lastTryAt = now
        val le = adapter.bluetoothLeScanner ?: return
        try {
            stop()
            val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(UUID.fromString(beaconUuid))).build()
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
                .setReportDelay(BATCH_MS)
                .build()
            le.startScan(listOf(filter), settings, callback)
            scanner = le
            uuid = beaconUuid
            scanning = true
            startedAt = now
            lastSeenMs = 0
            AppLogger.info(TAG, "scanning for home beacon (low power, ${BATCH_MS / 1000}s batches)")
        } catch (e: RuntimeException) {
            AppLogger.warn(TAG, "scan start failed", e)
        }
    }

    fun stop() {
        runCatching { scanner?.stopScan(callback) }
        scanner = null
        scanning = false
        uuid = null
    }
}
