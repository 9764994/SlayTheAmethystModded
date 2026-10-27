package io.stamethyst.backend.render

enum class RendererAvailabilityReason {
    VULKAN_UNSUPPORTED,
    MISSING_NATIVE_LIBRARIES,

    /** 选中的外部渲染器插件已被卸载或缺少库文件 */
    PLUGIN_UNAVAILABLE
}

data class RendererAvailability(
    val backend: RendererBackend,
    val available: Boolean,
    val reasons: List<RendererAvailabilityReason> = emptyList(),
    val missingLibraries: List<String> = emptyList()
) {
    fun describeUnavailable(): String? {
        if (available) {
            return null
        }
        val parts = ArrayList<String>(2)
        if (reasons.contains(RendererAvailabilityReason.VULKAN_UNSUPPORTED)) {
            parts += "requires Vulkan support"
        }
        if (reasons.contains(RendererAvailabilityReason.PLUGIN_UNAVAILABLE)) {
            parts += "renderer plugin unavailable"
        }
        if (missingLibraries.isNotEmpty() &&
            !reasons.contains(RendererAvailabilityReason.PLUGIN_UNAVAILABLE)
        ) {
            parts += "missing ${missingLibraries.joinToString(", ")}"
        }
        return parts.joinToString("; ").ifBlank { "unavailable" }
    }
}
