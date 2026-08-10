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
| `renderer` | 渲染器 | GL/Vulkan/ANGLE 等 `.so` | 下载 APK 并解压到本机插件目录 |
| `driver` | 驱动 | GPU/驱动相关原生库 | 同上 |
| `ui` | UI 插件 | 启动器界面扩展、主题、皮肤 | 打开直链（浏览器/下载器） |
| `control` | 操控 / 虚拟按键 | 触控布局、手柄映射 | 打开直链 |
| `input` | 输入法 / 键鼠 | 输入相关辅助 | 打开直链 |
| `overlay` | 悬浮窗 / HUD | 游戏内叠加层 | 打开直链 |
| `utility` | 工具 | 日志、诊断等 | 打开直链 |
| `pack` | 整合包附属 | 配套资源/补丁 | 打开直链 |
| `other` | 其他 | 未分类 | 打开直链 |

查询类型列表：`GET /api/plugin-types`

---

## 2. 上架流程

1. 开发者准备 **公开直链**（HTTPS，建议网盘/对象存储/GitHub Release）。
2. 在启动器「插件 → 申请上传」提交：名称、类型、直链、简介（需登录联机账号）。
3. 管理员在后台审核：
   - **通过**：可改名称/类型/简介/直链，并选择开发者（Booxin 联机账号）后上架。
   - **拒绝**：必须填写原因；可一键发到申请人注册邮箱。
4. 上架后出现在 `GET /api/plugins`，用户点击下载走直链。

也可由管理员在后台「直接上架」，无需事先申请。

---

## 3. 渲染器 / 驱动（原生插件）规范

适用于 `renderer` / `driver`，兼容 FCL 风格。

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
<!-- 或 FCL 兼容 -->
<meta-data android:name="fclPlugin" android:value="renderer" />
<meta-data android:name="org.lwjgl.opengl.libname" android:value="libmygl.so" />
```

包名建议包含 `fcl` + `renderer` / `plugin` / `driver` 以便启发式发现。

### 3.4 ABI

至少提供设备常用 ABI：`arm64-v8a`（必需）、可选 `armeabi-v7a`。  
启动器按当前 ABI 从 APK 的 `lib/<abi>/` 提取。

---

## 4. UI / 操控 / 其他插件规范

适用于 `ui`、`control`、`input`、`overlay`、`utility`、`pack`、`other`。

当前启动器版本：商店内点击后 **直接打开下载链接**（由系统浏览器或下载器处理）。  
后续版本可能支持一键导入到固定目录；现在请在简介里写清安装方式。

### 4.1 建议目录约定（给用户说明）

| 类型 | 建议安装位置 / 说明 |
|------|---------------------|
| ui | 主题/皮肤包：说明解压到启动器数据目录或由后续导入功能识别 |
| control | 提供布局 JSON/配置文件；说明导入路径 |
| overlay | 独立 APK 或资源包；说明是否需额外权限 |
| utility | 工具 APK 或脚本；说明用途与风险 |
| pack | 与整合包版本号对应；勿把大文件挂到不可用的外链 |

### 4.2 可选清单文件 `booxin-plugin.json`（放在压缩包根目录）

便于未来客户端自动识别：

```json
{
  "schema": 1,
  "id": "my-ui-theme-dark",
  "name": "暗色主题",
  "version": "1.0.0",
  "type": "ui",
  "minLauncher": "0.0.2",
  "entry": "theme.json",
  "description": "示例 UI 主题包",
  "homepage": "https://example.com",
  "author": "YourName"
}
```

### 4.3 直链要求

- 必须公网可访问的 **HTTPS** 直链（或可自动跳转到文件的链接）。
- 禁止把文件上传到 Booxin 插件 API 服务器（本服务不存插件二进制）。
- 链接失效会导致商店无法下载，请保持维护。

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
- [ ] 简介写清安装步骤与兼容启动器版本
- [ ] 不包含恶意代码、挖矿、盗号逻辑
- [ ] 尊重 Minecraft / 第三方库许可证

---

## 8. 联系与支持

审核与上架问题通过启动器联机账号或拒绝邮件中的说明处理。  
服务健康：`https://boonix.art/plugin-api/api/health`
