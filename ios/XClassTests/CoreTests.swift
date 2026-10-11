import XCTest
@testable import XClass

/// 与 Android 端 WeekScheduleCalculatorTest / SemesterStartDatePolicyTest /
/// ScheduleBackupCodecTest 用例一一对应
final class CoreTests: XCTestCase {

    // MARK: 周次计算

    private func course(_ rules: [WeekRule], weekday: Weekday = .monday) -> CourseSchedule {
        CourseSchedule(courseName: "测试课程", teacher: "老师", semester: "2026-2027-1",
                       credit: nil, rawWeekText: "custom",
                       meetings: [MeetingTime(weekday: weekday, startSection: 1, endSection: 2,
                                              weekRules: rules, location: "教室")])
    }

    func testGuessCurrentWeek() {
        let start = SimpleDate(year: 2026, month: 8, day: 31)
        XCTAssertEqual(WeekScheduleCalculator.guessCurrentWeek(semesterStart: start, today: start, totalWeeks: 20), 1)
        XCTAssertEqual(WeekScheduleCalculator.guessCurrentWeek(semesterStart: start, today: start.adding(days: 7), totalWeeks: 20), 2)
        XCTAssertEqual(WeekScheduleCalculator.guessCurrentWeek(semesterStart: start, today: start.adding(days: -30), totalWeeks: 20), 1)
        XCTAssertEqual(WeekScheduleCalculator.guessCurrentWeek(semesterStart: start, today: start.adding(days: 365), totalWeeks: 20), 20)
    }

    func testParityRules() {
        let courses = [course([WeekRule(startWeek: 1, endWeek: 16, parity: .odd)])]
        XCTAssertEqual(WeekScheduleCalculator.meetings(inWeek: 1, of: courses).count, 1)
        XCTAssertEqual(WeekScheduleCalculator.meetings(inWeek: 2, of: courses).count, 0)
        XCTAssertEqual(WeekScheduleCalculator.meetings(inWeek: 3, of: courses).count, 1)
    }

    func testPastWeeksShowEndedCourses() {
        let courses = [course([WeekRule(startWeek: 1, endWeek: 5, parity: .odd)])]
        let display = WeekScheduleCalculator.displayMeetings(of: courses, week: 4, showNonCurrent: true)
        XCTAssertEqual(display.count, 1)
        XCTAssertFalse(display[0].isCurrentWeek)
        XCTAssertEqual(display[0].nextActiveWeek, 5)
    }

    // MARK: 学期日期

    func testSemesterLabelInference() {
        XCTAssertEqual(SemesterStartDatePolicy.inferFromSemesterLabelOrNull("2026-2027学年第一学期"),
                       SimpleDate(year: 2026, month: 8, day: 31))
        XCTAssertEqual(SemesterStartDatePolicy.inferFromSemesterLabelOrNull("2026-2027学年第二学期"),
                       SimpleDate(year: 2027, month: 2, day: 22))
        XCTAssertNil(SemesterStartDatePolicy.inferFromSemesterLabelOrNull("未知学期"))
    }

    func testNormalizeToWeekStart() {
        XCTAssertEqual(SemesterStartDatePolicy.normalizeToWeekStart(SimpleDate(year: 2026, month: 9, day: 2)),
                       SimpleDate(year: 2026, month: 8, day: 31))
    }

    func testDefaultForToday() {
        XCTAssertEqual(SemesterStartDatePolicy.defaultForToday(SimpleDate(year: 2026, month: 8, day: 15)),
                       SimpleDate(year: 2026, month: 8, day: 31))
        XCTAssertEqual(SemesterStartDatePolicy.defaultForToday(SimpleDate(year: 2026, month: 3, day: 15)),
                       SimpleDate(year: 2026, month: 2, day: 23))
    }

    // MARK: 备份编解码

    func testBackupRoundTrip() {
        let courses = [course([WeekRule(startWeek: 1, endWeek: 16, parity: .odd)])]
        let profiles = [ScheduleBackupCodec.BackupProfile(
            name: "主课表", semesterStartDate: "2026-08-31",
            courses: courses.map { ScheduleBackupCodec.toBackupCourse($0) })]
        let decoded = ScheduleBackupCodec.decode(ScheduleBackupCodec.encode(exportedAt: "2026-09-05", profiles: profiles))
        XCTAssertNotNil(decoded)
        XCTAssertEqual(decoded?.count, 1)
        XCTAssertEqual(decoded?[0].courses.first?.courseName, "测试课程")
        XCTAssertEqual(decoded?[0].courses.first?.meetings.first?.weekRules.first?.parity, "ODD")
        XCTAssertNil(ScheduleBackupCodec.decode("not a json {"))
    }

    // MARK: 课表级备注 notes

    func testExtractNotesRuleA() {
        let text = "军事技能（张爽）1-19周\n入学教育（张爽）1-19周"
        let notes = CourseTableImporter.extractNotes(from: text)
        XCTAssertTrue(notes.contains("军事技能（张爽）1-19周"))
        XCTAssertTrue(notes.contains("入学教育（张爽）1-19周"))
    }

    func testExtractNotesRuleB() {
        let text = "学期时间：2026-08-31至2027-01-22，共21周"
        let notes = CourseTableImporter.extractNotes(from: text)
        XCTAssertEqual(notes, ["学期时间：2026-08-31至2027-01-22，共21周"])
    }

    func testExtractNotesRuleC() {
        let text = "本学期共21周"
        let notes = CourseTableImporter.extractNotes(from: text)
        XCTAssertEqual(notes, ["本学期共21周"])
    }

    func testExtractNotesDedupe() {
        let text = "军事技能（张爽）1-19周\n军事技能（张爽）1-19周\n入学教育（张爽）1-19周"
        let notes = CourseTableImporter.extractNotes(from: text)
        XCTAssertEqual(notes.filter { $0 == "军事技能（张爽）1-19周" }.count, 1)
        XCTAssertEqual(notes.count, 2)
    }

    func testExtractNotesCap10() {
        var lines: [String] = []
        for i in 1...15 { lines.append("备注条目（老师）\(i)-\(i)周") }
        let notes = CourseTableImporter.extractNotes(from: lines.joined(separator: "\n"))
        XCTAssertEqual(notes.count, 10)
    }

    func testExtractNotesSkipsCourseAndOtherCourseLines() {
        // 课程行（带星期+节次）不进 notes；「其他课程：」汇总行进 warnings 不进 notes
        let text = "星期一 1-2节 高等数学/1-16周/教室/老师\n其他课程：军事技能张爽(共19周)/1-19周\n军事技能（张爽）1-19周"
        let notes = CourseTableImporter.extractNotes(from: text)
        XCTAssertFalse(notes.contains { $0.contains("高等数学") })
        XCTAssertFalse(notes.contains { $0.hasPrefix("其他课程") })
        XCTAssertTrue(notes.contains("军事技能（张爽）1-19周"))
    }

    func testExtractNotesExplicitSectionMarkerExcluded() {
        // 含显式节次标记（N-M节 / 第N节）即使无星期，也不入 notes
        let text = "第1-2节 高等数学\n军事技能（张爽）1-19周"
        let notes = CourseTableImporter.extractNotes(from: text)
        XCTAssertFalse(notes.contains { $0.contains("高等数学") })
        XCTAssertTrue(notes.contains("军事技能（张爽）1-19周"))
    }

    func testExtractNotesHolidayLineKept() {
        // "1-19周" 不得被判为节次；"共21周（含节假日调休）" 应入 notes
        let notes = CourseTableImporter.extractNotes(from: "共21周（含节假日调休）\n军事技能（张爽）1-19周")
        XCTAssertTrue(notes.contains("共21周（含节假日调休）"))
        XCTAssertTrue(notes.contains("军事技能（张爽）1-19周"))
    }

    func testParsePropagatesNotes() {
        let text = "2026-2027学年第一学期\n本学期2026年8月31日开学\n共21周\n军事技能（张爽）1-19周"
        let result = CourseTableImporter.parse(rawText: text)
        XCTAssertTrue(result.notes.contains("军事技能（张爽）1-19周"))
    }

    func testCourseScheduleNotesDefaultEmptyAndDecodesLegacy() throws {
        // 默认空数组
        let c = course([WeekRule(startWeek: 1, endWeek: 16)])
        XCTAssertEqual(c.notes, [])
        // 旧数据（无 notes 字段）解码不报错、notes 为 []
        let legacyJSON = """
        {"courseName":"测试课程","teacher":"老师","semester":"2026-2027-1","credit":null,"rawWeekText":"custom","meetings":[]}
        """
        let data = legacyJSON.data(using: .utf8)!
        let decoded = try JSONDecoder().decode(CourseSchedule.self, from: data)
        XCTAssertEqual(decoded.notes, [])
        XCTAssertEqual(decoded.courseName, "测试课程")
    }

    func testBackupWithNotesRoundTrip() {
        var c = course([WeekRule(startWeek: 1, endWeek: 16)])
        c.notes = ["军事技能（张爽）1-19周", "学期时间：2026-08-31至2027-01-22，共21周"]
        let profiles = [ScheduleBackupCodec.BackupProfile(
            name: "主课表", semesterStartDate: "2026-08-31",
            courses: [ScheduleBackupCodec.toBackupCourse(c)])]
        let decoded = ScheduleBackupCodec.decode(ScheduleBackupCodec.encode(exportedAt: "2026-09-05", profiles: profiles))
        XCTAssertEqual(decoded?[0].courses[0].notes, c.notes)
    }

    func testBackupWithoutNotesLegacyCompatible() {
        // 旧 v2 备份无 notes 字段：解码不报错，notes 默认 []
        let legacy = """
        {"version":2,"exportedAt":"2026-09-05","profiles":[{"name":"主课表","semesterStartDate":"2026-08-31","courses":[{"courseName":"测试课程","teacher":"老师","meetings":[]}]}]}
        """
        let decoded = ScheduleBackupCodec.decode(legacy)
        XCTAssertEqual(decoded?.count, 1)
        XCTAssertEqual(decoded?[0].courses[0].notes, [])
    }

    func testShareTextCodecRoundTripNotes() {
        var c = course([WeekRule(startWeek: 1, endWeek: 16)])
        c.notes = ["军事技能（张爽）1-19周"]
        let text = ShareTextCodec.encode(courses: [c], notes: c.notes)
        XCTAssertTrue(text.contains("# 备注：军事技能（张爽）1-19周"))
        // 分享文本再解析回 notes
        let result = CourseTableImporter.parse(rawText: text)
        XCTAssertTrue(result.notes.contains("军事技能（张爽）1-19周"))
    }
}
