# 大模组库刷新优化与测试说明

## 已确认的历史与修复范围

核查 GitHub 的 91 个正式 Release 及其标签源码：包含 AI 补丁功能的 v1.6.1、v1.6.2、
v1.6.3 均直接将补丁打包到 `sts/agent_mods/<父模组ID>/`；更早版本没有该功能。
初始功能提交 `db021a31` 也使用该目录，没有任何正式版采用“混放在普通模组库”的方式。
原来的兼容迁移注释并不代表存在对应的正式历史数据。

因此直接删除该迁移扫描，而不是为它建立缓存：

- 删除普通模组库到 `agent_mods/` 的 AI 补丁迁移实现、主页调用及迁移后重复加载列表的分支。
- 删除上一轮为迁移扫描新增的缓存类、索引读写和专用缓存测试，不保留开关或占位检查。
- AI 补丁列表与启动查询只从 `agent_mods/` 读取，不扫描普通模组库的描述文件，也不会搬动、
  删除或修改其中的 JAR。即使有人手工放入带 `agent-patch.json` 的 JAR，也不自动迁移。
- 保留批量查询 AI 补丁的优化：每轮读取一次启用配置，列举实际存在的补丁目录。
- 保留导入补丁公共状态的复用：每轮读取一次公共设置和显示字符串，各模组的版本状态独立派生。

### 边界

- 删除的只是未正式发布过的 AI 补丁旧存放方式兼容逻辑；普通模组库自身的存储迁移不在范围内。
- 当前 AI 补丁正常的打包、更新、删除、启用及父模组加载规则保留，`agent-patch.json` 仍是其格式。
- 普通模组元数据所需的清单读取、文件属性检查仍保留，不宣称整个刷新流程完全不打开 JAR。
- AI 补丁迁移扫描所带来的全库逐 JAR 检查直接消失，无需等待首次建立缓存，亦无缓存失效问题。
- 本次不调整方案菜单的初始化占位或整个主页刷新的线程模型，不以单元测试代替真机 UI 验收。

## 自动回归测试

在仓库根目录执行：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'io.stamethyst.backend.mods.AgentPatchRefreshTest' \
  --tests 'io.stamethyst.backend.mods.importing.patches.ImportPatchRefreshStateTest'
```

依赖已缓存时可添加 `--offline`。同时检查既有打包/更新/删除行为时，添加：

```text
--tests 'io.stamethyst.backend.mods.AgentPatchModManagerTest'
```

Gradle XML/HTML 报告位于：

- `app/build/test-results/testDebugUnitTest/`
- `app/build/reports/tests/testDebugUnitTest/index.html`

### 147 模组隔离用例

`AgentPatchRefreshTest.patchQueriesAndLaunchIgnore147JarsInOrdinaryLibrary`：

1. 在普通模组库创建 147 个实际 JAR，全部包含合法 AI 补丁描述文件，并将其 ID 加入 AI 补丁启用配置。
2. 在没有 `agent_mods/` 目录时，多次执行单父模组、批量和启动列表查询，必须均为空，且不得创建目录。
3. 在正确的 `agent_mods/parent/` 中新增一个补丁，再重复查询 5 轮，必须只返回该补丁，保留启用状态。
4. 逐文件比较普通库原始内容，确认 147 个文件仍在且字节完全不变，没有被迁移、删除或改写。

该用例验证补丁发现的目录边界；源码检查另外确认主页不再调用任何迁移入口，也不再为此二次加载模组列表。
测试不把主机运行时间或目录隔离断言伪装成“整个主页刷新已测得的 JAR 打开次数”。

### 其他回归

- 批量查询结果与单父模组查询一致，父 ID 去重，排除未请求目录及描述中父 ID 不匹配的补丁。
- 更改启用配置、删除补丁后，下次查询立即反映。
- 导入补丁的已应用版本与过期标记不串用。
- 已记录但当前不可用的补丁仍显示；没有记录的无关通用补丁不出现。

## 真机验收（需独立执行）

1. 备份数据，在同一设备及同一份 147 模组库上比较旧版本与修复版本。
2. 分别记录首次进入、同进程至少 5 次刷新、退出进程后再进入的扫描耗时，比较中位数和范围。
3. 验证模组数量、名称、勾选状态、优先级、AI 补丁状态和导入补丁提示不变。
4. 新增、同路径更新、删除普通模组后刷新，确认列表正确变化。
5. 在 AI 工作区正常打包、启用、更新、删除补丁，确认只在 `agent_mods/` 管理，且父模组规则不变。
6. 新建、切换、删除模组方案后刷新，确认方案数据不丢失。

## 执行记录

2026-10-19，执行上方两个测试类（添加 `--offline`），**5 条全部通过，BUILD SUCCESSFUL**：

- `AgentPatchRefreshTest`：2 条通过，覆盖 147 JAR 的目录隔离、文件及启用配置不变、正常补丁加载与批量查询。
- `ImportPatchRefreshStateTest`：3 条通过，覆盖复用状态的版本隔离与显示规则。
- 应用和测试源码编译通过，`git diff --check` 通过。
- 全仓源码、测试、文档审计确认已删除迁移/缓存符号无残留，缓存实现和专用测试文件已不存在。
- AI 补丁管理器不再访问普通模组库目录、复制普通库文件或调用普通模组删除接口；
  `loadModItems()` 中只剩一次已安装模组列表加载。

本地日志：`agent-tmp/mod-refresh-no-migration-tests-final.log`；
审计记录：`agent-tmp/ai-patch-migration-removal-audit.txt`，均忽略、不提交。
上一轮缓存方案的测试结果不再作为当前实现的验证依据。
未测量真机毫秒耗时，不报告未经测量的加速倍数。

### 已知既有测试问题

上一轮包含 `AgentPatchModManagerTest` 的运行中，
`packagePatchMod_keepsParentDependencyAndDescriptor` 在第 40 行失败：要求创建工作区后已解压
`workspace.sourceRoot/data/config.txt`。当前 `AgentModSourceSnapshot.prepare()` 明确采用
`source_mode=on_demand`、`source_extracted=false`，本次不修改这一无关行为或跳过断言。
