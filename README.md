# Booxin Launcher（Android）

手机端 Minecraft 启动器，包名 `com.booxin.launcher`。

- Min SDK 26 / Target SDK 35
- Kotlin · ViewBinding · Navigation
- 支持原版、Forge、NeoForge、Fabric、Quilt、OptiFine
- 微软账号、离线账号
- 联机（需自备或配置房间 / 中继服务）

## 构建

1. Android Studio 打开本仓库根目录
2. Sync Gradle
3. Run

Release 签名：复制 `keystore.properties.example` → `keystore.properties`，填入你的 jks（不要提交）。

可选私有服务（写入本机 `local.properties`，不要提交）：

```properties
booxin.roomApiRoots=http://127.0.0.1:5000
booxin.easytierRelays=tcp://relay.example.com:8080
```

游戏目录在设备上：`filesDir/minecraft/`

## 开源协议

本项目采用 **[GPL-3.0](LICENSE)**（与 [FoldCraftLauncher](https://github.com/FCL-Team/FoldCraftLauncher) 相同）。

`app/src/main/jniLibs`、`app/src/main/assets/app_runtime` 等第三方组件另见 `docs/THIRD_PARTY_NOTICES.md`。
