# QQ图片提取工具

把 QQ 里的**动图表情**导出来，落到相册 `Pictures/QQ表情/`。
可预览、可勾选、只导你要的。不联网、不上传、不改 QQ 任何数据。

> 作者：**星球卑_PHb**

现成产物：`/sdcard/Download/QQ图片提取工具-3.0.apk`（v3.0，v2 签名，minSdk 26 / targetSdk 34）

---

## 一、用起来

> v4.0 起支持两端：顶部「QQ / 微信 / 两边都扫」三选一，默认 QQ。微信为实验性支持。

1. 装 **Shizuku**：<https://github.com/RikkaApps/Shizuku/releases>
   开发者选项 → 打开「无线调试」→ Shizuku 里配对 → 启动。
2. 打开本 App，**首次启动会弹出「使用须知」**（含"可能扫到一些你不需要的文件"的说明），点「我已了解」。
3. 点「授权」→ 允许；点「扫描」；**点一行勾选、长按一行播放动图**；点「导出选中」。

导出位置：相册 `Pictures/QQ表情/`。

| 按钮 | 作用 |
|---|---|
| 全选 / 全不选 / 清空 | 批量勾选、清空当前结果（不删文件） |
| 诊断 | 打印 shell 身份、各候选目录是否存在与文件数 |
| 目录 | 增删扫描路径 |
| 导出清单 | 把候选目录每个文件的类型/大小/时间写成 CSV（排查用） |

---

## 二、原理

QQ 把表情缓存在
`/sdcard/Android/data/com.tencent.mobileqq/Tencent/MobileQQ/.emotionsm/<表情包id>/<md5>`，
文件**没有后缀**，且**前 24 字节中下标 1/3/5… 的字节被 XOR 了 1**。

App 用 Shizuku 的 shell 身份（uid 2000）只读打开这些文件，还原文件头后按 GIF/APNG/WebP 识别，
排除 `_thu.png`（缩略图）、`_aio.png`（合集图）、`.jtmp`（元数据），按内容哈希去重后写入相册。

代码：`Scanner.detect/decodeHeader`、`Exporter`。

---

## 三、界面：Material You（莫奈）

- **动态取色**：Android 12+ 读系统从壁纸生成的 Monet 色板
  （`android.R.color.system_accent1_*` / `system_neutral1_*` / `system_neutral2_*`），
  跟随你的壁纸变色调；低版本回退到 Material 3 默认紫。全部颜色集中在 `Monet.kt`。
- **M3 结构**：主色块状态卡、surfaceVariant 圆角卡片、filled/tonal 两级按钮、
  选中行用 secondaryContainer 高亮、无重阴影（靠色块和圆角表达层级）。
- **图标**：纯色底 + 白色小星球与行星环，极简两笔画（`ic_launcher_foreground.xml`）。

---

## 四、构建

- 常规：`./build-local.sh`、`Dockerfile`、GitHub Actions
- 极简（无需 Gradle/Android SDK，实测可用）：`./build-ondevice.sh`
  只需 JDK 21 + kotlinc + aapt2 + R8 + apksig + zip

---

## 五、已知边界

- 微信：新版把聊天媒体放在应用内部存储，shell 读不到；外置目录 com.tencent.mm/MicroMsg 与旧版 tencent/MicroMsg 里能读到的表情会正常导出


- 「我的表情」这类**收藏**存在 QQ 内部数据库（需 root 或走 QQ 界面转发），缓存里只有"显示过"的表情
- 不解析 QQ 数据库、不解密私有容器格式
- 不联网、不收集信息
