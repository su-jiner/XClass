import Foundation
import WidgetKit

/// 课表/时间表数据变化后刷新桌面与锁屏组件（组件自身也有 30 分钟系统刷新兜底）。
enum WidgetRefresher {
    static func reload() {
        WidgetCenter.shared.reloadAllTimelines()
    }
}
