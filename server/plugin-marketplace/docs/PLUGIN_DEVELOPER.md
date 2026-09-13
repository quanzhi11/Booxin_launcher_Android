# Booxin 插件开发文档

面向第三方开发者与审核员：说明插件类型、打包规范、申请上架与客户端行为。

基址：`https://boonix.art/plugin-api`  
开发者网页：`https://boonix.art/plugin-api/developer/`  
管理后台：`https://boonix.art/plugin-api/admin/`  

- **≤250MB**：可在启动器「申请上架」直接上传到服务器（存于 `/uploads/`）。
- **>250MB**：请添加 QQ `1018364788`（zrr）私聊提交；二维码见 `/contact/qq.png`。
- 也可继续使用外链直链。

---

## 1. 插件类型

| type id | 名称 | 说明 | 客户端默认行为 |
|---------|------|------|----------------|
| `renderer` | 渲染器 | GL/Vulkan/ANGLE 等 `.so` | APK →「本机渲染器」 |
| `driver` | 驱动 | GPU/驱动相关原生库 | 同上 |
| `ui` | UI 插件 | 启动器界面扩展、主题、皮肤 | zip →「本机插件」（按 features 生效） |
| `control` | 操控 / 虚拟按键 | 触控布局 | zip →「本机插件」；`controlLayout` 进游戏生效；支持 `buttons[].icon`、`style`/`css`、`extraLayouts` 多面板、`outputText`、TOGGLE 按键 |
| `input` | 输入法 / 键鼠 | 输入相关辅助 | zip →「本机插件」（暂无独立钩子） |
| `overlay` | 悬浮窗 / HUD | 游戏内叠加层 | zip →「本机插件」（暂无独立钩子） |
| `utility` | 工具 | 日志、诊断、滑动过渡等 | zip →「本机插件」（按 features） |
| `pack` | 整合包附属 | 配套资源/补丁 | zip →「本机插件」（资源包） |
| `other` | 其他 | 未分类 | zip →「本机插件」 |

查询类型列表：`GET /api/plugin-types`

---

## 2. 上架流程

1. 开发者准备 **公开直链**（HTTPS）或 ≤250MB 经启动器上传。
2. 在启动器「插件 → 申请上传」提交：名称、类型、直链/上传、简介（需登录联机账号）。
3. 管理员在后台审核通过 / 拒绝。
4. 用户在商店下载：带 `booxin-plugin.json` 的 zip 装进「本机插件」；渲染器 APK 装进「本机渲染器」。也可在「本机插件」点「导入 zip」。

---

## 3. 渲染器 / 驱动（原生插件）规范

适用于 `renderer` / `driver`。

### 3.1 交付物

- 推荐：含 `lib/*.so` 的 **APK**（可无 Activity，仅作 so 容器）。
- 或已提取的目录，内含 `plugin.json` + ABI 匹配的 `.so`。

### 3.2 `plugin.json`（推荐写入 APK 根或安装目录）

```json
{
  "id": "my-gl-renderer",
  "name": "My GL Renderer",
  "version": "1.0.0",
  "type": "renderer",
  "glLib": "libmygl.so",
  "eglLib": "libEGL.so",
  "rendererToken": "opengles3",
  "libGlEs": "3",
  "kindName": null,
  "disguiseAsGl4es": false,
  "extraEnv": {
    "LIBGL_ES": "3"
  }
}
```

| 字段 | 必填 | 说明 |
|------|------|------|
| id | 建议 | 目录名 / 唯一标识，勿含路径非法字符 |
| name | 建议 | 显示名 |
| version | 建议 | 版本字符串 |
| type | 建议 | `renderer` 或 `driver` |
| glLib | 是 | 主 GL 库文件名 |
| eglLib | 否 | 默认 `libEGL.so` |
| rendererToken | 否 | 如 `opengles3`、`vulkan_zink` |
| libGlEs | 否 | 默认 `3` |
| disguiseAsGl4es | 否 | 是否伪装 gl4es 环境 |
| extraEnv | 否 | 额外环境变量 |

### 3.3 AndroidManifest 元数据（可选，便于系统 APK 发现）

```xml
<meta-data android:name="booxin.plugin" android:value="renderer" />
<meta-data android:name="org.lwjgl.opengl.libname" android:value="libmygl.so" />
```

包名建议包含 `booxin` / `renderer` / `plugin` / `driver` 以便启发式发现。  
（启动器仍可识别部分历史第三方插件元数据，新插件请只用 `booxin.plugin`。）

### 3.4 ABI

至少提供设备常用 ABI：`arm64-v8a`（必需）、可选 `armeabi-v7a`。  
启动器按当前 ABI 从 APK 的 `lib/<abi>/` 提取。

---

## 4. UI / 操控 / 工具等 zip 插件

适用于 `ui`、`control`、`input`、`overlay`、`utility`、`pack`、`other`。

客户端会把带 `booxin-plugin.json` 的 **zip** 安装到「本机插件」，可启用 / 禁用 / 卸载 / 商店更新。  
**运行效果**由清单里的 `features` 决定（与商店 `type` 标签可不同；例如 `utility` 也可只开 `pageSlideTransitions`）。

### 4.1 交付与安装

1. 用 Booxin Studio 新建 / 编辑后「一键打包」，或手写 zip（根目录含 `booxin-plugin.json`）。
2. 商店下载，或启动器「本机插件 → 导入 zip」。
3. 启用后按下方 features 生效。

### 4.2 清单 `booxin-plugin.json`

```json
{
  "schema": 1,
  "id": "my-ui-theme-dark",
  "name": "暗色主题",
  "version": "1.0.0",
  "type": "ui",
  "minLauncher": "5.3.6",
  "description": "示例",
  "features": {
    "customTheme": true
  },
  "theme": { }
}
```

常用 `features`：

| 字段 | 说明 |
|------|------|
| `homeGreetingByTime` | 主页按时段问候 |
| `pageSlideTransitions` | 顶部导航与进出游戏左右滑动 |
| `home3dModel` | 首页显示当前账号 3D 皮肤（逻辑在启动器内） |
| `customLauncherIcon` | 自定义主页品牌图标 |
| `customFont` | 自定义界面字体 |
| `customTheme` | 完整主题（色/壁纸图或视频/文案/导航名等）；写了 `theme` 也会自动开启 |
| `controlLayout` | 游戏内虚拟按键布局；需 `layoutFile`（默认 `control_layout.json`） |
| `jsCommands` | 声明 JS 桥意图；启用时需用户授权 |
| `customPages` | 自定义页面（也可由非空 `pages` 推断） |

`type: "control"` 且包内有布局文件时，也会按操控插件处理。

操控清单还可声明：

| 字段 | 说明 |
|------|------|
| `layoutFile` / `controlLayoutFile` | 主面板布局 JSON |
| `extraLayouts` | 额外面板数组：`{ "id", "name", "file"|"layoutFile"|"path" }`；游戏内操控菜单右侧「插件面板」可切换；每个面板有独立本机存档 |

### 4.3 操控布局 `control_layout.json`

格式与启动器本机 `control_layout.json` 相同（`version: 4`）：`buttons` / `joystick` / `floatingBall` / `gestureQuick` / `buttonStyle`。  
启用插件后进游戏即用该布局；禁用后回到用户本机保存的布局。  
用户在游戏内拖动 / 缩放 / **重命名** / 开关跟手后，会写入本机，并与插件布局合并：插件提供贴图与样式，本机保存覆盖几何与自定义文案（避免「每次打开又回到默认位置」）。  
同时多个 `controlLayout` 已启用时，按本机插件扫描顺序取**第一个**生效。

布局文件候选（相对安装目录，声明优先）：`layoutFile` → `control_layout.json` → `layout.json` → `controls.json` → `assets/control_layout.json`。

**无插件贴图/样式时的默认底：** 近透明填充 + 淡描边（闲置透明度约 `0.58`）。做主题操控包请提供 `icon` 或 `buttonStyle`/`style`，否则按钮几乎看不见。有插件 chrome 时闲置透明度约 `0.92`。

#### `buttons[]` 字段

| 字段 | 必填 | 说明 |
|------|------|------|
| `id` | 建议 | 按钮标识；缺省时客户端生成；**建议稳定唯一**，便于与用户本机位置合并 |
| `label` | 建议 | 显示文案，叠在按钮表面；用户可在编辑模式重命名覆盖 |
| `kind` | 是 | 见下方 kind 表 |
| `code` | 是 | 主键码（GLFW）；`SOFT_KEYBOARD` 可为 `0`；也可写数组（同 `codes`） |
| `codes` | 可选 | 长度 ≥2 时表示组合键（修饰键在前） |
| `x` / `y` | 是 | 按钮中心，相对父布局 `0..1` |
| `sizeDp` | 可选 | 边长 dp，默认约 `52`，范围 **`36..160`** |
| `icon` | 可选 | 贴图路径，见贴图接口；优先于 CSS 绘制 |
| `style` / `css` | 可选 | CSS 风格绘制对象，见下方；覆盖布局级默认样式 |
| `outputText` / `text` | 可选 | 非空时点击注入聊天/命令文本（FCL 风格）；可与 `code: 0` 组成纯文本键 |

`kind` 补充说明：

| kind | 行为 |
|------|------|
| `KEY_TAP` | 抬起时点击一次按键；若有 `outputText` 则注入文本 |
| `KEY_HOLD` | 按下按住，抬起松开；手指滑出按钮后交给触控板转视角 |
| `MOUSE_HOLD` | 同上，但是鼠标键 |
| `KEY_FOLLOW` / `MOUSE_FOLLOW` | 按住期间按钮图形跟手移动并保持按下，松开回弹原位；不交给触控板 |
| `KEY_TOGGLE` / `MOUSE_TOGGLE` | 点击闩住按下，再点松开 |
| `SOFT_KEYBOARD` | 切换系统软键盘；若布局未包含，启动器会自动补一个（位置可被用户拖动并记住） |
| `SCROLL` | 滚轮上/下（`code` ≥0 为上） |

FCL 兼容特殊码（单码）：`-1` 左键、`-2` Ctrl+C、`-3` 右键、`-4` 左箭头、`-5` 右箭头。

`joystick` 字段：

| 字段 | 说明 |
|------|------|
| `x` / `y` | 轮盘中心，相对父布局 `0..1` |
| `sizeDp` | 边长 dp，约 `120..220` |
| `follow` | 可选，**默认 `false`**。按下时轮盘移到指下，拖出外圈时底盘追手，松开回弹；编辑里点选轮盘可开关 |

布局级默认样式（对所有按钮生效，可被单键 `style`/`css` 覆盖）：

| 字段 | 说明 |
|------|------|
| `buttonStyle` / `buttonCss` / `css` | 与单键 `style` 同结构 |

#### `buttons[].icon` 贴图接口

- 值为**相对本插件安装目录**的图片路径，例如 `"assets/btn.png"`。
- 支持扩展名：`png` / `webp` / `jpg` / `jpeg`。
- 有有效贴图时：贴图作按钮背景，`label` 叠在上面；优先级高于 CSS 绘制。
- 安全限制：禁止绝对路径、禁止路径中含 `..`；解析结果必须落在插件目录内。
- 贴图与布局 JSON 同属该操控包；未启用 `controlLayout`（或未装到「本机插件」）时不会加载。

#### `style` / `css` JSON 绘制接口

无 `icon`（或贴图失败）时，可用 CSS 风格字段由客户端绘制按钮底：

| 字段 | 别名 | 说明 |
|------|------|------|
| `backgroundColor` | `background` / `bg` / `background-color` | 填充色，`#RGB` / `#RRGGBB` / `#AARRGGBB` / `0xAARRGGBB`；也支持关键字 **`transparent`** |
| `borderColor` | `border-color` / `strokeColor` | 描边色 |
| `borderWidth` | `border-width` / `strokeWidth` | 描边宽度（dp） |
| `borderRadius` | `border-radius` / `radius` | 圆角（dp）；`50%` / `full` / `circle` 视为圆形 |
| `shape` | | `oval` / `circle` / `rect` / `roundrect` / `pill` |
| `gradient` | | 颜色数组，至少 2 个；也支持 `"linear-gradient(#a,#b)"` 字符串 |
| `gradientOrientation` | `gradient-orientation` | `tl_br` / `top_bottom` / `left_right` 等 |
| `textColor` | `color` / `text-color` | 文案颜色 |
| `opacity` | `alpha` | `0.15..1` 整体透明度 |
| `pressScale` | `press-scale` / `clickScale` | 按下缩放 `0.72..1`，默认约 `0.90` |
| `pressEffect` | `press-effect` / `clickEffect`；或 `"jelly": true` | `squash`（瞬时）/ `jelly`（挤压 + 回弹） |

绘制优先级：**icon 贴图 → style/css → 启动器默认近透明圆形底**。

示例片段：

```json
{
  "version": 4,
  "buttonStyle": {
    "shape": "oval",
    "backgroundColor": "#AA2A6FA8",
    "borderColor": "#88FFFFFF",
    "borderWidth": 2,
    "textColor": "#FFFFFFFF",
    "opacity": 0.92,
    "pressEffect": "jelly",
    "pressScale": 0.84
  },
  "buttons": [
    {
      "id": "jump",
      "label": "跳",
      "kind": "KEY_HOLD",
      "code": 32,
      "x": 0.90,
      "y": 0.70,
      "sizeDp": 64,
      "style": {
        "gradient": ["#CCFF8AC8", "#CC88CCFF"],
        "gradientOrientation": "tl_br",
        "borderWidth": 2,
        "borderColor": "#FFFFFFFF"
      }
    },
    {
      "id": "lmb",
      "label": "左键",
      "kind": "MOUSE_HOLD",
      "code": 0,
      "x": 0.88,
      "y": 0.55,
      "sizeDp": 58
    }
  ],
  "joystick": { "x": 0.13, "y": 0.80, "sizeDp": 150, "follow": false },
  "floatingBall": { "x": 0.96, "y": 0.38 },
  "gestureQuick": { "x": 0.96, "y": 0.30 }
}
```

清单还可声明 `pages`（与 `features` 同级）：

| 字段 | 说明 |
|------|------|
| `id` / `title` | 页面标识与标题 |
| `entry` / `html` / `file` | 插件目录内相对 HTML 路径 |
| `url` / `href` / `remote` | 可选；仅 **https**，有则直接加载远程页（如论坛壳） |
| `orientation` | `portrait` / `landscape`（也认竖屏/横屏） |
| `fullscreen` / `fullScreen` / `immersive` | 沉浸式，隐藏部分页面铬 |

### 自定义页面与 JS 桥（应用层）

- 页面由启动器 WebView 托管，**不是**游戏底层（JVM/SDL）能力。
- JS 桥对象名：`BooxinPlugin`（仅在已授权 `jsCommands` 时注入）。
- 白名单：`getInfo()` / `toast(msg)` / `openUrl(https)` / `navigate(home|versions|community|multiplayer|ai|pluginstore|plugins|settings)` / `close()`。
- 明确不提供：shell、任意路径读写、NativeJvm / SDL / 输入注入。

### 4.4 尚未深度接线的类型

| type | 现状 |
|------|------|
| `input` / `overlay` / `pack` / `other` | 可安装到「本机插件」管理；暂无独立运行时钩子（可先当资源包 + 说明） |
| `renderer` / `driver` | 走「本机渲染器」APK 路径，勿打成 UI zip |

### 4.5 直链

- 商店条目需要公网 **HTTPS** 直链（或可自动跳到文件）。
- ≤250MB 也可经启动器申请上架上传到服务器。

---

## 5. HTTP API 摘要

### 公开

- `GET /api/health`
- `GET /api/plugin-types`
- `GET /api/plugins`（可选 `?type=ui`）
- `GET /api/plugins/:id`

### 用户（`Authorization: Bearer <联机 JWT>`）

- `POST /api/applications`  
  Body: `{ "name", "downloadUrl", "description", "type" }`
- `GET /api/applications/mine`
- `GET /api/applications/:id`

### 管理员（JWT 且 userId ∈ `ADMIN_USER_IDS`，或 `X-Admin-Secret`）

- `GET /api/admin/applications?status=pending`
- `POST /api/admin/applications/:id/approve`
- `POST /api/admin/applications/:id/reject` `{ "reason", "sendEmail": true }`
- `POST /api/admin/plugins` 直接上架
- `PATCH|DELETE /api/admin/plugins/:id`
- `GET /api/admin/users/search?query=`（需 Bearer，搜索联机账号作开发者）

---

## 6. 审核与邮件

- 拒绝必须写原因。
- `sendEmail: true` 时向申请时记录的注册邮箱发送说明（依赖服务器 SMTP）。
- 申请人未绑邮箱则跳过发送。

---

## 7. 自检清单

- [ ] 类型选择正确（UI 勿标成 renderer）
- [ ] 直链可在无登录环境下下载
- [ ] renderer/driver：APK 内含对应 ABI 的 `glLib`
- [ ] 操控包：提供 `icon` 或 `buttonStyle`，否则默认近透明难辨认
- [ ] 简介写清安装步骤与兼容启动器版本
- [ ] 不包含恶意代码、挖矿、盗号逻辑
- [ ] 尊重 Minecraft / 第三方库许可证

---

## 8. 联系与支持

审核与上架问题通过启动器联机账号或拒绝邮件中的说明处理。  
服务健康：`https://boonix.art/plugin-api/api/health`
