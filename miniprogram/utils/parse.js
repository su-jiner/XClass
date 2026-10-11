/**
 * XClass 小程序解析层：与 Android/iOS/鸿蒙逐函数对齐（纯逻辑，无 wx API 依赖）。
 * 覆盖：周次解析、单元格语法、标签识别、页眉页脚元数据、节次时间表、表格/列表解析。
 */

const WEEKDAYS = ['MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY', 'SUNDAY'];
const PARITY = { ALL: 'ALL', ODD: 'ODD', EVEN: 'EVEN' };
const DEFAULT_TOTAL_WEEKS = 20;
const DEFAULT_PROFILE_ID = 'default';
const DEFAULT_PROFILE_NAME = '默认（12 节）';
const DEFAULT_RANGES = [
  '08:00-08:45', '08:45-09:40', '10:00-10:45', '10:45-11:40',
  '13:30-14:15', '14:15-15:10', '15:30-16:15', '16:15-17:10',
  '18:20-19:05', '19:05-19:50', '20:00-20:45', '20:45-21:30'
];

function defaultTimeProfile() {
  return {
    id: DEFAULT_PROFILE_ID,
    name: DEFAULT_PROFILE_NAME,
    blocks: DEFAULT_RANGES.map(function (range, index) {
      const parts = range.split('-');
      return { startSection: index + 1, endSection: index + 1, startTime: parts[0], endTime: parts[1] };
    })
  };
}

/** 周次文本 → WeekRule[]（支持 7-19周 / 12-14周(双),15-19周 / 第1,3,5周（单）/ 单周） */
function parseWeekRules(raw, defaultTotalWeeks) {
  const total = defaultTotalWeeks || DEFAULT_TOTAL_WEEKS;
  let text = String(raw || '').trim();
  if (!text) return [{ startWeek: 1, endWeek: total, parity: PARITY.ALL }];
  text = text.replace(/[（(]?\s*第?\s*\d{1,2}\s*[-—–~～至]\s*\d{1,2}\s*节\s*[）)]?/g, ' ');
  const globalParity = text.indexOf('单') >= 0 ? PARITY.ODD : (text.indexOf('双') >= 0 ? PARITY.EVEN : PARITY.ALL);
  const tokens = text.split(/[,，、;；]/).map(function (item) { return item.trim(); })
    .filter(function (item) { return item && /\d/.test(item); });
  const rules = [];
  tokens.forEach(function (token) {
    const parity = token.indexOf('单') >= 0 ? PARITY.ODD : (token.indexOf('双') >= 0 ? PARITY.EVEN : globalParity);
    const range = token.match(/(\d{1,2})\s*[-—–~～至]\s*(\d{1,2})/);
    if (range) {
      rules.push({
        startWeek: Math.min(Number(range[1]), Number(range[2])),
        endWeek: Math.max(Number(range[1]), Number(range[2])),
        parity: parity
      });
      return;
    }
    (token.match(/\d{1,2}/g) || []).forEach(function (value) {
      const week = Number(value);
      if (week >= 1 && week <= 30) rules.push({ startWeek: week, endWeek: week, parity: parity });
    });
  });
  if (!rules.length) return [{ startWeek: 1, endWeek: total, parity: PARITY.ALL }];
  const seen = {};
  return rules
    .filter(function (rule) {
      const key = rule.startWeek + '-' + rule.endWeek + '-' + rule.parity;
      if (seen[key]) return false;
      seen[key] = true;
      return true;
    })
    .sort(function (a, b) { return a.startWeek - b.startWeek || a.endWeek - b.endWeek; });
}

function weekRulesText(rules) {
  return (rules || []).map(function (rule) {
    const base = rule.startWeek === rule.endWeek ? rule.startWeek + '周' : rule.startWeek + '-' + rule.endWeek + '周';
    if (rule.parity === PARITY.ODD) return base + '(单)';
    if (rule.parity === PARITY.EVEN) return base + '(双)';
    return base;
  }).join(',');
}

const CAMPUS_RE = /^[（(]\s*([^）)]{1,4})\s*[）)]/;
const TEACHER_TAIL_RE = /[（(][^）)]{1,3}[）)]\s*$/;
const SECTION_RE = /[（(]?\s*(\d{1,2})\s*[-—–~～至]\s*(\d{1,2})\s*节?\s*[）)]?/;

/** 非课程噪声过滤：与 Android/iOS/鸿蒙一致。丢弃表头、行标签、页脚注释与「其他课程」汇总行。 */
const NOISE_LINE_PREFIX = /^(其他课程[：:]?|注[一二三四五]?[：:]?|说明[：:]?|备注[：:]?|打印时间|本学期|内容顺序|专业[：:]?|节次[：:]?|校区|上课时间|课程表?[：:]?|上午|下午|晚上|学期|\d{4}\s*[-—–~～至/]\s*\d{4}\s*年?)/;
const NOISE_CELL_PREFIX = /^(其他课程[：:]?|注[一二三四五]?[：:]?|说明[：:]?|备注[：:]?|打印时间|本学期|内容顺序|专业[：:]?|节次[：:]?|校区|上课时间|课程表?[：:]?|星期[一二三四五六日天]$|上午|下午|晚上|学期|\d{4}\s*[-—–~～至/]\s*\d{4}\s*年?)/;
const MAX_COURSE_NAME_LENGTH = 40;

/** 整行是否应被当作噪声跳过（不解析为课程）。 */
function isNoiseLine(text) {
  const trimmed = String(text || '').trim();
  if (!trimmed) return true;
  if (NOISE_LINE_PREFIX.test(trimmed)) return true;
  const hasCourseMark = trimmed.indexOf('/') >= 0 || trimmed.indexOf('节') >= 0 || trimmed.indexOf('周') >= 0;
  return !hasCourseMark;
}

/** 单元格中的课程名是否像噪声（丢弃）。 */
function isNoiseCourseName(name) {
  const trimmed = String(name || '').trim();
  if (!trimmed) return true;
  if (trimmed.length > MAX_COURSE_NAME_LENGTH) return true;
  if (NOISE_CELL_PREFIX.test(trimmed)) return true;
  return /^\d{1,2}\s*[:：]\s*\d{2}/.test(trimmed);
}

/** 分享文本占位符归一化：空字段用 "-" 占位，导入时还原为空。 */
function normalizePlaceholder(value) {
  const trimmed = String(value || '').trim();
  return trimmed === '-' || trimmed === '—' ? '' : trimmed;
}

/** 单元格 → 课程条目；一格多门课按换行/分号拆 */
function parseCellEntries(cellText, defaultTotalWeeks) {
  return String(cellText || '')
    .split(/[\n\r]+/)
    .reduce(function (acc, line) { return acc.concat(line.split(/[；;]/)); }, [])
    .map(function (line) { return line.trim(); })
    .filter(function (line) { return line.length >= 2; })
    .map(function (line) { return parseCellEntry(line, defaultTotalWeeks); })
    .filter(Boolean);
}

function parseCellEntry(raw, defaultTotalWeeks) {
  const parts = raw.split(/[\/／⁄|]/).map(function (item) { return item.trim(); })
    .filter(function (item) { return item.length > 0; });
  if (!parts.length) return null;
  const courseName = parts[0];
  if (!courseName) return null;
  // 噪声守卫：表头/页脚/说明文字不应成为课程
  if (isNoiseCourseName(courseName)) return null;
  const sectionAndWeeks = parts[1] || '';
  const locationRaw = parts[2] || '';
  const teacherRaw = parts.slice(3).join('/').trim();
  const campusMatch = locationRaw.match(CAMPUS_RE);
  return {
    courseName: courseName,
    teacher: normalizePlaceholder(teacherRaw.replace(TEACHER_TAIL_RE, '').trim()),
    location: normalizePlaceholder(locationRaw.replace(CAMPUS_RE, '').trim() || locationRaw.trim()),
    campusAlias: campusMatch ? campusMatch[1].trim() : null,
    weekRules: parseWeekRules(sectionAndWeeks, defaultTotalWeeks),
    rawText: raw
  };
}

function sectionsOf(text) {
  const match = String(text || '').match(SECTION_RE);
  if (!match) return null;
  const start = Number(match[1]);
  const end = Number(match[2]);
  if (start < 1 || start > 12 || end < 1 || end > 12) return null;
  return { startSection: Math.min(start, end), endSection: Math.max(start, end) };
}

const WEEKDAY_ALIASES = [
  ['星期一', '周一', '礼拜一', 'mon', 'monday'],
  ['星期二', '周二', '礼拜二', 'tue', 'tuesday'],
  ['星期三', '周三', '礼拜三', 'wed', 'wednesday'],
  ['星期四', '周四', '礼拜四', 'thu', 'thursday'],
  ['星期五', '周五', '礼拜五', 'fri', 'friday'],
  ['星期六', '周六', '礼拜六', 'sat', 'saturday'],
  ['星期日', '星期天', '周日', '周天', 'sun', 'sunday']
];

function weekdayIndex(text) {
  const normalized = String(text || '').trim().toLowerCase().replace(/\s+/g, '');
  if (!normalized) return null;
  for (let i = 0; i < WEEKDAY_ALIASES.length; i += 1) {
    if (WEEKDAY_ALIASES[i].some(function (alias) { return normalized.indexOf(alias) >= 0; })) return i + 1;
  }
  return null;
}

const CHINESE_ROWS = { '一': 1, '二': 3, '三': 5, '四': 7, '五': 9, '六': 11 };

/** 行标签（一~六 / 34节 / 9-12节）→ 节次区间 */
function sectionRangeFromLabel(text) {
  const normalized = String(text || '').trim().replace(/\s+/g, '');
  if (!normalized) return null;
  const range = normalized.match(/(\d{1,2})\s*[-—–~～至]\s*(\d{1,2})/);
  if (range) {
    const start = Number(range[1]);
    const end = Number(range[2]);
    if (start >= 1 && start <= 12 && end >= 1 && end <= 12) {
      return { startSection: Math.min(start, end), endSection: Math.max(start, end) };
    }
  }
  const compact = normalized.match(/^(\d{1,2})节?$/);
  if (compact) {
    const digits = compact[1];
    if (digits.length === 2 && Number(digits[0]) < Number(digits[1])) {
      return { startSection: Number(digits[0]), endSection: Number(digits[1]) };
    }
    const single = Number(digits);
    if (single >= 9) return { startSection: 9, endSection: 12 };
    return { startSection: single, endSection: Math.min(12, single + 1) };
  }
  const rows = Object.keys(CHINESE_ROWS)
    .filter(function (key) { return normalized.indexOf(key) >= 0; })
    .map(function (key) { return CHINESE_ROWS[key]; });
  if (rows.length) {
    const start = Math.min.apply(null, rows);
    const end = rows.length === 1 ? start + 1 : Math.max.apply(null, rows) + 1;
    return { startSection: start, endSection: Math.min(12, end) };
  }
  const digits = normalized.match(/\d{1,2}/g);
  if (digits && digits.length >= 2) {
    return {
      startSection: Math.min(Number(digits[0]), Number(digits[1])),
      endSection: Math.max(Number(digits[0]), Number(digits[1]))
    };
  }
  return null;
}

/** 课表级公共备注（非课程）提取：备注 = 「其他课程：」汇总行 + 独立「名称（教师）1-19周」行；
 *  学期时间行（本学期…至…，共N周）不属于备注，不再收录显示。 */
const NOTE_TEACHER_WEEK_RE = /\S+[（(][^）)]{1,6}[）)]\s*\d{1,2}\s*[-—–~～至]\s*\d{1,2}\s*周/;
const NOTE_SECTION_MARK_RE = /\d{1,2}\s*[-—–~～至]\s*\d{1,2}\s*节|第\s*\d{1,2}\s*节/;
const MAX_NOTES = 10;

/** 已存旧课表数据兜底清洗：仅保留「其他课程」汇总行（含 OCR 变体如「淇他课程」「其它课程」，
 *  统一规范为「其他课程：」）与「名称（教师）1-19周」类备注行，
 *  丢弃旧版本误收的学期时间行（本学期…至…共N周）等，保证旧数据也符合新备注口径。 */
const OTHER_COURSE_LINE_RE = /^.{0,2}课程[：:]/;

function sanitizeStoredNotes(notes) {
  return (Array.isArray(notes) ? notes : []).map(function (line) {
    const trimmed = String(line || '').trim();
    if (!trimmed) return null;
    if (OTHER_COURSE_LINE_RE.test(trimmed)) return trimmed.replace(OTHER_COURSE_LINE_RE, '其他课程：');
    if (NOTE_TEACHER_WEEK_RE.test(trimmed)) return trimmed;
    return null;
  }).filter(function (item) { return item !== null; });
}

function extractNotes(source) {
  const notes = [];
  const seen = {};
  String(source || '').split(/\r?\n/).forEach(function (line) {
    const trimmed = line.trim();
    if (!trimmed) return;
    // 「其他课程」汇总行（含 OCR 变体）→ 规范为「其他课程：」备注内容（备注 = 其他课程）
    if (OTHER_COURSE_LINE_RE.test(trimmed)) {
      const normalized = trimmed.replace(OTHER_COURSE_LINE_RE, '其他课程：');
      if (!seen[normalized]) { seen[normalized] = true; notes.push(normalized); }
      return;
    }
    if (weekdayIndex(trimmed) != null) return;
    if (NOTE_SECTION_MARK_RE.test(trimmed)) return;
    if (NOTE_TEACHER_WEEK_RE.test(trimmed)) {
      if (!seen[trimmed]) { seen[trimmed] = true; notes.push(trimmed); }
    }
  });
  return notes.slice(0, MAX_NOTES);
}

function parsePageMetadata(text) {
  const source = String(text || '');
  const semesterMatch = source.match(/(\d{4})\s*-\s*(\d{4})\s*年\s*第?\s*([一二1-3])\s*学期/);
  const startMatch = source.match(/本学期\s*(\d{4})\s*[-/年]\s*(\d{1,2})\s*[-/月]\s*(\d{1,2})/)
    || source.match(/(\d{4})\s*年?\s*[-/年]\s*(\d{1,2})\s*[-/月]\s*(\d{1,2})/);
  const weekMatches = (source.match(/共\s*(\d{1,2})\s*周/g) || [])
    .map(function (item) { return Number((item.match(/\d{1,2}/) || [0])[0]); })
    .filter(function (value) { return value >= 1 && value <= 30; });
  const other = (source.match(/其他课程[：:]\s*(.+)/) || [])[1];
  const termLabel = { '一': '第1学期', '1': '第1学期', '二': '第2学期', '2': '第2学期', '三': '第3学期', '3': '第3学期' };
  return {
    semester: semesterMatch ? semesterMatch[1] + '-' + semesterMatch[2] + '年' + (termLabel[semesterMatch[3]] || '') : null,
    startYear: startMatch ? Number(startMatch[1]) : null,
    startMonth: startMatch ? Number(startMatch[2]) : null,
    startDay: startMatch ? Number(startMatch[3]) : null,
    totalWeeks: weekMatches.length ? Math.max.apply(null, weekMatches) : null,
    otherCourseLines: other ? other.split(/[；;]/).map(function (item) { return item.trim(); }).filter(Boolean) : [],
    notes: extractNotes(source)
  };
}

const TIME_RE = /\d{1,2}\s*[:：]\s*\d{2}\s*[-—–~～至]\s*\d{1,2}\s*[:：]\s*\d{2}/g;
const SECTION_LABEL_RE = /^\s*(\d{1,2})\s*[-—–~～]?\s*(\d{1,2})?\s*节/;

function resolveSectionBlock(raw, explicitEnd) {
  const digits = String(raw).trim();
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
function parseTimeTables(text) {
  const lines = String(text || '').split(/\r?\n/).map(function (line) { return line.trim(); })
    .filter(function (line) { return line.length > 0; });
  const profiles = [];
  let group = null;
  let columns = [];
  let rows = [];

  function flush() {
    if (group && rows.length) {
      if (columns.length <= 1) {
        const blocks = rows.map(function (row) {
          const time = row.times[0];
          if (!time) return null;
          const parts = time.split(/\s*[-—–~～至]\s*/);
          return parts.length >= 2
            ? { startSection: row.section[0], endSection: row.section[1], startTime: parts[0], endTime: parts[1] }
            : null;
        }).filter(Boolean);
        if (blocks.length) profiles.push({ id: 'mp-' + group, name: group, blocks: blocks });
      } else {
        columns.forEach(function (columnName, index) {
          const blocks = rows.map(function (row) {
            const time = row.times[index];
            if (!time) return null;
            const parts = time.split(/\s*[-—–~～至]\s*/);
            return parts.length >= 2
              ? { startSection: row.section[0], endSection: row.section[1], startTime: parts[0], endTime: parts[1] }
              : null;
          }).filter(Boolean);
          if (blocks.length) profiles.push({ id: 'mp-' + group + '-' + columnName, name: group + '·' + columnName, blocks: blocks });
        });
      }
    }
    rows = [];
    columns = [];
  }

  lines.forEach(function (line) {
    const header = line.match(/^(.{2,20}?校区).*上课时间/);
    if (header) {
      flush();
      group = header[1].trim();
      return;
    }
    if (!group) return;
    const times = (line.match(TIME_RE) || []).map(function (item) { return item.replace(/：/g, ':'); });
    const label = line.match(SECTION_LABEL_RE);
    if (label && times.length) {
      rows.push({
        section: resolveSectionBlock(label[1], label[2] ? Number(label[2]) : null),
        times: times
      });
      return;
    }
    if (!times.length && /楼|场/.test(line)) {
      columns = line.split(/[\s|｜]+/).map(function (item) { return item.trim(); })
        .filter(function (item) { return item && item !== '节次'; });
    }
  });
  flush();
  return profiles;
}

function timeRangeOf(profile, startSection, endSection) {
  if (!profile || !profile.blocks || !profile.blocks.length) return null;
  let start = null;
  let end = null;
  profile.blocks.forEach(function (block) {
    if (startSection >= block.startSection && startSection <= block.endSection) start = block;
    if (endSection >= block.startSection && endSection <= block.endSection) end = block;
  });
  if (!start) {
    start = profile.blocks.slice().sort(function (a, b) {
      return Math.abs(a.startSection - startSection) - Math.abs(b.startSection - startSection);
    })[0];
  }
  if (!end) end = start;
  return start && end ? { startTime: start.startTime, endTime: end.endTime } : null;
}

function resolveProfile(profiles, campusId) {
  const list = profiles && profiles.length ? profiles : [defaultTimeProfile()];
  if (!campusId) return list.filter(function (p) { return p.id === DEFAULT_PROFILE_ID; })[0] || list[0];
  return list.filter(function (p) { return p.id === campusId; })[0]
    || list.filter(function (p) { return p.id === DEFAULT_PROFILE_ID; })[0]
    || list[0];
}

function parseTableRows(rows, defaultTotalWeeks, timeTables) {
  const warnings = [];
  const courses = [];
  let headerIndex = -1;
  for (let i = 0; i < rows.length; i += 1) {
    const count = (rows[i] || []).filter(function (cell) { return weekdayIndex(cell); }).length;
    if (count >= 4) { headerIndex = i; break; }
  }
  if (headerIndex < 0) return { courses: courses, warnings: warnings };

  const columns = [];
  (rows[headerIndex] || []).forEach(function (cell, index) {
    const weekday = weekdayIndex(cell);
    if (weekday) columns.push({ index: index, weekday: weekday });
  });

  for (let rowIndex = headerIndex + 1; rowIndex < rows.length; rowIndex += 1) {
    const row = rows[rowIndex] || [];
    // 行级噪声过滤：表头/页脚/「其他课程」/「注一」等整行文本不得进入星期列
    if (isNoiseLine(row.join(' '))) continue;
    const rowSection = sectionRangeFromLabel(row[0] || '');
    columns.forEach(function (column) {
      const cell = String(row[column.index] || '').trim();
      if (!cell) return;
      const entries = parseCellEntries(cell, defaultTotalWeeks);
      if (!entries.length) {
        warnings.push('第 ' + (rowIndex + 1) + ' 行「' + cell + '」未能识别，建议手动补充');
        return;
      }
      entries.forEach(function (entry) {
        // 节次来源优先级：单元格内数字节次 → 行标签 → 时间区间映射（通用算法第 5 条）
        const sections = sectionsOf(entry.rawText) || rowSection || timeToSections(entry.rawText, timeTables);
        if (!sections) {
          warnings.push('「' + entry.courseName + '」缺少节次信息，已跳过');
          return;
        }
        courses.push({
          courseName: entry.courseName,
          teacher: entry.teacher,
          meetings: [{
            weekday: WEEKDAYS[column.weekday - 1],
            startSection: sections.startSection,
            endSection: sections.endSection,
            weekRules: entry.weekRules,
            location: entry.location,
            campusAlias: entry.campusAlias
          }]
        });
      });
    });
  }
  return { courses: courses, warnings: warnings };
}

/** 支持引号包裹的 CSV/TSV：`"12-14周(双),15-19周"` 这类含分隔符的单元格不会被拆开 */
function splitDelimited(text, delimiter) {
  const rows = [];
  String(text || '').split(/\r?\n/).forEach(function (line) {
    if (!line.trim()) return;
    const cells = [];
    let current = '';
    let inQuotes = false;
    for (let i = 0; i < line.length; i += 1) {
      const char = line[i];
      if (char === '"') {
        if (inQuotes && line[i + 1] === '"') { current += '"'; i += 1; } else { inQuotes = !inQuotes; }
        continue;
      }
      if (char === delimiter && !inQuotes) {
        cells.push(current.trim());
        current = '';
        continue;
      }
      current += char;
    }
    cells.push(current.trim());
    rows.push(cells);
  });
  return rows;
}

/** 列表式：每行一门课，含星期与节次 */
function parseListStyle(lines, defaultTotalWeeks, timeTables) {
  const courses = [];
  const warnings = [];
  String(lines || '').split(/\r?\n/).forEach(function (line) {
    const weekday = weekdayIndex(line);
    const sections = sectionRangeFromLabel(line);
    if (!weekday || !sections) return;
    const cleaned = line.replace(/星期[一二三四五六日天]|周[一二三四五六日天]/g, '');
    parseCellEntries(cleaned, defaultTotalWeeks).forEach(function (entry) {
      courses.push({
        courseName: entry.courseName,
        teacher: entry.teacher,
        meetings: [{
          weekday: WEEKDAYS[weekday - 1],
          startSection: sections.startSection,
          endSection: sections.endSection,
          weekRules: entry.weekRules,
          location: entry.location,
          campusAlias: entry.campusAlias
        }]
      });
    });
  });
  if (!courses.length) warnings.push('未识别到课表结构，请确认内容或改用粘贴文本导入');
  return { courses: courses, warnings: warnings };
}

/**
 * 节次映射与时间对齐（通用算法第 5、6 条）：
 * 单元格只写时间（如 13:30-15:10）时，用校区时间表把时间区间映射回标准节次。
 */
function timeToSections(text, timeTables) {
  if (!text || !timeTables || !timeTables.length) return null;
  const match = String(text).match(/(\d{1,2}\s*[:：]\s*\d{2})\s*[-—–~～至]\s*(\d{1,2}\s*[:：]\s*\d{2})/);
  if (!match) return null;
  const start = match[1].replace(/[：\s]/g, ':').replace(/\s/g, '');
  const end = match[2].replace(/[：\s]/g, ':').replace(/\s/g, '');
  for (let i = 0; i < timeTables.length; i += 1) {
    const blocks = timeTables[i].blocks || [];
    for (let j = 0; j < blocks.length; j += 1) {
      const block = blocks[j];
      if (block.startTime === start && block.endTime === end) {
        return { startSection: block.startSection, endSection: block.endSection };
      }
    }
  }
  // 容错：起止时间其中一个匹配
  for (let i = 0; i < timeTables.length; i += 1) {
    const blocks = timeTables[i].blocks || [];
    for (let j = 0; j < blocks.length; j += 1) {
      const block = blocks[j];
      if (block.startTime === start || block.endTime === end) {
        return { startSection: block.startSection, endSection: block.endSection };
      }
    }
  }
  return null;
}

/** 两组周次规则是否有交集（区间重叠 + 奇偶兼容）。 */
function weekRulesOverlap(a, b) {
  const listA = a || [];
  const listB = b || [];
  for (let i = 0; i < listA.length; i += 1) {
    for (let j = 0; j < listB.length; j += 1) {
      const ra = listA[i];
      const rb = listB[j];
      if (ra.endWeek < rb.startWeek || rb.endWeek < ra.startWeek) continue;
      if (ra.parity === PARITY.ALL || rb.parity === PARITY.ALL || ra.parity === rb.parity) return true;
    }
  }
  return false;
}

/** 冲突检测：同星期、节次重叠、周次相交的不同课程 → 警告（保留全部）。 */
function detectConflicts(courses) {
  const warnings = [];
  const seen = {};
  const bySlot = {};
  (courses || []).forEach(function (course) {
    (course.meetings || []).forEach(function (meeting) {
      const key = meeting.weekday + '|' + meeting.startSection + '-' + meeting.endSection;
      if (!bySlot[key]) bySlot[key] = [];
      bySlot[key].push(course);
    });
  });
  Object.keys(bySlot).forEach(function (slotKey) {
    const distinct = [];
    const names = {};
    bySlot[slotKey].forEach(function (course) {
      if (!names[course.courseName.trim()]) {
        names[course.courseName.trim()] = true;
        distinct.push(course);
      }
    });
    if (distinct.length < 2) return;
    for (let i = 0; i < distinct.length; i += 1) {
      for (let j = i + 1; j < distinct.length; j += 1) {
        const a = distinct[i];
        const b = distinct[j];
        if (sameSlotMeetingsOverlap(a, b)) {
          const parts = slotKey.split('|');
          const weekdayNames = ['星期一', '星期二', '星期三', '星期四', '星期五', '星期六', '星期日'];
          const label = (weekdayNames[Number(parts[0]) - 1] || '周' + parts[0]) + ' ' + parts[1] + ' 节';
          const message = '时间冲突：' + label + ' ' + a.courseName + ' 与 ' + b.courseName + ' 同时段，已全部保留，请核对';
          if (!seen[message]) {
            seen[message] = true;
            warnings.push(message);
          }
        }
      }
    }
  });
  return warnings;
}

function sameSlotMeetingsOverlap(a, b) {
  const meetingsA = a.meetings || [];
  const meetingsB = b.meetings || [];
  for (let i = 0; i < meetingsA.length; i += 1) {
    for (let j = 0; j < meetingsB.length; j += 1) {
      const ma = meetingsA[i];
      const mb = meetingsB[j];
      if (ma.weekday !== mb.weekday) continue;
      if (ma.startSection > mb.endSection || mb.startSection > ma.endSection) continue;
      if (weekRulesOverlap(ma.weekRules, mb.weekRules)) return true;
    }
  }
  return false;
}

function mergeCourses(courses) {
  const order = [];
  const grouped = {};
  (courses || []).forEach(function (course) {
    // 合并主键：课程名 + 教师 + 地点（通用算法第 7 条）
    const firstMeeting = (course.meetings || [])[0] || {};
    const key = course.courseName.trim() + '|' + String(course.teacher || '').trim() + '|' + String(firstMeeting.location || '').trim();
    if (!grouped[key]) { grouped[key] = []; order.push(key); }
    grouped[key].push(course);
  });
  return order.map(function (key) {
    const group = grouped[key];
    const first = group[0];
    return {
      courseName: first.courseName,
      teacher: first.teacher,
      semester: first.semester || '',
      credit: first.credit == null ? null : first.credit,
      rawWeekText: weekRulesText((first.meetings[0] || {}).weekRules),
      meetings: group.reduce(function (acc, item) { return acc.concat(item.meetings); }, [])
    };
  });
}

/** 导入总入口：rawText（粘贴文本/文本文件）或 rows（CSV/表格） */
function parseCourseTable(rawText, rows) {
  const metadata = parsePageMetadata(rawText || '');
  const total = metadata.totalWeeks || DEFAULT_TOTAL_WEEKS;
  // 时间表提前解析，供「时间→节次」映射（通用算法：节次映射与时间对齐）
  const timeTables = parseTimeTables(rawText || '');
  const parsed = rows && rows.length ? parseTableRows(rows, total, timeTables) : parseListStyle(rawText, total, timeTables);
  const courses = mergeCourses(parsed.courses);
  // 冲突标记：同时段多课全部保留并警告（通用算法：冲突处理）
  parsed.warnings = parsed.warnings.concat(detectConflicts(courses));
  const aliases = [];
  courses.forEach(function (course) {
    course.meetings.forEach(function (meeting) {
      if (meeting.campusAlias && aliases.indexOf(meeting.campusAlias) < 0) aliases.push(meeting.campusAlias);
    });
  });
  // 表外课程（「其他课程：」汇总行）：缺少星期/节次，无法放入课表网格，仅提示
  if (metadata.otherCourseLines && metadata.otherCourseLines.length) {
    parsed.warnings.push('检测到表外课程（缺少星期与节次，未纳入课表）：'
      + metadata.otherCourseLines.slice(0, 5).join('；'));
  }
  return {
    courses: courses,
    semester: metadata.semester,
    semesterStartDate: metadata.startYear
      ? metadata.startYear + '-' + String(metadata.startMonth || 9).padStart(2, '0') + '-' + String(metadata.startDay || 1).padStart(2, '0')
      : null,
    totalWeeks: metadata.totalWeeks,
    timeTables: timeTables,
    campusAliases: aliases,
    notes: metadata.notes || [],
    warnings: parsed.warnings
  };
}

/** XClass 专属课表分享文本 v1（与 Android/iOS/鸿蒙一致）：导出后可被任意端粘贴导入。 */
function encodeShareText(courses, profileName, semester, semesterStart, totalWeeks, campusIdToAlias, notes) {
  const lines = ['# XClass 课表分享 v1'];
  if (profileName && String(profileName).trim()) lines.push('# 课表：' + profileName);
  if (semester && String(semester).trim()) lines.push('# 学期：' + semester);
  if (semesterStart && String(semesterStart).trim()) lines.push('# 学期开始：' + semesterStart);
  if (totalWeeks && totalWeeks >= 1 && totalWeeks <= 60) lines.push('# 总周数：' + totalWeeks);
  // 课表级公共备注：原样写出，再导入时由 notes 提取规则还原（非课程行，不会被解析为课程）
  (notes || []).forEach(function (note) {
    const trimmed = String(note || '').trim();
    if (trimmed) lines.push(trimmed);
  });

  const rows = [];
  (courses || []).forEach(function (course) {
    (course.meetings || []).forEach(function (meeting) {
      // 小程序数据模型 weekday 为字符串枚举（'MONDAY'），兼容数字 1-7
      let day = null;
      if (typeof meeting.weekday === 'number') {
        if (meeting.weekday < 1 || meeting.weekday > 7) return;
        day = ['星期一', '星期二', '星期三', '星期四', '星期五', '星期六', '星期日'][meeting.weekday - 1];
      } else {
        const index = WEEKDAYS.indexOf(meeting.weekday);
        if (index < 0) return;
        day = ['星期一', '星期二', '星期三', '星期四', '星期五', '星期六', '星期日'][index];
      }
      const sections = meeting.startSection + '-' + meeting.endSection + '节';
      const weeks = (course.rawWeekText && String(course.rawWeekText).trim())
        ? course.rawWeekText
        : weekRulesText(meeting.weekRules || []);
      const alias = meeting.campusId && campusIdToAlias ? (campusIdToAlias[meeting.campusId] || '') : '';
      let location = alias ? '（' + alias + '）' : '';
      location += (meeting.location || '').trim();
      if (!location) location = '-';
      const teacher = (course.teacher || '').trim();
      rows.push(day + ' ' + course.courseName + '/(' + sections + ')' + weeks + '/' + location + '/' + (teacher ? teacher : '-'));
    });
  });
  rows.filter(function (value, index) { return rows.indexOf(value) === index; })
    .sort()
    .forEach(function (row) { lines.push(row); });
  return lines.join('\n') + '\n';
}

module.exports = {
  WEEKDAYS: WEEKDAYS,
  PARITY: PARITY,
  DEFAULT_TOTAL_WEEKS: DEFAULT_TOTAL_WEEKS,
  DEFAULT_PROFILE_ID: DEFAULT_PROFILE_ID,
  DEFAULT_PROFILE_NAME: DEFAULT_PROFILE_NAME,
  DEFAULT_RANGES: DEFAULT_RANGES,
  defaultTimeProfile: defaultTimeProfile,
  parseWeekRules: parseWeekRules,
  weekRulesText: weekRulesText,
  parseCellEntries: parseCellEntries,
  parseCellEntry: parseCellEntry,
  sectionsOf: sectionsOf,
  weekdayIndex: weekdayIndex,
  sectionRangeFromLabel: sectionRangeFromLabel,
  isNoiseLine: isNoiseLine,
  isNoiseCourseName: isNoiseCourseName,
  normalizePlaceholder: normalizePlaceholder,
  parsePageMetadata: parsePageMetadata,
  parseTimeTables: parseTimeTables,
  timeRangeOf: timeRangeOf,
  resolveProfile: resolveProfile,
  parseTableRows: parseTableRows,
  splitDelimited: splitDelimited,
  parseListStyle: parseListStyle,
  mergeCourses: mergeCourses,
  detectConflicts: detectConflicts,
  timeToSections: timeToSections,
  weekRulesOverlap: weekRulesOverlap,
  parseCourseTable: parseCourseTable,
  encodeShareText: encodeShareText,
  sanitizeStoredNotes: sanitizeStoredNotes
};
