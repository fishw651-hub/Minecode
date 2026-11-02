# MineCode AI

[![build](https://github.com/fishw651-hub/Minecode/actions/workflows/build.yml/badge.svg)](https://github.com/fishw651-hub/Minecode/actions/workflows/build.yml)
[![license: CC BY-NC 4.0](https://img.shields.io/badge/license-CC%20BY--NC%204.0-lightgrey.svg)](LICENSE)

Minecraft Java **26.3** 的 Fabric AI 助手。在游戏内描述建筑、红石机器或指令任务，查看模型输出与公开思考摘要，再逐条确认对世界的修改。

## 功能

- 云端 OpenAI 兼容 Chat Completions 接口，支持 SSE 实时输出。
- 自定义 API 地址、模型和思考强度（none / low / medium / high）。
- 全局 AI 档案、系统提示词与 skill 文本；世界专属记忆、技能和对话历史。
- 主 AI 可派遣最多 **3 个子 Agent**，显示角色、进度和输出，再汇总为最终方案；子 Agent 禁止继续递归派遣。
- AI 可以先提出项目草稿并请求只读注意力窗口；玩家批准总体边界后，AI 会在边界内自主切换焦点，将大型建筑或红石机器拆成阶段，并保存 checkpoint、蓝图和模块接口信息。
- `PLACE_BLOCK`、`FILL` 和受限 `RUN_COMMAND`，服务端校验后逐条确认。
- 方块操作可撤销；记忆和技能草稿需要确认，保留版本以便回滚。

## 安装与使用

需要 Java 25、Minecraft 26.3、Fabric Loader 0.19.5 或更新版本，以及 Fabric API 0.162.0+26.3。

1. 将构建出的 `minecode-1.0.0.jar` 和对应 Fabric API 放入客户端 `mods`。多人服务器也要安装这两个模组。
2. 进入世界后按 **O** 打开控制台，或输入 `/minecode`。
3. 在配置面板填写 API 地址、Key 和模型。地址支持 `https://example.com/v1` 或完整的 `/chat/completions` 地址。
4. 新建或切换 AI 档案，粘贴 skill 文本，然后发送任务，例如：“派遣建筑 Agent 和红石 Agent，在我附近设计一个 5×5 自动门。”
5. 查看聊天、公开摘要与子任务输出。只有最终方案会进入服务端预览；点击各条动作的确认按钮才修改世界。
6. 需要恢复时，对可撤销的操作点击撤销。任意指令不能保证撤销。

大型项目流程是“草稿 → 玩家批准边界 → focus 读取 → 分阶段计划 → 每批预览 → 逐条确认”。带有新项目草稿的首轮会先等待玩家批准，批准后下一轮 AI 才能在项目边界内读取 focus；这样远离玩家的大型项目不会绕过边界确认。focus 只读取已加载区块中的方块状态，最多 4096 个方块，不会自动加载区块，也不读取容器库存或 NBT。AI 可以在批准边界内移动注意力窗口，但每次写入仍受项目范围、方块数量、权限和区块校验约束。项目被替换后，旧预览会因授权 revision 变化而失效。

不同提供商的模型名与参数支持不同。默认模型只是可编辑的初始值；如果服务不接受 `reasoning_effort`，把思考强度设为 **none**，该参数将不再发送。界面展示模型返回的公开摘要，不保证模型提供完整内部推理。

## 存储和权限

客户端设置保存在游戏目录 `config/minecode.json`。API Key 仅保存在客户端，不通过模组网络包发送给服务器。该配置文件含密钥，请勿分享。

世界档案保存在存档目录 `data/minecode/<玩家 UUID>.json`，项目授权、蓝图和阶段 checkpoint 保存在 `data/minecode/projects/<玩家 UUID>.json`。聊天历史和已确认的记忆相互独立；AI 提出的记忆或技能更新，需要玩家确认才生效。档案更新属于提示词与知识的迭代，不会训练或改写模型权重。

修改世界及撤销要求玩家拥有相当于命令等级 2 的权限。默认限制为玩家附近 32 格、单动作 4096 个方块、单计划 8192 个方块和 16 条动作。目标区块必须已加载。首版保护已有方块实体，避免覆盖容器数据。

`RUN_COMMAND` 采用白名单；不支持 `execute`、`function`、管理命令或绕过范围限制的原始 `fill` / `setblock`。建筑和红石操作请使用结构化动作。撤销只恢复记录的目标方块，不能逆转所有红石、流体或生物造成的后续变化。先在测试存档中使用复杂方案。

## 构建

在 Java 25 环境运行：

```powershell
.\gradlew.bat test build
```

Linux/macOS 使用 `./gradlew test build`。Wrapper 使用 Gradle 9.7.1，构建需要能访问 Gradle、Fabric、Minecraft 和 Maven 仓库。产物位于 `build/libs`。

设计与实现边界见 [AI 设计文档](docs/superpowers/specs/2026-10-12-minecode-ai-design.md)。协议示例见 [AI 计划协议](docs/ai-plan-protocol.md)，玩家与服主说明见 [用户指南](docs/minecode-ai-user-guide.md)。

## 许可证

本项目采用 [Creative Commons Attribution-NonCommercial 4.0 International（CC BY-NC 4.0）](LICENSE) 许可。

简单说：

- ✅ **允许**复制、分发、修改、二次创作，也允许基于它做自己的模组或整合包。
- ✅ **允许**在私人、教育、研究等非商业场景使用，包括放在非盈利服务器上。
- ❗ **必须署名**：保留作者信息与许可声明，注明原项目地址 `https://github.com/fishw651-hub/Minecode`，并说明你做过哪些修改。
- ❌ **禁止商业使用**：不得用于以商业利益或金钱报酬为主要目的的场景（例如付费服务器、付费下载、捆绑销售、付费委托开发）。

完整法律文本见 [LICENSE](LICENSE)。若你需要商业授权，请另行联系作者。

> 说明：CC 系列许可主要面向作品而非软件，用于代码在专利与专利授权方面存在一定模糊性。如果你更希望采用为软件设计的非商业条款，可以考虑 PolyForm Noncommercial 1.0.0；但该许可对"必须署名"的要求不如 CC BY 明确。当前选择是按其三条要求（可修改 / 需署名 / 禁商用）做出的最直接匹配。
