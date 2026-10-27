# AI Agent 图片输入

## 上游基线

图片读取与消息转换参照 OpenCode V2 的 `v2` 分支：
`4617210822bdbb31e5749751afbfb4584ccedac8`。

- `packages/core/src/tool/read.ts`：图片作为附件返回，不把 base64 塞进文本结果。
- `packages/core/src/session/message-v2.ts`：工具结果中的媒体在下一次请求前转换成模型可见内容。
- `packages/core/src/session/transform.ts`：按模型能力筛选输入模态。

许可证及版权声明见根目录 `NOTICE`。对照源码保存在本地 `agent-tmp/`，不提交。

## 行为

- `read_agent_workspace_file` 默认读取工作区里的 PNG、JPEG、WebP、GIF 时返回图片附件，
  文本结果只保留路径、MIME、尺寸和“图片已附加”说明。显式传 `encoding=base64` 仍按旧
  行为返回可复制的 base64 文本。`read_agent_mod_resource` 读取整份图片资源时相同。
- OpenAI 兼容协议只允许工具结果为文本，因此工具图片先记录在工具结果上，等整批工具
  全部完成后合并成一条合成用户消息，正文以 `Attached image(s) from tool result:` 开头。
  这样不会在工具调用之间插入用户消息，OpenAI 与 Anthropic 的工具顺序都保持合法。
- 用户在输入框附加的图片直接进入对应用户消息，作为 `ImageContent` 与文字一起发送；
  文本附件仍拼接在用户正文之后。旧会话中的文本附件没有 `mime`/`data` 字段时按
  `text/plain` 处理。
- 支持的格式以魔数优先识别，识别失败再回退到扩展名；SVG 明确排除。单张输入上限
  24 MiB；最长边超过 1,568 像素或文件超过 3.5 MB 时先缩小并以 WebP（质量 88）重新
  编码，仍超限则拒绝读取，避免把整张截图原样塞进请求。
- 图片不计入文本 token：每张图按 1,600 tokens 估算，base64 不参与字符估算。压缩摘要
  不使用图片，只保留 `[Attached image/png: name]` 之类的文字说明；原始消息和归档不变，
  后续请求仍可看到原图。
- 聊天记录数据库 schema 升到 v5，附件表新增 `mime` 与 `data` 两列；旧行使用默认值，
  不需要重建数据库。聊天列表用缩略图显示图片附件，文本附件保持文件图标。

## 设置

设置 → LLM → 请求选项中新增“图片输入”开关，默认开启，持久化为
`supports_image_input`。关闭后，用户附件和工具读取到的图片都会替换成
“This model cannot view images” 的文字提示，模型可以继续回答并请用户描述图片，而不会
因为纯文本端点拒绝 `image_url` 内容而整轮失败。

## 启动器适配边界

- 启动器没有 OpenCode 的独立 file part 通道，工具图片通过“工具批次之后的一条合成
  用户消息”进入下一轮请求；这是协议适配，不改变持久化的工具结果。
- 暂不支持 PDF、音频、视频输入，也暂不支持动图；需要缩放时 GIF 会变成静态 WebP。
- 聊天输入框读取图片时只接受 PNG/JPEG/WebP/GIF；选择器虽然使用 `image/*`，其他图片
  格式会在读取阶段被忽略。
- 图片只在下一次模型请求时发送；如果当前轮工具图片过多，仍受上下文预算和工具结果
  上限约束。

## 测试

- `AgentVisionContextTest`：合成用户消息顺序、用户图片、关闭图片输入、token 估算、
  序列化与旧附件兼容。
- `AgentImagesTest`：MIME 识别、扩展名回退、小图透传与非图片拒绝。
- `AgentWorkspaceImageReadTest`：工作区图片读取、显式 base64 与文本文件不受影响。
- `PolicyGatedAgentGatewayTest.respond_sendsAnImageReadByAToolAsAUserImagePart`：端到端
  验证第二次请求包含 `image_url` 内容。
