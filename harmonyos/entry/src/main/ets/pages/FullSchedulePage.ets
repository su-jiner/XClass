import {
  CourseSchedule, SimpleDate, CourseMeetingRef, ImportedCourse, ImportedMeeting,
  allMeetings, resolveOverlaps, SECTION_RANGES,
  SectionTimeProfile, resolveProfile, defaultTimeProfile,
} from '../core/Core.ets';
import { ProfileStore } from '../core/ProfileStore.ets';
import { currentPalette, CARD_COLORS, ThemePalette } from '../model/Theme.ets';
import { Prefs } from '../model/Prefs.ets';
import { EditCourseDialog } from './EditCourseDialog.ets';
import { router } from '@kit.ArkUI';

const ROW_HEIGHT = 46;
const HEADER_HEIGHT = 34;
const LEFT_COLUMN_WIDTH = 44;
const WEEKDAY_LABELS = ['一', '二', '三', '四', '五', '六', '日'];

/**
 * 课表管理 → 查看完整课表：所有周的课程在同一页面展示（与课表页一致的网格样式），
 * 点击课程可编辑，修改即同步所有周。
 */
@Component
export struct FullSchedulePage {
  @StorageProp('dataVersion') @Watch('reload') dataVersion: number = 0;
  @StorageProp('themeVersion') @Watch('reload') themeVersion: number = 0;
  @StorageProp('systemDark') systemDark: boolean = false;
  @State courses: CourseSchedule[] = [];
  @State displayProfile: SectionTimeProfile = defaultTimeProfile();
  @State sectionRanges: string[] = SECTION_RANGES.slice();
  @State sectionCount: number = 12;
  @State notes: string[] = [];
  @State editingCourseIndex: number = -1;
  @State editingMeetingIndex: number = 0;
  @State editingCourse: ImportedCourse = {
    courseName: '', teacher: '', semester: '', credit: null, rawWeekText: '', meetings: [],
  };
  profileId: string = '';
  profileName: string = '';
  editDialogController: CustomDialogController = new CustomDialogController({
    builder: EditCourseDialog({
      course: this.editingCourse,
      meetingIndex: this.editingMeetingIndex,
      onSaved: (updated: ImportedCourse): void => {
        this.applyEdit(updated);
      },
    }),
    autoCancel: true,
    customStyle: false,
  });

  aboutToAppear(): void {
    const params = router.getParams() as Record<string, string> | undefined;
    this.profileId = params?.profileId ?? '';
    this.profileName = params?.profileName ?? '';
    this.reload();
  }

  reload(): void {
    this.courses = ProfileStore.instance?.loadCourses(this.profileId) ?? [];
    // 左侧时间列动态跟随「当前显示时间表」
    const profiles = ProfileStore.instance?.loadTimeProfiles() ?? [];
    this.displayProfile = resolveProfile(profiles, ProfileStore.instance?.loadDefaultProfileId() ?? null);
    let maxSection = 0;
    this.displayProfile.blocks.forEach((block) => {
      if (block.endSection > maxSection) maxSection = block.endSection;
    });
    this.sectionCount = Math.max(maxSection, 12);
    this.sectionRanges = [];
    for (let i = 1; i <= this.sectionCount; i += 1) {
      const start = this.sectionRangeText(i, true);
      const end = this.sectionRangeText(i, false);
      this.sectionRanges.push(`${start}-${end}`);
    }
    this.notes = ProfileStore.instance?.listProfiles()
      .find((p) => p.id === this.profileId || (this.profileId.length === 0 && p.isActive))?.notes ?? [];
  }

  /** 当前时间表下第 section 节的开始/结束时刻 */
  sectionRangeText(section: number, isStart: boolean): string {
    for (let i = 0; i < this.displayProfile.blocks.length; i += 1) {
      const block = this.displayProfile.blocks[i];
      if (section >= block.startSection && section <= block.endSection) {
        return isStart ? block.startTime : block.endTime;
      }
    }
    return '--:--';
  }

  palette(): ThemePalette {
    return currentPalette(Prefs.theme(), Prefs.darkMode(), this.systemDark);
  }

  build() {
    Column() {
      Row() {
        Text(this.profileName.length > 0 ? `完整课表 · ${this.profileName}` : '完整课表')
          .fontSize(18).fontWeight(FontWeight.Bold)
          .fontColor(this.palette().textPrimary)
          .layoutWeight(1)
        Button('返回')
          .backgroundColor(this.palette().panelBackground)
          .fontColor(this.palette().textPrimary)
          .fontSize(14)
          .onClick(() => {
            router.back();
          })
      }
      .width('100%')
      .padding({ left: 16, right: 12, top: 10, bottom: 6 })

      Text('全部周的课程都在这一页，修改课程将同步所有周')
        .fontSize(12)
        .fontColor(this.palette().textSecondary)
        .width('100%')
        .textAlign(TextAlign.Start)
        .padding({ left: 16, bottom: 8 })

      if (this.courses.length === 0) {
        Text('这个课表还没有课程')
          .fontSize(14)
          .fontColor(this.palette().textSecondary)
          .margin({ top: 80 })
      } else {
        Scroll() {
          Row() {
            Column() {
              Text('周')
                .fontSize(10).fontWeight(FontWeight.Bold)
                .fontColor(this.palette().textPrimary)
                .height(HEADER_HEIGHT)
                .width('100%')
                .textAlign(TextAlign.Center)
              ForEach(this.sectionRanges, (range: string, index: number) => {
                Column() {
                  Text(`${index + 1}`).fontSize(11).fontWeight(FontWeight.Bold)
                  Text(range.split('-')[0]).fontSize(8)
                  Text(range.split('-')[1]).fontSize(8)
                }
                .fontColor(this.palette().textSecondary)
                .width(LEFT_COLUMN_WIDTH - 6)
                .height(ROW_HEIGHT)
                .textAlign(TextAlign.Center)
              }, (range: string) => range)
            }
            .backgroundColor(this.palette().gridLeft)

            Row({ space: 2 }) {
              ForEach([0, 1, 2, 3, 4, 5, 6], (dayIndex: number) => {
                this.DayColumn(dayIndex)
              }, (dayIndex: number) => dayIndex.toString())
            }
            .padding({ left: 4, right: 4 })
          }
          .alignItems(VerticalAlign.Top)
        }
        .layoutWeight(1)
        .align(Alignment.Top)
        .scrollable(ScrollDirection.Vertical)
      }

      // 底部课表级备注栏（非空才显示：小字、次级色、圆角卡片）
      if (this.notes.length > 0) {
        Column() {
          ForEach(this.notes, (note: string) => {
            Text(note)
              .fontSize(11)
              .fontColor(this.palette().textSecondary)
              .maxLines(2)
              .textAlign(TextAlign.Start)
              .width('100%')
              .margin({ bottom: 2 })
          }, (note: string) => note)
        }
        .alignItems(HorizontalAlign.Start)
        .width('100%')
        .padding({ left: 12, right: 12, top: 8, bottom: 8 })
        .margin({ left: 12, right: 12, bottom: 8 })
        .backgroundColor(this.palette().panelAlt)
        .borderRadius(12)
      }
    }
    .width('100%')
    .height('100%')
    .backgroundColor(this.palette().page)
  }

  @Builder
  DayColumn(dayIndex: number) {
    Column() {
      Column() {
        Text(WEEKDAY_LABELS[dayIndex])
          .fontSize(12)
      }
      .fontColor(this.palette().textPrimary)
      .width('100%')
      .height(HEADER_HEIGHT)
      .backgroundColor(this.palette().gridHeader)
      .textAlign(TextAlign.Center)

      Stack({ alignContent: Alignment.TopStart }) {
        Rect()
          .width('100%').height('100%')
          .fill(this.palette().gridDay)
          .radius(4)
        ForEach(
          resolveOverlaps(allMeetings(this.courses)
            .filter((item: CourseMeetingRef) => item.meeting.weekday === dayIndex + 1)),
          (item: CourseMeetingRef) => {
            this.CourseCard(item)
          },
          (item: CourseMeetingRef) => `${item.courseIndex}-${item.meeting.weekday}-${item.meeting.startSection}`,
        )
      }
      .width(46)
      .height(ROW_HEIGHT * this.sectionCount)
      .clip(true)
    }
  }

  @Builder
  CourseCard(item: CourseMeetingRef) {
    Column() {
      Text(item.course.courseName)
        .fontSize(12 * Prefs.fontScale())
        .fontWeight(FontWeight.Medium)
        .maxLines(4)
      Text(item.meeting.location.length > 0 ? item.meeting.location : '教室待定')
        .fontSize(9.5 * Prefs.fontScale())
        .maxLines(2)
    }
    .alignItems(HorizontalAlign.Start)
    .width(44)
    .height((item.meeting.endSection - item.meeting.startSection + 1) * ROW_HEIGHT - 2)
    .padding(3)
    .backgroundColor(CARD_COLORS[item.courseIndex % CARD_COLORS.length])
    .borderRadius(5)
    .onClick(() => {
      this.editingCourseIndex = item.courseIndex;
      this.editingMeetingIndex = item.meetingIndex;
      this.editingCourse = this.toImportedCourse(item.course);
      this.editDialogController.open();
    })
  }

  toImportedCourse(course: CourseSchedule): ImportedCourse {
    return {
      courseName: course.courseName,
      teacher: course.teacher,
      semester: course.semester,
      credit: course.credit,
      rawWeekText: course.rawWeekText,
      meetings: course.meetings.map((m) => ({
        weekday: m.weekday,
        startSection: m.startSection,
        endSection: m.endSection,
        weekRules: m.weekRules,
        location: m.location,
        campusAlias: m.campusId ?? null,
        customStartTime: m.customStartTime ?? null,
        customEndTime: m.customEndTime ?? null,
      })),
    };
  }

  applyEdit(updated: ImportedCourse): void {
    const old = this.courses[this.editingCourseIndex];
    if (!old) return;
    const meetings: CourseSchedule['meetings'] = updated.meetings.map((m: ImportedMeeting) => ({
      weekday: m.weekday,
      startSection: m.startSection,
      endSection: m.endSection,
      weekRules: m.weekRules,
      location: m.location,
      campusId: m.campusAlias,
      customStartTime: m.customStartTime ?? null,
      customEndTime: m.customEndTime ?? null,
    }));
    const newCourse: CourseSchedule = {
      courseName: updated.courseName,
      teacher: updated.teacher,
      semester: old.semester,
      credit: old.credit,
      rawWeekText: updated.rawWeekText,
      meetings: meetings,
    };
    // 拆分保存：同名同师课程含多个上课时段且改了名/师时，只改被编辑时段，
    // 其余时段（其他天）保留原名原师，避免跨天绑定连带修改
    const nameChanged = updated.courseName !== old.courseName;
    const teacherChanged = updated.teacher !== old.teacher;
    let ok = false;
    if (old.meetings.length > 1 && (nameChanged || teacherChanged)) {
      const targetIndex = Math.min(this.editingMeetingIndex, Math.max(0, old.meetings.length - 1));
      const keptMeetings = old.meetings.slice();
      keptMeetings.splice(targetIndex, 1);
      const keptCourse: CourseSchedule = {
        courseName: old.courseName,
        teacher: old.teacher,
        semester: old.semester,
        credit: old.credit,
        rawWeekText: old.rawWeekText,
        meetings: keptMeetings,
      };
      const extractedCourse: CourseSchedule = {
        courseName: updated.courseName,
        teacher: updated.teacher,
        semester: old.semester,
        credit: old.credit,
        rawWeekText: updated.rawWeekText,
        meetings: meetings.filter((m: MeetingTime, index: number) => index === targetIndex),
      };
      ok = ProfileStore.instance?.splitUpdateCourse(
        this.editingCourseIndex, keptCourse, extractedCourse, this.profileId) ?? false;
    } else {
      ok = ProfileStore.instance?.updateCourse(this.editingCourseIndex, newCourse, this.profileId) ?? false;
    }
    if (ok) {
      AppStorage.setOrCreate('dataVersion', Date.now());
    }
  }
}
