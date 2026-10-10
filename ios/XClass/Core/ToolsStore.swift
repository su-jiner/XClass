import Foundation

// MARK: - 工具页数据持久化（UserDefaults JSON，与 Android 存储语义一致）

final class ToolsStore {
    static let shared = ToolsStore()

    private enum Keys {
        static let gpa = "xclass.tools.gpaCourses"
        static let grades = "xclass.tools.grades"
        static let exams = "xclass.tools.exams"
        static let progress = "xclass.tools.progress"
        static let modules = "xclass.tools.progressModules"
    }

    private let defaults: UserDefaults

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    private func load<T: Decodable>(_ key: String, fallback: T) -> T {
        guard let data = defaults.data(forKey: key),
              let value = try? JSONDecoder().decode(T.self, from: data) else { return fallback }
        return value
    }

    private func save<T: Encodable>(_ key: String, _ value: T) {
        if let data = try? JSONEncoder().encode(value) {
            defaults.set(data, forKey: key)
        }
    }

    func loadGpaCourses() -> [GpaCourse] { load(Keys.gpa, fallback: []) }
    func saveGpaCourses(_ list: [GpaCourse]) { save(Keys.gpa, list) }

    func loadGrades() -> [GradeEntry] { load(Keys.grades, fallback: []) }
    func saveGrades(_ list: [GradeEntry]) { save(Keys.grades, list) }

    func loadExams() -> [ExamEntry] { load(Keys.exams, fallback: []) }
    func saveExams(_ list: [ExamEntry]) { save(Keys.exams, list) }

    func loadProgress() -> ProgressInfo { load(Keys.progress, fallback: ProgressInfo(totalRequired: 160, totalEarned: 0)) }
    func saveProgress(_ info: ProgressInfo) { save(Keys.progress, info) }

    func loadProgressModules() -> [ProgressModule] {
        load(Keys.modules, fallback: [
            ProgressModule(name: "学科基础与专业必修", required: 0, earned: 0),
            ProgressModule(name: "专业选修课", required: 0, earned: 0),
            ProgressModule(name: "通识教育选修/核心课", required: 0, earned: 0),
            ProgressModule(name: "实践教学与毕业设计", required: 0, earned: 0),
        ])
    }
    func saveProgressModules(_ list: [ProgressModule]) { save(Keys.modules, list) }
}
