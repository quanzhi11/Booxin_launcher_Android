# AI 队员：PC → 手机端迁移技术文档

**状态：** 仅文档，未开工实现  
**日期：** 2026-08-17  
**手机仓库：** `booxin launcher phone`（当前 AI 页「AI 队员」= 占位）  
**PC 仓库：** `D:\all code in D\booxin-launcher2.3.0`（已上线运营；**Player2 行为层今天起重构中，只完成一半**）

**PC 侧当前执行真相（行为层）：**

- `booxin-launcher2.3.0/Docs/AI-Player2复刻交接说明书.md`
- `booxin-launcher2.3.0/Docs/AI-Player2对齐方案.md`

手机迁移必须以「**大脑下 TASK → bot TaskRunner 跑完**」为目标架构对齐；不要把 PC 旧的 dig/INPUT 散装路径原样搬过来。

---

## 0. 一句话结论

| 层 | 能否复用 | 说明 |
|----|----------|------|
| `Tools/AiPlayerBot`（mineflayer 侧车） | **尽量原样共享** | 协议与任务机是跨端资产；PC 重构完成后同步拷贝 |
| `BooxinAiApi` 额度 / 订阅 | **已部分打通** | 手机已有 `playerLimitFen`、订阅档位、聊天代理 |
| 宿主编排（C# `Services/AIPlayer/*`） | **需 Kotlin 重写** | 能力对齐，不是翻译粘贴 |
| PC 专属：屏幕键鼠 / 桌面截屏 / Viewer 窗 | **首期不做** | Android 上没有等价桌面控制面 |

手机端缺口不在「有没有 AI 聊天」，而在：**本机能否拉起 Node 侧车 + 能否拿到可加入的局域网端口 + 能否用同一套 stdin/stdout 任务协议指挥假人**。

---

## 1. 手机端现状（已确认）

### 1.1 UI 占位

- 布局：`app/src/main/res/layout/fragment_ai_assistant.xml` → `cardTeammate`
- 文案：`ai_teammate_title` / `ai_teammate_desc` / `ai_teammate_cta`（「尽情期待」）
- 点击：`AiAssistantFragment.showTeammateComing()` → Toast `ai_feature_coming`

AI **助手聊天**已可用（`AiChatActivity` + `AiChatService` + `AiBackendClient`）；**AI 队员**无业务代码。

### 1.2 已可复用的基础设施

| 模块 | 路径 | 与队员关系 |
|------|------|------------|
| AI 后端客户端 | `core/ai/AiBackendClient.kt` | 同一 `BooxinAiApi`；队员会话另开 system prompt / 额度池 |
| 额度快照 | `AiModels.kt` 含 `playerLimitFen` | PC 队员日额度字段已解析，UI 未消费 |
| 联机登录门禁 | `multiplayerAuth` | 队员入口应与助手一样要求 Booxin 登录 |
| 游戏启动 | `GameRuntime` / `LaunchActivity` | 队员依赖「已在跑的世界」；需补局域网开房/端口发现 |

### 1.3 明确没有的能力

- 无 `AiPlayer*` 包 / 无 mineflayer 资产
- 无 Node 运行时供给
- 启动链路中未见「对局域网开放 / 解析 LAN 端口」的完整队员编排（需在实现阶段再扫 `LaunchActivity` / bridge 日志）

---

## 2. PC 端现状（迁移源）

### 2.1 产品形态（已运营）

玩家开局 → 对局域网开放 → 启动器拉起 Node `bot.js` 以离线号加入 → 聊天/语音指挥 → 假人执行跟随/采集/合成/战斗等。

依赖约束（PC 文档原话）：

- 本机 Node 机器人桥接
- 已启动的 Minecraft **局域网**世界
- `online-mode=false`（离线假人）

### 2.2 分层（目标架构，含今天重构方向）

```text
玩家自然语言 / 语音
        │
        ▼
┌──────────────────────────────────────┐
│ 宿主（PC: C# / 手机: Kotlin）         │
│  Dialogue：LLM → SAY + TASK（高级）   │
│  意图短路：木头/镐等 → 直发 get       │
│  Orchestrator：打断、验收、禁插队     │
│  Quota：队员日额度扣减                │
└──────────────────┬───────────────────┘
                   │ stdin JSON lines
                   ▼
┌──────────────────────────────────────┐
│ Tools/AiPlayerBot/bot.js（共享）      │
│  TaskRunner：get/collect/craft…      │
│  taskCatalogue + craftIntent         │
│  原语：pathfinder / dig / smelt…     │
│  stdout：ready/chat/event/state…     │
└──────────────────────────────────────┘
                   │
                   ▼
          局域网 Minecraft（1.21.x 优先）
```

**禁止**把「弄 10 木头」拆成十几行 dig 交给模型死磕——这是 PC 旧架构债，手机不要继承。

### 2.3 PC 关键代码地图

| 角色 | PC 路径 | 手机应对 |
|------|---------|----------|
| 总服务 / 会话状态机 | `Services/AIPlayer/AIPlayerService.cs` | 新建 `core/aiplayer/AiPlayerService.kt` |
| 阶段枚举 | `AIPlayerModels.cs`（Idle→WaitingGame→…→InGame） | 同语义 Kotlin 模型 |
| 进程 + stdin/out | `MinecraftLanChatBot.cs` | `AiPlayerBotProcess.kt`（Android 进程模型另议） |
| Node 供给 | `NodeRuntimeProvisioner.cs` | Android 专属供给方案（见 §4） |
| 动作分发 | `AIPlayerActionDispatcher.cs` | 移植协议，精简 PC 独有 INPUT/LOOK |
| Prompt | `AIPlayerPromptComposer.cs` | 共享文案/规则，Kotlin 组装 |
| 意图短路 | `AIPlayerCraftIntent.cs` | 可直接逻辑移植 |
| 总控 | `AIPlayerOrchestrator.cs` | 移植；与 Task 层对齐 |
| 任务列表 UI 模型 | `AIPlayerTaskTracker.cs` | 手机队员页任务条 |
| 侧车本体 | `Tools/AiPlayerBot/*` | **子模块 / 拷贝同源** |
| 皮肤 | `AIPlayerSkinStore.cs` | 后期；可先固定默认皮 |
| 语音球 | `AIPlayerVoiceChatService` + Orb 窗 | 二期；手机可用系统 STT/TTS |
| 视觉 | `AIPlayerVisionGateway` | 二期；截 Surface 成本高 |
| 桌面键鼠 / 屏操作 | `MinecraftScreenPilot` / InputTelemetry | **不做** |

### 2.4 宿主 ↔ bot 协议（迁移契约，冻结面）

下发（节选，以 PC `MinecraftLanChatBot.Send*` / 对齐方案为准）：

```json
{"type":"task","id":"t1","name":"get","item":"oak_log","count":10}
{"type":"task_cancel","id":"t1"}
{"type":"stop"}
{"type":"follow"}
{"type":"come"}
{"type":"quit"}
```

回传：`ready` / `chat` / `event`（`task_started|progress|done|fail`）/ `state` / `error` / `exit`

手机宿主必须只依赖这套 JSON lines；实现细节跟 PC `bot.js` 同步，不自创第二套协议。

### 2.5 PC 重构半成品对迁移的约束

截至交接说明书勾选：

- 已落地：Prompt/JSON/`TASK get`、意图直发、tick 式 Resource、木石铁镐目录、Command feedback
- 未完成：实机 1.21.x 六条验收

因此手机文档约定：

1. **协议与 `taskCatalogue` 以 PC 重构后的树为唯一真相**；PC 合并一版侧车，手机再锁版本。
2. 手机一期验收口令与 PC 相同：「弄 10 木头」「做木镐」「停」——避免两端行为分叉。
3. 在 PC 实机验收未过之前，手机可先做「壳 + 入局 + 跟/停」，高级 get 任务与 PC 同步提测。

---

## 3. 目标产品（手机）

### 3.1 用户路径（一期 MVP）

1. Booxin 登录 + 有队员日额度（或免费共用池，与 PC 计费一致）
2. 启动某版本进入单人世界
3. **对局域网开放**（需自动或一键引导）
4. AI 页「AI 队员」进入会话页：检测游戏 → 显示 LAN 端口 → 「召唤队友」
5. bot 加入后：游戏内聊天或启动器聊天框指挥
6. 支持：跟随 / 过来 / 停止 / 基础 get（与 PC catalogue 对齐）
7. 断开：quit 进程、回收额度会话

### 3.2 非目标（一期明确砍掉）

- prismarine-viewer 桌面窗口
- 截屏视觉理解闭环
- 语音球浮窗（可二期接系统语音）
- 复杂建造小屋 / 完整战斗编排（等 PC Task 目录扩完再跟）
- 把队员做成模组实体（产品线不同）

---

## 4. Android 最大技术风险：Node 侧车怎么跑

PC 用 `ProcessStartInfo` 调系统/便携 Node 跑 `bot.js`。Android 没有同等「随便 spawn 桌面 Node」的用户体验，必须先定方案。

### 方案对比

| 方案 | 做法 | 优点 | 风险 |
|------|------|------|------|
| **A. 应用内嵌 Node（推荐调研优先）** | 打包官方 Node Android 二进制或成熟嵌入运行时，私有目录 `files/aiplayer/node` + `AiPlayerBot` | 与 PC 同脚本；闭环在 App 内 | so/体积大；SELinux；首次 `npm ci` 慢；需 arm64 |
| **B. Termux / 外部 Node** | 检测已装 Node，Intent/路径调用 | 开发快 | 普通用户不可达；商店政策差 |
| **C. 远端 bot 主机** | 手机只做 UI，假人跑在 PC/云 | 手机轻 | 不是「手机单机队员」；延迟与安全 |
| **D. 纯 Java 协议客户端** | 重写 mineflayer | 无 Node | 工作量爆炸，否决 |

**文档建议（待 spike）：** 一期走 **A**，镜像 PC 的 `NodeRuntimeProvisioner`（下载 arm64 Node tarball → 解压 → `node bot.js …`）。  
Spike 输出必须回答：

1. 目标 ABI：`arm64-v8a`（是否兼 `armeabi-v7a`）
2. 包体增量与是否按需下载（推荐按需，不塞进 APK 主包）
3. `npm ci` 放首次召唤还是预置 `node_modules`（预置更稳，体积更大）
4. 前台 Service 保活（游戏切后台时 bot 进程策略）
5. 与 `LaunchActivity` 同进程 vs 独立进程（崩溃隔离）

若 A 在两周内不可行，再评估「仅 Wi‑Fi 同网的 PC 侧车遥控」作为过渡，不作为最终形态。

---

## 5. 局域网开房与发现（手机特有缺口）

PC 侧：`AIPlayerService` 轮询游戏状态 + LAN 发现/手动端口 → `EnsureJoinedAsync(host, port)`。

手机需补齐一条清晰链路：

```text
LaunchActivity 游戏运行中
    → 检测/触发 Open to LAN（或读已有端口）
    → 得到 127.0.0.1:port（或局域网 IP）
    → AiPlayerBotProcess.start(host, port, version, username, skin?)
```

实现注意：

- 假人必须 `online-mode=false` 世界；需在 UI 提示「请使用允许离线加入的局域网」
- 推荐默认宣传版本：**Java 1.21.x**（与 PC 对齐方案一致；26.x 挖矿仍不稳）
- 端口来源优先级建议：用户手填 → 日志/桥接解析 →（若有）状态探测 API
- 若当前 runtime 无法稳定读到 LAN 端口，一期允许「手动输入端口」降级，不阻塞召唤

---

## 6. 宿主 Kotlin 模块设计（建议落点）

```text
app/.../core/aiplayer/
  AiPlayerModels.kt          # Phase / BehaviorMode / TaskItem / Snapshot
  AiPlayerService.kt         # 状态机 + 会话入口（对标 AIPlayerService）
  AiPlayerBotProcess.kt      # 对标 MinecraftLanChatBot（进程 IO）
  AiPlayerNodeProvisioner.kt # 对标 NodeRuntimeProvisioner
  AiPlayerActionDispatcher.kt
  AiPlayerPromptComposer.kt
  AiPlayerCraftIntent.kt
  AiPlayerOrchestrator.kt
  AiPlayerTaskTracker.kt
  AiPlayerQuotaGate.kt       # 消费 playerLimitFen / 与 AiBackend 对齐

app/.../ui/ai/
  AiTeammateFragment.kt      # 替换占位 card 跳转
  （或独立 Activity，避免与助手聊天抢导航）
```

资产：

```text
app/src/main/assets/aiplayer/AiPlayerBot/   # 或 filesDir 按需下发
  bot.js / taskCatalogue.js / craftIntent.js / package.json / …
```

与现有 `core/ai` 边界：

- `AiChatService`：启动器助手（工具调用改版本等）——**不动**
- `AiPlayerService`：游戏内队员——**新模块**；可共用 `AiBackendClient.chatCompletion`，但 **system prompt、历史、额度字段分离**

---

## 7. 与 PC 的代码同步策略

| 策略 | 说明 |
|------|------|
| 侧车单源 | 理想：独立 git submodule / 拷贝脚本；禁止手机私改协议后不回流 PC |
| 宿主双实现 | C# / Kotlin 各维护；用本文 §2.4 协议 + 交接说明书验收口令做契约测试 |
| 重构节奏 | PC 今天改 Task 层时，手机先写壳与 Node spike，**等 PC 侧车稳定 tag 再锁依赖** |
| 回归 | 共享一组 offline 用例：`Tools/AiPlayerBot/offline-tests/`（若有）在桌面 CI 跑；手机仪器用真机局域网 |

建议在两边文档互链：

- PC：`Docs/AI-Player2复刻交接说明书.md` 增加「手机迁移见 phone/docs/…」
- 手机：本文

---

## 8. 分阶段实施计划

### Phase 0 — Spike（3～5 天，可并行 PC 重构）

- [ ] Android arm64 Node 启动 `bot.js --help` / 假连无效端口看错误路径
- [ ] 确认 `npm` 依赖在设备上的安装体积与时间
- [ ] 从运行中 MC 拿到 LAN 端口的一条可行路径（手动也算）
- [ ] 输出：Go / No-Go 与包体策略

### Phase 1 — 入局 MVP

- [ ] 去掉「尽情期待」，进入队员页
- [ ] 状态机：WaitingGame → WaitingOpenLan → JoiningBot → InGame
- [ ] `follow` / `come` / `stop` / `quit`
- [ ] 游戏内 chat 事件回传到队员页
- [ ] 登录门禁 + 基础错误文案（无 Node / 无端口 / 入局失败）

### Phase 2 — 与 PC Task 层对齐

- [ ] 同步锁定版 `AiPlayerBot`（含 `taskCatalogue` / `runGetGoalTask`）
- [ ] 意图短路 + `TASK: get …` / JSON command
- [ ] 验收口令与 PC 交接说明书第 4 节一致
- [ ] `playerLimitFen` 扣费与 UI 展示

### Phase 3 — 体验增强（按需）

- [ ] 皮肤选择
- [ ] 系统语音输入
- [ ] 任务列表 / 进度条
- [ ] 战斗与更多 catalogue 项（跟 PC）
- [ ] 视觉（慎重评估耗电与隐私）

---

## 9. 测试与验收

### 9.1 环境

- 真机 arm64 + 同一 Wi‑Fi（若 bot 绑 `127.0.0.1`，则 bot 与游戏必须同机）
- MC **1.21.x**，局域网离线模式
- 已登录 Booxin，队员额度充足

### 9.2 契约验收（与 PC 对齐）

| # | 操作 | 通过标准 |
|---|------|----------|
| 1 | 召唤队友 | 假人出现在世界；Phase=InGame |
| 2 | 「跟着我」 | 跟随；任务中不被 desire 乱抢（若已开 Orchestrator） |
| 3 | 「弄 10 个木头」 | 原木净增 ≥10；有 progress/done |
| 4 | 「做一个木镐」 | 最终背包有木镐 |
| 5 | 「停」 | ≤3s 停任务 |
| 6 | 退出队员 | 进程退出；可再次召唤 |

### 9.3 回归

- AI 助手聊天不受影响
- 游戏杀进程时 bot 被清理（无僵尸 node）
- 无额度 / 未登录有明确提示

---

## 10. 开放问题（实现前必须拍板）

1. **Node 供给**：内嵌按需下载 vs 预置 `node_modules`？（影响首包与首次召唤时长）
2. **bot 与游戏是否必须同设备**：仅本机 `127.0.0.1`，还是支持加入「同网其他主机开的房」？
3. **额度**：队员日额度是否与 PC 共用同一用户池（预期是）；免费档是否允许体验分钟数？
4. **PC 重构未完成时**：手机 Phase 1 是否允许先上 follow/stop，get 任务等 PC tag？
5. **商店合规**：按需下载 Node 二进制的披露与第三方 notice（`mineflayer` 等）写入 `THIRD_PARTY_NOTICES`

---

## 11. 参考索引

### 手机仓库

- `app/.../ui/ai/AiAssistantFragment.kt`（占位入口）
- `app/.../core/ai/*`（已有助手与额度）
- `docs/RUNTIME_ARCHITECTURE.md`（运行时边界）

### PC 仓库

- `Services/AIPlayer/*`
- `Tools/AiPlayerBot/bot.js`、`taskCatalogue.js`、`craftIntent.js`
- `Docs/AI-Player2复刻交接说明书.md`（行为层真相）
- `Docs/AI-Player2对齐方案.md`
- `Docs/V4.0.0.0.md`（产品与额度档位背景）

---

## 12. 建议的下一步（仍不写业务代码）

1. 开 **Phase 0 Node spike** 分支，只验证进程能起。  
2. 与 PC 约定一个 `AiPlayerBot` 冻结 tag（重构过验收后再锁）。  
3. 补一张时序图进本文附录（召唤 → 入局 → TASK → done），实现时照图写状态机。

---

*本文只描述迁移技术路径；具体 API 签名以实现时 PC 侧车与 `MinecraftLanChatBot` 源码为准。*
