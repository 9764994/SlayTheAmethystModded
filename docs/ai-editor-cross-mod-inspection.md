# AI 编辑器跨模组代码查看

编辑 A 模组时，可以要求 AI 阅读其他已安装模组（包括未启用的模组）的代码，用于理解行为或分析兼容性。

AI 先调用 `list_installed_mods` 获取模组 ID，再向以下工具传入可选的 `mod_id`：

- `list_agent_mod_entries`：列出类或资源；使用 `entry_type="classes"` 发现类。
- `inspect_agent_class`：查看类的公开/受保护 API。
- `decompile_agent_class`：按需反编译一个类，查看实现。
- `read_agent_mod_resource`：读取 JSON、文本等资源。

不传 `mod_id` 时仍查看当前编辑的模组。可使用列表中的 `mod_id` 或唯一的 manifest ID；大小写不敏感。存在同名 manifest ID 时应使用列表中的明确 `mod_id`。

其他模组的反编译代码直接返回，不写入当前工作区的 `source/`，`materialize` 对其他模组始终为 false。跨模组查看不会修改任何已安装 JAR，也不会更换当前补丁的目标或扩展编译类路径。`search_agent_api` 仍只搜索原有编译类路径，其他模组的类应通过文件列表发现。

仅接受已安装模组目录中登记且 JAR 存在的模组，不接受任意文件路径。未知、未安装、文件缺失或歧义 ID 会返回错误，不会回退到当前模组。

游戏本体、BaseMod、StSLib、ModTheSpire 等编译类路径上的类不属于“其他已安装模组”，应使用 `decompile_agent_api_class` 按需读取；该工具不接受 `mod_id`，也不会把源码写入当前工作区。
