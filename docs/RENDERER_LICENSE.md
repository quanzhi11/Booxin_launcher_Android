# Renderer Backend — License Assessment

| Backend | Library | Upstream (typical) | Assessment | Closed-source link |
|---------|---------|--------------------|------------|--------------------|
| `GL4ES` | `libgl4es_114.so` | ptitSeb/gl4es | MIT | 通常可保留 + NOTICE |
| `MOBILE_GLUES` | `libmobileglues.so` | MobileGlues project | 以发布仓库 LICENSE 为准 | 披露后按上游条款 |
| `KRYPTON` | `libng_gl4es.so`（插件下载） | BZLZHH/NG-GL4ES | MIT（插件侧） | 按上游 NOTICE |
| `LTW` | `libltw.so`（插件下载） | OpenLTW / FCL plugin | 以插件 LICENSE 为准 | 条件分发 |
| `VULKAN_ZINK` / `VIRGL` / `FREEDRENO` | Mesa OSMesa（插件下载） | Mesa3D | MIT | 可保留 + NOTICE |

内置仅打包 GL4ES / MobileGlues；其余五个从 GitHub Releases 下载 FCL/Zalith 兼容插件 APK 并解压 `.so`。

## 去 Pojav 壳策略

1. 渲染选型只通过 `RendererBackend` / `GlRendererKind`（Booxin API）；设置项可覆盖为手动选择。
2. `LaunchCommandBuilder` 经 `RuntimeEnv` 写出 `BOOXIN_RENDERER`，并镜像旧 `POJAV_RENDERER` 供过渡 native 读取。
3. 自研 bridge 就绪后：直接 `dlopen` 翻译库，删除对 `libpojavexec` 渲染初始化路径的依赖。
4. Sodium 等「检测 Pojav」：短期仍可用 Podium；长期用自有 runtime 指纹，避免被识别为 Pojav。
