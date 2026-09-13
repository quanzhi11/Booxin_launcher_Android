# 从零开发 Booxin Launcher 插件（UI / 操控 / 渲染器）

Booxin Launcher 支持第三方插件扩展界面、虚拟按键和渲染器。本文说明插件有哪些类型、怎么打包、怎么本地测试、怎么申请上架。

相关入口：

- 开发者文档：<https://boonix.art/plugin-api/developer/>
- 完整字段表：[`server/plugin-marketplace/docs/PLUGIN_DEVELOPER.md`](../server/plugin-marketplace/docs/PLUGIN_DEVELOPER.md)
- 插件 API 基址：<https://boonix.art/plugin-api>
- 大文件 / 审核沟通 QQ：`1018364788`（zrr）

---

## 一、先搞清：你要做哪一类插件？

| 类型 | 交付形式 | 装到哪 | 典型用途 |
|------|----------|--------|----------|
| `ui` | zip（含 `booxin-plugin.json`） | 本机插件 | 主题、字体、壁纸、自定义页面 |
| `control` | zip | 本机插件 | 游戏内虚拟按键布局（可多面板） |
| `utility` | zip | 本机插件 | 滑动过渡等小功能 |
| `renderer` / `driver` | APK（含 `.so`） | 本机渲染器 | GL/Vulkan/ANGLE 等原生库 |
| `input` / `overlay` / `pack` / `other` | zip | 本机插件 | 可安装管理，部分尚无独立运行时钩子 |

**注意：**

- UI / 操控不要标成 `renderer`。
- 渲染器不要打成 UI zip，走 APK +「本机渲染器」。
- zip 插件真正起作用的是清单里的 `features`，商店里的 `type` 只是分类标签。

---

## 二、最快上手：做一个 UI 主题包

### 1. 目录结构示例

```text
my-theme/
  booxin-plugin.json
  icon.png
  fonts/MyFont.ttf
  backgrounds/bg.png
```

### 2. 写 `booxin-plugin.json`

```json
{
  "schema": 1,
  "id": "my-ui-theme-dark",
  "name": "暗色主题",
  "version": "1.0.0",
  "type": "ui",
  "minLauncher": "5.3.6",
  "description": "示例主题：改配色、壁纸和主页文案",
  "launcherIcon": "icon.png",
  "font": "fonts/MyFont.ttf",
  "features": {
    "customTheme": true,
    "homeGreetingByTime": true
  },
  "theme": {
    "brandName": "MyLauncher",
    "welcomeMorning": "早上好",
    "welcomeNoon": "中午好",
    "welcomeEvening": "晚上好",
    "homeLaunchText": "开始游戏",
    "hideOrbs": true,
    "backgroundImage": "backgrounds/bg.png",
    "backgroundVideo": "backgrounds/loop.mp4",
    "backgroundAlpha": 0.85,
    "colors": {
      "primary": "#449AD8",
      "accent": "#FF8A2B",
      "text": "#F2F6FF",
      "textSecondary": "#B4C0D4",
      "surface": "#141A26"
    },
    "nav": {
      "home": "主页",
      "versions": "版本",
      "community": "社区",
      "multiplayer": "联机",
      "ai": "AI",
      "pluginStore": "插件",
      "settings": "设置"
    }
  }
}
```

### 3. 常用 `features`

| 字段 | 作用 |
|------|------|
| `customTheme` | 完整主题（色/壁纸图或视频/文案/导航名等）；写了 `theme` 也会自动开启 |
| `homeGreetingByTime` | 主页按时段问候 |
| `home3dModel` | 首页欢迎区显示当前账号 3D 皮肤 |
| `customFont` | 自定义界面字体 |
| `customLauncherIcon` | 自定义主页品牌图标 |
| `pageSlideTransitions` | 顶部导航与进出游戏左右滑动 |
| `controlLayout` | 游戏内虚拟按键（需布局文件） |
| `customPages` | 自定义 Web 页面（或非空 `pages` 自动开启） |
| `jsCommands` | 声明 JS 桥（启用时需用户授权） |

### 4. 打包与安装

1. 把整个文件夹打成 **zip**（根目录直接有 `booxin-plugin.json`，不要多套一层空目录）。
2. 启动器 → **本机插件 → 导入 zip**。
3. 启用后看主页/主题是否生效。

也可用 **Booxin Studio** 新建 / 编辑后一键打包。

---

## 三、进阶：虚拟按键（`control`）

清单打开 `features.controlLayout`，并带上布局文件（默认 `control_layout.json`）。

### 1. 清单字段

| 字段 | 说明 |
|------|------|
| `layoutFile` | 主面板布局 JSON（默认 `control_layout.json`） |
| `extraLayouts` | 额外面板：`[{ "id", "name", "file" }]`；游戏内操控菜单右侧「插件面板」可切换 |

示例：

```json
{
  "schema": 1,
  "id": "my-control-pack",
  "name": "我的操控包",
  "version": "1.0.0",
  "type": "control",
  "features": { "controlLayout": true },
  "layoutFile": "control_layout.json",
  "extraLayouts": [
    { "id": "build", "name": "建造", "file": "control_layout_build.json" }
  ]
}
```

每个面板有独立的本机用户存档（`control_layout.json` / `control_layout_<id>.json`）。

### 2. 布局格式（`version: 4`）

可含：`buttons` / `joystick` / `floatingBall` / `gestureQuick` / `buttonStyle`。

**绘制优先级：** `icon` 贴图 → `style`/`css` → **启动器默认近透明圆形底**。

> 无插件贴图/样式时，系统默认按键几乎透明（淡描边 + 低透明度），避免挡画面。  
> 做主题操控包时请提供 `icon` 或 `buttonStyle`，否则用户只会看到很淡的文字轮廓。

`buttons[].kind`：

| kind | 说明 |
|------|------|
| `KEY_TAP` | 抬起点击一次 |
| `KEY_HOLD` / `MOUSE_HOLD` | 按住；手指滑出后交给触控板转视角 |
| `KEY_FOLLOW` / `MOUSE_FOLLOW` | 按住时按钮跟手移动并保持按下，松开回弹原位 |
| `KEY_TOGGLE` / `MOUSE_TOGGLE` | 点击闩住按下，再点松开 |
| `SOFT_KEYBOARD` | 软键盘；布局未写时启动器会自动补一个 |
| `SCROLL` | 滚轮（`code` ≥0 为上） |

其它常用字段：

| 字段 | 说明 |
|------|------|
| `id` | 稳定唯一；用于与用户本机位置合并 |
| `label` | 按钮上的显示名；用户可在编辑模式「重命名」覆盖 |
| `code` / `codes` | GLFW 键码；`codes` ≥2 为组合键；也支持 FCL 特殊码 `-1`…`-5` |
| `outputText` / `text` | 非空时点击注入聊天/命令文本（可 `code: 0`） |
| `x` / `y` | 中心相对父布局 `0..1` |
| `sizeDp` | 边长 dp，约 `36..160` |
| `icon` | 相对插件目录的 png/webp/jpg |
| `style` / `css` | 颜色/圆角/渐变/`transparent`/`opacity`/`pressEffect` 等 |

`joystick.follow`：**默认 `false`**。为 `true` 时按下移到指下、拖出外圈底盘追手、松开回弹；游戏内编辑可选中轮盘后点「跟手」开关。

### 3. 与用户本机布局的合并

- 插件：贴图、样式、默认文案。
- 用户：按键集合、坐标、大小、跟手、重命名后的 `label`。
- 多个 `controlLayout` 同时启用时，按扫描顺序取**第一个**。

无插件时的系统默认文案偏**具体按键名**（如「左键」「右键」「Shift」），「跳」与移动轮盘除外。插件仍可自定义任意 `label`。

### 4. 游戏内相关 UX（给作者参考）

悬浮球 → **操控设置**：陀螺仪 / 联机 / 社区 / 自定义布局 / 隐藏按键 / 退出；有 `extraLayouts` 时右侧单独一列切换插件面板。  
编辑栏：添加 / 删除 / **重命名** / 跟手 / 缩放 / 恢复默认 / 完成。

### 5. 示例片段

```json
{
  "version": 4,
  "buttonStyle": {
    "shape": "oval",
    "backgroundColor": "#AA2A6FA8",
    "borderColor": "#88FFFFFF",
    "borderWidth": 2,
    "opacity": 0.92,
    "pressEffect": "jelly"
  },
  "buttons": [
    {
      "id": "jump",
      "label": "跳",
      "kind": "KEY_HOLD",
      "code": 32,
      "x": 0.90,
      "y": 0.70,
      "sizeDp": 64
    },
    {
      "id": "lmb_follow",
      "label": "左键·跟手",
      "kind": "MOUSE_FOLLOW",
      "code": 0,
      "x": 0.88,
      "y": 0.55,
      "sizeDp": 58
    }
  ],
  "joystick": { "x": 0.13, "y": 0.80, "sizeDp": 150, "follow": false }
}
```

更完整字段表见开发者站 HTML 与 `PLUGIN_DEVELOPER.md`。

---

## 四、自定义页面 + JS 桥（可选）

适合做说明页、工具页、论坛壳等**启动器 UI 层**页面（不是游戏 JVM/SDL 底层）。

清单示例：

```json
{
  "id": "booxin.sample.pages",
  "name": "示例·自定义页面",
  "version": "1.0.0",
  "type": "ui",
  "features": {
    "jsCommands": true,
    "customPages": true
  },
  "pages": [
    {
      "id": "demo",
      "title": "示例页",
      "entry": "pages/index.html"
    },
    {
      "id": "forum",
      "title": "论坛",
      "url": "https://example.com/forum",
      "orientation": "portrait",
      "fullscreen": true
    }
  ]
}
```

`pages[]` 字段：

| 字段 | 说明 |
|------|------|
| `id` / `title` | 页面标识与顶栏标题 |
| `entry` / `html` / `file` | 插件目录内相对 HTML |
| `url` / `href` / `remote` | 可选；仅 **https**，有则直接加载远程页 |
| `orientation` | `portrait` / `landscape`（也认竖屏/横屏） |
| `fullscreen` | 沉浸式，隐藏部分页面铬 |

页面在 WebView 中打开。若用户授权了 `jsCommands`，可注入 `BooxinPlugin`：

- `getInfo()`
- `toast(msg)`
- `openUrl(https)`
- `navigate(home|versions|community|multiplayer|ai|pluginstore|plugins|settings)`
- `close()`

**没有**：shell、任意读写文件、NativeJvm / SDL / 注入按键。请勿指望用插件改游戏底层。

---

## 五、渲染器 / 驱动（原生）

交付推荐：含 `lib/<abi>/*.so` 的 APK（可无 Activity）。

`plugin.json` 示例：

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
  "extraEnv": {
    "LIBGL_ES": "3"
  }
}
```

- **ABI**：至少 `arm64-v8a`，可选 `armeabi-v7a`
- Manifest 可加：
  - `booxin.plugin=renderer`
  - `org.lwjgl.opengl.libname=libmygl.so`

装进「本机渲染器」，不要和 UI zip 混用。

---

## 六、如何上架插件商店

1. 准备 **HTTPS 公开直链**，或 ≤250MB 在启动器里直接上传。
2. 启动器 → **插件 → 申请上传**（需登录联机账号）：名称、类型、简介、直链/文件。
3. 管理员审核通过 / 拒绝（拒绝会写原因，可能发邮件）。
4. 通过后用户可在商店下载；带 `booxin-plugin.json` 的 zip 进「本机插件」，渲染器 APK 进「本机渲染器」。

>250MB 建议加 QQ `1018364788` 私聊提交。

---

## 七、上架前自检

- [ ] 类型选对了（UI ≠ renderer）
- [ ] zip 根目录就有 `booxin-plugin.json`
- [ ] 直链无登录也能下
- [ ] renderer：APK 里有对应 ABI 的 `glLib`
- [ ] 操控包：有 `icon` 或 `buttonStyle`，否则默认近透明
- [ ] 简介写清安装步骤、兼容启动器版本
- [ ] 无恶意代码、挖矿、盗号
- [ ] 尊重 Minecraft / 第三方库许可证

---

## 八、小结

做 Booxin 插件可以很轻：

1. **UI 主题**：一个 json + 几张图/字体（可加壁纸视频），打 zip 导入即可。
2. **操控**：布局 json + 可选贴图/样式；可用 `extraLayouts` 做多面板。
3. **页面**：本地 HTML 或 https 远程壳 + 可选 JS 桥。
4. **渲染器**：APK 塞 so + `plugin.json`。

先本地「导入 zip / 本机渲染器」测通，再申请上架。有问题看开发者页或走审核沟通渠道。

欢迎在评论区分享你的主题包 / 按键布局截图，互相交流～
