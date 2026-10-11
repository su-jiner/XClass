import Foundation

/// 课表文本导入解析（与 Android importing/、鸿蒙 Core.ets、小程序 utils/parse.js 对齐）。
enum CourseTableImporter {

    struct ParsedEntry {
        var courseName: String
        var teacher: String
        var location: String
        var campusAlias: String?
        var weekRules: [WeekRule]
        var customStartTime: String?
        var customEndTime: String?
        var rawText: String
    }

    struct ParsedCourse {
        var courseName: String
        var teacher: String
        var meetings: [(weekday: Weekday, startSection: Int, endSection: Int, weekRules: [WeekRule], location: String, campusAlias: String?, customStartTime: String?, customEndTime: String?)]
    }

    struct Result {
        var courses: [ParsedCourse]
        var semester: String?
        var semesterStartDate: String?
        var totalWeeks: Int?
        var timeTables: [SectionTimeProfile]
        var campusAliases: [String]
        var warnings: [String]
        /// 课表级公共备注（独立备注行，原样保留，去重后最多 10 条）
        var notes: [String] = []
    }

    private static let weekdayAliases: [[String]] = [
        ["星期一", "周一", "礼拜一", "mon", "monday"],
        ["星期二", "周二", "礼拜二", "tue", "tuesday"],
        ["星期三", "周三", "礼拜三", "wed", "wednesday"],
        ["星期四", "周四", "礼拜四", "thu", "thursday"],
        ["星期五", "周五", "礼拜五", "fri", "friday"],
        ["星期六", "周六", "礼拜六", "sat", "saturday"],
        ["星期日", "星期天", "周日", "周天", "sun", "sunday"],
    ]

    static func weekdayIndex(_ text: String) -> Int? {
        let normalized = text.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
            .replacingOccurrences(of: " ", with: "")
        guard !normalized.isEmpty else { return nil }
        for (index, aliases) in weekdayAliases.enumerated() where aliases.contains(where: { normalized.contains($0) }) {
            return index + 1
        }
        return nil
    }

    /// 周次文本 → WeekRule[]（7-19周 / 12-14周(双),15-19周 / 第1,3,5周（单））
    static func parseWeekRules(_ raw: String, defaultTotalWeeks: Int = 20) -> [WeekRule] {
        var text = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if text.isEmpty { return [WeekRule(startWeek: 1, endWeek: defaultTotalWeeks, parity: .all)] }
        text = text.replacingOccurrences(
            of: "[（(]?\\s*第?\\s*\\d{1,2}\\s*[-—–~～至]\\s*\\d{1,2}\\s*节\\s*[）)]?",
            with: " ",
            options: .regularExpression
        )
        let globalParity: WeekParity = text.contains("单") ? .odd : (text.contains("双") ? .even : .all)
        var rules: [WeekRule] = []
        for token in text.components(separatedBy: CharacterSet(charactersIn: ",，、;；")) {
            let trimmed = token.trimmingCharacters(in: .whitespaces)
            guard !trimmed.isEmpty, trimmed.rangeOfCharacter(from: .decimalDigits) != nil else { continue }
            let parity: WeekParity = trimmed.contains("单") ? .odd : (trimmed.contains("双") ? .even : globalParity)
            if let range = firstMatch(#"(\d{1,2})\s*[-—–~～至]\s*(\d{1,2})"#, in: trimmed),
               let start = Int(range[0]), let end = Int(range[1]) {
                rules.append(WeekRule(startWeek: min(start, end), endWeek: max(start, end), parity: parity))
                continue
            }
            for value in matches(#"\d{1,2}"#, in: trimmed) {
                guard let week = Int(value), week >= 1, week <= 30 else { continue }
                rules.append(WeekRule(startWeek: week, endWeek: week, parity: parity))
            }
        }
        if rules.isEmpty { return [WeekRule(startWeek: 1, endWeek: defaultTotalWeeks, parity: .all)] }
        return Array(Set(rules)).sorted { $0.startWeek == $1.startWeek ? $0.endWeek < $1.endWeek : $0.startWeek < $1.startWeek }
    }

    static func weekRulesText(_ rules: [WeekRule]) -> String {
        rules.map { rule in
            let base = rule.startWeek == rule.endWeek ? "\(rule.startWeek)周" : "\(rule.startWeek)-\(rule.endWeek)周"
            switch rule.parity {
            case .odd: return "\(base)(单)"
            case .even: return "\(base)(双)"
            default: return base
            }
        }.joined(separator: ",")
    }

    /// 单元格：课程◇(节次)周次◇地点◇教师；一格多课按换行/分号拆
    static func parseCellEntries(_ cellText: String, defaultTotalWeeks: Int = 20) -> [ParsedEntry] {
        var lines: [String] = []
        for line in cellText.components(separatedBy: .newlines) {
            for part in line.components(separatedBy: CharacterSet(charactersIn: "；;")) {
                lines.append(part.trimmingCharacters(in: .whitespaces))
            }
        }
        return lines.compactMap { line in
            guard line.count >= 2 else { return nil }
            let parts = line.components(separatedBy: CharacterSet(charactersIn: "/／⁄|"))
                .map { $0.trimmingCharacters(in: .whitespaces) }
                .filter { !$0.isEmpty }
            guard let courseName = parts.first else { return nil }
            // 噪声守卫：表头/页脚/说明文字不应成为课程
            if isNoiseCourseName(courseName) { return nil }
            let sectionAndWeeks = parts.count > 1 ? parts[1] : ""
            // 自定义上课时间：`(1-2节)[09:00-10:40]12-14周` —— 提取后剥离，避免污染周次解析
            var customStart: String?
            var customEnd: String?
            var sectionAndWeeksClean = sectionAndWeeks
            if let customMatch = firstMatch(#"\[(\d{2}:\d{2})\s*[-—–~～至]\s*(\d{2}:\d{2})\]"#, in: sectionAndWeeks) {
                customStart = customMatch[0]
                customEnd = customMatch[1]
                sectionAndWeeksClean = sectionAndWeeks.replacingOccurrences(
                    of: #"\[(\d{2}:\d{2})\s*[-—–~～至]\s*(\d{2}:\d{2})\]"#, with: "", options: .regularExpression
                )
            }
            let locationRaw = parts.count > 2 ? parts[2] : ""
            let teacherRaw = parts.count > 3 ? parts.dropFirst(3).joined(separator: "/") : ""
            var campusAlias: String?
            var location = locationRaw
            if let campus = firstMatch(#"^[（(]\s*([^）)]{1,4})\s*[）)]"#, in: locationRaw) {
                campusAlias = campus[0].trimmingCharacters(in: .whitespaces)
                location = locationRaw.replacingOccurrences(
                    of: #"^[（(]\s*[^）)]{1,4}\s*[）)]"#, with: "", options: .regularExpression
                ).trimmingCharacters(in: .whitespaces)
            }
            let teacher = normalizePlaceholder(
                teacherRaw.replacingOccurrences(
                    of: #"[（(][^）)]{1,3}[）)]\s*$"#, with: "", options: .regularExpression
                ).trimmingCharacters(in: .whitespaces)
            )
            return ParsedEntry(
                courseName: courseName,
                teacher: teacher,
                location: normalizePlaceholder(location.isEmpty ? locationRaw : location),
                campusAlias: campusAlias,
                weekRules: parseWeekRules(sectionAndWeeksClean, defaultTotalWeeks: defaultTotalWeeks),
                customStartTime: customStart,
                customEndTime: customEnd,
                rawText: line
            )
        }
    }

    static func sectionsOf(_ text: String) -> (Int, Int)? {
        guard let match = firstMatch(#"[（(]?\s*(\d{1,2})\s*[-—–~～至]\s*(\d{1,2})\s*节?\s*[）)]?"#, in: text),
              let start = Int(match[0]), let end = Int(match[1]),
              (1...12).contains(start), (1...12).contains(end) else { return nil }
        return (min(start, end), max(start, end))
    }

    private static let chineseRowStart: [Character: Int] = ["一": 1, "二": 3, "三": 5, "四": 7, "五": 9, "六": 11]

    static func sectionRangeFromLabel(_ text: String) -> (Int, Int)? {
        let normalized = text.trimmingCharacters(in: .whitespacesAndNewlines)
            .replacingOccurrences(of: " ", with: "")
        guard !normalized.isEmpty else { return nil }
        if let match = firstMatch(#"(\d{1,2})\s*[-—–~～至]\s*(\d{1,2})"#, in: normalized),
           let start = Int(match[0]), let end = Int(match[1]),
           (1...12).contains(start), (1...12).contains(end) {
            return (min(start, end), max(start, end))
        }
        if let match = firstMatch(#"^(\d{1,2})节?$"#, in: normalized), let digits = match.first {
            if digits.count == 2, let first = Int(String(digits.first!)), let second = Int(String(digits.last!)), second > first {
                return (first, second)
            }
            if let single = Int(digits) {
                return single >= 9 ? (9, 12) : (single, min(12, single + 1))
            }
        }
        let starts = normalized.compactMap { chineseRowStart[$0] }
        if !starts.isEmpty {
            let start = starts.min()!
            let end = starts.count == 1 ? start + 1 : starts.max()! + 1
            return (start, min(12, end))
        }
        let digits = matches(#"\d{1,2}"#, in: normalized).compactMap { Int($0) }
        if digits.count >= 2 { return (min(digits[0], digits[1]), max(digits[0], digits[1])) }
        return nil
    }

    struct Metadata {
        var semester: String?
        var startYear: Int?
        var startMonth: Int?
        var startDay: Int?
        var totalWeeks: Int?
        var otherCourseLines: [String]
        /// 课表级公共备注行（去重后最多 10 条，原样保留）
        var notes: [String] = []
    }

    /// 底部备注提取：从原始文本中挑出独立备注行。
    /// 备注 = 「其他课程：」汇总行 + 独立「名称（教师）1-19周」类备注行。
    /// 学期时间行（本学期…至…，共N周）不属于备注，不再收录显示。
    /// 过滤掉：带星期/节次标记的课程行。
    /// 另识别分享文本里的「# 备注：xxx」行并剥前缀收录。
    static func extractNotes(from text: String) -> [String] {
        var collected: [String] = []
        var seen = Set<String>()
        func append(_ line: String) {
            guard collected.count < 10, !seen.contains(line) else { return }
            seen.insert(line)
            collected.append(line)
        }
        for rawLine in text.components(separatedBy: .newlines) {
            let line = rawLine.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !line.isEmpty else { continue }
            // 分享文本备注行：「# 备注：军事技能（张爽）1-19周」→ 剥前缀
            if let shareNote = firstMatch(#"^#\s*备注[：:]\s*(.+)$"#, in: line)?.first {
                append(shareNote.trimmingCharacters(in: .whitespaces))
                continue
            }
            // 排除条件 = 星期标记存在 OR 显式节次标记存在（含"节"字的 N-M节/第N节）。
            // 注意：不得用 sectionRangeFromLabel（其宽松回退会把 "1-19周" 误判为节次）。
            if weekdayIndex(line) != nil { continue }
            if firstMatch(#"\d{1,2}\s*[-—–~～至]\s*\d{1,2}\s*节|第\s*\d{1,2}\s*节"#, in: line) != nil { continue }
            // 「其他课程」汇总行（含 OCR 变体）→ 规范为「其他课程：」备注内容（备注 = 其他课程）
            let otherLine = #"^.{0,2}课程[：:]"#
            if let range = line.range(of: otherLine, options: .regularExpression) {
                append(line.replacingCharacters(in: range, with: "其他课程："))
                continue
            }
            // 独立备注行：名称（教师）1-19周
            let matchesA = firstMatch(#"\S+[（(][^）)]{1,6}[）)]\s*\d{1,2}\s*[-—–~～至]\s*\d{1,2}\s*周"#, in: line) != nil
            if matchesA {
                append(line)
            }
        }
        return collected
    }

    /// 已存旧课表数据兜底清洗：仅保留「其他课程」汇总行（含 OCR 变体如「淇他课程」「其它课程」，
    /// 统一规范为「其他课程：」）与「名称（教师）1-19周」类备注行，
    /// 丢弃旧版本误收的学期时间行（本学期…至…共N周）等，保证旧数据也符合新备注口径。
    static func sanitizeStoredNotes(_ notes: [String]) -> [String] {
        let teacherWeek = #"\S+[（(][^）)]{1,6}[）)]\s*\d{1,2}\s*[-—–~～至]\s*\d{1,2}\s*周"#
        let otherLine = #"^.{0,2}课程[：:]"#
        return notes.compactMap { raw -> String? in
            let line = raw.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !line.isEmpty else { return nil }
            if let range = line.range(of: otherLine, options: .regularExpression) {
                return line.replacingCharacters(in: range, with: "其他课程：")
            }
            if firstMatch(teacherWeek, in: line) != nil { return line }
            return nil
        }
    }

    static func parsePageMetadata(_ text: String) -> Metadata {
        var semester: String?
        if let match = firstMatch(#"(\d{4})\s*-\s*(\d{4})\s*年\s*第?\s*([一二1-3])\s*学期"#, in: text) {
            let term: [String: String] = ["一": "第1学期", "1": "第1学期", "二": "第2学期", "2": "第2学期", "三": "第3学期", "3": "第3学期"]
            semester = "\(match[0])-\(match[1])年\(term[match[2]] ?? "")"
        }
        let startMatch = firstMatch(#"本学期\s*(\d{4})\s*[-/年]\s*(\d{1,2})\s*[-/月]\s*(\d{1,2})"#, in: text)
            ?? firstMatch(#"(\d{4})\s*年?\s*[-/年]\s*(\d{1,2})\s*[-/月]\s*(\d{1,2})"#, in: text)
        let weekMatches = matches(#"共\s*(\d{1,2})\s*周"#, in: text).compactMap { Int($0) }.filter { (1...30).contains($0) }
        var others: [String] = []
        if let other = firstMatch(#"其他课程[：:]\s*(.+)"#, in: text)?.first {
            others = other.components(separatedBy: CharacterSet(charactersIn: "；;"))
                .map { $0.trimmingCharacters(in: .whitespaces) }
                .filter { !$0.isEmpty }
        }
        return Metadata(
            semester: semester,
            startYear: startMatch.flatMap { Int($0[0]) },
            startMonth: startMatch.flatMap { Int($0[1]) },
            startDay: startMatch.flatMap { Int($0[2]) },
            totalWeeks: weekMatches.max(),
            otherCourseLines: others,
            notes: extractNotes(from: text)
        )
    }

    /// 「XX校区上课时间」分块表 → 多套节次时间表
    static func parseTimeTables(_ text: String) -> [SectionTimeProfile] {
        let lines = text.components(separatedBy: .newlines).map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty }
        var profiles: [SectionTimeProfile] = []
        var group: String?
        var columns: [String] = []
        var rows: [(section: (Int, Int), times: [String])] = []

        func flush() {
            guard let group, !rows.isEmpty else { rows = []; columns = []; return }
            if columns.count <= 1 {
                let blocks: [SectionBlock] = rows.compactMap { row in
                    guard let time = row.times.first else { return nil }
                    let parts = time.components(separatedBy: CharacterSet(charactersIn: "-—–~～至"))
                    guard parts.count >= 2 else { return nil }
                    return SectionBlock(startSection: row.section.0, endSection: row.section.1,
                                        startTime: parts[0], endTime: parts[1])
                }
                if !blocks.isEmpty { profiles.append(SectionTimeProfile(id: "ios-\(group)", name: group, blocks: blocks)) }
            } else {
                for (index, name) in columns.enumerated() {
                    let blocks: [SectionBlock] = rows.compactMap { row in
                        guard row.times.count > index else { return nil }
                        let parts = row.times[index].components(separatedBy: CharacterSet(charactersIn: "-—–~～至"))
                        guard parts.count >= 2 else { return nil }
                        return SectionBlock(startSection: row.section.0, endSection: row.section.1,
                                            startTime: parts[0], endTime: parts[1])
                    }
                    if !blocks.isEmpty {
                        profiles.append(SectionTimeProfile(id: "ios-\(group)-\(name)", name: "\(group)·\(name)", blocks: blocks))
                    }
                }
            }
            rows = []
            columns = []
        }

        for line in lines {
            if let header = firstMatch(#"^(.{2,20}?校区).*上课时间"#, in: line)?.first {
                flush()
                group = header
                continue
            }
            guard group != nil else { continue }
            let times = matches(#"\d{1,2}\s*[:：]\s*\d{2}\s*[-—–~～至]\s*\d{1,2}\s*[:：]\s*\d{2}"#, in: line)
                .map { $0.replacingOccurrences(of: "：", with: ":") }
            if let label = firstMatch(#"^\s*(\d{1,2})\s*[-—–~～]?\s*(\d{1,2})?\s*节"#, in: line), !times.isEmpty {
                let section = resolveSectionBlock(label[0], explicitEnd: label.count > 1 ? Int(label[1]) : nil)
                rows.append((section, times))
                continue
            }
            if times.isEmpty, line.contains("楼") || line.contains("场") {
                columns = line.components(separatedBy: CharacterSet(charactersIn: " |｜"))
                    .map { $0.trimmingCharacters(in: .whitespaces) }
                    .filter { !$0.isEmpty && $0 != "节次" }
            }
        }
        flush()
        return profiles
    }

    private static func resolveSectionBlock(_ raw: String, explicitEnd: Int?) -> (Int, Int) {
        if let explicitEnd, (1...12).contains(explicitEnd) {
            let start = Int(raw) ?? 1
            return start <= explicitEnd ? (start, explicitEnd) : (explicitEnd, start)
        }
        if raw.count == 2, let first = Int(String(raw.first!)), let second = Int(String(raw.last!)), second > first {
            return (first, second)
        }
        let single = Int(raw) ?? 1
        return single >= 9 ? (9, 12) : (single, min(12, single + 1))
    }

    /// 导入总入口：rawText（粘贴/文本文件）或 rows（CSV 网格）
    static func parse(rawText: String, rows: [[String]]? = nil) -> Result {
        let metadata = parsePageMetadata(rawText)
        let totalWeeks = metadata.totalWeeks ?? 20
        var warnings: [String] = []
        var collected: [ParsedCourse] = []
        // 时间表提前解析，供「时间→节次」映射（通用算法：节次映射与时间对齐）
        let timeTables = parseTimeTables(rawText)

        if let rows, !rows.isEmpty {
            if let headerIndex = rows.firstIndex(where: { row in row.filter { weekdayIndex($0) != nil }.count >= 4 }) {
                let columns = rows[headerIndex].enumerated().compactMap { index, cell -> (Int, Int)? in
                    guard let weekday = weekdayIndex(cell) else { return nil }
                    return (index, weekday)
                }
                for rowIndex in (headerIndex + 1)..<rows.count {
                    let row = rows[rowIndex]
                    // 行级噪声过滤：表头/页脚/「其他课程」/「注一」等整行文本不得进入星期列
                    if isNoiseLine(row.joined(separator: " ")) { continue }
                    let rowSection = row.first.flatMap { sectionRangeFromLabel($0) }
                    for (index, weekday) in columns {
                        guard index < row.count else { continue }
                        let cell = row[index].trimmingCharacters(in: .whitespaces)
                        guard !cell.isEmpty else { continue }
                        for entry in parseCellEntries(cell, defaultTotalWeeks: totalWeeks) {
                            // 节次来源优先级：单元格内数字节次 → 行标签 → 时间区间映射
                            let sections = sectionsOf(entry.rawText) ?? rowSection ?? timeToSections(entry.rawText, timeTables: timeTables)
                            guard let sections else {
                                warnings.append("「\(entry.courseName)」缺少节次信息，已跳过")
                                continue
                            }
                            collected.append(ParsedCourse(
                                courseName: entry.courseName,
                                teacher: entry.teacher,
                                meetings: [(Weekday.allCases[weekday - 1], sections.0, sections.1, entry.weekRules, entry.location, entry.campusAlias, entry.customStartTime, entry.customEndTime)]
                            ))
                        }
                    }
                }
            }
        } else {
            for line in rawText.components(separatedBy: .newlines) {
                guard let weekday = weekdayIndex(line), let sections = sectionRangeFromLabel(line) else { continue }
                let cleaned = line.replacingOccurrences(
                    of: #"星期[一二三四五六日天]|周[一二三四五六日天]"#, with: "", options: .regularExpression
                )
                for entry in parseCellEntries(cleaned, defaultTotalWeeks: totalWeeks) {
                    collected.append(ParsedCourse(
                        courseName: entry.courseName,
                        teacher: entry.teacher,
                        meetings: [(Weekday.allCases[weekday - 1], sections.0, sections.1, entry.weekRules, entry.location, entry.campusAlias, entry.customStartTime, entry.customEndTime)]
                    ))
                }
            }
            if collected.isEmpty { warnings.append("未识别到课表结构，请确认内容或改用粘贴文本导入") }
        }

        // 合并主键：课程名 + 教师 + 地点（通用算法第 7 条）
        var order: [String] = []
        var grouped: [String: [ParsedCourse]] = [:]
        for course in collected {
            let key = "\(course.courseName)|\(course.teacher)|\(course.meetings.first?.location ?? "")"
            if grouped[key] == nil { grouped[key] = []; order.append(key) }
            grouped[key]?.append(course)
        }
        let courses = order.compactMap { key -> ParsedCourse? in
            guard let group = grouped[key], let first = group.first else { return nil }
            return ParsedCourse(
                courseName: first.courseName,
                teacher: first.teacher,
                meetings: group.flatMap { $0.meetings }
            )
        }

        // 冲突标记：同时段多课全部保留并警告（通用算法：冲突处理）
        warnings.append(contentsOf: detectConflicts(courses))
        var aliases: [String] = []
        for course in courses {
            for meeting in course.meetings {
                if let alias = meeting.campusAlias, !aliases.contains(alias) { aliases.append(alias) }
            }
        }
        // 表外课程（「其他课程：」汇总行）：缺少星期/节次，无法放入课表网格，仅提示
        if !metadata.otherCourseLines.isEmpty {
            warnings.append("检测到表外课程（缺少星期与节次，未纳入课表）："
                + metadata.otherCourseLines.prefix(5).joined(separator: "；"))
        }
        let startDate: String? = {
            guard let year = metadata.startYear else { return nil }
            return String(format: "%04d-%02d-%02d", year, metadata.startMonth ?? 9, metadata.startDay ?? 1)
        }()
        return Result(
            courses: courses,
            semester: metadata.semester,
            semesterStartDate: startDate,
            totalWeeks: metadata.totalWeeks,
            timeTables: timeTables,
            campusAliases: aliases,
            warnings: warnings,
            notes: metadata.notes
        )
    }

    /// 节次映射与时间对齐（通用算法第 5、6 条）：单元格只写时间时用校区时间表映射回标准节次。
    private static func timeToSections(_ text: String, timeTables: [SectionTimeProfile]) -> (Int, Int)? {
        guard !text.isEmpty, !timeTables.isEmpty else { return nil }
        guard let match = firstMatch(#"(\d{1,2}\s*[:：]\s*\d{2})\s*[-—–~～至]\s*(\d{1,2}\s*[:：]\s*\d{2})"#, in: text) else { return nil }
        let start = match[1].replacingOccurrences(of: "：", with: ":").replacingOccurrences(of: " ", with: "")
        let end = match[2].replacingOccurrences(of: "：", with: ":").replacingOccurrences(of: " ", with: "")
        for profile in timeTables {
            for block in profile.blocks where block.startTime == start && block.endTime == end {
                return (block.startSection, block.endSection)
            }
        }
        for profile in timeTables {
            for block in profile.blocks where block.startTime == start || block.endTime == end {
                return (block.startSection, block.endSection)
            }
        }
        return nil
    }

    /// 两组周次规则是否有交集（区间重叠 + 奇偶兼容）。
    private static func weekRulesOverlap(_ a: [WeekRule], _ b: [WeekRule]) -> Bool {
        for ra in a {
            for rb in b {
                if ra.endWeek < rb.startWeek || rb.endWeek < ra.startWeek { continue }
                if ra.parity == .all || rb.parity == .all || ra.parity == rb.parity { return true }
            }
        }
        return false
    }

    /// 冲突检测：同星期、节次重叠、周次相交的不同课程 → 警告（保留全部）。
    private static func detectConflicts(_ courses: [ParsedCourse]) -> [String] {
        var warnings: [String] = []
        var seen = Set<String>()
        var bySlot: [String: [ParsedCourse]] = [:]
        for course in courses {
            for meeting in course.meetings {
                let key = "\(meeting.weekday.rawValue)|\(meeting.startSection)-\(meeting.endSection)"
                bySlot[key, default: []].append(course)
            }
        }
        let weekdayNames = ["星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日"]
        for (slotKey, slotCourses) in bySlot {
            var distinct: [ParsedCourse] = []
            var names = Set<String>()
            for course in slotCourses where !names.contains(course.courseName.trimmingCharacters(in: .whitespaces)) {
                names.insert(course.courseName.trimmingCharacters(in: .whitespaces))
                distinct.append(course)
            }
            guard distinct.count >= 2 else { continue }
            for i in 0..<distinct.count {
                for j in (i + 1)..<distinct.count {
                    if sameSlotMeetingsOverlap(distinct[i], distinct[j]) {
                        let parts = slotKey.split(separator: "|")
                        let weekdayIndex = Int(parts[0]) ?? 0
                        let label = (weekdayNames.indices.contains(weekdayIndex - 1) ? weekdayNames[weekdayIndex - 1] : "周\(weekdayIndex)")
                            + " \(parts[1]) 节"
                        let message = "时间冲突：\(label) \(distinct[i].courseName) 与 \(distinct[j].courseName) 同时段，已全部保留，请核对"
                        if !seen.contains(message) {
                            seen.insert(message)
                            warnings.append(message)
                        }
                    }
                }
            }
        }
        return warnings
    }

    private static func sameSlotMeetingsOverlap(_ a: ParsedCourse, _ b: ParsedCourse) -> Bool {
        for ma in a.meetings {
            for mb in b.meetings {
                if ma.weekday != mb.weekday { continue }
                if ma.startSection > mb.endSection || mb.startSection > ma.endSection { continue }
                if weekRulesOverlap(ma.weekRules, mb.weekRules) { return true }
            }
        }
        return false
    }

    private static let noiseLinePatterns = [
        #"^(其他课程|说明|备注|打印时间|本学期|内容顺序|专业|节次|校区|上课时间|课程表?)[：:]?"#,
        #"^注[一二三四五]?[：:]?"#,
        #"^(上午|下午|晚上)$"#,
        #"^学期"#,
        #"^\d{4}\s*[-—–~～至/]\s*\d{4}\s*年?"#,
    ]
    private static let noiseCellPatterns = [
        #"^(其他课程|说明|备注|打印时间|本学期|内容顺序|专业|节次|校区|上课时间|课程表?)[：:]?"#,
        #"^注[一二三四五]?[：:]?"#,
        #"^星期[一二三四五六日天]$"#,
        #"^(上午|下午|晚上)$"#,
        #"^学期"#,
        #"^\d{4}\s*[-—–~～至/]\s*\d{4}\s*年?"#,
    ]
    private static let maxCourseNameLength = 40

    /// 整行是否应被当作噪声跳过（不解析为课程）。
    static func isNoiseLine(_ text: String) -> Bool {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty { return true }
        for pattern in noiseLinePatterns where firstMatch(pattern, in: trimmed) != nil { return true }
        // 兜底：真正的课程行必然带「/」分隔或「节/周」标记
        let hasCourseMark = trimmed.contains("/") || trimmed.contains("节") || trimmed.contains("周")
        return !hasCourseMark
    }

    /// 单元格中的课程名是否像噪声（丢弃）。
    static func isNoiseCourseName(_ name: String) -> Bool {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty { return true }
        if trimmed.count > maxCourseNameLength { return true }
        for pattern in noiseCellPatterns where firstMatch(pattern, in: trimmed) != nil { return true }
        return firstMatch(#"^\d{1,2}\s*[:：]\s*\d{2}"#, in: trimmed) != nil
    }

    /// 分享文本占位符归一化：空字段用 "-" 占位，导入时还原为空。
    static func normalizePlaceholder(_ value: String) -> String {
        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed == "-" || trimmed == "—" ? "" : trimmed
    }

    // MARK: - 正则工具

    private static func firstMatch(_ pattern: String, in text: String) -> [String]? {
        guard let regex = try? NSRegularExpression(pattern: pattern) else { return nil }
        let range = NSRange(text.startIndex..<text.endIndex, in: text)
        guard let match = regex.firstMatch(in: text, range: range) else { return nil }
        var groups: [String] = []
        for index in 1..<match.numberOfRanges {
            if let groupRange = Range(match.range(at: index), in: text) {
                groups.append(String(text[groupRange]))
            }
        }
        return groups
    }

    private static func matches(_ pattern: String, in text: String) -> [String] {
        guard let regex = try? NSRegularExpression(pattern: pattern) else { return [] }
        let range = NSRange(text.startIndex..<text.endIndex, in: text)
        return regex.matches(in: text, range: range).compactMap { match in
            guard let groupRange = Range(match.range(at: match.numberOfRanges > 1 ? 1 : 0), in: text) else { return nil }
            return String(text[groupRange])
        }
    }
}
