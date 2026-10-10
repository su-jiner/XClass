import Foundation

// MARK: - 备份文件编解码（格式与 Android ScheduleBackupCodec 一致，version 2）

enum ScheduleBackupCodec {
    struct BackupFile: Codable {
        var version: Int = 2
        var exportedAt: String = ""
        var profiles: [BackupProfile] = []
        var timeProfiles: [BackupTimeProfile] = []
        var campusMap: [String: String] = [:]
        var defaultProfileId: String? = nil
    }

    struct BackupTimeProfile: Codable {
        var id: String
        var name: String
        var blocks: [SectionBlock]
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
        var campusId: String? = nil
    }

    struct BackupWeekRule: Codable {
        var startWeek: Int
        var endWeek: Int
        var parity: String = "ALL"
    }

    static func encode(
        exportedAt: String,
        profiles: [BackupProfile],
        timeProfiles: [BackupTimeProfile] = [],
        campusMap: [String: String] = [:],
        defaultProfileId: String? = nil
    ) -> String {
        let file = BackupFile(
            exportedAt: exportedAt,
            profiles: profiles,
            timeProfiles: timeProfiles.filter { $0.id != TimeProfileResolver.defaultProfileId },
            campusMap: campusMap,
            defaultProfileId: defaultProfileId
        )
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        guard let data = try? encoder.encode(file) else { return "" }
        return String(data: data, encoding: .utf8) ?? ""
    }

    static func decode(_ content: String) -> [BackupProfile]? {
        decodeEnvelope(content)?.profiles
    }

    /// 解码完整信封（v2 含时间表与校区映射；v1 备份仍可读）
    static func decodeEnvelope(_ content: String) -> BackupFile? {
        guard let data = content.data(using: .utf8),
              let file = try? JSONDecoder().decode(BackupFile.self, from: data),
              (1...2).contains(file.version) else { return nil }
        return file
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
                    location: meeting.location,
                    campusId: meeting.campusId
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
                    location: meeting.location,
                    campusId: meeting.campusId
                )
            }
        )
    }
}
