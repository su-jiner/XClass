import Foundation

/// App 与 Widget/Live Activity 扩展共享数据用的 App Group 容器。
/// 在 Xcode 里给 App 与扩展 target 都勾选 App Groups → `group.cn.xiao.xyaax` 后即生效；
/// 未配置时返回 nil，App 自动回落到自身沙盒（功能不受影响，只是组件读不到数据）。
enum SharedContainer {
    static let appGroupId = "group.cn.xiao.xyaax"

    static var containerURL: URL? {
        FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: appGroupId)
    }

    /// 组件侧读取课表根目录
    static var timetableRoot: URL? {
        guard let base = containerURL else { return nil }
        return base.appendingPathComponent("XClass", isDirectory: true)
    }
}
