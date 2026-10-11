import SwiftUI

struct TimetablePage: View {
    let palette: ThemePalette
    @EnvironmentObject var notifier: StoreChangeNotifier
    @Environment(\.colorScheme) private var systemScheme

    @State private var courses: [CourseSchedule] = []
    @State private var semesterStart = SimpleDate.today()
    @State private var currentWeek = 1
    @State private var totalWeeks = WeekScheduleCalculator.defaultTotalWeeks
    @State private var selectedWeek = 1
    @State private var detailItem: CourseDetailItem?
    @State private var detailGroup: [CourseDetailItem] = []
    @State private var showAddSheet = false
    @State private var showImportSheet = false
    @State private var showAddChooser = false
    @State private var showEditSheet = false
    @State private var editingItem: (index: Int, course: CourseSchedule, meeting: MeetingTime)?
    @State private var sectionRanges: [String] = SectionTimes.ranges
    @State private var notes: [String] = []

    var body: some View {
        NavigationStack {
            ZStack {
                // 自定义背景（设置页选择，存沙箱 custom_background.jpg）
                if !AppPreferences.customBackgroundPath.isEmpty {
                    Image(uiImage: (try? UIImage(contentsOfFile: AppPreferences.customBackgroundPath)) ?? UIImage())
                        .resizable()
                        .scaledToFill()
                        .opacity(0.35)
                        .ignoresSafeArea()
                }
                VStack(spacing: 0) {
                    header
                    // 网格常显：时间列时间跟随课程（无课节次时间为空，空课表显示空时间列而非整页空白）
                    weekPager
                }
            }
            .background(palette.pageBackground)
            .navigationBarHidden(true)
            .sheet(isPresented: $showAddSheet, onDismiss: { reload() }) {
                AddCourseSheet(palette: palette, guessWeek: currentWeek)
            }
            .sheet(isPresented: $showImportSheet, onDismiss: { reload() }) {
                ImportView(palette: palette)
            }
            .confirmationDialog("添加课程", isPresented: $showAddChooser, titleVisibility: .visible) {
                Button("手动添加课程") { showAddSheet = true }
                Button("导入课表文件 / 粘贴文本") { showImportSheet = true }
                Button("取消", role: .cancel) {}
            }
            .sheet(item: $detailItem) { item in
                let items = detailGroup.isEmpty ? [item] : detailGroup
                CourseDetailSheet(palette: palette, items: items,
                                  onEdit: { edited in
                                      editingItem = (edited.courseIndex, edited.course, edited.meeting)
                                      detailItem = nil
                                      showEditSheet = true
                                  })
                    .presentationDetents([.medium, .large])
            }
            .sheet(isPresented: $showEditSheet, onDismiss: { reload() }) {
                if let editing = editingItem {
                    AddCourseSheet(palette: palette, guessWeek: currentWeek,
                                   editing: editing, editingProfileId: nil)
                }
            }
            .onAppear { reload() }
            .onChange(of: notifier.version) { _ in reload() }
        }
    }

    private var dark: Bool { effectiveDark }

    private var effectiveDark: Bool {
        switch AppPreferences.darkMode {
        case "light": return false
        case "dark": return true
        default: return systemScheme == .dark
        }
    }

    private var header: some View {
        HStack(alignment: .center) {
            VStack(alignment: .leading, spacing: 2) {
                Text(headerWeekText)
                    .font(.system(size: 15, weight: .bold))
                    .foregroundColor(palette.textPrimary)
                Text(SimpleDate.today().displayText)
                    .font(.system(size: 12))
                    .foregroundColor(palette.textSecondary)
            }
            Spacer()
            Button { showAddChooser = true } label: {
                Image(systemName: "plus").foregroundColor(palette.iconTint)
            }
            .padding(8)
            .background(Circle().fill(palette.panelBackground))
            .padding(.trailing, 6)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 6)
    }

    private var headerWeekText: String {
        let weekdayLabel = Weekday(rawValue: SimpleDate.today().weekdayMonBased)?.shortLabel ?? "一"
        return selectedWeek == currentWeek ? "第\(selectedWeek)周 周\(weekdayLabel)" : "第\(selectedWeek)周（非本周）"
    }

    private var weekPager: some View {
        TabView(selection: $selectedWeek) {
            ForEach(1...max(totalWeeks, 1), id: \.self) { week in
                WeekGridView(
                    palette: palette,
                    week: week,
                    today: SimpleDate.today(),
                    weekStart: semesterStart.adding(days: (week - 1) * 7),
                    items: WeekScheduleCalculator.displayMeetings(of: courses, week: week,
                                                                  showNonCurrent: false),
                    fontScale: AppPreferences.fontScale,
                    currentSection: currentSection,
                    showDates: true,
                    sectionRanges: sectionRanges,
                    notes: notes,
                    onTap: { courseIndex, course, meeting, conflicts in
                        detailGroup = conflicts.map { CourseDetailItem(courseIndex: $0.courseIndex, course: $0.course, meeting: $0.meeting) }
                        detailItem = detailGroup.first ?? CourseDetailItem(courseIndex: courseIndex, course: course, meeting: meeting)
                    }
                )
                .tag(week)
            }
        }
        .tabViewStyle(.page(indexDisplayMode: .never))
    }

    private var currentSection: Int? {
        let formatter = DateFormatter()
        formatter.dateFormat = "HH:mm"
        let now = formatter.string(from: Date())
        for (index, range) in sectionRanges.enumerated() {
            let parts = range.components(separatedBy: "-")
            if parts.count == 2, parts[0] <= now, now < parts[1] {
                return index + 1
            }
        }
        return nil
    }

    private var notesBar: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("备注")
                .font(.system(size: 12, weight: .bold))
                .foregroundColor(palette.textPrimary)
            ForEach(notes, id: \.self) { note in
                Text(note)
                    .font(.system(size: 10))
                    .lineSpacing(2)
                    .foregroundColor(palette.textSecondary)
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 9)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(
            RoundedRectangle(cornerRadius: 12)
                .fill(palette.panelAlt)
                .overlay(
                    RoundedRectangle(cornerRadius: 12)
                        .stroke(palette.gridLeft, lineWidth: 1)
                )
        )
        .padding(.horizontal, 12)
        .padding(.top, 6)
        .padding(.bottom, 4)
    }

    private func reload() {
        courses = ProfileStore.shared.loadActiveCourses()
        semesterStart = ProfileStore.shared.getActiveSemesterStartDate()
        totalWeeks = WeekScheduleCalculator.totalWeeks(of: courses)
        currentWeek = WeekScheduleCalculator.guessCurrentWeek(semesterStart: semesterStart, today: .today(), totalWeeks: totalWeeks)
        if selectedWeek < 1 || selectedWeek > totalWeeks {
            selectedWeek = currentWeek
        }
        // 左侧时间列动态跟随当前显示时间表（导入含校区时间表时已自动切换）
        let profile = ProfileStore.shared.currentDisplayTimeProfile()
        let maxSection = max(profile.blocks.map(\.endSection).max() ?? 12, 12)
        sectionRanges = (1...maxSection).map { profile.sectionRange($0) ?? "--:--" }
        notes = ImportView.displayNotes(of: courses)
    }
}

// MARK: - 单周课表网格

struct WeekGridView: View {
    let palette: ThemePalette
    let week: Int
    let today: SimpleDate
    let weekStart: SimpleDate
    let items: [(courseIndex: Int, course: CourseSchedule, meeting: MeetingTime, isCurrentWeek: Bool, nextActiveWeek: Int)]
    let fontScale: Double
    let currentSection: Int?
    var showDates: Bool = true
    var sectionRanges: [String] = SectionTimes.ranges
    var notes: [String] = []
    let onTap: (Int, CourseSchedule, MeetingTime, [(courseIndex: Int, course: CourseSchedule, meeting: MeetingTime)]) -> Void

    private let leftColumnWidth: CGFloat = 44
    private let rowHeight: CGFloat = 46
    /// 最后一节（第 12 节）额外加长：多行课程内容完整显示，而非靠间距留白
    private let lastRowExtra: CGFloat = 18

    private var sectionCount: Int { max(sectionRanges.count, 1) }

    var body: some View {
        ScrollView {
            GeometryReader { geo in
                let dayWidth = max((geo.size.width - leftColumnWidth - 16) / 7, 38)
                HStack(spacing: 0) {
                    VStack(spacing: 0) {
                        Text("\(weekStart.month)月")
                            .font(.system(size: 10, weight: .bold))
                            .foregroundColor(palette.textPrimary)
                            .frame(height: 34)
                        ForEach(1...sectionCount, id: \.self) { section in
                            // 固定编码时间：每节始终显示起止时间
                            VStack(spacing: 0) {
                                Text("\(section)").font(.system(size: 11 * fontScale, weight: .bold))
                                let parts = sectionRanges.indices.contains(section - 1)
                                    ? sectionRanges[section - 1].components(separatedBy: "-") : []
                                if parts.count == 2 {
                                    Text(parts[0]).font(.system(size: 8 * fontScale))
                                    Text(parts[1]).font(.system(size: 8 * fontScale))
                                }
                            }
                            .foregroundColor(palette.textSecondary)
                            .frame(width: leftColumnWidth - 6,
                                   height: section == sectionCount ? rowHeight + lastRowExtra : rowHeight)
                        }
                    }
                    .background(palette.gridLeftColumn)

                    HStack(spacing: 2) {
                        ForEach(0..<7, id: \.self) { index in
                            dayColumn(dayIndex: index, width: dayWidth)
                        }
                    }
                    .padding(.horizontal, 4)
                }
            }
            .frame(height: 34 + CGFloat(sectionCount) * rowHeight + lastRowExtra + 24)
            if !notes.isEmpty {
                VStack(alignment: .leading, spacing: 4) {
                    Text("备注")
                        .font(.system(size: 12, weight: .bold))
                        .foregroundColor(palette.textPrimary)
                    ForEach(notes, id: \.self) { note in
                        Text(note)
                            .font(.system(size: 10))
                            .lineSpacing(2)
                            .foregroundColor(palette.textSecondary)
                    }
                }
                .padding(.horizontal, 12)
                .padding(.vertical, 9)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(
                    RoundedRectangle(cornerRadius: 12)
                        .fill(palette.panelAlt)
                        .overlay(
                            RoundedRectangle(cornerRadius: 12)
                                .stroke(palette.gridLeft, lineWidth: 1)
                        )
                )
                .padding(.horizontal, 12)
                .padding(.top, 22)
                .padding(.bottom, 16)
            }
        }
    }

    private func dayColumn(dayIndex: Int, width: CGFloat) -> some View {
        let date = weekStart.adding(days: dayIndex)
        let isToday = showDates && date == today
        let dayItems = items.filter { $0.meeting.weekday.rawValue == dayIndex + 1 }
        let visible = resolveOverlaps(dayItems)

        return VStack(spacing: 0) {
            VStack(spacing: 0) {
                Text(Weekday(rawValue: dayIndex + 1)?.shortLabel ?? "")
                    .font(.system(size: 12, weight: isToday ? .bold : .regular))
                if showDates {
                    Text("\(date.month)/\(date.day)")
                        .font(.system(size: 9))
                }
            }
            .foregroundColor(palette.textPrimary)
            .frame(width: width, height: 34)
            .background(isToday ? palette.gridHeaderToday : palette.gridHeader)

            ZStack(alignment: .topLeading) {
                RoundedRectangle(cornerRadius: 4)
                    .fill(isToday ? palette.gridDayToday : palette.gridDayCell)
                VStack(spacing: 0) {
                    if let current = currentSection {
                        Rectangle()
                            .fill(palette.iconTint.opacity(0.55))
                            .frame(height: 1.5)
                            .offset(y: CGFloat(current - 1) * rowHeight)
                    }
                }
                ForEach(Array(visible.enumerated()), id: \.offset) { _, item in
                    let group: [(courseIndex: Int, course: CourseSchedule, meeting: MeetingTime)] = dayItems
                        .filter { other in
                            let aStart = min(max(item.meeting.startSection, 1), 12)
                            let aEnd = min(max(item.meeting.endSection, 1), 12)
                            let bStart = min(max(other.meeting.startSection, 1), 12)
                            let bEnd = min(max(other.meeting.endSection, 1), 12)
                            return !(aEnd < bStart || bEnd < aStart)
                        }
                        .map { ($0.courseIndex, $0.course, $0.meeting) }
                        .sorted { $0.2.startSection < $1.2.startSection }
                    card(for: item, width: width - 2, conflicts: group)
                        .offset(y: CGFloat(item.meeting.startSection - 1) * rowHeight + 1)
                }
            }
            .frame(width: width,
                   height: rowHeight * CGFloat(sectionCount) +
                       (sectionCount > 0 ? lastRowExtra : 0))
            .clipShape(RoundedRectangle(cornerRadius: 4))
        }
    }

    private func card(for item: (courseIndex: Int, course: CourseSchedule, meeting: MeetingTime, isCurrentWeek: Bool, nextActiveWeek: Int),
                      width: CGFloat,
                      conflicts: [(courseIndex: Int, course: CourseSchedule, meeting: MeetingTime)]) -> some View {
        let height = CGFloat(item.meeting.endSection - item.meeting.startSection + 1) * rowHeight - 2 +
            (item.meeting.endSection >= sectionCount ? lastRowExtra : 0)
        let color = cardColors[item.courseIndex % cardColors.count]
        let isCover = AppPreferences.coverImageKey == "\(item.meeting.weekday.rawValue):\(item.meeting.startSection)"
        return Button {
            onTap(item.courseIndex, item.course, item.meeting, conflicts)
        } label: {
            VStack(alignment: .leading, spacing: 1) {
                if isCover {
                    Text("封面")
                        .font(.system(size: 7))
                        .padding(.horizontal, 3).padding(.vertical, 1)
                        .background(Capsule().fill(palette.iconTint))
                        .foregroundColor(palette.buttonText)
                }
                if !item.isCurrentWeek {
                    Text("[非本周] 第\(item.nextActiveWeek)周").font(.system(size: 7.5 * fontScale))
                }
                Text(item.course.courseName)
                    .font(.system(size: 12 * fontScale, weight: .semibold))
                    .lineLimit(4)
                Text(item.meeting.location.isEmpty ? "教室待定" : item.meeting.location)
                    .font(.system(size: 9.5 * fontScale))
                    .lineLimit(2)
            }
            .foregroundColor(cardTextColor)
            .padding(3)
            .frame(width: width, height: height, alignment: .topLeading)
            .background(RoundedRectangle(cornerRadius: 5).fill(color))
            .overlay(RoundedRectangle(cornerRadius: 5).stroke(isCover ? palette.iconTint : .clear, lineWidth: 2))
            .opacity(item.isCurrentWeek ? 1 : 0.55)
            .shadow(radius: item.isCurrentWeek ? 0 : 1)
        }
        .buttonStyle(.plain)
    }

    /// 冲突课程按优先级去重（与 Android resolveOverlapForDay 一致）
    private func resolveOverlaps(
        _ items: [(courseIndex: Int, course: CourseSchedule, meeting: MeetingTime, isCurrentWeek: Bool, nextActiveWeek: Int)]
    ) -> [(courseIndex: Int, course: CourseSchedule, meeting: MeetingTime, isCurrentWeek: Bool, nextActiveWeek: Int)] {
        var occupied = [Bool](repeating: false, count: 13)
        let sorted = items.sorted {
            if $0.isCurrentWeek != $1.isCurrentWeek { return $0.isCurrentWeek }
            if $0.nextActiveWeek != $1.nextActiveWeek { return $0.nextActiveWeek < $1.nextActiveWeek }
            return $0.meeting.startSection < $1.meeting.startSection
        }
        var selected: [(courseIndex: Int, course: CourseSchedule, meeting: MeetingTime, isCurrentWeek: Bool, nextActiveWeek: Int)] = []
        for item in sorted {
            let start = min(max(item.meeting.startSection, 1), 12)
            let end = min(max(item.meeting.endSection, 1), 12)
            guard !(start...end).contains(where: { occupied[$0] }) else { continue }
            selected.append(item)
            for section in start...end { occupied[section] = true }
        }
        return selected.sorted { $0.meeting.startSection < $1.meeting.startSection }
    }
}

// MARK: - 课程详情

struct CourseDetailSheet: View {
    let palette: ThemePalette
    let items: [CourseDetailItem]
    var onEdit: ((CourseDetailItem) -> Void)?
    @State private var currentIndex = 0

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            RoundedRectangle(cornerRadius: 2)
                .fill(palette.textSecondary.opacity(0.4))
                .frame(width: 36, height: 4)
                .frame(maxWidth: .infinity)
            if items.count > 1 {
                Text("第 \(currentIndex + 1) / \(items.count) 门")
                    .font(.system(size: 12))
                    .foregroundColor(palette.textSecondary)
                    .frame(maxWidth: .infinity)
            }
            TabView(selection: $currentIndex) {
                ForEach(Array(items.enumerated()), id: \.offset) { index, item in
                    detailPage(item).tag(index)
                }
            }
            .tabViewStyle(.page(indexDisplayMode: .never))
            .frame(height: 330)
        }
        .padding(20)
        .background(palette.panelBackground)
    }

    private func detailPage(_ item: CourseDetailItem) -> some View {
        let course = item.course
        let meeting = item.meeting
        return VStack(alignment: .leading, spacing: 10) {
            Text(course.courseName)
                .font(.system(size: 18, weight: .bold))
                .foregroundColor(palette.detailTitle)
            row("教师：", course.teacher.isEmpty ? "未知" : course.teacher)
            row("节次：", "\(Weekday(rawValue: meeting.weekday.rawValue)?.shortLabel ?? "") 第\(meeting.startSection)-\(meeting.endSection)节 \(sectionTime(meeting))")
            row("周次：", weekText(meeting, course))
            row("地点：", meeting.location.isEmpty ? "未标注" : meeting.location)
            row("学期：", "\(course.semester)   学分：\(course.credit.map { "\($0)" } ?? "N/A")")
            Button(isCover(meeting) ? "取消课表封面" : "设为课表封面") {
                AppPreferences.coverImageKey = isCover(meeting) ? "" : coverKey(meeting)
                StoreChangeNotifier.shared.notifyChanged()
            }
            .font(.system(size: 14, weight: .medium))
            .foregroundColor(isCover(meeting) ? palette.textSecondary : palette.iconTint)
            .frame(maxWidth: .infinity)
            if let onEdit = onEdit {
                Button("编辑课程（修改同步所有周）") { onEdit(item) }
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundColor(palette.buttonText)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 10)
                    .background(RoundedRectangle(cornerRadius: 10).fill(palette.buttonBackground))
            }
            Spacer()
        }
        .frame(maxWidth: .infinity)
        .padding(.horizontal, 2)
    }

    private func coverKey(_ meeting: MeetingTime) -> String { "\(meeting.weekday.rawValue):\(meeting.startSection)" }
    private func isCover(_ meeting: MeetingTime) -> Bool { AppPreferences.coverImageKey == coverKey(meeting) }

    private func sectionTime(_ meeting: MeetingTime) -> String {
        if let custom = meeting.customStartTime, !custom.isEmpty {
            let end = (meeting.customEndTime?.isEmpty == false) ? meeting.customEndTime! : custom
            return "\(custom)-\(end)"
        }
        let start = SectionTimes.ranges.indices.contains(meeting.startSection - 1)
            ? String(SectionTimes.ranges[meeting.startSection - 1].prefix(5)) : "--:--"
        let end = SectionTimes.ranges.indices.contains(meeting.endSection - 1)
            ? String(SectionTimes.ranges[meeting.endSection - 1].suffix(5)) : "--:--"
        return "\(start)-\(end)"
    }

    private func weekText(_ meeting: MeetingTime, _ course: CourseSchedule) -> String {
        if meeting.weekRules.isEmpty {
            return course.rawWeekText.isEmpty ? "未标注" : course.rawWeekText
        }
        return meeting.weekRules.map { rule in
            let range = rule.startWeek == rule.endWeek ? "\(rule.startWeek)周" : "\(rule.startWeek)-\(rule.endWeek)周"
            switch rule.parity {
            case .odd: return range + "(单)"
            case .even: return range + "(双)"
            case .all: return range
            }
        }.joined(separator: "，")
    }

    private func row(_ label: String, _ value: String) -> some View {
        Text(label + value)
            .font(.system(size: 14))
            .foregroundColor(palette.textSecondary)
    }
}

/// 课程详情弹窗携带课程索引，用于「编辑课程」定位。
struct CourseDetailItem: Identifiable {
    let id = UUID()
    let courseIndex: Int
    let course: CourseSchedule
    let meeting: MeetingTime
}
