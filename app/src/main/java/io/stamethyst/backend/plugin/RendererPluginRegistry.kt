package io.stamethyst.backend.plugin

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.util.zip.ZipFile

/**
 * 插件渲染器的进程内注册表：负责扫描结果缓存、可用性判断、环境变量展开，
 * 以及把“用户选中的插件 id”解析成启动时可用的 [RendererPluginRuntime]。
 */
object RendererPluginRegistry {
    private const val TAG = "RendererPluginRegistry"

    /** 系统库搜索目录；插件声明的 EGL/GL 通常落在这些位置（libEGL.so 等） */
    private val LINKER_LIBRARY_DIRECTORIES = listOf(
        "/system/lib64",
        "/system/lib64/egl",
        "/vendor/lib64",
        "/vendor/lib64/egl",
        "/system/lib",
        "/system/lib/egl",
        "/vendor/lib",
        "/vendor/lib/egl"
    )

    /** 由系统/驱动提供、不会出现在插件 APK 里的常见库名 */
    private val SYSTEM_LIBRARY_NAMES = setOf(
        "libEGL.so",
        "libGLESv1_CM.so",
        "libGLESv2.so",
        "libGL.so",
        "libvulkan.so"
    )

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
        return missingLibraries(plugin).isEmpty()
    }

    /**
     * 缺失的库名列表；空列表表示插件可用。
     *
     * 依次检查：配置里的绝对路径 / 插件 nativeLibraryDir / base 与 split APK 内的 lib/<abi>/。
     * 插件若用 `extractNativeLibs=false` 打包，库只存在于 APK 内，必须走第三条路径；
     * 从应用商店安装的插件，库还可能只在 split APK 里。
     */
    fun missingLibraries(plugin: RendererPlugin): List<String> {
        val missing = ArrayList<String>(2)
        if (plugin.glLibraryName.isBlank() || !libraryExists(plugin, plugin.glLibraryName)) {
            missing.add(plugin.glLibraryName.ifBlank { "GL library" })
        }
        if (plugin.hasEglLibrary && !libraryExists(plugin, plugin.eglLibraryName)) {
            missing.add(plugin.eglLibraryName)
        }
        return missing
    }

    private fun libraryExists(plugin: RendererPlugin, libraryName: String): Boolean {
        if (libraryName.isBlank()) return false
        val absolute = File(plugin.toAbsolutePath(libraryName))
        if (absolute.isFile) return true
        if (plugin.nativeLibraryDir.isNotBlank()) {
            val inNativeDir = File(plugin.nativeLibraryDir, absolute.name)
            if (inNativeDir.isFile) return true
        }
        if (apkContainsLibrary(plugin, absolute.name)) return true
        // libEGL.so 这类库由系统提供，插件 APK 里不会有，按系统库目录探测。
        return systemLibraryExists(absolute.name)
    }

    private fun systemLibraryExists(libraryName: String): Boolean {
        if (libraryName in SYSTEM_LIBRARY_NAMES) return true
        return LINKER_LIBRARY_DIRECTORIES.any { directory -> File(directory, libraryName).isFile }
    }

    /**
     * `extractNativeLibs=false` 的插件 APK 不会把 .so 解包到 nativeLibraryDir，
     * 此时直接在 APK 的 lib/<abi>/ 中按文件名匹配；真正的加载由动态链接器完成。
     */
    private fun apkContainsLibrary(plugin: RendererPlugin, libraryName: String): Boolean {
        if (plugin.apkPaths.isEmpty()) return false
        val abis = LinkedHashSet<String>()
        if (plugin.abi.isNotBlank()) abis.add(plugin.abi)
        abis.addAll(Build.SUPPORTED_ABIS)
        plugin.apkPaths.forEach { apkPath ->
            try {
                val found = ZipFile(File(apkPath)).use { zip ->
                    zip.entries().asSequence().any { entry ->
                        if (entry.isDirectory) return@any false
                        val name = entry.name
                        if (!name.startsWith("lib/")) return@any false
                        if (!name.endsWith("/$libraryName", ignoreCase = true)) return@any false
                        val entryAbi = name.removePrefix("lib/").substringBefore('/')
                        abis.isEmpty() || entryAbi in abis
                    }
                }
                if (found) {
                    Log.i(TAG, "Plugin library found in ${File(apkPath).name}: $libraryName")
                    return true
                }
            } catch (error: Throwable) {
                Log.w(TAG, "Failed to inspect $apkPath for $libraryName", error)
            }
        }
        return false
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
