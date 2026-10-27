package io.stamethyst.backend.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * Best-effort view of whether the device currently has a usable network.
 *
 * A holding foreground service keeps the process runnable but does not keep the network usable:
 * locking the screen lets the Wi-Fi radio enter power save, and Doze suspends network access for
 * the whole device. Existing TCP connections are then torn down under the app, which surfaces as
 * `IOException: Software caused connection abort` rather than as "no network". Waiting for this
 * predicate before retrying is what turns that abort into a delay instead of a failure.
 */
internal object NetworkAvailability {
    /**
     * True when a network is present and has been validated by the system.
     *
     * `NET_CAPABILITY_VALIDATED` is the part that matters: an associated Wi-Fi network without
     * internet access (captive portal, dead router, radio still waking up) is reported as
     * available but cannot carry the retry.
     */
    fun hasValidatedNetwork(context: Context): Boolean = runCatching {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }.getOrDefault(false)
}
