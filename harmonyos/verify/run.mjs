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

for (const [status, name] of results) {
  console.log(status.padEnd(4), name);
}
console.log(results.every(r => r[0] === 'PASS') ? 'ALL PASS (' + results.length + ')' : 'HAS FAILURES');
`;

writeFileSync(testFile, testSource);

console.log('Running HarmonyOS core logic verification with Node type stripping...\n');
execFileSync(process.execPath, ['--experimental-strip-types', testFile], { stdio: 'inherit' });
