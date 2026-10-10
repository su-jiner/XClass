import Foundation

/// 一个「节次段 → 上课时间」映射块，例如 1-2 节 08:30-10:05。
struct SectionBlock: Codable, Hashable {
    var startSection: Int
    var endSection: Int
    var startTime: String
    var endTime: String
}

/// 一套节次时间表，可对应校区或校区内的某个楼栋（如「真理道·实训楼」）。
struct SectionTimeProfile: Codable, Hashable {
    var id: String
    var name: String
    var blocks: [SectionBlock]

    /// 某节课小节的起止时间：起点取覆盖 startSection 的段，终点取覆盖 endSection 的段。
    func timeRange(startSection: Int, endSection: Int) -> (start: String, end: String)? {
        guard !blocks.isEmpty else { return nil }
        let start = blocks.first { startSection >= $0.startSection && startSection <= $0.endSection }
            ?? blocks.min { abs($0.startSection - startSection) < abs($1.startSection - startSection) }
        let end = blocks.first { endSection >= $0.startSection && endSection <= $0.endSection } ?? start
        guard let startTime = start?.startTime, let endTime = end?.endTime else { return nil }
        return (startTime, endTime)
    }

    func sectionRange(_ section: Int) -> String? {
        guard let range = timeRange(startSection: section, endSection: section) else { return nil }
        return "\(range.start)-\(range.end)"
    }
}

/// 多校区时间表解析：默认 12 节表 + 用户维护的校区/楼栋时间表（与 Android/iOS/小程序一致）。
enum TimeProfileResolver {
    static let defaultProfileId = "default"
    static let defaultProfileName = "默认（12 节）"

    static func defaultProfile() -> SectionTimeProfile {
        let blocks = SectionTimes.ranges.enumerated().map { index, range -> SectionBlock in
            let parts = range.split(separator: "-")
            return SectionBlock(
                startSection: index + 1,
                endSection: index + 1,
                startTime: parts.first.map(String.init) ?? "",
                endTime: parts.count > 1 ? String(parts[1]) : ""
            )
        }
        return SectionTimeProfile(id: defaultProfileId, name: defaultProfileName, blocks: blocks)
    }

    static func resolve(_ profiles: [SectionTimeProfile], campusId: String?) -> SectionTimeProfile {
        let list = profiles.isEmpty ? [defaultProfile()] : profiles
        if let campusId, let matched = list.first(where: { $0.id == campusId }) {
            return matched
        }
        return list.first { $0.id == defaultProfileId } ?? list[0]
    }

    static func timeRange(_ profiles: [SectionTimeProfile], meeting: MeetingTime) -> (start: String, end: String)? {
        let profile = resolve(profiles, campusId: meeting.campusId)
        if let range = profile.timeRange(startSection: meeting.startSection, endSection: meeting.endSection) {
            return range
        }
        return resolve(profiles, campusId: nil).timeRange(startSection: meeting.startSection, endSection: meeting.endSection)
    }

    static func sectionRanges(_ profile: SectionTimeProfile, count: Int = 12) -> [String] {
        (1...count).map { profile.sectionRange($0) ?? "--:--" }
    }
}
