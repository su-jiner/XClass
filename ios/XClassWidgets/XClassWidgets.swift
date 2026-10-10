import WidgetKit
import SwiftUI

/**
 * XClass 四件套组件（主屏 + 锁屏 / 灵动岛）。
 * 与 Android 端一致：今日课程列表、下一节课（含倒计时）、今日时间轴、本周课表微缩。
 * 数据来自 App Group 容器（group.cn.xiao.xyaax/XClass），与本 App 完全共享；
 * 全部本地读取，不联网。
 *
 * 该文件需要放进 Xcode 里新建的 Widget Extension target（名字建议 XClassWidgets），
 * 具体步骤见仓库 ios/WIDGET_SETUP.md。
 */

struct TimetableEntry: TimelineEntry {
    var date: Date
    var subtitle: String
    var items: [WidgetCourse]
    var next: WidgetCourse?
}

struct WidgetCourse: Identifiable {
    var id: String { "\(courseName)-\(startSection)-\(startTime)" }
    var courseName: String
    var location: String
    var campus: String
    var startSection: Int
    var endSection: Int
    var startTime: String
    var endTime: String
    var isOngoing: Bool
}

struct TimetableProvider: TimelineProvider {
    func placeholder(in context: Context) -> TimetableEntry {
        TimetableEntry(date: Date(), subtitle: "第1周", items: [], next: nil)
    }

    func getSnapshot(in context: Context, completion: @escaping (TimetableEntry) -> Void) {
        completion(loadEntry())
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<TimetableEntry>) -> Void) {
        let entry = loadEntry()
        // 30 分钟系统刷新；课前/课后由 App 侧主动刷新
        let next = Calendar.current.date(byAdding: .minute, value: 30, to: Date()) ?? Date().addingTimeInterval(1800)
        completion(Timeline(entries: [entry], policy: .after(next)))
    }

    private func loadEntry() -> TimetableEntry {
        WidgetDataReader.snapshot()
    }
}

/// 阅读 App Group 里的课表并算出组件展示所需的今日数据（与 App 内规则一致）
enum WidgetDataReader {
    static func snapshot() -> TimetableEntry {
        guard let root = SharedContainer.timetableRoot,
              let metaData = try? Data(contentsOf: root.appendingPathComponent("meta.json")),
              let meta = try? JSONDecoder().decode(WidgetMeta.self, from: metaData),
              let active = meta.profiles.first(where: { $0.id == meta.activeId }) ?? meta.profiles.first,
              let courseData = try? Data(contentsOf: root.appendingPathComponent(active.coursesFile)),
              let courses = try? JSONDecoder().decode([WidgetCourseRecord].self, from: courseData) else {
            return TimetableEntry(date: Date(), subtitle: "未共享数据", items: [], next: nil)
        }

        let semesterStart = SemesterStartDatePolicy.parse(active.semesterStartDate) ?? SimpleDate.today()
        let totalWeeks = WidgetMath.totalWeeks(courses)
        let today = SimpleDate.today()
        let week = WidgetMath.currentWeek(semesterStart: semesterStart, today: today, totalWeeks: totalWeeks)
        let weekday = today.weekdayMonBased
        let profiles = loadTimeProfiles(root)

        let items = courses.flatMap { course -> [WidgetCourse] in
            course.meetings
                .filter { $0.weekday == weekday && WidgetMath.isActive(meeting: $0, week: week) }
                .map { meeting in
                    let range = WidgetMath.timeRange(profiles: profiles, campusId: meeting.campusId, start: meeting.startSection, end: meeting.endSection)
                    let ongoing = WidgetMath.isOngoing(range: range)
                    return WidgetCourse(
                        courseName: course.courseName,
                        location: meeting.location,
                        campus: profiles.first { $0.id == meeting.campusId }?.name ?? "",
                        startSection: meeting.startSection,
                        endSection: meeting.endSection,
                        startTime: range?.start ?? "--:--",
                        endTime: range?.end ?? "--:--",
                        isOngoing: ongoing
                    )
                }
        }.sorted { $0.startSection < $1.startSection }

        let next = items.first { !$0.isOngoing } ?? items.first
        return TimetableEntry(
            date: Date(),
            subtitle: "第\(week)周 · 周\(["一", "二", "三", "四", "五", "六", "日"][max(0, min(6, weekday - 1))])",
            items: items,
            next: next
        )
    }

    private static func loadTimeProfiles(_ root: URL) -> [WidgetTimeProfile] {
        guard let data = try? Data(contentsOf: root.appendingPathComponent("time_profiles.json")),
              let stored = try? JSONDecoder().decode(WidgetTimeProfileFile.self, from: data) else { return [] }
        return stored.profiles
    }
}

struct WidgetMeta: Codable {
    var activeId: String
    var profiles: [WidgetProfileMeta]
}

struct WidgetProfileMeta: Codable {
    var id: String
    var name: String
    var coursesFile: String
    var semesterStartDate: String?
}

struct WidgetCourseRecord: Codable {
    var courseName: String
    var teacher: String?
    var meetings: [WidgetMeeting]
}

struct WidgetMeeting: Codable {
    var weekday: Int
    var startSection: Int
    var endSection: Int
    var location: String
    var campusId: String?
    var weekRules: [WidgetWeekRule]
}

struct WidgetWeekRule: Codable {
    var startWeek: Int
    var endWeek: Int
    var parity: String
}

struct WidgetTimeProfile: Codable {
    var id: String
    var name: String
    var blocks: [WidgetBlock]
}

struct WidgetBlock: Codable {
    var startSection: Int
    var endSection: Int
    var startTime: String
    var endTime: String
}

struct WidgetTimeProfileFile: Codable {
    var profiles: [WidgetTimeProfile]
}

enum WidgetMath {
    static func totalWeeks(_ courses: [WidgetCourseRecord]) -> Int {
        let weeks = courses.flatMap { $0.meetings.flatMap { $0.weekRules.map { $0.endWeek } } }
        return max(weeks.max() ?? 20, 1)
    }

    static func currentWeek(semesterStart: SimpleDate, today: SimpleDate, totalWeeks: Int) -> Int {
        let start = semesterStart.toDate()
        let current = today.toDate()
        let days = Calendar.current.dateComponents([.day], from: start, to: current).day ?? 0
        if days < 0 { return 1 }
        return min(days / 7 + 1, totalWeeks)
    }

    static func isActive(meeting: WidgetMeeting, week: Int) -> Bool {
        if meeting.weekRules.isEmpty { return true }
        return meeting.weekRules.contains { rule in
            guard week >= rule.startWeek && week <= rule.endWeek else { return false }
            if rule.parity == "ODD" { return week % 2 == 1 }
            if rule.parity == "EVEN" { return week % 2 == 0 }
            return true
        }
    }

    static func timeRange(profiles: [WidgetTimeProfile], campusId: String?, start: Int, end: Int) -> (start: String, end: String)? {
        guard let profile = profiles.first(where: { $0.id == campusId }) ?? profiles.first else { return nil }
        let startBlock = profile.blocks.first { start >= $0.startSection && start <= $0.endSection }
        let endBlock = profile.blocks.first { end >= $0.startSection && end <= $0.endSection } ?? startBlock
        guard let startBlock, let endBlock else { return nil }
        return (startBlock.startTime, endBlock.endTime)
    }

    static func isOngoing(range: (start: String, end: String)?) -> Bool {
        guard let range else { return false }
        let formatter = DateFormatter()
        formatter.dateFormat = "HH:mm"
        let now = formatter.string(from: Date())
        return range.start <= now && now < range.end
    }
}

// MARK: - 主屏组件

struct TodayCoursesWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "XClassTodayCourses", provider: TimetableProvider()) { entry in
            TodayCoursesView(entry: entry)
        }
        .configurationDisplayName("今日课程")
        .description("今天要上的课：节次时间、校区与地点")
        .supportedFamilies([.systemSmall, .systemMedium, .systemLarge])
    }
}

struct NextCourseWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "XClassNextCourse", provider: TimetableProvider()) { entry in
            NextCourseView(entry: entry)
        }
        .configurationDisplayName("下一节课")
        .description("课程名、时间与倒计时")
        .supportedFamilies([.systemSmall, .accessoryCircular, .accessoryRectangular, .accessoryInline])
    }
}

struct TodayTimelineWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "XClassTodayTimeline", provider: TimetableProvider()) { entry in
            TodayTimelineView(entry: entry)
        }
        .configurationDisplayName("今日时间轴")
        .description("按节次纵向排列，进行中高亮")
        .supportedFamilies([.systemMedium, .systemLarge])
    }
}

struct WeekGridWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "XClassWeekGrid", provider: TimetableProvider()) { entry in
            WeekGridWidgetView(entry: entry)
        }
        .configurationDisplayName("本周课表微缩")
        .description("今日课程缩略（点击进入 App 查看完整课表）")
        .supportedFamilies([.systemLarge])
    }
}

// MARK: - 视图

struct TodayCoursesView: View {
    let entry: TimetableEntry

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(entry.subtitle).font(.system(size: 12, weight: .semibold))
            if entry.items.isEmpty {
                Text("今天没有课程安排").font(.system(size: 12))
            } else {
                ForEach(entry.items.prefix(5)) { item in
                    VStack(alignment: .leading, spacing: 1) {
                        HStack(spacing: 4) {
                            Text(item.courseName).font(.system(size: 12, weight: .medium)).lineLimit(1)
                            if item.isOngoing {
                                Text("进行中").font(.system(size: 9)).padding(.horizontal, 4)
                                    .background(Capsule().fill(Color.orange.opacity(0.25)))
                            }
                        }
                        Text("第\(item.startSection)-\(item.endSection)节 \(item.startTime)-\(item.endTime)\(item.location.isEmpty ? "" : " · \(item.location)")")
                            .font(.system(size: 10)).opacity(0.8).lineLimit(1)
                    }
                }
            }
            Spacer(minLength: 0)
        }
        .containerBackground(for: .widget) { Color(.systemBackground) }
    }
}

struct NextCourseView: View {
    let entry: TimetableEntry
    @Environment(\.widgetFamily) private var family

    var body: some View {
        switch family {
        case .accessoryCircular:
            VStack(spacing: 0) {
                Text(entry.next?.startTime ?? "--:--").font(.system(size: 11, weight: .bold))
                Text("下一节").font(.system(size: 8))
            }
        case .accessoryRectangular:
            VStack(alignment: .leading, spacing: 1) {
                Text(entry.next?.courseName ?? "今天没有课程").font(.system(size: 12, weight: .semibold)).lineLimit(1)
                Text(entry.next.map { "\($0.startTime)-\($0.endTime) · \($0.location)" } ?? "").font(.system(size: 10)).lineLimit(1)
            }
        case .accessoryInline:
            Text(entry.next.map { "\($0.courseName) \($0.startTime)" } ?? "今天没有课程")
        default:
            VStack(alignment: .leading, spacing: 4) {
                Text("下一节课").font(.system(size: 11)).opacity(0.8)
                if let next = entry.next {
                    Text(next.courseName).font(.system(size: 15, weight: .bold)).lineLimit(1)
                    Text("第\(next.startSection)-\(next.endSection)节 \(next.startTime)-\(next.endTime)")
                        .font(.system(size: 11))
                    Text([next.campus, next.location].filter { !$0.isEmpty }.joined(separator: " · "))
                        .font(.system(size: 11)).opacity(0.8).lineLimit(1)
                } else {
                    Text("今天没有课程安排").font(.system(size: 12))
                }
                Spacer(minLength: 0)
            }
        }
    }
}

struct TodayTimelineView: View {
    let entry: TimetableEntry

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(entry.subtitle).font(.system(size: 12, weight: .semibold))
            ForEach(entry.items.prefix(6)) { item in
                HStack(spacing: 8) {
                    VStack(alignment: .leading, spacing: 0) {
                        Text(item.startTime).font(.system(size: 11, weight: .bold))
                        Text(item.endTime).font(.system(size: 9)).opacity(0.7)
                    }
                    .frame(width: 44, alignment: .leading)
                    VStack(alignment: .leading, spacing: 1) {
                        Text(item.courseName).font(.system(size: 12)).lineLimit(1)
                        Text("第\(item.startSection)-\(item.endSection)节 \(item.location)")
                            .font(.system(size: 10)).opacity(0.75).lineLimit(1)
                    }
                    Spacer(minLength: 0)
                    if item.isOngoing {
                        Text("进行中").font(.system(size: 9)).padding(.horizontal, 4)
                            .background(Capsule().fill(Color.orange.opacity(0.25)))
                    }
                }
            }
            Spacer(minLength: 0)
        }
    }
}

struct WeekGridWidgetView: View {
    let entry: TimetableEntry

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("\(entry.subtitle) · 今日课程").font(.system(size: 12, weight: .semibold))
            if entry.items.isEmpty {
                Text("今天没有课程安排").font(.system(size: 12))
            } else {
                ForEach(entry.items) { item in
                    HStack {
                        Text("\(item.startTime)").font(.system(size: 11, weight: .medium)).frame(width: 42, alignment: .leading)
                        Text(item.courseName).font(.system(size: 12)).lineLimit(1)
                        Spacer(minLength: 0)
                        Text(item.location).font(.system(size: 10)).opacity(0.7).lineLimit(1)
                    }
                }
            }
            Spacer(minLength: 0)
        }
    }
}

@main
struct XClassWidgetsBundle: WidgetBundle {
    var body: some Widget {
        TodayCoursesWidget()
        NextCourseWidget()
        TodayTimelineWidget()
        WeekGridWidget()
        if #available(iOS 16.2, *) {
            ClassLiveActivityWidget()
        }
    }
}
