# Booxin Launcher — Third-Party Notices

本文件列出启动器分发产物中可能包含的第三方组件及其许可类别。
**业务代码（`com.booxin.launcher`）为 Booxin 自有；游戏运行时仍存在过渡期第三方二进制。**

## 三色分类

| 颜色 | 含义 | 闭源分发 |
|------|------|----------|
| 绿 | 许可允许闭源链接/分发（需保留 NOTICE） | 可以 |
| 黄 | 许可需单独评估 / 有例外条款 | 条件允许 |
| 红 | 强 copyleft（典型 GPL-3）衍生或二进制 | **不可直接闭源整包分发** |

## 清单

| 组件 | 典型文件 / 位置 | 颜色 | 备注 |
|------|-----------------|------|------|
| AndroidX / Material / Kotlin / Coroutines | Gradle `implementation` | 绿 | Apache-2.0 |
| OkHttp / Coil / commons-compress / XZ | Gradle | 绿 | Apache-2.0 / 相关许可 |
| LWJGL（Android 构建 + bridge patch） | `assets/app_runtime/lwjgl/*`, `liblwjgl*.so` | 绿 | BSD；bridge 含 Booxin 补丁 |
| OpenAL Soft | `libopenal.so` | 绿 | LGPL（动态链接常见用法） |
| FreeType | `libfreetype.so` | 绿 | FTL / GPL 双许可（使用 FTL） |
| shaderc / SPIRV-Cross | `libshaderc.so`, `libspirv-cross-c-shared.so` | 绿 | Apache-2.0 |
| GL4ES | `libgl4es_114.so` | 黄 | MIT 系；确认上游版本 NOTICE |
| MobileGlues | `libmobileglues*.so` | 黄 | 按上游仓库许可披露 |
| OpenJDK / HotSpot（嵌入式 JRE） | 用户数据目录 Java home | 黄 | GPL-2 + Classpath Exception |
| Booxin Bridge（自研） | `libbooxin_bridge.so` | 绿 | Booxin 自有；替代原 exec/input/EGL 桥 |
| ~~历史第三方桥接库~~ | 已移出 APK（`tools/_quarantine_gpl/`） | 红 | **不再打包** |
| EasyTier | `libeasytier_*.so` | 黄 | 联机组件；按上游许可 |
| Terracotta | `libterracotta.so` | 黄 | 按上游许可 |
| Minecraft 客户端 / 模组 | 用户下载 | — | **非本启动器版权**；不可宣称自有 |

## 仓库拆分策略

见 [RUNTIME_ARCHITECTURE.md](./RUNTIME_ARCHITECTURE.md)。

## 过渡策略

1. ART / 业务层只通过 `com.booxin.runtime` / `GameRuntimeBackend` 访问运行时。
2. 红区组件仅由 `LegacyCompatBackend` 加载；目标是 Phase 2+ 用自研 bridge 替换。
3. `tools/_quarantine_gpl/` 仅供对照，**不参与 APK 构建**。

## 免责

本清单供工程治理使用，不构成法律意见。正式上架/闭源发布前应由法务复核许可与分发方式。
