# Renderer Backend — License Assessment

| Backend | Library | Upstream (typical) | Assessment | Closed-source link |
|---------|---------|--------------------|------------|--------------------|
| `GL4ES` | `libgl4es_114.so` | ptitSeb/gl4es | MIT | 通常可保留 + NOTICE |
| `MOBILE_GLUES` | `libmobileglues.so` | MobileGlues project | 以发布仓库 LICENSE 为准 | 披露后按上游条款 |
| `KRYPTON` | `libng_gl4es.so`（插件下载） | BZLZHH/NG-GL4ES | MIT（插件侧） | 按上游 NOTICE |
| `LTW` | `libltw.so`（插件下载） | OpenLTW / 社区插件包 | 以插件 LICENSE 为准 | 条件分发 |
| `VULKAN_ZINK` / `VIRGL` / `FREEDRENO` | Mesa OSMesa（插件下载） | Mesa3D | MIT | 可保留 + NOTICE |

内置仅打包 GL4ES / MobileGlues；其余从 GitHub Releases 下载第三方渲染插件 APK 并解压 `.so`。

## Windowing (Minecraft 26.3+)

| Library | File | Upstream | License | Closed-source |
|---------|------|----------|---------|---------------|
| SDL3 | `libSDL3.so` | [libsdl-org/SDL](https://github.com/libsdl-org/SDL) official Android AAR | **zlib** | 可闭源商用；保留 `assets/licenses/SDL3_LICENSE.txt` |
| LWJGL SDL bindings | `lwjgl-sdl.jar` + 游戏 libraries | [LWJGL](https://www.lwjgl.org) Maven `org.lwjgl:lwjgl-sdl` | **BSD-3** | 可闭源；内置 `assets/app_runtime/lwjgl/lwjgl-sdl.jar`，NOTICE 见 `assets/licenses/LWJGL_SDL_LICENSE.txt` |

**不要**从第三方启动器 APK 抽取 SDL（其壳常为 GPL）。官方 SDL3 Android 包与 LWJGL Maven 工件可单独用于闭源发行。LWJGL 3.4 的 SDL 模块直接加载 `libSDL3.so`，无需另打 `liblwjgl_sdl.so`。

## 渲染与 bridge 策略

1. 渲染选型只通过 `RendererBackend` / `GlRendererKind`（Booxin API）；设置项可覆盖为手动选择。
2. `LaunchCommandBuilder` 经 `RuntimeEnv` 写出 `BOOXIN_RENDERER`，并镜像历史 env 别名供过渡 native 读取。
3. 自研 bridge 就绪后：直接 `dlopen` 翻译库，删除对过渡 bridge 渲染初始化路径的依赖。
4. Sodium 等 runtime 指纹检测：短期可用 Podium；长期用自有 runtime 指纹。
5. 26.3+：`BOOXIN_WINDOWING=sdl`，跳过 GLFW preinit；`org.lwjgl.sdl.libname` 指向内置 zlib `libSDL3.so`。
