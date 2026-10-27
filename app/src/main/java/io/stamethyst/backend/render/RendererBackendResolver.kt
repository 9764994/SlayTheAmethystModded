package io.stamethyst.backend.render

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import io.stamethyst.backend.plugin.RendererPluginRegistry
import io.stamethyst.backend.plugin.RendererPluginRuntime
import io.stamethyst.config.RenderSurfaceBackend
import io.stamethyst.config.RuntimePaths
import java.io.File
import java.util.LinkedHashSet
import java.util.Locale

object RendererBackendResolver {
    data class RuntimeInfo(
        val packagedLibraryNames: Set<String>,
        val hasVulkanSupport: Boolean,
        val supportsGles3: Boolean,
        val manufacturer: String,
        val brand: String,
        val model: String,
        val hardware: String,
        val socModel: String,
        val oneUiVersion: String
    )

    private val automaticOrder = listOf(
        RendererBackend.OPENGL_ES_MOBILEGLUES,
        RendererBackend.OPENGL_ES2_GL4ES,
        RendererBackend.OPENGL_ES3_DESKTOPGL_ZINK_KOPPER,
        RendererBackend.VULKAN_ZINK,
        RendererBackend.OPENGL_ES2_NATIVE
    )

    fun resolve(
        requestedSurfaceBackend: RenderSurfaceBackend,
        selectionMode: RendererSelectionMode,
        manualBackend: RendererBackend?,
        runtimeInfo: RuntimeInfo,
        manualPlugin: RendererPluginRuntime? = null
    ): RendererDecision {
        val availabilityMap = LinkedHashMap<RendererBackend, RendererAvailability>(RendererBackend.entries.size)
        for (backend in RendererBackend.entries) {
            availabilityMap[backend] = evaluateAvailability(backend, runtimeInfo, manualPlugin)
        }

        val automaticBackend = automaticOrder.firstOrNull { backend ->
            availabilityMap[backend]?.available == true
        } ?: RendererBackend.OPENGL_ES2_NATIVE

        val manualAvailability = manualBackend?.let { availabilityMap[it] }
        val effectiveBackend = if (
            selectionMode == RendererSelectionMode.MANUAL &&
            manualBackend != null &&
            manualAvailability?.available == true
        ) {
            manualBackend
        } else {
            automaticBackend
        }

        val effectiveSurfaceBackend =
            effectiveBackend.forcedSurfaceBackend ?: requestedSurfaceBackend

        return RendererDecision(
            selectionMode = selectionMode,
            manualBackend = manualBackend,
            automaticBackend = automaticBackend,
            effectiveBackend = effectiveBackend,
            requestedSurfaceBackend = requestedSurfaceBackend,
            effectiveSurfaceBackend = effectiveSurfaceBackend,
            availableBackends = RendererBackend.entries
                .filter { it != RendererBackend.PLUGIN_EXTERNAL }
                .mapNotNull { availabilityMap[it] },
            manualFallbackAvailability = if (
                selectionMode == RendererSelectionMode.MANUAL &&
                manualBackend != null &&
                manualAvailability?.available == false
            ) {
                manualAvailability
            } else {
                null
            },
            enableEmuiIteratorMitigation = isHuaweiOrHonor(runtimeInfo),
            enableUbwcHint = effectiveBackend == RendererBackend.OPENGL_ES3_DESKTOPGL_ZINK_KOPPER &&
                shouldUseUbwcHint(runtimeInfo),
            effectivePlugin = if (effectiveBackend == RendererBackend.PLUGIN_EXTERNAL) manualPlugin else null
        )
    }

    fun resolve(
        context: Context,
        requestedSurfaceBackend: RenderSurfaceBackend,
        selectionMode: RendererSelectionMode,
        manualBackend: RendererBackend?
    ): RendererDecision {
        // 选中外部渲染器插件时，把持久化的插件 id 解析成运行时配置；插件被卸载后
        // 这里返回 null，可用性判断会把它标记为不可用并回退到自动选择。
        val manualPlugin = if (manualBackend == RendererBackend.PLUGIN_EXTERNAL) {
            RendererPluginRegistry.selectedRuntime(context)
        } else {
            null
        }
        return resolve(
            requestedSurfaceBackend = requestedSurfaceBackend,
            selectionMode = selectionMode,
            manualBackend = manualBackend,
            runtimeInfo = collectRuntimeInfo(context),
            manualPlugin = manualPlugin
        )
    }

    @JvmStatic
    fun collectRuntimeInfo(context: Context): RuntimeInfo {
        val packagedLibraryNames = LinkedHashSet<String>()
        collectLibraryNames(File(context.applicationInfo.nativeLibraryDir), packagedLibraryNames)
        collectLibraryNames(RuntimePaths.externalNativeLibDir(context), packagedLibraryNames)
        return RuntimeInfo(
            packagedLibraryNames = packagedLibraryNames,
            hasVulkanSupport = hasVulkanSupport(context.packageManager),
            supportsGles3 = supportsGles3(context.packageManager),
            manufacturer = Build.MANUFACTURER.orEmpty(),
            brand = Build.BRAND.orEmpty(),
            model = Build.MODEL.orEmpty(),
            hardware = Build.HARDWARE.orEmpty(),
            socModel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Build.SOC_MODEL.orEmpty()
            } else {
                ""
            },
            oneUiVersion = readSystemProperty("ro.build.version.oneui")
        )
    }

    private fun collectLibraryNames(directory: File, out: MutableSet<String>) {
        directory.list()
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.forEach(out::add)
    }

    private fun evaluateAvailability(
        backend: RendererBackend,
        runtimeInfo: RuntimeInfo,
        manualPlugin: RendererPluginRuntime? = null
    ): RendererAvailability {
        // 插件渲染器的库来自插件 APK，不会出现在 packagedLibraryNames 里，
        // 可用性完全取决于插件是否仍然安装且库文件存在。
        if (backend == RendererBackend.PLUGIN_EXTERNAL) {
            val available = manualPlugin != null
            return RendererAvailability(
                backend = backend,
                available = available,
                reasons = if (available) emptyList() else listOf(RendererAvailabilityReason.PLUGIN_UNAVAILABLE),
                missingLibraries = if (available) emptyList() else listOf(manualPlugin?.plugin?.glLibraryName ?: "plugin")
            )
        }
        val reasons = ArrayList<RendererAvailabilityReason>(2)
        if (backend.requiresVulkan && !runtimeInfo.hasVulkanSupport) {
            reasons += RendererAvailabilityReason.VULKAN_UNSUPPORTED
        }
        val missingLibraries = backend.requiredNativeLibraries
            .filterNot { runtimeInfo.packagedLibraryNames.contains(it) }
            .sorted()
        if (missingLibraries.isNotEmpty()) {
            reasons += RendererAvailabilityReason.MISSING_NATIVE_LIBRARIES
        }
        return RendererAvailability(
            backend = backend,
            available = reasons.isEmpty(),
            reasons = reasons,
            missingLibraries = missingLibraries
        )
    }

    private fun hasVulkanSupport(packageManager: PackageManager): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
            packageManager.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL) &&
            packageManager.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_VERSION)
    }

    private fun supportsGles3(packageManager: PackageManager): Boolean {
        return try {
            packageManager.systemAvailableFeatures.orEmpty().any { featureInfo ->
                featureInfo.reqGlEsVersion >= 0x00030000
            }
        } catch (_: Throwable) {
            false
        }
    }

    private fun isHuaweiOrHonor(runtimeInfo: RuntimeInfo): Boolean {
        val manufacturer = runtimeInfo.manufacturer.lowercase(Locale.ROOT)
        val brand = runtimeInfo.brand.lowercase(Locale.ROOT)
        return manufacturer.contains("huawei") ||
            manufacturer.contains("honor") ||
            brand.contains("huawei") ||
            brand.contains("honor")
    }

    private fun shouldUseUbwcHint(runtimeInfo: RuntimeInfo): Boolean {
        val manufacturer = runtimeInfo.manufacturer.lowercase(Locale.ROOT)
        if (!manufacturer.contains("samsung")) {
            return false
        }
        if (runtimeInfo.oneUiVersion.isBlank()) {
            return false
        }
        val combined = listOf(
            runtimeInfo.model,
            runtimeInfo.hardware,
            runtimeInfo.socModel
        ).joinToString(" ").lowercase(Locale.ROOT)
        return combined.contains("adreno 740") ||
            combined.contains("sm8550") ||
            combined.contains("snapdragon 8 gen 2") ||
            combined.contains("kalama")
    }

    private fun readSystemProperty(key: String): String {
        return try {
            val clazz = Class.forName("android.os.SystemProperties")
            val method = clazz.getMethod("get", String::class.java)
            (method.invoke(null, key) as? String).orEmpty()
        } catch (_: Throwable) {
            ""
        }
    }
}
