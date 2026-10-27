package io.stamethyst.backend.plugin

import android.content.Context
import android.util.Log
import java.io.File
import java.util.zip.ZipFile

/**
 * 插件渲染器的进程内注册表：负责扫描结果缓存、可用性判断、环境变量展开，
 * 以及把“用户选中的插件 id”解析成启动时可用的 [RendererPluginRuntime]。
 */
object RendererPluginRegistry {
    private const val TAG = "RendererPluginRegistry"

    @Volatile
    private var cachedPlugins: List<RendererPlugin>? = null

    /** 扫描结果发生变化（安装/卸载/禁用插件）时调用 */
    fun invalidate() {
        cachedPlugins = null
    }

    fun plugins(context: Context): List<RendererPlugin> {
        cachedPlugins?.let { return it }
        return synchronized(this) {
            cachedPlugins ?: RendererPluginScanner.scan(context).also { cachedPlugins = it }
        }
    }

    fun enabledPlugins(context: Context): List<RendererPlugin> {
        val disabled = RendererPluginStore.disabledPackages(context)
        return plugins(context).filter { it.packageName !in disabled }
    }

    fun find(context: Context, pluginId: String?): RendererPlugin? {
        if (pluginId.isNullOrBlank()) return null
        return plugins(context).firstOrNull { it.id == pluginId }
    }

    /** 当前选中的插件 id；未选中或插件已消失时返回 null */
    fun selectedPluginId(context: Context): String? {
        val stored = RendererPluginStore.selectedPluginId(context) ?: return null
        val plugin = find(context, stored) ?: return null
        return plugin.id
    }

    fun select(context: Context, pluginId: String?) {
        RendererPluginStore.setSelectedPluginId(context, pluginId)
    }

    /** 启动链路使用：把选中插件解析成绝对库路径 + 展开后的环境变量 */
    fun selectedRuntime(context: Context): RendererPluginRuntime? {
        val plugin = find(context, RendererPluginStore.selectedPluginId(context)) ?: return null
        return runtimeOf(context, plugin)
    }

    fun runtimeOf(context: Context, plugin: RendererPlugin): RendererPluginRuntime {
        return RendererPluginRuntime(plugin = plugin, environment = environmentOf(context, plugin))
    }

    fun isAvailable(plugin: RendererPlugin): Boolean {
        if (plugin.glLibraryPath.isBlank()) return false
        if (libraryExists(plugin, plugin.glLibraryName)) {
            return !plugin.hasEglLibrary || libraryExists(plugin, plugin.eglLibraryName)
        }
        return false
    }

    fun unavailableReason(context: Context, plugin: RendererPlugin): String? {
        if (!isAvailable(plugin)) {
            return "missing ${plugin.glLibraryName}"
        }
        return null
    }

    private fun libraryExists(plugin: RendererPlugin, libraryName: String): Boolean {
        if (libraryName.isBlank()) return false
        val direct = File(plugin.toAbsolutePath(libraryName))
        if (direct.isFile) return true
        return apkContainsLibrary(plugin, File(libraryName).name)
    }

    /**
     * `extractNativeLibs=false` 的插件 APK 不会把 .so 解包到 nativeLibraryDir，
     * 此时需要直接在 APK 的 lib/<abi>/ 里确认库存在；真正的加载由动态链接器按需完成。
     */
    private fun apkContainsLibrary(plugin: RendererPlugin, libraryName: String): Boolean {
        val apkPath = plugin.apkPath
        if (apkPath.isBlank()) return false
        val abi = plugin.abi.ifBlank { "arm64-v8a" }
        return try {
            ZipFile(File(apkPath)).use { zip ->
                zip.getEntry("lib/$abi/$libraryName") != null
            }
        } catch (error: Throwable) {
            Log.w(TAG, "Failed to inspect ${plugin.packageName} for $libraryName", error)
            false
        }
    }

    /** 按用户配置展开插件的环境变量，逻辑对齐 FCL 的 buildPojavEnv */
    fun environmentOf(context: Context, plugin: RendererPlugin): Map<String, String> {
        val environment = LinkedHashMap<String, String>()
        val dlopenEntries = LinkedHashSet<String>()
        plugin.env.forEach { spec ->
            when (spec.type) {
                RendererPluginEnvType.NORMAL -> {
                    val value = resolveLibraryValue(plugin, spec.value)
                    if (value.isEmpty()) return@forEach
                    // DLOPEN 的多个条目在 v2 协议里合并成一条逗号分隔的环境变量
                    if (spec.key == "DLOPEN") {
                        value.split(',').map(String::trim).filter(String::isNotEmpty)
                            .forEach(dlopenEntries::add)
                    } else {
                        environment[spec.key] = value
                    }
                }

                RendererPluginEnvType.SELECTABLE -> {
                    val enabled = RendererPluginStore.envEnabled(context, plugin.id, spec.key)
                        ?: spec.enabledByDefault
                    if (!enabled) return@forEach
                    val value = RendererPluginStore.envValue(context, plugin.id, spec.key) ?: spec.value
                    if (value.isNotEmpty()) {
                        environment[spec.key] = value
                    }
                }

                RendererPluginEnvType.CUSTOMIZABLE -> {
                    val value = RendererPluginStore.envValue(context, plugin.id, spec.key) ?: spec.value
                    if (value.isNotEmpty()) {
                        environment[spec.key] = value
                    }
                }

                RendererPluginEnvType.TOGGLEABLE -> {
                    val enabled = RendererPluginStore.envEnabled(context, plugin.id, spec.key)
                        ?: spec.enabledByDefault
                    if (!enabled) return@forEach
                    val value = resolveLibraryValue(plugin, spec.value)
                    if (value.isNotEmpty()) {
                        environment[spec.key] = value
                    }
                }
            }
        }
        if (dlopenEntries.isNotEmpty()) {
            environment["DLOPEN"] = dlopenEntries.joinToString(",")
        }
        return environment
    }

    /** 环境变量值里的 `**|` 前缀表示插件目录下的文件，需要展开成绝对路径 */
    private fun resolveLibraryValue(plugin: RendererPlugin, raw: String): String {
        val value = raw.trim()
        if (value.isEmpty()) return value
        return if (value.startsWith(NATIVE_PREFIX)) {
            plugin.toAbsolutePath(value.removePrefix(NATIVE_PREFIX))
        } else {
            value
        }
    }

    private const val NATIVE_PREFIX = "**|"
}
