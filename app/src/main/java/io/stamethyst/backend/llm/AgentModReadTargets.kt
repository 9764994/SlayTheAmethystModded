package io.stamethyst.backend.llm

import io.stamethyst.backend.mods.ModManager
import java.io.File

data class AgentModReadTarget(val modId: String, val jar: File)

/** Resolves only catalogued, installed JARs; model input is never treated as a file path. */
class AgentModReadTargets(
    private val parentModId: String,
    private val parentJar: File,
    private val installedMods: () -> List<ModManager.InstalledMod>,
) {
    fun resolve(modId: String?): AgentModReadTarget {
        val requested = modId?.trim().orEmpty()
        if (requested.isEmpty() || ModManager.normalizeModId(requested) == ModManager.normalizeModId(parentModId)) {
            return AgentModReadTarget(parentModId, parentJar)
        }
        val normalized = ModManager.normalizeModId(requested)
        val matches = installedMods().filter {
            it.installed && it.jarFile.isFile &&
                (ModManager.normalizeModId(it.modId) == normalized ||
                    ModManager.normalizeModId(it.manifestModId) == normalized)
        }
        val exact = matches.filter { ModManager.normalizeModId(it.modId) == normalized }
        val candidates = exact.ifEmpty { matches }
        require(candidates.isNotEmpty()) { "mod_not_installed" }
        require(candidates.size == 1) { "ambiguous_mod_id_use_catalog_mod_id" }
        return candidates.single().let { AgentModReadTarget(it.modId, it.jarFile) }
    }
}
