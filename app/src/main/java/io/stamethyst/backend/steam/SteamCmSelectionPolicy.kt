package io.stamethyst.backend.steam

import android.content.Context
import android.net.ConnectivityManager
import top.apricityx.workshop.steam.protocol.SteamCmServerHealth
import top.apricityx.workshop.steam.protocol.SteamCmServerSelector
import top.apricityx.workshop.steam.protocol.SteamWebSocketFactory

/** Each process shares bounded history; changing the Android network starts a fresh scope. */
internal object SteamCmSelectionPolicy {
    private val health = SteamCmServerHealth()

    fun create(context: Context, factory: SteamWebSocketFactory): SteamCmServerSelector {
        val appContext = context.applicationContext
        return SteamCmServerSelector(
            webSocketFactory = factory,
            health = health,
            networkScope = {
                val manager = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                manager?.activeNetwork?.toString() ?: "offline"
            },
        )
    }
}
