import Foundation

/// XClass 专属课表分享文本（Share Text v1）：与 Android ShareTextCodec / 鸿蒙 / 小程序完全一致。
/// 导出格式可直接粘贴进任意端的「粘贴文本导入」，无需新解析器。
enum ShareTextCodec {
    static let header = "# XClass 课表分享 v1"

    private static let weekdayLabels: [Weekday: String] = [
        .monday: "星期一", .tuesday: "星期二", .wednesday: "星期三",
        .thursday: "星期四", .friday: "星期五", .saturday: "星期六",
        .sunday: "星期日",
    ]

    /// 生成分享文本。
    /// - Parameters:
    ///   - campusIdToAlias: 校区时间表 ID → 校区简称（用于还原分享文本里的（乌）前缀）
    static func encode(
        courses: [CourseSchedule],
        profileName: String? = nil,
        semester: String? = nil,
        semesterStart: SimpleDate? = nil,
        totalWeeks: Int? = nil,
        campusIdToAlias: [String: String] = [:]
    ) -> String {
        var lines: [String] = []
        lines.append(header)
        if let profileName, !profileName.isEmpty { lines.append("# 课表：\(profileName)") }
        if let semester, !semester.isEmpty { lines.append("# 学期：\(semester)") }
        if let semesterStart { lines.append("# 学期开始：\(semesterStart.isoString)") }
        if let totalWeeks, (1...60).contains(totalWeeks) { lines.append("# 总周数：\(totalWeeks)") }

        var rows: [String] = []
        for course in courses {
            for meeting in course.meetings {
                guard let day = weekdayLabels[meeting.weekday] else { continue }
                let sections = "\(meeting.startSection)-\(meeting.endSection)节"
                let weeks = course.rawWeekText.isEmpty
                    ? CourseTableImporter.weekRulesText(meeting.weekRules)
                    : course.rawWeekText
                let alias = meeting.campusId.flatMap { campusIdToAlias[$0] }
                var location = ""
                if let alias, !alias.isEmpty { location += "（\(alias)）" }
                location += meeting.location.trimmingCharacters(in: .whitespaces)
                if location.isEmpty { location = "-" }
                let teacher = course.teacher.trimmingCharacters(in: .whitespaces)
                rows.append("\(day) \(course.courseName)/(\(sections))\(weeks)/\(location)/\(teacher.isEmpty ? "-" : teacher)")
            }
        }
        rows = Array(Set(rows)).sorted()
        lines.append(contentsOf: rows)
        return lines.joined(separator: "\n") + "\n"
    }
}
