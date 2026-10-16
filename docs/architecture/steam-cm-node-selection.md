# Steam CM 节点优选与手动 A/B 测试

## 策略

Android 的 `WorkshopSteamClientIdentity`（包含进程共享 CM 会话）启用
`SteamCmServerSelector`；独立的 Cloud 协议会话也启用相同选择器。
底层协议库的构造参数是可选的，未传入时仍可运行旧策略，供回归/A/B 使用。
探测和正式连接使用同一套 WebSocket transport，不会把 HTTP 加速误当成 CM 可达性。

1. 目录返回的 WebSocket 节点去重，按健康记录排序。
2. 当前 Android network 下，10 分钟内登录成功且未冷却的节点直接优先尝试，不额外测速。
3. 先给排序第一的节点一个 750ms 的独占正式连接窗口；成功则直接使用该连接，
   不测速、不重连。窗口结束仍未连上则保留原连接，同时最多探测 20 个节点，并发度 3；
   原连接和 probe 每次 WSS 握手最多 6 秒。原连接先成功时取消 probes，直接使用它；
   probes 先产出候选时取消原连接，再正式连接优选结果。
   750ms 窗口到期本身不把节点判坏，不中断高延迟但可用的连接。
   总并行 socket 上限为 4（原连接 + 三路 probe），正式登录仍只有一路。
4. 得到第一个成功结果后最多再收集 150ms，不等待黑洞节点。
5. 成功探测节点优先，按握手耗时 EWMA 排序；其他节点保留为 fallback。
6. 正式连接/登录仍然串行：fallback 阶段最多 20 个候选连接，最多发送 3 次 ClientLogon。
   连接失败不消耗登录次数。认证错误/限流立即终止，不为绕过限流切节点。
   只有真正发送过登录的可恢复失败才触发登录退避，DNS/TLS/握手失败不累加登录退避。
7. 选择、实际连接、登录及其重试共用 45 秒总预算；每个正式连接最多 6 秒。
   **预算从取得节点列表后开始，不包含上层目录 HTTP 请求或等待共享会话锁。**
   外层任务取消/退出登录也会中止探测和实际连接。

传输失败、可恢复的登录路由错误使节点冷却 30/60/90/120 秒。
握手成功不清除登录失败记录；只有真正登录成功才清除失败次数。
已经登录的 socket 断线也会降权。普通业务服务错误不会因此把整个节点拉黑。
所有节点冷却时仍允许 fallback，冷却不是永久封禁。

健康记录是有界的进程内 LRU（最多 128 项），按 Android active network 标识隔离，
不会落盘，不存账号、SSID 或凭据；重启或淘汰后重新探测。
这不是基于所有业务 RPC 的完整质量评分，也没有目录缓存/种子回退。
当前 probe 连接会在探测完成/取消时销毁，正式登录重新连接；不尝试跨状态机移交 socket。

## 自动测试：可重复的故障与性能验证

```bash
./gradlew :steam-protocol:test --configure-on-demand
```

`SteamCmServerSelectorTest` 覆盖：

- 前两项黑洞、第三项可用时，无需等待黑洞超时；并发不超过 3。
- 前三项不可达时能发现第四项，不局限于原来的前三项。
- 失败冷却、成功记录过期、网络隔离、有界历史。
- 历史成功节点跳过测速；取消后所有探测连接被清理，探测不发送 Steam 消息。
- 健康首节点仅创建一个正式连接，没有额外探测。
- **可控 A/B**：第一节点握手 2400ms，第三节点 10ms；比较旧串行连接与新选择器，
  断言新策略耗时不到旧策略一半，并输出两者耗时。

`OkHttpSteamCmSessionLogonTest` 还验证优选后的实际登录、整体 deadline 清理，
以及既有的最多三次登录、限流/凭据错误不重试、注销不可恢复旧账号等行为。

## 手动触发：真实网络 A/B

```bash
./gradlew :steam-protocol:cmSelectionBenchmark --configure-on-demand
```

可配置轮数（2–30，默认 6）和输出路径：

```bash
STS_CM_BENCHMARK_ROUNDS=12 \
STS_CM_BENCHMARK_OUT="$PWD/agent-tmp/cm-wifi.csv" \
./gradlew :steam-protocol:cmSelectionBenchmark --configure-on-demand
```

任务每次都会执行，不会因为 Gradle up-to-date 跳过。普通单元测试跳过真实网络测试。
默认生成：

- `agent-tmp/cm-selection-benchmark.csv`：round、mode、success、elapsed_ms、connections。
- `agent-tmp/cm-selection-benchmark.csv.summary.txt`：成功次数与全样本 P50/P95。
  超时/失败耗时也计入分位数，必须连同成功率一起阅读。

两个模式使用同一轮获取的目录列表、同一个 OkHttp client、相同 45 秒外层预算，
交替执行先后顺序，减少 DNS/TLS 缓存和时段变化偏差：

- `serial`：旧策略，按目录顺序串行连接，单节点等待最多 30 秒。
- `selected`：新策略，750ms 首选快路径 + 小并发 WSS 优选，单连接 6 秒，总预算 45 秒。

每个样本创建冷的选择器，测量的是**首次选路开销**，不是历史成功 fast path。
计时包括优选后的正式 WSS 连接，不能仅拿 probe 时间对比旧策略连接时间。
connections 包含探测连接，便于同时看到额外连接成本。

测试不读取任何账号、不发送 ClientLogon、不执行 Cloud/成就写操作；
正式 transport 建立后会发送协议正常的 ClientHello。
因此只能验证**到可用 WSS transport 的时间与成功率**，不能证明账号登录或 Cloud RPC 提升。
两个模式均失败时测试失败；否则报告实际结果，**不强制断言任意网络下都变快**。
目录入口不可达时直接报错，不伪造节点或测速结果。

建议分别在直连 Wi-Fi、移动网络、系统 VPN 下运行并保留输出，不混合样本。
这是 JVM/运行该命令的主机网络测试，使用默认 OkHttp，不加载 Android 内置 HTTP 加速器；
不能用桌面主机结果代替手机、运营商网络或 Android 加速链路实测。
先比较成功率，再比较 P95/P50 和连接开销。第一节点本来就健康时，冷探测可能反而更慢；
这也是需要保留真实 A/B 而不能承诺普遍加速的原因。

## 本次验证记录（2026-10-16，开发主机）

首版“总是先探测再重连”在健康网络上反而变慢：4 轮样本中旧策略 P50 273ms，
探测策略 P50 676ms。因此最终方案加入 750ms 正式连接快路径，而不是无条件探测。

最终方案的 6 轮真实网络 A/B：

| 策略 | 成功 | P50 | P95 | 每样本连接数 |
| --- | --- | --- | --- | --- |
| 原目录顺序串行 | 6/6 | 278ms | 399ms | 1 |
| 快路径 + 按需优选 | 6/6 | 263ms | 279ms | 1 |

原始结果位于 `agent-tmp/cm-selection-final.csv`（不提交）。样本很少，且本轮全部走健康首节点，
只能说明快路径避免了额外探测开销，不能据此宣称普遍降低 P95。

自动化可控慢节点 A/B（使用生产默认 750ms 窗口和 150ms 收集窗口）：
原串行 2403ms，新方案 931ms，耗时降低约 61%。这是注入延迟的模拟场景，
与上述真实网络结果分开解读。协议单元测试 42 项通过，1 项真实网络测试在普通任务中跳过；
真实网络测试通过独立手动任务运行，Android `compileDebugKotlin` 也通过。

上述数字属于首次实现验证，后续玩家网络模拟与修正见
[`steam-cm-network-simulation.md`](steam-cm-network-simulation.md)。
