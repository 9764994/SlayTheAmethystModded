package io.stamethyst.backend.render

import io.stamethyst.backend.plugin.RendererPluginRuntime
import io.stamethyst.config.RenderSurfaceBackend

data class RendererDecision(
    val selectionMode: RendererSelectionMode,
    val manualBackend: RendererBackend?,
    val automaticBackend: RendererBackend,
    val effectiveBackend: RendererBackend,
    val requestedSurfaceBackend: RenderSurfaceBackend,
    val effectiveSurfaceBackend: RenderSurfaceBackend,
    val availableBackends: List<RendererAvailability>,
    val manualFallbackAvailability: RendererAvailability? = null,
    val enableEmuiIteratorMitigation: Boolean = false,
    val enableUbwcHint: Boolean = false,
    /** 选中外部渲染器插件时非空：提供 GL 绝对路径与插件声明的环境变量 */
    val effectivePlugin: RendererPluginRuntime? = null
) {
    val surfaceBackendForced: Boolean
        get() = effectiveSurfaceBackend != requestedSurfaceBackend

    val usedAutomaticFallback: Boolean
        get() = manualFallbackAvailability != null

    /** 展示名：插件渲染器用 `插件名 · 渲染器名`，内置渲染器用枚举名 */
    val effectiveDisplayName: String
        get() = effectivePlugin?.summary ?: effectiveBackend.displayName

    fun effectiveRendererSummary(): String {
        return buildString {
            append(effectiveDisplayName)
            if (selectionMode == RendererSelectionMode.AUTO) {
                append(" (Auto)")
            } else if (usedAutomaticFallback) {
                append(" (Auto fallback)")
            }
        }
    }

    fun autoSelectionSummary(): String = automaticBackend.displayName

    fun fallbackSummary(): String? {
        val fallback = manualFallbackAvailability ?: return null
        return buildString {
            append(manualBackend?.displayName ?: "Manual renderer")
            append(" unavailable")
            fallback.describeUnavailable()?.let {
                append(": ")
                append(it)
            }
            append(". Using ")
            append(automaticBackend.displayName)
            append(".")
        }
    }

    fun overlaySummary(): String {
        return buildString {
            append(effectiveDisplayName)
            if (selectionMode == RendererSelectionMode.AUTO) {
                append(" [Auto]")
            } else if (usedAutomaticFallback) {
                append(" [Fallback]")
            }
            if (surfaceBackendForced) {
                append(" [")
                append(
                    when (effectiveSurfaceBackend) {
                        RenderSurfaceBackend.SURFACE_VIEW -> "SV"
                        RenderSurfaceBackend.TEXTURE_VIEW -> "TV"
                    }
                )
                append("]")
            }
        }
    }
}
