# iOS 组件 / 锁屏 / 灵动岛 接入步骤（需要 macOS + Xcode）

本仓库已包含全部 Swift 源码，但 **Widget Extension target 必须在 Xcode 里创建一次**
（我这边没有 Xcode，无法代为生成 target 并验证编译）。步骤如下，约 2 分钟：

## 1. 新建 Widget Extension

1. Xcode 打开 `ios/XClass.xcodeproj`
2. `File → New → Target… → Widget Extension`，名称填 **XClassWidgets**，取消勾选 "Include Configuration App Intent"
3. 删掉模板生成的 `XClassWidgets.swift`，把仓库里这两个文件加入该 target：
   - `ios/XClassWidgets/XClassWidgets.swift`
   - `ios/XClassWidgets/ClassLiveActivityWidget.swift`
4. 该 target 的 `Deployment Info` 设为 **iOS 16.2**（实时活动/灵动岛需要 16.2+）

## 2. 打开 App Group（App 与组件共享课表数据）

1. App target → `Signing & Capabilities` → `+ Capability` → **App Groups** → 新建 `group.cn.xiao.xyaax`
2. Widget target 同样添加 **App Groups** 并勾选同一个 `group.cn.xiao.xyaax`
3. 两个 target 都选好你的 Team（个人 Apple ID 也可，免费账号可本地运行）

## 3. 打开实时活动（灵动岛/锁屏）

1. App target → `Info` → 添加 `NSSupportsLiveActivities` = `YES`
2. `Signing & Capabilities` → `+ Capability` → **Live Activities**（部分 Xcode 版本无需手动添加）

## 4. 运行与验证

1. 运行 App（真机），进入课表页；App 会把数据写入 App Group 容器
2. 长按桌面 → 添加小组件 → XClass：可见 **今日课程 / 下一节课 / 今日时间轴 / 本周课表微缩**
3. 锁屏组件在同一列表的「锁屏」分类里（圆形/矩形/行内）
4. 实时活动：当有课程在 **15 分钟内开始** 时，App 进入前台即会启动灵动岛/锁屏实时活动；下课后自动结束（iOS 限制：后台不能准点启动）

## 说明

- 组件数据全部来自本地 App Group 容器，**不联网**
- 未配置 App Group 时，App 仍可正常使用（数据写在自身沙盒），只是组件读不到数据
