# Booxin Studio 2.0

微型插件 IDE：编辑项目文件、校验清单、一键打包 zip。

| 文件 | 说明 |
|------|------|
| `BooxinStudio.exe` | 窗口版 IDE（推荐） |
| `BooxinStudio-CLI.exe` | 命令行版 |

## 绿色包下载

[前往下载](https://www.coze.cn/s/4GVr0Tgr2GM/)

## 窗口版

- **未打开项目**：主页显示「打开项目」与最近历史（双击打开）
- **已打开项目**：左侧文件树、中间 Monaco 编辑器（高亮/补全）、右侧 Agent、底部日志
- 顶部工具栏：一键打包 / 校验 / 保存 / 新建模板 / Agent / 检查更新 / 关闭项目
- 快捷键：`Ctrl+O` 打开 · `Ctrl+S` 保存 · `Ctrl+B` 打包 · `Ctrl+N` 新建文件 · `Ctrl+L` Agent

窗口版基于本地 Web IDE（Edge 应用窗口），需联网加载 Monaco CDN（首次）。

## 开发

```bash
cd tools/booxin-studio
npm install
npm run gui          # 窗口版
npm run cli          # 命令行
npm run build        # 生成 dist/*.exe
```

## 命令行示例

```bash
BooxinStudio-CLI.exe new theme
BooxinStudio-CLI.exe pack
BooxinStudio-CLI.exe validate D:\plugins\my-theme
```
