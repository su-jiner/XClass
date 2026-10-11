import Foundation

// MARK: - 基础模型（与 Android 端 cn.xiao.xyaax.model 一一对应）

enum Weekday: Int, CaseIterable, Codable {
    case monday = 1, tuesday, wednesday, thursday, friday, saturday, sunday

    var shortLabel: String {
        switch self {
        case .monday: return "一"
        case .tuesday: return "二"
        case .wednesday: return "三"
        case .thursday: return "四"
        case .friday: return "五"
        case .saturday: return "六"
        case .sunday: return "日"
        }
    }

    static func from(chinese: String) -> Weekday? {
        switch chinese {
        case "一": return .monday
        case "二": return .tuesday
        case "三": return .wednesday
        case "四": return .thursday
        case "五": return .friday
        case "六": return .saturday
        case "日", "天": return .sunday
        default: return nil
        }
    }
}

enum WeekParity: String, Codable {
    case all = "ALL"
    case odd = "ODD"
    case even = "EVEN"
}

struct WeekRule: Codable, Hashable {
    var startWeek: Int
    var endWeek: Int
    var parity: WeekParity = .all
}

struct MeetingTime: Codable, Hashable {
    var weekday: Weekday
    var startSection: Int
    var endSection: Int
    var weekRules: [WeekRule]
    var location: String
    /// 校区/楼栋时间表 ID；为空表示使用默认 12 节表
    var campusId: String? = nil
}

struct CourseSchedule: Codable, Hashable, Identifiable {
    var courseName: String
    var teacher: String
    var semester: String
    var credit: Double?
    var rawWeekText: String
    var meetings: [MeetingTime]
    /// 课表级公共备注（非课程级）：每行原样保留，默认空数组。
    /// 旧数据无该字段时解码为 []，不报错。
    var notes: [String] = []

    var id: String {
        "\(courseName)|\(semester)|" + meetings.map {
            "\($0.weekday.rawValue):\($0.startSection)-\($0.endSection)@\($0.location)"
        }.joined(separator: ";")
    }

    enum CodingKeys: String, CodingKey {
        case courseName, teacher, semester, credit, rawWeekText, meetings, notes
    }

    init(courseName: String, teacher: String, semester: String, credit: Double?,
         rawWeekText: String, meetings: [MeetingTime], notes: [String] = []) {
        self.courseName = courseName
        self.teacher = teacher
        self.semester = semester
        self.credit = credit
        self.rawWeekText = rawWeekText
        self.meetings = meetings
        self.notes = notes
    }

    // 宽松解码：旧数据无 notes 字段时默认 []，避免整体解码失败导致课表"消失"
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        courseName = try c.decodeIfPresent(String.self, forKey: .courseName) ?? ""
        teacher = try c.decodeIfPresent(String.self, forKey: .teacher) ?? ""
        semester = try c.decodeIfPresent(String.self, forKey: .semester) ?? ""
        credit = try c.decodeIfPresent(Double.self, forKey: .credit)
        rawWeekText = try c.decodeIfPresent(String.self, forKey: .rawWeekText) ?? ""
        meetings = try c.decodeIfPresent([MeetingTime].self, forKey: .meetings) ?? []
        notes = try c.decodeIfPresent([String].self, forKey: .notes) ?? []
    }
}

// MARK: - 简单日期（语义对齐 java.time.LocalDate，避免时区干扰）

struct SimpleDate: Codable, Hashable, Comparable {
    var year: Int
    var month: Int
    var day: Int

    static let calendar: Calendar = {
        var c = Calendar(identifier: .gregorian)
        c.timeZone = TimeZone(identifier: "UTC")!
        return c
    }()

    var date: Date {
        var comps = DateComponents()
        comps.year = year; comps.month = month; comps.day = day; comps.hour = 12
        return Self.calendar.date(from: comps) ?? Date(timeIntervalSince1970: 0)
    }

    /// 周一=1 ... 周日=7
    var weekdayMonBased: Int {
        let wd = Self.calendar.component(.weekday, from: date) // 1=Sun..7=Sat
        return wd == 1 ? 7 : wd - 1
    }

    var weekday: Weekday { Weekday(rawValue: weekdayMonBased) ?? .monday }

    static func < (lhs: SimpleDate, rhs: SimpleDate) -> Bool {
        (lhs.year, lhs.month, lhs.day) < (rhs.year, rhs.month, rhs.day)
    }

    func days(since other: SimpleDate) -> Int {
        Self.calendar.dateComponents([.day], from: other.date, to: date).day ?? 0
    }

    func adding(days: Int) -> SimpleDate {
        let d = Self.calendar.date(byAdding: .day, value: days, to: date) ?? date
        var c = Self.calendar.dateComponents([.year, .month, .day], from: d)
        return SimpleDate(year: c.year ?? 1970, month: c.month ?? 1, day: c.day ?? 1)
    }

    /// 归一到本周周一（含当天）
    func previousOrSameMonday() -> SimpleDate {
        adding(days: -(weekdayMonBased - 1))
    }

    static func today() -> SimpleDate {
        let now = Calendar.current.dateComponents([.year, .month, .day], from: Date())
        return SimpleDate(year: now.year ?? 1970, month: now.month ?? 1, day: now.day ?? 1)
    }

    static func parse(_ text: String) -> SimpleDate? {
        let parts = text.split(separator: "-").map { Int($0) ?? -1 }
        guard parts.count == 3, parts.allSatisfy({ $0 > 0 }) else { return nil }
        return SimpleDate(year: parts[0], month: parts[1], day: parts[2])
    }

    var isoString: String { String(format: "%04d-%02d-%02d", year, month, day) }

    var displayText: String { String(format: "%d/%d/%d", year, month, day) }
}

// MARK: - 周次计算（与 Android WeekScheduleCalculator 语义一致）

enum WeekScheduleCalculator {
    static let defaultTotalWeeks = 20

    static func totalWeeks(of courses: [CourseSchedule]) -> Int {
        let maxWeek = courses.flatMap { $0.meetings }.flatMap { $0.weekRules }.map { $0.endWeek }.max()
        return max(maxWeek ?? defaultTotalWeeks, 1)
    }

    static func guessCurrentWeek(semesterStart: SimpleDate, today: SimpleDate, totalWeeks: Int) -> Int {
        let days = today.days(since: semesterStart)
        let week = Int(floor(Double(days) / 7.0)) + 1
        return min(max(week, 1), max(totalWeeks, 1))
    }

    static func meetings(inWeek week: Int, of courses: [CourseSchedule]) -> [(courseIndex: Int, course: CourseSchedule, meeting: MeetingTime)] {
        var result: [(Int, CourseSchedule, MeetingTime)] = []
        for (index, course) in courses.enumerated() {
            for meeting in course.meetings where isActive(meeting, inWeek: week) {
                result.append((index, course, meeting))
            }
        }
        return result
    }

    static func isActive(_ meeting: MeetingTime, inWeek week: Int) -> Bool {
        if meeting.weekRules.isEmpty { return true }
        return meeting.weekRules.contains { rule in
            guard week >= rule.startWeek && week <= rule.endWeek else { return false }
            switch rule.parity {
            case .all: return true
            case .odd: return week % 2 == 1
            case .even: return week % 2 == 0
            }
        }
    }

    /// 周视图：非本周课程从被查看周向后寻找下一次上课周（历史周可见已结课课程）
    static func displayMeetings(
        of courses: [CourseSchedule],
        week: Int,
        showNonCurrent: Bool
    ) -> [(courseIndex: Int, course: CourseSchedule, meeting: MeetingTime, isCurrentWeek: Bool, nextActiveWeek: Int)] {
        var result: [(Int, CourseSchedule, MeetingTime, Bool, Int)] = []
        for (index, course) in courses.enumerated() {
            for meeting in course.meetings {
                if isActive(meeting, inWeek: week) {
                    result.append((index, course, meeting, true, week))
                    continue
                }
                guard showNonCurrent, let next = nextActiveWeek(of: meeting, from: week) else { continue }
                result.append((index, course, meeting, false, next))
            }
        }
        return result
    }

    /// 全周视图：返回课表内全部排课（所有周），用于课表管理「查看完整课表」。
    static func allMeetings(
        of courses: [CourseSchedule]
    ) -> [(courseIndex: Int, course: CourseSchedule, meeting: MeetingTime, isCurrentWeek: Bool, nextActiveWeek: Int)] {
        var result: [(Int, CourseSchedule, MeetingTime, Bool, Int)] = []
        for (index, course) in courses.enumerated() {
            for meeting in course.meetings {
                result.append((index, course, meeting, true, firstActiveWeek(of: meeting)))
            }
        }
        return result
    }

    static func firstActiveWeek(of meeting: MeetingTime) -> Int {
        if meeting.weekRules.isEmpty { return 1 }
        return nextActiveWeek(of: meeting, from: 1) ?? 1
    }

    static func nextActiveWeek(of meeting: MeetingTime, from week: Int) -> Int? {
        if meeting.weekRules.isEmpty { return week }
        guard let maxEnd = meeting.weekRules.map({ $0.endWeek }).max() else { return nil }
        guard week <= maxEnd else { return nil }
        for w in week...maxEnd where isActive(meeting, inWeek: w) {
            return w
        }
        return nil
    }
}
