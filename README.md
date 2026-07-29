# Booxin Launcher (Phone)
#
# Native Android scaffold for a Minecraft launcher.
# Open this folder in Android Studio (Giraffe+ / Koala+) and Sync Gradle.
#
# Package: com.booxin.launcher
# Min SDK 26 · Target SDK 35 · Kotlin + ViewBinding + Navigation

## Open & run
1. Install [Android Studio](https://developer.android.com/studio)
2. File → Open → select this project root
3. Let Gradle sync (it will download the wrapper / SDK components if needed)
4. Run on an emulator or a physical Android phone

## Current scaffold
- Bottom nav: 主页 / 版本 / 账号 / 设置
- Brand theme colors from the Booxin logo
- Adaptive launcher icon generated from `Booxin.jpg`
- `LauncherRepository` sample versions + offline account stub
- `GameRuntime` stub for future download / launch engines
- Game files root: `filesDir/minecraft/`

## Suggested next steps
1. Version manifest fetch (Mojang / BMCLAPI)
2. Download manager + local install detection
3. Microsoft account OAuth
4. Game runtime integration (Pojav / other Android MC runtime)

App id: `com.booxin.launcher`
Version: `0.0.2`
