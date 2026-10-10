import SwiftUI
import UniformTypeIdentifiers
import Vision
import PDFKit
import UIKit

/// 课表导入：粘贴文本 / 选择 txt·md·csv·tsv 文件 / 选择图片走 Vision 离线识别 / PDF 走文本层。
/// 预览校对后覆盖当前课表或新建课表；解析规则与 Android、鸿蒙、小程序一致。
/// 也可把当前课表导出为 XClass 分享文本（朋友粘贴/打开即可导入）。
struct ImportView: View {
    let palette: ThemePalette
    @Environment(\.dismiss) private var dismiss

    @State private var text = ""
    @State private var parsed: CourseTableImporter.Result?
    @State private var courses: [CourseTableImporter.ParsedCourse] = []
    @State private var message = ""
    @State private var showFileImporter = false
    @State private var shareText = ""
    @State private var showShareSheet = false

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    Text("纯本地解析：粘贴文本、选择 txt/md/csv/tsv，或选择图片/PDF 走离线识别")
                        .font(.system(size: 12))
                        .foregroundColor(palette.textSecondary)

                    Button("导出课表分享文本") { exportShareText() }
                        .frame(maxWidth: .infinity)
                        .padding(10)
                        .background(RoundedRectangle(cornerRadius: 12).fill(palette.panelAlt))
                        .foregroundColor(palette.textPrimary)

                    TextEditor(text: $text)
                        .frame(height: 120)
                        .padding(8)
                        .background(RoundedRectangle(cornerRadius: 12).fill(palette.panelAlt))
                        .font(.system(size: 13))

                    Button("解析粘贴文本") { parse(rawText: text, fileName: "paste.txt") }
                        .frame(maxWidth: .infinity)
                        .padding(10)
                        .background(RoundedRectangle(cornerRadius: 12).fill(palette.buttonBackground))
                        .foregroundColor(palette.buttonText)

                    Button("选择文件（txt/md/csv/tsv/图片/PDF）") { showFileImporter = true }
                        .frame(maxWidth: .infinity)
                        .padding(10)
                        .background(RoundedRectangle(cornerRadius: 12).fill(palette.panelAlt))
                        .foregroundColor(palette.textPrimary)

                    if let parsed {
                        VStack(alignment: .leading, spacing: 4) {
                            Text("识别到 \(courses.count) 门课").font(.system(size: 14, weight: .semibold))
                            if let semester = parsed.semester { Text("学期：\(semester)").font(.system(size: 12)) }
                            if let weeks = parsed.totalWeeks { Text("总周数：\(weeks)").font(.system(size: 12)) }
                            if !parsed.timeTables.isEmpty {
                                Text("节次时间表：\(parsed.timeTables.count) 套（已保存并建立校区映射）")
                                    .font(.system(size: 12))
                            }
                            ForEach(parsed.warnings, id: \.self) { warning in
                                Text("⚠ \(warning)").font(.system(size: 12))
                            }
                        }
                        .padding(10)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .background(RoundedRectangle(cornerRadius: 12).fill(palette.panelAlt))
                        .foregroundColor(palette.textPrimary)
                    }

                    ForEach(Array(courses.enumerated()), id: \.offset) { index, course in
                        VStack(alignment: .leading, spacing: 3) {
                            HStack {
                                Text(course.courseName).font(.system(size: 15, weight: .semibold))
                                Spacer()
                                Button("删除") { courses.remove(at: index) }
                                    .font(.system(size: 12))
                                    .foregroundColor(palette.textSecondary)
                            }
                            Text(meetingText(course)).font(.system(size: 12))
                                .foregroundColor(palette.textSecondary)
                            if !course.teacher.isEmpty {
                                Text(course.teacher).font(.system(size: 12)).foregroundColor(palette.textSecondary)
                            }
                        }
                        .padding(10)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .background(RoundedRectangle(cornerRadius: 12).fill(palette.panelBackground))
                        .foregroundColor(palette.textPrimary)
                    }

                    if !courses.isEmpty {
                        HStack(spacing: 10) {
                            Button("覆盖当前课表") { save(mode: .overwriteActive) }
                                .frame(maxWidth: .infinity).padding(10)
                                .background(RoundedRectangle(cornerRadius: 12).fill(palette.buttonBackground))
                                .foregroundColor(palette.buttonText)
                            Button("新建课表导入") { save(mode: .createNew) }
                                .frame(maxWidth: .infinity).padding(10)
                                .background(RoundedRectangle(cornerRadius: 12).fill(palette.panelAlt))
                                .foregroundColor(palette.textPrimary)
                        }
                    }

                    if !message.isEmpty {
                        Text(message).font(.system(size: 12)).foregroundColor(palette.iconTint)
                    }
                }
                .padding(16)
            }
            .background(palette.pageBackground.ignoresSafeArea())
            .navigationTitle("导入课表")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("关闭") { dismiss() }
                }
            }
            .fileImporter(
                isPresented: $showFileImporter,
                allowedContentTypes: [.plainText, .commaSeparatedText, .pdf, .image, .data],
                allowsMultipleSelection: false
            ) { result in
                handleFileSelection(result)
            }
            .sheet(isPresented: $showShareSheet) {
                ActivityView(items: [shareText])
            }
        }
    }

    /// 导出当前课表为 XClass 分享文本（朋友粘贴/打开即可导入任意端）。
    private func exportShareText() {
        let store = ProfileStore.shared
        let courses = store.loadActiveCourses()
        guard !courses.isEmpty else {
            message = "当前课表还没有课程，无法导出"
            return
        }
        let active = store.listProfiles().first(where: { $0.isActive })
        let campusMap = store.loadCampusMap()
        let idToAlias = Dictionary(uniqueKeysWithValues: campusMap.map { ($0.value, $0.key) })
        let semester = courses.first(where: { !$0.semester.isEmpty })?.semester
        let totalWeeks = courses.flatMap { $0.meetings }.flatMap { $0.weekRules }.map(\.endWeek).max()
        shareText = ShareTextCodec.encode(
            courses: courses,
            profileName: active?.name,
            semester: semester,
            semesterStart: store.getActiveSemesterStartDate(),
            totalWeeks: totalWeeks,
            campusIdToAlias: idToAlias
        )
        showShareSheet = true
    }

    private func meetingText(_ course: CourseTableImporter.ParsedCourse) -> String {
        let labels = ["一", "二", "三", "四", "五", "六", "日"]
        return course.meetings.map { meeting in
            let label = labels[meeting.weekday.rawValue - 1]
            let campus = meeting.campusAlias.map { "（\($0)）" } ?? ""
            return "周\(label) 第\(meeting.startSection)-\(meeting.endSection)节 \(CourseTableImporter.weekRulesText(meeting.weekRules))\(campus)\(meeting.location)"
        }.joined(separator: "；")
    }

    private func parse(rawText: String, fileName: String, rows: [[String]]? = nil) {
        let result = CourseTableImporter.parse(rawText: rawText, rows: rows)
        parsed = result
        courses = result.courses
        if result.courses.isEmpty, let warning = result.warnings.first { message = warning }
    }

    private func handleFileSelection(_ result: Result<[URL], Error>) {
        guard case let .success(urls) = result, let url = urls.first else { return }
        let secured = url.startAccessingSecurityScopedResource()
        defer { if secured { url.stopAccessingSecurityScopedResource() } }
        let name = url.lastPathComponent.lowercased()

        if name.hasSuffix(".pdf") {
            if let content = pdfText(url), !content.isEmpty {
                parse(rawText: content, fileName: name)
            } else {
                message = "PDF 未取到文本层，请改用截图并选择图片识别"
            }
            return
        }
        if name.hasSuffix(".png") || name.hasSuffix(".jpg") || name.hasSuffix(".jpeg") || name.hasSuffix(".webp") {
            recognizeImage(url)
            return
        }
        guard let content = try? String(contentsOf: url, encoding: .utf8) else {
            message = "读取文件失败"
            return
        }
        let rows: [[String]]? = name.hasSuffix(".csv") ? splitDelimited(content, ",")
            : (name.hasSuffix(".tsv") ? splitDelimited(content, "\t") : nil)
        parse(rawText: content, fileName: name, rows: rows)
    }

    private func splitDelimited(_ text: String, _ delimiter: Character) -> [[String]] {
        text.split(separator: "\n").map { line in
            var cells: [String] = []
            var current = ""
            var inQuotes = false
            for character in line {
                if character == "\"" {
                    inQuotes.toggle()
                } else if character == delimiter && !inQuotes {
                    cells.append(current.trimmingCharacters(in: .whitespaces))
                    current = ""
                } else {
                    current.append(character)
                }
            }
            cells.append(current.trimmingCharacters(in: .whitespaces))
            return cells
        }
    }

    /// 图片：Vision 离线中文识别（不联网）。与 Android 相同——用行包围盒重建表格（行/列坐标聚类），
    /// 走「表格解析」而不是逐行找星期词，才能正确识别教务课表图片。
    private func recognizeImage(_ url: URL) {
        guard let image = UIImage(contentsOfFile: url.path)?.cgImage else {
            message = "图片读取失败"
            return
        }
        let size = CGSize(width: image.width, height: image.height)
        let request = VNRecognizeTextRequest { request, _ in
            let observations = (request.results as? [VNRecognizedTextObservation] ?? [])
            let rows = self.visionRows(observations, imageSize: size)
            let content = observations.compactMap { $0.topCandidates(1).first?.string }.joined(separator: "\n")
            DispatchQueue.main.async {
                self.message = "图片 OCR（离线识别，\(rows.count) 行表格）"
                self.parse(rawText: content, fileName: "image.txt", rows: rows)
            }
        }
        request.recognitionLanguages = ["zh-Hans", "en-US"]
        request.recognitionLevel = .accurate
        request.usesLanguageCorrection = true
        DispatchQueue.global(qos: .userInitiated).async {
            let handler = VNImageRequestHandler(cgImage: image, options: [:])
            try? handler.perform([request])
        }
    }

    /// Vision 归一化包围盒（原点左下）→ 像素坐标 → 按 y 聚行、行内按 x 聚列，得到与 CSV 一致的 rows。
    private func visionRows(_ observations: [VNRecognizedTextObservation], imageSize: CGSize) -> [[String]] {
        struct Block {
            let text: String
            let x: CGFloat, y: CGFloat, width: CGFloat, height: CGFloat
            var centerX: CGFloat { x + width / 2 }
            var centerY: CGFloat { y + height / 2 }
        }
        var blocks: [Block] = []
        for observation in observations {
            guard let candidate = observation.topCandidates(1).first else { continue }
            let box = observation.boundingBox
            let x = box.minX * imageSize.width
            let y = (1 - box.maxY) * imageSize.height
            blocks.append(Block(
                text: candidate.string.trimmingCharacters(in: .whitespacesAndNewlines),
                x: x, y: y,
                width: box.width * imageSize.width,
                height: box.height * imageSize.height
            ))
        }
        blocks = blocks.filter { !$0.text.isEmpty }
        guard !blocks.isEmpty else { return [] }

        // 按 y 投影聚类成行（与 Android TableGridBuilder 同容差：行高均值 × 0.6）
        blocks.sort { $0.centerY < $1.centerY }
        let heights = blocks.map(\.height).filter { $0 > 0 }
        let avgHeight = heights.isEmpty ? 20 : heights.reduce(0, +) / CGFloat(heights.count)
        let rowTolerance = avgHeight * 0.6
        var rows: [[Block]] = []
        for block in blocks {
            if let last = rows.last, let anchor = last.first?.centerY, abs(block.centerY - anchor) <= rowTolerance {
                rows[rows.count - 1].append(block)
            } else {
                rows.append([block])
            }
        }

        // 行内按 x 聚类成列（间隙 > 均值宽度 × 0.9 视为新列）
        return rows.map { row in
            let sorted = row.sorted { $0.x < $1.x }
            let widths = sorted.map(\.width).filter { $0 > 0 }
            let avgWidth = widths.isEmpty ? 40 : widths.reduce(0, +) / CGFloat(widths.count)
            let gapTolerance = max(12, avgWidth * 0.9)
            var cells: [String] = []
            var previousEnd: CGFloat = -.greatestFiniteMagnitude
            for block in sorted {
                if cells.isEmpty || block.x - previousEnd > gapTolerance {
                    cells.append(block.text)
                } else {
                    cells[cells.count - 1] += " " + block.text
                }
                previousEnd = max(block.x + block.width, block.x)
            }
            return cells
        }
    }

    private func pdfText(_ url: URL) -> String? {
        guard let document = PDFDocument(url: url) else { return nil }
        var content = ""
        for index in 0..<document.pageCount {
            content += (document.page(at: index)?.string ?? "") + "\n"
        }
        return content
    }

    private func save(mode: ProfileStore.ImportMode) {
        guard !courses.isEmpty else { return }
        let campusMap = ProfileStore.shared.saveParsedTimeTables(
            timeTables: parsed?.timeTables ?? [],
            campusAliases: parsed?.campusAliases ?? []
        )
        let schedules = courses.map { course -> CourseSchedule in
            CourseSchedule(
                courseName: course.courseName,
                teacher: course.teacher,
                semester: parsed?.semester ?? "",
                credit: nil,
                rawWeekText: CourseTableImporter.weekRulesText(course.meetings.first?.weekRules ?? []),
                meetings: course.meetings.map { meeting in
                    MeetingTime(
                        weekday: meeting.weekday,
                        startSection: meeting.startSection,
                        endSection: meeting.endSection,
                        weekRules: meeting.weekRules,
                        location: meeting.location,
                        campusId: meeting.campusAlias.flatMap { campusMap[$0] }
                    )
                }
            )
        }
        let name = ProfileStore.shared.importCourses(
            schedules,
            mode: mode,
            newProfileName: parsed?.semester.map { "\($0) 导入" },
            semesterStartDate: parsed?.semesterStartDate.flatMap { SimpleDate.parse($0) },
            campusMap: campusMap
        )
        StoreChangeNotifier.shared.notifyChanged()
        message = "已导入 \(schedules.count) 门课到「\(name)」"
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.8) { dismiss() }
    }
}

/// 系统分享面板（UIActivityViewController 的 SwiftUI 包装）。
private struct ActivityView: UIViewControllerRepresentable {
    let items: [Any]

    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: items, applicationActivities: nil)
    }

    func updateUIViewController(_ uiViewController: UIActivityViewController, context: Context) {}
}
