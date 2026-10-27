package io.stamethyst.backend.plugin

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * 扫描设备上已安装的 FCL 渲染器插件 APK。
 *
 * 解析规则与 FoldCraftLauncher 的 `com.mio.plugin.RendererPlugin` 保持一致，v2 优先于 v1：
 * 同时声明两种协议的插件只按 v2 处理。
 *
 * v1（Boat/Pojav 时代协议）：
 * ```
 * <meta-data android:name="fclPlugin" android:value="true"/>
 * <meta-data android:name="renderer" android:value="MobileGlues:libmobileglues.so:libmobileglues.so"/>
 * <meta-data android:name="des" android:value="MobileGlues (OpenGL 4.0)"/>
 * <meta-data android:name="pojavEnv" android:value="LIBGL_ES=3:POJAV_RENDERER=opengles3"/>
 * ```
 *
 * v2（RendererPlugin-v2）：`fclPlugin_V2` 的值为字符串资源 id，内容为 JSON：
 * ```
 * {"displayName":"...","rendererId":"...","rendererGLPath":"**|libx.so",
 *  "rendererEGLPath":"**|libx.so","env":[{"type":"NormalEnv","key":"K","value":"V"}]}
 * ```
 */
object RendererPluginScanner {
    private const val TAG = "RendererPluginScanner"

    const val META_PLUGIN = "fclPlugin"
    const val META_PLUGIN_V2 = "fclPlugin_V2"

    /** v2 配置中 `**|` 前缀表示“插件 nativeLibraryDir 下的文件” */
    private const val NATIVE_PREFIX = "**|"

    private const val KEY_RENDERER = "renderer"
    private const val KEY_DESCRIPTION = "des"
    private const val KEY_POJAV_ENV = "pojavEnv"
    private const val KEY_MIN_MC_VERSION = "minMCVer"
    private const val KEY_MAX_MC_VERSION = "maxMCVer"

    private const val SCAN_FLAGS = PackageManager.GET_META_DATA

    fun scan(context: Context): List<RendererPlugin> {
        val packageManager = context.packageManager
        val plugins = ArrayList<RendererPlugin>()
        val resolvedActivities = try {
            packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN), SCAN_FLAGS)
        } catch (error: Throwable) {
            Log.w(TAG, "queryIntentActivities failed", error)
            return emptyList()
        }
        resolvedActivities.forEach { resolved ->
            val info = resolved.activityInfo?.applicationInfo ?: return@forEach
            if (info.flags and ApplicationInfo.FLAG_SYSTEM != 0) return@forEach
            val metaData = info.metaData ?: return@forEach
            val pluginLabel = runCatching { info.loadLabel(packageManager).toString() }
                .getOrDefault(info.packageName)
            val plugin = parseV2(packageManager, info, metaData, pluginLabel)
                ?: parseV1(info, metaData, pluginLabel)
            if (plugin != null) {
                plugins.add(plugin)
            }
        }
        val result = plugins
            .distinctBy { it.id }
            .sortedWith(compareBy({ it.pluginLabel.lowercase() }, { it.displayName.lowercase() }))
        // 扫描结果是排查“设置里看不到插件”的第一手线索，保留一行 info 级别日志。
        Log.i(
            TAG,
            "Renderer plugin scan: ${result.size} renderer(s) from " +
                "${resolvedActivities.size} launchable app(s)"
        )
        if (result.isEmpty() && resolvedActivities.isEmpty()) {
            Log.w(
                TAG,
                "No launchable app visible to PackageManager; check <queries><intent>ACTION_MAIN</intent></queries>"
            )
        }
        result.forEach { plugin ->
            Log.i(
                TAG,
                "  plugin=${plugin.packageName} renderer=${plugin.displayName} " +
                    "gl=${plugin.glLibraryName} protocol=${plugin.protocol}"
            )
        }
        return result
    }

    /** base APK + split APK；渲染器插件把 .so 放在 split 里的场景并不少见 */
    private fun resolveApkPaths(info: ApplicationInfo): List<String> {
        val paths = LinkedHashSet<String>()
        info.sourceDir?.takeIf { it.isNotBlank() }?.let(paths::add)
        info.splitSourceDirs?.forEach { path -> path?.takeIf { it.isNotBlank() }?.let(paths::add) }
        return paths.toList()
    }

    /** 插件原生库目录形如 /data/app/<pkg>-<hash>/lib/arm64，最后一段即 ABI */
    private fun resolveAbi(info: ApplicationInfo): String {
        val fromDir = info.nativeLibraryDir?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
        if (fromDir != null) return fromDir
        return android.os.Build.SUPPORTED_ABIS.firstOrNull().orEmpty()
    }

    private fun parseV1(
        info: ApplicationInfo,
        metaData: android.os.Bundle,
        pluginLabel: String
    ): RendererPlugin? {
        if (!metaData.getBoolean(META_PLUGIN, false)) return null
        val rendererValue = metaData.getString(KEY_RENDERER) ?: return null
        // 格式固定为 "显示名:GL库:EGL库"
        val parts = rendererValue.split(":")
        if (parts.size < 3) return null
        val displayName = parts[0].ifBlank { pluginLabel }
        val glLibrary = parts[1].trim()
        if (glLibrary.isEmpty()) return null
        val eglLibrary = parts[2].trim()
        return RendererPlugin(
            packageName = info.packageName,
            pluginLabel = pluginLabel,
            rendererId = rendererValue,
            displayName = displayName,
            description = metaData.getString(KEY_DESCRIPTION).orEmpty(),
            glLibraryName = glLibrary,
            eglLibraryName = eglLibrary,
            nativeLibraryDir = info.nativeLibraryDir.orEmpty(),
            apkPaths = resolveApkPaths(info),
            abi = resolveAbi(info),
            env = parsePojavEnv(metaData.getString(KEY_POJAV_ENV)),
            protocol = RendererPluginProtocol.V1,
            minGameVersion = metaData.getString(KEY_MIN_MC_VERSION).orEmpty(),
            maxGameVersion = metaData.getString(KEY_MAX_MC_VERSION).orEmpty()
        )
    }

    /** v1 的 pojavEnv 形如 `LIBGL_ES=3:POJAV_RENDERER=opengles3:FOO=bar` */
    private fun parsePojavEnv(raw: String?): List<RendererPluginEnvSpec> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split(":")
            .mapNotNull { entry ->
                val separator = entry.indexOf('=')
                if (separator <= 0) return@mapNotNull null
                val key = entry.substring(0, separator).trim()
                val value = entry.substring(separator + 1).trim()
                if (key.isEmpty()) return@mapNotNull null
                RendererPluginEnvSpec(
                    key = key,
                    type = RendererPluginEnvType.NORMAL,
                    title = null,
                    value = value
                )
            }
    }

    private fun parseV2(
        packageManager: PackageManager,
        info: ApplicationInfo,
        metaData: android.os.Bundle,
        pluginLabel: String
    ): RendererPlugin? {
        if (!metaData.containsKey(META_PLUGIN_V2)) return null
        val configResId = metaData.getInt(META_PLUGIN_V2)
        if (configResId == 0) return null
        return try {
            val resources = packageManager.getResourcesForApplication(info)
            val json = JSONObject(resources.getString(configResId))
            val displayName = json.optString("displayName").ifBlank {
                json.optString("rendererId").ifBlank { pluginLabel }
            }
            val rendererId = json.optString("rendererId").ifBlank { displayName }
            val glLibrary = stripNativePrefix(json.optString("rendererGLPath"))
            if (glLibrary.isBlank()) return null
            RendererPlugin(
                packageName = info.packageName,
                pluginLabel = pluginLabel,
                rendererId = rendererId,
                displayName = displayName,
                description = json.optString("description"),
                glLibraryName = glLibrary,
                eglLibraryName = stripNativePrefix(json.optString("rendererEGLPath")),
                nativeLibraryDir = info.nativeLibraryDir.orEmpty(),
            apkPaths = resolveApkPaths(info),
            abi = resolveAbi(info),
                env = parseV2Env(resources, metaData, json.optJSONArray("env")),
                protocol = RendererPluginProtocol.V2,
                minGameVersion = json.optString("minMCVer"),
                maxGameVersion = json.optString("maxMCVer")
            )
        } catch (error: Throwable) {
            Log.w(TAG, "Failed to parse v2 renderer plugin ${info.packageName}", error)
            null
        }
    }

    private fun stripNativePrefix(raw: String): String {
        val value = raw.trim()
        return if (value.startsWith(NATIVE_PREFIX)) value.removePrefix(NATIVE_PREFIX) else value
    }

    private fun parseV2Env(
        resources: android.content.res.Resources,
        metaData: android.os.Bundle,
        array: JSONArray?
    ): List<RendererPluginEnvSpec> {
        if (array == null) return emptyList()
        val specs = ArrayList<RendererPluginEnvSpec>(array.length())
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val key = item.optString("key")
            if (key.isBlank()) continue
            val title = resolveTitle(resources, metaData, item.optJSONObject("title"))
            val type = item.optString("type")
            val spec = when (type) {
                "SelectableEnv" -> {
                    val items = item.optJSONObject("items")
                    val defaultValue = items?.optString("defaultValue").orEmpty()
                    val values = LinkedHashSet<String>()
                    if (defaultValue.isNotEmpty()) values.add(defaultValue)
                    val arrayValues = items?.optJSONArray("values")
                    if (arrayValues != null) {
                        for (i in 0 until arrayValues.length()) {
                            values.add(arrayValues.optString(i))
                        }
                    }
                    RendererPluginEnvSpec(
                        key = key,
                        type = RendererPluginEnvType.SELECTABLE,
                        title = title,
                        value = defaultValue,
                        options = values.toList(),
                        enabledByDefault = if (item.has("check")) item.optBoolean("check", true) else true,
                        checkable = item.has("check")
                    )
                }

                "CustomizableEnv" -> RendererPluginEnvSpec(
                    key = key,
                    type = RendererPluginEnvType.CUSTOMIZABLE,
                    title = title,
                    value = item.optString("defaultValue")
                )

                "ToggleableEnv" -> RendererPluginEnvSpec(
                    key = key,
                    type = RendererPluginEnvType.TOGGLEABLE,
                    title = title,
                    value = item.optString("value"),
                    enabledByDefault = item.optBoolean("toggle", true),
                    checkable = true
                )

                else -> RendererPluginEnvSpec(
                    key = key,
                    type = RendererPluginEnvType.NORMAL,
                    title = title,
                    value = item.optString("value")
                )
            }
            specs.add(spec)
        }
        return specs
    }

    /** v2 的标题是一个 meta-data 键，指向插件内的字符串资源 */
    private fun resolveTitle(
        resources: android.content.res.Resources,
        metaData: android.os.Bundle,
        title: JSONObject?
    ): String? {
        val key = title?.optString("key")?.takeIf { it.isNotBlank() } ?: return null
        val resId = metaData.getInt(key, 0)
        if (resId == 0) return null
        return runCatching { resources.getString(resId) }.getOrNull()
    }
}
