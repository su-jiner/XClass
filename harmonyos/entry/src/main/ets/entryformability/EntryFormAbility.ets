import { FormExtensionAbility, formBindingData, formProvider } from '@kit.FormKit';
import { Want } from '@kit.AbilityKit';
import { CourseSchedule, SimpleDate, guessCurrentWeek, totalWeeks, sectionTimeText, resolveProfile } from '../core/Core.ets';
import { ProfileStore } from '../core/ProfileStore.ets';

const WEEKDAY_LABELS = ['一', '二', '三', '四', '五', '六', '日'];

/**
 * XClass 服务卡片（桌面卡片 / 锁屏卡片）：
 * 今日课程、下一节课、今日时间轴、本周微缩四张卡片共用本 Ability 推送数据；
 * 数据全部来自本地课表文件，不联网。
 */
export default class EntryFormAbility extends FormExtensionAbility {
  onAddForm(want: Want): formBindingData.FormBindingData {
    return this.buildBindingData(want);
  }

  onUpdateForm(formId: string): void {
    const data = this.buildBindingData(null);
    formProvider.updateForm(formId, data).catch(() => {});
  }

  onRemoveForm(formId: string): void {
  }

  onFormEvent(formId: string, message: string): void {
  }

  private buildBindingData(want: Want | null): formBindingData.FormBindingData {
    const store = ProfileStore.initWithFilesDir((this.context as object)['filesDir'] ?? '');
    const courses = store.loadActiveCourses();
    const semesterStart = store.getActiveSemesterStartDate();
    const profiles = store.loadTimeProfiles();
    const weeks = totalWeeks(courses);
    const today = SimpleDate.today();
    const week = guessCurrentWeek(semesterStart, today, weeks);
    const weekday = today.weekdayMonBased;

    const todayMeetings: Array<{ name: string; detail: string; startSection: number; startTime: string }> = [];
    courses.forEach((course: CourseSchedule) => {
      course.meetings.forEach((meeting) => {
        if (meeting.weekday !== weekday) return;
        let active = false;
        if (meeting.weekRules.length === 0) {
          active = true;
        } else {
          meeting.weekRules.forEach((rule) => {
            if (week >= rule.startWeek && week <= rule.endWeek) {
              if (rule.parity === 'ODD' && week % 2 === 0) return;
              if (rule.parity === 'EVEN' && week % 2 === 1) return;
              active = true;
            }
          });
        }
        if (!active) return;
        const timeText = sectionTimeText(profiles, meeting);
        todayMeetings.push({
          name: course.courseName,
          detail: `第${meeting.startSection}-${meeting.endSection}节 ${timeText}` +
            (meeting.location.length > 0 ? ` · ${meeting.location}` : ''),
          startSection: meeting.startSection,
          startTime: timeText.split('-')[0],
        });
      });
    });
    todayMeetings.sort((a, b) => a.startSection - b.startSection);

    const next = todayMeetings.length > 0 ? todayMeetings[0] : null;
    const displayProfile = resolveProfile(profiles, null);
    const payload: Record<string, string> = {
      title: 'XClass',
      subtitle: `第${week}周 · 周${WEEKDAY_LABELS[weekday - 1]} · ${today.isoString()}`,
      empty: todayMeetings.length === 0 ? '今天没有课程安排' : '',
      nextName: next ? next.name : '今天没有课程安排',
      nextDetail: next ? next.detail : '',
      nextStart: next ? next.startTime : '--:--',
      line1: todayMeetings.length > 0 ? `${todayMeetings[0].name} ${todayMeetings[0].detail}` : '',
      line2: todayMeetings.length > 1 ? `${todayMeetings[1].name} ${todayMeetings[1].detail}` : '',
      line3: todayMeetings.length > 2 ? `${todayMeetings[2].name} ${todayMeetings[2].detail}` : '',
      line4: todayMeetings.length > 3 ? `${todayMeetings[3].name} ${todayMeetings[3].detail}` : '',
      sectionCount: String(displayProfile.blocks.length),
    };
    return formBindingData.createFormBindingData(payload);
  }
}
