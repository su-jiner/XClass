import SwiftUI
import UniformTypeIdentifiers
import Vision
import PDFKit

/// 课表导入：粘贴文本 / 选择 txt·md·csv·tsv 文件 / 选择图片走 Vision 离线识别 / PDF 走文本层。
/// 预览校对后覆盖当前课表或新建课表；解析规则与 Android、鸿蒙、小程序一致。
struct ImportView: View {
    let palette: ThemePalette
    @Environment(\.dismiss) private var dismiss

    @State private var text = ""
    @State private var parsed: CourseTableImporter.Result?
    @State private var courses: [CourseTableImporter.ParsedCourse] = []
    @State private var message = ""
    @State private var showFileImporter = false

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    Text("纯本地解析：粘贴文本、选择 txt/md/csv/tsv，或选择图片/PDF 走离线识别")
                        .font(.system(size: 12))
                        .foregroundColor(palette.textSecondary)

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
        }
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

    /// 图片：Vision 离线中文识别（不联网），行级包围盒 → 文本块 → 同一套解析
    private func recognizeImage(_ url: URL) {
        guard let image = UIImage(contentsOfFile: url.path)?.cgImage else {
            message = "图片读取失败"
            return
        }
        let request = VNRecognizeTextRequest { request, _ in
            let lines = (request.results as? [VNRecognizedTextObservation] ?? []).compactMap { observation in
                observation.topCandidates(1).first?.string
            }
            DispatchQueue.main.async {
                let content = lines.joined(separator: "\n")
                self.message = "图片 OCR（离线识别，\(lines.count) 行）"
                self.parse(rawText: content, fileName: "image.txt")
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
