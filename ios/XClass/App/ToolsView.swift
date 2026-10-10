import SwiftUI

// MARK: - 工具页：绩点计算器 / 成绩查询 / 考试安排 / 学业完成（与 Android/小程序/鸿蒙功能对齐）

struct ToolsView: View {
    let palette: ThemePalette
    @State private var tab = 0
    @State private var gpaCourses: [GpaCourse] = []
    @State private var grades: [GradeEntry] = []
    @State private var exams: [ExamEntry] = []
    @State private var progress: ProgressInfo
    @State private var modules: [ProgressModule] = []
    @State private var showAddSheet = false
    @State private var addMode: AddMode = .gpa

    enum AddMode: String {
        case gpa = "gpa"
        case grade = "grade"
        case exam = "exam"
        case progress = "progress"
        case module = "module"
    }

    private let tabs = ["绩点计算器", "成绩查询", "考试安排", "学业完成"]

    init(palette: ThemePalette) {
        self.palette = palette
        _progress = State(initialValue: ToolsStore.shared.loadProgress())
    }

    private var summary: ToolsLogic.Summary { ToolsLogic.summary(of: gpaCourses) }
    private var gradeGroups: [GradeGroup] { ToolsLogic.groupBySemester(grades) }
    private var countdown: String { ToolsLogic.countdownText(for: exams) }

    var body: some View {
        VStack(spacing: 0) {
            Text("工具")
                .font(.title2.bold())
                .foregroundColor(palette.textPrimary)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 16)
                .padding(.top, 4)

            Picker("工具", selection: $tab) {
                ForEach(Array(tabs.enumerated()), id: \.offset) { _, name in
                    Text(name).tag(Array(tabs).firstIndex(of: name) ?? 0)
                }
            }
            .pickerStyle(.segmented)
            .padding(.horizontal, 16)
            .padding(.vertical, 10)

            ScrollView {
                VStack(alignment: .leading, spacing: 10) {
                    switch tab {
                    case 0: gpaSection
                    case 1: gradesSection
                    case 2: examsSection
                    default: progressSection
                    }
                }
                .padding(.horizontal, 16)
                .padding(.bottom, 24)
            }
        }
        .background(palette.pageBackground)
        .onAppear { reload() }
        .sheet(isPresented: $showAddSheet) {
            AddSheet(mode: addMode, placeholder: placeholder(for: addMode)) { text in
                apply(text, mode: addMode)
            }
        }
    }

    // MARK: - 数据

    private func reload() {
        let store = ToolsStore.shared
        gpaCourses = store.loadGpaCourses()
        grades = store.loadGrades()
        exams = store.loadExams()
        progress = store.loadProgress()
        modules = store.loadProgressModules()
    }

    private func apply(_ text: String, mode: AddMode) {
        let store = ToolsStore.shared
        let parts = text.split(separator: "/").map { $0.trimmingCharacters(in: .whitespaces) }
        switch mode {
        case .gpa:
            guard parts.count >= 3 else { return }
            let isLevel = !(parts[1].first?.isNumber ?? false)
            var list = store.loadGpaCourses()
            list.append(GpaCourse(
                courseName: parts[0].isEmpty ? "未命名课程" : parts[0],
                credit: Double(parts[2]) ?? 0,
                gradeType: isLevel ? "level" : "percent",
                score: isLevel ? 0 : (Double(parts[1]) ?? 0),
                level: isLevel ? parts[1] : "",
                included: true
            ))
            store.saveGpaCourses(list)
        case .grade:
            guard parts.count >= 3 else { return }
            var list = store.loadGrades()
            list.append(GradeEntry(
                courseName: parts[0].isEmpty ? "未命名课程" : parts[0],
                credit: Double(parts[1]) ?? 0,
                score: Double(parts[2]) ?? 0,
                semester: parts.count > 3 ? parts[3] : "未标注学期"
            ))
            store.saveGrades(list)
        case .exam:
            guard parts.count >= 2 else { return }
            var list = store.loadExams()
            list.append(ExamEntry(
                courseName: parts[0].isEmpty ? "未命名考试" : parts[0],
                time: parts[1],
                location: parts.count > 2 ? parts[2] : "",
                seat: parts.count > 3 ? parts[3] : ""
            ))
            store.saveExams(list)
        case .progress:
            store.saveProgress(ProgressInfo(
                totalRequired: parts.count > 1 ? (Double(parts[1]) ?? 160) : 160,
                totalEarned: Double(parts[0]) ?? 0
            ))
        case .module:
            var list = store.loadProgressModules()
            guard moduleEditIndex >= 0 && moduleEditIndex < list.count else { return }
            list[moduleEditIndex].earned = Double(parts[0]) ?? 0
            if parts.count > 1 { list[moduleEditIndex].required = Double(parts[1]) ?? 0 }
            store.saveProgressModules(list)
        }
        reload()
    }

    private func placeholder(for mode: AddMode) -> String {
        switch mode {
        case .gpa: return "高等数学/88/4 或 高等数学/优秀/4"
        case .grade: return "高等数学/4/89/2026-2027-1"
        case .exam: return "高等数学/2026-06-20 08:30/逸夫楼101/32号"
        case .progress: return "已修/需修，如 120/160"
        case .module: return "已修/需修，如 30/40"
        }
    }

    // MARK: - 绩点计算器

    private var gpaSection: some View {
        VStack(alignment: .leading, spacing: 10) {
            VStack(spacing: 6) {
                Text("GPA").font(.caption).foregroundColor(palette.textSecondary)
                Text(summary.gpa).font(.system(size: 32, weight: .bold)).foregroundColor(palette.textPrimary)
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 12)
            .background(palette.panelAltBackground, in: RoundedRectangle(cornerRadius: 12))

            HStack(spacing: 8) {
                metric("加权平均", summary.weighted)
                metric("算术平均", summary.arithmetic)
                metric("总学分", summary.credits)
            }

            HStack {
                Text("已录入 \(gpaCourses.count) 门").font(.caption).foregroundColor(palette.textSecondary)
                Spacer()
                Button("从成绩导入") { importGradesToGpa() }
                    .font(.subheadline).foregroundColor(palette.iconTint)
                Button("清空") { ToolsStore.shared.saveGpaCourses([]); reload() }
                    .font(.subheadline).foregroundColor(palette.iconTint)
            }

            ForEach(Array(gpaCourses.enumerated()), id: \.element.id) { index, course in
                HStack {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(course.courseName).font(.subheadline.weight(.medium)).foregroundColor(palette.textPrimary)
                        Text("\(course.credit, specifier: "%.1f") 学分 · \(course.gradeType == "level" ? course.level : String(format: "%.0f", course.score))")
                            .font(.caption).foregroundColor(palette.textSecondary)
                    }
                    Spacer()
                    Button(course.included ? "GPA \(ToolsLogic.gpaOf(score: ToolsLogic.scoreOf(course)), specifier: "%.1f")" : "已排除") {
                        var list = ToolsStore.shared.loadGpaCourses()
                        list[index].included.toggle()
                        ToolsStore.shared.saveGpaCourses(list)
                        reload()
                    }
                    .font(.caption)
                    .foregroundColor(course.included ? palette.iconTint : palette.textSecondary)
                }
                .padding(12)
                .background(palette.panelAltBackground, in: RoundedRectangle(cornerRadius: 12))
            }

            Button("添加绩点课程") {
                addMode = .gpa
                showAddSheet = true
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 10)
            .background(palette.panelAltBackground, in: RoundedRectangle(cornerRadius: 12))
            .foregroundColor(palette.textPrimary)
        }
    }

    private func metric(_ label: String, _ value: String) -> some View {
        VStack(spacing: 2) {
            Text(label).font(.caption2).foregroundColor(palette.textSecondary)
            Text(value).font(.headline).foregroundColor(palette.textPrimary)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 10)
        .background(palette.panelAltBackground, in: RoundedRectangle(cornerRadius: 12))
    }

    private func importGradesToGpa() {
        let converted = grades.map {
            GpaCourse(courseName: $0.courseName, credit: $0.credit, gradeType: "percent", score: $0.score, level: "", included: true)
        }
        guard !converted.isEmpty else { return }
        ToolsStore.shared.saveGpaCourses(converted)
        reload()
    }

    // MARK: - 成绩查询

    private var gradesSection: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Text("共 \(gradeGroups.count) 个学期").font(.caption).foregroundColor(palette.textSecondary)
                Spacer()
                Button("添加成绩") { addMode = .grade; showAddSheet = true }
                    .font(.subheadline).foregroundColor(palette.iconTint)
                Button("转入绩点") { importGradesToGpa() }
                    .font(.subheadline).foregroundColor(palette.iconTint)
            }

            ForEach(gradeGroups, id: \.name) { group in
                VStack(alignment: .leading, spacing: 4) {
                    HStack {
                        Text(group.name).font(.subheadline.bold()).foregroundColor(palette.textPrimary)
                        Spacer()
                        Text("\(group.count) 门 · \(group.credits, specifier: "%.1f") 学分 · 均分 \(group.average, specifier: "%.1f")")
                            .font(.caption).foregroundColor(palette.textSecondary)
                    }
                    ForEach(group.items, id: \.id) { grade in
                        HStack {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(grade.courseName).font(.subheadline).foregroundColor(palette.textPrimary)
                                Text("\(grade.credit, specifier: "%.1f") 学分").font(.caption).foregroundColor(palette.textSecondary)
                            }
                            Spacer()
                            Text(String(format: "%.0f", grade.score))
                                .font(.subheadline.bold()).foregroundColor(palette.iconTint)
                            Button("删除") {
                                ToolsStore.shared.saveGrades(
                                    ToolsStore.shared.loadGrades().filter { $0.id != grade.id }
                                )
                                reload()
                            }
                            .font(.caption).foregroundColor(palette.textSecondary)
                        }
                    }
                }
                .padding(12)
                .background(palette.panelAltBackground, in: RoundedRectangle(cornerRadius: 12))
            }
        }
    }

    // MARK: - 考试安排

    private var examsSection: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(countdown)
                .font(.subheadline.weight(.medium))
                .foregroundColor(palette.textPrimary)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(14)
                .background(palette.panelAltBackground, in: RoundedRectangle(cornerRadius: 12))

            HStack {
                Spacer()
                Button("添加考试") { addMode = .exam; showAddSheet = true }
                    .font(.subheadline).foregroundColor(palette.iconTint)
            }

            ForEach(Array(exams.enumerated()), id: \.element.id) { index, exam in
                HStack {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(exam.courseName).font(.subheadline.weight(.medium)).foregroundColor(palette.textPrimary)
                        Text([exam.time, exam.location, exam.seat].filter { !$0.isEmpty }.joined(separator: " · "))
                            .font(.caption).foregroundColor(palette.textSecondary)
                    }
                    Spacer()
                    Button("删除") {
                        ToolsStore.shared.saveExams(
                            ToolsStore.shared.loadExams().enumerated().filter { $0.offset != index }.map { $0.element }
                        )
                        reload()
                    }
                    .font(.caption).foregroundColor(palette.textSecondary)
                }
                .padding(12)
                .background(palette.panelAltBackground, in: RoundedRectangle(cornerRadius: 12))
            }
        }
    }

    // MARK: - 学业完成

    private var progressSection: some View {
        VStack(alignment: .leading, spacing: 10) {
            VStack(spacing: 4) {
                Text("毕业学分").font(.caption).foregroundColor(palette.textSecondary)
                Text("\(progress.totalEarned, specifier: "%.0f") / \(progress.totalRequired, specifier: "%.0f")")
                    .font(.system(size: 28, weight: .bold)).foregroundColor(palette.textPrimary)
                Text("完成度 \(progress.totalRequired > 0 ? String(format: "%.0f", progress.totalEarned / progress.totalRequired * 100) : "0")%")
                    .font(.caption).foregroundColor(palette.textSecondary)
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 14)
            .background(palette.panelAltBackground, in: RoundedRectangle(cornerRadius: 12))

            HStack {
                Button("编辑") { addMode = .progress; showAddSheet = true }
                    .font(.subheadline).foregroundColor(palette.iconTint)
                Button("按成绩累计") {
                    let earned = ToolsLogic.earnedCredits(from: grades)
                    ToolsStore.shared.saveProgress(ProgressInfo(totalRequired: progress.totalRequired, totalEarned: earned))
                    reload()
                }
                .font(.subheadline).foregroundColor(palette.iconTint)
            }

            ForEach(Array(modules.enumerated()), id: \.element.id) { index, module in
                HStack {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(module.name).font(.subheadline).foregroundColor(palette.textPrimary)
                        Text("已修 \(module.earned, specifier: "%.0f") / 需修 \(module.required, specifier: "%.0f")")
                            .font(.caption).foregroundColor(palette.textSecondary)
                    }
                    Spacer()
                    Button("编辑") {
                        addMode = .module
                        showAddSheet = true
                        moduleEditIndex = index
                    }
                    .font(.caption).foregroundColor(palette.iconTint)
                }
                .padding(12)
                .background(palette.panelAltBackground, in: RoundedRectangle(cornerRadius: 12))
            }
        }
    }

    @State private var moduleEditIndex: Int = -1
}

// MARK: - 通用添加表单（多项以 / 分隔）

private struct AddSheet: View {
    let mode: ToolsView.AddMode
    let placeholder: String
    let onSubmit: (String) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var text = ""

    var body: some View {
        NavigationStack {
            Form {
                TextField("多项用 / 分隔", text: $text)
                    .textInputAutocapitalization(.never)
                Text("示例：\(placeholder)")
                    .font(.caption)
                    .foregroundColor(.secondary)
            }
            .navigationTitle(modeTitle)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("确定") {
                        onSubmit(text)
                        dismiss()
                    }
                }
            }
        }
    }

    private var modeTitle: String {
        switch mode {
        case .gpa: return "添加绩点课程"
        case .grade: return "添加成绩"
        case .exam: return "添加考试"
        case .progress: return "毕业学分"
        case .module: return "编辑模块"
        }
    }
}
