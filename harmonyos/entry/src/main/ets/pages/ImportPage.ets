import {
  CourseSchedule, CourseTableParseResult, ImportedCourse, Weekday, DEFAULT_TOTAL_WEEKS,
  parseCourseTableText, parseWeekRulesText, parseTimeTablesText, encodeShareText,
} from '../core/Core.ets';
import { ProfileStore } from '../core/ProfileStore.ets';
import { currentPalette, ThemePalette } from '../model/Theme.ets';
import { Prefs } from '../model/Prefs.ets';
import { documentViewPicker } from '@kit.CoreFileKit';
import { fs } from '@kit.CoreFileKit';
import { util } from '@kit.ArkTS';
import { pasteboard } from '@kit.BasicServicesKit';
import { router } from '@kit.ArkUI';
import { EditCourseDialog } from './EditCourseDialog.ets';

interface ImportGroup {
  name: string;
  parsed: CourseTableParseResult;
  courses: ImportedCourse[];
}

/**
 * 课表导入页：粘贴文本 / 选择 txt·md·csv·tsv 文件（可多选，一个文件 = 一个课表）→
 * 本地解析 → 预览校对（可逐条编辑/删除）→ 确认后覆盖或新建课表。
 * 与 Android 导入向导、小程序导入页共用同一套解析规则；全程离线。
 */
@Entry
@Component
struct ImportPage {
  @StorageProp('themeVersion') @Watch('reload') themeVersion: number = 0;
  @StorageProp('systemDark') systemDark: boolean = false;
  @State text: string = '';
  @State mode: string = 'paste';
  @State groups: ImportGroup[] = [];
  @State previewCount: number = 0;
  @State toast: string = '';
  @State editingCourse: ImportedCourse | null = null;

  editController: CustomDialogController = new CustomDialogController({
    builder: EditCourseDialog({
      course: this.editingCourse ?? {
        courseName: '', teacher: '', semester: '', credit: null, rawWeekText: '', meetings: [],
      },
      onSaved: (updated: ImportedCourse): void => {
        this.commitEdit(updated);
      },
    }),
  });

  aboutToAppear(): void {
    const params = router.getParams() as Record<string, string>;
    if (params && params.mode) this.mode = params.mode;
    if (this.mode === 'file') this.pickFile();
  }

  reload(): void {
    this.toast = this.toast;
  }

  palette(): ThemePalette {
    return currentPalette(Prefs.theme(), Prefs.darkMode(), this.systemDark);
  }

  pickFile(): void {
    const picker = new documentViewPicker.DocumentViewPicker();
    const options = new documentViewPicker.DocumentSelectOptions();
    options.fileSuffixFilters = ['.txt', '.md', '.csv', '.tsv'];
    picker.select(options).then((urls: Array<string>) => {
      if (urls.length === 0) return;
      let total = 0;
      urls.forEach((url) => {
        try {
          const file = fs.openSync(url, fs.OpenMode.READ_ONLY);
          const stat = fs.statSync(file.fd);
          const buf = new ArrayBuffer(stat.size);
          fs.readSync(file.fd, buf);
          fs.closeSync(file);
          const content = util.TextDecoder.create('utf-8').decodeToString(new Uint8Array(buf));
          const name = url.split('/').pop() ?? url;
          this.parse(content, name);
          total += this.groups.length > 0 ? this.groups[this.groups.length - 1].courses.length : 0;
        } catch {
          this.toast = '读取文件失败';
        }
      });
      if (urls.length > 1) {
        this.toast = `已解析 ${urls.length} 份课表文件，可逐条编辑后再保存`;
      }
    }).catch(() => {
      this.toast = '未选择文件';
    });
  }

  parse(content: string, sourceName: string): void {
    let rows: string[][] | null = null;
    const lower = sourceName.toLowerCase();
    if (lower.endsWith('.csv')) rows = this.splitDelimited(content, ',');
    if (lower.endsWith('.tsv')) rows = this.splitDelimited(content, '\t');
    const parsed = parseCourseTableText(content, rows);
    const groups = this.groups.slice();
    groups.push({ name: sourceName, parsed: parsed, courses: parsed.courses.slice() });
    this.groups = groups;
    this.previewCount = this.groups.reduce((sum, group) => sum + group.courses.length, 0);
    if (parsed.courses.length === 0 && parsed.warnings.length > 0) this.toast = parsed.warnings[0];
  }

  /** 引号安全的 CSV/TSV 拆分 */
  splitDelimited(text: string, delimiter: string): string[][] {
    const rows: string[][] = [];
    text.split(/\r?\n/).forEach((line) => {
      if (line.trim().length === 0) return;
      const cells: string[] = [];
      let current = '';
      let inQuotes = false;
      for (let i = 0; i < line.length; i++) {
        const ch = line[i];
        if (ch === '"') {
          if (inQuotes && line[i + 1] === '"') { current += '"'; i++; } else { inQuotes = !inQuotes; }
          continue;
        }
        if (ch === delimiter && !inQuotes) { cells.push(current.trim()); current = ''; continue; }
        current += ch;
      }
      cells.push(current.trim());
      rows.push(cells);
    });
    return rows;
  }

  meetingText(course: ImportedCourse): string {
    return course.meetings.map((meeting) => {
      const label = ['一', '二', '三', '四', '五', '六', '日'][meeting.weekday - 1];
      const campus = meeting.campusAlias ? `（${meeting.campusAlias}）` : '';
      const weeks = meeting.weekRules.map((rule) => {
        const base = rule.startWeek === rule.endWeek ? `${rule.startWeek}周` : `${rule.startWeek}-${rule.endWeek}周`;
        return rule.parity === 'ODD' ? `${base}(单)` : (rule.parity === 'EVEN' ? `${base}(双)` : base);
      }).join(',');
      return `周${label} 第${meeting.startSection}-${meeting.endSection}节 ${weeks}${campus}${meeting.location}`;
    }).join('；');
  }

  openEdit(groupIndex: number, courseIndex: number): void {
    const group = this.groups[groupIndex];
    if (!group) return;
    const course = group.courses[courseIndex];
    if (!course) return;
    this.editingCourse = {
      courseName: course.courseName,
      teacher: course.teacher,
      semester: course.semester,
      credit: course.credit,
      rawWeekText: course.rawWeekText,
      meetings: course.meetings.map((m) => ({
        weekday: m.weekday,
        startSection: m.startSection,
        endSection: m.endSection,
        weekRules: m.weekRules.map((r) => ({ startWeek: r.startWeek, endWeek: r.endWeek, parity: r.parity })),
        location: m.location,
        campusAlias: m.campusAlias,
      })),
    };
    this.editController.open();
  }

  commitEdit(updated: ImportedCourse): void {
    const gs = this.groups.slice();
    const group = gs[this.lastEditGroup];
    if (!group) return;
    const courses = group.courses.map((course, index) => index === this.lastEditCourse ? updated : course);
    gs[this.lastEditGroup] = { ...group, courses: courses };
    this.groups = gs;
    this.previewCount = this.groups.reduce((sum, g) => sum + g.courses.length, 0);
    this.toast = '已更新，保存后生效';
  }

  lastEditGroup: number = -1;
  lastEditCourse: number = -1;

  removeCourse(groupIndex: number, courseIndex: number): void {
    const gs = this.groups.slice();
    const group = gs[groupIndex];
    if (!group) return;
    const courses = group.courses.slice();
    courses.splice(courseIndex, 1);
    gs[groupIndex] = { ...group, courses: courses };
    this.groups = gs;
    this.previewCount = this.groups.reduce((sum, g) => sum + g.courses.length, 0);
  }

  save(mode: 'overwrite' | 'new'): void {
    const valid = this.groups.filter((group) => group.courses.length > 0);
    if (valid.length === 0) {
      this.toast = '暂无可导入课程';
      return;
    }
    const store = ProfileStore.instance;
    if (!store) { this.toast = '课表存储未就绪'; return; }
    let imported = 0;
    let firstName = '';
    valid.forEach((group, index) => {
      const campusMap = store.saveParsedTimeTables(group.parsed.timeTables, group.parsed.campusAliases) ?? {};
      const schedules: CourseSchedule[] = group.courses.map((course) => ({
        courseName: course.courseName,
        teacher: course.teacher,
        semester: course.semester,
        credit: course.credit,
        rawWeekText: course.rawWeekText,
        meetings: course.meetings.map((meeting) => ({
          weekday: meeting.weekday,
          startSection: meeting.startSection,
          endSection: meeting.endSection,
          weekRules: meeting.weekRules,
          location: meeting.location,
          campusId: meeting.campusAlias ? (campusMap[meeting.campusAlias] ?? null) : null,
        })),
      }));
      const modeForGroup: 'overwrite' | 'new' = (mode === 'overwrite' && index === 0) ? 'overwrite' : 'new';
      const semesterName = group.parsed.semester ? `${group.parsed.semester} 导入` : null;
      const groupName = valid.length > 1 ? (semesterName ?? group.name) : semesterName;
      const name = store.importCourses(
        schedules,
        modeForGroup,
        modeForGroup === 'new' ? groupName : null,
        group.parsed.semesterStartDate,
        campusMap
      ) ?? '';
      imported += schedules.length;
      if (index === 0) firstName = name;
    });
    AppStorage.setOrCreate('dataVersion', Date.now());
    this.toast = `已导入 ${imported} 门课到「${firstName}」`;
    setTimeout(() => router.back(), 800);
  }

  /** 导出当前课表为 XClass 分享文本，复制到剪贴板（朋友粘贴即可导入任意端）。 */
  exportShareText(): void {
    const store = ProfileStore.instance;
    if (!store) { this.toast = '课表存储未就绪'; return; }
    const courses = store.loadActiveCourses();
    if (courses.length === 0) { this.toast = '当前课表还没有课程，无法导出'; return; }
    const active = store.listProfiles().find((p) => p.isActive);
    const campusMap = store.loadCampusMap();
    const idToAlias: Record<string, string> = {};
    Object.keys(campusMap).forEach((alias) => { idToAlias[campusMap[alias]] = alias; });
    const semester = courses.find((c) => c.semester.trim().length > 0)?.semester ?? null;
    let totalWeeks: number | null = null;
    courses.forEach((course) => course.meetings.forEach((meeting) => meeting.weekRules.forEach((rule) => {
      if (totalWeeks === null || rule.endWeek > totalWeeks) totalWeeks = rule.endWeek;
    })));
    const text = encodeShareText(
      courses,
      active?.name ?? null,
      semester,
      store.getActiveSemesterStartDate().isoString(),
      totalWeeks,
      idToAlias
    );
    const data = pasteboard.createData(pasteboard.MIMETYPE_TEXT_PLAIN, text);
    pasteboard.getSystemPasteboard().setData(data).then(() => {
      this.toast = '已复制课表分享文本，可直接粘贴给朋友';
    }).catch(() => {
      this.toast = '复制失败，请重试';
    });
  }

  @Builder
  card() {
    Column() {
      Text('导入课表').fontSize(24).fontWeight(FontWeight.Bold).fontColor(this.palette().text)
      Text('纯本地解析：粘贴文本或选择 txt/md/csv/tsv 文件（可多选，一个文件 = 一个课表）')
        .fontSize(12).fontColor(this.palette().textSecondary).margin({ top: 4 })
    }
    .alignItems(HorizontalAlign.Start)
    .width('100%')
    .padding({ left: 16, right: 16, top: 12, bottom: 8 })
  }

  build() {
    Column() {
      this.card()
      Scroll() {
        Column() {
          TextArea({ text: this.text, placeholder: '粘贴课表文本，例如：信息技术/(1-2节)12-14周(双),15-19周/（乌）E406/刘文杰' })
            .height(110)
            .backgroundColor(this.palette().panelAlt)
            .fontColor(this.palette().text)
            .onChange((value: string) => { this.text = value; })
          Button('解析粘贴文本')
            .width('100%').margin({ top: 8 })
            .backgroundColor(this.palette().buttonBackground)
            .fontColor(this.palette().buttonText)
            .onClick(() => this.parse(this.text, 'paste.txt'))
          Button('从文件选择（可多选：txt/md/csv/tsv）')
            .width('100%').margin({ top: 8 })
            .backgroundColor(this.palette().panelAlt)
            .fontColor(this.palette().text)
            .onClick(() => this.pickFile())
          Button('导出课表分享文本（复制到剪贴板）')
            .width('100%').margin({ top: 8 })
            .backgroundColor(this.palette().panelAlt)
            .fontColor(this.palette().text)
            .onClick(() => this.exportShareText())

          if (this.groups.length > 0) {
            Text(`已解析 ${this.groups.length} 份课表文本，全部可编辑后再保存`)
              .fontSize(12).fontColor(this.palette().textSecondary).margin({ top: 8 })
          }

          ForEach(this.groups, (group: ImportGroup, groupIndex: number) => {
            Column() {
              Text(group.name).fontSize(14).fontWeight(FontWeight.Bold).fontColor(this.palette().text)
              if (group.parsed.semester !== null && group.parsed.semester.length > 0) {
                Text(`学期：${group.parsed.semester}`).fontSize(12).fontColor(this.palette().textSecondary)
              }
              if (group.parsed.totalWeeks !== null) {
                Text(`总周数：${group.parsed.totalWeeks}`).fontSize(12).fontColor(this.palette().textSecondary)
              }
              if (group.parsed.timeTables.length > 0) {
                Text(`节次时间表：${group.parsed.timeTables.length} 套（已保存并建立校区映射）`)
                  .fontSize(12).fontColor(this.palette().textSecondary)
              }
              ForEach(group.parsed.warnings, (warning: string) => {
                Text(`⚠ ${warning}`).fontSize(12).fontColor(this.palette().textSecondary)
              })
            }
            .alignItems(HorizontalAlign.Start)
            .width('100%')
            .padding(12)
            .margin({ top: 8 })
            .backgroundColor(this.palette().panelAlt)
            .borderRadius(14)

            ForEach(group.courses, (course: ImportedCourse, courseIndex: number) => {
              Column() {
                Row() {
                  Text(course.courseName).fontSize(15).fontWeight(FontWeight.Medium).fontColor(this.palette().text)
                    .layoutWeight(1)
                  Text('编辑').fontSize(12).fontColor(this.palette().iconTint)
                    .onClick(() => {
                      this.lastEditGroup = groupIndex;
                      this.lastEditCourse = courseIndex;
                      this.openEdit(groupIndex, courseIndex);
                    })
                  Text('删除').fontSize(12).fontColor(this.palette().textSecondary)
                    .margin({ left: 10 })
                    .onClick(() => this.removeCourse(groupIndex, courseIndex))
                }
                .width('100%')
                Text(this.meetingText(course)).fontSize(12).fontColor(this.palette().textSecondary)
                if (course.teacher.length > 0) {
                  Text(course.teacher).fontSize(12).fontColor(this.palette().textSecondary)
                }
              }
              .alignItems(HorizontalAlign.Start)
              .width('100%')
              .padding(12)
              .margin({ top: 8 })
              .backgroundColor(this.palette().panel)
              .borderRadius(14)
            }, (course: ImportedCourse) => course.courseName + course.meetings.length + groupIndex + courseIndex)
          }, (group: ImportGroup) => group.name + group.courses.length)

          if (this.previewCount > 0) {
            Row() {
              if (this.groups.length === 1) {
                Button('覆盖当前课表')
                  .layoutWeight(1)
                  .backgroundColor(this.palette().buttonBackground)
                  .fontColor(this.palette().buttonText)
                  .onClick(() => this.save('overwrite'))
              }
              Button(`保存（${this.previewCount} 门课）`)
                .layoutWeight(1)
                .margin({ left: this.groups.length === 1 ? 8 : 0 })
                .backgroundColor(this.palette().panelAlt)
                .fontColor(this.palette().text)
                .onClick(() => this.save('new'))
            }
            .width('100%')
            .margin({ top: 12 })
          }

          if (this.toast.length > 0) {
            Text(this.toast).fontSize(12).fontColor(this.palette().iconTint).margin({ top: 8 })
          }
        }
        .width('100%')
        .padding(16)
      }
      .layoutWeight(1)
    }
    .width('100%')
    .height('100%')
    .backgroundColor(this.palette().page)
  }
}
