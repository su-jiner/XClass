/**
 * 小程序核心逻辑回归：Node 直接跑 utils/*.js（与鸿蒙 verify/run.mjs 同思路）。
 * 运行：node tools/miniprogram-verify/run.mjs
 */
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const here = dirname(fileURLToPath(import.meta.url));
const require = createRequire(import.meta.url);
const core = require(join(here, '..', '..', 'miniprogram', 'utils', 'core.js'));

const results = [];
function test(name, fn) {
  try {
    fn();
    results.push(['PASS', name]);
  } catch (error) {
    results.push(['FAIL', `${name} :: ${error.message}`]);
    process.exitCode = 1;
  }
}

// ---------------- 单元格语法（你 PDF 里的真实写法） ----------------
const realCells = [
  ['体育（一）/(1-2节)7-19周/ 体育场1/殷乐', '体育（一）', '殷乐', null],
  ['信息技术/(1-2节)12-14周(双),15-19周/（乌）E406/刘文杰', '信息技术', '刘文杰', '乌'],
  ['思想道德与法治/(1-2节)7-17周/（乌）报告厅506/张娜(小)', '思想道德与法治', '张娜', '乌'],
  ['计算机网路技术/(1-2节)7-14周,16-19周/（乌）多媒体202/张晓锋', '计算机网路技术', '张晓锋', '乌'],
  ['操作系统管理/(5-6节)8-19周/（乌）E504/李健波', '操作系统管理', '李健波', '乌'],
  ['程序设计基础（python）/(5-6节)7-14周/（乌）E504/张铮', '程序设计基础（python）', '张铮', '乌'],
  ['信息技术/(5-6节)19周/（乌）E406/刘文杰', '信息技术', '刘文杰', '乌'],
  ['创新创业教育/(5-6节)10-13周/（乌）多媒体207/刘魏', '创新创业教育', '刘魏', '乌'],
  ['新时代大学生心理健康/(5-6节)14-15周/（乌）多媒体201/靳云华', '新时代大学生心理健康', '靳云华', '乌'],
  ['形势与政策5-1/(5-6节)16-17周/（乌）多媒体202/张娜 (小)', '形势与政策5-1', '张娜', '乌'],
  ['英语（一）/(5-6节)6-7周,9-17周,19周/（乌）多媒体202/章斌', '英语（一）', '章斌', '乌'],
];

test('parses all 11 real cell formats', () => {
  realCells.forEach(([raw, name, teacher, campus]) => {
    const entries = core.parseCellEntries(raw);
    assert.equal(entries.length, 1, `应解析出一门课: ${raw}`);
    assert.equal(entries[0].courseName, name);
    assert.equal(entries[0].teacher, teacher);
    assert.equal(entries[0].campusAlias, campus);
  });
});

test('extracts sections, campus and week rules', () => {
  const entry = core.parseCellEntries('信息技术/(1-2节)12-14周(双),15-19周/（乌）E406/刘文杰')[0];
  assert.equal(entry.location, 'E406');
  assert.equal(entry.weekRules.length, 2);
  assert.equal(entry.weekRules[0].parity, 'EVEN');
  assert.equal(entry.weekRules[0].startWeek, 12);
  const sections = core.sectionsOf('信息技术/(1-2节)12-14周(双),15-19周/（乌）E406/刘文杰');
  assert.deepEqual(sections, { startSection: 1, endSection: 2 });
});

test('splits multiple courses inside one cell', () => {
  const cell = '程序设计基础（python）/(5-6节)7-14周/（乌）E504/张铮\n信息技术/(5-6节)19周/（乌）E406/刘文杰';
  const entries = core.parseCellEntries(cell);
  assert.equal(entries.length, 2);
  assert.equal(entries[1].courseName, '信息技术');
});

test('week rule parser keeps ranges and parity', () => {
  const rules = core.parseWeekRules('(5-6节)6-7周,9-17周,19周');
  assert.deepEqual(rules.map((r) => [r.startWeek, r.endWeek]), [[6, 7], [9, 17], [19, 19]]);
  const odd = core.parseWeekRules('第1,3,5周（单）');
  assert.deepEqual(odd.map((r) => r.startWeek), [1, 3, 5]);
  assert.ok(odd.every((r) => r.parity === 'ODD'));
});

test('section row labels map to blocks', () => {
  assert.deepEqual(core.sectionRangeFromLabel('一'), { startSection: 1, endSection: 2 });
  assert.deepEqual(core.sectionRangeFromLabel('二'), { startSection: 3, endSection: 4 });
  assert.deepEqual(core.sectionRangeFromLabel('9-12节'), { startSection: 9, endSection: 12 });
  assert.deepEqual(core.sectionRangeFromLabel('34节'), { startSection: 3, endSection: 4 });
});

test('reads header/footer metadata', () => {
  const text = '2026-2027年第1学期 云计算261课表\n其他课程：军训技能张爽(共19周)/1-19周； 入学教育张爽(共19周)/1-19周；\n'
    + '本学期2026-08-31正式上课至2027-01-22结束，共21周.';
  const meta = core.parsePageMetadata(text);
  assert.equal(meta.semester, '2026-2027年第1学期');
  assert.equal(meta.totalWeeks, 21);
  assert.equal(meta.startYear, 2026);
  assert.equal(meta.startMonth, 8);
  assert.equal(meta.startDay, 31);
  assert.equal(meta.otherCourseLines.length, 2);
});

// ---------------- 多校区时间表（第二张图） ----------------
test('parses per-building section time tables', () => {
  const text = [
    '真理道校区上课时间',
    '节次 | 教学楼、体育场 | 实训楼',
    '12节 | 8:30-10:05 | 8:30-10:05',
    '34节 | 10:25-12:00 | 10:10-11:30',
    '56节 | 13:30-15:05 | 13:30-15:05',
    '78节 | 15:20-16:55 | 15:20-16:55',
    '9-12节 | 18:00-20:30 | 18:00-20:30',
  ].join('\n');
  const profiles = core.parseTimeTables(text);
  assert.equal(profiles.length, 2);
  const building = profiles.find((p) => p.name.indexOf('教学楼') >= 0);
  const training = profiles.find((p) => p.name.indexOf('实训楼') >= 0);
  assert.deepEqual(core.timeRangeOf(building, 3, 4), { startTime: '10:25', endTime: '12:00' });
  assert.deepEqual(core.timeRangeOf(training, 3, 4), { startTime: '10:10', endTime: '11:30' });
  assert.deepEqual(core.timeRangeOf(building, 9, 12), { startTime: '18:00', endTime: '20:30' });
});

test('parses shared table for two campuses', () => {
  const text = [
    '乌江路、泰兴路校区上课时间',
    '12节 | 8:30-10:05',
    '34节 | 10:20-11:55',
    '56节 | 13:30-15:05',
    '78节 | 15:20-16:55',
    '9-12节 | 18:00-20:30',
  ].join('\n');
  const profiles = core.parseTimeTables(text);
  assert.equal(profiles.length, 1);
  assert.deepEqual(core.timeRangeOf(profiles[0], 3, 4), { startTime: '10:20', endTime: '11:55' });
});

// ---------------- 表格 / 列表导入 ----------------
test('imports delimited table with weekday header', () => {
  const csv = [
    '节次,星期一,星期二,星期三,星期四,星期五',
    '一,体育（一）/(1-2节)7-19周/体育场1/殷乐,,"信息技术/(1-2节)12-14周(双),15-19周/（乌）E406/刘文杰",,',
  ].join('\n');
  const rows = core.splitDelimited(csv, ',');
  const parsed = core.parseCourseTable(csv, rows);
  assert.equal(parsed.courses.length, 2);
  const pe = parsed.courses.find((c) => c.courseName.indexOf('体育') >= 0);
  assert.equal(pe.meetings[0].weekday, 'MONDAY');
  const info = parsed.courses.find((c) => c.courseName === '信息技术');
  assert.equal(info.meetings[0].weekday, 'WEDNESDAY');
  assert.equal(info.meetings[0].campusAlias, '乌');
});

test('imports pasted list-style text', () => {
  const text = '星期一 第1-2节 高等数学/A101/张三\n星期三 第5-6节 大学物理/B202/李四';
  const parsed = core.parseCourseTable(text);
  assert.equal(parsed.courses.length, 2);
  assert.equal(parsed.courses[0].meetings[0].weekday, 'MONDAY');
  assert.equal(parsed.courses[0].meetings[0].startSection, 1);
});

// ---------------- 快照 / 校区时间 ----------------
const campusProfile = {
  id: 'mp-wu',
  name: '乌江路、泰兴路校区',
  blocks: [
    { startSection: 1, endSection: 2, startTime: '08:30', endTime: '10:05' },
    { startSection: 3, endSection: 4, startTime: '10:20', endTime: '11:55' },
  ],
};
const courses = [{
  courseName: '信息技术',
  teacher: '刘文杰',
  semester: '2026-2027年第1学期',
  credit: null,
  rawWeekText: '1-20周',
  meetings: [{
    weekday: 'MONDAY', startSection: 1, endSection: 2,
    weekRules: [{ startWeek: 1, endWeek: 20, parity: 'ALL' }],
    location: 'E406', campusId: 'mp-wu',
  }],
}];

test('uses campus time table when resolving times', () => {
  const snapshot = core.todaySnapshot(courses, '2026-08-31', [core.defaultTimeProfile(), campusProfile], '2026-08-31', 8 * 60 + 40);
  assert.equal(snapshot.week, 1);
  assert.equal(snapshot.items.length, 1);
  assert.equal(snapshot.items[0].startTime, '08:30');
  assert.equal(snapshot.items[0].endTime, '10:05');
  assert.equal(snapshot.items[0].campusName, '乌江路、泰兴路校区');
  assert.equal(snapshot.ongoingCourse.courseName, '信息技术');
  assert.equal(core.countdownText(15), '15 分钟后');
});

test('falls back to default table without campus', () => {
  const plain = JSON.parse(JSON.stringify(courses));
  plain[0].meetings[0].campusId = null;
  const snapshot = core.todaySnapshot(plain, '2026-08-31', [core.defaultTimeProfile()], '2026-08-31', 7 * 60);
  assert.equal(snapshot.items[0].startTime, '08:00');
  assert.equal(snapshot.items[0].campusName, '');
});

test('week grid places course into the right cell', () => {
  const grid = core.weekGrid(courses, 1);
  assert.equal(grid.cells[0][0], '信息技术');
  assert.equal(grid.cells[0][1], '');
});

test('next course falls back to a later weekday', () => {
  const next = core.nextCourse(courses, '2026-08-31', [core.defaultTimeProfile()], '2026-09-04', 12 * 60);
  assert.equal(next.courseName, '信息技术');
});

// ---------------- 备份 v2 往返 ----------------
test('backup round trip keeps campus and time profiles', () => {
  const profile = { name: '主课表', semesterStartDate: '2026-08-31', courses: courses };
  const json = core.encodeBackup([profile], [campusProfile], { 乌: 'mp-wu' }, 'mp-wu', '2026-10-10T00:00:00Z');
  const decoded = core.decodeBackup(json);
  assert.equal(decoded.profiles.length, 1);
  assert.equal(decoded.profiles[0].courses[0].meetings[0].campusId, 'mp-wu');
  assert.equal(decoded.timeProfiles.length, 1);
  assert.equal(decoded.campusMap['乌'], 'mp-wu');
  assert.equal(core.decodeBackup('not a json {'), null);
  assert.equal(core.decodeBackup('{"version":9,"profiles":[]}'), null);
});

test('reads v1 backup without campus fields', () => {
  const v1 = JSON.stringify({
    version: 1,
    profiles: [{ name: '旧课表', courses: [{ courseName: '高数', meetings: [{ weekday: 'MONDAY', startSection: 1, endSection: 2, weekRules: [{ startWeek: 1, endWeek: 16, parity: 'ALL' }], location: 'A101' }] }] }],
  });
  const decoded = core.decodeBackup(v1);
  assert.equal(decoded.profiles[0].courses[0].meetings[0].campusId, null);
  assert.equal(decoded.timeProfiles.length, 0);
});

// ---------------- 图片识别噪声过滤（页脚/表头不得成为课程） ----------------
test('filters footer noise rows from grid', () => {
  const csv = [
    '节次,星期一,星期二,星期三,星期四,星期五',
    '一,体育（一）/(1-2节)7-19周/体育场1/殷乐,,,,',
    '注一内容顺序为：课程◇周次◇地点◇教师 本学期2026-08-31正式上课至2027-01-22结束，共21周.,,,,',
    '其他课程：军训技能张爽(共19周)/1-19周； 入学教育张爽(共19周)/1-19周；,,,',
    '2026-2027年第1学期 云计算261课表,,,,',
    '打印时间：2026-10-09,,,,',
  ].join('\n');
  const rows = core.splitDelimited(csv, ',');
  const parsed = core.parseCourseTable(csv, rows);
  assert.equal(parsed.courses.length, 1, '噪声行不得成为课程');
  assert.equal(parsed.courses[0].courseName, '体育（一）');
  assert.ok(parsed.warnings.some((w) => w.indexOf('表外课程') >= 0), '表外课程应以 warning 提示');
});

test('filters noise course names inside cells', () => {
  const entries = core.parseCellEntries('注一内容顺序为：课程◇周次◇地点◇教师\n打印时间：2026-10-09\n体育（一）/(1-2节)7-19周/体育场1/殷乐');
  assert.equal(entries.length, 1);
  assert.equal(entries[0].courseName, '体育（一）');
});

test('keeps real courses in list mode with weekday prefix', () => {
  const parsed = core.parseCourseTable('星期一 体育（一）/(1-2节)7-19周/体育场1/殷乐\n星期二 信息技术/(1-2节)12-14周(双),15-19周/（乌）E406/刘文杰');
  assert.equal(parsed.courses.length, 2);
});

test('normalizes share placeholders', () => {
  const entry = core.parseCellEntries('自习/(1-2节)1-16周/-/-')[0];
  assert.equal(entry.courseName, '自习');
  assert.equal(entry.location, '');
  assert.equal(entry.teacher, '');
});

// ---------------- XClass 分享文本 v1 导出/导入回路 ----------------
const shareCourses = [
  {
    courseName: '体育（一）', teacher: '殷乐', semester: '2026-2027年第1学期', credit: null, rawWeekText: '7-19周',
    meetings: [{ weekday: 'MONDAY', startSection: 1, endSection: 2, weekRules: [{ startWeek: 7, endWeek: 19, parity: 'ALL' }], location: '体育场1', campusId: null }],
  },
  {
    courseName: '信息技术', teacher: '刘文杰', semester: '2026-2027年第1学期', credit: null, rawWeekText: '12-14周(双),15-19周',
    meetings: [{ weekday: 'TUESDAY', startSection: 1, endSection: 2, weekRules: [{ startWeek: 12, endWeek: 14, parity: 'EVEN' }, { startWeek: 15, endWeek: 19, parity: 'ALL' }], location: 'E406', campusId: 'mp-wu' }],
  },
  {
    courseName: '自习', teacher: '', semester: '', credit: null, rawWeekText: '1-16周',
    meetings: [{ weekday: 'FRIDAY', startSection: 9, endSection: 10, weekRules: [{ startWeek: 1, endWeek: 16, parity: 'ALL' }], location: '', campusId: null }],
  },
];

test('encodes share text with header and sorted rows', () => {
  const text = core.encodeShareText(shareCourses, '默认课表', '2026-2027年第1学期', '2026-08-31', 21, { 'mp-wu': '乌' });
  assert.ok(text.startsWith('# XClass 课表分享 v1'));
  assert.ok(text.indexOf('# 课表：默认课表') >= 0);
  assert.ok(text.indexOf('# 学期开始：2026-08-31') >= 0);
  assert.ok(text.indexOf('星期一 体育（一）/(1-2节)7-19周/体育场1/殷乐') >= 0);
  assert.ok(text.indexOf('星期二 信息技术/(1-2节)12-14周(双),15-19周/（乌）E406/刘文杰') >= 0);
  assert.ok(text.indexOf('星期五 自习/(9-10节)1-16周/-/-') >= 0);
});

test('share text round-trips back into courses', () => {
  const text = core.encodeShareText(shareCourses, '默认课表', '2026-2027年第1学期', '2026-08-31', 21, { 'mp-wu': '乌' });
  const parsed = core.parseCourseTable(text);
  assert.equal(parsed.courses.length, 3);
  const pe = parsed.courses.find((c) => c.courseName === '体育（一）');
  assert.equal(pe.teacher, '殷乐');
  assert.equal(pe.meetings[0].startSection, 1);
  assert.equal(pe.meetings[0].endSection, 2);
  assert.equal(pe.meetings[0].weekRules[0].startWeek, 7);
  const info = parsed.courses.find((c) => c.courseName === '信息技术');
  assert.equal(info.meetings[0].campusAlias, '乌');
  assert.equal(info.meetings[0].weekRules[0].parity, 'EVEN');
  const study = parsed.courses.find((c) => c.courseName === '自习');
  assert.equal(study.teacher, '');
  assert.equal(study.meetings[0].location, '');
});

// ---------------- 课表级 notes 提取（备注 = 其他课程 + 名称（教师）周次行） ----------------
test('extracts schedule-level notes by new rules', () => {
  const text = [
    '2026-2027年第1学期 云计算261课表',
    '其他课程：军训技能张爽(共19周)/1-19周； 入学教育张爽(共19周)/1-19周；',
    '军事技能（张爽）1-19周',
    '入学教育（张爽）1-19周',
    '学期时间：2026-08-31至2027-01-22，共21周',
  ].join('\n');
  const meta = core.parsePageMetadata(text);
  assert.ok(meta.notes.includes('军事技能（张爽）1-19周'), '规则a：教师括号+周次范围');
  assert.ok(meta.notes.includes('入学教育（张爽）1-19周'), '规则a 第二条');
  // 备注 = 其他课程：汇总行进入备注
  assert.ok(meta.notes.some((n) => n.indexOf('其他课程') >= 0), '其他课程汇总行为备注内容');
  // 学期时间行不属于备注，不再收录
  assert.ok(!meta.notes.some((n) => n.indexOf('学期时间') >= 0), '学期时间行不得入 notes');
  assert.ok(!meta.notes.some((n) => n.indexOf('共21周') >= 0), '共N周行不得入 notes');
});

test('notes default to empty array without note lines', () => {
  const meta = core.parsePageMetadata('星期一 第1-2节 高等数学/A101/张三\n星期三 第5-6节 大学物理/B202/李四');
  assert.deepEqual(meta.notes, []);
  const parsed = core.parseCourseTable('星期一 体育（一）/(1-2节)7-19周/体育场1/殷乐');
  assert.deepEqual(parsed.notes, []);
});

test('deduplicates notes and caps at 10', () => {
  const lines = ['军事技能（张爽）1-19周', '军事技能（张爽）1-19周'];
  for (let i = 0; i < 12; i++) lines.push('公共事项' + i + '（张老师）' + (i + 1) + '-' + (i + 5) + '周');
  const meta = core.parsePageMetadata(lines.join('\n'));
  assert.equal(meta.notes.filter((n) => n.indexOf('军事技能') >= 0).length, 1, '按原样字符串去重');
  assert.equal(meta.notes.length, 10, '最多 10 条');
});

test('backup v2 carries notes and tolerates missing notes', () => {
  const profile = { name: '主课表', semesterStartDate: '2026-08-31', courses: courses, notes: ['军事技能（张爽）1-19周'] };
  const json = core.encodeBackup([profile], [], {}, null);
  const decoded = core.decodeBackup(json);
  assert.deepEqual(decoded.profiles[0].notes, ['军事技能（张爽）1-19周']);
  // 旧备份无 notes 字段：按空数组处理、不报错
  const oldShape = JSON.parse(json);
  oldShape.profiles.forEach((p) => delete p.notes);
  const decodedOld = core.decodeBackup(JSON.stringify(oldShape));
  assert.deepEqual(decodedOld.profiles[0].notes, []);
});

for (const [status, name] of results) {
  console.log(status.padEnd(4), name);
}
console.log(results.every((item) => item[0] === 'PASS') ? `ALL PASS (${results.length})` : 'HAS FAILURES');
