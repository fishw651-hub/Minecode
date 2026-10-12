# MineCode AI 建造助手设计

## 目标

为 Minecraft 1.26.3 Java Fabric 模组提供一个游戏内 AI 控制台：玩家可以选择模型和思考强度，实时看到 AI 的聊天内容与思考摘要，维护 AI 档案和 skill，并让 AI 生成可审阅、可确认、可追踪的指令、建筑和红石机器操作。

## 已确认的产品边界

- AI 采用云端直连：客户端连接玩家配置的 OpenAI 兼容 API。
- 档案采用两层保存：全局基础档案 + 世界专属记忆。
- 世界修改采用逐条预览并确认，支持方块类操作撤销。
- 入口是游戏内独立面板：左侧聊天/思考流，右侧档案、模型、skill 和待执行命令。
- “自我进化”只允许 AI 提出记忆更新和 skill 草稿，玩家确认后才写入，并保留版本以便回滚。
- 主 AI 支持派遣子 Agent：可将建筑、红石、指令与审查任务分给最多三个子 Agent，界面显示各任务的角色、状态和输出。子 Agent 不能继续递归派遣；主 AI 汇总结果后才产生供玩家确认的最终计划。
- 界面展示可解释的思考摘要和计划步骤，不保存或展示隐藏的内部推理 token。

## 架构与数据流

客户端打开独立面板，使用 Java `HttpClient` 连接云端 OpenAI 兼容接口。请求包含当前 AI 档案、世界记忆、启用的 skill 和有限的世界上下文。模型以结构化 JSON 返回 `message`、`thoughtSummary`、`actions[]` 和可选 `delegations[]`。存在子任务时，客户端并发请求子 Agent，然后由主 AI 进行一次汇总；单轮最多五次模型请求。客户端把流式文本、子任务状态和最终动作计划显示到时间线，并通过 Fabric 网络包发送给服务端。

服务端重新校验玩家权限、所在世界、作用半径和方块数量，然后生成逐条预览。玩家确认后，服务端执行动作并写入审计记录。`placeBlock` 与 `fill` 执行前保存旧方块状态，支持撤销；无法安全推导逆操作的 `runCommand` 仅保留日志。

## 结构化动作协议

`actions[]` 只接受以下三类动作：

- `placeBlock`: 坐标、方块 ID、可选方块状态。
- `fill`: 两个角点、方块 ID、可选替换模式。
- `runCommand`: 一条原版命令文本，默认必须逐条确认。

所有动作都必须包含唯一 ID、说明、预计影响范围和风险级别。服务端拒绝缺字段、坐标超范围、超过方块数量上限或危险管理命令。

## 代码组件

### 公共/服务端

- `mine.coding.ai.AiProfile`：全局基础档案、模型、思考强度、系统提示词和 skill 元数据。
- `mine.coding.ai.AiMemoryStore`：世界专属记忆、任务进度和已确认的 skill 版本。
- `mine.coding.ai.AiAction` / `AiPlan`：动作协议与 JSON 编解码。
- `mine.coding.ai.AiExecutionService`：权限检查、预览、执行日志、方块快照和撤销。
- `mine.coding.network.MineCodeNetworking`：客户端/服务端数据包与流式事件。
- `mine.coding.command.MineCodeCommand`：`/minecode` 打开面板、查看状态、清理记忆和导出日志。
- `mine.coding.config.MineCodeConfig`：本地端点、Key、模型列表和 UI 偏好，统一脱敏日志。

### 客户端

- `mine.coding.client.MineCodeClient`：按键、网络接收器和屏幕注册。
- `mine.coding.client.screen.AiConsoleScreen`：主控制台。
- `mine.coding.client.screen.components.*`：消息列表、模型选择、思考强度、skill 编辑器和动作卡片。
- `mine.coding.client.AiChatController`：发送、流式结果拼接、错误态和重试。
- `mine.coding.client.render.ChatTimeline`：按时间线渲染消息、思考摘要、动作预览和执行结果。

## 持久化

- 全局配置与基础档案写入客户端配置目录；API Key 不写入世界存档。
- 世界记忆、skill 确认版本、会话摘要和动作日志写入服务端世界数据。
- 每次记忆或 skill 更新带版本号、时间、来源和变更摘要，支持回滚。

## 错误处理与权限

- 连接失败、超时、非 2xx、SSE 断流显示在时间线，保留已收到内容并提供重试。
- 非法 JSON 或动作字段错误只标记为待修正，绝不执行。
- 默认以玩家当前位置为中心限制半径和方块数量；服务端权限检查始终优先于客户端请求。
- 同一玩家同一时间只允许一个生成任务；切换世界或退出时取消请求并清理未确认计划。

## 首版交付与验证

1. 配置 OpenAI 兼容端点、模型和思考强度，完成流式聊天。
2. 创建、切换、编辑 AI 档案。
3. 粘贴 skill，启用/停用，并在确认后写入世界记忆。
4. 生成三类动作，逐条预览、确认、执行和撤销方块操作。
5. 保存跨会话摘要和世界记忆。
6. 测试动作 JSON 往返编解码、边界校验、快照撤销、版本回滚和网络错误状态；运行 `gradlew build`。
7. 支持受限的主/子 Agent 任务分派、并行生成与汇总，验证取消、失败和禁止递归派遣；所有世界修改沿用逐条确认机制。

## 技术约束

- Minecraft 1.26.3、Fabric Loader 0.19.5、Fabric API 0.162.0+26.3、Java 25。
- 采用模板当前的 Mojang 官方 mappings；Fabric 26.3 小版本签名以编译结果为准校对。
- 不引入外部服务依赖；客户端使用 JDK HTTP 客户端，服务端使用 Fabric 网络与世界数据 API。
