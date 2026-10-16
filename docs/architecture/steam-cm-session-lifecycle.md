# Steam CM 会话与退出登录

## 所有权

- `SharedSteamCmSessions` 是 Android 进程内共享入口；主进程的创意工坊/下载、`:steamcloud` 的云同步、`:game` 的在线状态各自持有一条共享连接。
- `SharedSteamCmSession` 串行合并登录，借用句柄关闭不影响其他调用。显式传入的账号只是声明，必须与当前凭据一致；每次 RPC 也检查账号归属。
- 登录失败后同一账号有 30 秒连接冷却，防止排队任务反复鉴权。重新登录、账号变化或创建新的会话管理器不继承旧冷却。
- `invalidate()` 永久撤销管理器与已有借用句柄。下一次获取入口创建新管理器，不能让旧句柄恢复。
- `OkHttpSteamCmSession.close()` 是终态：清除重连计划、关闭 socket、终止待处理握手/登录/RPC、取消心跳作用域。普通网络故障保留有界重试，但每次重试与发送登录包前必须校验凭据。

## 退出顺序

`SteamAccountLogoutCoordinator` 先原子更新无凭据的 `steam-cm-account-epoch`，再暂停下载队列、撤销本进程连接、清理网页会话、停止在线状态及云同步，最后在云操作锁内切换独立存档并清空凭据/缓存。

这个标记不需要等待云同步持有的操作锁。其他进程通过 `FileObserver` 立即关闭共享 CM，发送和重连时的校验也会拒绝旧 epoch。新创建的管理器只能使用与当前 epoch 一致的凭据，否则匿名连接。标记保留在磁盘上，所以清理中断或进程重启不能复活旧凭据。

登录开始时记录 epoch，提交时核对；退出前开始的登录不能在退出后把凭据写回来。只有本地存档迁移能读取被撤销的账号身份，以免把存档保存到错误账号目录。清理步骤分别执行并汇总错误，不因存档切换或某个缓存清理失败而跳过凭据清理。游戏持有存档租约时，保留账号 ID 并延迟独立存档切换，不删除本地存档。

下载暂停保留分块和历史，退出不会删除已安装模组。用户可以手动恢复，后续新任务仍可匿名下载。撤销/取消不能触发公共 CDN 降级或账号过期提示。

## CM 登录失败提示

带账号的 CM 登录（包括底层自动重连）只有在 Steam 明确返回凭据拒绝时，才上报 `SteamCmLoginFailureNotice`：ClientLogon 的 EResult 5（InvalidPassword）、27（Expired）、106（InvalidSignature）、109（ExpiredLoginAuthCode）。由 `isSteamCmCredentialRejection` 统一分类，不匹配异常文案，也不把泛化 AccessDenied 当成过期证据。DNS/TLS/握手/超时/断线/节点路由不可用、EResult 20、84/87 限流、未知错误、匿名连接失败和主动取消均不触发账号过期提示。失败事件保存在无 token 的小型文件中，使用跨进程锁去重，并按账号、凭据版本及 epoch 校验。

启动器统一显示：**Steam CM 登录失败，Steam 账号登录状态疑似过期，建议重新登录账号。** 提供登录入口；同一凭据版本关闭后不重复弹出，重新登录/退出会清理旧提示。CM 登录失败不走云同步原有的强制退出分支，避免网络故障直接清除账号或覆盖专用提示。

`SteamProtocolException.isCmLogonFailure` 仍只标记失败发生在登录阶段，不表示凭据失效；网络失败也保留该标记，避免云同步回落到旧强制退出逻辑。提示回调的触发条件与这个阶段标记独立。

新提示带 `classificationVersion=1`；没有分类版本的旧持久化提示不再显示，避免升级后仍弹出历史网络故障误报。后续收到明确凭据拒绝时可以覆盖旧记录，正常去重和关闭逻辑不变。

## 验证

- `SharedSteamCmSessionTest`：并发登录合并、旧账号快照、排队/进行中撤销、账号切换、匿名恢复、失败冷却。
- `OkHttpSteamCmSessionLogonTest`：关闭后禁止复活、进行中 RPC/登录撤销、旧凭据禁止重连、明确凭据拒绝才通知；DNS/TLS/reset/超时/路由不可用/限流/未知返回码及重连网络失败均不通知；匿名及取消不通知。
- `UgcWorkshopDownloaderRateLimitTest`：撤销不可降级至公共 CDN。
- `SteamAccountRevocationInstrumentedTest`：隔离目录验证持久化撤销、登录提交竞态、提示去重与清理、忽略旧未分类提示并允许明确拒绝替换；不使用真实账号、不请求 Steam。
