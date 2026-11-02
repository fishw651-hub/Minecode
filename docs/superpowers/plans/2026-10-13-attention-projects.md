# AI 注意力与大型项目实现计划

> 面向代理开发者：在当前会话并行执行独立模块；每项先写失败测试、验证失败，再实现并验证通过。共同类型由主代理负责，避免同时编辑同一文件。

**目标：** AI 自主读取局部工作区域，在玩家批准的大项目内分阶段建造并记住真实进度。

**架构：** 公共空间协议负责包围盒和请求数据。客户端编排器处理有预算的模型/读取循环，服务端保存授权项目、读取方块并校验执行，控制台连接网络 Future 与预览确认。

**技术栈：** Java 25、Minecraft 26.3、Fabric API、JDK HttpClient、Gson、JUnit 5。

## 全局约束

- 项目每轴最多 512 格，单窗口 4096 方块、48 KiB，模型调用 16 次、读取 16 次。
- 每批 16 动作、8192 方块；每动作 4096 方块；默认范围整个区域在半径 32 内。
- 子 Agent最多三个、深度一；读取不授予写入权限，世界修改逐条确认。
- API Key仅客户端保存，旧档案 JSON schema 不变。
- 新项目替换、取消、维度切换时旧预览和读取失效。

## 任务 1：共同空间协议（主代理）

文件：`src/main/java/mine/coding/ai/spatial/{BlockBox,FocusRequest,ProjectDefinition,StageCheckpoint}.java`；测试 `src/test/java/mine/coding/ai/spatial/SpatialProtocolTest.java`。

接口：每个类型提供 `fromJson(JsonObject)` 与 `toJson()`；BlockBox 提供 `volume()`、`contains(BlockBox)`、`contains(int,int,int)`、`ofCorners(...)`。bounds JSON 为 x/y/z/x2/y2/z2。

- [ ] 写往返、反向坐标、溢出/非法整数与完整包含测试，例如 `assertFalse(BlockBox.ofCorners(0,0,0,15,15,15).contains(BlockBox.ofCorners(0,0,0,16,0,0)))`。
- [ ] 用本地 Java 25 / 缓存 JUnit 编译并记录因新类型不存在的失败。
- [ ] 实现不可变 records，严格字段/类型/长度与安全体积计算。
- [ ] 重跑空间协议测试确认通过。

## 任务 2：服务端项目与观察

文件：`src/main/java/mine/coding/server/ProjectStore.java`、`RegionObservation.java`；修改 ActionPolicy、ServerRequest、MineCodeService；对应服务端测试。

接口：网络操作 `proposeProject {definition:string}`、`approveProject {draftId:string}`、`focus {requestId:string,bounds:string,purpose:string}`、`confirmStage {planId:string}`。响应 `projectDraft {draftId,definition}`、context 的 project、`focus {requestId,snapshot}`、阶段结果。项目存于 data/minecode/projects/玩家 UUID.json。

- [ ] 写旧半径区域最近点通过的回归失败测试及 palette 往返、项目持久化隔离测试。
- [ ] 修正完整区域半径判定；独立保存项目蓝图与执行记录，校验维度与边界。
- [ ] 实现服务端只读观察，保持精确方块属性与 byte budget，不强制加载区块。
- [ ] 在 preview/confirm 中重新校验批准项目和完整目标，处理同批自有改动的基线更新。
- [ ] 阶段确认要求所有动作真正成功，记录拒绝/失败/撤销；重批不携带旧授权。
- [ ] 重跑服务端测试与 compileJava。

## 任务 3：AI 自主注意力循环

文件：修改 AiPlan、AiHttpClient、AiOrchestrator；新增 `FocusReader` 接口；修改协议/编排测试。

接口：AiPlan 保留旧六参/五参构造，新增 projectDraft、attentionRequests、checkpoint；Delegation 增加可选 focus，保留三参。`AiOrchestrator.start(..., FocusReader, Consumer<ChatEvent>)` 与旧 start 共存；`FocusReader.read(FocusRequest)` 返回 `CompletableFuture<String>`。

- [ ] 写 model→focus→model→final 的失败测试，验证 budget、取消和唯一 PLAN。
- [ ] 扩展 JSON 编解码，添加只读请求循环和独立 Agent范围校验。
- [ ] 构造有预算上下文，保留项目蓝图/实际进度，去除重复聊天；给子 Agent传自己的 focus。
- [ ] 重跑所有 HTTP/编排测试，验证旧无 attention 请求的行为不变。

## 任务 4：控制台闭环

文件：新增 `FocusGateway.java`、`ProjectContext.java`，修改 AiConsoleScreen；新增纯逻辑客户端测试。

接口：FocusGateway 实现 FocusReader，负责发送带 requestId 的 focus JSON、接收快照、取消 pending。ProjectContext 投影服务端上下文，保留 project、记忆、skills、位置、limits，排除 chatHistory/auditLog。

- [ ] 写 requestId关联、取消旧请求、上下文不重复历史的失败测试。
- [ ] 接入 orchestrator 与网络，显示窗口、目的与快照读取状态。
- [ ] 显示批准范围、阶段确认、继续下一阶段，保留所有动作确认/撤销。
- [ ] 重跑客户端逻辑测试与 compileClientJava。

## 任务 5：集成与文档（主代理）

- [ ] 审查各模块契约与实际 JSON，对错误响应 requestId/取消/世界切换补充回归。
- [ ] 更新 README 与 docs/ai-plan-protocol.md，说明大型项目操作和已知撤销/红石约束。
- [ ] 使用 Java 25、工作区 GRADLE_USER_HOME 执行 `gradle test build`；若依赖离线缺失，执行完整纯 Java 测试并如实报告。
- [ ] `git diff --check`，确认产物 build/libs/minecode-1.0.0.jar；只在允许写 Git 索引时提交。
