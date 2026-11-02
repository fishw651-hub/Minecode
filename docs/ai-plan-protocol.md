# AI 计划协议

模型返回一个 JSON 对象。`actions` 必须存在，纯聊天时可以是空数组。类型名称使用大写；坐标必须是绝对整数，不能用 `~`。服务端会重新检查每条操作。

```json
{
  "message": "建议在玩家附近放置一组石块作为机器底座。",
  "thoughtSummary": "先制作底座，再审查红石布局。",
  "actions": [
    {
      "id": "base",
      "type": "FILL",
      "description": "制作 3×3 石质底座",
      "risk": "medium",
      "payload": {
        "x": 10, "y": 64, "z": 10,
        "x2": 12, "y2": 64, "z2": 12,
        "block": "minecraft:stone"
      }
    },
    {
      "id": "repeater",
      "type": "PLACE_BLOCK",
      "description": "放置朝北的中继器",
      "risk": "low",
      "payload": {
        "x": 11, "y": 65, "z": 11,
        "block": "minecraft:repeater[facing=north,delay=2]"
      }
    }
  ],
  "memoryDrafts": ["玩家偏好紧凑的红石布局。"],
  "skillDrafts": [
    {
      "id": "compact-redstone",
      "name": "紧凑红石设计",
      "description": "把经过验证的布局经验保存为技能。",
      "instructions": "先固定输入与输出，再绘制逐层布局；不能假定电路已通过游戏内验证。"
    }
  ],
  "delegations": []
}
```

对于大型建筑或红石机器，模型可以先返回项目草稿和只读注意力请求：

```json
{
  "message": "先勘察地基，再按模块建造机器。",
  "thoughtSummary": "把项目拆成地基、时序模块和输出模块。",
  "actions": [],
  "memoryDrafts": [],
  "skillDrafts": [],
  "delegations": [],
  "projectDraft": {
    "id": "auto-door",
    "name": "自动门",
    "goal": "建造并分阶段验证三宽自动门",
    "blueprint": "底座、输入模块、时序模块、输出模块",
    "dimension": "minecraft:overworld",
    "bounds": {"x": 10, "y": 63, "z": 10, "x2": 24, "y2": 72, "z2": 30}
  },
  "attentionRequests": [
    {"id": "foundation", "purpose": "检查地基和附近障碍", "bounds": {"x": 10, "y": 63, "z": 10, "x2": 18, "y2": 68, "z2": 18}}
  ],
  "checkpoint": {"stage": "foundation", "summary": "等待玩家批准项目范围", "nextTask": "读取地基焦点", "complete": false}
}
```

客户端会把 `attentionRequests` 转换为带 `requestId` 的 `focus` 请求。服务器返回 palette 与 RLE 快照；模型收到快照后继续规划。单个 focus 最多 4096 个方块，项目每轴最多 512 格；未加载区块直接返回错误。带 `projectDraft` 的首轮会先展示草稿并等待玩家批准，批准后再执行远离玩家的 focus；`projectDraft` 必须由玩家点击批准后才会成为写入授权，`checkpoint` 只是待确认的阶段记录。

包含 checkpoint 的预览会在动作列表下显示“确认阶段记录”。服务器只有在该计划的每条动作都已成功确认执行、项目仍使用同一授权 revision 且存在已批准项目时，才会写入 checkpoint；被拒绝、失败或越界的动作不能把阶段标记为完成。写入 checkpoint 会递增项目 revision，使旧预览需要重新生成。

主 Agent 可以返回 `delegations`：

```json
{
  "message": "分派设计与审查任务。",
  "thoughtSummary": "将建筑与电路分别设计，再合并。",
  "actions": [],
  "memoryDrafts": [],
  "skillDrafts": [],
  "delegations": [
    {"id": "build", "role": "建筑 Agent", "task": "设计建筑底座，返回结构化方块动作。"},
    {"id": "redstone", "role": "红石 Agent", "task": "设计门电路，并解释输入输出位置。"},
    {"id": "review", "role": "审查 Agent", "task": "检查尺寸、方向与安全范围。"}
  ]
}
```

子 Agent 只返回方案，不能再分派。主 Agent 的首次方案与子 Agent 的完整结果都交给汇总请求；最终方案才进入服务端预览。最多三个子请求、一个主请求和一个汇总请求。所有模型请求使用当前档案选择的模型，单轮调用量可能高于普通聊天。
