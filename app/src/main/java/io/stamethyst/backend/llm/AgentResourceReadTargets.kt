package io.stamethyst.backend.llm

import java.io.File

data class AgentResourceReadTarget(val source: String, val jar: File, val modId: String? = null)

/** Resource-only routing; game access does not broaden class tools or the installed-mod catalog. */
class AgentResourceReadTargets(
    private val mods: AgentModReadTargets,
    private val gameJar: File?,
) {
    fun resolve(source: String?, modId: String?): AgentResourceReadTarget = when (source ?: "mod") {
        "mod" -> mods.resolve(modId).let { AgentResourceReadTarget("mod", it.jar, it.modId) }
        "game" -> {
            require(modId.isNullOrBlank()) { "mod_id_not_allowed_for_game_source" }
            require(gameJar != null && gameJar.isFile) { "game_jar_not_found" }
            AgentResourceReadTarget("game", gameJar)
        }
        else -> throw IllegalArgumentException("invalid_source")
    }
}
