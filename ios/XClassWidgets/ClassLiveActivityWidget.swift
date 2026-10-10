import ActivityKit
import SwiftUI
import WidgetKit

/// 灵动岛 + 锁屏实时活动：课前 15 分钟出现，显示课程名/节次时间/地点，下课后自动结束。
struct ClassLiveActivityWidget: Widget {
    var body: some WidgetConfiguration {
        ActivityConfiguration(for: ClassActivityAttributes.self) { context in
            // 锁屏 / 通知中心
            VStack(alignment: .leading, spacing: 2) {
                Text(context.attributes.courseName).font(.system(size: 15, weight: .bold))
                Text("\(context.state.sectionText) \(context.state.timeText)")
                    .font(.system(size: 12))
                Text([context.state.campus, context.state.location].filter { !$0.isEmpty }.joined(separator: " · "))
                    .font(.system(size: 11)).opacity(0.8)
            }
            .padding(12)
            .activityBackgroundTint(Color.orange.opacity(0.15))
            .activitySystemActionForegroundColor(.orange)
        } dynamicIsland: { context in
            DynamicIsland {
                DynamicIslandExpandedRegion(.leading) {
                    Text(context.state.sectionText).font(.system(size: 12, weight: .semibold))
                }
                DynamicIslandExpandedRegion(.trailing) {
                    Text(context.state.timeText).font(.system(size: 12))
                }
                DynamicIslandExpandedRegion(.center) {
                    Text(context.attributes.courseName).font(.system(size: 14, weight: .bold)).lineLimit(1)
                }
                DynamicIslandExpandedRegion(.bottom) {
                    Text([context.state.campus, context.state.location].filter { !$0.isEmpty }.joined(separator: " · "))
                        .font(.system(size: 11)).opacity(0.8).lineLimit(1)
                }
            } compactLeading: {
                Image(systemName: "calendar")
            } compactTrailing: {
                Text(context.state.timeText.split(separator: "-").first.map(String.init) ?? "--:--")
                    .font(.system(size: 12, weight: .semibold))
            } minimal: {
                Image(systemName: "calendar.badge.clock")
            }
            .keylineTint(.orange)
        }
    }
}
