# 当前交接入口

**下载页加载器已齐：Forge / NeoForge / Fabric / Quilt / OptiFine。**

| 加载器 | 安装方式 | 版本 id |
|--------|----------|---------|
| Forge / NeoForge | installer processors（`:forge`） | `{mc}-forge-{ver}` / `{mc}-neoforge-{ver}` |
| Fabric | fabric-meta profile JSON | `{mc}-fabric-{loader}` |
| Quilt | quilt-meta profile JSON | `{mc}-quilt-{loader}` |
| OptiFine | BMCL 下载 + 静默 `optifine.Installer` | `{mc}-optifine-{type}_{patch}` |

详情：NeoForge 见 [HANDOFF_NEOFORGE.md](./HANDOFF_NEOFORGE.md)。

---

## 下一步（建议）

1. 真机验收 Quilt / OptiFine（进标题屏）
2. Forge+OptiFine（装完 OptiFine 后把 jar 当模组丢进 Forge `mods/`，可选）
3. 联机 Tab 大改（低优先级，另开任务）

---

## 历史交接（已过期 / 低优先级）

旧版联机 Tab 重构说明曾写在本文件，内容已过时。若仍需联机 UI 大改，另开任务，勿与加载器混做。
