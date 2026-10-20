package com.termux.cybersyn.core.network

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.ScanResult
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.Build
import android.os.SystemClock
import android.telephony.TelephonyManager
import com.termux.cybersyn.core.contexts.DeviceStateEvents
import com.termux.cybersyn.core.logging.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Home Wi-Fi band steering + mobile-data auto switch (scope.md, 2026-10-18).
 *
 * Android never roams off a "good enough" 2.4 GHz link on its own, so a 120 fps Moonlight
 * stream collapsed on the 2.4 GHz radio while the 5 GHz radio of the same router was in
 * range. This controller keeps the home network's saved config pinned to ONE of its radios
 * (by BSSID) and moves the pin with hysteresis:
 *
 *  - on 2.4 GHz and a 5 GHz BSSID of the home SSID is seen at >= [Config.upDbm] -> pin 5 GHz
 *    (after a fallback, only once 5 GHz has been good for [Config.upHoldMs]);
 *  - on 5 GHz and RSSI < [Config.downDbm] for [Config.downHoldMs] -> pin 2.4 GHz;
 *  - at most one band switch per [Config.minSwitchIntervalMs];
 *  - not connected, home SSID visible but the pinned radio is not -> re-pin to a visible one
 *    (a pinned network otherwise never auto-joins).
 *
 * Mobile data: OFF once the home network has been connected for [Config.homeOnMs]; back ON
 * after [Config.homeOffMs] without it, but only if this controller turned it off (it never
 * turns mobile data on just because the phone woke up away from home).
 *
 * Runs as UID 1000 (android.uid.system), which holds NETWORK_SETTINGS / MODIFY_PHONE_STATE, so
 * the public WifiManager / TelephonyManager calls work without root. Polls every [TICK_MS]
 * from the engine's process; a delay() holds no wakelock, so a sleeping phone is not woken.
 */
@SuppressLint("MissingPermission")
@Suppress("DEPRECATION")
object HomeWifiSteerer {
    private const val TAG = "HomeWifiSteerer"
    private const val PREFS = "home_wifi_steer"
    private const val TICK_MS = 5_000L
    private const val SCAN_INTERVAL_MS = 60_000L
    private const val SCAN_MAX_AGE_MS = 120_000L
    private const val BEACON_PRESENT_MS = 45_000L   // batches are 15 s and the low-power scan duty-cycles
    private const val UNKNOWN = 0
    private const val PRESENT = 1
    private const val ABSENT = 2

    data class Config(
        val ssid: String,
        val upDbm: Int = -72,
        val downDbm: Int = -80,
        val downHoldMs: Long = 20_000,
        val upHoldMs: Long = 30_000,
        val minSwitchIntervalMs: Long = 120_000,
        val homeOnMs: Long = 10_000,
        val homeOffMs: Long = 20_000,
        val mobileData: Boolean = true,
        /** Connected to home Wi-Fi but no real internet (not VALIDATED) this long -> mobile data + 5G ON. */
        val deadWifiMs: Long = 20_000,
        /** After a fallback, home Wi-Fi must be validated this long before mobile data goes OFF again. */
        val recoverHoldMs: Long = 30_000,
        /** No Wi-Fi link at all AND no beacon this long -> you left: mobile on first, then Wi-Fi OFF. */
        val leaveMs: Long = 120_000,
        /** comrade's BLE home beacon service UUID (config only, never committed); null = no beacon logic. */
        val beaconUuid: String? = null,
    )

    data class Status(
        val enabled: Boolean,
        val ssid: String,
        val connected: Boolean,
        val bssid: String,
        val band: String,
        val rssi: Int,
        val best5Dbm: Int?,
        val pinnedBssid: String,
        val mobileOffByUs: Boolean,
        val lastAction: String,
        val beacon: String = "",
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var loop: Job? = null
    @Volatile private var config: Config? = null
    private lateinit var app: Context

    // Hysteresis state (monotonic ms).
    private var lastSwitchAt = 0L
    private var lastScanAt = 0L
    private var fellBackAt = 0L
    private var upGoodSince = 0L
    private var downBadSince = 0L
    private var homeSince = 0L
    private var lostSince = 0L
    private var validSince = 0L
    private var deadSince = 0L
    private var wifiGoneSince = 0L
    private var fallbackOn = false      // we turned mobile data on because home Wi-Fi was lost/dead
    private var presence = UNKNOWN
    @Volatile private var lastAction = "none"
    @Volatile private var lastStatus: Status? = null

    /** Called from AutomationService.onCreate: resume if it was switched on before. */
    fun restore(context: Context) {
        app = context.applicationContext
        val p = prefs()
        if (!p.getBoolean("enabled", false)) return
        val ssid = p.getString("ssid", null) ?: return
        start(context, Config(
            ssid = ssid,
            upDbm = p.getInt("up_dbm", -72),
            downDbm = p.getInt("down_dbm", -80),
            mobileData = p.getBoolean("mobile_data", true),
            beaconUuid = p.getString("beacon_uuid", null),
        ), persist = false)
    }

    fun start(context: Context, cfg: Config, persist: Boolean = true) {
        app = context.applicationContext
        config = cfg
        if (persist) {
            prefs().edit()
                .putBoolean("enabled", true)
                .putString("ssid", cfg.ssid)
                .putInt("up_dbm", cfg.upDbm)
                .putInt("down_dbm", cfg.downDbm)
                .putBoolean("mobile_data", cfg.mobileData)
                .putString("beacon_uuid", cfg.beaconUuid)
                .apply()
        }
        if (loop?.isActive == true) {
            AppLogger.info(TAG, "config updated: $cfg")
            return
        }
        AppLogger.info(TAG, "started: $cfg")
        loop = scope.launch {
            while (isActive) {
                runCatching { tick() }.onFailure { AppLogger.warn(TAG, "tick failed", it) }
                delay(TICK_MS)
            }
        }
    }

    fun stop(context: Context, persist: Boolean = true) {
        app = context.applicationContext
        loop?.cancel()
        loop = null
        if (persist) prefs().edit().putBoolean("enabled", false).apply()
        HomeBeacon.stop()
        presence = UNKNOWN
        AppLogger.info(TAG, "stopped (persist=$persist)")
    }

    /** Engine shutdown: stop the loop but keep the persisted on/off choice. */
    fun shutdown() {
        loop?.cancel()
        loop = null
        HomeBeacon.stop()
    }

    fun status(context: Context): Status {
        app = context.applicationContext
        return lastStatus ?: snapshotStatus(config?.ssid ?: prefs().getString("ssid", "").orEmpty())
    }

    // ---------------------------------------------------------------------------------------

    private fun prefs() = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun wifi(): WifiManager? = app.getSystemService(WifiManager::class.java)

    private fun tick() {
        val cfg = config ?: return
        val wm = wifi() ?: return
        val now = SystemClock.elapsedRealtime()
        val info = wm.connectionInfo
        val connected = info != null && info.networkId != -1 &&
            normalizeSsid(info.ssid) == cfg.ssid
        val home = homeConfig(wm, cfg.ssid)
        val pinned = home?.BSSID?.lowercase().orEmpty()
        val scans = freshScans(wm, cfg.ssid)
        val best5 = scans.filter { is5Ghz(it.frequency) }.maxByOrNull { it.level }
        val best24 = scans.filter { !is5Ghz(it.frequency) }.maxByOrNull { it.level }

        if (cfg.mobileData) steerMobile(connected, now, cfg)
        steerPresence(wm, info, connected, now, cfg)

        if (connected) {
            val onFive = is5Ghz(info.frequency)
            if (!onFive) {
                downBadSince = 0
                if (best5 == null || now - lastScanAt > SCAN_INTERVAL_MS) requestScan(wm, now)
                if (best5 != null && best5.level >= cfg.upDbm) {
                    if (upGoodSince == 0L) upGoodSince = now
                    val held = fellBackAt == 0L || now - upGoodSince >= cfg.upHoldMs
                    if (held && now - lastSwitchAt >= cfg.minSwitchIntervalMs) {
                        pin(wm, home, best5.BSSID, now, "up: 2.4->5 GHz (${best5.level} dBm)", reassociate = true)
                        fellBackAt = 0
                    }
                } else {
                    upGoodSince = 0
                }
            } else {
                upGoodSince = 0
                if (info.rssi < cfg.downDbm) {
                    if (downBadSince == 0L) downBadSince = now
                    if (now - downBadSince >= cfg.downHoldMs && now - lastSwitchAt >= cfg.minSwitchIntervalMs) {
                        val target = best24 ?: run { requestScan(wm, now); null }
                        if (target != null) {
                            pin(wm, home, target.BSSID, now, "down: 5->2.4 GHz (5 GHz at ${info.rssi} dBm)", reassociate = true)
                            fellBackAt = now
                            downBadSince = 0
                        }
                    }
                } else {
                    downBadSince = 0
                }
            }
        } else if (home != null && scans.isNotEmpty() && wm.isWifiEnabled) {
            // A pinned network only joins its pinned radio. If that radio is out of sight but
            // the other one is visible, move the pin so the phone can join at all.
            val pinnedVisible = pinned.isNotEmpty() && scans.any { it.BSSID.equals(pinned, ignoreCase = true) }
            if (!pinnedVisible && now - lastSwitchAt >= TICK_MS * 6) {
                val target = best5?.takeIf { it.level >= cfg.upDbm } ?: best24 ?: best5
                if (target != null && !target.BSSID.equals(pinned, ignoreCase = true)) {
                    pin(wm, home, target.BSSID, now, "rejoin: pinned radio not visible -> ${target.BSSID}", reassociate = false)
                }
            }
        }

        val status = Status(
            enabled = true,
            ssid = cfg.ssid,
            connected = connected,
            bssid = if (connected) info.bssid.orEmpty() else "",
            band = if (connected) bandName(info.frequency) else "",
            rssi = if (connected) info.rssi else 0,
            best5Dbm = best5?.level,
            pinnedBssid = pinned,
            mobileOffByUs = prefs().getBoolean("mobile_off_by_us", false),
            lastAction = lastAction,
            beacon = beaconText(now, cfg),
        )
        if (status.band != lastStatus?.band || status.bssid != lastStatus?.bssid) {
            DeviceStateEvents.publishPatch(mapOf(
                "wifi_band" to status.band,
                "wifi_bssid" to status.bssid,
            ))
        }
        lastStatus = status
    }

    private fun steerMobile(connected: Boolean, now: Long, cfg: Config) {
        val p = prefs()
        val offByUs = p.getBoolean("mobile_off_by_us", false)
        if (connected) {
            lostSince = 0
            if (homeSince == 0L) homeSince = now
            if (!wifiValidated()) {
                // Associated but no real internet (router up, ISP down, captive portal...): bring mobile back.
                validSince = 0
                if (deadSince == 0L) deadSince = now
                if (offByUs && now - deadSince >= cfg.deadWifiMs) {
                    if (setMobile(true)) {
                        p.edit().putBoolean("mobile_off_by_us", false).apply()
                        fallbackOn = true
                        note("mobile data + 5G ON (home Wi-Fi has no internet ${cfg.deadWifiMs / 1000}s)")
                    }
                }
            } else {
                deadSince = 0
                if (validSince == 0L) validSince = now
                // After a fallback wait longer, so a flaky router cannot flap mobile data on/off.
                val hold = if (fallbackOn) cfg.recoverHoldMs else cfg.homeOnMs
                if (now - validSince >= hold && !offByUs) {
                    if (setMobile(false)) {
                        p.edit().putBoolean("mobile_off_by_us", true).apply()
                        fallbackOn = false
                        note("mobile data + 5G OFF (home Wi-Fi validated ${hold / 1000}s)")
                    }
                }
            }
        } else {
            homeSince = 0
            validSince = 0
            deadSince = 0
            if (!offByUs) return
            if (lostSince == 0L) lostSince = now
            if (now - lostSince >= cfg.homeOffMs) {
                if (setMobile(true)) {
                    p.edit().putBoolean("mobile_off_by_us", false).apply()
                    fallbackOn = true
                    note("mobile data + 5G ON (no home Wi-Fi ${cfg.homeOffMs / 1000}s)")
                }
            }
        }
    }

    /**
     * Beacon-driven presence (needs [Config.beaconUuid]). Arrive: beacon seen again after being absent ->
     * Wi-Fi ON (the existing logic then joins 5 GHz and drops mobile data). Leave: no Wi-Fi link at all AND
     * no beacon for [Config.leaveMs] -> mobile data + 5G ON, and only once cellular is really validated,
     * Wi-Fi OFF. One signal alone never switches (router reboot / Bluetooth hiccup are safe).
     */
    private fun steerPresence(wm: WifiManager, info: WifiInfo?, connected: Boolean, now: Long, cfg: Config) {
        HomeBeacon.ensure(app, cfg.beaconUuid, now)
        if (cfg.beaconUuid.isNullOrBlank() || !HomeBeacon.isScanning) return

        val age = HomeBeacon.ageMs(now)
        val prev = presence
        when {
            age != null && age <= BEACON_PRESENT_MS -> presence = PRESENT
            age != null && age > cfg.leaveMs -> presence = ABSENT
            age == null && HomeBeacon.scanningFor(now, cfg.leaveMs) -> presence = ABSENT
        }

        if (prev == ABSENT && presence == PRESENT) {
            if (!wm.isWifiEnabled) {
                if (wm.setWifiEnabled(true)) note("Wi-Fi ON (home beacon seen)")
                else AppLogger.warn(TAG, "setWifiEnabled(true) refused")
            }
        }

        val anyLink = info != null && info.networkId != -1
        if (connected || anyLink) wifiGoneSince = 0
        else if (wifiGoneSince == 0L) wifiGoneSince = now

        if (presence == ABSENT && wm.isWifiEnabled && !anyLink &&
            wifiGoneSince != 0L && now - wifiGoneSince >= cfg.leaveMs
        ) {
            if (setMobile(true)) {
                prefs().edit().putBoolean("mobile_off_by_us", false).apply()
                fallbackOn = true
            }
            if (cellularValidated()) {
                if (wm.setWifiEnabled(false)) note("Wi-Fi OFF (left home: no Wi-Fi + no beacon ${cfg.leaveMs / 1000}s)")
                else AppLogger.warn(TAG, "setWifiEnabled(false) refused")
            }
        }
    }

    private fun beaconText(now: Long, cfg: Config): String = when {
        cfg.beaconUuid.isNullOrBlank() -> "off"
        !HomeBeacon.isScanning -> "not scanning"
        else -> {
            val age = HomeBeacon.ageMs(now)
            val st = when (presence) { PRESENT -> "present"; ABSENT -> "absent"; else -> "unknown" }
            if (age == null) "$st, never seen" else "$st, seen ${age / 1000}s ago"
        }
    }

    private fun transportValidated(transport: Int, default: Boolean): Boolean {
        val cm = app.getSystemService(ConnectivityManager::class.java) ?: return default
        val caps = cm.allNetworks.mapNotNull { cm.getNetworkCapabilities(it) }
            .firstOrNull { it.hasTransport(transport) } ?: return default
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /** Unknown counts as validated, so a failed query never turns mobile data on by itself. */
    private fun wifiValidated(): Boolean = transportValidated(NetworkCapabilities.TRANSPORT_WIFI, true)

    private fun cellularValidated(): Boolean = transportValidated(NetworkCapabilities.TRANSPORT_CELLULAR, false)

    /** Mobile data and the "Enable 5G" switch always move together (user 2026-10-20). */
    private fun setMobile(enabled: Boolean): Boolean {
        val ok = setMobileData(enabled)
        if (ok) setFiveG(enabled)
        return ok
    }

    /** The Internet panel's "Enable 5G" switch = whether NR is in the user's allowed network types. */
    private fun setFiveG(enabled: Boolean) {
        val tm = app.getSystemService(TelephonyManager::class.java) ?: return
        try {
            val reason = TelephonyManager.ALLOWED_NETWORK_TYPES_REASON_USER
            val cur = tm.getAllowedNetworkTypesForReason(reason)
            val nr = TelephonyManager.NETWORK_TYPE_BITMASK_NR
            val want = if (enabled) cur or nr else cur and nr.inv()
            if (want != cur) {
                tm.setAllowedNetworkTypesForReason(reason, want)
                AppLogger.info(TAG, "5G ${if (enabled) "ON" else "OFF"} (allowed types $cur -> $want)")
            }
        } catch (e: RuntimeException) {
            AppLogger.warn(TAG, "5G -> $enabled failed", e)
        }
    }

    /** Returns true when mobile data is in the requested state afterwards. */
    private fun setMobileData(enabled: Boolean): Boolean {
        val tm = app.getSystemService(TelephonyManager::class.java) ?: return false
        return try {
            if (Build.VERSION.SDK_INT >= 31) {
                if (tm.isDataEnabledForReason(TelephonyManager.DATA_ENABLED_REASON_USER) == enabled) return true
                tm.setDataEnabledForReason(TelephonyManager.DATA_ENABLED_REASON_USER, enabled)
            } else {
                if (tm.isDataEnabled == enabled) return true
                tm.isDataEnabled = enabled
            }
            true
        } catch (e: RuntimeException) {
            AppLogger.warn(TAG, "mobile data -> $enabled failed", e)
            false
        }
    }

    private fun pin(
        wm: WifiManager,
        home: WifiConfiguration?,
        bssid: String,
        now: Long,
        why: String,
        reassociate: Boolean,
    ) {
        if (home == null) {
            AppLogger.warn(TAG, "no saved config for home SSID; cannot pin $bssid")
            return
        }
        lastSwitchAt = now
        when (val r = pinBssid(wm, home, bssid, reassociate)) {
            null -> note("$why [pinned $bssid]")
            else -> AppLogger.warn(TAG, "pin $bssid failed: $r")
        }
    }

    private fun requestScan(wm: WifiManager, now: Long) {
        if (now - lastScanAt < SCAN_INTERVAL_MS) return
        lastScanAt = now
        runCatching { wm.startScan() }
    }

    private fun freshScans(wm: WifiManager, ssid: String): List<ScanResult> {
        val nowUs = SystemClock.elapsedRealtimeNanos() / 1_000
        return runCatching { wm.scanResults }.getOrDefault(emptyList())
            .filter { normalizeSsid(it.SSID) == ssid }
            .filter { nowUs - it.timestamp <= SCAN_MAX_AGE_MS * 1_000 }
    }

    private fun note(msg: String) {
        lastAction = msg
        AppLogger.info(TAG, msg)
    }

    private fun snapshotStatus(ssid: String): Status {
        val wm = wifi()
        val info = wm?.connectionInfo
        val connected = info != null && info.networkId != -1
        return Status(
            enabled = prefs().getBoolean("enabled", false),
            ssid = ssid,
            connected = connected,
            bssid = if (connected) info?.bssid.orEmpty() else "",
            band = if (connected) bandName(info!!.frequency) else "",
            rssi = if (connected) info!!.rssi else 0,
            best5Dbm = wm?.let { w -> freshScans(w, ssid).filter { is5Ghz(it.frequency) }.maxOfOrNull { it.level } },
            pinnedBssid = wm?.let { homeConfig(it, ssid)?.BSSID }.orEmpty(),
            mobileOffByUs = prefs().getBoolean("mobile_off_by_us", false),
            lastAction = lastAction,
        )
    }

    // ---- shared helpers, also used by the wifi.* actions ----------------------------------

    fun homeConfig(wm: WifiManager, ssid: String): WifiConfiguration? =
        runCatching { wm.configuredNetworks }.getOrNull()
            ?.firstOrNull { normalizeSsid(it.SSID) == ssid }

    /**
     * Pin a saved network to [bssid]. Returns null on success, else an error string.
     * With [reassociate], drops the current association so the new pin takes effect now
     * (costs ~2 s of Wi-Fi); otherwise the pin applies at the next join.
     */
    fun pinBssid(wm: WifiManager, cfg: WifiConfiguration, bssid: String, reassociate: Boolean): String? {
        cfg.BSSID = bssid.lowercase()
        val id = wm.updateNetwork(cfg)
        if (id == -1) return "updateNetwork rejected"
        if (!wm.enableNetwork(id, true)) return "enableNetwork rejected"
        if (reassociate) {
            val current = wm.connectionInfo?.bssid
            if (!bssid.equals(current, ignoreCase = true)) {
                wm.disconnect()
                wm.reconnect()
            }
        }
        return null
    }

    fun is5Ghz(freqMhz: Int): Boolean = freqMhz >= 4900

    fun bandName(freqMhz: Int): String = when {
        freqMhz >= 5925 -> "6"
        freqMhz >= 4900 -> "5"
        freqMhz > 0 -> "2.4"
        else -> ""
    }

    fun normalizeSsid(raw: String?): String =
        raw?.trim()?.removeSurrounding("\"").orEmpty()
}
