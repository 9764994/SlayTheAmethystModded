package io.stamethyst.backend.steam

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import io.stamethyst.backend.steamcloud.SteamCloudAuthCoordinator
import io.stamethyst.backend.steamcloud.SteamCloudAuthStore
import java.io.File
import java.util.concurrent.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import top.apricityx.workshop.steam.protocol.SteamAccountSession

/** Isolated storage only; never touches the installed user's account or calls Steam. */
class SteamAccountRevocationInstrumentedTest {
    @Test fun logoutFenceIsImmediateAndSurvivesAProcessStyleReread() = isolated { context ->
        saveAccount(context)
        SteamCloudAuthStore.cacheWebAccessToken(context, steamId.toLong(), "test-token", "test-web-token", System.currentTimeMillis() + 60_000)
        assertNotNull(SteamCloudAuthStore.readCachedWebAccessToken(context, steamId.toLong(), "test-token", minimumRemainingLifetimeMs = 0))
        assertNotNull(SteamCloudAuthStore.readAuthMaterial(context))
        SteamCmAccountEpoch.revoke(context)
        assertNull(SteamCloudAuthStore.readAuthMaterial(context))
        assertFalse(SteamCloudAuthStore.readSnapshot(context).isComplete)
        // Local profile migration still knows which account owns the live saves.
        assertEquals(steamId, SteamCloudAuthStore.readProfileAuthMaterial(context)?.steamId64)
        assertNull(SteamCloudAuthStore.readCmAuthMaterial(context))
        assertNull(SteamCloudAuthStore.readCachedWebAccessToken(context, steamId.toLong(), "test-token", minimumRemainingLifetimeMs = 0))
        SteamCloudAuthStore.clear(context)
        SteamCloudAuthStore.verifyCleared(context)
        assertNull(SteamCloudAuthStore.readProfileAuthMaterial(context))
        assertEquals("", SteamCloudAuthStore.readSavedLoginCredentials(context).password)
        assertNull(SteamCloudAuthStore.readCachedWebAccessToken(context, steamId.toLong(), "test-token", minimumRemainingLifetimeMs = 0))
    }

    @Test fun loginStartedBeforeLogoutCannotRestoreCredentials() = isolated { context ->
        val attempt = SteamCloudAuthStore.beginLoginAttempt(context)
        SteamCmAccountEpoch.revoke(context)
        try {
            commitAccount(context, attempt)
            fail("Pre-logout login must not commit")
        } catch (_: CancellationException) { }
        assertNull(SteamCloudAuthStore.readAuthMaterial(context))
        saveAccount(context)
        assertNotNull(SteamCloudAuthStore.readAuthMaterial(context))
    }

    @Test fun failureNoticeIsDeduplicatedAndCannotSurviveLogoutOrNewLogin() = isolated { context ->
        runBlocking {
            saveAccount(context)
            val account = SteamAccountSession("test-account", steamId.toLong(), "test-token")
            SteamCmLoginFailureNotice.report(context, account)
            val notice = withTimeout(2_000) { SteamCmLoginFailureNotice.observe(context).first() }!!
            SteamCmLoginFailureNotice.dismiss(context, notice)
            SteamCmLoginFailureNotice.report(context, account)
            assertNull(withTimeout(2_000) { SteamCmLoginFailureNotice.observe(context).first() })
            saveAccount(context)
            SteamCmLoginFailureNotice.report(context, account)
            assertNotNull(withTimeout(2_000) { SteamCmLoginFailureNotice.observe(context).first() })
            SteamCmAccountEpoch.revoke(context)
            SteamCmLoginFailureNotice.report(context, account)
            assertNull(withTimeout(2_000) { SteamCmLoginFailureNotice.observe(context).first() })
        }
    }

    private fun saveAccount(context: Context) {
        commitAccount(context, SteamCloudAuthStore.beginLoginAttempt(context))
    }

    private fun commitAccount(context: Context, attempt: String) {
        SteamCloudAuthStore.recordAuthSuccess(context, attempt, SteamCloudAuthCoordinator.CancellationHandle(),
            "test-account", "test-token", "test-guard", steamId)
    }

    private fun isolated(block: (Context) -> Unit) {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val root = File(app.cacheDir, "cm-revocation-test-${System.nanoTime()}").apply { mkdirs() }
        val context = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }
            override fun getNoBackupFilesDir(): File = File(root, "no_backup").apply { mkdirs() }
            override fun getApplicationInfo(): android.content.pm.ApplicationInfo =
                android.content.pm.ApplicationInfo(super.getApplicationInfo()).apply { dataDir = root.path }
        }
        try { block(context) } finally { root.deleteRecursively() }
    }

    private val steamId = "76561198000000001"
}
