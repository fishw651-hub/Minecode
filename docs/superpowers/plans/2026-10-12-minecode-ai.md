# MineCode AI 建造助手实现计划

> **面向代理开发者：** 按任务顺序执行本计划。每个步骤使用复选框跟踪；涉及代码的步骤先写测试，再写最小实现。

**目标：** 为 Minecraft 1.26.3 Fabric 模组交付一个可运行的 AI 控制台首版，支持云端流式聊天、全局 AI 档案、世界记忆与 skill、结构化建筑动作预览确认和方块操作撤销。

**架构：** 客户端使用 Java 25 `HttpClient` 直连 OpenAI 兼容接口，并在独立 `Screen` 中显示流式聊天与动作计划。客户端只提交结构化计划；服务端通过 Fabric payload 接收请求，重新校验权限、范围和方块数量，确认后执行并保存方块快照与世界记忆。

**技术栈：** Minecraft 1.26.3、Fabric Loader 0.19.5、Fabric API 0.162.0+26.3、Java 25、Mojang 官方 mappings、JDK `HttpClient`、Minecraft 内置 Gson、Brigadier。

## 全局约束

- API Key 仅保存在客户端配置，不能写入世界存档或日志。
- 服务端始终重新验证玩家权限、维度、作用半径和方块数量。
- AI 输出只接受 `message`、`thoughtSummary`、`actions[]` 结构；非法 JSON 或危险动作不得执行。
- `placeBlock` 与 `fill` 保存旧方块状态并支持撤销；`runCommand` 只做审计，默认逐条确认。
- “自我进化”只产生待确认的记忆/skill 草稿，确认后才写入并带版本号。
- 客户端网络调用必须异步，不能阻塞渲染线程。
- 每个玩家同时只能有一个生成任务；切换世界或退出时清理未确认计划。
- 以当前模板的 Mojang mappings 为准；若 26.3 的持久化或 payload 类名与计划不同，以编译器实际 API 调整调用点，不改变模块边界。

---

### 任务 1：动作协议、档案模型与客户端配置

**文件：**

- 创建：`src/main/java/mine/coding/ai/AiAction.java`
- 创建：`src/main/java/mine/coding/ai/AiPlan.java`
- 创建：`src/main/java/mine/coding/ai/AiProfile.java`
- 创建：`src/main/java/mine/coding/ai/SkillDraft.java`
- 创建：`src/main/java/mine/coding/config/MineCodeConfig.java`
- 测试：`src/test/java/mine/coding/ai/AiPlanTest.java`

**接口：**

- `AiAction` 提供 `id()`, `type()`, `description()`, `risk()`, `payload()`，并定义 `PLACE_BLOCK`、`FILL`、`RUN_COMMAND` 三种类型。
- `AiPlan` 提供 `fromJson(String)`, `toJson()`, `message()`, `thoughtSummary()`, `actions()`, `memoryDrafts()`, `skillDrafts()`。
- `MineCodeConfig` 提供 `load()`, `save()`, `endpoint()`, `apiKey()`, `model()`, `reasoningEffort()`，默认端点为 `https://api.openai.com/v1/chat/completions`，默认模型为 `gpt-4o-mini`，默认思考强度为 `medium`。

- [ ] **步骤 1：写失败测试**。覆盖合法三动作 JSON 往返、缺少 `actions`、未知动作类型、缺少坐标和 API Key 脱敏。
- [ ] **步骤 2：运行测试确认失败**。

运行：`./gradlew test --tests mine.coding.ai.AiPlanTest`

预期：因模型类尚未存在而失败。

- [ ] **步骤 3：实现最小模型与 JSON 编解码**。使用 Gson `JsonObject`/`JsonArray`；坐标统一使用整数 `x/y/z`；`AiPlan.fromJson` 遇到非法动作抛出带动作 ID 的 `IllegalArgumentException`。
- [ ] **步骤 4：实现配置文件读写与脱敏**。文件名固定为 `config/minecode.json`；`toRedactedString()` 只显示 API Key 前两位和末两位。
- [ ] **步骤 5：运行测试确认通过**。
- [ ] **步骤 6：提交任务**。

提交：`git add src/main/java/mine/coding/ai src/main/java/mine/coding/config src/test/java/mine/coding/ai && git commit -m "feat: add ai plan and profile models"`

### 任务 2：异步 AI HTTP 客户端与流式事件

**文件：**

- 创建：`src/client/java/mine/coding/client/AiHttpClient.java`
- 创建：`src/client/java/mine/coding/client/AiChatController.java`
- 创建：`src/client/java/mine/coding/client/ChatEvent.java`
- 测试：`src/test/java/mine/coding/client/AiHttpClientTest.java`

**接口：**

- `AiHttpClient.streamChat(AiProfile profile, String userMessage, Consumer<ChatEvent> onEvent, Consumer<Throwable> onError)` 返回 `CompletableFuture<Void>`。
- `ChatEvent` 类型为 `THOUGHT_SUMMARY`、`TEXT_DELTA`、`PLAN_READY`、`ERROR`、`DONE`，每个事件带时间戳和文本。
- `AiChatController.send(String)`、`cancel()`、`retryLast()`、`isBusy()` 管理单玩家单任务状态。

- [ ] **步骤 1：写失败测试**。使用可注入的 `HttpClient`/传输接口，验证请求包含 Bearer Key、模型、思考强度和 JSON 响应格式；验证非 2xx 与断流转成 `ERROR`。
- [ ] **步骤 2：运行测试确认失败**。
- [ ] **步骤 3：实现请求构造**。`HttpRequest` 设置 30 秒连接/响应超时、`Content-Type: application/json`、`Authorization: Bearer <key>`；系统提示要求模型只输出结构化 JSON，并把 thought summary 限制为可解释摘要。
- [ ] **步骤 4：实现响应解析**。对普通 JSON 和 SSE `data:` 行都支持；每条增量事件通过回调发送，收到 `[DONE]` 后解析 `AiPlan` 并发送 `PLAN_READY`。
- [ ] **步骤 5：把回调切回客户端主线程**。使用 `Minecraft.getInstance().execute(...)` 更新屏幕状态，渲染线程不等待 Future。
- [ ] **步骤 6：运行测试确认通过**。
- [ ] **步骤 7：提交任务**。

提交：`git add src/client/java/mine/coding/client src/test/java/mine/coding/client && git commit -m "feat: add async ai chat client"`

### 任务 3：世界档案、记忆版本与动作执行服务

**文件：**

- 创建：`src/main/java/mine/coding/ai/WorldArchiveData.java`
- 创建：`src/main/java/mine/coding/ai/BlockSnapshot.java`
- 创建：`src/main/java/mine/coding/ai/AiExecutionService.java`
- 创建：`src/main/java/mine/coding/ai/ActionValidationResult.java`
- 测试：`src/test/java/mine/coding/ai/AiExecutionServiceTest.java`

**接口：**

- `WorldArchiveData.get(ServerLevel)` 返回 overworld 共享档案；`appendMemory(String)`, `proposeSkill(SkillDraft)`, `confirmDraft(String)`, `rollbackVersion(long)`, `logs()` 修改后标记 dirty。
- `AiExecutionService.preview(ServerPlayer, AiPlan)` 返回每条动作的 `ActionValidationResult`。
- `AiExecutionService.confirm(ServerPlayer, String actionId)` 执行已通过预览的动作并返回执行结果。
- `AiExecutionService.undo(ServerPlayer, String operationId)` 恢复该操作保存的 `BlockSnapshot`。

- [ ] **步骤 1：写失败测试**。覆盖半径 32 以内通过、超半径拒绝、fill 体积超过 4096 拒绝、无权限拒绝、方块快照撤销恢复原状态、记忆版本回滚。
- [ ] **步骤 2：运行测试确认失败**。
- [ ] **步骤 3：实现世界数据载入/保存**。使用 26.3 实际可用的 `SavedData`/`DimensionDataStorage` 或等价 `PersistentState` API，将档案固定存储在 overworld；NBT 保存 profile summary、memory、skill versions、operation logs。
- [ ] **步骤 4：实现动作校验**。检查玩家在线、同一维度、距离、方块数量、方块 ID 可解析性和命令黑名单；每个计划只缓存当前玩家的待确认动作。
- [ ] **步骤 5：实现执行与快照**。`placeBlock` 保存单方块旧状态；`fill` 先枚举范围并保存旧状态，再逐块设置；`runCommand` 仅允许白名单前缀并记录原文，不能推导撤销时返回不可撤销说明。
- [ ] **步骤 6：实现记忆/skill 确认与回滚**。版本号单调递增，记录时间、来源和摘要；回滚恢复上一版本内容并写一条审计日志。
- [ ] **步骤 7：运行测试确认通过**。
- [ ] **步骤 8：提交任务**。

提交：`git add src/main/java/mine/coding/ai src/test/java/mine/coding/ai && git commit -m "feat: add world archive and action execution"`

### 任务 4：Fabric 网络 payload 与 `/minecode` 命令

**文件：**

- 创建：`src/main/java/mine/coding/network/MineCodeNetworking.java`
- 创建：`src/main/java/mine/coding/network/PlanPayload.java`
- 创建：`src/main/java/mine/coding/network/ActionResultPayload.java`
- 创建：`src/main/java/mine/coding/command/MineCodeCommand.java`
- 修改：`src/main/java/mine/coding/MineCode.java`

**接口：**

- `MineCodeNetworking.registerServer()`、`registerClient()` 注册 plan、confirm、undo、memory sync payload。
- `MineCodeCommand.register()` 注册 `/minecode`、`/minecode status`、`/minecode clear-memory`、`/minecode logs`。

- [ ] **步骤 1：实现最小 payload 类型与 codec**。优先使用 26.3 的 `PayloadTypeRegistry`/`CustomPacketPayload`/`StreamCodec`；若编译显示版本使用 `PacketCodec`，只替换 codec 声明，不改变 payload 字段。
- [ ] **步骤 2：实现服务端接收器**。所有接收器通过 `context.server().execute(...)` 切回主线程，再调用 `AiExecutionService`；拒绝未知 action ID、过期计划和非本人请求。
- [ ] **步骤 3：实现客户端结果接收器**。通过 `Minecraft.getInstance().execute(...)` 把预览、执行、撤销和错误事件交给 `AiConsoleScreen`。
- [ ] **步骤 4：注册命令**。`/minecode` 发送打开面板提示；`status` 返回档案/任务状态；`clear-memory` 和 `logs` 要求权限等级 2。
- [ ] **步骤 5：在 `MineCode.onInitialize()` 注册服务端入口**。
- [ ] **步骤 6：运行 `./gradlew compileJava` 校对 26.3 API 签名并修正编译错误**。
- [ ] **步骤 7：提交任务**。

提交：`git add src/main/java/mine/coding/network src/main/java/mine/coding/command src/main/java/mine/coding/MineCode.java && git commit -m "feat: add server networking and commands"`

### 任务 5：游戏内 AI 控制台界面

**文件：**

- 修改：`src/client/java/mine/coding/client/MineCodeClient.java`
- 创建：`src/client/java/mine/coding/client/screen/AiConsoleScreen.java`
- 创建：`src/client/java/mine/coding/client/render/ChatTimeline.java`
- 创建：`src/client/java/mine/coding/client/screen/components/ActionCard.java`
- 创建：`src/client/java/mine/coding/client/screen/components/ProfilePanel.java`

**接口：**

- `AiConsoleScreen` 管理输入、时间线、档案字段、模型、思考强度、skill 文本和待确认动作。
- `ChatTimeline.append(ChatEvent)`、`clear()`、`scroll(int)`。
- `ActionCard` 提供确认、拒绝、撤销按钮回调。

- [ ] **步骤 1：实现按键绑定**。在 `MineCodeClient.onInitializeClient()` 使用 Fabric key binding 注册默认 `O` 键；客户端 tick 中按下时打开 `AiConsoleScreen`。
- [ ] **步骤 2：实现两栏布局**。左侧 60% 宽度显示聊天/思考时间线和输入框；右侧显示档案名称、模型下拉、思考强度按钮、skill 多行编辑框和待执行动作列表。
- [ ] **步骤 3：实现流式更新**。将 `ChatEvent` 映射为不同颜色的消息；`PLAN_READY` 创建动作卡片；错误保留已收到内容并显示“重试本轮”。
- [ ] **步骤 4：实现动作交互**。确认按钮发送 confirm payload，拒绝按钮从本地列表移除，撤销按钮发送 undo payload；按钮文本显示风险和预计方块数。
- [ ] **步骤 5：实现档案和 skill 保存**。模型、思考强度和 skill 草稿写入客户端配置；世界记忆确认由服务端返回版本结果后刷新右侧状态。
- [ ] **步骤 6：注册客户端网络接收器与屏幕打开逻辑**。
- [ ] **步骤 7：运行 `./gradlew compileClientJava` 或 `./gradlew build`，修正 Screen/Button/EditBox 的 26.3 签名**。
- [ ] **步骤 8：提交任务**。

提交：`git add src/client/java/mine/coding/client && git commit -m "feat: add in-game ai console"`

### 任务 6：集成验证、文档与发布产物

**文件：**

- 修改：`README.md`
- 修改：`src/main/resources/fabric.mod.json`
- 创建：`docs/minecode-ai-user-guide.md`
- 创建：`src/test/java/mine/coding/IntegrationSmokeTest.java`

- [ ] **步骤 1：补充模组元数据**。把描述改为 AI 建造助手，作者改为 MineCode Contributors，保留当前 license，并确认客户端/服务端 entrypoint 与依赖版本一致。
- [ ] **步骤 2：写集成冒烟测试**。验证 `AiPlan` 解析一个 placeBlock/fill/runCommand 计划、校验服务拒绝超范围动作、确认并撤销方块快照。
- [ ] **步骤 3：更新 README**。写明安装、首次配置 API 地址/Key、打开面板、输入 skill、确认动作、撤销操作和多人服权限。
- [ ] **步骤 4：运行完整验证**。

运行：`./gradlew test build`

预期：测试通过并在 `build/libs/` 生成 remapped 模组 jar；若依赖下载失败，保留错误日志并报告网络阻塞，不宣称构建成功。

- [ ] **步骤 5：检查敏感信息**。运行 `rg -n "sk-[A-Za-z0-9]|api[_-]?key\\s*[:=]" src README.md docs`，确认没有真实密钥。
- [ ] **步骤 6：运行 `git diff --check`，检查未提交的格式错误**。
- [ ] **步骤 7：提交最终任务**。

提交：`git add README.md docs src/main/resources/fabric.mod.json src/test && git commit -m "docs: document minecode ai assistant"`

## 计划自检

- 设计中的客户端直连、两层档案、三种动作、逐条确认、方块撤销、skill/记忆确认、流式聊天和错误处理均有对应任务。
- 所有后续任务依赖的类名和方法签名已在前置任务中定义。
- 没有使用 TBD、TODO 或“稍后补充”等占位描述。
- 26.3 映射差异只集中在持久化和 payload codec 两个适配点，且计划包含编译校对步骤。
