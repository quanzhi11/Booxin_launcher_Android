# Booxin 皮肤库（skin-api）

独立后端，评论/评分/举报流程对齐插件市场。

## 本地启动

```bash
cd server/skin-marketplace
cp .env.example .env
npm install
npm run dev
```

默认端口 `5021`。管理后台：`http://127.0.0.1:5021/admin/`（审核页可打开 3D 穿戴预览）。

## Nginx 反代建议

```nginx
location ^~ /skin-api/ {
  proxy_pass http://127.0.0.1:5021/;
  proxy_set_header Host $host;
  proxy_set_header X-Real-IP $remote_addr;
  client_max_body_size 4m;
}
```

公开基址：`https://boonix.art/skin-api`

## 主要 API

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/skins` | 已上架列表（`q`/`model`） |
| GET | `/api/skins/:id` | 详情 |
| POST | `/api/skins/:id/download` | 记一次下载并返回 textureUrl |
| PUT | `/api/skins/:id/rating` | 评分（需登录） |
| POST | `/api/skins/:id/comments` | 评论 |
| POST | `/api/skins/:id/report` | 举报皮肤 |
| POST | `/api/applications/upload` | 用户上传申请（multipart PNG） |
| POST | `/api/skins/:id/release` | 作者更新版本 |
| POST | `/api/admin/applications/:id/approve` | 审核通过（含 3D 预览入口） |
