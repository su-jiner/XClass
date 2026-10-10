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
}
