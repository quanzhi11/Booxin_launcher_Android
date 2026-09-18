BooxinGlues 1.0.0 — source package
==================================

结构参考 OpenREL 一类渲染器源码包：顶层 README / LICENSE / NOTICE，
下面是可对接启动器的编排源码与协议文本。

这是什么
--------
BooxinGlues：Minecraft 26.3+（SDL 窗口）用的渲染选项。
启动器侧接线 + 预编译 Mesa OSMesa（运行时 GALLIUM_DRIVER=zink）。

不是从 Mesa 源码里单独抠出的「纯 Zink 工程」；也不是 MobileGlues。
不替换 REL / MCrender / GL4ES / MobileGlues。

目录
----
  LICENSE                 Booxin 编排（Apache-2.0）
  NOTICE                  第三方摘要
  README.md               本文件
  licenses/               Mesa MIT 等全文
  integration/java/       启动器 Kotlin 接线
  integration/cpp/        Vulkan / SDL 钩子（booxin_bridge 相关）
  prebuilt/arm64-v8a/     libOSMesa_25.so（可选随包；也可从启动器 jniLibs 取）

怎么用
------
1. 把 prebuilt/.../libOSMesa_25.so 放进启动器
   app/src/main/jniLibs/arm64-v8a/
2. 合并 integration 里的 Java/C 到对应包路径（或直接用本启动器仓库）
3. 分发 APK 时保留 licenses/MESA_MIT_LICENSE.txt

上游
----
Mesa: https://gitlab.freedesktop.org/mesa/mesa
Booxin 接线：本包 integration/ + 启动器仓库 com.booxin.launcher
