import Foundation

// MARK: - 工具页数据模型与逻辑（与 Android GpaCalculator、小程序 tools.js、鸿蒙 Core.ets 对齐）

struct GpaCourse: Codable, Identifiable, Equatable {
    var id: String { "\(courseName)|\(credit)|\(gradeType)|\(level)|\(score)" }
    var courseName: String
    var credit: Double
    var gradeType: String // "percent" | "level"
    var score: Double
    var level: String
    var included: Bool
}

struct GradeEntry: Codable, Identifiable, Equatable {
    var id: String { "\(courseName)|\(credit)|\(score)|\(semester)" }
    var courseName: String
    var credit: Double
    var score: Double
    var semester: String
}

struct ExamEntry: Codable, Identifiable, Equatable {
    var id: String { "\(courseName)|\(time)|\(location)|\(seat)" }
    var courseName: String
    var time: String
    var location: String
    var seat: String
}

struct ProgressInfo: Codable, Equatable {
    var totalRequired: Double
    var totalEarned: Double
}

struct ProgressModule: Codable, Identifiable, Equatable {
    var id: String { name }
    var name: String
    var required: Double
    var earned: Double
}

struct GradeGroup: Equatable {
    var name: String
    var items: [GradeEntry]
    var credits: Double
    var average: Double
    var count: Int
}

enum ToolsLogic {
    private static let levelScores: [String: Double] = [
        "优秀": 95, "良好": 85, "中等": 75, "及格": 65, "不及格": 50,
    ]

    /// 百分制分数 → 4.0 制绩点（吉林大学档位映射）
    static func gpaOf(score: Double) -> Double {
        if score >= 90 { return 4.0 }
        if score >= 85 { return 3.7 }
        if score >= 82 { return 3.3 }
        if score >= 78 { return 3.0 }
        if score >= 75 { return 2.7 }
        if score >= 72 { return 2.3 }
        if score >= 68 { return 2.0 }
        if score >= 64 { return 1.5 }
        if score >= 60 { return 1.0 }
        return 0
    }

    static func scoreOf(_ course: GpaCourse) -> Double {
        course.gradeType == "level" ? (levelScores[course.level] ?? 0) : course.score
    }

    struct Summary {
        var gpa: String
        var weighted: String
        var arithmetic: String
        var credits: String
    }

    static func summary(of courses: [GpaCourse]) -> Summary {
        var creditSum = 0.0
        var weighted = 0.0
        var gpaSum = 0.0
        var arithmetic = 0.0
        var count = 0
        for course in courses where course.included {
            let score = scoreOf(course)
            let credit = course.credit
            creditSum += credit
            weighted += score * credit
            gpaSum += gpaOf(score: score) * credit
            arithmetic += score
            count += 1
        }
        let fmt = { (value: Double, digits: Int) in
            String(format: "%.\(digits)f", value)
        }
        return Summary(
            gpa: creditSum > 0 ? fmt(gpaSum / creditSum, 2) : "0.00",
            weighted: creditSum > 0 ? fmt(weighted / creditSum, 2) : "0.00",
            arithmetic: count > 0 ? fmt(arithmetic / Double(count), 2) : "0.00",
            credits: fmt(creditSum, 1)
        )
    }

    static func groupBySemester(_ grades: [GradeEntry]) -> [GradeGroup] {
        var order: [String] = []
        var grouped: [String: [GradeEntry]] = [:]
        for grade in grades {
            let key = grade.semester.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                ? "未标注学期" : grade.semester.trimmingCharacters(in: .whitespacesAndNewlines)
            if grouped[key] == nil { grouped[key] = []; order.append(key) }
            grouped[key]?.append(grade)
        }
        return order.map { key in
            let items = grouped[key] ?? []
            var creditSum = 0.0
            var weighted = 0.0
            for item in items {
                creditSum += item.credit
                weighted += item.score * item.credit
            }
            return GradeGroup(
                name: key,
                items: items,
                credits: creditSum,
                average: creditSum > 0 ? weighted / creditSum : 0,
                count: items.count
            )
        }
    }

    /// 最近一场考试倒计时文案
    static func countdownText(for exams: [ExamEntry], now: Date = Date()) -> String {
        if exams.isEmpty { return "暂无考试安排" }
        var upcoming: ExamEntry?
        var upcomingTime = 0.0
        for exam in exams {
            guard let time = parseExamTime(exam.time), time >= now.timeIntervalSince1970 else { continue }
            if upcoming == nil || time < upcomingTime {
                upcoming = exam
                upcomingTime = time
            }
        }
        guard let upcoming, upcomingTime > 0 else { return "考试均已结束" }
        let diff = upcomingTime - now.timeIntervalSince1970
        let days = Int(diff / 86400)
        let hours = Int((diff.truncatingRemainder(dividingBy: 86400)) / 3600)
        return "距离「\(upcoming.courseName)」还有 \(days) 天 \(hours) 小时"
    }

    static func parseExamTime(_ text: String) -> TimeInterval? {
        let pattern = #"(\d{4})-(\d{1,2})-(\d{1,2})[ T]?(\d{1,2})?:?(\d{2})?"#
        guard let match = text.range(of: pattern, options: .regularExpression) else { return nil }
        let s = String(text[match])
        var parts: [Int] = []
        let scanner = Scanner(string: s)
        _ = scanner.scanInt()
        while let value = scanner.scanInt() { parts.append(value) }
        guard parts.count >= 3 else { return nil }
        var comps = DateComponents()
        comps.year = parts[0]
        comps.month = parts[1]
        comps.day = parts[2]
        comps.hour = parts.count > 3 ? parts[3] : 8
        comps.minute = parts.count > 4 ? parts[4] : 30
        return Calendar.current.date(from: comps)?.timeIntervalSince1970
    }

    /// 按成绩累计已修学分（>=60 视为通过）
    static func earnedCredits(from grades: [GradeEntry]) -> Double {
        var sum = 0.0
        for grade in grades where grade.score >= 60 { sum += grade.credit }
        return (sum * 10).rounded() / 10
    }
}
