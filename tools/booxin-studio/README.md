# Booxin Studio

官方插件工具，提供两个可执行文件：

| 文件 | 说明 |
|------|------|
| `BooxinStudio.exe` | 窗口版（推荐） |
| `BooxinStudio-CLI.exe` | 命令行版 |

## 绿色包

官方下载页（解压后双击 `BooxinStudio.exe`，**不需要**本机安装 Node）：

[前往下载](https://www.coze.cn/s/HE-KO21qqfk/)

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
