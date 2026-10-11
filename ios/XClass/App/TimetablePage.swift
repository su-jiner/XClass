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
                    if !notes.isEmpty { notesBar }
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
                CourseDetailSheet(palette: palette, course: item.course, meeting: item.meeting,
                                  onEdit: {
                                      editingItem = (item.courseIndex, item.course, item.meeting)
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
                    onTap: { courseIndex, course, meeting in
                        detailItem = CourseDetailItem(courseIndex: courseIndex, course: course, meeting: meeting)
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
        VStack(alignment: .leading, spacing: 3) {
            ForEach(notes, id: \.self) { note in
                Text("• \(note)")
                    .font(.system(size: 10))
                    .foregroundColor(palette.textSecondary)
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 10).fill(palette.panelAlt))
        .padding(.horizontal, 12)
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
    let onTap: (Int, CourseSchedule, MeetingTime) -> Void

    private let leftColumnWidth: CGFloat = 44
    private let rowHeight: CGFloat = 46

    private var sectionCount: Int { max(sectionRanges.count, 1) }

    var body: some View {
        GeometryReader { geo in
            let dayWidth = max((geo.size.width - leftColumnWidth - 16) / 7, 38)
            HStack(spacing: 0) {
                VStack(spacing: 0) {
                    Text("\(weekStart.month)月")
                        .font(.system(size: 10, weight: .bold))
                        .foregroundColor(palette.textPrimary)
                        .frame(height: 34)
                    ForEach(1...sectionCount, id: \.self) { section in
                        // 时间跟随课程：该节次有课程才显示起止时间，无课显示空
                        let hasCourse = items.contains { item in
                            section >= item.meeting.startSection && section <= item.meeting.endSection
                        }
                        VStack(spacing: 0) {
                            Text("\(section)").font(.system(size: 11 * fontScale, weight: .bold))
                            if hasCourse {
                                let parts = sectionRanges.indices.contains(section - 1)
                                    ? sectionRanges[section - 1].components(separatedBy: "-") : []
                                if parts.count == 2 {
                                    Text(parts[0]).font(.system(size: 8 * fontScale))
                                    Text(parts[1]).font(.system(size: 8 * fontScale))
                                }
                            }
                        }
                        .foregroundColor(palette.textSecondary)
                        .frame(width: leftColumnWidth - 6, height: rowHeight)
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
                    card(for: item, width: width - 2)
                        .offset(y: CGFloat(item.meeting.startSection - 1) * rowHeight + 1)
                }
            }
            .frame(width: width, height: rowHeight * CGFloat(sectionCount))
            .clipShape(RoundedRectangle(cornerRadius: 4))
        }
    }

    private func card(for item: (courseIndex: Int, course: CourseSchedule, meeting: MeetingTime, isCurrentWeek: Bool, nextActiveWeek: Int),
                      width: CGFloat) -> some View {
        let height = CGFloat(item.meeting.endSection - item.meeting.startSection + 1) * rowHeight - 2
        let color = cardColors[item.courseIndex % cardColors.count]
        let isCover = AppPreferences.coverImageKey == "\(item.meeting.weekday.rawValue):\(item.meeting.startSection)"
        return Button {
            onTap(item.courseIndex, item.course, item.meeting)
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
    let course: CourseSchedule
    let meeting: MeetingTime
    var onEdit: (() -> Void)?

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            RoundedRectangle(cornerRadius: 2)
                .fill(palette.textSecondary.opacity(0.4))
                .frame(width: 36, height: 4)
                .frame(maxWidth: .infinity)
            Text(course.courseName)
                .font(.system(size: 18, weight: .bold))
                .foregroundColor(palette.detailTitle)
            row("教师：", course.teacher.isEmpty ? "未知" : course.teacher)
            row("节次：", "\(Weekday(rawValue: meeting.weekday.rawValue)?.shortLabel ?? "") 第\(meeting.startSection)-\(meeting.endSection)节 \(sectionTime)")
            row("周次：", weekText)
            row("地点：", meeting.location.isEmpty ? "未标注" : meeting.location)
            row("学期：", "\(course.semester)   学分：\(course.credit.map { "\($0)" } ?? "N/A")")
            Button(isCover ? "取消课表封面" : "设为课表封面") {
                AppPreferences.coverImageKey = isCover ? "" : coverKey
                StoreChangeNotifier.shared.notifyChanged()
            }
            .font(.system(size: 14, weight: .medium))
            .foregroundColor(isCover ? palette.textSecondary : palette.iconTint)
            .frame(maxWidth: .infinity)
            if let onEdit = onEdit {
                Button("编辑课程（修改同步所有周）") { onEdit() }
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundColor(palette.buttonText)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 10)
                    .background(RoundedRectangle(cornerRadius: 10).fill(palette.buttonBackground))
            }
            Spacer()
        }
        .padding(20)
        .background(palette.detailBackground)
    }

    private var coverKey: String { "\(meeting.weekday.rawValue):\(meeting.startSection)" }
    private var isCover: Bool { AppPreferences.coverImageKey == coverKey }

    private var detailBackground: Color {
        palette.panelBackground
    }

    private var sectionTime: String {
        let start = SectionTimes.ranges.indices.contains(meeting.startSection - 1)
            ? String(SectionTimes.ranges[meeting.startSection - 1].prefix(5)) : "--:--"
        let end = SectionTimes.ranges.indices.contains(meeting.endSection - 1)
            ? String(SectionTimes.ranges[meeting.endSection - 1].suffix(5)) : "--:--"
        return "\(start)-\(end)"
    }

    private var weekText: String {
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
