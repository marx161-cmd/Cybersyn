package com.termux.cybersyn.core.actions

import android.content.Context
import android.net.wifi.WifiManager
import com.termux.cybersyn.core.engine.Action
import com.termux.cybersyn.core.engine.ActionCategory
import com.termux.cybersyn.core.engine.ActionContext
import com.termux.cybersyn.core.engine.ActionResult
import com.termux.cybersyn.core.network.HomeWifiSteerer

/**
 * Switch the home Wi-Fi band steerer on/off, or read its status.
 *
 * Args:
 *   - "state": "on", "off" or "status" (default status)
 *   - "ssid": home network name (required for "on")
 *   - "up_dbm" / "down_dbm": thresholds, default -72 / -80
 *   - "mobile_data": "true"/"false" — also drive mobile data (default true)
 *   - "beacon_uuid": comrade's BLE home-beacon service UUID (optional; enables arrive/leave Wi-Fi switching)
 * Sets variables wifi_steer_* (enabled, band, rssi, bssid, best5, pinned, mobile_off, last).
 */
class WifiHomeSteerAction : Action {
    override val id = "wifi.home_steer"
    override val category = ActionCategory.SETTINGS

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        when (args["state"]?.lowercase() ?: "status") {
            "on" -> {
                val ssid = args["ssid"]?.trim().orEmpty()
                if (ssid.isEmpty()) return ActionResult.Failure("ssid is required")
                HomeWifiSteerer.start(ctx.app, HomeWifiSteerer.Config(
                    ssid = ssid,
                    upDbm = args["up_dbm"]?.toIntOrNull() ?: -72,
                    downDbm = args["down_dbm"]?.toIntOrNull() ?: -80,
                    mobileData = args["mobile_data"]?.toBooleanStrictOrNull() ?: true,
                    beaconUuid = args["beacon_uuid"]?.trim()?.takeIf { it.isNotEmpty() },
                ))
            }
            "off" -> HomeWifiSteerer.stop(ctx.app)
            "status" -> Unit
            else -> return ActionResult.Failure("invalid state: ${args["state"]}")
        }
        val s = HomeWifiSteerer.status(ctx.app)
        ctx.variables.set("wifi_steer_enabled", s.enabled.toString())
        ctx.variables.set("wifi_steer_band", s.band)
        ctx.variables.set("wifi_steer_rssi", s.rssi.toString())
        ctx.variables.set("wifi_steer_bssid", s.bssid)
        ctx.variables.set("wifi_steer_best5", s.best5Dbm?.toString().orEmpty())
        ctx.variables.set("wifi_steer_pinned", s.pinnedBssid)
        ctx.variables.set("wifi_steer_mobile_off", s.mobileOffByUs.toString())
        ctx.variables.set("wifi_steer_last", s.lastAction)
        ctx.variables.set("wifi_steer_beacon", s.beacon)
        ctx.logger("home steer: $s")
        return ActionResult.Success
    }
}

/** Current Wi-Fi link into variables: wifi_ssid, wifi_bssid, wifi_band, wifi_rssi, wifi_freq. */
class WifiInfoAction : Action {
    override val id = "wifi.info"
    override val category = ActionCategory.SETTINGS

    @Suppress("DEPRECATION")
    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val wm = ctx.app.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return ActionResult.Failure("WiFi not available")
        val info = wm.connectionInfo
        val connected = info != null && info.networkId != -1
        ctx.variables.set("wifi_ssid", if (connected) HomeWifiSteerer.normalizeSsid(info.ssid) else "")
        ctx.variables.set("wifi_bssid", if (connected) info.bssid.orEmpty() else "")
        ctx.variables.set("wifi_band", if (connected) HomeWifiSteerer.bandName(info.frequency) else "")
        ctx.variables.set("wifi_rssi", if (connected) info.rssi.toString() else "")
        ctx.variables.set("wifi_freq", if (connected) info.frequency.toString() else "")
        ctx.logger("wifi: connected=$connected ${info?.bssid} ${info?.frequency}MHz ${info?.rssi}dBm")
        return ActionResult.Success
    }
}

/**
 * Pin a saved Wi-Fi network to one radio (BSSID) and, by default, reassociate now.
 *
 * Args: "ssid" (saved network), "bssid", "reassociate" ("true" default).
 */
class WifiConnectBssidAction : Action {
    override val id = "wifi.connect"
    override val category = ActionCategory.SETTINGS

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val ssid = args["ssid"]?.trim().orEmpty()
        val bssid = args["bssid"]?.trim().orEmpty()
        if (ssid.isEmpty() || bssid.isEmpty()) return ActionResult.Failure("ssid and bssid are required")
        val wm = ctx.app.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return ActionResult.Failure("WiFi not available")
        val cfg = HomeWifiSteerer.homeConfig(wm, ssid)
            ?: return ActionResult.Failure("no saved network named $ssid")
        val err = HomeWifiSteerer.pinBssid(wm, cfg, bssid, args["reassociate"]?.toBooleanStrictOrNull() ?: true)
        ctx.logger("wifi.connect $ssid -> $bssid: ${err ?: "ok"}")
        return if (err == null) ActionResult.Success else ActionResult.Failure(err)
    }
}
