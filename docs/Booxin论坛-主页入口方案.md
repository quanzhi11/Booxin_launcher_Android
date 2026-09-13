# 主页加论坛入口（推荐方案）

## 为什么这样最好

- **继续用已备案的 `boonix.art`**，论坛挂在 `https://boonix.art/forum/`，一般**不需要新域名、也不必为 www 单独再搞一套备案**。
- **主站 `location /` 只放静态页**；`/bbx`、`/plugin-api`、`/forum` 等路径反代保持不变 → **其它 API 不受影响**。
- 想把论坛做得更「像官网」时，优先打磨 **`/forum/` 站点本身**（桌面布局、品牌、动效），而不是再买/再指新域名。

不推荐：

- 把整个 `www` 或主域根路径全部指到论坛 → 容易误伤其它服务。
- 新开未备案域名只为论坛 → 备案成本高、周期长。

---

## Nginx（保持现状即可）

确保已有论坛反代（与其它 location 并列），例如：

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
    proxy_http_version 1.1;
}

# 主页静态文件（不要改成整站反代到论坛）
location / {
    try_files $uri $uri/ =404;
}
```

访问地址：

- 论坛：`https://boonix.art/forum/`
- 主页：`https://boonix.art/`（在这里放入口链接）

---

## 主页入口卡片（复制到主站 HTML）

把下面整块贴进主站首页合适位置（导航旁或英雄区下方均可）：

```html
<style>
  .bx-forum-entry {
    max-width: 720px;
    margin: 24px auto;
    padding: 20px 22px;
    border: 2px solid #222;
    background: linear-gradient(135deg, #3a2a1a 0%, #5c4033 55%, #6b4f3a 100%);
    color: #f0e6d2;
    box-shadow: 3px 3px 0 #111;
    font-family: "Noto Sans SC", "Segoe UI", sans-serif;
  }
  .bx-forum-entry h2 {
    margin: 0 0 6px;
    font-size: 22px;
    color: #f1c40f;
    font-weight: 600;
  }
  .bx-forum-entry p {
    margin: 0 0 14px;
    font-size: 14px;
    line-height: 1.55;
    color: #e8dcc8;
  }
  .bx-forum-entry a {
    display: inline-block;
    padding: 10px 18px;
    background: #5d8c2e;
    color: #fff;
    text-decoration: none;
    border: 2px solid #222;
    font-weight: 600;
    font-size: 14px;
  }
  .bx-forum-entry a:hover { filter: brightness(1.08); }
</style>

<section class="bx-forum-entry" aria-label="Booxin 论坛">
  <h2>Booxin 论坛</h2>
  <p>讨论启动技巧、联机、插件与反馈。使用 Booxin 账号登录即可发帖、加好友。</p>
  <a href="https://boonix.art/forum/">进入论坛 →</a>
</section>
```

若主站已有导航栏，最少也加一行链接即可：

```html
<a href="https://boonix.art/forum/">论坛</a>
```

---

## 之后把论坛「做漂亮」可以怎么走

都在 **`/forum/` 内迭代**，无需新域名：

1. 桌面端更像独立站点（侧栏、宽版阅读），手机仍底部导航  
2. 品牌首屏（Logo / 一句 slogan / 进入浏览）  
3. 发帖 Markdown、排序、用户主页等已有能力继续打磨体验  

需要时再说，我可以只改论坛前端，不动主站 API 反代。
