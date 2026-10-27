# 外部渲染器插件支持（FCL / ZalithLauncher RendererPlugin 协议）

本文档描述本仓库新增的“外部渲染器插件”能力：允许把独立 APK 形式分发的渲染器
（例如 [MobileGlues](https://github.com/MobileGL-Dev/MobileGlues-release)）直接挂到启动器的
渲染器列表里，而不必把它的原生库打进 APK。

协议对齐 [FoldCraftLauncher](https://github.com/FCL-Team/FoldCraftLauncher) 的
`com.mio.plugin.RendererPlugin`（v1 / v2 两代）。

> 状态：实现完成但**未经编译与真机验证**（开发机为 aarch64 Debian，缺少 arm64 build-tools/NDK）。
> 合入前请在有 Android SDK 的环境执行 `./gradlew :app:assembleDebug` 并在设备上按第 5 节验证。

## 1. 协议

### 1.1 v1：meta-data 声明

```xml
<application ... android:extractNativeLibs="true">
    <meta-data android:name="fclPlugin" android:value="true"/>
    <meta-data android:name="renderer" android:value="MobileGlues:libmobileglues.so:libmobileglues.so"/>
    <meta-data android:name="des" android:value="MobileGlues (OpenGL 4.0, 1.17+)"/>
    <meta-data android:name="pojavEnv" android:value="LIBGL_ES=3:POJAV_RENDERER=opengles3:POJAVEXEC_EGL=libmobileglues.so"/>
    <meta-data android:name="minMCVer" android:value=""/>
    <meta-data android:name="maxMCVer" android:value=""/>
</application>
```

| 键 | 含义 |
| --- | --- |
| `fclPlugin` | 声明这是一个插件应用 |
| `renderer` | `显示名:GL库:EGL库`，库名相对插件自己的 `nativeLibraryDir` |
| `des` | 渲染器描述 |
| `pojavEnv` | `K=V` 用 `:` 分隔的环境变量 |
| `boatEnv` | Boat 后端专用，本仓库不读取 |
| `minMCVer` / `maxMCVer` | 兼容版本区间，本仓库只记录不校验 |

### 1.2 v2：`@string/config` JSON

```xml
<meta-data android:name="fclPlugin_V2" android:resource="@string/config"/>
```

```json
{
  "displayName": "MobileGlues",
  "rendererId": "mobileglues",
  "rendererGLPath": "**|libmobileglues.so",
  "rendererEGLPath": "**|libmobileglues.so",
  "env": [
    { "type": "NormalEnv", "key": "LIBGL_ES", "value": "3" },
    { "type": "SelectableEnv", "key": "MG_MULTIDRAW", "title": {"key": "title_multidraw"},
      "check": true, "items": {"defaultValue": "auto", "values": ["auto", "on", "off"]} },
    { "type": "CustomizableEnv", "key": "MG_GL_VERSION", "title": {"key": "title_glver"},
      "defaultValue": "" },
    { "type": "ToggleableEnv", "key": "MG_DEBUG", "value": "1", "toggle": false }
  ]
}
```

- `**|` 前缀表示“插件 nativeLibraryDir 下的文件”，运行时会展开成绝对路径。
- `title.key` 指向插件自身 `meta-data` 里的字符串资源 id。
- `NormalEnv` 的 `DLOPEN` 会与其他条目合并为一条逗号分隔的环境变量（与 FCL 行为一致）。

## 2. 运行时链路

```
设置页选择渲染器
   └─ RendererPluginRegistry.select(context, pluginId)   // 写入 renderer_plugins.xml
      RendererBackend.PLUGIN_EXTERNAL                    // 主配置里记录后端 = plugin_external

启动
   ├─ RendererBackendResolver.resolve(context, ...)
   │     └─ selectedRuntime(context) → RendererPluginRuntime（绝对库路径 + 展开后的 env）
   ├─ NativeLibraryPathResolver          → 插件 nativeLibraryDir 加入 LD_LIBRARY_PATH
   ├─ StsLaunchSpec                      → -Dorg.lwjgl.opengl.libname=<插件 GL 绝对路径>
   └─ JREUtils.setJavaEnvironment        → 插件 env 覆盖内置默认值（POJAVEXEC_EGL / LIBGL_EGL 等）
```

关键点：LWJGL 拿绝对路径直接 `dlopen`，不再依赖 `System.loadLibrary` 的搜索路径；
插件库的依赖（如 `libc++_shared.so`）通过 LD_LIBRARY_PATH 解析。

## 3. 改动清单

新增：

| 文件 | 作用 |
| --- | --- |
| `backend/plugin/RendererPluginModels.kt` | v1/v2 通用模型：`RendererPlugin`、`RendererPluginEnvSpec`、`RendererPluginRuntime` |
| `backend/plugin/RendererPluginScanner.kt` | `PackageManager` 扫描 + v1 meta-data / v2 JSON 解析 |
| `backend/plugin/RendererPluginStore.kt` | 选中项、禁用集合、环境变量覆盖的持久化（`renderer_plugins` 首选项） |
| `backend/plugin/RendererPluginRegistry.kt` | 扫描缓存、可用性判断、环境变量展开 |

修改：

| 文件 | 改动 |
| --- | --- |
| `backend/render/RendererBackend.kt` | 新增 `PLUGIN_EXTERNAL("plugin_external")` |
| `backend/render/RendererDecision.kt` | 新增 `effectivePlugin` 与 `effectiveDisplayName` |
| `backend/render/RendererBackendResolver.kt` | 插件可用性判定、选中插件注入 decision |
| `backend/render/RendererAvailability.kt` | 新增 `PLUGIN_UNAVAILABLE` 原因 |
| `backend/launch/StsLaunchSpec.kt` | `org.lwjgl.opengl.libname` 支持绝对路径 |
| `backend/launch/NativeLibraryPathResolver.kt` | 插件库目录加入原生搜索路径 |
| `net/kdt/pojavlaunch/utils/JREUtils.java` | 注入插件环境变量（覆盖内置默认值） |
| `ui/settings/core/SettingsScreenViewModel.kt` | 渲染器选项合并插件、选择/环境变量读写 |
| `ui/settings/core/SettingsScreen.kt` | 新增动作接线 |
| `ui/settings/sections/SettingsDeveloperSections.kt` | 插件条目、环境变量对话框、描述文案 |
| `res/values*/strings.xml` | 新增 8 条文案（en / zh-rCN / zh-rTW） |

## 4. 数据存储

`SharedPreferences("renderer_plugins")`：

| 键 | 类型 | 含义 |
| --- | --- | --- |
| `selected_plugin` | String | 选中的插件 id（`<包名>/<rendererId>`） |
| `disabled_plugins` | StringSet | 被禁用的插件包名 |
| `env.<pluginId>.<KEY>` | String | 环境变量覆盖值 |
| `env.<pluginId>.<KEY>@enabled` | Boolean | 开关状态 |

主配置（`LauncherConfig`）仍只记录 `plugin_external`，插件 id 单独存放，避免破坏已有键。

## 5. 验证步骤

1. 安装插件 APK（例如 MobileGlues 2.0.0）：
   ```bash
   adb install MobileGlues_2.0.0.apk
   ```
2. 启动器 → 设置 → 开发者选项 → 渲染后端，切到手动，列表里应出现 `MobileGlues · MobileGlues`。
3. 选中后进入“插件环境变量”，确认 `LIBGL_ES` / `POJAV_RENDERER` 等只读项已正确展示。
4. 启动游戏，检查 logcat：
   ```bash
   adb logcat | grep -E "STS-JRE|Renderer plugin env applied|libmobileglues"
   ```
   应出现 `Renderer plugin env applied: <插件名> · <渲染器名>`。
5. 在游戏内确认渲染正常（主菜单 → 战斗场景），再检查 `dumpsys` 或日志里的 GL 版本字符串。

## 6. 包可见性（必须）

Android 11+ 的包可见性会过滤 `PackageManager` 的查询结果。`<queries>` 里必须声明：

```xml
<queries>
    <intent>
        <action android:name="android.intent.action.MAIN" />
    </intent>
</queries>
```

否则 `queryIntentActivities(ACTION_MAIN)` 只会返回已显式声明包名的那几个应用，插件永远扫描不到，
表现为「设置里渲染后端列表没有插件条目」。日志里会看到 `Renderer plugin scan: 0 renderer(s)`。

## 7. 已知限制

- **ABI 必须匹配**：插件需提供与设备一致的 `lib/<abi>/`（本项目 `arm64-v8a` 为主）。
- **插件更新不自动感知**：`RendererPluginRegistry` 有进程内缓存；插件安装/卸载/升级后需重启应用，
  或在调用处调用 `RendererPluginRegistry.invalidate()`。
- **`extractNativeLibs=false`**：可用性判断会退化为读取 APK 内的 `lib/<abi>/` 条目，
  但实际加载仍依赖系统链接器能按路径打开 APK 内的库。
- **仅读取 `pojavEnv`**：`boatEnv` 面向 Boat 后端，本项目不使用。
- **版本区间不校验**：`minMCVer` / `maxMCVer` 是 Minecraft 语义，对《杀戮尖塔》无意义，仅保留展示。
- **MobileGlues 的配置同步**：内置 MobileGlues 走 `MobileGluesConfigFile.syncFromLauncherPreferences`，
  插件版 MobileGlues 不经过该逻辑，配置需由插件自身或环境变量提供。
