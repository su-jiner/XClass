/**
 * 工具页：绩点计算器 / 成绩查询 / 考试安排 / 学业完成。
 * 逻辑与 Android GpaCalculator、小程序 tools.js 对齐（纯函数在 Core.ets，可 Node 验证）。
 */
import {
  CourseSchedule, SimpleDate, GpaCourse, GradeEntry, ExamEntry, ProgressInfo, ProgressModule,
  gpaOfScore, scoreOfGrade, gpaSummary, groupGradesBySemester, examCountdownText, earnedCreditsFromGrades,
} from '../core/Core.ets';
import { ProfileStore } from '../core/ProfileStore.ets';
import {
  currentPalette, ThemePalette,
} from '../model/Theme.ets';
import { Prefs } from '../model/Prefs.ets';
import { router } from '@kit.ArkUI';

const LEVEL_SCORES: Record<string, number> = { '优秀': 95, '良好': 85, '中等': 75, '及格': 65, '不及格': 50 };

@Component
export struct ToolsPage {
  @StorageProp('themeVersion') themeVersion: number = 0;
  @StorageProp('systemDark') systemDark: boolean = false;
  @State tab: number = 0;
  @State gpaCourses: GpaCourse[] = [];
  @State summary: { gpa: string; weighted: string; arithmetic: string; credits: string } =
    { gpa: '0.00', weighted: '0.00', arithmetic: '0.00', credits: '0.0' };
  @State gradeGroups: Array<{ name: string; items: GradeEntry[]; credits: number; average: number; count: number }> = [];
  @State exams: ExamEntry[] = [];
  @State countdown: string = '';
  @State progress: ProgressInfo = { totalRequired: 160, totalEarned: 0 };
  @State modules: ProgressModule[] = [];
  @State toast: string = '';
  private tabs: string[] = ['绩点计算器', '成绩查询', '考试安排', '学业完成'];

  aboutToAppear(): void {
    this.reload();
  }

  palette(): ThemePalette {
    return currentPalette(Prefs.theme(), Prefs.darkMode(), this.systemDark);
  }

  reload(): void {
    const store = ProfileStore.instance;
    if (!store) return;
    this.gpaCourses = store.loadGpaCourses();
    this.summary = gpaSummary(this.gpaCourses);
    this.gradeGroups = groupGradesBySemester(store.loadGrades());
    this.exams = store.loadExams().sort((a, b) => String(a.time).localeCompare(String(b.time)));
    this.countdown = examCountdownText(this.exams);
    this.progress = store.loadProgress();
    this.modules = store.loadProgressModules();
  }

  bump(): void {
    AppStorage.setOrCreate('dataVersion', Date.now());
    this.reload();
  }

  // ---------------- 绩点计算器 ----------------

  promptAddGpa(): void {
    this.alertText('添加绩点课程', '输入：课程名/分数或等级/学分，如 高等数学/88/4 或 高等数学/优秀/4', 'gpa');
  }

  toggleGpaIncluded(index: number): void {
    const list = this.gpaCourses.slice();
    if (index < 0 || index >= list.length) return;
    list[index].included = !list[index].included;
    ProfileStore.instance?.saveGpaCourses(list);
    this.bump();
  }

  clearGpa(): void {
    ProfileStore.instance?.saveGpaCourses([]);
    this.bump();
  }

  importGradesToGpa(): void {
    const store = ProfileStore.instance;
    if (!store) return;
    const grades = store.loadGrades();
    if (grades.length === 0) { this.toast = '成绩查询中暂无成绩'; return; }
    const list: GpaCourse[] = grades.map((grade) => ({
      courseName: grade.courseName,
      credit: grade.credit,
      gradeType: 'percent',
      score: grade.score,
      level: '',
      included: true,
    }));
    store.saveGpaCourses(list);
    this.bump();
    this.toast = `已从成绩导入 ${list.length} 门`;
  }

  // ---------------- 成绩查询 ----------------

  promptAddGrade(): void {
    this.alertText('添加成绩', '输入：课程名/学分/分数/学期，如 高等数学/4/89/2026-2027-1', 'grade');
  }

  deleteGrade(semester: string, name: string): void {
    const store = ProfileStore.instance;
    if (!store) return;
    const list = store.loadGrades().filter((grade) => !(grade.semester === semester && grade.courseName === name));
    store.saveGrades(list);
    this.bump();
  }

  // ---------------- 考试安排 ----------------

  promptAddExam(): void {
    this.alertText('添加考试', '输入：课程名/时间/地点/座位，如 高等数学/2026-06-20 08:30/逸夫楼101/32号', 'exam');
  }

  deleteExam(index: number): void {
    const store = ProfileStore.instance;
    if (!store) return;
    const list = store.loadExams().filter((item, itemIndex) => itemIndex !== index);
    store.saveExams(list);
    this.bump();
  }

  // ---------------- 学业完成 ----------------

  promptEditProgress(): void {
    this.alertText('毕业学分', `输入：已修/需修，如 ${this.progress.totalEarned}/${this.progress.totalRequired}`, 'progress');
  }

  promptEditModule(index: number): void {
    if (index < 0 || index >= this.modules.length) return;
    const module = this.modules[index];
    this.alertText(module.name, `输入：已修/需修，如 ${module.earned}/${module.required}`, 'module:' + index);
  }

  calculateFromGrades(): void {
    const store = ProfileStore.instance;
    if (!store) return;
    const earned = earnedCreditsFromGrades(store.loadGrades());
    store.saveProgress({ totalEarned: earned, totalRequired: this.progress.totalRequired || 160 });
    this.bump();
    this.toast = `已按通过课程累计 ${earned} 学分`;
  }

  // ---------------- 通用文本输入（跳 ToolsInputPage 录入，返回后应用） ----------------

  alertText(title: string, placeholder: string, op: string): void {
    router.pushUrl({
      url: 'pages/ToolsInputPage?title=' + encodeURIComponent(title)
        + '&placeholder=' + encodeURIComponent(placeholder)
        + '&op=' + encodeURIComponent(op),
    });
  }

  onPageShow(): void {
    const input = AppStorage.get<string>('pendingToolsInput');
    const op = AppStorage.get<string>('pendingToolsOp');
    AppStorage.setOrCreate('pendingToolsInput', '');
    AppStorage.setOrCreate('pendingToolsOp', '');
    if (input === undefined || input === null || input === '' || op === undefined || op === null || op === '') {
      this.reload();
      return;
    }
    this.applyInput(op, input);
    this.reload();
  }

  applyInput(op: string, text: string): void {
    if (op === 'gpa') {
      const parts = text.split('/').map((item) => item.trim());
      if (parts.length < 3) { this.toast = '格式：课程名/分数或等级/学分'; return; }
      const isLevel = !/^\d/.test(parts[1]);
      const course: GpaCourse = {
        courseName: parts[0] || '未命名课程',
        credit: Number(parts[2] || 0),
        gradeType: isLevel ? 'level' : 'percent',
        level: isLevel ? parts[1] : '',
        score: isLevel ? 0 : Number(parts[1] || 0),
        included: true,
      };
      const list = this.gpaCourses.slice();
      list.push(course);
      ProfileStore.instance?.saveGpaCourses(list);
      return;
    }
    if (op === 'grade') {
      const parts = text.split('/').map((item) => item.trim());
      if (parts.length < 3) { this.toast = '格式：课程名/学分/分数/学期'; return; }
      const store = ProfileStore.instance;
      if (!store) return;
      const list = store.loadGrades().slice();
      list.push({
        courseName: parts[0] || '未命名课程',
        credit: Number(parts[1] || 0),
        score: Number(parts[2] || 0),
        semester: parts[3] || '未标注学期',
      });
      store.saveGrades(list);
      return;
    }
    if (op === 'exam') {
      const parts = text.split('/').map((item) => item.trim());
      if (parts.length < 2) { this.toast = '格式：课程名/时间/地点/座位'; return; }
      const store = ProfileStore.instance;
      if (!store) return;
      const list = store.loadExams().slice();
      list.push({
        courseName: parts[0] || '未命名考试',
        time: parts[1] || '',
        location: parts[2] || '',
        seat: parts[3] || '',
      });
      store.saveExams(list);
      return;
    }
    if (op === 'progress') {
      const parts = text.split('/').map((item) => Number(item.trim()));
      const store = ProfileStore.instance;
      if (!store) return;
      store.saveProgress({ totalEarned: parts[0] || 0, totalRequired: parts[1] || 160 });
      return;
    }
    if (op.startsWith('module:')) {
      const index = Number(op.substring(7));
      if (index < 0 || index >= this.modules.length) return;
      const parts = text.split('/').map((item) => Number(item.trim()));
      const store = ProfileStore.instance;
      if (!store) return;
      const list = this.modules.slice();
      list[index].earned = parts[0] || 0;
      list[index].required = parts[1] || 0;
      store.saveProgressModules(list);
      return;
    }
    this.toast = '未知操作';
  }

  build() {
    Column() {
      Row() {
        Text('工具').fontSize(20).fontWeight(FontWeight.Bold)
          .fontColor(this.palette().textPrimary)
        Blank()
      }
      .width('100%')
      .padding({ bottom: 10 })

      Row({ space: 6 }) {
        ForEach(this.tabs, (name: string, index: number) => {
          Text(name)
            .fontSize(13)
            .fontColor(this.tab === index ? this.palette().buttonText : this.palette().textSecondary)
            .padding({ left: 10, right: 10, top: 6, bottom: 6 })
            .backgroundColor(this.tab === index ? this.palette().buttonBg : this.palette().panelAlt)
            .borderRadius(14)
            .onClick(() => {
              this.tab = index;
            })
        }, (name: string, index: number) => `${name}-${index}`)
      }
      .width('100%')

      Scroll() {
        Column() {
          if (this.tab === 0) {
            this.gpaSection()
          } else if (this.tab === 1) {
            this.gradesSection()
          } else if (this.tab === 2) {
            this.examsSection()
          } else {
            this.progressSection()
          }
        }
        .width('100%')
        .padding({ top: 12, bottom: 24 })
      }
      .width('100%')
      .layoutWeight(1)

      if (this.toast.length > 0) {
        Text(this.toast)
          .fontSize(13)
          .fontColor(this.palette().buttonText)
          .backgroundColor(this.palette().buttonBg)
          .padding({ left: 14, right: 14, top: 8, bottom: 8 })
          .borderRadius(16)
          .margin({ bottom: 12 })
          .onClick(() => {
            this.toast = '';
          })
      }
    }
    .padding(14)
    .width('100%')
    .height('100%')
    .backgroundColor(this.palette().page)
  }

  // ---------------- 绩点计算器 ----------------

  @Builder
  gpaSection() {
    Column() {
      Row() {
        Text('GPA').fontSize(12).fontColor(this.palette().textSecondary)
        Blank()
        Text(this.summary.gpa).fontSize(30).fontWeight(FontWeight.Bold).fontColor(this.palette().textPrimary)
      }
      .width('100%')
      Row() {
        this.metric('加权平均', this.summary.weighted)
        this.metric('算术平均', this.summary.arithmetic)
        this.metric('总学分', this.summary.credits)
      }
      .width('100%')
      .margin({ top: 10 })

      Row() {
        Text(`已录入 ${this.gpaCourses.length} 门`).fontSize(12).fontColor(this.palette().textSecondary)
        Blank()
        Text('从成绩导入').fontSize(13).fontColor(this.palette().iconTint).onClick(() => this.importGradesToGpa())
        Text('清空').fontSize(13).fontColor(this.palette().iconTint).margin({ left: 12 }).onClick(() => this.clearGpa())
      }
      .width('100%')
      .margin({ top: 12 })

      ForEach(this.gpaCourses, (course: GpaCourse, index: number) => {
        Row() {
          Column() {
            Text(course.courseName).fontSize(14).fontWeight(FontWeight.Medium)
              .fontColor(this.palette().textPrimary)
            Text(`${course.credit} 学分 · ${course.gradeType === 'level' ? course.level : String(course.score)}`)
              .fontSize(11).fontColor(this.palette().textSecondary).margin({ top: 2 })
          }
          .alignItems(HorizontalAlign.Start)
          Blank()
          Text(course.included === false ? '已排除' : `GPA ${gpaOfScore(scoreOfGrade(course)).toFixed(1)}`)
            .fontSize(12)
            .fontColor(course.included === false ? this.palette().textSecondary : this.palette().iconTint)
            .onClick(() => this.toggleGpaIncluded(index))
        }
        .width('100%')
        .padding(12)
        .backgroundColor(this.palette().panelAlt)
        .borderRadius(12)
        .margin({ bottom: 8 })
      }, (course: GpaCourse, index: number) => `${course.courseName}-${index}`)

      Button('添加绩点课程')
        .width('100%').margin({ top: 4 })
        .backgroundColor(this.palette().panelAlt)
        .fontColor(this.palette().textPrimary)
        .onClick(() => this.promptAddGpa())
    }
    .width('100%')
  }

  @Builder
  metric(label: string, value: string) {
    Column() {
      Text(label).fontSize(11).fontColor(this.palette().textSecondary)
      Text(value).fontSize(18).fontWeight(FontWeight.Bold).fontColor(this.palette().textPrimary).margin({ top: 2 })
    }
    .layoutWeight(1)
    .padding({ top: 10, bottom: 10 })
    .backgroundColor(this.palette().panelAlt)
    .borderRadius(12)
  }

  // ---------------- 成绩查询 ----------------

  @Builder
  gradesSection() {
    Column() {
      Row() {
        Text(`共 ${this.gradeGroups.length} 个学期`).fontSize(12).fontColor(this.palette().textSecondary)
        Blank()
        Text('添加成绩').fontSize(13).fontColor(this.palette().iconTint).onClick(() => this.promptAddGrade())
        Text('转入绩点').fontSize(13).fontColor(this.palette().iconTint).margin({ left: 12 }).onClick(() => this.importGradesToGpa())
      }
      .width('100%')
      .margin({ bottom: 8 })

      ForEach(this.gradeGroups, (group: { name: string; items: GradeEntry[]; credits: number; average: number; count: number }, index: number) => {
        Column() {
          Row() {
            Text(group.name).fontSize(14).fontWeight(FontWeight.Bold).fontColor(this.palette().textPrimary)
            Blank()
            Text(`${group.count} 门 · ${group.credits} 学分 · 均分 ${group.average.toFixed(1)}`)
              .fontSize(11).fontColor(this.palette().textSecondary)
          }
          .width('100%')
          ForEach(group.items, (grade: GradeEntry, itemIndex: number) => {
            Row() {
              Column() {
                Text(grade.courseName).fontSize(13).fontColor(this.palette().textPrimary)
                Text(`${grade.credit} 学分`).fontSize(11).fontColor(this.palette().textSecondary).margin({ top: 2 })
              }
              .alignItems(HorizontalAlign.Start)
              Blank()
              Text(String(grade.score)).fontSize(14).fontWeight(FontWeight.Bold).fontColor(this.palette().iconTint)
              Text('删除').fontSize(12).fontColor(this.palette().textSecondary).margin({ left: 12 })
                .onClick(() => this.deleteGrade(group.name, grade.courseName))
            }
            .width('100%')
            .padding({ top: 6, bottom: 6 })
          }, (grade: GradeEntry, itemIndex: number) => `${grade.courseName}-${itemIndex}`)
        }
        .width('100%')
        .padding(12)
        .backgroundColor(this.palette().panelAlt)
        .borderRadius(12)
        .margin({ bottom: 8 })
      }, (group: { name: string }, index: number) => `${group.name}-${index}`)
    }
    .width('100%')
  }

  // ---------------- 考试安排 ----------------

  @Builder
  examsSection() {
    Column() {
      Text(this.countdown)
        .fontSize(15).fontWeight(FontWeight.Medium)
        .fontColor(this.palette().textPrimary)
        .width('100%')
        .padding(14)
        .backgroundColor(this.palette().panelAlt)
        .borderRadius(12)
        .margin({ bottom: 8 })

      Row() {
        Blank()
        Text('添加考试').fontSize(13).fontColor(this.palette().iconTint).onClick(() => this.promptAddExam())
      }
      .width('100%')
      .margin({ bottom: 8 })

      ForEach(this.exams, (exam: ExamEntry, index: number) => {
        Row() {
          Column() {
            Text(exam.courseName).fontSize(14).fontWeight(FontWeight.Medium)
              .fontColor(this.palette().textPrimary)
            Text(`${exam.time}${exam.location ? ' · ' + exam.location : ''}${exam.seat ? ' · ' + exam.seat : ''}`)
              .fontSize(11).fontColor(this.palette().textSecondary).margin({ top: 2 })
          }
          .alignItems(HorizontalAlign.Start)
          Blank()
          Text('删除').fontSize(12).fontColor(this.palette().textSecondary)
            .onClick(() => this.deleteExam(index))
        }
        .width('100%')
        .padding(12)
        .backgroundColor(this.palette().panelAlt)
        .borderRadius(12)
        .margin({ bottom: 8 })
      }, (exam: ExamEntry, index: number) => `${exam.courseName}-${index}`)
    }
    .width('100%')
  }

  // ---------------- 学业完成 ----------------

  @Builder
  progressSection() {
    Column() {
      Column() {
        Text('毕业学分')
          .fontSize(12).fontColor(this.palette().textSecondary)
        Text(`${this.progress.totalEarned} / ${this.progress.totalRequired}`)
          .fontSize(28).fontWeight(FontWeight.Bold).fontColor(this.palette().textPrimary).margin({ top: 4 })
        Text(`完成度 ${this.progress.totalRequired > 0 ? (this.progress.totalEarned / this.progress.totalRequired * 100).toFixed(0) : '0'}%`)
          .fontSize(12).fontColor(this.palette().textSecondary).margin({ top: 4 })
      }
      .width('100%')
      .padding(14)
      .backgroundColor(this.palette().panelAlt)
      .borderRadius(12)

      Row() {
        Text('编辑').fontSize(13).fontColor(this.palette().iconTint).onClick(() => this.promptEditProgress())
        Text('按成绩累计').fontSize(13).fontColor(this.palette().iconTint).margin({ left: 12 }).onClick(() => this.calculateFromGrades())
      }
      .width('100%')
      .margin({ top: 10, bottom: 8 })

      ForEach(this.modules, (module: ProgressModule, index: number) => {
        Row() {
          Column() {
            Text(module.name).fontSize(13).fontColor(this.palette().textPrimary)
            Text(`已修 ${module.earned} / 需修 ${module.required}`)
              .fontSize(11).fontColor(this.palette().textSecondary).margin({ top: 2 })
          }
          .alignItems(HorizontalAlign.Start)
          Blank()
          Text('编辑').fontSize(12).fontColor(this.palette().iconTint).onClick(() => this.promptEditModule(index))
        }
        .width('100%')
        .padding(12)
        .backgroundColor(this.palette().panelAlt)
        .borderRadius(12)
        .margin({ bottom: 8 })
      }, (module: ProgressModule, index: number) => `${module.name}-${index}`)
    }
    .width('100%')
  }
}
