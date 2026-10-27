package io.stamethyst.backend.plugin

/**
 * 外部渲染器插件（兼容 FCL / ZalithLauncher 的 RendererPlugin 协议）的运行时模型。
 *
 * 协议来源：FoldCraftLauncher 的 `com.mio.plugin.RendererPlugin`。插件以独立 APK 形式安装，
 * 通过 `<meta-data>`（v1）或 `@string/config` 中的 JSON（v2）声明渲染器信息，原生库随插件
 * APK 的 `lib/<abi>/` 一起分发，运行时从插件自身的 `nativeLibraryDir` 加载。
 */
enum class RendererPluginProtocol {
    /** `<meta-data android:name="fclPlugin" android:value="true"/>` + renderer/pojavEnv 等键 */
    V1,

    /** `<meta-data android:name="fclPlugin_V2" android:resource="@string/config"/>`+JSON */
    V2
}

/** v2 协议中的环境变量种类，与 FCL 的 NormalEnv/SelectableEnv/CustomizableEnv/ToggleableEnv 对应 */
enum class RendererPluginEnvType {
    /** 固定值，不可配置 */
    NORMAL,

    /** 从预设列表中选择，可带启用开关 */
    SELECTABLE,

    /** 用户自由输入，留空则不注入 */
    CUSTOMIZABLE,

    /** 布尔开关，决定是否注入固定值 */
    TOGGLEABLE
}

/**
 * 一个环境变量声明。
 *
 * @param value NORMAL/TOGGLEABLE 的固定值；SELECTABLE 的默认选中值；CUSTOMIZABLE 的默认输入值
 * @param options SELECTABLE 的候选值（已包含 [value]）
 * @param enabledByDefault SELECTABLE/TOGGLEABLE 的默认开关状态
 */
data class RendererPluginEnvSpec(
    val key: String,
    val type: RendererPluginEnvType,
    val title: String?,
    val value: String,
    val options: List<String> = emptyList(),
    val enabledByDefault: Boolean = true,
    /** SELECTABLE 是否带启用开关（v2 的 check 为 null 表示始终启用） */
    val checkable: Boolean = false
) {
    val displayTitle: String get() = title?.takeIf { it.isNotBlank() } ?: key

    val configurable: Boolean
        get() = type != RendererPluginEnvType.NORMAL
}

/**
 * 扫描到的一个插件渲染器。
 *
 * @param rendererId v1 的渲染器 id 由插件包名派生，v2 使用配置里的 rendererId
 * @param glLibraryName GL 库名，可能是裸名（`libmobileglues.so`）或绝对路径
 * @param nativeLibraryDir 插件 APK 的原生库目录，用于拼接绝对路径与加入 LD_LIBRARY_PATH
 */
data class RendererPlugin(
    val packageName: String,
    val pluginLabel: String,
    val rendererId: String,
    val displayName: String,
    val description: String,
    val glLibraryName: String,
    val eglLibraryName: String,
    val nativeLibraryDir: String,
    /**
     * 插件 APK 路径列表（base + split）。用于 `extractNativeLibs=false` 时直接检查
     * APK 内的 lib/<abi>/；从应用商店安装的插件库可能只存在于 split APK 里。
     */
    val apkPaths: List<String> = emptyList(),
    /** 插件原生库的 ABI 目录名，从 nativeLibraryDir 推断 */
    val abi: String = "",
    val env: List<RendererPluginEnvSpec>,
    val protocol: RendererPluginProtocol,
    val minGameVersion: String = "",
    val maxGameVersion: String = ""
) {
    /** 持久化用的稳定 id，插件包名 + 渲染器 id */
    val id: String get() = "$packageName/$rendererId"

    val glLibraryPath: String get() = resolveLibraryPath(glLibraryName)

    val eglLibraryPath: String get() = if (eglLibraryName.isBlank()) "" else resolveLibraryPath(eglLibraryName)

    /**
     * 插件目录里存在该库时返回绝对路径，否则保留裸名交给动态链接器
     * （`libEGL.so` 这类系统库本来就不在插件 APK 里）。
     */
    private fun resolveLibraryPath(name: String): String {
        if (name.isBlank()) return name
        if (name.startsWith("/")) return name
        if (nativeLibraryDir.isBlank()) return name
        val candidate = java.io.File(nativeLibraryDir, name)
        return if (candidate.isFile) candidate.absolutePath else name
    }

    val hasEglLibrary: Boolean get() = eglLibraryName.isNotBlank()

    fun toAbsolutePath(name: String): String {
        if (name.isBlank()) return name
        if (name.startsWith("/")) return name
        return "$nativeLibraryDir/$name"
    }
}

/** 解析后的可执行配置：库的绝对路径 + 已按用户配置展开的环境变量 */
data class RendererPluginRuntime(
    val plugin: RendererPlugin,
    val environment: Map<String, String>
) {
    val glLibraryPath: String get() = plugin.glLibraryPath

    val eglLibraryPath: String get() = plugin.eglLibraryPath

    /** 展示用摘要：`插件名 · 渲染器名` */
    val summary: String
        get() = if (plugin.pluginLabel.isBlank()) plugin.displayName
        else "${plugin.pluginLabel} · ${plugin.displayName}"
}
