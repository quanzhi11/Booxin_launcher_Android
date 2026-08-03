# Booxin Runtime Architecture

## 目标

将游戏运行时从「Pojav/FCL 直调」收敛为 Booxin 自有 API，业务层可闭源；过渡期红区二进制隔离在 `LegacyCompatBackend`。

## 模块边界（建议仓库拆分）

| 仓库 / 模块 | 内容 | 许可意图 |
|-------------|------|----------|
| `booxin-launcher` | UI、下载、账号、版本管理、联机业务 | 闭源 |
| `booxin-runtime` | `GameRuntimeBackend`、`BooxinBridge`、JNI、渲染选型 | 自研完成后可闭源；含 GPL 过渡二进制时须单独开源或剔除 |
| `booxin-runtime-legacy`（可选） | 仅封装 `libpojavexec` 等红区 | GPL-3 开源，动态分发需谨慎 |

当前单仓库阶段：源码包路径已按上述边界划分，后续可用 Git subtree / 独立 Gradle module 物理拆分。

## 调用链（目标）

```
LaunchActivity / GameInput
        ↓
com.booxin.runtime.BooxinBridge   (ART 公共 API)
        ↓
GameRuntimeBackend
   ├─ LegacyCompatBackend  → libpojavexec / CallbackBridge (过渡)
   └─ BooxinNativeBackend  → libbooxin_bridge (目标)
        ↓
NativeJvmLauncher / libbooxin_jvm.so
        ↓
HotSpot + LWJGL + GLES translator + Minecraft
```

## 不可改动的过渡 ABI

在替换 `libpojavexec` 之前，以下符号/类名仍被 native 硬编码查找：

- `org.lwjgl.glfw.CallbackBridge`（HotSpot + JNI）
- `com.tungsten.fclauncher.CriticalNativeTest`（CriticalNative 探测）
- 环境变量 `POJAV_*` / `FCL_*`（由 `RuntimeEnv` 同步写出兼容别名）

业务代码禁止直接依赖上述名称；一律走 `BooxinBridge` / `RuntimeEnv`。

## 环境变量

| Booxin（首选） | 过渡别名（写给旧 .so） |
|----------------|------------------------|
| `BOOXIN_NATIVEDIR` | `POJAV_NATIVEDIR`, `FCL_NATIVEDIR` |
| `BOOXIN_RENDERER` | `POJAV_RENDERER` |
| `BOOXIN_EGL` | `POJAVEXEC_EGL` |
