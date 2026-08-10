# Booxin 插件市场 API

独立 Node 服务：用户申请上传 → 管理员审核上架；**下载链接为直链，不经本服务中转**。

## 启动

```bash
cd server/plugin-marketplace
cp .env.example .env
# 编辑 .env：ADMIN_USER_IDS、SMTP_*、可选 ADMIN_SECRET
npm install
npm start
```

- API：`http://127.0.0.1:5020/api/...`
- 开发者文档：`http://127.0.0.1:5020/developer/`
- 管理后台：`http://127.0.0.1:5020/admin/`

建议 Nginx 反代到 `https://boonix.art/plugin-api/`（与现有 `/bbx`、`/eco`、`/ai-api` 一致）。

上传限制 250MB，Nginx 需加大请求体，例如：

```nginx
location ^~ /plugin-api/ {
    client_max_body_size 260m;
    proxy_request_buffering off;
    proxy_pass http://127.0.0.1:5020/;
    # ...其余 header 同其它 location
}
```

## 鉴权

| 角色 | 方式 |
|------|------|
| 用户申请 | `Authorization: Bearer <联机 JWT>`（走 Auth `validate`） |
| 管理员 | 后台密码登录：`POST /api/admin/login`；或联机管理员账号密码；脚本可用 `X-Admin-Secret` |
| 公开目录 | 无鉴权 `GET /api/plugins` |

后台登录账号二选一：
1. `.env` 的 `ADMIN_USERNAME` / `ADMIN_PASSWORD`
2. 联机账号密码，且 `userId` 在 `ADMIN_USER_IDS`

开发者搜索依赖联机 Auth；本地密码登录时可另配 `BOOXIN_AUTH_USERNAME` / `BOOXIN_AUTH_PASSWORD`。

## 主要接口

- `GET /api/plugins` 已上架列表（`?type=ui` 可筛选）
- `GET /api/plugin-types` 类型字典（含 UI / 操控等）
- `POST /api/applications` 提交申请
- `GET /api/applications/mine` 我的申请
- `POST /api/admin/applications/:id/approve` 通过并上架（可改名/开发者/链接/简介）
- `POST /api/admin/applications/:id/reject` `{ reason, sendEmail }` 拒绝并可发邮件
- `POST /api/admin/plugins` 直接上架

类型：`renderer` `driver` `ui` `control` `input` `overlay` `utility` `pack` `other`。

数据文件：`data/marketplace.json`。  
开发文档：`docs/PLUGIN_DEVELOPER.md`。
