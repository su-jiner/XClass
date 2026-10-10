/**
 * 鸿蒙核心逻辑 Node 验证脚本。
 * 把 .ets 核心文件改写为 .ts 后用 Node 原生类型剥离运行断言，
 * 使解析器/周计算/策略/备份编解码在 Windows 上即可回归（无需 DevEco）。
 *
 * 运行：node verify/run.mjs
 */
import { execFileSync } from 'node:child_process';
import { mkdirSync, rmSync, readFileSync, writeFileSync, copyFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const projectRoot = join(here, '..');
const coreDir = join(projectRoot, 'entry', 'src', 'main', 'ets', 'core');
const tmpDir = join(here, '.verify_tmp');
const testFile = join(tmpDir, 'verify.test.ts');

rmSync(tmpDir, { recursive: true, force: true });
mkdirSync(tmpDir, { recursive: true });

for (const name of ['Core.ets']) {
  let source = readFileSync(join(coreDir, name), 'utf-8');
  source = source.replaceAll("from './Core.ets'", "from './Core.ts'");
  // 类型剥离模式下 interface 无运行时导出：把纯类型导入拆分为 import type
  const typeOnlyNames = new Set([
    'CourseSchedule', 'MeetingTime', 'WeekRule', 'CourseMeetingRef',
    'BackupFile', 'BackupProfile', 'BackupCourse', 'BackupMeeting', 'BackupWeekRule',
    'SectionBlock', 'SectionTimeProfile', 'BackupTimeProfile', 'ImportedCourse', 'ImportedMeeting',
    'CourseTableParseResult', 'ParsedCellEntry',
  ]);
  source = source.replace(/import \{([^}]*)\} from '\.\/Core\.ts';/gs, (whole, inner) => {
    const names = inner.split(',').map((s) => s.trim()).filter(Boolean);
    const typeImports = names.filter((n) => typeOnlyNames.has(n));
    const valueImports = names.filter((n) => !typeOnlyNames.has(n));
    const parts = [];
    if (typeImports.length > 0) parts.push(`import type { ${typeImports.join(', ')} } from './Core.ts';`);
    if (valueImports.length > 0) parts.push(`import { ${valueImports.join(', ')} } from './Core.ts';`);
    return parts.join('\n');
  });
  writeFileSync(join(tmpDir, name.replace('.ets', '.ts')), source);
}

const testSource = String.raw`
import assert from 'node:assert/strict';
import {
  SimpleDate, guessCurrentWeek, totalWeeks,
  displayMeetings, defaultForToday, normalizeToWeekStart, inferFromSemesterLabelOrNull,
  encodeBackup, decodeBackup, courseToBackup, backupToCourse,
  defaultTimeProfile, timeRangeOf, resolveProfile, sectionRangesOf,
  parseWeekRulesText, weekRulesToText, parseCellEntries, sectionsOfText,
  sectionRangeFromLabel, weekdayIndexOfText, parsePageMetadata, parseTimeTablesText,
  parseCourseTableText, sectionTimeText, decodeBackupEnvelope,
  isNoiseLine, isNoiseCourseName, normalizePlaceholder, encodeShareText,
  gpaOfScore, scoreOfGrade, gpaSummary, groupGradesBySemester, examCountdownText, earnedCreditsFromGrades,
} from './Core.ts';

const results = [];
function test(name, fn) {
  try { fn(); results.push(['PASS', name]); }
  catch (error) { results.push(['FAIL', name + ' :: ' + error.message]); process.exitCode = 1; }
}

// ---- 周计算 ----
const course = (rules, weekday = 1) => ({
  courseName: '测试课程', teacher: '老师', semester: '2026-2027-1',
  credit: null, rawWeekText: 'custom',
  meetings: [{ weekday, startSection: 1, endSection: 2, weekRules: rules, location: '教室' }],
});

test('guess current week semantics', () => {
  const start = new SimpleDate(2026, 8, 31);
  assert.equal(guessCurrentWeek(start, start, 20), 1);
  assert.equal(guessCurrentWeek(start, start.adding(6), 20), 1);
  assert.equal(guessCurrentWeek(start, start.adding(7), 20), 2);
  assert.equal(guessCurrentWeek(start, start.adding(-30), 20), 1);
  assert.equal(guessCurrentWeek(start, start.adding(365), 20), 20);
});

test('total weeks', () => {
  assert.equal(totalWeeks([course([{ startWeek: 1, endWeek: 16, parity: 'ALL' }])]), 16);
  assert.equal(totalWeeks([]), 20);
});

test('parity rules', () => {
  const courses = [course([{ startWeek: 1, endWeek: 16, parity: 'ODD' }])];
  assert.equal(displayMeetings(courses, 1, false).filter(i => i.isCurrentWeek).length, 1);
  assert.equal(displayMeetings(courses, 2, false).filter(i => i.isCurrentWeek).length, 0);
  assert.equal(displayMeetings(courses, 3, false).filter(i => i.isCurrentWeek).length, 1);
});

test('past weeks show ended courses', () => {
  const courses = [course([{ startWeek: 1, endWeek: 5, parity: 'ODD' }])];
  const display = displayMeetings(courses, 4, true);
  assert.equal(display.length, 1);
  assert.equal(display[0].isCurrentWeek, false);
  assert.equal(display[0].nextActiveWeek, 5);
});

test('hides non current when disabled', () => {
  const courses = [course([{ startWeek: 6, endWeek: 10, parity: 'ALL' }])];
  assert.equal(displayMeetings(courses, 2, false).length, 0);
  assert.equal(displayMeetings(courses, 2, true).length, 1);
});

// ---- 学期日期 ----
test('semester label inference', () => {
  assert.equal(inferFromSemesterLabelOrNull('2026-2027学年第一学期').isoString(), '2026-08-31');
  assert.equal(inferFromSemesterLabelOrNull('2026-2027学年第二学期').isoString(), '2027-02-22');
  assert.equal(inferFromSemesterLabelOrNull('2026-2027学年秋季').isoString(), '2026-08-31');
  assert.equal(inferFromSemesterLabelOrNull('未知学期'), null);
});

test('normalize to week start', () => {
  assert.equal(normalizeToWeekStart(new SimpleDate(2026, 9, 2)).isoString(), '2026-08-31');
  assert.equal(normalizeToWeekStart(new SimpleDate(2026, 8, 31)).isoString(), '2026-08-31');
});

test('default for today branches', () => {
  assert.equal(defaultForToday(new SimpleDate(2026, 8, 15)).isoString(), '2026-08-31');
  assert.equal(defaultForToday(new SimpleDate(2026, 3, 15)).isoString(), '2026-02-23');
  assert.equal(defaultForToday(new SimpleDate(2027, 1, 10)).isoString(), '2027-02-22');
});

// ---- 备份编解码 ----
test('backup round trip', () => {
  const profiles = [{
    name: '主课表', semesterStartDate: '2026-08-31',
    courses: [courseToBackup(course([{ startWeek: 1, endWeek: 16, parity: 'ODD' }]))],
  }];
  const decoded = decodeBackup(encodeBackup('2026-09-05T00:00:00', profiles));
  assert.ok(decoded !== null);
  assert.equal(decoded.length, 1);
  assert.equal(decoded[0].courses[0].courseName, '测试课程');
  assert.equal(decoded[0].courses[0].meetings[0].weekRules[0].parity, 'ODD');
  const restored = backupToCourse(decoded[0].courses[0]);
  assert.equal(restored.meetings[0].weekday, 1);
  assert.equal(decoded[0].semesterStartDate, '2026-08-31');
  assert.equal(decodeBackup('not a json {'), null);
});

// ---- 单元格语法（真实教务课表写法） ----
test('parses real cell formats with campus alias', () => {
  const cases = [
    ['体育（一）/(1-2节)7-19周/ 体育场1/殷乐', '体育（一）', '殷乐', null],
    ['信息技术/(1-2节)12-14周(双),15-19周/（乌）E406/刘文杰', '信息技术', '刘文杰', '乌'],
    ['思想道德与法治/(1-2节)7-17周/（乌）报告厅506/张娜(小)', '思想道德与法治', '张娜', '乌'],
    ['操作系统管理/(5-6节)8-19周/（乌）E504/李健波', '操作系统管理', '李健波', '乌'],
    ['英语（一）/(5-6节)6-7周,9-17周,19周/（乌）多媒体202/章斌', '英语（一）', '章斌', '乌'],
  ];
  cases.forEach(([raw, name, teacher, campus]) => {
    const entries = parseCellEntries(raw);
    assert.equal(entries.length, 1);
    assert.equal(entries[0].courseName, name);
    assert.equal(entries[0].teacher, teacher);
    assert.equal(entries[0].campusAlias, campus);
  });
});

test('splits multiple courses in one cell and parses sections', () => {
  const entries = parseCellEntries('程序设计基础（python）/(5-6节)7-14周/（乌）E504/张铮\n信息技术/(5-6节)19周/（乌）E406/刘文杰');
  assert.equal(entries.length, 2);
  assert.deepEqual(sectionsOfText('信息技术/(5-6节)19周/（乌）E406/刘文杰'), [5, 6]);
  assert.deepEqual(sectionRangeFromLabel('34节'), [3, 4]);
  assert.deepEqual(sectionRangeFromLabel('9-12节'), [9, 12]);
  assert.equal(weekdayIndexOfText('星期三'), 3);
});

test('week rule text parser keeps ranges and parity', () => {
  const rules = parseWeekRulesText('(5-6节)6-7周,9-17周,19周');
  assert.deepEqual(rules.map((r) => [r.startWeek, r.endWeek]), [[6, 7], [9, 17], [19, 19]]);
  assert.equal(weekRulesToText(rules), '6-7周,9-17周,19周');
  const odd = parseWeekRulesText('第1,3,5周（单）');
  assert.ok(odd.every((r) => r.parity === 'ODD'));
});

test('parses page metadata', () => {
  const meta = parsePageMetadata('2026-2027年第1学期 云计算261课表\n本学期2026-08-31正式上课至2027-01-22结束，共21周.');
  assert.equal(meta.semester, '2026-2027年第1学期');
  assert.equal(meta.totalWeeks, 21);
  assert.equal(meta.startYear, 2026);
  assert.equal(meta.startMonth, 8);
  assert.equal(meta.startDay, 31);
});

// ---- 多校区节次时间表 ----
test('parses per-building time tables', () => {
  const text = [
    '真理道校区上课时间',
    '节次 | 教学楼、体育场 | 实训楼',
    '12节 | 8:30-10:05 | 8:30-10:05',
    '34节 | 10:25-12:00 | 10:10-11:30',
    '9-12节 | 18:00-20:30 | 18:00-20:30',
  ].join('\n');
  const profiles = parseTimeTablesText(text);
  assert.equal(profiles.length, 2);
  const training = profiles.find((p) => p.name.indexOf('实训楼') >= 0);
  const range = timeRangeOf(training, 3, 4);
  assert.equal(range.startTime, '10:10');
  assert.equal(range.endTime, '11:30');
  const long = timeRangeOf(training, 9, 12);
  assert.equal(long.startTime, '18:00');
  assert.equal(long.endTime, '20:30');
});

test('campus time overrides default when resolving meeting time', () => {
  const campusProfile = {
    id: 'hm-wu', name: '乌江路、泰兴路校区',
    blocks: [{ startSection: 1, endSection: 2, startTime: '08:30', endTime: '10:05' }],
  };
  const profiles = [defaultTimeProfile(), campusProfile];
  const meeting = { weekday: 1, startSection: 1, endSection: 2, weekRules: [], location: 'E406', campusId: 'hm-wu' };
  assert.equal(sectionTimeText(profiles, meeting), '08:30-10:05');
  const fallback = { weekday: 1, startSection: 1, endSection: 2, weekRules: [], location: '教室', campusId: null };
  assert.equal(sectionTimeText(profiles, fallback), '08:00-09:40');
  assert.equal(resolveProfile(profiles, 'hm-wu').name, '乌江路、泰兴路校区');
  assert.equal(sectionRangesOf(defaultTimeProfile()).length, 12);
});

test('imports pasted list-style course table', () => {
  const parsed = parseCourseTableText('星期一 第1-2节 高等数学/A101/张三\n星期三 第5-6节 大学物理/B202/李四');
  assert.equal(parsed.courses.length, 2);
  assert.equal(parsed.courses[0].meetings[0].weekday, 1);
  assert.equal(parsed.courses[0].meetings[0].startSection, 1);
});

test('backup v2 keeps campus and time profiles', () => {
  const profile = { id: 'hm-wu', name: '乌江路、泰兴路校区', blocks: [{ startSection: 1, endSection: 2, startTime: '08:30', endTime: '10:05' }] };
  const courses = [{
    courseName: '信息技术', teacher: '刘文杰', semester: '2026-2027-1', credit: null, rawWeekText: '1-20周',
    meetings: [{ weekday: '1', startSection: 1, endSection: 2, weekRules: [{ startWeek: 1, endWeek: 20, parity: 'ALL' }], location: 'E406', campusId: 'hm-wu' }],
  }];
  const json = encodeBackup('2026-10-10T00:00:00', [{ name: '主课表', semesterStartDate: '2026-08-31', courses }], [profile], { '乌': 'hm-wu' }, 'hm-wu');
  const envelope = decodeBackupEnvelope(json);
  assert.equal(envelope.version, 2);
  assert.equal(envelope.timeProfiles.length, 1);
  assert.equal(envelope.campusMap['乌'], 'hm-wu');
  const restored = backupToCourse(envelope.profiles[0].courses[0]);
  assert.equal(restored.meetings[0].campusId, 'hm-wu');
  const v1 = decodeBackup(JSON.stringify({ version: 1, exportedAt: '', profiles: [{ name: '旧', semesterStartDate: null, courses: [] }] }));
  assert.equal(v1.length, 1);
});

// ---- 图片识别噪声过滤（页脚/表头不得成为课程） ----
test('filters footer noise rows from grid', () => {
  const rows = [
    ['节次', '星期一', '星期二', '星期三', '星期四', '星期五'],
    ['一', '体育（一）/(1-2节)7-19周/体育场1/殷乐', '', '', '', ''],
    ['注一内容顺序为：课程◇周次◇地点◇教师 本学期2026-08-31正式上课至2027-01-22结束，共21周.', '', '', '', '', ''],
    ['其他课程：军训技能张爽(共19周)/1-19周； 入学教育张爽(共19周)/1-19周；', '', '', '', '', ''],
    ['2026-2027年第1学期 云计算261课表', '', '', '', '', ''],
    ['打印时间：2026-10-09', '', '', '', '', ''],
  ];
  const parsed = parseCourseTableText(rows.map(r => r.join(' ')).join('\n'), rows);
  assert.equal(parsed.courses.length, 1, '噪声行不得成为课程');
  assert.equal(parsed.courses[0].courseName, '体育（一）');
  assert.ok(parsed.warnings.some(w => w.indexOf('表外课程') >= 0), '表外课程应以 warning 提示');
});

test('filters noise course names inside cells', () => {
  const entries = parseCellEntries('注一内容顺序为：课程◇周次◇地点◇教师\n打印时间：2026-10-09\n体育（一）/(1-2节)7-19周/体育场1/殷乐');
  assert.equal(entries.length, 1);
  assert.equal(entries[0].courseName, '体育（一）');
});

test('keeps real courses in list mode with weekday prefix', () => {
  const parsed = parseCourseTableText('星期一 体育（一）/(1-2节)7-19周/体育场1/殷乐\n星期二 信息技术/(1-2节)12-14周(双),15-19周/（乌）E406/刘文杰');
  assert.equal(parsed.courses.length, 2);
});

test('normalizes share placeholders', () => {
  const entry = parseCellEntries('自习/(1-2节)1-16周/-/-')[0];
  assert.equal(entry.courseName, '自习');
  assert.equal(entry.location, '');
  assert.equal(entry.teacher, '');
});

// ---- XClass 分享文本 v1 导出/导入回路 ----
const shareCourses = [
  {
    courseName: '体育（一）', teacher: '殷乐', semester: '2026-2027年第1学期', credit: null, rawWeekText: '7-19周',
    meetings: [{ weekday: 1, startSection: 1, endSection: 2, weekRules: [{ startWeek: 7, endWeek: 19, parity: 'ALL' }], location: '体育场1', campusId: null }],
  },
  {
    courseName: '信息技术', teacher: '刘文杰', semester: '2026-2027年第1学期', credit: null, rawWeekText: '12-14周(双),15-19周',
    meetings: [{ weekday: 2, startSection: 1, endSection: 2, weekRules: [{ startWeek: 12, endWeek: 14, parity: 'EVEN' }, { startWeek: 15, endWeek: 19, parity: 'ALL' }], location: 'E406', campusId: 'hm-wu' }],
  },
  {
    courseName: '自习', teacher: '', semester: '', credit: null, rawWeekText: '1-16周',
    meetings: [{ weekday: 5, startSection: 9, endSection: 10, weekRules: [{ startWeek: 1, endWeek: 16, parity: 'ALL' }], location: '', campusId: null }],
  },
];

test('encodes share text with header and sorted rows', () => {
  const text = encodeShareText(shareCourses, '默认课表', '2026-2027年第1学期', '2026-08-31', 21, { 'hm-wu': '乌' });
  assert.ok(text.startsWith('# XClass 课表分享 v1'));
  assert.ok(text.indexOf('# 课表：默认课表') >= 0);
  assert.ok(text.indexOf('# 学期开始：2026-08-31') >= 0);
  assert.ok(text.indexOf('星期一 体育（一）/(1-2节)7-19周/体育场1/殷乐') >= 0);
  assert.ok(text.indexOf('星期二 信息技术/(1-2节)12-14周(双),15-19周/（乌）E406/刘文杰') >= 0);
  assert.ok(text.indexOf('星期五 自习/(9-10节)1-16周/-/-') >= 0);
});

test('share text round-trips back into courses', () => {
  const text = encodeShareText(shareCourses, '默认课表', '2026-2027年第1学期', '2026-08-31', 21, { 'hm-wu': '乌' });
  const parsed = parseCourseTableText(text);
  assert.equal(parsed.courses.length, 3);
  const pe = parsed.courses.find(c => c.courseName === '体育（一）');
  assert.equal(pe.teacher, '殷乐');
  assert.equal(pe.meetings[0].startSection, 1);
  assert.equal(pe.meetings[0].endSection, 2);
  assert.equal(pe.meetings[0].weekRules[0].startWeek, 7);
  const info = parsed.courses.find(c => c.courseName === '信息技术');
  assert.equal(info.meetings[0].campusAlias, '乌');
  assert.equal(info.meetings[0].weekRules[0].parity, 'EVEN');
  const study = parsed.courses.find(c => c.courseName === '自习');
  assert.equal(study.teacher, '');
  assert.equal(study.meetings[0].location, '');
});

// ---- 工具页逻辑（绩点/成绩/考试/学业） ----
const gpaCourses = [
  { courseName: '高等数学', credit: 4, gradeType: 'percent', score: 92, level: '', included: true },
  { courseName: '大学物理', credit: 3, gradeType: 'percent', score: 85, level: '', included: true },
  { courseName: '体育', credit: 1, gradeType: 'level', score: 0, level: '优秀', included: true },
  { courseName: '已排除课程', credit: 2, gradeType: 'percent', score: 100, level: '', included: false },
];

test('gpa band mapping matches Android', () => {
  assert.equal(gpaOfScore(90), 4.0);
  assert.equal(gpaOfScore(85), 3.7);
  assert.equal(gpaOfScore(64), 1.5);
  assert.equal(gpaOfScore(59), 0);
  assert.equal(scoreOfGrade({ courseName: 'x', credit: 1, gradeType: 'level', score: 0, level: '良好', included: true }), 85);
});

test('gpa summary excludes disabled courses', () => {
  const summary = gpaSummary(gpaCourses);
  assert.equal(summary.gpa, ((4.0 * 4 + 3.7 * 3 + 4.0 * 1) / 8).toFixed(2));
  assert.equal(summary.weighted, ((92 * 4 + 85 * 3 + 95 * 1) / 8).toFixed(2));
  assert.equal(summary.arithmetic, ((92 + 85 + 95) / 3).toFixed(2));
  assert.equal(summary.credits, '8.0');
});

test('grades group by semester with averages', () => {
  const grades = [
    { courseName: 'A', credit: 4, score: 90, semester: '2026-2027-1' },
    { courseName: 'B', credit: 3, score: 80, semester: '2026-2027-1' },
    { courseName: 'C', credit: 2, score: 70, semester: '' },
  ];
  const groups = groupGradesBySemester(grades);
  assert.equal(groups.length, 2);
  assert.equal(groups[0].name, '2026-2027-1');
  assert.equal(groups[0].average, (4 * 90 + 3 * 80) / 7);
  assert.equal(groups[1].name, '未标注学期');
  assert.equal(earnedCreditsFromGrades(grades), 9);
});

test('exam countdown picks nearest upcoming exam', () => {
  const now = Date.parse('2026-06-10T00:00:00');
  const exams = [
    { courseName: '旧考试', time: '2026-06-01 08:30', location: '', seat: '' },
    { courseName: '高等数学', time: '2026-06-20 08:30', location: '逸夫楼101', seat: '32号' },
    { courseName: '大学物理', time: '2026-07-01 08:30', location: '', seat: '' },
  ];
  const text = examCountdownText(exams, now);
  assert.ok(text.indexOf('高等数学') >= 0);
  assert.ok(text.indexOf('10 天') >= 0);
  assert.equal(examCountdownText([], now), '暂无考试安排');
  assert.equal(examCountdownText([{ courseName: '结束', time: '2026-01-01 08:30', location: '', seat: '' }], now), '考试均已结束');
});

for (const [status, name] of results) {
  console.log(status.padEnd(4), name);
}
console.log(results.every(r => r[0] === 'PASS') ? 'ALL PASS (' + results.length + ')' : 'HAS FAILURES');
`;

writeFileSync(testFile, testSource);

console.log('Running HarmonyOS core logic verification with Node type stripping...\n');
execFileSync(process.execPath, ['--experimental-strip-types', testFile], { stdio: 'inherit' });
