import Foundation

// MARK: - 备份文件编解码（格式与 Android ScheduleBackupCodec 一致，version 1）

enum ScheduleBackupCodec {
    struct BackupFile: Codable {
        var version: Int = 1
        var exportedAt: String = ""
        var profiles: [BackupProfile] = []
    }

    struct BackupProfile: Codable {
        var name: String
        var semesterStartDate: String?
        var courses: [BackupCourse]
    }

    struct BackupCourse: Codable {
        var courseName: String
        var teacher: String = ""
        var semester: String = ""
        var credit: Double?
        var rawWeekText: String = ""
        var meetings: [BackupMeeting]
    }

    struct BackupMeeting: Codable {
        var weekday: String
        var startSection: Int
        var endSection: Int
        var weekRules: [BackupWeekRule] = []
        var location: String = ""
    }

    struct BackupWeekRule: Codable {
        var startWeek: Int
        var endWeek: Int
        var parity: String = "ALL"
    }

    static func encode(exportedAt: String, profiles: [BackupProfile]) -> String {
        let file = BackupFile(exportedAt: exportedAt, profiles: profiles)
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        guard let data = try? encoder.encode(file) else { return "" }
        return String(data: data, encoding: .utf8) ?? ""
    }

    static func decode(_ content: String) -> [BackupProfile]? {
        guard let data = content.data(using: .utf8),
              let file = try? JSONDecoder().decode(BackupFile.self, from: data),
              (1...1).contains(file.version) else { return nil }
        return file.profiles
    }

    static func toBackupCourse(_ course: CourseSchedule) -> BackupCourse {
        BackupCourse(
            courseName: course.courseName,
            teacher: course.teacher,
            semester: course.semester,
            credit: course.credit,
            rawWeekText: course.rawWeekText,
            meetings: course.meetings.map { meeting in
                BackupMeeting(
                    weekday: meeting.weekday.rawValue.description,
                    startSection: meeting.startSection,
                    endSection: meeting.endSection,
                    weekRules: meeting.weekRules.map {
                        BackupWeekRule(startWeek: $0.startWeek, endWeek: $0.endWeek, parity: $0.parity.rawValue)
                    },
                    location: meeting.location
                )
            }
        )
    }

    static func toCourseSchedule(_ backup: BackupCourse) -> CourseSchedule {
        CourseSchedule(
            courseName: backup.courseName,
            teacher: backup.teacher,
            semester: backup.semester,
            credit: backup.credit,
            rawWeekText: backup.rawWeekText,
            meetings: backup.meetings.map { meeting in
                MeetingTime(
                    weekday: Weekday(rawValue: Int(meeting.weekday) ?? 1) ?? .monday,
                    startSection: meeting.startSection,
                    endSection: meeting.endSection,
                    weekRules: meeting.weekRules.map {
                        WeekRule(startWeek: $0.startWeek, endWeek: $0.endWeek,
                                 parity: WeekParity(rawValue: $0.parity) ?? .all)
                    },
                    location: meeting.location
                )
            }
        )
    }
}
