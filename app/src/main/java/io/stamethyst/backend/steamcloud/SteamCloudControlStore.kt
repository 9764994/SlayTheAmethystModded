package io.stamethyst.backend.steamcloud

import android.content.Context
import android.content.SharedPreferences
import io.stamethyst.config.LauncherConfig
import io.stamethyst.config.RuntimePaths
import io.stamethyst.config.SteamCloudSaveMode
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/** Atomic, uncached snapshots for readers; only writers/transactions take the control lock. */
internal object SteamCloudControlStore {
    @Serializable data class State(
        val disabled: Boolean = false,
        val mode: String = SteamCloudSaveMode.DEFAULT.persistedValue,
        val backgroundLaunchRequested: Boolean = false,
        val independentSwitchPending: Boolean = false,
        val pendingSteamId: String = "",
        val blacklist: Set<String> = LauncherConfig.DEFAULT_STEAM_CLOUD_SYNC_BLACKLIST_PATHS,
    )
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val lock = ReentrantLock(true)
    fun file(context: Context): File = File(RuntimePaths.storageRoot(context), "steam-cloud/control-v2.json")
    fun <T> locked(context: Context, block: () -> T): T =
        locked(File(context.filesDir, "steam-cloud-control.lock"), block)

    internal fun <T> locked(lockFile: File, block: () -> T): T = lock.withLock {
        if (lock.holdCount > 1) return@withLock block()
        RandomAccessFile(lockFile, "rw").use { handle ->
            handle.channel.lock().use { block() }
        }
    }
    // Route backend readers through the same legacy preferences as LauncherConfig.
    // Reads no longer persist migration, so a no-legacy fallback would lose old flags.
    fun read(context: Context): State = LauncherConfig.readSteamCloudControls(context)
    fun read(context: Context, legacy: SharedPreferences): State = read(file(context), legacy)

    // A save transaction can hold the writer lock for seconds. UI/status reads must
    // never acquire it. Every writer (including transaction apply/rollback) replaces
    // this regular file atomically, so readers see a complete old or new snapshot.
    internal fun read(file: File, legacy: SharedPreferences? = null): State =
        if (file.exists()) json.decodeFromString<State>(file.readText()) else legacyState(legacy)

    private fun legacyState(legacy: SharedPreferences?): State = State(
        disabled = legacy?.getBoolean("steam_cloud_sync_disabled", false) ?: false,
        mode = legacy?.getString("steam_cloud_save_mode", SteamCloudSaveMode.DEFAULT.persistedValue)
            ?: SteamCloudSaveMode.DEFAULT.persistedValue,
        independentSwitchPending = legacy?.getBoolean("steam_cloud_independent_switch_pending", false) ?: false,
        pendingSteamId = legacy?.getString("steam_cloud_pending_profile_steam_id", "").orEmpty(),
        blacklist = legacy?.getStringSet("steam_cloud_sync_blacklist_paths", LauncherConfig.DEFAULT_STEAM_CLOUD_SYNC_BLACKLIST_PATHS)
            ?: LauncherConfig.DEFAULT_STEAM_CLOUD_SYNC_BLACKLIST_PATHS,
    )
    fun update(context: Context, legacy: SharedPreferences, transform: (State) -> State) = locked(context) {
        // Migration is persisted on the first write, not from a UI read.
        write(file(context), transform(read(context, legacy)))
    }
    fun write(file: File, state: State) {
        SteamCloudAtomicFileStore.writeTextWithoutBackup(file, json.encodeToString(state))
    }
    fun modeReplacement(context: Context, staged: File) = SteamCloudPathReplacement(staged, file(context),
        rollbackJsonKeys = setOf("mode", "independentSwitchPending", "pendingSteamId"))
}
