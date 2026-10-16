package io.stamethyst.backend.steam

import android.content.Context
import io.stamethyst.backend.steamcloud.SteamCloudAtomicFileStore
import java.io.File
import java.util.UUID

/** Cross-process revocation fence. Contains no credentials and never rolls back on cleanup failure. */
internal object SteamCmAccountEpoch {
    const val FILE_NAME = "steam-cm-account-epoch"

    fun read(context: Context): String {
        val file = File(context.applicationContext.filesDir, FILE_NAME)
        return if (file.exists()) file.readText().trim() else ""
    }

    fun revoke(context: Context) {
        SteamCloudAtomicFileStore.writeTextWithoutBackup(
            File(context.applicationContext.filesDir, FILE_NAME),
            UUID.randomUUID().toString(),
        )
    }
}
