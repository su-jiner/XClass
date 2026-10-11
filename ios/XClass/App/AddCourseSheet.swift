import SwiftUI

/// 手动添加课程 / 编辑课程共用表单。
/// editing 非空时进入编辑模式：预填课程信息，保存时整体替换课程定义（所有周同步）。
struct AddCourseSheet: View {
    let palette: ThemePalette
    let guessWeek: Int
    var editing: (index: Int, course: CourseSchedule, meeting: MeetingTime)?
    var editingProfileId: String?

    @Environment(\.dismiss) private var dismiss

    @State private var courseName = ""
    @State private var teacher = ""
    @State private var location = ""
    @State private var weekday = Weekday.monday
    @State private var startSection = 1
    @State private var endSection = 2
    @State private var startWeek = 1
    @State private var endWeek = 1
    @State private var parity: WeekParity = .all
    @State private var customStart = ""
    @State private var customEnd = ""
    @State private var errorMessage: String?

    init(palette: ThemePalette, guessWeek: Int,
         editing: (index: Int, course: CourseSchedule, meeting: MeetingTime)? = nil,
         editingProfileId: String? = nil) {
        self.palette = palette
        self.guessWeek = guessWeek
        self.editing = editing
        self.editingProfileId = editingProfileId
    }

    var body: some View {
        NavigationStack {
            Form {
                Section("课程信息") {
                    TextField("课程名称", text: $courseName)
                    TextField("授课教师（可留空）", text: $teacher)
                    TextField("上课地点（可留空）", text: $location)
                }
                Section("时间信息") {
                    Picker("星期", selection: $weekday) {
                        ForEach(Weekday.allCases, id: \.self) { day in
                            Text("周\(day.shortLabel)").tag(day)
                        }
                    }
                    Stepper("开始节：\(startSection)", value: $startSection, in: 1...12)
                    Stepper("结束节：\(endSection)", value: $endSection, in: 1...12)
                    Text(sectionPreview)
                        .font(.footnote)
                        .foregroundColor(palette.textSecondary)
                    HStack {
                        TextField("自定义开始 如 09:00", text: $customStart)
                            .keyboardType(.numbersAndPunctuation)
                        TextField("自定义结束 如 10:40", text: $customEnd)
                            .keyboardType(.numbersAndPunctuation)
                    }
                    .font(.footnote)
                    Stepper("开始周：第\(startWeek)周", value: $startWeek, in: 1...30)
                    Stepper("结束周：第\(endWeek)周", value: $endWeek, in: 1...30)
                    Picker("单双周规则", selection: $parity) {
                        Text("单双周都上").tag(WeekParity.all)
                        Text("仅单周").tag(WeekParity.odd)
                        Text("仅双周").tag(WeekParity.even)
                    }
                }
                if let errorMessage = errorMessage {
                    Section { Text(errorMessage).foregroundColor(.red).font(.footnote) }
                }
            }
            .navigationTitle(editing == nil ? "手动添加课程" : "编辑课程")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(editing == nil ? "保存" : "保存（同步所有周）") { save() }
                }
            }
            .onAppear(perform: prefill)
        }
    }

    private func prefill() {
        if let editing = editing {
            let rule = editing.meeting.weekRules.first
            courseName = editing.course.courseName
            teacher = editing.course.teacher
            location = editing.meeting.location
            weekday = editing.meeting.weekday
            startSection = editing.meeting.startSection
            endSection = editing.meeting.endSection
            startWeek = rule?.startWeek ?? 1
            endWeek = rule?.endWeek ?? 16
            parity = rule?.parity ?? .all
            customStart = editing.meeting.customStartTime ?? ""
            customEnd = editing.meeting.customEndTime ?? ""
        } else {
            startWeek = max(guessWeek, 1)
            endWeek = max(guessWeek, 1)
        }
    }

    /// 节次 → 默认时间预览（跟随当前节次输入实时刷新）
    private var sectionPreview: String {
        let ranges = SectionTimes.ranges
        let start = ranges.indices.contains(startSection - 1) ? String(ranges[startSection - 1].prefix(5)) : "--:--"
        let end = ranges.indices.contains(endSection - 1) ? String(ranges[endSection - 1].suffix(5)) : "--:--"
        return "对应时间：\(start)-\(end)（留空自定义时间则用默认）"
    }

    private func isValidTime(_ value: String) -> Bool {
        let pattern = "^([01]\\d|2[0-3]):[0-5]\\d$"
        return value.range(of: pattern, options: .regularExpression) != nil
    }

    private func save() {
        let name = courseName.trimmingCharacters(in: .whitespaces)
        if name.isEmpty { errorMessage = "课程名不能为空"; return }
        if name.count > 40 { errorMessage = "课程名最多 40 个字符"; return }
        if teacher.count > 40 { errorMessage = "教师名称最多 40 个字符"; return }
        if location.count > 40 { errorMessage = "地点最多 40 个字符"; return }
        guard endSection >= startSection else { errorMessage = "结束节不能小于开始节"; return }
        guard endWeek >= startWeek else { errorMessage = "结束周不能小于开始周"; return }

        // 自定义上课时间：两个都填才生效，校验 HH:mm 且开始早于结束
        var customStartTime: String? = nil
        var customEndTime: String? = nil
        let cs = customStart.trimmingCharacters(in: .whitespaces)
        let ce = customEnd.trimmingCharacters(in: .whitespaces)
        if !cs.isEmpty || !ce.isEmpty {
            guard isValidTime(cs), isValidTime(ce) else { errorMessage = "时间格式应为 HH:mm"; return }
            guard cs < ce else { errorMessage = "结束时间须晚于开始时间"; return }
            customStartTime = cs
            customEndTime = ce
        }

        do {
            if let editing = editing {
                let old = editing.course
                var meetings = old.meetings
                let rule = WeekRule(startWeek: startWeek, endWeek: endWeek, parity: parity)
                let baseMeeting = MeetingTime(
                    weekday: weekday, startSection: startSection, endSection: endSection,
                    weekRules: [rule], location: location.trimmingCharacters(in: .whitespaces),
                    customStartTime: customStartTime, customEndTime: customEndTime)
                if let meetingIndex = old.meetings.firstIndex(where: {
                    $0.weekday == editing.meeting.weekday
                        && $0.startSection == editing.meeting.startSection
                        && $0.endSection == editing.meeting.endSection
                        && $0.location == editing.meeting.location
                }) {
                    meetings[meetingIndex] = baseMeeting
                } else if !meetings.isEmpty {
                    meetings[0] = baseMeeting
                } else {
                    meetings = [baseMeeting]
                }
                let rawWeekText: String
                switch parity {
                case .all: rawWeekText = "\(startWeek)-\(endWeek)周"
                case .odd: rawWeekText = "单周 \(startWeek)-\(endWeek)周"
                case .even: rawWeekText = "双周 \(startWeek)-\(endWeek)周"
                }
                // 课表级备注不受单课编辑影响：保留原课程的 notes
                let updated = CourseSchedule(
                    courseName: name,
                    teacher: teacher.trimmingCharacters(in: .whitespaces),
                    semester: old.semester,
                    credit: old.credit,
                    rawWeekText: rawWeekText,
                    meetings: meetings,
                    notes: old.notes
                )
                // 拆分保存：同名同师课程含多个上课时段且改了名/师时，只改被编辑时段，
                // 其余时段（其他天）保留原名原师，避免跨天绑定连带修改
                let nameChanged = name != old.courseName
                let teacherChanged = teacher.trimmingCharacters(in: .whitespaces) != old.teacher
                if old.meetings.count > 1 && (nameChanged || teacherChanged) {
                    var keptMeetings = old.meetings
                    if let keptIndex = old.meetings.firstIndex(where: {
                        $0.weekday == editing.meeting.weekday
                            && $0.startSection == editing.meeting.startSection
                            && $0.endSection == editing.meeting.endSection
                            && $0.location == editing.meeting.location
                    }) {
                        keptMeetings.remove(at: keptIndex)
                    } else {
                        keptMeetings.removeFirst()
                    }
                    let keptCourse = CourseSchedule(
                        courseName: old.courseName,
                        teacher: old.teacher,
                        semester: old.semester,
                        credit: old.credit,
                        rawWeekText: old.rawWeekText,
                        meetings: keptMeetings,
                        notes: old.notes
                    )
                    let extractedCourse = CourseSchedule(
                        courseName: name,
                        teacher: teacher.trimmingCharacters(in: .whitespaces),
                        semester: old.semester,
                        credit: old.credit,
                        rawWeekText: rawWeekText,
                        meetings: [baseMeeting],
                        notes: old.notes
                    )
                    try ProfileStore.shared.splitUpdateCourse(
                        index: editing.index, keptCourse: keptCourse, extractedCourse: extractedCourse, profileId: editingProfileId)
                } else {
                    try ProfileStore.shared.updateCourse(index: editing.index, newCourse: updated, profileId: editingProfileId)
                }
            } else {
                try ProfileStore.shared.addManualCourse(.init(
                    courseName: name, teacher: teacher.trimmingCharacters(in: .whitespaces),
                    location: location.trimmingCharacters(in: .whitespaces),
                    weekday: weekday, startSection: startSection, endSection: endSection,
                    startWeek: startWeek, endWeek: endWeek, parity: parity))
            }
            StoreChangeNotifier.shared.notifyChanged()
            dismiss()
        } catch {
            errorMessage = error.localizedDescription
        }
    }
}
