/**
 * 课表核心逻辑（与 Android cn.xiao.xyaax 的 model/parser/domain 逐一对应）。
 * 使用 TS 可剥离语法书写，便于在 Node 环境下运行单元验证（见 verify/ 目录）。
 */

// MARK: 常量（星期 1=周一 ... 7=周日；单双周为字符串常量）
export const WEEK_MONDAY = 1;
export const WEEK_SUNDAY = 7;
export const PARITY_ALL = 'ALL';
export const PARITY_ODD = 'ODD';
export const PARITY_EVEN = 'EVEN';
export const DEFAULT_TOTAL_WEEKS = 20;
export const MAX_WEEK = 30;

export interface WeekRule {
  startWeek: number;
  endWeek: number;
  parity: string;
}

export interface MeetingTime {
  weekday: number;
  startSection: number;
  endSection: number;
  weekRules: WeekRule[];
  location: string;
  /** 校区/楼栋时间表 ID；为空表示使用默认 12 节表 */
  campusId?: string | null;
}

export interface CourseSchedule {
  courseName: string;
  teacher: string;
  semester: string;
  credit: number | null;
  rawWeekText: string;
  meetings: MeetingTime[];
}

// MARK: 简单日期（语义对齐 java.time.LocalDate / Swift SimpleDate）

export class SimpleDate {
  readonly year: number;
  readonly month: number;
  readonly day: number;

  constructor(year: number, month: number, day: number) {
    this.year = year;
    this.month = month;
    this.day = day;
  }

  static today(): SimpleDate {
    const now = new Date();
    return new SimpleDate(now.getFullYear(), now.getMonth() + 1, now.getDate());
  }

  static parse(text: string): SimpleDate | null {
    const parts = text.split('-');
    if (parts.length !== 3) return null;
    const y = Number(parts[0]);
    const m = Number(parts[1]);
    const d = Number(parts[2]);
    if (!Number.isInteger(y) || !Number.isInteger(m) || !Number.isInteger(d)) return null;
    if (y <= 0 || m <= 0 || d <= 0) return null;
    return new SimpleDate(y, m, d);
  }

  private utc(): number {
    return Date.UTC(this.year, this.month - 1, this.day, 12, 0, 0);
  }

  /** 周一=1 ... 周日=7 */
  weekdayMonBased(): number {
    const wd = new Date(this.utc()).getUTCDay(); // 0=Sun..6=Sat
    return wd === 0 ? 7 : wd;
  }

  weekday(): number {
    return this.weekdayMonBased();
  }

  days(since: SimpleDate): number {
    return Math.round((this.utc() - since.utc()) / 86400000);
  }

  adding(days: number): SimpleDate {
    const d = new Date(this.utc() + days * 86400000);
    return new SimpleDate(d.getUTCFullYear(), d.getUTCMonth() + 1, d.getUTCDate());
  }

  previousOrSameMonday(): SimpleDate {
    return this.adding(-(this.weekdayMonBased() - 1));
  }

  isoString(): string {
    const mm = String(this.month).padStart(2, '0');
    const dd = String(this.day).padStart(2, '0');
    return `${this.year}-${mm}-${dd}`;
  }

  displayText(): string {
    return `${this.year}/${this.month}/${this.day}`;
  }

  equals(other: SimpleDate): boolean {
    return this.year === other.year && this.month === other.month && this.day === other.day;
  }
}

// MARK: 周次计算（对齐 WeekScheduleCalculator）

export function totalWeeks(of: CourseSchedule[]): number {
  let maxWeek: number | null = null;
  for (const course of of) {
    for (const meeting of course.meetings) {
      for (const rule of meeting.weekRules) {
        if (maxWeek === null || rule.endWeek > maxWeek) maxWeek = rule.endWeek;
      }
    }
  }
  return Math.max(maxWeek ?? DEFAULT_TOTAL_WEEKS, 1);
}

export function guessCurrentWeek(semesterStart: SimpleDate, today: SimpleDate, weeks: number): number {
  const days = today.days(semesterStart);
  const week = Math.floor(days / 7) + 1;
  return Math.min(Math.max(week, 1), Math.max(weeks, 1));
}

export function isMeetingActiveInWeek(meeting: MeetingTime, week: number): boolean {
  if (meeting.weekRules.length === 0) return true;
  return meeting.weekRules.some((rule) => {
    if (week < rule.startWeek || week > rule.endWeek) return false;
    if (rule.parity === PARITY_ODD) return week % 2 === 1;
    if (rule.parity === PARITY_EVEN) return week % 2 === 0;
    return true;
  });
}

export function nextActiveWeekOf(meeting: MeetingTime, fromWeek: number): number | null {
  if (meeting.weekRules.length === 0) return fromWeek;
  let maxEnd: number | null = null;
  for (const rule of meeting.weekRules) {
    if (maxEnd === null || rule.endWeek > maxEnd) maxEnd = rule.endWeek;
  }
  if (maxEnd === null || fromWeek > maxEnd) return null;
  for (let w = fromWeek; w <= maxEnd; w++) {
    if (isMeetingActiveInWeek(meeting, w)) return w;
  }
  return null;
}

export interface CourseMeetingRef {
  courseIndex: number;
  course: CourseSchedule;
  meeting: MeetingTime;
  isCurrentWeek: boolean;
  nextActiveWeek: number;
}

export function meetingsInWeek(courses: CourseSchedule[], week: number): CourseMeetingRef[] {
  const result: CourseMeetingRef[] = [];
  courses.forEach((course, index) => {
    for (const meeting of course.meetings) {
      if (isMeetingActiveInWeek(meeting, week)) {
        result.push({ courseIndex: index, course, meeting, isCurrentWeek: true, nextActiveWeek: week });
      }
    }
  });
  return result;
}

/** 周视图：非本周课程从被查看周向后寻找下一次上课周（历史周可见已结课课程） */
export function displayMeetings(courses: CourseSchedule[], week: number, showNonCurrent: boolean): CourseMeetingRef[] {
  const result: CourseMeetingRef[] = [];
  courses.forEach((course, index) => {
    for (const meeting of course.meetings) {
      if (isMeetingActiveInWeek(meeting, week)) {
        result.push({ courseIndex: index, course, meeting, isCurrentWeek: true, nextActiveWeek: week });
        continue;
      }
      if (!showNonCurrent) continue;
      const next = nextActiveWeekOf(meeting, week);
      if (next === null) continue;
      result.push({ courseIndex: index, course, meeting, isCurrentWeek: false, nextActiveWeek: next });
    }
  });
  return result;
}

/** 冲突课程去重（对齐 Android resolveOverlapForDay） */
export function resolveOverlaps(items: CourseMeetingRef[]): CourseMeetingRef[] {
  const occupied = new Array<boolean>(13).fill(false);
  const sorted = [...items].sort((a, b) => {
    if (a.isCurrentWeek !== b.isCurrentWeek) return a.isCurrentWeek ? -1 : 1;
    if (a.nextActiveWeek !== b.nextActiveWeek) return a.nextActiveWeek - b.nextActiveWeek;
    return a.meeting.startSection - b.meeting.startSection;
  });
  const selected: CourseMeetingRef[] = [];
  for (const item of sorted) {
    const start = Math.min(Math.max(item.meeting.startSection, 1), 12);
    const end = Math.min(Math.max(item.meeting.endSection, 1), 12);
    let overlap = false;
    for (let s = start; s <= end; s++) {
      if (occupied[s]) { overlap = true; break; }
    }
    if (overlap) continue;
    selected.push(item);
    for (let s = start; s <= end; s++) occupied[s] = true;
  }
  return selected.sort((a, b) => a.meeting.startSection - b.meeting.startSection);
}

// MARK: 学期开始日期推断（对齐 SemesterStartDatePolicy）

const codePattern = /(20\d{2})\D+(20\d{2})\D*([12])/;
const yearRangePattern = /(20\d{2})\D+(20\d{2})/;

export function normalizedSemesterKey(label: string): string | null {
  const normalized = label.trim();
  if (normalized.length === 0) return null;

  const codeMatch = codePattern.exec(normalized);
  if (codeMatch) {
    return `${codeMatch[1]}-${codeMatch[2]}-${codeMatch[3]}`;
  }
  const rangeMatch = yearRangePattern.exec(normalized);
  if (rangeMatch) {
    let term: string | null = null;
    if (normalized.includes('第一') || normalized.includes('第1') || normalized.includes('秋')) term = '1';
    else if (normalized.includes('第二') || normalized.includes('第2') || normalized.includes('春')) term = '2';
    if (term !== null) return `${rangeMatch[1]}-${rangeMatch[2]}-${term}`;
  }
  return null;
}

function mondayOnOrBefore(year: number, month: number, day: number): SimpleDate {
  return new SimpleDate(year, month, day).previousOrSameMonday();
}

export function inferFromSemesterLabelOrNull(label: string): SimpleDate | null {
  const key = normalizedSemesterKey(label);
  if (key === null) return null;
  const parts = key.split('-');
  if (parts.length !== 3) return null;
  const firstYear = Number(parts[0]);
  const secondYear = Number(parts[1]);
  const term = parts[2];
  if (term === '1') return mondayOnOrBefore(firstYear, 9, 1);
  if (term === '2') return mondayOnOrBefore(secondYear, 2, 24);
  return null;
}

export function inferFromCoursesOrNull(courses: CourseSchedule[]): SimpleDate | null {
  const counts = new Map<string, number>();
  for (const course of courses) {
    const label = course.semester.trim();
    if (label.length === 0) continue;
    counts.set(label, (counts.get(label) ?? 0) + 1);
  }
  let dominant: string | null = null;
  let best = 0;
  for (const label of counts.keys()) {
    const count = counts.get(label) ?? 0;
    if (count > best) {
      best = count;
      dominant = label;
    }
  }
  if (dominant === null) return null;
  return inferFromSemesterLabelOrNull(dominant);
}

export function inferFromCourses(courses: CourseSchedule[], fallback?: SimpleDate): SimpleDate {
  return inferFromCoursesOrNull(courses) ?? fallback ?? defaultForToday();
}

export function defaultForToday(today: SimpleDate = SimpleDate.today()): SimpleDate {
  if (today.month >= 8) {
    return mondayOnOrBefore(today.year, 9, 1);
  }
  return mondayOnOrBefore(today.year, 2, 24);
}

export function normalizeToWeekStart(date: SimpleDate): SimpleDate {
  return date.previousOrSameMonday();
}

// MARK: 节次时间表（多校区，对齐 Android SectionTimeProfile / TimeProfileStore）

export interface SectionBlock {
  startSection: number;
  endSection: number;
  startTime: string;
  endTime: string;
}

export interface SectionTimeProfile {
  id: string;
  name: string;
  blocks: SectionBlock[];
}

export const DEFAULT_PROFILE_ID = 'default';
export const DEFAULT_PROFILE_NAME = '默认（12 节）';

export function defaultTimeProfile(): SectionTimeProfile {
  const blocks: SectionBlock[] = SECTION_RANGES.map((range, index) => {
    const parts = range.split('-');
    return { startSection: index + 1, endSection: index + 1, startTime: parts[0], endTime: parts[1] };
  });
  return { id: DEFAULT_PROFILE_ID, name: DEFAULT_PROFILE_NAME, blocks };
}

/** 按小节取起止时间；跨段时用起始段开始 + 结束段结束 */
export function timeRangeOf(profile: SectionTimeProfile | null, startSection: number, endSection: number): SectionBlock | null {
  if (!profile || !profile.blocks || profile.blocks.length === 0) return null;
  let start: SectionBlock | null = null;
  let end: SectionBlock | null = null;
  for (const block of profile.blocks) {
    if (startSection >= block.startSection && startSection <= block.endSection) start = block;
    if (endSection >= block.startSection && endSection <= block.endSection) end = block;
  }
  if (!start) {
    let best = profile.blocks[0];
    for (const block of profile.blocks) {
      if (Math.abs(block.startSection - startSection) < Math.abs(best.startSection - startSection)) best = block;
    }
    start = best;
  }
  if (!end) end = start;
  return start && end ? { startSection: start.startSection, endSection: start.endSection, startTime: start.startTime, endTime: end.endTime } : null;
}

export function resolveProfile(profiles: SectionTimeProfile[] | null, campusId?: string | null): SectionTimeProfile {
  const list = profiles && profiles.length ? profiles : [defaultTimeProfile()];
  if (!campusId) {
    const fallback = list.find((p) => p.id === DEFAULT_PROFILE_ID);
    return fallback ?? list[0];
  }
  return list.find((p) => p.id === campusId)
    ?? list.find((p) => p.id === DEFAULT_PROFILE_ID)
    ?? list[0];
}

/** 课表左侧时间列：某套时间表下每个小节的起止 */
export function sectionRangesOf(profile: SectionTimeProfile, count: number = 12): string[] {
  const result: string[] = [];
  for (let section = 1; section <= count; section++) {
    const range = timeRangeOf(profile, section, section);
    result.push(range ? `${range.startTime}-${range.endTime}` : '--:--');
  }
  return result;
}

// MARK: 备份编解码（对齐 ScheduleBackupCodec，version 2）

export interface BackupWeekRule { startWeek: number; endWeek: number; parity: string; }
export interface BackupMeeting {
  weekday: string;
  startSection: number;
  endSection: number;
  weekRules: BackupWeekRule[];
  location: string;
  campusId?: string | null;
}
export interface BackupTimeProfile { id: string; name: string; blocks: SectionBlock[]; }
export interface BackupCourse {
  courseName: string;
  teacher: string;
  semester: string;
  credit: number | null;
  rawWeekText: string;
  meetings: BackupMeeting[];
}
export interface BackupProfile {
  name: string;
  semesterStartDate: string | null;
  courses: BackupCourse[];
}
export interface BackupFile {
  version: number;
  exportedAt: string;
  profiles: BackupProfile[];
  timeProfiles?: BackupTimeProfile[];
  campusMap?: Record<string, string>;
  defaultProfileId?: string | null;
}

export function encodeBackup(
  exportedAt: string,
  profiles: BackupProfile[],
  timeProfiles: BackupTimeProfile[] = [],
  campusMap: Record<string, string> = {},
  defaultProfileId: string | null = null
): string {
  const file: BackupFile = {
    version: 2,
    exportedAt,
    profiles,
    timeProfiles: timeProfiles.filter((p) => p.id !== DEFAULT_PROFILE_ID),
    campusMap,
    defaultProfileId,
  };
  return JSON.stringify(file, null, 2);
}

export function decodeBackup(content: string): BackupProfile[] | null {
  try {
    const file = JSON.parse(content) as BackupFile;
    if (typeof file.version !== 'number' || file.version < 1 || file.version > 2) return null;
    if (!Array.isArray(file.profiles)) return null;
    return file.profiles;
  } catch {
    return null;
  }
}

/** 解码完整信封（v2 含时间表与校区映射） */
export function decodeBackupEnvelope(content: string): BackupFile | null {
  try {
    const file = JSON.parse(content) as BackupFile;
    if (typeof file.version !== 'number' || file.version < 1 || file.version > 2) return null;
    if (!Array.isArray(file.profiles)) return null;
    return file;
  } catch {
    return null;
  }
}

export function backupToCourse(backup: BackupCourse): CourseSchedule {
  return {
    courseName: backup.courseName,
    teacher: backup.teacher,
    semester: backup.semester,
    credit: backup.credit,
    rawWeekText: backup.rawWeekText,
    meetings: backup.meetings.map((m) => ({
      weekday: Number(m.weekday) || 1,
      startSection: m.startSection,
      endSection: m.endSection,
      weekRules: (m.weekRules ?? []).map((r) => ({
        startWeek: r.startWeek, endWeek: r.endWeek, parity: r.parity,
      })),
      location: m.location,
      campusId: m.campusId ?? null,
    })),
  };
}

export function courseToBackup(course: CourseSchedule): BackupCourse {
  return {
    courseName: course.courseName,
    teacher: course.teacher,
    semester: course.semester,
    credit: course.credit,
    rawWeekText: course.rawWeekText,
    meetings: course.meetings.map((m) => ({
      weekday: String(m.weekday),
      startSection: m.startSection,
      endSection: m.endSection,
      weekRules: m.weekRules.map((r) => ({
        startWeek: r.startWeek, endWeek: r.endWeek, parity: r.parity,
      })),
      location: m.location,
      campusId: m.campusId ?? null,
    })),
  };
}

// MARK: 应用统一节次-时间对照（对齐 SectionTimes）

export const SECTION_RANGES: string[] = [
  '08:00-08:45', '08:45-09:40', '10:00-10:45', '10:45-11:40',
  '13:30-14:15', '14:15-15:10', '15:30-16:15', '16:15-17:10',
  '18:20-19:05', '19:05-19:50', '20:00-20:45', '20:45-21:30',
];

// MARK: 课表文本导入解析（对齐 Android importing/ 与小程序 utils/parse.js）

export interface ImportedMeeting {
  weekday: number;
  startSection: number;
  endSection: number;
  weekRules: WeekRule[];
  location: string;
  campusAlias: string | null;
}

export interface ImportedCourse {
  courseName: string;
  teacher: string;
  semester: string;
  credit: number | null;
  rawWeekText: string;
  meetings: ImportedMeeting[];
}

export interface CourseTableParseResult {
  courses: ImportedCourse[];
  semester: string | null;
  semesterStartDate: string | null;
  totalWeeks: number | null;
  timeTables: SectionTimeProfile[];
  campusAliases: string[];
  warnings: string[];
}

/** 周次文本 → WeekRule[]（7-19周 / 12-14周(双),15-19周 / 第1,3,5周（单）） */
export function parseWeekRulesText(raw: string, defaultTotalWeeks: number = DEFAULT_TOTAL_WEEKS): WeekRule[] {
  let text = raw.trim();
  if (!text) return [{ startWeek: 1, endWeek: defaultTotalWeeks, parity: PARITY_ALL }];
  text = text.replace(/[（(]?\s*第?\s*\d{1,2}\s*[-—–~～至]\s*\d{1,2}\s*节\s*[）)]?/g, ' ');
  const globalParity = text.indexOf('单') >= 0 ? PARITY_ODD : (text.indexOf('双') >= 0 ? PARITY_EVEN : PARITY_ALL);
  const rules: WeekRule[] = [];
  text.split(/[,，、;；]/).forEach((token0) => {
    const token = token0.trim();
    if (!token || !/\d/.test(token)) return;
    const parity = token.indexOf('单') >= 0 ? PARITY_ODD : (token.indexOf('双') >= 0 ? PARITY_EVEN : globalParity);
    const range = token.match(/(\d{1,2})\s*[-—–~～至]\s*(\d{1,2})/);
    if (range) {
      const start = Number(range[1]);
      const end = Number(range[2]);
      rules.push({ startWeek: Math.min(start, end), endWeek: Math.max(start, end), parity });
      return;
    }
    const singles = token.match(/\d{1,2}/g) ?? [];
    singles.forEach((value) => {
      const week = Number(value);
      if (week >= 1 && week <= MAX_WEEK) rules.push({ startWeek: week, endWeek: week, parity });
    });
  });
  if (!rules.length) return [{ startWeek: 1, endWeek: defaultTotalWeeks, parity: PARITY_ALL }];
  const seen: string[] = [];
  return rules
    .filter((rule) => {
      const key = `${rule.startWeek}-${rule.endWeek}-${rule.parity}`;
      if (seen.indexOf(key) >= 0) return false;
      seen.push(key);
      return true;
    })
    .sort((a, b) => a.startWeek - b.startWeek || a.endWeek - b.endWeek);
}

export function weekRulesToText(rules: WeekRule[]): string {
  return rules.map((rule) => {
    const base = rule.startWeek === rule.endWeek ? `${rule.startWeek}周` : `${rule.startWeek}-${rule.endWeek}周`;
    if (rule.parity === PARITY_ODD) return `${base}(单)`;
    if (rule.parity === PARITY_EVEN) return `${base}(双)`;
    return base;
  }).join(',');
}

const CAMPUS_RE = /^[（(]\s*([^）)]{1,4})\s*[）)]/;
const TEACHER_TAIL_RE = /[（(][^）)]{1,3}[）)]\s*$/;
const SECTION_RE = /[（(]?\s*(\d{1,2})\s*[-—–~～至]\s*(\d{1,2})\s*节?\s*[）)]?/;

export interface ParsedCellEntry {
  courseName: string;
  teacher: string;
  location: string;
  campusAlias: string | null;
  weekRules: WeekRule[];
  rawText: string;
}

/** 单元格：课程◇(节次)周次◇地点◇教师；一格多课按换行/分号拆 */
export function parseCellEntries(cellText: string, defaultTotalWeeks: number = DEFAULT_TOTAL_WEEKS): ParsedCellEntry[] {
  const lines: string[] = [];
  cellText.split(/\r?\n/).forEach((line) => line.split(/[；;]/).forEach((part) => lines.push(part.trim())));
  const entries: ParsedCellEntry[] = [];
  lines.forEach((line) => {
    if (line.length < 2) return;
    const parts = line.split(/[\/／⁄|]/).map((item) => item.trim()).filter((item) => item.length > 0);
    if (!parts.length) return;
    const sectionAndWeeks = parts.length > 1 ? parts[1] : '';
    const locationRaw = parts.length > 2 ? parts[2] : '';
    const teacherRaw = parts.length > 3 ? parts.slice(3).join('/') : '';
    const campusMatch = locationRaw.match(CAMPUS_RE);
    entries.push({
      courseName: parts[0],
      teacher: teacherRaw.replace(TEACHER_TAIL_RE, '').trim(),
      location: locationRaw.replace(CAMPUS_RE, '').trim() || locationRaw.trim(),
      campusAlias: campusMatch ? campusMatch[1].trim() : null,
      weekRules: parseWeekRulesText(sectionAndWeeks, defaultTotalWeeks),
      rawText: line,
    });
  });
  return entries;
}

export function sectionsOfText(text: string): number[] | null {
  const match = text.match(SECTION_RE);
  if (!match) return null;
  const start = Number(match[1]);
  const end = Number(match[2]);
  if (start < 1 || start > 12 || end < 1 || end > 12) return null;
  return [Math.min(start, end), Math.max(start, end)];
}

export function weekdayIndexOfText(text: string): number | null {
  const normalized = text.trim().toLowerCase().replace(/\s+/g, '');
  if (!normalized) return null;
  const aliases: string[][] = [
    ['星期一', '周一', '礼拜一', 'mon', 'monday'],
    ['星期二', '周二', '礼拜二', 'tue', 'tuesday'],
    ['星期三', '周三', '礼拜三', 'wed', 'wednesday'],
    ['星期四', '周四', '礼拜四', 'thu', 'thursday'],
    ['星期五', '周五', '礼拜五', 'fri', 'friday'],
    ['星期六', '周六', '礼拜六', 'sat', 'saturday'],
    ['星期日', '星期天', '周日', '周天', 'sun', 'sunday'],
  ];
  for (let i = 0; i < aliases.length; i++) {
    if (aliases[i].some((alias) => normalized.indexOf(alias) >= 0)) return i + 1;
  }
  return null;
}

const CHINESE_ROW_START: Record<string, number> = { '一': 1, '二': 3, '三': 5, '四': 7, '五': 9, '六': 11 };

export function sectionRangeFromLabel(text: string): number[] | null {
  const normalized = text.trim().replace(/\s+/g, '');
  if (!normalized) return null;
  const range = normalized.match(/(\d{1,2})\s*[-—–~～至]\s*(\d{1,2})/);
  if (range) {
    const start = Number(range[1]);
    const end = Number(range[2]);
    if (start >= 1 && start <= 12 && end >= 1 && end <= 12) return [Math.min(start, end), Math.max(start, end)];
  }
  const compact = normalized.match(/^(\d{1,2})节?$/);
  if (compact) {
    const digits = compact[1];
    if (digits.length === 2 && Number(digits[0]) < Number(digits[1])) return [Number(digits[0]), Number(digits[1])];
    const single = Number(digits);
    if (single >= 9) return [9, 12];
    return [single, Math.min(12, single + 1)];
  }
  const starts: number[] = [];
  Object.keys(CHINESE_ROW_START).forEach((key) => {
    if (normalized.indexOf(key) >= 0) starts.push(CHINESE_ROW_START[key]);
  });
  if (starts.length) {
    const start = Math.min(...starts);
    const end = starts.length === 1 ? start + 1 : Math.max(...starts) + 1;
    return [start, Math.min(12, end)];
  }
  const digits = normalized.match(/\d{1,2}/g);
  if (digits && digits.length >= 2) return [Math.min(Number(digits[0]), Number(digits[1])), Math.max(Number(digits[0]), Number(digits[1]))];
  return null;
}

/** 页眉页脚：学期、开学日期、总周数、其他课程 */
export function parsePageMetadata(text: string): Record<string, string | number | string[]> {
  const semesterMatch = text.match(/(\d{4})\s*-\s*(\d{4})\s*年\s*第?\s*([一二1-3])\s*学期/);
  const startMatch = text.match(/本学期\s*(\d{4})\s*[-/年]\s*(\d{1,2})\s*[-/月]\s*(\d{1,2})/)
    ?? text.match(/(\d{4})\s*年?\s*[-/年]\s*(\d{1,2})\s*[-/月]\s*(\d{1,2})/);
  const weekMatches = (text.match(/共\s*(\d{1,2})\s*周/g) ?? [])
    .map((item) => Number((item.match(/\d{1,2}/) ?? ['0'])[0]))
    .filter((value) => value >= 1 && value <= MAX_WEEK);
  const other = (text.match(/其他课程[：:]\s*(.+)/) ?? [])[1];
  const termMap: Record<string, string> = { '一': '第1学期', '1': '第1学期', '二': '第2学期', '2': '第2学期', '三': '第3学期', '3': '第3学期' };
  const semester = semesterMatch ? `${semesterMatch[1]}-${semesterMatch[2]}年${termMap[semesterMatch[3]] ?? ''}` : null;
  return {
    semester: semester ?? '',
    startYear: startMatch ? Number(startMatch[1]) : 0,
    startMonth: startMatch ? Number(startMatch[2]) : 0,
    startDay: startMatch ? Number(startMatch[3]) : 0,
    totalWeeks: weekMatches.length ? Math.max(...weekMatches) : 0,
    otherCourseLines: other ? other.split(/[；;]/).map((item) => item.trim()).filter((item) => item.length > 0) : [],
  };
}

const TIME_RE = /\d{1,2}\s*[:：]\s*\d{2}\s*[-—–~～至]\s*\d{1,2}\s*[:：]\s*\d{2}/g;
const SECTION_LABEL_RE = /^\s*(\d{1,2})\s*[-—–~～]?\s*(\d{1,2})?\s*节/;

function resolveSectionBlock(raw: string, explicitEnd: number | null): number[] {
  const digits = raw.trim();
  if (explicitEnd && explicitEnd >= 1 && explicitEnd <= 12) {
    const start = Number(digits) || 1;
    return start <= explicitEnd ? [start, explicitEnd] : [explicitEnd, start];
  }
  if (digits.length === 2) {
    const first = Number(digits[0]);
    const second = Number(digits[1]);
    if (first >= 1 && first <= 9 && second > first) return [first, second];
  }
  const single = Number(digits) || 1;
  return single >= 9 ? [9, 12] : [single, Math.min(12, single + 1)];
}

/** 「XX校区上课时间」分块表 → 多套节次时间表 */
export function parseTimeTablesText(text: string): SectionTimeProfile[] {
  const lines = text.split(/\r?\n/).map((line) => line.trim()).filter((line) => line.length > 0);
  const profiles: SectionTimeProfile[] = [];
  let group: string | null = null;
  let columns: string[] = [];
  let rows: Array<{ section: number[]; times: string[] }> = [];

  const flush = () => {
    if (group && rows.length) {
      if (columns.length <= 1) {
        const blocks: SectionBlock[] = [];
        rows.forEach((row) => {
          const time = row.times[0];
          if (!time) return;
          const parts = time.split(/\s*[-—–~～至]\s*/);
          if (parts.length >= 2) blocks.push({ startSection: row.section[0], endSection: row.section[1], startTime: parts[0], endTime: parts[1] });
        });
        if (blocks.length) profiles.push({ id: `hm-${group}`, name: group, blocks });
      } else {
        columns.forEach((columnName, index) => {
          const blocks: SectionBlock[] = [];
          rows.forEach((row) => {
            const time = row.times[index];
            if (!time) return;
            const parts = time.split(/\s*[-—–~～至]\s*/);
            if (parts.length >= 2) blocks.push({ startSection: row.section[0], endSection: row.section[1], startTime: parts[0], endTime: parts[1] });
          });
          if (blocks.length) profiles.push({ id: `hm-${group}-${columnName}`, name: `${group}·${columnName}`, blocks });
        });
      }
    }
    rows = [];
    columns = [];
  };

  lines.forEach((line) => {
    const header = line.match(/^(.{2,20}?校区).*上课时间/);
    if (header) {
      flush();
      group = header[1].trim();
      return;
    }
    if (!group) return;
    const times = (line.match(TIME_RE) ?? []).map((item) => item.replace(/：/g, ':'));
    const label = line.match(SECTION_LABEL_RE);
    if (label && times.length) {
      rows.push({ section: resolveSectionBlock(label[1], label[2] ? Number(label[2]) : null), times });
      return;
    }
    if (!times.length && /楼|场/.test(line)) {
      columns = line.split(/[\s|｜]+/).map((item) => item.trim()).filter((item) => item.length > 0 && item !== '节次');
    }
  });
  flush();
  return profiles;
}

/** 导入总入口：粘贴/文本文件内容（rows 为可选的 CSV 网格） */
export function parseCourseTableText(rawText: string, rows: string[][] | null = null): CourseTableParseResult {
  const metadata = parsePageMetadata(rawText);
  const total = (metadata.totalWeeks as number) > 0 ? (metadata.totalWeeks as number) : DEFAULT_TOTAL_WEEKS;
  const collected: ImportedCourse[] = [];
  const warnings: string[] = [];

  if (rows && rows.length) {
    let headerIndex = -1;
    for (let i = 0; i < rows.length; i++) {
      const count = rows[i].filter((cell) => weekdayIndexOfText(cell) !== null).length;
      if (count >= 4) { headerIndex = i; break; }
    }
    if (headerIndex >= 0) {
      const columns: Array<{ index: number; weekday: number }> = [];
      rows[headerIndex].forEach((cell, index) => {
        const weekday = weekdayIndexOfText(cell);
        if (weekday) columns.push({ index, weekday });
      });
      for (let rowIndex = headerIndex + 1; rowIndex < rows.length; rowIndex++) {
        const row = rows[rowIndex];
        const rowSection = sectionRangeFromLabel(row.length ? row[0] : '');
        columns.forEach((column) => {
          const cell = (row[column.index] ?? '').trim();
          if (!cell) return;
          const entries = parseCellEntries(cell, total);
          entries.forEach((entry) => {
            const sections = sectionsOfText(entry.rawText) ?? rowSection;
            if (!sections) { warnings.push(`「${entry.courseName}」缺少节次信息，已跳过`); return; }
            collected.push({
              courseName: entry.courseName,
              teacher: entry.teacher,
              semester: (metadata.semester as string) ?? '',
              credit: null,
              rawWeekText: weekRulesToText(entry.weekRules),
              meetings: [{
                weekday: column.weekday,
                startSection: sections[0],
                endSection: sections[1],
                weekRules: entry.weekRules,
                location: entry.location,
                campusAlias: entry.campusAlias,
              }],
            });
          });
        });
      }
    }
  } else {
    rawText.split(/\r?\n/).forEach((line) => {
      const weekday = weekdayIndexOfText(line);
      const sections = sectionRangeFromLabel(line);
      if (!weekday || !sections) return;
      const cleaned = line.replace(/星期[一二三四五六日天]|周[一二三四五六日天]/g, '');
      parseCellEntries(cleaned, total).forEach((entry) => {
        collected.push({
          courseName: entry.courseName,
          teacher: entry.teacher,
          semester: (metadata.semester as string) ?? '',
          credit: null,
          rawWeekText: weekRulesToText(entry.weekRules),
          meetings: [{
            weekday,
            startSection: sections[0],
            endSection: sections[1],
            weekRules: entry.weekRules,
            location: entry.location,
            campusAlias: entry.campusAlias,
          }],
        });
      });
    });
    if (!collected.length) warnings.push('未识别到课表结构，请确认内容或改用粘贴文本导入');
  }

  // 同名同教师合并
  const order: string[] = [];
  const grouped: Record<string, ImportedCourse[]> = {};
  collected.forEach((course) => {
    const key = `${course.courseName.trim()}|${course.teacher.trim()}`;
    if (!grouped[key]) { grouped[key] = []; order.push(key); }
    grouped[key].push(course);
  });
  const courses: ImportedCourse[] = order.map((key) => {
    const group = grouped[key];
    const meetings: ImportedMeeting[] = [];
    group.forEach((item) => item.meetings.forEach((meeting) => meetings.push(meeting)));
    return {
      courseName: group[0].courseName,
      teacher: group[0].teacher,
      semester: group[0].semester,
      credit: group[0].credit,
      rawWeekText: group[0].rawWeekText,
      meetings,
    };
  });

  const timeTables = parseTimeTablesText(rawText);
  const aliases: string[] = [];
  courses.forEach((course) => course.meetings.forEach((meeting) => {
    if (meeting.campusAlias && aliases.indexOf(meeting.campusAlias) < 0) aliases.push(meeting.campusAlias);
  }));

  const startYear = metadata.startYear as number;
  const startMonth = metadata.startMonth as number;
  const startDay = metadata.startDay as number;
  const pad = (value: number) => value < 10 ? `0${value}` : `${value}`;
  return {
    courses,
    semester: ((metadata.semester as string) ?? '') || null,
    semesterStartDate: startYear > 0 ? `${startYear}-${pad(startMonth || 9)}-${pad(startDay || 1)}` : null,
    totalWeeks: (metadata.totalWeeks as number) > 0 ? (metadata.totalWeeks as number) : null,
    timeTables,
    campusAliases: aliases,
    warnings,
  };
}

/** 今日/下一节课快照所需的最小信息（与 Android WidgetSnapshotBuilder 规则一致） */
export function sectionTimeText(profiles: SectionTimeProfile[], meeting: MeetingTime): string {
  const profile = resolveProfile(profiles, meeting.campusId ?? null);
  const range = timeRangeOf(profile, meeting.startSection, meeting.endSection)
    ?? timeRangeOf(resolveProfile(profiles, null), meeting.startSection, meeting.endSection);
  return range ? `${range.startTime}-${range.endTime}` : '--:--';
}
