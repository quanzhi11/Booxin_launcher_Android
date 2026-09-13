# Booxin 论坛更新说明

> 服务：`server/booxin-forum`（端口 `5025`，线上路径 `/forum/`）  
> 部署包：`server/booxin-forum/booxin-forum-deploy.zip`  
> 日期：2026-08-12

---

## 本次更新概览

| 功能 | 说明 |
| --- | --- |
| 用户主页 | 点击头像/用户名可查看他人主页 |
| 账号注册 | 支持邮箱验证码注册（与启动器同一 Booxin 账号） |
| Markdown 发帖 | 支持 MD 语法、表格插入与预览 |
| 列表排序 | 默认 / 最新 / 热度 |
| 长文折叠 | 列表过长内容默认折叠，可展开 |

---

## 1. 用户主页

- 在帖子列表、帖子详情、评论、粉丝/关注、通知中，点击用户头像或用户名进入主页。
- 主页展示：头像、昵称、粉丝 / 关注 / 帖子数、TA 的帖子列表。
- 他人主页可一键关注 / 取消关注。
- 点击自己会跳转到「我的」页。

**相关接口**

- `GET /api/users/:userId/profile`  
  返回用户公开资料、统计、是否已关注、是否本人。

---

## 2. 注册

- 登录页增加「没有账号？立即注册」。
- 注册字段：用户名、密码、邮箱、邮箱验证码。
- 验证码与注册对接官方接口（与启动器一致）：
  - `POST https://boonix.art/bbx/api/auth/register/send-code`
  - `POST https://boonix.art/bbx/api/auth/register`
- 注册成功后自动登录。

---

## 3. Markdown 发帖（含表格与预览）

- 发帖页内容支持 Markdown（GFM）。
- 工具栏：粗体、斜体、标题、代码、链接、列表、**表格**、引用。
- 「编辑 / 预览」切换，发布前可查看渲染效果。
- 列表与详情中的帖子内容按 Markdown 渲染（含表格、代码块、列表等）。
- 前端使用 `marked` + `DOMPurify` 渲染并做 XSS 过滤。

**表格示例**

```md
| 列1 | 列2 | 列3 |
| --- | --- | --- |
| A   | B   | C   |
```

也可直接点工具栏「表格」插入模板。

---

## 4. 帖子排序

首页工具栏新增排序切换：

| 选项 | 规则 |
| --- | --- |
| 默认 | 综合排序：互动热度随时间衰减（兼顾新鲜度） |
| 最新 | 按发布时间倒序 |
| 热度 | 按互动量：`点赞×2 + 评论×3`，同热度再比时间 |

**相关接口**

- `GET /api/posts?sort=default|latest|hot`  
  可与 `category`、`q`、`userId`、`page` 等参数组合使用。

---

## 5. 长文折叠

- 列表中内容过长时默认折叠（约 220px 高度），底部渐隐。
- 显示「展开全文」；展开后可「收起」。
- 点击展开按钮不会误进详情；点卡片其他区域仍进入帖子详情。
- 实际高度未超限的卡片会自动去掉折叠按钮。

---

## 部署

1. 上传并解压 `booxin-forum-deploy.zip` 覆盖服务目录。
2. 重启进程：

```bash
pm2 restart booxin-forum
```

3. 浏览器强刷缓存后访问：`https://boonix.art/forum/`

**Nginx 提醒（若尚未配置）**

```nginx
location ^~ /forum/ {
    client_max_body_size 25m;
    proxy_request_buffering off;
    proxy_pass http://127.0.0.1:5025/;
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
    proxy_set_header X-Forwarded-Prefix /forum;
}
```

---

## 建议自测清单

- [ ] 未登录 → 注册（发验证码 → 注册并登录）
- [ ] 发帖：插入表格 → 预览 → 发布，列表/详情渲染正常
- [ ] 首页切换「默认 / 最新 / 热度」，顺序变化符合预期
- [ ] 长帖列表折叠 / 展开 / 收起正常
- [ ] 点击他人头像进入主页，可关注
- [ ] 点击自己头像进入「我的」
