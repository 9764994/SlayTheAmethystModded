package io.stamethyst.backend.easytier

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process

/** Non-exported IPC boundary; the encrypted preferences are opened in :easytier only. */
class EasyTierCredentialProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    @Synchronized
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        // ContentProvider.call does not use URI read/write permission checks. Keep an explicit
        // UID check as well as exported=false, including for callers holding a provider handle.
        if (Binder.getCallingUid() != Process.myUid()) {
            throw SecurityException("Credential access denied.")
        }
        val context = checkNotNull(context)
        val data = requireNotNull(extras)
        val roomId = data.getString("roomId").orEmpty()
        val playerId = data.getString("playerId").orEmpty()
        val value = when (method) {
            "save" -> {
                EasyTierLocalCredentialStore.save(
                    context, roomId, playerId,
                    data.getString("sessionToken").orEmpty(),
                    data.getString("ownerToken").orEmpty(),
                )
                ""
            }
            "sessionToken" -> EasyTierLocalCredentialStore.sessionToken(context, roomId, playerId)
            "ownerToken" -> EasyTierLocalCredentialStore.ownerToken(context, roomId)
            "roomPassword" -> EasyTierLocalCredentialStore.roomPassword(context, roomId)
            "saveRoomPassword" -> {
                EasyTierLocalCredentialStore.saveRoomPassword(context, roomId, data.getString("password").orEmpty())
                ""
            }
            "clearRoomPassword" -> {
                EasyTierLocalCredentialStore.clearRoomPassword(context, roomId)
                ""
            }
            "clearSession" -> {
                EasyTierLocalCredentialStore.clearSession(context, roomId, playerId)
                ""
            }
            "clearRoom" -> {
                EasyTierLocalCredentialStore.clearRoom(context, roomId, playerId)
                ""
            }
            else -> throw IllegalArgumentException("Unsupported credential operation.")
        }
        return Bundle().apply { putString("value", value) }
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = throw UnsupportedOperationException()

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException()

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = throw UnsupportedOperationException()
}
