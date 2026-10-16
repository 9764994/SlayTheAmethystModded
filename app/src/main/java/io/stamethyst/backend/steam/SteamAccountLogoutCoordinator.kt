package io.stamethyst.backend.steam

import android.content.Context
import io.stamethyst.backend.steamcloud.SteamCloudAuthStore
import io.stamethyst.backend.steamcloud.SteamCloudAvatarCacheStore
import io.stamethyst.backend.steamcloud.SteamCloudBaselineStore
import io.stamethyst.backend.steamcloud.SteamCloudDiagnosticsStore
import io.stamethyst.backend.steamcloud.SteamCloudManifestStore
import io.stamethyst.backend.steamcloud.SteamCloudLiveSaveInUseException
import io.stamethyst.backend.steamcloud.SteamCloudOperationMutex
import io.stamethyst.backend.steamcloud.SteamCloudSaveProfileManager
import io.stamethyst.backend.steamcloud.SteamCloudSyncProcessService
import io.stamethyst.backend.steamcloud.SteamGamePresenceService
import io.stamethyst.backend.workshop.SharedSteamCmSessions
import io.stamethyst.backend.workshop.WorkshopService
import io.stamethyst.backend.workshop.WorkshopDownloadProcessService
import io.stamethyst.config.LauncherConfig
import io.stamethyst.config.SteamCloudSaveMode
import io.stamethyst.ui.preferences.LauncherPreferences
import top.apricityx.workshop.steam.protocol.OkHttpSteamCmSession

internal object SteamAccountLogoutCoordinator {
    @Synchronized
    fun logout(
        context: Context,
        clearDiagnostics: Boolean,
    ) {
        val appContext = context.applicationContext
        // Publish before waiting for the operation lock: other processes may hold it
        // while using CM. Their observers and account validators revoke immediately.
        val failures = mutableListOf<Throwable>()
        fun cleanup(block: () -> Unit) {
            try { block() } catch (error: Exception) { failures += error }
        }
        cleanup { SteamCmAccountEpoch.revoke(appContext) }
        cleanup { WorkshopDownloadProcessService.pauseAllForLogout(appContext) }
        cleanup { SharedSteamCmSessions.invalidateProcessSession() }
        cleanup { OkHttpSteamCmSession.closeAllActiveSessions() }
        cleanup { WorkshopService.revokeAllAccountSessions() }
        cleanup { SteamCmLoginFailureNotice.clear(appContext) }
        cleanup { LauncherPreferences.setSteamGamePresenceEnabled(appContext, false) }
        cleanup { SteamGamePresenceService.stop(appContext) }
        cleanup { SteamCloudSyncProcessService.cancel(appContext) }
        SteamCloudOperationMutex.runExclusive(appContext) {
            // Profile migration needs the old account ID, but no network caller can
            // use its credentials after the epoch fence above. Always wipe credentials
            // and caches, even if local profile migration fails.
            try {
                val cloudProfileSteamId = SteamCloudAuthStore.readProfileAuthMaterial(appContext)?.steamId64.orEmpty()
                    .ifBlank { LauncherConfig.readSteamCloudPendingProfileSteamId(appContext) }
                val currentMode = LauncherPreferences.readSteamCloudSaveMode(appContext)
                if (currentMode != SteamCloudSaveMode.INDEPENDENT) {
                    LauncherConfig.saveSteamCloudIndependentSwitchPending(
                        appContext, true, cloudProfileSteamId,
                    )
                    try {
                        SteamCloudSaveProfileManager.switchMode(
                            context = appContext,
                            fromMode = currentMode,
                            toMode = SteamCloudSaveMode.INDEPENDENT,
                        )
                        LauncherConfig.saveSteamCloudIndependentSwitchPending(appContext, false)
                    } catch (_: SteamCloudLiveSaveInUseException) {
                        // The game still owns the live save; finish the already-recorded
                        // independent switch when its lease is released.
                    }
                } else {
                    LauncherPreferences.saveSteamCloudSaveMode(appContext, SteamCloudSaveMode.INDEPENDENT)
                    LauncherConfig.saveSteamCloudIndependentSwitchPending(appContext, false)
                }
            } catch (error: Exception) {
                failures += error
            } finally {
                cleanup { SteamCloudAuthStore.clear(appContext) }
                cleanup { SteamCloudAvatarCacheStore.clear(appContext) }
                cleanup { SteamCloudManifestStore.clear(appContext) }
                cleanup { SteamCloudBaselineStore.clear(appContext) }
                if (clearDiagnostics) cleanup { SteamCloudDiagnosticsStore.clear(appContext) }
            }

            cleanup { SteamCloudAuthStore.verifyCleared(appContext) }
        }
        if (failures.isNotEmpty()) {
            throw IllegalStateException("Steam account was revoked, but logout cleanup did not fully complete.", failures.first())
                .also { failure -> failures.drop(1).forEach(failure::addSuppressed) }
        }
    }
}
