# Booxin Launcher (Phone)

Native Android Minecraft launcher (`com.booxin.launcher`).

Min SDK 26 · Target SDK 35 · Kotlin + ViewBinding + Navigation

## Open & run

1. Install [Android Studio](https://developer.android.com/studio)
2. File → Open → select this project root
3. Let Gradle sync
4. Run on an emulator or a physical Android phone

## Local configuration (do not commit secrets)

| File | Purpose |
|------|---------|
| `local.properties` | Android SDK path + optional private endpoints |
| `keystore.properties` | Release signing (see `keystore.properties.example`) |

Optional keys in `local.properties` (see `local.properties.example`):

```properties
booxin.roomApiRoots=http://127.0.0.1:5000
booxin.easytierRelays=tcp://relay.example.com:8080
```

Signing materials under `signing/` and `keystore.properties` are gitignored.

## Game files

Game files root on device: `filesDir/minecraft/`

## License / third-party

Review bundled native libraries and JARs under `app/src/main/jniLibs` and `app/src/main/assets/app_runtime` before redistributing. See any `THIRD_PARTY_NOTICES` docs in-tree.
