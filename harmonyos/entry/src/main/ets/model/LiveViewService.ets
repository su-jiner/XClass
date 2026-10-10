/**
 * 鸿蒙实况窗（LiveViewKit）：课前 15 分钟开始、下课后结束，展示课程名与节次时间。
 *
 * 说明：
 * - 实况窗需要在支持实况窗的设备与已开通该能力的签名证书上生效，本工程用 try/catch 包裹，
 *   不支持时静默降级（不影响使用），桌面卡片仍可正常展示今日课程。
 * - 与 iOS 一致：后台不保证准点，App 进入前台时刷新。
 */
import { liveViewManager } from '@kit.LiveViewKit';
import { CourseSchedule, SimpleDate, guessCurrentWeek, totalWeeks, sectionTimeText } from '../core/Core.ets';
import { ProfileStore } from '../core/ProfileStore.ets';

export class LiveViewService {
  private static readonly LEAD_MINUTES: number = 15;
  private static liveViewId: string = '';

  /** App 进入前台/数据变化时调用 */
  static refreshIfEnabled(): void {
    try {
      const store = ProfileStore.instance;
      if (store === null) return;
      const courses = store.loadActiveCourses();
      const semesterStart = store.getActiveSemesterStartDate();
      const profiles = store.loadTimeProfiles();
      const weeks = totalWeeks(courses);
      const today = SimpleDate.today();
      const week = guessCurrentWeek(semesterStart, today, weeks);
      const weekday = today.weekdayMonBased;
      const now = new Date();
      const nowMinutes = now.getHours() * 60 + now.getMinutes();

      let target: { name: string; start: number; end: number; detail: string } | null = null;
      courses.forEach((course: CourseSchedule) => {
        course.meetings.forEach((meeting) => {
          if (meeting.weekday !== weekday) return;
          let active = meeting.weekRules.length === 0;
          meeting.weekRules.forEach((rule) => {
            if (week >= rule.startWeek && week <= rule.endWeek) active = true;
          });
          if (!active) return;
          const timeText = sectionTimeText(profiles, meeting);
          const parts = timeText.split('-');
          const start = LiveViewService.toMinutes(parts[0]);
          const end = LiveViewService.toMinutes(parts.length > 1 ? parts[1] : '');
          if (start === null || end === null) return;
          if (nowMinutes >= start - LiveViewService.LEAD_MINUTES && nowMinutes <= end) {
            if (target === null || start < target.start) {
              target = { name: course.courseName, start, end, detail: `第${meeting.startSection}-${meeting.endSection}节 ${timeText}` };
            }
          }
        });
      });

      if (target === null) {
        LiveViewService.stop();
        return;
      }
      const payload: Record<string, Object> = {
        liveViewData: {
          title: target.name,
          content: target.detail,
        } as Record<string, string>,
      };
      if (LiveViewService.liveViewId.length === 0) {
        LiveViewService.liveViewId = `xclass-${Date.now()}`;
        liveViewManager.startLiveView(LiveViewService.liveViewId, payload);
      } else {
        liveViewManager.updateLiveView(LiveViewService.liveViewId, payload);
      }
    } catch (error) {
      // 设备/证书不支持实况窗时静默降级
    }
  }

  private static stop(): void {
    if (LiveViewService.liveViewId.length === 0) return;
    try {
      liveViewManager.stopLiveView(LiveViewService.liveViewId);
    } catch (error) {
      // 忽略
    }
    LiveViewService.liveViewId = '';
  }

  private static toMinutes(text: string): number | null {
    const parts = text.split(':');
    if (parts.length !== 2) return null;
    const hour = Number(parts[0]);
    const minute = Number(parts[1]);
    if (isNaN(hour) || isNaN(minute)) return null;
    return hour * 60 + minute;
  }
}
