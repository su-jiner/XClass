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

/**
 * 课表导入页：粘贴文本 / 选择 txt·md·csv·tsv 文件 → 本地解析 → 预览校对 → 覆盖或新建课表。
 * 与 Android 导入向导、小程序导入页共用同一套解析规则；全程离线。
 */
@Entry
@Component
struct ImportPage {
  @StorageProp('themeVersion') @Watch('reload') themeVersion: number = 0;
  @StorageProp('systemDark') systemDark: boolean = false;
  @State text: string = '';
  @State mode: string = 'paste';
  @State parsed: CourseTableParseResult | null = null;
  @State previewCount: number = 0;
  @State toast: string = '';
  private courses: ImportedCourse[] = [];

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
      try {
        const file = fs.openSync(urls[0], fs.OpenMode.READ_ONLY);
        const stat = fs.statSync(file.fd);
        const buf = new ArrayBuffer(stat.size);
        fs.readSync(file.fd, buf);
        fs.closeSync(file);
        const content = util.TextDecoder.create('utf-8').decodeToString(new Uint8Array(buf));
        this.text = content;
        this.parse(content, urls[0]);
      } catch {
        this.toast = '读取文件失败';
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
    this.parsed = parsed;
    this.courses = parsed.courses;
    this.previewCount = parsed.courses.length;
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

  save(mode: 'overwrite' | 'new'): void {
    const parsed = this.parsed;
    if (!parsed || parsed.courses.length === 0) {
      this.toast = '暂无可导入课程';
      return;
    }
    const campusMap = ProfileStore.instance?.saveParsedTimeTables(parsed.timeTables, parsed.campusAliases) ?? {};
    const schedules: CourseSchedule[] = parsed.courses.map((course) => ({
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
    const name = ProfileStore.instance?.importCourses(
      schedules,
      mode,
      parsed.semester ? `${parsed.semester} 导入` : null,
      parsed.semesterStartDate,
      campusMap
    ) ?? '';
    AppStorage.setOrCreate('dataVersion', Date.now());
    this.toast = `已导入 ${schedules.length} 门课到「${name}」`;
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
      Text('纯本地解析：粘贴文本或选择 txt/md/csv/tsv 文件（图片与 PDF 暂不支持）')
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
            .height(120)
            .backgroundColor(this.palette().panelAlt)
            .fontColor(this.palette().text)
            .onChange((value: string) => { this.text = value; })
          Button('解析粘贴文本')
            .width('100%').margin({ top: 8 })
            .backgroundColor(this.palette().buttonBackground)
            .fontColor(this.palette().buttonText)
            .onClick(() => this.parse(this.text, 'paste.txt'))
          Button('从文件选择（txt/md/csv/tsv）')
            .width('100%').margin({ top: 8 })
            .backgroundColor(this.palette().panelAlt)
            .fontColor(this.palette().text)
            .onClick(() => this.pickFile())
          Button('导出课表分享文本（复制到剪贴板）')
            .width('100%').margin({ top: 8 })
            .backgroundColor(this.palette().panelAlt)
            .fontColor(this.palette().text)
            .onClick(() => this.exportShareText())

          if (this.parsed !== null) {
            Column() {
              Text(`识别到 ${this.previewCount} 门课`).fontSize(14).fontColor(this.palette().text)
              if (this.parsed.semester !== null && this.parsed.semester.length > 0) {
                Text(`学期：${this.parsed.semester}`).fontSize(12).fontColor(this.palette().textSecondary)
              }
              if (this.parsed.totalWeeks !== null) {
                Text(`总周数：${this.parsed.totalWeeks}`).fontSize(12).fontColor(this.palette().textSecondary)
              }
              if (this.parsed.timeTables.length > 0) {
                Text(`节次时间表：${this.parsed.timeTables.length} 套（已保存并建立校区映射）`)
                  .fontSize(12).fontColor(this.palette().textSecondary)
              }
              ForEach(this.parsed.warnings, (warning: string) => {
                Text(`⚠ ${warning}`).fontSize(12).fontColor(this.palette().textSecondary)
              })
            }
            .alignItems(HorizontalAlign.Start)
            .width('100%')
            .padding(12)
            .margin({ top: 8 })
            .backgroundColor(this.palette().panelAlt)
            .borderRadius(14)
          }

          ForEach(this.courses, (course: ImportedCourse) => {
            Column() {
              Text(course.courseName).fontSize(15).fontWeight(FontWeight.Medium).fontColor(this.palette().text)
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
          }, (course: ImportedCourse) => course.courseName + course.meetings.length)

          if (this.previewCount > 0) {
            Row() {
              Button('覆盖当前课表')
                .layoutWeight(1)
                .backgroundColor(this.palette().buttonBackground)
                .fontColor(this.palette().buttonText)
                .onClick(() => this.save('overwrite'))
              Button('新建课表导入')
                .layoutWeight(1)
                .margin({ left: 8 })
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
