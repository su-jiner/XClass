import Foundation

// MARK: - 多课表持久化（语义对齐 Android ImportedScheduleStorage）

final class ProfileStore {
    struct ProfileInfo: Identifiable, Hashable {
        var id: String
        var name: String
        var isActive: Bool
        var updatedAt: Date
        var semesterStartDate: SimpleDate
    }

    struct ManualCourseInput {
        var courseName: String
        var teacher: String
        var location: String
        var weekday: Weekday
        var startSection: Int
        var endSection: Int
        var startWeek: Int
        var endWeek: Int
        var parity: WeekParity = .all
        var semester: String = "手动添加"
    }

    private struct ProfileMeta: Codable {
        var id: String
        var name: String
        var coursesFile: String
        var createdAt: Date
        var updatedAt: Date
        var semesterStartDate: String?

        enum CodingKeys: String, CodingKey {
            case id, name, coursesFile, createdAt, updatedAt, semesterStartDate
        }

        init(id: String, name: String, coursesFile: String, createdAt: Date, updatedAt: Date, semesterStartDate: String?) {
            self.id = id; self.name = name; self.coursesFile = coursesFile
            self.createdAt = createdAt; self.updatedAt = updatedAt; self.semesterStartDate = semesterStartDate
        }

        // 宽松解码：字段缺失给默认值，避免整体解码失败导致数据"消失"
        init(from decoder: Decoder) throws {
            let c = try decoder.container(keyedBy: CodingKeys.self)
            id = try c.decodeIfPresent(String.self, forKey: .id) ?? UUID().uuidString
            name = try c.decodeIfPresent(String.self, forKey: .name) ?? "默认课表"
            coursesFile = try c.decodeIfPresent(String.self, forKey: .coursesFile) ?? "courses_\(id).json"
            createdAt = try c.decodeIfPresent(Date.self, forKey: .createdAt) ?? Date()
            updatedAt = try c.decodeIfPresent(Date.self, forKey: .updatedAt) ?? Date()
            semesterStartDate = try c.decodeIfPresent(String.self, forKey: .semesterStartDate)
        }
    }

    private struct Meta: Codable {
        var activeId: String
        var profiles: [ProfileMeta]
    }

    static let shared = ProfileStore()
    static let defaultProfileName = "默认课表"

    private let lock = NSLock()
    private let encoder: JSONEncoder
    private let decoder: JSONDecoder

    private init() {
        encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .millisecondsSince1970
        decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .millisecondsSince1970
    }

    // MARK: - 目录

    private var rootDir: URL {
        // 组件/锁屏/灵动岛与 App 共享同一份数据：优先 App Group 容器，未配置时回落 App 沙盒
        let base: URL
        if let group = SharedContainer.containerURL {
            base = group.appendingPathComponent("XClass", isDirectory: true)
        } else {
            base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
                .appendingPathComponent("XClass", isDirectory: true)
        }
        try? FileManager.default.createDirectory(at: base, withIntermediateDirectories: true)
        return base
    }

    private var metaURL: URL { rootDir.appendingPathComponent("meta.json") }

    private func coursesURL(_ fileName: String) -> URL { rootDir.appendingPathComponent(fileName) }

    // MARK: - 公开 API

    func listProfiles() -> [ProfileInfo] {
        lock.lock(); defer { lock.unlock() }
        let meta = ensureInitialized()
        return meta.profiles.map {
            ProfileInfo(id: $0.id, name: $0.name, isActive: $0.id == meta.activeId,
                        updatedAt: $0.updatedAt, semesterStartDate: semesterStart(of: $0))
        }
    }

    func setActiveProfile(_ id: String) -> Bool {
        lock.lock(); defer { lock.unlock() }
        var meta = ensureInitialized()
        guard meta.profiles.contains(where: { $0.id == id }) else { return false }
        meta.activeId = id
        save(meta)
        return true
    }

    func createEmptyProfile(named name: String) -> ProfileInfo {
        lock.lock(); defer { lock.unlock() }
        var meta = ensureInitialized()
        let now = Date()
        let id = UUID().uuidString
        let trimmed = name.trimmingCharacters(in: .whitespaces)
        let profileName = trimmed.isEmpty ? "课表\(timestampLabel())" : trimmed
        let fileName = "courses_\(id).json"
        writeCourses([], to: fileName)
        let start = SemesterStartDatePolicy.defaultForToday()
        meta.profiles.append(ProfileMeta(id: id, name: profileName, coursesFile: fileName,
                                         createdAt: now, updatedAt: now, semesterStartDate: start.isoString))
        meta.activeId = id
        save(meta)
        return ProfileInfo(id: id, name: profileName, isActive: true, updatedAt: now, semesterStartDate: start)
    }

    enum ImportMode { case overwriteActive, createNew }

    /// 导入向导入库：覆盖当前课表或新建课表；campusMap 用于把解析出的校区简称映射为时间表 ID。
    @discardableResult
    func importCourses(
        _ courses: [CourseSchedule],
        mode: ImportMode,
        newProfileName: String? = nil,
        semesterStartDate: SimpleDate? = nil,
        campusMap: [String: String] = [:]
    ) -> String {
        lock.lock(); defer { lock.unlock() }
        var meta = ensureInitialized()
        let now = Date()
        // campusMap 已在调用方（ImportView）用于把别名换成时间表 ID，这里直接写盘
        let persisted = courses
        let start = semesterStartDate ?? SemesterStartDatePolicy.inferFromCourses(persisted)

        switch mode {
        case .overwriteActive:
            let activeIndex = meta.profiles.firstIndex { $0.id == meta.activeId } ?? 0
            let active = meta.profiles[activeIndex]
            writeCourses(persisted, to: active.coursesFile)
            meta.profiles[activeIndex].updatedAt = now
            meta.profiles[activeIndex].semesterStartDate = start.isoString
            save(meta)
            return active.name
        case .createNew:
            let id = UUID().uuidString
            let trimmed = newProfileName?.trimmingCharacters(in: .whitespaces)
            let name = (trimmed?.isEmpty ?? true) ? "课表\(timestampLabel())" : trimmed!
            let fileName = "courses_\(id).json"
            writeCourses(persisted, to: fileName)
            meta.profiles.append(ProfileMeta(id: id, name: name, coursesFile: fileName,
                                             createdAt: now, updatedAt: now, semesterStartDate: start.isoString))
            meta.activeId = id
            save(meta)
            return name
        }
    }

    func renameProfile(_ id: String, to newName: String) -> Bool {
        let trimmed = newName.trimmingCharacters(in: .whitespaces)
        guard !trimmed.isEmpty else { return false }
        lock.lock(); defer { lock.unlock() }
        var meta = ensureInitialized()
        guard let idx = meta.profiles.firstIndex(where: { $0.id == id }) else { return false }
        meta.profiles[idx].name = trimmed
        meta.profiles[idx].updatedAt = Date()
        save(meta)
        return true
    }

    /// 先写 meta 再删课程文件，避免悬空引用
    func deleteProfile(_ id: String) -> Bool {
        lock.lock(); defer { lock.unlock() }
        var meta = ensureInitialized()
        guard let target = meta.profiles.first(where: { $0.id == id }), meta.profiles.count > 1 else { return false }
        let remained = meta.profiles.filter { $0.id != id }
        meta.activeId = meta.activeId == id ? remained[0].id : meta.activeId
        meta.profiles = remained
        save(meta)
        try? FileManager.default.removeItem(at: coursesURL(target.coursesFile))
        return true
    }

    func addManualCourse(_ input: ManualCourseInput) throws {
        func validate(_ condition: Bool, _ message: String) throws {
            guard condition else { throw NSError(domain: "ProfileStore", code: 2,
                                                userInfo: [NSLocalizedDescriptionKey: message]) }
        }
        try validate(!input.courseName.trimmingCharacters(in: .whitespaces).isEmpty, "课程名不能为空")
        try validate((1...12).contains(input.startSection), "开始节必须在 1-12 之间")
        try validate((1...12).contains(input.endSection), "结束节必须在 1-12 之间")
        try validate(input.endSection >= input.startSection, "结束节不能小于开始节")
        try validate((1...30).contains(input.startWeek), "开始周必须在 1-30 之间")
        try validate((1...30).contains(input.endWeek), "结束周必须在 1-30 之间")
        try validate(input.endWeek >= input.startWeek, "结束周不能小于开始周")

        lock.lock(); defer { lock.unlock() }
        var meta = ensureInitialized()
        let active = meta.profiles.first { $0.id == meta.activeId } ?? meta.profiles[0]
        var courses = readCourses(active.coursesFile)
        let course = CourseSchedule(
            courseName: input.courseName.trimmingCharacters(in: .whitespaces),
            teacher: input.teacher,
            semester: input.semester,
            credit: nil,
            rawWeekText: "\(input.startWeek)-\(input.endWeek)周",
            meetings: [MeetingTime(weekday: input.weekday, startSection: input.startSection,
                                   endSection: input.endSection,
                                   weekRules: [WeekRule(startWeek: input.startWeek, endWeek: input.endWeek, parity: input.parity)],
                                   location: input.location)]
        )
        courses.append(course)
        writeCourses(courses, to: active.coursesFile)
        if let idx = meta.profiles.firstIndex(where: { $0.id == active.id }) {
            meta.profiles[idx].updatedAt = Date()
        }
        save(meta)
    }

    func loadActiveCourses() -> [CourseSchedule] {
        lock.lock(); defer { lock.unlock() }
        let meta = ensureInitialized()
        let active = meta.profiles.first { $0.id == meta.activeId } ?? meta.profiles[0]
        return readCourses(active.coursesFile)
    }

    /// 按课表 ID 加载课程（nil 或空串时加载当前课表）。
    func loadCourses(profileId: String?) -> [CourseSchedule] {
        lock.lock(); defer { lock.unlock() }
        let meta = ensureInitialized()
        let profile: ProfileMeta
        if let profileId = profileId, !profileId.isEmpty,
           let found = meta.profiles.first(where: { $0.id == profileId }) {
            profile = found
        } else {
            profile = meta.profiles.first { $0.id == meta.activeId } ?? meta.profiles[0]
        }
        return readCourses(profile.coursesFile)
    }

    /// 编辑课程定义（全局生效：所有周同步），profileId 为空时作用于当前课表。
    func updateCourse(index: Int, newCourse: CourseSchedule, profileId: String?) throws {
        lock.lock(); defer { lock.unlock() }
        var meta = ensureInitialized()
        let profile: ProfileMeta
        if let profileId = profileId, !profileId.isEmpty,
           let found = meta.profiles.first(where: { $0.id == profileId }) {
            profile = found
        } else {
            profile = meta.profiles.first { $0.id == meta.activeId } ?? meta.profiles[0]
        }
        var courses = readCourses(profile.coursesFile)
        guard courses.indices.contains(index) else {
            throw NSError(domain: "ProfileStore", code: 3,
                          userInfo: [NSLocalizedDescriptionKey: "课程不存在，可能已被删除"])
        }
        courses[index] = newCourse
        writeCourses(courses, to: profile.coursesFile)
        if let idx = meta.profiles.firstIndex(where: { $0.id == profile.id }) {
            meta.profiles[idx].updatedAt = Date()
        }
        save(meta)
    }

    /// 编辑拆分保存：课程含多个上课时段且课程名/教师被修改时，被编辑时段独立成新课程（追加到末尾），
    /// 原课程保留其余时段（原名原师）；keptCourse 时段为空时原课程被移除。避免跨天绑定连带修改。
    func splitUpdateCourse(index: Int, keptCourse: CourseSchedule, extractedCourse: CourseSchedule, profileId: String?) throws {
        lock.lock(); defer { lock.unlock() }
        var meta = ensureInitialized()
        let profile: ProfileMeta
        if let profileId = profileId, !profileId.isEmpty,
           let found = meta.profiles.first(where: { $0.id == profileId }) {
            profile = found
        } else {
            profile = meta.profiles.first { $0.id == meta.activeId } ?? meta.profiles[0]
        }
        var courses = readCourses(profile.coursesFile)
        guard courses.indices.contains(index) else {
            throw NSError(domain: "ProfileStore", code: 3,
                          userInfo: [NSLocalizedDescriptionKey: "课程不存在，可能已被删除"])
        }
        if keptCourse.meetings.isEmpty {
            courses.remove(at: index)
        } else {
            courses[index] = keptCourse
        }
        courses.append(extractedCourse)
        writeCourses(courses, to: profile.coursesFile)
        if let idx = meta.profiles.firstIndex(where: { $0.id == profile.id }) {
            meta.profiles[idx].updatedAt = Date()
        }
        save(meta)
    }

    /// 删除指定课表中的课程；返回是否成功。
    func deleteCourseInProfile(index: Int, profileId: String?) -> Bool {
        lock.lock(); defer { lock.unlock() }
        var meta = ensureInitialized()
        let profile: ProfileMeta
        if let profileId = profileId, !profileId.isEmpty,
           let found = meta.profiles.first(where: { $0.id == profileId }) {
            profile = found
        } else {
            profile = meta.profiles.first { $0.id == meta.activeId } ?? meta.profiles[0]
        }
        var courses = readCourses(profile.coursesFile)
        guard courses.indices.contains(index) else { return false }
        courses.remove(at: index)
        writeCourses(courses, to: profile.coursesFile)
        if let idx = meta.profiles.firstIndex(where: { $0.id == profile.id }) {
            meta.profiles[idx].updatedAt = Date()
        }
        save(meta)
        return true
    }

    func getActiveSemesterStartDate() -> SimpleDate {
        lock.lock(); defer { lock.unlock() }
        let meta = ensureInitialized()
        let active = meta.profiles.first { $0.id == meta.activeId } ?? meta.profiles[0]
        return semesterStart(of: active)
    }

    func setActiveSemesterStartDate(_ date: SimpleDate) {
        lock.lock(); defer { lock.unlock() }
        var meta = ensureInitialized()
        let active = meta.profiles.first { $0.id == meta.activeId } ?? meta.profiles[0]
        if let idx = meta.profiles.firstIndex(where: { $0.id == active.id }) {
            meta.profiles[idx].semesterStartDate = SemesterStartDatePolicy.normalizeToWeekStart(date).isoString
            meta.profiles[idx].updatedAt = Date()
        }
        save(meta)
    }

    func exportBackup() -> String {
        lock.lock(); defer { lock.unlock() }
        let meta = ensureInitialized()
        let profiles = meta.profiles.map { profile in
            ScheduleBackupCodec.BackupProfile(
                name: profile.name,
                semesterStartDate: profile.semesterStartDate,
                courses: readCourses(profile.coursesFile).map { ScheduleBackupCodec.toBackupCourse($0) }
            )
        }
        let formatter = ISO8601DateFormatter()
        let timeProfiles = loadTimeProfiles()
            .filter { $0.id != TimeProfileResolver.defaultProfileId }
            .map { ScheduleBackupCodec.BackupTimeProfile(id: $0.id, name: $0.name, blocks: $0.blocks) }
        return ScheduleBackupCodec.encode(
            exportedAt: formatter.string(from: Date()),
            profiles: profiles,
            timeProfiles: timeProfiles,
            campusMap: loadCampusMap(),
            defaultProfileId: loadDefaultProfileId()
        )
    }

    /// 用备份替换全部课表，返回恢复数量
    func importBackup(_ content: String) throws -> Int {
        guard let envelope = ScheduleBackupCodec.decodeEnvelope(content) else {
            throw NSError(domain: "ProfileStore", code: 1,
                          userInfo: [NSLocalizedDescriptionKey: "备份文件格式不正确"])
        }
        let backupProfiles = envelope.profiles
        lock.lock(); defer { lock.unlock() }
        if !envelope.timeProfiles.isEmpty {
            saveTimeProfiles(
                envelope.timeProfiles.map { SectionTimeProfile(id: $0.id, name: $0.name, blocks: $0.blocks) },
                campusMap: envelope.campusMap,
                defaultProfileId: envelope.defaultProfileId
            )
        }
        let existing = (try? FileManager.default.contentsOfDirectory(at: rootDir, includingPropertiesForKeys: nil)) ?? []
        for url in existing where url.lastPathComponent.hasPrefix("courses_") && url.pathExtension == "json" {
            try? FileManager.default.removeItem(at: url)
        }
        let now = Date()
        var newProfiles: [ProfileMeta] = []
        for backup in backupProfiles {
            let id = UUID().uuidString
            let fileName = "courses_\(id).json"
            writeCourses(backup.courses.map { ScheduleBackupCodec.toCourseSchedule($0) }, to: fileName)
            newProfiles.append(ProfileMeta(id: id, name: backup.name.isEmpty ? "课表\(timestampLabel())" : backup.name,
                                           coursesFile: fileName, createdAt: now, updatedAt: now,
                                           semesterStartDate: backup.semesterStartDate))
        }
        if newProfiles.isEmpty {
            _ = ensureInitialized()
        } else {
            save(Meta(activeId: newProfiles[0].id, profiles: newProfiles))
        }
        return newProfiles.count
    }

    func exportActiveTimetableText(periodRanges: [String]) -> String {
        lock.lock(); defer { lock.unlock() }
        let meta = ensureInitialized()
        let active = meta.profiles.first { $0.id == meta.activeId } ?? meta.profiles[0]
        let courses = readCourses(active.coursesFile)
        let start = semesterStart(of: active)
        let maxWeek = courses.flatMap { $0.meetings }.flatMap { $0.weekRules }.map { $0.endWeek }.max() ?? 0

        var out = "课表：\(active.name)\n学期开始：\(start.isoString)（第1周周一）\n总周数：\(maxWeek)\n"
        for weekday in Weekday.allCases {
            out += "\n【周\(weekday.shortLabel)】\n"
            var meetings: [(CourseSchedule, MeetingTime)] = []
            for course in courses {
                for meeting in course.meetings where meeting.weekday == weekday {
                    meetings.append((course, meeting))
                }
            }
            meetings.sort { $0.1.startSection < $1.1.startSection }
            var seen = Set<String>()
            let distinct = meetings.filter { item in
                let key = "\(item.0.courseName)|\(item.1.startSection)|\(item.1.endSection)|\(item.1.location)|\(item.0.rawWeekText)"
                return seen.insert(key).inserted
            }
            if distinct.isEmpty { out += "（无课程）\n" }
            for (course, meeting) in distinct {
                let startText = periodRanges.indices.contains(meeting.startSection - 1)
                    ? periodRanges[meeting.startSection - 1].components(separatedBy: "-")[0] : "--:--"
                let endText = periodRanges.indices.contains(meeting.endSection - 1)
                    ? periodRanges[meeting.endSection - 1].components(separatedBy: "-")[1] : "--:--"
                out += "第\(meeting.startSection)-\(meeting.endSection)节 \(startText)-\(endText)  \(course.courseName)"
                if !meeting.location.isEmpty { out += "  \(meeting.location)" }
                if !course.teacher.isEmpty { out += "  \(course.teacher)" }
                if !course.rawWeekText.isEmpty { out += "  \(course.rawWeekText)" }
                out += "\n"
            }
        }
        return out
    }

    // MARK: - 内部实现

    private func ensureInitialized() -> Meta {
        let fm = FileManager.default
        if !fm.fileExists(atPath: rootDir.path) {
            try? fm.createDirectory(at: rootDir, withIntermediateDirectories: true)
        }
        if fm.fileExists(atPath: metaURL.path), let data = try? Data(contentsOf: metaURL), !data.isEmpty {
            if let meta = try? decoder.decode(Meta.self, from: data), !meta.profiles.isEmpty {
                let migrated = fillMissingSemesterStarts(meta)
                if migrated != meta { save(migrated) }
                return migrated
            }
            // meta 损坏：保留现场，尝试从课程文件恢复
            let backup = rootDir.appendingPathComponent("meta.json.corrupt-\(Int(Date().timeIntervalSince1970))")
            try? fm.moveItem(at: metaURL, to: backup)
        }

        if let salvaged = salvageOrphans() {
            save(salvaged)
            return salvaged
        }
        return initializeDefault()
    }

    private func salvageOrphans() -> Meta? {
        let fm = FileManager.default
        guard let urls = try? fm.contentsOfDirectory(at: rootDir, includingPropertiesForKeys: [.contentModificationDateKey]) else {
            return nil
        }
        let orphanFiles = urls.filter {
            $0.lastPathComponent.hasPrefix("courses_") && $0.pathExtension == "json"
        }.sorted { a, b in
            let da = (try? a.resourceValues(forKeys: [.contentModificationDateKey]))?.contentModificationDate ?? .distantPast
            let db = (try? b.resourceValues(forKeys: [.contentModificationDateKey]))?.contentModificationDate ?? .distantPast
            return da > db
        }
        guard !orphanFiles.isEmpty else { return nil }

        var profiles: [ProfileMeta] = []
        for (index, url) in orphanFiles.enumerated() {
            let id = url.deletingPathExtension().lastPathComponent
            guard !id.isEmpty else { continue }
            let courses = readCourses(url.lastPathComponent)
            profiles.append(ProfileMeta(
                id: id,
                name: index == 0 ? Self.defaultProfileName : "恢复课表\(index + 1)",
                coursesFile: url.lastPathComponent,
                createdAt: (try? url.resourceValues(forKeys: [.contentModificationDateKey]))?.contentModificationDate ?? Date(),
                updatedAt: (try? url.resourceValues(forKeys: [.contentModificationDateKey]))?.contentModificationDate ?? Date(),
                semesterStartDate: SemesterStartDatePolicy.inferFromCourses(courses).isoString
            ))
        }
        guard !profiles.isEmpty else { return nil }
        return Meta(activeId: profiles[0].id, profiles: profiles)
    }

    private func initializeDefault() -> Meta {
        let id = UUID().uuidString
        let fileName = "courses_\(id).json"
        let courses: [CourseSchedule] = []
        writeCourses(courses, to: fileName)
        let now = Date()
        let start = SemesterStartDatePolicy.defaultForToday()
        let meta = Meta(activeId: id, profiles: [
            ProfileMeta(id: id, name: Self.defaultProfileName, coursesFile: fileName,
                        createdAt: now, updatedAt: now, semesterStartDate: start.isoString)
        ])
        save(meta)
        return meta
    }

    private func fillMissingSemesterStarts(_ meta: Meta) -> Meta {
        var changed = false
        var profiles = meta.profiles
        for i in profiles.indices where profiles[i].semesterStartDate == nil || profiles[i].semesterStartDate!.isEmpty {
            let courses = readCourses(profiles[i].coursesFile)
            profiles[i].semesterStartDate = SemesterStartDatePolicy.inferFromCourses(courses).isoString
            changed = true
        }
        return changed ? Meta(activeId: meta.activeId, profiles: profiles) : meta
    }

    private func semesterStart(of profile: ProfileMeta) -> SimpleDate {
        guard let raw = profile.semesterStartDate, let date = SimpleDate.parse(raw) else {
            return SemesterStartDatePolicy.defaultForToday()
        }
        return SemesterStartDatePolicy.normalizeToWeekStart(date)
    }

    private func readCourses(_ fileName: String) -> [CourseSchedule] {
        let url = coursesURL(fileName)
        guard let data = try? Data(contentsOf: url), !data.isEmpty else { return [] }
        return (try? decoder.decode([PersistedCourse].self, from: data)).map { $0.map { $0.toCourseSchedule() } } ?? []
    }

    // MARK: - 多校区节次时间表（与 Android time_profiles.json 同构）

    private var timeProfilesURL: URL { rootDir.appendingPathComponent("time_profiles.json") }

    private struct PersistedTimeProfiles: Codable {
        var version: Int = 1
        var defaultProfileId: String? = nil
        var profiles: [SectionTimeProfile] = []
        var campusMap: [String: String] = [:]
    }

    func loadTimeProfiles() -> [SectionTimeProfile] {
        guard let data = try? Data(contentsOf: timeProfilesURL),
              let stored = try? decoder.decode(PersistedTimeProfiles.self, from: data) else {
            return [TimeProfileResolver.defaultProfile()]
        }
        let custom = stored.profiles.filter { !$0.blocks.isEmpty && $0.id != TimeProfileResolver.defaultProfileId }
        return [TimeProfileResolver.defaultProfile()] + custom
    }

    func loadCampusMap() -> [String: String] {
        guard let data = try? Data(contentsOf: timeProfilesURL),
              let stored = try? decoder.decode(PersistedTimeProfiles.self, from: data) else { return [:] }
        return stored.campusMap
    }

    func loadDefaultProfileId() -> String? {
        guard let data = try? Data(contentsOf: timeProfilesURL),
              let stored = try? decoder.decode(PersistedTimeProfiles.self, from: data) else { return nil }
        return stored.defaultProfileId
    }

    /// 当前用于展示的节次时间表（左侧时间列）。优先 defaultProfileId 指向的校区时间表，
    /// 否则回落到默认 12 节表。
    func currentDisplayTimeProfile() -> SectionTimeProfile {
        let all = loadTimeProfiles()
        if let id = loadDefaultProfileId(),
           let matched = all.first(where: { $0.id == id }),
           !matched.blocks.isEmpty {
            return matched
        }
        return TimeProfileResolver.resolve(all, campusId: nil)
    }

    /// 设置"当前显示时间表"（导入解析出校区时间表后自动调用）。
    func setDefaultTimeProfileId(_ id: String?) {
        let profiles = loadTimeProfiles()
        let campusMap = loadCampusMap()
        saveTimeProfiles(profiles, campusMap: campusMap, defaultProfileId: id)
    }

    func saveTimeProfiles(_ profiles: [SectionTimeProfile], campusMap: [String: String], defaultProfileId: String?) {
        let payload = PersistedTimeProfiles(
            defaultProfileId: defaultProfileId,
            profiles: profiles.filter { $0.id != TimeProfileResolver.defaultProfileId },
            campusMap: campusMap
        )
        guard let data = try? encoder.encode(payload) else { return }
        try? FileManager.default.createDirectory(at: rootDir, withIntermediateDirectories: true)
        try? data.write(to: timeProfilesURL, options: .atomic)
    }

    /// 导入解析结果入库：保存识别出的时间表并建立「校区简称 → 时间表」映射
    @discardableResult
    func saveParsedTimeTables(timeTables: [SectionTimeProfile], campusAliases: [String]) -> [String: String] {
        var existing = loadTimeProfiles().filter { $0.id != TimeProfileResolver.defaultProfileId }
        for profile in timeTables {
            if let index = existing.firstIndex(where: { $0.id == profile.id || $0.name == profile.name }) {
                existing[index] = profile
            } else {
                existing.append(profile)
            }
        }
        var campusMap = loadCampusMap()
        for alias in campusAliases {
            if let matched = timeTables.first(where: { $0.name.contains(alias) }) { campusMap[alias] = matched.id }
        }
        saveTimeProfiles(existing, campusMap: campusMap, defaultProfileId: loadDefaultProfileId())
        return campusMap
    }

    private func writeCourses(_ courses: [CourseSchedule], to fileName: String) {
        let persisted = courses.map { PersistedCourse(from: $0) }
        guard let data = try? encoder.encode(persisted) else { return }
        try? data.write(to: coursesURL(fileName), options: .atomic)
    }

    private func save(_ meta: Meta) {
        guard let data = try? encoder.encode(meta) else { return }
        try? data.write(to: metaURL, options: .atomic)
    }

    private func mergeDuplicates(_ courses: [CourseSchedule]) -> [CourseSchedule] {
        var seen = Set<String>()
        var result: [CourseSchedule] = []
        for course in courses {
            var key = "\(course.courseName)|\(course.teacher)|\(course.semester)|\(course.credit ?? -1)|\(course.rawWeekText)|"
            let meetings = course.meetings.sorted {
                ($0.weekday.rawValue, $0.startSection) < ($1.weekday.rawValue, $1.startSection)
            }
            for meeting in meetings {
                key += "\(meeting.weekday.rawValue):\(meeting.startSection)-\(meeting.endSection)@\(meeting.location)#"
                key += meeting.weekRules.map { "\($0.startWeek)-\($0.endWeek)(\($0.parity.rawValue))" }.joined(separator: ",")
                key += ";"
            }
            if seen.insert(key).inserted { result.append(course) }
        }
        return result
    }

    private func timestampLabel() -> String {
        let f = DateFormatter()
        f.dateFormat = "MMdd_HHmm"
        return f.string(from: Date())
    }
}

// MARK: - 持久化模型（与 Android PersistedCourse 结构一致）

private struct PersistedCourse: Codable {
    var courseName: String
    var teacher: String
    var semester: String
    var credit: Double?
    var rawWeekText: String
    var meetings: [PersistedMeeting]
    /// 课表级公共备注（v3 起写入；旧文件无此字段时解码为 []）
    var notes: [String]

    enum CodingKeys: String, CodingKey {
        case courseName, teacher, semester, credit, rawWeekText, meetings, notes
    }

    init(from course: CourseSchedule) {
        courseName = course.courseName
        teacher = course.teacher
        semester = course.semester
        credit = course.credit
        rawWeekText = course.rawWeekText
        meetings = course.meetings.map { PersistedMeeting(from: $0) }
        notes = course.notes
    }

    // 宽松解码：旧课程文件无 notes 字段时默认 []
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        courseName = try c.decodeIfPresent(String.self, forKey: .courseName) ?? ""
        teacher = try c.decodeIfPresent(String.self, forKey: .teacher) ?? ""
        semester = try c.decodeIfPresent(String.self, forKey: .semester) ?? ""
        credit = try c.decodeIfPresent(Double.self, forKey: .credit)
        rawWeekText = try c.decodeIfPresent(String.self, forKey: .rawWeekText) ?? ""
        meetings = try c.decodeIfPresent([PersistedMeeting].self, forKey: .meetings) ?? []
        notes = try c.decodeIfPresent([String].self, forKey: .notes) ?? []
    }

    func toCourseSchedule() -> CourseSchedule {
        CourseSchedule(courseName: courseName, teacher: teacher, semester: semester,
                       credit: credit, rawWeekText: rawWeekText,
                       meetings: meetings.map { $0.toMeetingTime() },
                       notes: notes)
    }
}

private struct PersistedMeeting: Codable {
    var weekday: String
    var startSection: Int
    var endSection: Int
    var weekRules: [PersistedWeekRule]
    var location: String
    /// 校区/楼栋时间表 ID（v2 起）
    var campusId: String? = nil
    /// 自定义上课起止时间（HH:mm），旧数据无此字段时回退为 nil
    var customStartTime: String? = nil
    var customEndTime: String? = nil

    init(from meeting: MeetingTime) {
        weekday = meeting.weekday.rawValue.description
        startSection = meeting.startSection
        endSection = meeting.endSection
        weekRules = meeting.weekRules.map { PersistedWeekRule(startWeek: $0.startWeek, endWeek: $0.endWeek, parity: $0.parity.rawValue) }
        location = meeting.location
        campusId = meeting.campusId
        customStartTime = meeting.customStartTime
        customEndTime = meeting.customEndTime
    }

    func toMeetingTime() -> MeetingTime {
        MeetingTime(weekday: Weekday(rawValue: Int(weekday) ?? 1) ?? .monday,
                    startSection: startSection, endSection: endSection,
                    weekRules: weekRules.map {
                        WeekRule(startWeek: $0.startWeek, endWeek: $0.endWeek,
                                 parity: WeekParity(rawValue: $0.parity) ?? .all)
                    },
                    location: location,
                    campusId: campusId,
                    customStartTime: customStartTime,
                    customEndTime: customEndTime)
    }
}

private struct PersistedWeekRule: Codable {
    var startWeek: Int
    var endWeek: Int
    var parity: String
}
