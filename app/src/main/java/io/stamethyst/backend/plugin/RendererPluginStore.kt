package io.stamethyst.backend.plugin

import android.content.Context
import android.content.SharedPreferences

/**
 * 插件渲染器的本地状态：选中的渲染器、被禁用的插件、每个插件的环境变量配置。
 *
 * 单独使用一个 SharedPreferences 文件，避免与启动器主配置（LauncherConfig）的键空间混在一起。
 */
object RendererPluginStore {
    private const val PREF_NAME = "renderer_plugins"

    private const val KEY_SELECTED = "selected_plugin"
    private const val KEY_DISABLED = "disabled_plugins"

    private const val ENV_PREFIX = "env."
    private const val ENV_ENABLED_SUFFIX = "@enabled"

    fun selectedPluginId(context: Context): String? {
        return prefs(context).getString(KEY_SELECTED, null)?.takeIf { it.isNotBlank() }
    }

    fun setSelectedPluginId(context: Context, pluginId: String?) {
        prefs(context).edit().apply {
            if (pluginId.isNullOrBlank()) remove(KEY_SELECTED) else putString(KEY_SELECTED, pluginId)
        }.apply()
    }

    fun disabledPackages(context: Context): Set<String> {
        val stored = prefs(context).getStringSet(KEY_DISABLED, emptySet()) ?: emptySet()
        return LinkedHashSet(stored)
    }

    fun setPackageEnabled(context: Context, packageName: String, enabled: Boolean) {
        val disabled = disabledPackages(context).toMutableSet()
        if (enabled) disabled.remove(packageName) else disabled.add(packageName)
        prefs(context).edit().putStringSet(KEY_DISABLED, disabled).apply()
    }

    fun isPackageEnabled(context: Context, packageName: String): Boolean {
        return packageName !in disabledPackages(context)
    }

    fun envValue(context: Context, pluginId: String, key: String): String? {
        return prefs(context).getString(envValueKey(pluginId, key), null)
    }

    fun envEnabled(context: Context, pluginId: String, key: String): Boolean? {
        val prefs = prefs(context)
        val enabledKey = envEnabledKey(pluginId, key)
        if (!prefs.contains(enabledKey)) return null
        return prefs.getBoolean(enabledKey, true)
    }

    /**
     * 写入某个插件的环境变量配置。
     *
     * @param values key → 用户选择/输入的值，null 表示清除该键覆盖
     * @param enabledValues key → 开关状态，用于 SELECTABLE / TOGGLEABLE
     */
    fun saveEnvConfig(
        context: Context,
        pluginId: String,
        values: Map<String, String?>,
        enabledValues: Map<String, Boolean>
    ) {
        prefs(context).edit().apply {
            values.forEach { (key, value) ->
                if (value == null) remove(envValueKey(pluginId, key))
                else putString(envValueKey(pluginId, key), value)
            }
            enabledValues.forEach { (key, enabled) ->
                putBoolean(envEnabledKey(pluginId, key), enabled)
            }
        }.apply()
    }

    /** 清除某个插件（或全部插件）的环境变量覆盖 */
    fun clearEnvConfig(context: Context, pluginId: String? = null) {
        val prefs = prefs(context)
        val editor = prefs.edit()
        val prefix = if (pluginId == null) ENV_PREFIX else "$ENV_PREFIX$pluginId."
        prefs.all.keys
            .filter { it.startsWith(prefix) }
            .forEach { key -> editor.remove(key) }
        editor.apply()
    }

    private fun envValueKey(pluginId: String, key: String): String = "$ENV_PREFIX$pluginId.$key"

    private fun envEnabledKey(pluginId: String, key: String): String =
        "$ENV_PREFIX$pluginId.$key$ENV_ENABLED_SUFFIX"

    private fun prefs(context: Context): SharedPreferences {
        return context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }
}
