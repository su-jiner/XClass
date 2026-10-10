# 三端同步开发指南（Android / iOS / HarmonyOS）

本项目采用「多端轻量移植」架构：**Android 是参考实现（source of truth）**，iOS（SwiftUI）、鸿蒙（ArkUI）与微信小程序（WXML/JS）各自移植同一套核心逻辑。所有端均为纯本地应用：不访问任何外部服务，课表来自手动添加、离线导入与 JSON 备份恢复。

> 小程序端仓库入口：`miniprogram/`（测试号 AppID，纯离线导入：粘贴文本 + 聊天文件 txt/md/csv/tsv；发布需正式 AppID）。回归脚本：`node miniprogram/verify/run.mjs`。

## 目录结构

```
app/                          # Android 主版本（Kotlin）
  src/main/java/cn/xiao/xyaax/
    domain/WeekScheduleCalculator.kt  # 周次计算
    data/SemesterStartDatePolicy.kt
    data/ScheduleBackupCodec.kt
    data/ImportedScheduleStorage.kt   # 多课表存储
    update/                           # 更新链路骨架（更新源当前为空）
ios/                          # iOS 版（SwiftUI，目标 iOS 16+）
  XClass/Core/                 # 与 Android 一一对应的核心移植
  XClass/App/                  # SwiftUI 界面
  XClassTests/                 # XCTest（用例与 Android 对齐）
  XClass.xcodeproj
harmonyos/                    # 鸿蒙版（ArkUI，API 12+）
  entry/src/main/ets/core/          # Core.ets / ProfileStore.ets（纯逻辑，可 Node 验证）
  entry/src/main/ets/pages/         # ArkUI 界面
  entry/src/ohosTest/               # hypium 单元测试
  verify/run.mjs                    # Windows/Node 上直接验证核心逻辑
docs/PORTING.md               # 本文件
```

## 核心逻辑对应表

| 逻辑 | Android | iOS | 鸿蒙 |
|---|---|---|---|
| 数据模型 | `model/ScheduleModels.kt` | `Core/Models.swift` | `core/Core.ets` |
| 周次计算 | `domain/WeekScheduleCalculator.kt` | `Core/Models.swift: WeekScheduleCalculator` | `Core.ets: guessCurrentWeek/displayMeetings/...` |
| 学期日期 | `data/SemesterStartDatePolicy.kt` | `Core/SemesterStartDatePolicy.swift` | `Core.ets: inferFromSemesterLabelOrNull/...` |
| 备份格式 | `data/ScheduleBackupCodec.kt`（version 1） | `Core/ImportParser.swift: ScheduleBackupCodec` | `Core.ets: encodeBackup/decodeBackup` |
| 多课表存储 | `data/ImportedScheduleStorage.kt` | `Core/ProfileStore.swift` | `core/ProfileStore.ets` |
| 节次时间表（多校区） | `model/TimeProfile.kt` + `data/TimeProfileStore.kt` | `Core/TimeProfile.swift` | `Core.ets: SectionTimeProfile/timeRangeOf/resolveProfile` |
| 课表导入解析 | `importing/`（单元格语法 + 表格重建 + 时间表识别） | `Core/CourseTableImporter.swift` | `Core.ets: parseCourseTableText` |
| 备份格式版本 | **v2**（含 `timeProfiles` 与 `campusId`，兼容 v1 读取） | v2 | v2 |
| 桌面/锁屏组件 | 4 个 AppWidgetProvider（含 keyguard） | WidgetKit 四件套 + 锁屏 accessory + 灵动岛实时活动（`ios/WIDGET_SETUP.md`） | 4 张 FormKit 服务卡片 + 实况窗 |

三端的备份文件格式（version 1 JSON）与 meta.json + courses_*.json 存储布局完全一致，可以互相恢复对方导出的备份。

## 修改核心逻辑时的同步流程

1. 先在 Android 端实现并补单元测试（`gradlew testDebugUnitTest`）。
2. 同步移植到 iOS（`ios/XClass/Core/` + `ios/XClassTests/`）与鸿蒙
   （`harmonyos/entry/src/main/ets/core/` + `harmonyos/entry/src/ohosTest/`）。
3. 鸿蒙端在本机即可回归：`cd harmonyos && node verify/run.mjs`（无需 DevEco）。
4. iOS 端在 Xcode 按 Cmd+U 运行 XCTest。

## 构建与运行

### Android
```bash
./gradlew assembleDebug          # 输出 app/build/outputs/apk/debug
```

### iOS（需要 macOS + Xcode 16+）
```bash
open ios/XClass.xcodeproj   # 选择个人签名 Team 后运行
# 若工程文件打开异常，可用 XcodeGen 重建：
# brew install xcodegen && cd ios && xcodegen
```
- Bundle ID：`cn.xiao.xyaax.ios`；最低 iOS 16。
- 应用图标由仓库根目录的 `favicon.png` 生成（`python scripts/generate_icons.py`，产物在 `XClass/Assets.xcassets/AppIcon.appiconset`）。
- 每日提醒用 UNCalendarNotificationTrigger 的 7 天滚动窗口（每次启动重排）。

### 鸿蒙（需要 DevEco Studio 5+ / API 12）
```bash
# DevEco 打开 harmonyos/ 目录，签名后运行到真机/模拟器
# 核心逻辑回归（任何平台）：
cd harmonyos && node verify/run.mjs
```
- Bundle Name：`cn.xiao.xyaax.harmony`。
- 应用图标同样由 `favicon.png` 生成（三处 `resources/base/media/app_icon.png`）。
- 每日提醒用 `reminderAgentManager` 日历提醒的 7 天滚动窗口。

## 已知的端差异（有意为之）

- **iOS 默认启动页**固定课表页（无 Android 的"默认打开页"设置）。
- **自定义背景图**：仅 Android 支持（UCrop 依赖）；iOS/鸿蒙暂未实现。
- **小组件**：iOS 版暂未实现 WidgetKit 扩展；鸿蒙端暂未实现卡片（JS 卡片/ArkTS 卡片可后续加）。

## 版本策略

三端版本号保持一致（当前 1.0.0-Beta）。发版时同步更新：
- Android：`app/build.gradle.kts` 的 versionCode/versionName
- iOS：`project.pbxproj` 中 MARKETING_VERSION / CURRENT_PROJECT_VERSION
- 鸿蒙：`AppScope/app.json5` 的 versionCode/versionName
