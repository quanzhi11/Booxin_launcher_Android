# 交接文档 — NeoForge 模组加载器（下一步）

> 写给下一个 Agent。仓库：本项目根目录  
> 今日：`2026-07-31`

---

## 目标

在下载页实现 **NeoForge** 安装与启动，体验对齐现有 **Forge**（选 MC 版本 → 选 NeoForge build → 装 vanilla → 跑 installer processors → 下 libraries → 出现在已安装列表并可启动）。

---

## 当前状态（Android）— NeoForge 已接入

### Forge / NeoForge（共用安装流水线）

| 环节 | 关键文件 |
|------|----------|
| UI 选 Forge / NeoForge / 列 build | `ui/download/DownloadFragment.kt` |
| 枚举 | `ModLoaderKind.kt`：`VANILLA` / `FORGE` / `NEOFORGE` |
| Forge 版本列表 | `ForgeVersionClient.kt` → BMCL `/forge/minecraft/{mc}` |
| NeoForge 版本列表 | `NeoForgeVersionClient.kt` → BMCL `/neoforge/list/{mc}`（回退 maven details） |
| 安装编排 | `ForgeGameInstaller.install(..., isNeoForge)` |
| 新版 installer（processors） | `ForgeNewInstaller.kt` + `ForgeProcessorService`（`:forge` 进程） |
| 入口 | `prepareForge` / `prepareNeoForge` → `installForgeVersion` / `installNeoForgeVersion` |
| 启动识别模组版 | `VersionJsonMerger.isModLoaderVersion`（`inheritsFrom`） |
| Maven 镜像 | `BmclApiDownloadProvider` 已映射 `maven.neoforged.net` |

NeoForge 版本目录：`{mc}-neoforge-{neoVersion}`  
NeoForge installer：`net/neoforged/neoforge/{ver}/…`（1.20.1 legacy：`net/neoforged/forge`）

### 仍 WIP（本任务之外）

- Fabric / Quilt / OptiFine 下载页 chip 仍是 Toast
- 社区页 NeoForge 筛选与安装无关，勿混改

### 近期已做完（勿重复）

- 官服：`OfficialServerCatalog` / `OfficialServerJoinService` / `servers.dat` / HTTPS 模组下载 / 主机名去空格
- 启动：不再自动装 `mcwifipnp`
- 虚拟按键：全键盘 + 组合键（`AddControlKeyDialog`）
- 明文：`network_security_config` 已放行 `boonix.art`

---

## PC 参考（必读）

PC 把 Forge / NeoForge 共用一套安装服务，用 `isNeoForge` 分支：

- `Services/ForgeInstallService.cs` — `ForgeInstallOptions.IsNeoForge`
- `Services/ForgeLaunchVersionJsonHelper.cs` — Maven 路径 `net/neoforged/neoforge`，前缀 `neoforge`
- `Views/MainWindow.xaml.cs`
  - `ResolveNeoForgeLatestVersionAsync` — BMCL NeoForge 版本列表
  - `InstallForgeBasedLoaderAsync(..., isNeoForge: true)`

### NeoForge 版本元数据（PC 用法）

```
https://bmclapi2.bangbang93.com/neoforge/meta/api/maven/details/releases/net/neoforged/neoforge
```

- NeoForge 版本号形态多为 `{mcMajor}.{mcMinor}.{build}`，例如 `21.1.x` 对应 MC `1.21.1`（**不是** Forge 的 `mc-loader` 拼接）。
- 旧线（极老）：`.../releases/net/neoforged/forge`（legacy，一般可后做）。

### NeoForge installer Maven（典型）

```
net/neoforged/neoforge/{neoVersion}/neoforge-{neoVersion}-installer.jar
```

BMCL：`{API}/maven/net/neoforged/neoforge/{ver}/neoforge-{ver}-installer.jar`  
官方：`https://maven.neoforged.net/...`

版本 ID 命名建议与 PC/HMCL 一致，例如：

- `{mc}-neoforge-{neoVersion}`  
  或 installer 写出的目录名（安装后以落盘 `versions/*/ *.json` 为准，必要时 rename/sync）。

---

## 建议实现步骤

### 1. 数据层：列出版本

新增 `NeoForgeVersionClient.kt`（可与 `ForgeVersionClient` 并列）：

1. 拉取 BMCL NeoForge maven details（或等价 list API）。
2. 按所选 **Minecraft 版本**过滤（用 PC 的 prefix 规则：`1.21.1` → `21.1.`）。
3. 返回 `NeoForgeBuild(mcVersion, loaderVersion, versionId, recommended?)`。
4. `installerUrls(neoVersion)`：BMCL maven + neoforged.net。

也可先探测 BMCL 是否另有简化接口（如 `/neoforge/list/{mc}`）；以实测为准，失败再走 maven details。

### 2. 安装层：复用 Forge 流水线

优先策略（改动面小）：

1. `ModLoaderKind` 增加 `NEOFORGE`。
2. 抽公共 `ForgeBasedInstaller`，或给 `ForgeGameInstaller.install(..., isNeoForge: Boolean)`：
   - vanilla 仍先装。
   - installer 改走 NeoForge URL。
   - `versionId` 用 neoforge 命名。
3. **processors**：现代 NeoForge installer 与 Forge NEW_SPEC 类似（`install_profile.json` + processors）。先跑现有 `ForgeNewInstaller`；若 profile 字段/变量不同，对照 PC / FCL 补差异。
4. 装完再 `libraryDownloader.downloadVersionLibraries(versionId)`。

注意：

- NeoForge 的 `libraries` / `mainClass` / bootstrap 可能与 Forge 略有不同；启动侧已用 `inheritsFrom` 通杀，一般够用。
- `:forge` 进程名可保留；逻辑上是「跑 installer JVM」，不必强行改进程名。
- 进度继续走 `repository.forgeInstallProgress`，或改名为 `modLoaderInstallProgress`（可选重构）。

### 3. UI

`DownloadFragment.kt`：

1. `chipNeoForge` 去掉 WIP，与 Forge 一样可勾选。
2. `when (loader)` 增加 `NEOFORGE -> pickNeoForgeBuild(version)`。
3. 对话框列 NeoForge builds → `runtime.prepareNeoForge(...)`（或 `prepareForge(..., neo=true)`）。

布局：`fragment_download.xml` 已有 chip，通常不用改布局。

### 4. 启动回归

装完后验证：

1. `versions/{id}/{id}.json` 存在且 `inheritsFrom` 正确。
2. `LaunchCommandBuilder` 走模组路径（FML flags / ignoreList）。
3. 真机进标题屏；再试放一个 NeoForge 模组进 `mods/`。

Java：跟 MC 版本走现有 `JavaEnvironmentManager.ensureForMinecraft`（1.21+ → 21）。

### 5. 编译安装

```bat
.\gradlew.bat :app:assembleRelease
adb install -r app\build\outputs\apk\release\app-release.apk
```

（设备已装 release 签名包，须继续用 release。）

---

## 已知坑（Forge 踩过，NeoForge 可能重演）

1. **Installer processors 必须在独立进程**（`ForgeProcessorService` / `:forge`），主进程跑会和游戏 JVM/native 冲突。
2. **Pack200 / 旧 Java**：老 Forge 才需要；现代 NeoForge 一般只要 Java 21。
3. **BMCL 404**：不要只信 `/forge/download?installer=`；NeoForge 也优先 **maven 路径**。
4. **version.json 目录名**：injector/legacy 可能写出带空格或不同大小写的目录，需要 sync/rename（见 `ForgeGameInstaller.ensureForgeVersionJson`）。
5. **官服**仍是 Forge（`guanfu.txt` 的 `mod_forge`）；NeoForge 与官服无关，勿混进 `OfficialServerJoinService`。
6. 勿提交 `tools/_jre8_unpack/`、`tools/_forge_restore*`、解包 jar 垃圾。

---

## 验收清单

- [x] 下载页可选 NeoForge，列出对应当前 MC 的 builds  
- [x] 安装进度条有意义（vanilla → installer → processors → libraries）  
- [x] 安装后版本列表出现 NeoForge 实例并可删除（版本 id：`{mc}-neoforge-{ver}`）  
- [ ] 能启动到主菜单（需真机）  
- [ ] 放入 NeoForge 模组后能加载（至少一个简单模组）  
- [x] Forge 原有路径无回归（`isNeoForge` 默认 false）  

---

## 后续（本任务之后）

1. Fabric / Quilt（下载页仍 WIP）  
2. OptiFine  
3. 旧 `HANDOFF.md` 里联机 Tab 重构（若产品仍要）— **优先级低于加载器**

---

## 文件速查

| 用途 | 路径 |
|------|------|
| 下载 UI | `app/src/main/java/.../ui/download/DownloadFragment.kt` |
| 下载布局 | `app/src/main/res/layout/fragment_download.xml` |
| Loader 枚举 | `.../modloader/ModLoaderKind.kt` |
| Forge 安装 | `.../modloader/ForgeGameInstaller.kt` |
| Forge 版本 API | `.../modloader/ForgeVersionClient.kt` |
| 新 installer | `.../modloader/ForgeNewInstaller.kt` |
| Processor 服务 | `.../modloader/ForgeProcessorService.kt` |
| Runtime API | `.../core/GameRuntime.kt`（`prepareForge`） |
| Repository | `.../data/repository/LauncherRepository.kt` |
| 版本 JSON 合并 | `.../download/game/VersionJsonMerger.kt` |
| 启动命令 | `.../launch/LaunchCommandBuilder.kt` |
| PC NeoForge 安装 | `booxin-launcher2.3.0/Services/ForgeInstallService.cs` |
| PC 版本解析 | `booxin-launcher2.3.0/Views/MainWindow.xaml.cs`（`ResolveNeoForgeLatestVersionAsync`） |
