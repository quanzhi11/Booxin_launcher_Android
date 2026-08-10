# Compatibility Matrix (Booxin Runtime)

验收维度：机型 × MC 版本 × 加载器 × 渲染后端。

## 最低验收（每次 runtime 改动）

| MC | 加载器 | 渲染 | 期望 |
|----|--------|------|------|
| 1.16.5 | Vanilla / Forge | GL4ES | 进标题屏、触控、视角 |
| 1.20.1 | Fabric | BooxinGlues (GL4ES MIT) | 进标题屏、触控、视角 |
| 1.21.x | NeoForge | BooxinGlues | 同上；重模组时自动拉 Zink (MIT) |
| 1.21.x | Fabric + Sodium | BooxinGlues→Zink | Podium；profile=`sodium`；无 Vulkan 时可手动 MobileGlues |
| 1.20+ | Fabric + Iris | BooxinGlues→Zink | profile=`iris`；Path A 过渡期兼容面低于 MG |
| 1.20+ | Fabric/Forge + Create | BooxinGlues→Zink | profile=`create` |

## ABI

当前打包：`arm64-v8a`（主力）、`armeabi-v7a`、`x86_64`、`x86`（模拟器）。

## 发布检查

- [ ] `docs/THIRD_PARTY_NOTICES.md` 已更新
- [ ] 红区 `.so` 是否仍打入 APK（过渡期注明）
- [ ] R8 未剥离 JNI 入口类（见 `proguard-rules.pro`）
- [ ] `BooxinBridge.enableAndroidInput()` 在 Surface 绑定前后均成功
