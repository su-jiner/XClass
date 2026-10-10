# XClass

本地课表应用，开源、无广告、**全程离线**（不访问任何外部服务）。支持 Android / iOS / 鸿蒙 / 微信小程序 四端（采用"多端轻量移植"架构，Android 为主版本，详见 [docs/PORTING.md](docs/PORTING.md)）。

## 项目简介

XClass 是一个纯本地课表工具：手动录入课表、管理多份课表、查看今日课程，并提供可切换的主题外观与深色模式。所有数据只保存在本机，支持 JSON 备份与恢复。

## 安装包下载

- Android: 从 [GitHub Releases](https://github.com/su-jiner/XClass/releases) 下载正式安装包。打 `v*` tag 时 GitHub Actions 自动用仓库 Secret 中的签名凭据构建签名 APK（本仓库不提交任何密码/密钥文件）。
- iOS / 鸿蒙：源码位于 `ios/` 与 `harmonyos/` 目录，需自行用 Xcode / DevEco Studio 构建签名（构建说明见 PORTING.md）。
- 微信小程序：`miniprogram/` 目录，用微信开发者工具导入后预览/上传。

## 效果截图

| 日程 | 课表 | 设置 |
| -- | -- | -- |
| ![](media/t1.png) | ![](media/t2.png) | ![](media/t3.png) |

## 主要功能

- 多校区节次时间表
	- 一个学校/校区/楼栋可各配一套节次时间（如 真理道·教学楼 10:25-12:00、真理道·实训楼 10:10-11:30）
	- 今日页、桌面组件、上课提醒与课表时间列都按**课程所属校区**取时间；可在设置页管理，也可粘贴「XX校区上课时间」文本自动识别
- 课表导入（离线）
	- 课表页「+」→ 手动添加 / 导入课表文件 / 粘贴文本导入
	- Android 支持 PDF（文本层优先，扫描件回退离线 OCR）、图片（ML Kit 中文离线识别）、txt/md/csv/tsv、docx/xlsx
	- iOS 支持 txt/md/csv/tsv、图片（Vision 中文识别）、PDF（文本层）；鸿蒙与小程序支持粘贴文本与 txt/md/csv/tsv
	- 图片识别内置噪声过滤：教务页面的表头、列头、页脚（如「其他课程：」「注一…」「打印时间…」「XX年第N学期 XX课表」）不会被误识别成课程
	- 导入结果一律先预览校对（可逐条删除）再选择「覆盖当前课表 / 新建课表」
- 课表分享（四端互通）
	- 任意端可导出「XClass 课表分享文本」（`# XClass 课表分享 v1` 开头），粘贴给朋友后在任意端「粘贴文本导入」即可还原课程、节次、周次、地点与校区
	- 粘贴文本弹窗带「导出」按钮，Android/iOS 走系统分享，鸿蒙/小程序复制到剪贴板
- 桌面与锁屏组件
	- Android：今日课程、下一节课、今日时间轴、本周课表微缩（同时声明锁屏组件，Android 15+/16+ 可见）
	- iOS：同四件套（WidgetKit）+ 锁屏 accessory 组件 + **灵动岛/锁屏实时活动**（课前 15 分钟开始、下课结束），接入步骤见 [ios/WIDGET_SETUP.md](ios/WIDGET_SETUP.md)
	- 鸿蒙：四张服务卡片（FormKit）+ 实况窗（LiveViewKit）
	- 微信小程序：无桌面组件（平台限制），改为小程序内今日提醒 + 写入手机日历 + 添加到桌面
- 多课表管理
	- 课表管理页可查看所有课表
	- 支持切换当前课表、重命名、删除、新建空课表
- 手动添加课程
	- 可设置课程名、教师、地点、星期、节次、周次、单双周
- 今日课程视图
	- 展示今日课程列表、课程数量、最早/最晚上课时间
	- 时间轴卡片式排版，正在进行的课程带"进行中"标识
- 桌面小组件
	- 在桌面直接查看今日课程与当前节次
- 上课提醒
	- 每日固定时间推送当日课程提醒，时间可自定义，重启后自动恢复（默认关闭）
- 数据安全
	- 存储写入原子化 + 并发加锁，元数据损坏自动从课程文件恢复
	- 课表文本导出 / 全量备份与恢复（JSON）
- 工具页（底部导航，Android 与微信小程序已上线；iOS 与鸿蒙在 v1.1.0-Beta 起补齐）
	- 绩点计算器：手动录入成绩（百分制 / 五级制）；实时估算保研绩点（4.0 制）、加权平均分与算术平均分，逐门可计入/排除，重修同课程号自动取最高分（原生改造自 [DailyPotato/JLU-GPA-Calculator](https://github.com/DailyPotato/JLU-GPA-Calculator) 与 [Coldymemos/JLU-GPA-Calculator-for-Windows-Desktop](https://github.com/Coldymemos/JLU-GPA-Calculator-for-Windows-Desktop)，已获原作者同意）
	- 成绩查询：手动录入/编辑成绩，按学期汇总，与绩点计算器双向互通
	- 考试安排：手动添加考试、考场与倒计时
	- 学业完成：手动编辑培养方案指标，或按本地成绩估算学分达成度
- 个性化设置
	- 主题色切换（暖色、海蓝、薄荷），支持深色模式（跟随系统/浅色/深色）
	- 默认打开页面（Android/小程序；iOS 与鸿蒙在 v1.1.0-Beta 起补齐）
	- 课表字号三档（小号 / 标准 / 大号）
	- 是否显示非本周课程
	- 自定义背景（选择与裁剪；iOS/鸿蒙在 v1.1.0-Beta 起补齐）、置顶封面（Android/小程序；iOS/鸿蒙同步补齐）
- 课程详情
	- 上拉浮动卡片展示教师 / 地点 / 周次 / 学期，点击条目一键复制

## 技术栈

- Android (Kotlin, Material Components)
- iOS (SwiftUI, UNUserNotification 提醒)
- 鸿蒙 (ArkUI, reminderAgentManager 提醒)
- Kotlinx Serialization / Swift Codable / ArkTS（四端统一备份格式，当前版本 v2，兼容读取 v1）
- Gradle (KTS) / Xcode / DevEco Studio

## 项目结构

- `app/src/main/java/cn/xiao/xyaax/`
	- `ui/`：课表页、今日课程页、设置页、工具页（成绩/绩点/考试/学业）、主题
	- `data/`：偏好设置、课表持久化、多课表元数据、备份编解码
	- `domain/`：周次推算、绩点计算等纯逻辑（JUnit 覆盖）
	- `update/`：应用内更新链路骨架（下载/验签/安装），更新源当前为空
	- `widget/`：今日课程桌面小组件
	- `reminder/`：每日课程提醒
- `ios/`：iOS 版（SwiftUI，核心逻辑与 Android 逐函数对齐，XCTest 覆盖）
- `harmonyos/`：鸿蒙版（ArkUI，核心逻辑可用 `node verify/run.mjs` 在任意平台回归）
- `miniprogram/`：微信小程序版（页面与 Android 对齐，核心逻辑可用 `node miniprogram/verify/run.mjs` 回归）
- `docs/PORTING.md`：四端同步开发指南与核心逻辑对应表
- `app/src/main/assets/`
	- `target.url`：校内导入入口
	- `VPN.url`：校外导入入口

## 更新日志
- v1.1.0-Beta
	- 课表页只显示本周课程（四端），移除「显示非本周课程」开关与非本周占位卡
	- 课程详情新增「编辑课程」（四端）：修改课程名/教师/地点/节次/周次后保存，修改同步到所有周
	- 课表管理新增「查看完整课表」（四端）：所有周的课程在同一页面展示（与课表页样式一致），点击课程可编辑/删除，修改同步所有周
	- 重新设计页面切换动画：统一 160-180ms、小位移、高不透明度，消除卡顿与页面重叠
	- 修复微信小程序 wxss 编译错误（inset 降级四边）与提醒开关误绑，小程序课表管理新增完整课表入口
	- 图片识别课表全面优化：四端一致的噪声过滤（教务表头/列头/页脚「其他课程」「注一」「打印时间」等不再被识别为课程），iOS 图片识别改为按坐标重建表格
	- 新增 XClass 专属课表分享文本（`# XClass 课表分享 v1`）：四端可导出/导入，朋友之间粘贴即可互换课表；粘贴弹窗增加「导出」按钮
	- 微信小程序课表网格改为与移动端一致的圆角长条卡片样式
	- 更新链路启用（设置页可见更新检查；Android 更新源部署页 `deploy/android-update/` 正式启用）
	- iOS 与鸿蒙补齐工具页（绩点/成绩/考试/学业）与个性化设置（自定义背景/置顶封面/默认打开页）
	- 许可证改为 MIT；签名密码迁 GitHub Actions Secret，仓库不再包含任何密钥文件；绑定 Git remote `su-jiner/XClass`
## 快速开始

1. 使用 Android Studio 打开仓库根目录。
2. 等待 Gradle Sync 完成。
3. 运行 `app` 模块到模拟器或真机。

> 应用不访问任何外部服务，首次启动课表为空：在课表页右上角手动添加课程，或从设置页"恢复备份"导入之前导出的 JSON。

命令行构建：

```bash
./gradlew assembleDebug
```

Windows PowerShell：

```powershell
.\gradlew.bat assembleDebug
```

## 使用说明

1. 打开课表页，点击右上角「+」。
2. 添加课程可任选：手动添加 / 粘贴课表文本 / 导入课表文件（txt/md/csv/tsv、Android 另支持 PDF 与图片、iOS 另支持图片与 PDF）。
3. 导入结果先预览校对（可逐条删除），再选择「覆盖当前课表 / 新建课表」。
4. 想和朋友互换课表：在「粘贴文本」弹窗点「导出课表分享文本」，把生成文本发给对方；对方在任意端粘贴该文本导入即可。
5. 多份课表可在「课表管理页」切换、重命名、删除；JSON 备份在设置页导出与恢复。
6. 可在设置页进入「课表管理页」切换或整理课表。

> 应用不访问任何外部服务（离线）；首次启动课表为空，用上述任一方式导入即可。

## 开发说明

- 仓库已配置 `.gitignore`，默认忽略构建产物与本地环境文件。
- 提交前建议执行：

```bash
./gradlew assembleDebug
```

## 许可证

本项目采用 MIT License，许可证全文见 [LICENSE](LICENSE)。

Copyright (c) 2026 Xyao (su-jiner)

项目使用的第三方组件保留各自的许可证与版权声明。

## 免责声明

本项目仅用于学习与个人效率提升，请遵守学校相关系统使用规范与法律法规。
