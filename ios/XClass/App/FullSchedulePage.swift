import SwiftUI

/// 课表管理 → 查看完整课表：所有周的课程在同一页面展示（与课表页一致的网格样式），
/// 点击课程可编辑，修改即同步所有周。
struct FullSchedulePage: View {
    let palette: ThemePalette
    let profileId: String
    let profileName: String
    @EnvironmentObject var notifier: StoreChangeNotifier

    @State private var courses: [CourseSchedule] = []
    @State private var showEditSheet = false
    @State private var editingItem: (index: Int, course: CourseSchedule, meeting: MeetingTime)?
    @State private var sectionRanges: [String] = SectionTimes.ranges
    @State private var notes: [String] = []

    var body: some View {
        let items = WeekScheduleCalculator.allMeetings(of: courses)
        ScrollView {
            if courses.isEmpty {
                Text("这个课表还没有课程")
                    .font(.system(size: 14))
                    .foregroundColor(palette.textSecondary)
                    .padding(.top, 60)
            } else {
                WeekGridView(
                    palette: palette,
                    week: 1,
                    today: SimpleDate.today(),
                    weekStart: SimpleDate.today(),
                    items: items,
                    fontScale: AppPreferences.fontScale,
                    currentSection: nil,
                    showDates: false,
                    sectionRanges: sectionRanges,
                    onTap: { courseIndex, course, meeting in
                        editingItem = (courseIndex, course, meeting)
                        showEditSheet = true
                    }
                )
                .frame(height: 34 + 46 * CGFloat(max(sectionRanges.count, 1)))
                .padding(.horizontal, 8)

                if !notes.isEmpty {
                    VStack(alignment: .leading, spacing: 3) {
                        ForEach(notes, id: \.self) { note in
                            Text("• \(note)")
                                .font(.system(size: 10))
                                .foregroundColor(palette.textSecondary)
                        }
                    }
                    .padding(10)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(RoundedRectangle(cornerRadius: 10).fill(palette.panelAlt))
                    .padding(.horizontal, 8)
                    .padding(.bottom, 12)
                }
            }
        }
        .background(palette.pageBackground)
        .navigationTitle(profileName.isEmpty ? "完整课表" : "完整课表 · \(profileName)")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear { reload() }
        .onChange(of: notifier.version) { _ in reload() }
        .sheet(isPresented: $showEditSheet, onDismiss: { reload() }) {
            if let editing = editingItem {
                AddCourseSheet(palette: palette, guessWeek: 1,
                               editing: editing, editingProfileId: profileId)
            }
        }
    }

    private func reload() {
        courses = ProfileStore.shared.loadCourses(profileId: profileId)
        let profile = ProfileStore.shared.currentDisplayTimeProfile()
        let maxSection = max(profile.blocks.map(\.endSection).max() ?? 12, 12)
        sectionRanges = (1...maxSection).map { profile.sectionRange($0) ?? "--:--" }
        notes = ImportView.displayNotes(of: courses)
    }
}
