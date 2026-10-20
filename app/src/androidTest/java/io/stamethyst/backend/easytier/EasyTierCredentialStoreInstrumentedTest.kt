package io.stamethyst.backend.easytier

import android.content.Context
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Uses unique test room keys only; never changes a real room or contacts the server. */
class EasyTierCredentialStoreInstrumentedTest {
    @Test
    fun providerOwnsStorageInEasyTierProcessAndIsNotExported() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        @Suppress("DEPRECATION")
        val provider = checkNotNull(context.packageManager.resolveContentProvider(
            "${context.packageName}.easytier.credentials", 0,
        ))
        assertFalse(provider.exported)
        assertEquals("${context.packageName}:easytier", provider.processName)
    }

    @Test
    fun cachedCallerSeesRemoteSavesRotationsAndClears() = withRoom { context, roomId ->
        // Open the old backing file in the caller before the service-side write. This is the
        // ordering that used to strand the UI on an empty SharedPreferences cache.
        context.getSharedPreferences("easytier_credentials", Context.MODE_PRIVATE).all
        assertEquals("", EasyTierCredentialStore.ownerToken(context, roomId))
        assertEquals("", EasyTierCredentialStore.sessionToken(context, roomId, "player"))

        EasyTierCredentialStore.save(context, roomId, "player", "session-1", "owner-1")
        assertEquals("session-1", EasyTierCredentialStore.sessionToken(context, roomId, "player"))
        assertEquals("owner-1", EasyTierCredentialStore.ownerToken(context, roomId))

        EasyTierCredentialStore.save(context, roomId, "player", "session-2", "")
        assertEquals("session-2", EasyTierCredentialStore.sessionToken(context, roomId, "player"))
        assertEquals("owner-1", EasyTierCredentialStore.ownerToken(context, roomId))
        assertEquals("", EasyTierCredentialStore.sessionToken(context, roomId, "other-player"))

        EasyTierCredentialStore.clearSession(context, roomId, "player")
        assertEquals("", EasyTierCredentialStore.sessionToken(context, roomId, "player"))
        assertEquals("owner-1", EasyTierCredentialStore.ownerToken(context, roomId))

        EasyTierCredentialStore.clearRoom(context, roomId, "player")
        assertEquals("", EasyTierCredentialStore.ownerToken(context, roomId))
        EasyTierCredentialStore.save(context, roomId, "player", "session-3", "owner-3")
        assertEquals("owner-3", EasyTierCredentialStore.ownerToken(context, roomId))
    }

    @Test
    fun passwordsKeepWhitespaceAndClearWithoutRemovingTokens() = withRoom { context, roomId ->
        EasyTierCredentialStore.save(context, roomId, "player", "session", "owner")
        EasyTierCredentialStore.saveRoomPassword(context, roomId, " secret ")
        assertEquals(" secret ", EasyTierCredentialStore.roomPassword(context, roomId))
        EasyTierCredentialStore.clearRoomPassword(context, roomId)
        assertEquals("", EasyTierCredentialStore.roomPassword(context, roomId))
        assertEquals("owner", EasyTierCredentialStore.ownerToken(context, roomId))
        EasyTierCredentialStore.saveRoomPassword(context, roomId, "again")
        EasyTierCredentialStore.saveRoomPassword(context, roomId, "")
        assertEquals("", EasyTierCredentialStore.roomPassword(context, roomId))
        EasyTierCredentialStore.saveRoomPassword(context, roomId, "last")
        EasyTierCredentialStore.clearRoom(context, roomId, "player")
        assertEquals("", EasyTierCredentialStore.roomPassword(context, roomId))
        assertEquals("", EasyTierCredentialStore.sessionToken(context, roomId, "player"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun unknownOperationsAreRejected() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.contentResolver.call(
            android.net.Uri.parse("content://${context.packageName}.easytier.credentials"),
            "unknown", null, Bundle(),
        )
    }

    private fun withRoom(test: (Context, String) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val roomId = "credential-ipc-test-${UUID.randomUUID()}"
        try {
            test(context, roomId)
        } finally {
            EasyTierCredentialStore.clearRoom(context, roomId, "player")
        }
    }
}
