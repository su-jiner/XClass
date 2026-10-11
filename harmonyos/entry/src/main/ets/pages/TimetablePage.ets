import {
  CourseSchedule, SimpleDate, CourseMeetingRef, ImportedCourse, ImportedMeeting,
  displayMeetings, guessCurrentWeek, totalWeeks, resolveOverlaps, SECTION_RANGES,
  SectionTimeProfile, resolveProfile, defaultTimeProfile, sanitizeStoredNotesText,
} from '../core/Core.ets';
import { ProfileStore } from '../core/ProfileStore.ets';
import { currentPalette, CARD_COLORS, ThemePalette } from '../model/Theme.ets';
import { Prefs } from '../model/Prefs.ets';
import { AddCourseDialog } from './AddCourseDialog.ets';
import { EditCourseDialog } from './EditCourseDialog.ets';
import { router } from '@kit.ArkUI';

const ROW_HEIGHT = 46;
/** 最后一节（第 12 节）额外加长：多行课程内容（如「教室待定」）完整显示，而非靠间距留白 */
const LAST_ROW_EXTRA = 18;
const HEADER_HEIGHT = 34;
const LEFT_COLUMN_WIDTH = 44;
const WEEKDAY_LABELS = ['一', '二', '三', '四', '五', '六', '日'];

@Component
export struct TimetablePage {
  @StorageProp('dataVersion') @Watch('reload') dataVersion: number = 0;
  @StorageProp('themeVersion') @Watch('reload') themeVersion: number = 0;
  @StorageProp('systemDark') systemDark: boolean = false;
  @State courses: CourseSchedule[] = [];
  @State semesterStart: SimpleDate = SimpleDate.today();
  @State currentWeek: number = 1;
  @State weeks: number = 20;
  @State selectedWeek: number = 1;
  @State displayProfile: SectionTimeProfile = defaultTimeProfile();
  @State sectionRanges: string[] = SECTION_RANGES.slice();
  @State sectionCount: number = 12;
  @State notes: string[] = [];
  @State editingCourseIndex: number = -1;
  @State editingMeetingIndex: number = 0;
  @State showDetail: boolean = false;
  @State detailItems: CourseMeetingRef[] = [];
  @State detailIndex: number = 0;
  @State editingCourse: ImportedCourse = {
    courseName: '', teacher: '', semester: '', credit: null, rawWeekText: '', meetings: [],
  };
  addDialogController: CustomDialogController = new CustomDialogController({
    builder: AddCourseDialog({
      onSaved: (): void => {
        AppStorage.setOrCreate('dataVersion', Date.now());
      },
    }),
    autoCancel: true,
    customStyle: false,
  });
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
    this.reload();
  }

  reload(): void {
    this.courses = ProfileStore.instance?.loadActiveCourses() ?? [];
    this.semesterStart = ProfileStore.instance?.getActiveSemesterStartDate() ?? SimpleDate.today();
    this.weeks = totalWeeks(this.courses);
    this.currentWeek = guessCurrentWeek(this.semesterStart, SimpleDate.today(), this.weeks);
    if (this.selectedWeek < 1 || this.selectedWeek > this.weeks) {
      this.selectedWeek = this.currentWeek;
    }
    // 左侧时间列动态跟随「当前显示时间表」（导入校区时间表后自动切换，可在时间表管理页手动改回）
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
    this.notes = sanitizeStoredNotesText(ProfileStore.instance?.listProfiles().find((p) => p.isActive)?.notes ?? []);
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

  /** 「+」选择面板：手动添加 / 导入课表文件 / 粘贴文本导入（与 Android、小程序一致） */
  showAddChooser(): void {
    ActionSheet.show({
      title: '添加课程',
      autoCancel: true,
      sheets: [
        {
          title: '手动添加课程',
          action: (): void => {
            this.addDialogController.open();
          },
        },
        {
          title: '导入课表文件（txt/md/csv/tsv）',
          action: (): void => {
            router.pushUrl({ url: 'pages/ImportPage', params: { mode: 'file' } });
          },
        },
        {
          title: '粘贴文本导入',
          action: (): void => {
            router.pushUrl({ url: 'pages/ImportPage', params: { mode: 'paste' } });
          },
        },
      ],
    });
  }

  currentSection(): number | null {
    const now = new Date();
    const pad = (n: number): string => String(n).padStart(2, '0');
    const nowText = `${pad(now.getHours())}:${pad(now.getMinutes())}`;
    for (let i = 0; i < this.sectionRanges.length; i++) {
      const parts = this.sectionRanges[i].split('-');
      if (parts.length === 2 && parts[0] <= nowText && nowText < parts[1]) return i + 1;
    }
    return null;
  }

  build() {
    Stack({ alignContent: Alignment.TopStart }) {
      // 自定义背景（设置页选择，存在沙箱 custom_background.jpg）
      if (Prefs.customBackgroundPath().length > 0) {
        Image(Prefs.customBackgroundPath())
          .width('100%')
          .height('100%')
          .objectFit(ImageFit.Cover)
          .opacity(0.35)
      }
      Column() {
      // 头部：周次 + 日期 + 操作按钮
      Row() {
        Column() {
          Text(this.headerText())
            .fontSize(15).fontWeight(FontWeight.Bold)
            .fontColor(this.palette().textPrimary)
          Text(SimpleDate.today().displayText())
            .fontSize(12)
            .fontColor(this.palette().textSecondary)
        }
        .alignItems(HorizontalAlign.Start)
        .layoutWeight(1)

        Button('＋')
          .backgroundColor(this.palette().panelBackground)
          .fontColor(this.palette().iconTint)
          .fontSize(20)
          .width(36).height(36)
          .margin({ right: 8 })
          .onClick(() => {
            this.showAddChooser();
          })

      }
      .width('100%')
      .padding({ left: 12, right: 12, top: 6, bottom: 6 })

      // 周切换
      Row() {
        Button('‹')
          .backgroundColor(this.palette().panelBackground)
          .fontColor(this.palette().textPrimary)
          .onClick(() => {
            if (this.selectedWeek > 1) this.selectedWeek -= 1;
          })
        Text(`第 ${this.selectedWeek} 周 / 共 ${this.weeks} 周`)
          .fontSize(13)
          .fontColor(this.palette().textSecondary)
          .margin({ left: 12, right: 12 })
          .onClick(() => {
            this.selectedWeek = this.currentWeek;
          })
        Button('›')
          .backgroundColor(this.palette().panelBackground)
          .fontColor(this.palette().textPrimary)
          .onClick(() => {
            if (this.selectedWeek < this.weeks) this.selectedWeek += 1;
          })
      }
      .width('100%')
      .padding({ left: 12, right: 12, bottom: 4 })

      // 周网格 + 底部备注（备注随课表内容一起滚动，非悬浮）
      Scroll() {
        Column() {
          Row() {
            // 左列：节次（固定编码时间）
            Column() {
              Text(`${this.weekStart().month}月`)
                .fontSize(10).fontWeight(FontWeight.Bold)
                .fontColor(this.palette().textPrimary)
                .height(HEADER_HEIGHT)
                .width('100%')
                .textAlign(TextAlign.Center)
              ForEach(this.sectionRanges, (range: string, index: number) => {
                Column() {
                  Text(`${index + 1}`).fontSize(11).fontWeight(FontWeight.Bold)
                  // 固定编码时间：每节始终显示起止时间
                  Text(range.split('-')[0]).fontSize(8)
                  Text(range.split('-')[1]).fontSize(8)
                }
                .fontColor(this.palette().textSecondary)
                .width(LEFT_COLUMN_WIDTH - 6)
                .height(index === this.sectionRanges.length - 1 ? ROW_HEIGHT + LAST_ROW_EXTRA : ROW_HEIGHT)
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

          // 课表级备注栏（随课表滚动，非空才显示：方框 + 左侧「备注」标签）
          if (this.notes.length > 0) {
            Column() {
              Text('备注')
                .fontSize(12).fontWeight(FontWeight.Bold)
                .fontColor(this.palette().textPrimary)
                .width('100%')
                .margin({ bottom: 4 })
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
            .padding({ left: 12, right: 12, top: 9, bottom: 9 })
            .margin({ left: 12, right: 12, top: 22, bottom: 16 })
            .backgroundColor(this.palette().panelAlt)
            .border({ width: 1, color: this.palette().gridLeft })
            .borderRadius(12)
          }
        }
        .alignItems(HorizontalAlign.Start)
      }
      .layoutWeight(1)
      .align(Alignment.Top)
      .scrollable(ScrollDirection.Vertical)
      .width('100%')
    }

    // 课程详情弹窗：重叠课程左右滑动切换查看
    if (this.showDetail) {
      this.CourseDetailOverlay()
    }
    }
    .width('100%')
    .height('100%')
    .backgroundColor(this.palette().page)
  }

  @Builder
  CourseDetailOverlay() {
    Stack() {
      Column() {
        Row() {
          Text(this.detailItems[this.detailIndex]?.course.courseName ?? '')
            .fontSize(17)
            .fontWeight(FontWeight.Bold)
            .fontColor(this.palette().textPrimary)
            .maxLines(1)
            .layoutWeight(1)
          if (this.detailItems.length > 1) {
            Text(`${this.detailIndex + 1} / ${this.detailItems.length}`)
              .fontSize(12)
              .fontColor(this.palette().textSecondary)
              .margin({ left: 8 })
          }
        }
        .width('100%')
        .margin({ bottom: 10 })

        Swiper() {
          ForEach(this.detailItems, (item: CourseMeetingRef) => {
            Column() {
              Text(`教师：${item.course.teacher.length > 0 ? item.course.teacher : '未知'}`)
              Text(`节次：第${item.meeting.startSection}-${item.meeting.endSection}节 ${this.detailTimeText(item)}`)
              Text(`周次：${item.course.rawWeekText.length > 0 ? item.course.rawWeekText : '未标注'}`)
              Text(`地点：${item.meeting.location.length > 0 ? item.meeting.location : '未标注'}`)
              Text(`学期：${item.course.semester}   学分：${item.course.credit === null ? 'N/A' : String(item.course.credit)}`)
            }
            .alignItems(HorizontalAlign.Start)
            .width('100%')
            .padding({ top: 4 })
            .fontSize(14)
            .fontColor(this.palette().textPrimary)
            .rowGap(10)
          }, (item: CourseMeetingRef) =>
            `${item.courseIndex}-${item.meeting.weekday}-${item.meeting.startSection}`)
        }
        .indicator(false)
        .height(190)
        .width('100%')
        .onChange((index: number) => {
          this.detailIndex = index;
        })

        Row({ space: 12 }) {
          Button('编辑课程')
            .layoutWeight(1)
            .height(40)
            .fontSize(14)
            .fontColor(this.palette().textPrimary)
            .backgroundColor(this.palette().panelAlt)
            .onClick(() => {
              this.editDetailCourse();
            })
          Button(Prefs.coverImageKey() === this.detailCoverKey() ? '取消课表封面' : '设为课表封面')
            .layoutWeight(1)
            .height(40)
            .fontSize(14)
            .fontColor(this.palette().iconTint)
            .backgroundColor(this.palette().panelAlt)
            .onClick(() => {
              this.toggleDetailCover();
            })
        }
        .width('100%')
        .margin({ top: 16 })
      }
      .width('92%')
      .padding(18)
      .borderRadius(20)
      .backgroundColor(this.palette().panel)
      .onClick(() => {})
    }
    .width('100%')
    .height('100%')
    .backgroundColor('#66000000')
    .onClick(() => {
      this.showDetail = false;
    })
  }

  detailCoverKey(): string {
    const item = this.detailItems[this.detailIndex];
    if (!item) return '';
    return `${item.meeting.weekday}:${item.meeting.startSection}`;
  }

  /** 详情时间文本：优先自定义起止时间，否则节次默认时间 */
  detailTimeText(item: CourseMeetingRef): string {
    if (item.meeting.customStartTime && item.meeting.customStartTime.length > 0) {
      return `${item.meeting.customStartTime}-${item.meeting.customEndTime ?? ''}`;
    }
    const start = this.sectionRanges[item.meeting.startSection - 1]?.split('-')[0] ?? '--:--';
    const end = this.sectionRanges[item.meeting.endSection - 1]?.split('-')[1] ?? '--:--';
    return `${start}-${end}`;
  }

  weekStart(): SimpleDate {
    return this.semesterStart.adding((this.selectedWeek - 1) * 7);
  }

  headerText(): string {
    const weekdayLabel = WEEKDAY_LABELS[SimpleDate.today().weekdayMonBased() - 1];
    if (this.selectedWeek === this.currentWeek) {
      return `第${this.selectedWeek}周 周${weekdayLabel}`;
    }
    return `第${this.selectedWeek}周（非本周）`;
  }

  /** 该节次在本周是否有课程（时间列时间跟随课程显示） */
  hasCourseInSection(section: number): boolean {
    const items = displayMeetings(this.courses, this.selectedWeek, false);
    for (const item of items) {
      if (section >= item.meeting.startSection && section <= item.meeting.endSection) {
        return true;
      }
    }
    return false;
  }

  @Builder
  DayColumn(dayIndex: number) {
    Column() {
      Column() {
        Text(WEEKDAY_LABELS[dayIndex])
          .fontSize(12).fontWeight(this.isToday(dayIndex) ? FontWeight.Bold : FontWeight.Normal)
        Text(`${this.dayDate(dayIndex).month}/${this.dayDate(dayIndex).day}`).fontSize(9)
      }
      .fontColor(this.palette().textPrimary)
      .width('100%')
      .height(HEADER_HEIGHT)
      .backgroundColor(this.isToday(dayIndex) ? this.palette().gridHeaderToday : this.palette().gridHeader)
      .textAlign(TextAlign.Center)

      Stack({ alignContent: Alignment.TopStart }) {
        Rect()
          .width('100%').height('100%')
          .fill(this.isToday(dayIndex) ? this.palette().gridDayToday : this.palette().gridDay)
          .radius(4)
        if (this.currentSection() !== null) {
          Line({ start: [0, 0], end: [44, 0] })
            .stroke(this.palette().iconTint)
            .strokeWidth(1.5)
            .offset({ x: 0, y: ((this.currentSection() ?? 1) - 1) * ROW_HEIGHT })
        }
        ForEach(
          resolveOverlaps(displayMeetings(this.courses, this.selectedWeek, false)
            .filter((item: CourseMeetingRef) => item.meeting.weekday === dayIndex + 1)),
          (item: CourseMeetingRef) => {
            this.CourseCard(item)
          },
          (item: CourseMeetingRef) => `${item.courseIndex}-${item.meeting.weekday}-${item.meeting.startSection}`,
        )
      }
      .width(46)
      .height(ROW_HEIGHT * this.sectionCount + LAST_ROW_EXTRA)
      .clip(true)
    }
  }

  dayDate(dayIndex: number): SimpleDate {
    return this.weekStart().adding(dayIndex);
  }

  isToday(dayIndex: number): boolean {
    return this.dayDate(dayIndex).equals(SimpleDate.today());
  }

  @Builder
  CourseCard(item: CourseMeetingRef) {
    Column() {
      if (this.isCover(item)) {
        Text('封面')
          .fontSize(7)
          .fontColor(this.palette().buttonText)
          .padding({ left: 3, right: 3, top: 1, bottom: 1 })
          .backgroundColor(this.palette().iconTint)
          .borderRadius(4)
          .margin({ bottom: 1 })
      }
      if (!item.isCurrentWeek) {
        Text(`[非本周] 第${item.nextActiveWeek}周`)
          .fontSize(7.5 * Prefs.fontScale())
          .maxLines(1)
      }
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
    .height((item.meeting.endSection - item.meeting.startSection + 1) * ROW_HEIGHT - 2 +
      (item.meeting.endSection >= this.sectionCount ? LAST_ROW_EXTRA : 0))
    .padding(3)
    .backgroundColor(CARD_COLORS[item.courseIndex % CARD_COLORS.length])
    .borderRadius(5)
    .opacity(item.isCurrentWeek ? 1 : 0.55)
    .border({ width: this.isCover(item) ? 2 : 0, color: this.palette().iconTint })
    .onClick(() => {
      this.showCourseDetail(item);
    })
  }

  /** 该课次是否被置顶为课表封面（设置页/课程详情可清除） */
  isCover(item: CourseMeetingRef): boolean {
    return Prefs.coverImageKey() === `${item.meeting.weekday}:${item.meeting.startSection}`;
  }

  showCourseDetail(item: CourseMeetingRef): void {
    // 收集同格子重叠课程组：节次区间相交且不同课程，按节次排序
    const dayItems = displayMeetings(this.courses, this.selectedWeek, false)
      .filter((it: CourseMeetingRef) => it.meeting.weekday === item.meeting.weekday);
    const group = dayItems.filter((other: CourseMeetingRef) => {
      const aS = Math.min(Math.max(item.meeting.startSection, 1), 12);
      const aE = Math.min(Math.max(item.meeting.endSection, 1), 12);
      const bS = Math.min(Math.max(other.meeting.startSection, 1), 12);
      const bE = Math.min(Math.max(other.meeting.endSection, 1), 12);
      return !(aE < bS || bE < aS);
    }).sort((a: CourseMeetingRef, b: CourseMeetingRef) => a.meeting.startSection - b.meeting.startSection);
    this.detailItems = group.length > 0 ? group : [item];
    this.detailIndex = Math.max(0, this.detailItems.findIndex(
      (g: CourseMeetingRef) => g.courseIndex === item.courseIndex && g.meeting.startSection === item.meeting.startSection));
    this.showDetail = true;
  }

  /** 详情弹窗：编辑当前页课程 */
  editDetailCourse(): void {
    const item = this.detailItems[this.detailIndex];
    if (!item) return;
    this.editingCourseIndex = item.courseIndex;
    this.editingMeetingIndex = item.meetingIndex;
    this.editingCourse = this.toImportedCourse(item.course);
    this.showDetail = false;
    this.editDialogController.open();
  }

  /** 详情弹窗：切换当前页课程为课表封面 */
  toggleDetailCover(): void {
    const item = this.detailItems[this.detailIndex];
    if (!item) return;
    const slotKey = `${item.meeting.weekday}:${item.meeting.startSection}`;
    Prefs.setCoverImageKey(Prefs.coverImageKey() === slotKey ? '' : slotKey);
    AppStorage.setOrCreate('dataVersion', Date.now());
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
        this.editingCourseIndex, keptCourse, extractedCourse, '') ?? false;
    } else {
      ok = ProfileStore.instance?.updateCourse(this.editingCourseIndex, newCourse, '') ?? false;
    }
    if (ok) {
      AppStorage.setOrCreate('dataVersion', Date.now());
    }
  }
}
