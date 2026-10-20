package io.stamethyst.backend.easytier

import android.content.Context
import android.net.Uri
import android.os.Bundle

/** All processes access the encrypted file through its single owner in :easytier. */
internal object EasyTierCredentialStore {
    fun save(context: Context, roomId: String, playerId: String, sessionToken: String, ownerToken: String) {
        call(context, "save", roomId, playerId, sessionToken, ownerToken)
    }

    fun sessionToken(context: Context, roomId: String, playerId: String): String =
        call(context, "sessionToken", roomId, playerId).getString("value").orEmpty()

    fun ownerToken(context: Context, roomId: String): String =
        call(context, "ownerToken", roomId).getString("value").orEmpty()

    fun saveRoomPassword(context: Context, roomId: String, password: String) {
        call(context, "saveRoomPassword", roomId, password = password)
    }

    fun roomPassword(context: Context, roomId: String): String =
        call(context, "roomPassword", roomId).getString("value").orEmpty()

    fun clearRoomPassword(context: Context, roomId: String) {
        call(context, "clearRoomPassword", roomId)
    }

    fun clearSession(context: Context, roomId: String, playerId: String) {
        call(context, "clearSession", roomId, playerId)
    }

    fun clearRoom(context: Context, roomId: String, playerId: String) {
        call(context, "clearRoom", roomId, playerId)
    }

    private fun call(
        context: Context,
        method: String,
        roomId: String,
        playerId: String = "",
        sessionToken: String = "",
        ownerToken: String = "",
        password: String = "",
    ): Bundle {
        val uri = Uri.parse("content://${context.packageName}.easytier.credentials")
        val extras = Bundle().apply {
            putString("roomId", roomId)
            putString("playerId", playerId)
            putString("sessionToken", sessionToken)
            putString("ownerToken", ownerToken)
            putString("password", password)
        }
        // Do not fall back to local preferences or mask IPC failures as missing credentials.
        return checkNotNull(context.contentResolver.call(uri, method, null, extras)) {
            "EasyTier credential provider is unavailable."
        }
    }
}
