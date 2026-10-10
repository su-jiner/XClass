import Foundation
import ActivityKit

/// 灵动岛 / 锁屏实时活动：课前 15 分钟自动开始、下课后自动结束。
/// 说明：iOS 只允许前台（或后台任务命中）启动实时活动，后台不保证准点；设置里提供总开关。
enum LiveActivityManager {
    static let leadMinutes = 15

    /// 在 App 进入前台或数据变化时调用：若 15 分钟内/正在进行有课则开始实时活动，否则结束旧活动。
    static func refresh() {
        guard #available(iOS 16.2, *) else { return }
        guard UserDefaults.standard.object(forKey: "xclass.liveActivityEnabled") as? Bool ?? true else {
            Task { await endAll() }
            return
        }
        let profileStore = ProfileStore.shared
        let courses = profileStore.loadActiveCourses()
        let semesterStart = profileStore.getActiveSemesterStartDate()
        let profiles = profileStore.loadTimeProfiles()
        let now = Date()
        let calendar = Calendar.current
        let weekday = calendar.component(.weekday, from: now)
        let weekdayIndex = weekday == 1 ? 7 : weekday - 1
        let totalWeeks = WeekScheduleCalculator.totalWeeks(of: courses)
        let today = SimpleDate.today()
        let week = WeekScheduleCalculator.guessCurrentWeek(semesterStart: semesterStart, today: today, totalWeeks: totalWeeks)
        let candidates = WeekScheduleCalculator.meetings(inWeek: week, of: courses)
            .filter { $0.meeting.weekday.rawValue == weekdayIndex }
            .compactMap { item -> (course: CourseSchedule, meeting: MeetingTime, start: Date, end: Date)? in
                guard let range = TimeProfileResolver.timeRange(profiles, meeting: item.meeting) else { return nil }
                let start = dateAt(range.start, from: now)
                let end = dateAt(range.end, from: now)
                return (item.course, item.meeting, start, end)
            }
            .sorted { $0.start < $1.start }

        guard let upcoming = candidates.first(where: { candidate in
            candidate.start.timeIntervalSince(now) <= Double(leadMinutes * 60) && candidate.end >= now
        }) else {
            Task { await endAll() }
            return
        }

        let range = TimeProfileResolver.timeRange(profiles, meeting: upcoming.meeting)
        Task {
            await endAll()
            guard ActivityAuthorizationInfo().areActivitiesEnabled else { return }
            let attributes = ClassActivityAttributes(courseName: upcoming.course.courseName)
            let state = ClassActivityAttributes.ContentState(
                location: upcoming.meeting.location,
                campus: profiles.first { $0.id == upcoming.meeting.campusId }?.name ?? "",
                sectionText: "第\(upcoming.meeting.startSection)-\(upcoming.meeting.endSection)节",
                timeText: "\(range?.start ?? "--:--")-\(range?.end ?? "--:--")"
            )
            _ = try? Activity.request(attributes: attributes, content: .init(state: state, staleDate: nil))
        }
    }

    static func endAll() async {
        guard #available(iOS 16.2, *) else { return }
        for activity in Activity<ClassActivityAttributes>.activities {
            await activity.end(nil, dismissalPolicy: .immediate)
        }
    }

    private static func dateAt(_ time: String, from reference: Date) -> Date {
        let parts = time.split(separator: ":").compactMap { Int($0) }
        guard parts.count == 2 else { return reference }
        let calendar = Calendar.current
        return calendar.date(bySettingHour: parts[0], minute: parts[1], second: 0, of: reference) ?? reference
    }
}

/// 实时活动（灵动岛/锁屏）数据模型
struct ClassActivityAttributes: ActivityAttributes {
    public struct ContentState: Codable, Hashable {
        var location: String
        var campus: String
        var sectionText: String
        var timeText: String
    }
    var courseName: String
}
