# 交接文档 — 联机页重构 + 离线联机 + 头像修复

## 项目
Android Minecraft 启动器：`D:\all code in D\booxin launcher phone`
PC 参考版：`D:\all code in D\booxin-launcher2.3.0`（含 `bphone` 子目录）

## 已完成

### 1. 头像 URL 解析（刚完成，未编译验证）
- **新增** `core/multiplayer/MultiplayerAvatarUrlHelper.kt` — 与 PC `MultiplayerAvatarUrlHelper.cs` 对齐
  - API 返回 `avatars/xxx.webp` 等相对路径 → 拼成 `https://boonix.art/bbx/api/auth/avatars/xxx.webp`
  - 已有绝对 URL 直接用；空值返回 null（用 placeholder）
- **新增** `ui/multiplayer/MultiplayerAvatarLoader.kt` — 扩展函数 `ImageView.loadBooxinAvatar(url)`
- **已改** `MultiplayerUserAdapter.kt` — 列表项头像改用 `loadBooxinAvatar`
- **未改** `MultiplayerFragment.kt` 里 `renderSession` 的 `imageSessionAvatar.load(user.avatarUrl)` 也需换成 `loadBooxinAvatar`

### 2. 离线联机支持
- `HomeFragment.prepareLaunchAccount()` — 已去掉「离线号禁止进房」拦截，改为 Toast 提示
- `strings.xml` — `multiplayer_join_ok` / `multiplayer_offline_join_hint` 已更新：说明房主需装 mcwifipnp 关正版验证
- 离线启动参数已对齐 PC（`OfflineAuth.kt` UUID、`accessToken=0`、`userType=legacy`）

### 3. strings.xml Tab 名已更新
- `multiplayer_tab_rooms` → 房间
- `multiplayer_tab_friends` → 好友
- `multiplayer_tab_lobby` → 大厅（**新增**）
- `multiplayer_tab_messages` → 消息
- `multiplayer_tab_account` → 我的
- 新增好友子 Tab 字符串：`multiplayer_friends_sub_friends/requests/invites/blocked`

---

## 待完成（按优先级）

### A. 联机页 Tab 重构（核心）
**目标**：对齐 bphone `(tabs)/_layout.tsx`，5 个顶级 Tab：**房间 / 好友 / 大厅 / 消息 / 我的**

当前状态：4 个 Tab（房间 / 好友 / 消息 / 账号），好友页里塞了搜索、邀请、申请、好友列表、黑名单、大厅所有内容。

**需要做的**：

1. **fragment_multiplayer.xml** — 在 FrameLayout 里新增 `pageLobby`（大厅页），包含搜索框 + recyclerSearch + recyclerLobby
2. **pageFriends** — 内部加一组 sub-tabs（好友 / 申请 / 邀请 / 黑名单），参考 bphone `friends.tsx` 的 `TAB_CONFIG`
   - 好友列表、申请列表、邀请列表、黑名单各自一个 RecyclerView，按 sub-tab 切换显示
   - 搜索功能移到大厅页
3. **MultiplayerFragment.kt**：
   - `setupTabs()` 改为 5 个 Tab
   - `showPage()` 加 index==2 → pageLobby, index==3 → pageMessages, index==4 → pageAccount
   - 好友页加 sub-tab 逻辑
   - `refreshFriends()` 里 lobby 相关部分移到独立 `refreshLobby()` 在大厅页调用
4. **renderSession** 里 `imageSessionAvatar.load(...)` 改成 `imageSessionAvatar.loadBooxinAvatar(...)`

### B. 自动安装 LAN 联机模组（可选，后续）
PC 端 `GameService.InstallLanServerPropertiesAsync()` 在启动前自动装 mcwifipnp（Fabric only）。
Android 端可以同样在 `GameLaunchService.runLaunch()` 里检查 Fabric 版本 → 从 Modrinth API 下载 mcwifipnp 到 mods 目录。
- Modrinth slug: `mcwifipnp`，API: `https://api.modrinth.com/v2/project/mcwifipnp/version?game_versions=["1.21.1"]&loaders=["fabric"]`
- mods 目录: `LauncherPaths.versionsDir/{versionId}/mods/`

### C. 编译安装
改完后 `./gradlew.bat :app:assembleDebug` 编译，`adb install` 推到设备。

---

## 关键文件速查
| 用途 | 路径 |
|------|------|
| 联机页布局 | `app/src/main/res/layout/fragment_multiplayer.xml` |
| 联机页逻辑 | `app/src/main/java/.../ui/multiplayer/MultiplayerFragment.kt` |
| 列表 Adapter | `app/src/main/java/.../ui/multiplayer/MultiplayerUserAdapter.kt` |
| 列表项布局 | `app/src/main/res/layout/item_multiplayer_user.xml` |
| 头像 URL 解析 | `app/src/main/java/.../core/multiplayer/MultiplayerAvatarUrlHelper.kt` |
| 头像加载扩展 | `app/src/main/java/.../ui/multiplayer/MultiplayerAvatarLoader.kt` |
| API | `app/src/main/java/.../core/multiplayer/BooxinMultiplayerApi.kt` |
| 字符串 | `app/src/main/res/values/strings.xml` |
| PC 头像参考 | `booxin-launcher2.3.0/Services/Multiplayer/MultiplayerAvatarUrlHelper.cs` |
| bphone Tab 参考 | `booxin-launcher2.3.0/bphone/app/(tabs)/_layout.tsx` |
| bphone 好友参考 | `booxin-launcher2.3.0/bphone/app/(tabs)/friends.tsx` |
| bphone 头像参考 | `booxin-launcher2.3.0/bphone/lib/avatar-url.ts` |
| 启动入口 | `app/src/main/java/.../ui/home/HomeFragment.kt` |
| 离线 UUID | `app/src/main/java/.../core/launch/OfflineAuth.kt` |
