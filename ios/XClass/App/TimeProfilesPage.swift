import SwiftUI

/// 节次时间表管理：多校区/楼栋各一套时间表，支持粘贴「XX校区上课时间」文本识别。
/// 与 Android TimeProfileActivity、鸿蒙 TimeProfilesPage、小程序设置页同构。
struct TimeProfilesPage: View {
    let palette: ThemePalette

    @State private var profiles: [SectionTimeProfile] = []
    @State private var defaultId: String = TimeProfileResolver.defaultProfileId
    @State private var input = ""
    @State private var message = ""

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                Text("多校区/楼栋可各配一套节次时间；今日页、提醒与课表时间列按课程所属校区取时间。")
                    .font(.system(size: 12))
                    .foregroundColor(palette.textSecondary)

                ForEach(profiles, id: \.id) { profile in
                    VStack(alignment: .leading, spacing: 6) {
                        Text(profile.name + (profile.id == defaultId ? "  ·  当前显示" : ""))
                            .font(.system(size: 15, weight: .semibold))
                        Text(profile.blocks.map { block in
                            let label = block.startSection == block.endSection
                                ? "\(block.startSection)节" : "\(block.startSection)-\(block.endSection)节"
                            return "\(label) \(block.startTime)-\(block.endTime)"
                        }.joined(separator: "  "))
                            .font(.system(size: 12))
                            .foregroundColor(palette.textSecondary)
                        HStack(spacing: 12) {
                            Button("设为当前显示") { setDefault(profile) }
                                .font(.system(size: 12))
                                .foregroundColor(palette.iconTint)
                            if profile.id != TimeProfileResolver.defaultProfileId {
                                Button("删除") { remove(profile) }
                                    .font(.system(size: 12))
                                    .foregroundColor(palette.textSecondary)
                            }
                        }
                    }
                    .padding(12)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(RoundedRectangle(cornerRadius: 12).fill(palette.panelAlt))
                    .foregroundColor(palette.textPrimary)
                }

                Text("从文本识别时间表").font(.system(size: 14, weight: .semibold))
                TextEditor(text: $input)
                    .frame(height: 110)
                    .padding(8)
                    .background(RoundedRectangle(cornerRadius: 12).fill(palette.panelAlt))
                    .font(.system(size: 13))
                Button("识别并保存") { parseFromText() }
                    .frame(maxWidth: .infinity)
                    .padding(10)
                    .background(RoundedRectangle(cornerRadius: 12).fill(palette.buttonBackground))
                    .foregroundColor(palette.buttonText)
                if !message.isEmpty {
                    Text(message).font(.system(size: 12)).foregroundColor(palette.iconTint)
                }
            }
            .padding(16)
        }
        .background(palette.pageBackground.ignoresSafeArea())
        .navigationTitle("节次时间表")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear(perform: reload)
    }

    private func reload() {
        profiles = ProfileStore.shared.loadTimeProfiles()
        defaultId = ProfileStore.shared.loadDefaultProfileId() ?? TimeProfileResolver.defaultProfileId
    }

    private func parseFromText() {
        let parsed = CourseTableImporter.parseTimeTables(input)
        guard !parsed.isEmpty else {
            message = "未识别到时间表，请检查文本内容"
            return
        }
        var existing = ProfileStore.shared.loadTimeProfiles()
            .filter { $0.id != TimeProfileResolver.defaultProfileId }
        for profile in parsed {
            if let index = existing.firstIndex(where: { $0.id == profile.id || $0.name == profile.name }) {
                existing[index] = profile
            } else {
                existing.append(profile)
            }
        }
        ProfileStore.shared.saveTimeProfiles(
            existing,
            campusMap: ProfileStore.shared.loadCampusMap(),
            defaultProfileId: ProfileStore.shared.loadDefaultProfileId()
        )
        message = "已识别并保存 \(parsed.count) 套时间表"
        input = ""
        reload()
    }

    private func setDefault(_ profile: SectionTimeProfile) {
        ProfileStore.shared.saveTimeProfiles(
            ProfileStore.shared.loadTimeProfiles().filter { $0.id != TimeProfileResolver.defaultProfileId },
            campusMap: ProfileStore.shared.loadCampusMap(),
            defaultProfileId: profile.id
        )
        message = "已把「\(profile.name)」设为课表时间列显示的时间表"
        reload()
        StoreChangeNotifier.shared.notifyChanged()
    }

    private func remove(_ profile: SectionTimeProfile) {
        let remaining = ProfileStore.shared.loadTimeProfiles()
            .filter { $0.id != profile.id && $0.id != TimeProfileResolver.defaultProfileId }
        var campusMap = ProfileStore.shared.loadCampusMap()
        for (key, value) in campusMap where value == profile.id {
            campusMap.removeValue(forKey: key)
        }
        ProfileStore.shared.saveTimeProfiles(
            remaining,
            campusMap: campusMap,
            defaultProfileId: TimeProfileResolver.defaultProfileId
        )
        reload()
    }
}
