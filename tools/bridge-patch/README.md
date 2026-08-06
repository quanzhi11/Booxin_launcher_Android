# Booxin LWJGL bridge patch

Self-hosted Android LWJGL patch sources for HotSpot classpath merge.

## Layout

- `src/org/lwjgl/glfw/CallbackBridge.java` — JNI ABI (transitional; called via `BooxinBridge` from ART)
- `src/org/lwjgl/glfw/BooxinInputHooks.java` — Booxin input helpers
- `src/org/lwjgl/opengl/RendererInit.java` — GLES translator init

## Build intent

Produce `lwjgl-bridge-patch.jar` / merge into `assets/app_runtime/lwjgl/lwjgl.jar`
without depending on FoldCraftLauncher prebuilt jars as the source of truth.

Upstream LWJGL is BSD-licensed; Booxin patches are project-owned.
