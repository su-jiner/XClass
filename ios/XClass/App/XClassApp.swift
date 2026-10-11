import SwiftUI
import UserNotifications

@main
struct XClassApp: App {
    @StateObject private var notifier = StoreChangeNotifier.shared
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(notifier)
                .preferredColorScheme(colorScheme)
                .task {
                    await ReminderScheduler.refreshIfEnabled()
                    LiveActivityManager.refresh()
                }
        }
        .onChange(of: scenePhase) { phase in
            if phase == .active {
                ReminderScheduler.refreshIfEnabled()
                LiveActivityManager.refresh()
            }
        }
    }

    private var colorScheme: ColorScheme? {
        switch AppPreferences.darkMode {
        case "light": return .light
        case "dark": return .dark
        default: return nil
        }
    }
}

struct RootView: View {
    @EnvironmentObject var notifier: StoreChangeNotifier
    @Environment(\.colorScheme) private var systemScheme
    @State private var tab = AppPreferences.defaultPage == "today" ? 1 : 0

    var body: some View {
        ZStack(alignment: .bottom) {
            TabView(selection: $tab) {
                TimetablePage(palette: palette)
                    .tag(0)
                TodayPage(palette: palette)
                    .tag(1)
                ToolsView(palette: palette)
                    .tag(2)
                SettingsPage(palette: palette)
                    .tag(3)
            }
            .tint(palette.iconTint)

            CapsuleTabBar(
                selected: tab,
                palette: palette,
                onSelect: { tab = $0 }
            )
        }
    }

    private var palette: ThemePalette {
        let dark: Bool
        switch AppPreferences.darkMode {
        case "light": dark = false
        case "dark": dark = true
        default: dark = systemScheme == .dark
        }
        return ThemePalette.palette(theme: AppPreferences.theme, dark: dark)
    }
}

/// 液态胶囊底栏（等效 SleepDown LiquidBottomTabs 视觉）：浮动玻璃胶囊容器 + 圆角胶囊 Tab
struct CapsuleTabBar: View {
    let selected: Int
    let palette: ThemePalette
    let onSelect: (Int) -> Void
    @Environment(\.colorScheme) private var systemScheme

    private let items: [(Int, String, String)] = [
        (0, "课表", "calendar"),
        (1, "日程", "clock"),
        (2, "工具", "chart.bar.xaxis"),
        (3, "设置", "gearshape"),
    ]

    var body: some View {
        let dark = systemScheme == .dark
        HStack(spacing: 6) {
            ForEach(items, id: \.0) { item in
                let isSelected = item.0 == selected
                Button {
                    onSelect(item.0)
                } label: {
                    VStack(spacing: 2) {
                        Image(systemName: item.2)
                            .font(.system(size: 16))
                        Text(item.1)
                            .font(.system(size: 10))
                    }
                    .foregroundColor(isSelected ? palette.buttonText : palette.textSecondary)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 8)
                    .background(
                        Capsule().fill(isSelected ? palette.buttonBackground : Color.clear)
                    )
                }
                .buttonStyle(.plain)
            }
        }
        .padding(6)
        .background(
            Capsule().fill(
                dark
                    ? Color(white: 0.12).opacity(0.82)
                    : Color.white.opacity(0.78)
            )
        )
        .overlay(
            Capsule().stroke(palette.textSecondary.opacity(0.14), lineWidth: 1)
        )
        .shadow(color: Color.black.opacity(dark ? 0.35 : 0.10), radius: 14, y: 4)
        .padding(.horizontal, 14)
        .padding(.bottom, 8)
    }
}

// MARK: - 每日提醒（滚动 7 天窗口，每次启动/数据变化时重排）

enum ReminderScheduler {
    static let identifierPrefix = "daily_reminder_"

    static func refreshIfEnabled() async {
        guard AppPreferences.reminderEnabled else {
            clearPending()
            return
        }
        let center = UNUserNotificationCenter.current()
        let settings = await center.notificationSettings()
        guard settings.authorizationStatus == .authorized || settings.authorizationStatus == .provisional else {
            return
        }
        scheduleWindow()
    }

    static func requestAuthorizationAndSchedule() async -> Bool {
        let center = UNUserNotificationCenter.current()
        let granted = try? await center.requestAuthorization(options: [.alert, .sound, .badge])
        guard granted == true else { return false }
        scheduleWindow()
        return true
    }

    static func clearPending() {
        let center = UNUserNotificationCenter.current()
        center.getPendingNotificationRequests { requests in
            let ids = requests.map { $0.identifier }.filter { $0.hasPrefix(identifierPrefix) }
            center.removePendingNotificationRequests(withIdentifiers: ids)
        }
    }

    /// 未来 7 天每天一条当日课程概览，内容在排程时按当天课表生成
    static func scheduleWindow() {
        clearPending()
        let center = UNUserNotificationCenter.current()
        let minute = AppPreferences.reminderMinute
        let courses = ProfileStore.shared.loadActiveCourses()
        let semesterStart = ProfileStore.shared.getActiveSemesterStartDate()
        let totalWeeks = WeekScheduleCalculator.totalWeeks(of: courses)
        let today = SimpleDate.today()

        for offset in 0..<7 {
            let date = today.adding(days: offset + 1)
            var fire = DateComponents()
            fire.year = date.year; fire.month = date.month; fire.day = date.day
            fire.hour = minute / 60; fire.minute = minute % 60

            let week = WeekScheduleCalculator.guessCurrentWeek(semesterStart: semesterStart, today: date, totalWeeks: totalWeeks)
            let meetings = WeekScheduleCalculator.meetings(inWeek: week, of: courses)
                .filter { $0.meeting.weekday == date.weekday }
            let title = "今日课程"
            let body: String
            if meetings.isEmpty {
                body = "今天没有课程安排"
            } else {
                let first = meetings.min { $0.meeting.startSection < $1.meeting.startSection }!
                let time = TimeProfileResolver.timeRange(ProfileStore.shared.loadTimeProfiles(), meeting: first.meeting)?.start ?? "--:--"
                body = "\(meetings.count) 门课，最早 \(time) \(first.course.courseName)"
            }

            let content = UNMutableNotificationContent()
            content.title = title
            content.body = body
            content.sound = .default
            let trigger = UNCalendarNotificationTrigger(dateMatching: fire, repeats: false)
            let request = UNNotificationRequest(identifier: identifierPrefix + date.isoString,
                                                content: content, trigger: trigger)
            center.add(request)
        }
    }
}
