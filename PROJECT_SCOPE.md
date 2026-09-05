# 项目边界（必读）— 避免打错仓库 / 修错启动器

> 给 Agent 与协作者。**在本仓库改代码或打包前先读这一段。**

## 你现在在哪个项目？

| 项目 | 路径 | 包名 / 产物 | 是什么 |
|------|------|-------------|--------|
| **Booxin 手机启动器（本仓库）** | `D:\all code in D\booxin launcher phone\` | `com.booxin.launcher` → `app-release.apk` | **当前产品**：Kotlin/Android 自研启动器 |
| FCL 参考 / BooxinLauncher-Android fork | `D:\all code in D\booxin-launcher2.3.0\BooxinLauncher-Android\`（内含 `FoldCraftLauncher/`） | `art.boonix.launcher.mobile` / FCL 变体 | **另一条线**：基于 FoldCraftLauncher 的 fork，**不是**本 App |
| 上游 FCL 源码参考 | `D:\all code in D\FoldCraftLauncher\` | `com.tungsten.fcl` | 开源参考实现，可抄逻辑，**不要当成 Booxin 本体打包** |
| PC Booxin | `D:\all code in D\booxin-launcher2.3.0\` | Windows WPF | PC 启动器，与手机 APK 无关 |

## 硬性规则

1. **在本 Cursor 工作区（`booxin launcher phone`）打包 = 只打 `:app:assembleRelease`**，产物：  
   `app/build/outputs/apk/release/app-release.apk`
2. **不要**在本仓库会话里去跑 `:FCL:assembleRelease`，除非用户**明确**说在做 `BooxinLauncher-Android` / FCL。
3. FoldCraftLauncher / FCL **仅作参考**（例如 ForgeBootstrap classpath）。移植逻辑时：
   - 改 **本仓库** `app/src/main/java/com/booxin/launcher/...`
   - **不要**只改 FCL 仓库就以为 Booxin 修好了
4. 用户说「Booxin launcher」「手机启动器」「重新打包 APK」且未点名 FCL → **默认就是本仓库**。

## ForgeBootstrap 修复落点（本仓库）

华为平板等设备上 `net.minecraftforge.bootstrap.ForgeBootstrap` 崩溃  
（`NoSuchElementException: No value present` @ `Bootstrap.start`）的修复在：

- `app/.../launch/ForgeBootstrapClasspathHelper.kt`（新建，自 FCL 逻辑移植）
- `app/.../launch/LaunchCommandBuilder.kt`（精简 `-cp`、cwd=`libraries` 父目录、禁止错误 add-exports / classpath.jar）

参考来源（只读）：  
`BooxinLauncher-Android/FoldCraftLauncher/FCLCore/.../ForgeBootstrapClasspathHelper.java`  
**修本 App 时以本仓库文件为准。**

## 打包命令（本仓库）

```bat
cd /d "D:\all code in D\booxin launcher phone"
gradlew :app:assembleRelease
```

版本号在 `app/build.gradle.kts` 的 `versionCode` / `versionName`。

## 若任务来自「另一条对话」的交接文

交接里若写着 `FCL` / `BooxinLauncher-Android` / `:FCL:assembleRelease`，那是**别的工程**。  
在本仓库工作时：先向用户确认目标，或按上表默认落到 **Booxin 手机启动器**。
