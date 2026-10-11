import { ImportedCourse, parseWeekRulesText } from '../core/Core.ets';
import { currentPalette, ThemePalette } from '../model/Theme.ets';
import { Prefs } from '../model/Prefs.ets';

/**
 * 导入预览课程编辑弹窗：修改课程名/教师/地点/周次后回调 onSaved。
 * 与 Android 导入向导编辑、iOS ImportView 编辑弹窗功能一致。
 */
@CustomDialog
export struct EditCourseDialog {
  controller?: CustomDialogController;
  course: ImportedCourse = {
    courseName: '',
    teacher: '',
    semester: '',
    credit: null,
    rawWeekText: '',
    meetings: [],
  };
  /** 被编辑的上课时段在 course.meetings 中的索引（默认 0）。 */
  meetingIndex: number = 0;
  onSaved: (updated: ImportedCourse) => void = (updated: ImportedCourse): void => {};
  @State courseName: string = '';
  @State teacher: string = '';
  @State location: string = '';
  @State weekText: string = '';
  @State customStart: string = '';
  @State customEnd: string = '';
  @State errorMessage: string = '';
  @StorageProp('systemDark') systemDark: boolean = false;

  aboutToAppear(): void {
    const targetIndex = Math.min(this.meetingIndex, Math.max(0, this.course.meetings.length - 1));
    const target = this.course.meetings[targetIndex];
    this.courseName = this.course.courseName;
    this.teacher = this.course.teacher;
    this.location = target ? target.location : '';
    this.customStart = target ? (target.customStartTime ?? '') : '';
    this.customEnd = target ? (target.customEndTime ?? '') : '';
    this.weekText = this.course.rawWeekText.length > 0 ? this.course.rawWeekText
      : (target ? this.weekRulesText(target.weekRules) : '');
  }

  palette(): ThemePalette {
    return currentPalette(Prefs.theme(), Prefs.darkMode(), this.systemDark);
  }

  weekRulesText(rules: { startWeek: number; endWeek: number; parity: string }[]): string {
    return rules.map((rule) => {
      const base = rule.startWeek === rule.endWeek ? `${rule.startWeek}` : `${rule.startWeek}-${rule.endWeek}`;
      return rule.parity === 'ODD' ? `${base}周(单)` : (rule.parity === 'EVEN' ? `${base}周(双)` : `${base}周`);
    }).join(',');
  }

  save(): void {
    const name = this.courseName.trim();
    if (name.length === 0) { this.errorMessage = '课程名不能为空'; return; }
    // 自定义上课时间：两个都填才生效，校验 HH:mm 且开始早于结束
    const cs = this.customStart.trim();
    const ce = this.customEnd.trim();
    let customStartTime: string | null = null;
    let customEndTime: string | null = null;
    if (cs.length > 0 || ce.length > 0) {
      if (!/^([01]\d|2[0-3]):[0-5]\d$/.test(cs) || !/^([01]\d|2[0-3]):[0-5]\d$/.test(ce)) {
        this.errorMessage = '时间格式应为 HH:mm';
        return;
      }
      if (cs >= ce) {
        this.errorMessage = '结束时间须晚于开始时间';
        return;
      }
      customStartTime = cs;
      customEndTime = ce;
    }
    const weekRules = parseWeekRulesText(this.weekText.trim().length > 0 ? this.weekText.trim() : '1-16周');
    const first = this.course.meetings.length > 0 ? this.course.meetings[0] : null;
    const targetIndex = Math.min(this.meetingIndex, Math.max(0, this.course.meetings.length - 1));
    const updated: ImportedCourse = {
      courseName: name,
      teacher: this.teacher.trim(),
      semester: this.course.semester,
      credit: this.course.credit,
      rawWeekText: this.weekText.trim(),
      meetings: this.course.meetings.map((meeting, index) => {
        if (index !== targetIndex) return meeting;
        return {
          weekday: meeting.weekday,
          startSection: meeting.startSection,
          endSection: meeting.endSection,
          weekRules: weekRules,
          location: this.location.trim(),
          campusAlias: meeting.campusAlias,
          customStartTime: customStartTime,
          customEndTime: customEndTime,
        };
      }),
    };
    if (!first) {
      updated.meetings = [{
        weekday: 1,
        startSection: 1,
        endSection: 2,
        weekRules: weekRules,
        location: this.location.trim(),
        campusAlias: null,
        customStartTime: customStartTime,
        customEndTime: customEndTime,
      }];
    }
    this.controller?.close();
    this.onSaved(updated);
  }

  build() {
    Column() {
      Text('编辑课程')
        .fontSize(17).fontWeight(FontWeight.Bold)
        .fontColor(this.palette().textPrimary)
        .margin({ bottom: 12 })

      Scroll() {
        Column() {
          this.fieldLabel('课程名称')
          TextInput({ text: this.courseName, placeholder: '课程名称' })
            .onChange((value: string) => { this.courseName = value; })
            .height(40)
          this.fieldLabel('授课教师（可留空）')
          TextInput({ text: this.teacher, placeholder: '授课教师（可留空）' })
            .onChange((value: string) => { this.teacher = value; })
            .height(40)
          this.fieldLabel('上课地点（可留空）')
          TextInput({ text: this.location, placeholder: '上课地点（可留空）' })
            .onChange((value: string) => { this.location = value; })
            .height(40)
          this.fieldLabel('自定义开始（可留空，如 09:00）')
          TextInput({ text: this.customStart, placeholder: '自定义开始 如 09:00' })
            .onChange((value: string) => { this.customStart = value; })
            .height(40)
          this.fieldLabel('自定义结束（可留空，如 10:40；留空则按节次默认时间）')
          TextInput({ text: this.customEnd, placeholder: '自定义结束 如 10:40' })
            .onChange((value: string) => { this.customEnd = value; })
            .height(40)
          this.fieldLabel('周次（如 1-16周 / 3-9周(单)）')
          TextInput({ text: this.weekText, placeholder: '1-16周 / 3-9周(单)' })
            .onChange((value: string) => { this.weekText = value; })
            .height(40)

          if (this.errorMessage.length > 0) {
            Text(this.errorMessage)
              .fontSize(12)
              .fontColor('#D35454')
              .margin({ top: 8 })
          }
        }
        .alignItems(HorizontalAlign.Start)
        .width('100%')
      }
      .align(Alignment.Top)
      .scrollable(ScrollDirection.Vertical)
      .layoutWeight(1)

      Row() {
        Button('取消')
          .backgroundColor(this.palette().panelAlt)
          .fontColor(this.palette().textPrimary)
          .onClick(() => {
            this.controller?.close();
          })
        Button('确认修改')
          .backgroundColor(this.palette().buttonBg)
          .fontColor(this.palette().buttonText)
          .margin({ left: 10 })
          .onClick(() => {
            this.save();
          })
      }
      .width('100%')
      .justifyContent(FlexAlign.End)
      .margin({ top: 12 })
    }
    .padding(16)
    .backgroundColor(this.palette().panelAlt)
    .borderRadius(16)
  }

  @Builder
  fieldLabel(text: string) {
    Text(text)
      .fontSize(12)
      .fontColor(this.palette().textSecondary)
      .margin({ top: 8, bottom: 2 })
  }
}
