package io.stamethyst.backend.network

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/**
 * Battery-optimisation exemption for long agent runs.
 *
 * A foreground service is exempt from App Standby but **not** from Doze, and Doze explicitly
 * suspends network access and ignores wake locks. The only mechanism the platform documents for
 * keeping the network during Doze is the battery-optimisation allowlist: an exempted app "can use
 * the network and hold partial wake locks during Doze and App Standby".
 *
 * So the Wi-Fi lock and the wake lock only help against screen-off radio power save; Doze needs
 * this exemption. The user must grant it once through the system dialog.
 */
internal object BatteryOptimization {
    /**
     * True when the system will not apply Doze network restrictions to this app.
     *
     * Always true on builds without the API, which matches the pre-Doze behaviour.
     */
    fun isExempt(context: Context): Boolean = runCatching {
        val powerManager = context.getSystemService(PowerManager::class.java) ?: return true
        powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }.getOrDefault(false)

    /**
     * The system dialog that adds this app to the allowlist.
     *
     * Returns null when the device accepts no such request, so the caller can fall back to opening
     * the general battery-optimisation list instead of crashing.
     */
    fun requestIntent(context: Context): Intent? {
        val action = Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
        val intent = Intent(action, Uri.parse("package:${context.packageName}")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val resolved = intent.resolveActivity(context.packageManager)
        return if (resolved != null) intent else fallbackIntent(context)
    }

    /**
     * Opens the battery-optimisation list for manual selection.
     *
     * Some ROMs refuse the per-app request intent; showing the list is the only remaining route and
     * does not need the caller to hold a special permission.
     */
    fun fallbackIntent(context: Context): Intent =
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
}
