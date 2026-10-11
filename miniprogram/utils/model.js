/**
 * XClass 小程序数据模型层：周次计算、今日/本周快照、按校区取时间、备份 v2 编解码。
 * 与 Android 的 WeekScheduleCalculator / WidgetSnapshotBuilder / ScheduleBackupCodec 对齐。
 */

const P = require('./parse.js');

const BACKUP_VERSION = 2;

function totalWeeks(courses) {
  const weeks = [];
  (courses || []).forEach(function (course) {
    (course.meetings || []).forEach(function (meeting) {
      (meeting.weekRules || []).forEach(function (rule) { weeks.push(rule.endWeek); });
    });
  });
  return weeks.length ? Math.max.apply(null, weeks) : P.DEFAULT_TOTAL_WEEKS;
}

function todayIso() {
  const now = new Date();
  const month = String(now.getMonth() + 1).padStart(2, '0');
  const day = String(now.getDate()).padStart(2, '0');
  return now.getFullYear() + '-' + month + '-' + day;
}

function guessCurrentWeek(semesterStart, dateIso, weeks) {
  if (!semesterStart || !dateIso) return 1;
  const start = new Date(semesterStart + 'T00:00:00');
  const current = new Date(dateIso + 'T00:00:00');
  const diffDays = Math.floor((current.getTime() - start.getTime()) / 86400000);
  if (diffDays < 0) return 1;
  return Math.min(Math.floor(diffDays / 7) + 1, weeks || P.DEFAULT_TOTAL_WEEKS);
}

function isActiveInWeek(meeting, week) {
  const rules = meeting.weekRules || [];
  if (!rules.length) return true;
  return rules.some(function (rule) {
    if (week < rule.startWeek || week > rule.endWeek) return false;
    if (rule.parity === P.PARITY.ODD) return week % 2 === 1;
    if (rule.parity === P.PARITY.EVEN) return week % 2 === 0;
    return true;
  });
}

function parseMinutes(time) {
  const match = String(time || '').match(/^(\d{1,2}):(\d{2})$/);
  if (!match) return null;
  const hour = Number(match[1]);
  const minute = Number(match[2]);
  if (hour > 23 || minute > 59) return null;
  return hour * 60 + minute;
}

function countdownText(minutes) {
  if (minutes == null) return '';
  if (minutes < 0) return '已结束';
  if (minutes === 0) return '即将开始';
  if (minutes < 60) return minutes + ' 分钟后';
  return Math.floor(minutes / 60) + ' 小时 ' + (minutes % 60) + ' 分后';
}

function activeMeetingsInWeek(courses, week) {
  const result = [];
  (courses || []).forEach(function (course) {
    (course.meetings || []).forEach(function (meeting) {
      if (isActiveInWeek(meeting, week)) result.push({ course: course, meeting: meeting });
    });
  });
  return result;
}

function toSlot(course, meeting, profiles, nowMinutes) {
  const profile = P.resolveProfile(profiles, meeting.campusId);
  const range = P.timeRangeOf(profile, meeting.startSection, meeting.endSection)
    || P.timeRangeOf(P.resolveProfile(profiles, null), meeting.startSection, meeting.endSection);
  const startTime = range ? range.startTime : '--:--';
  const endTime = range ? range.endTime : '--:--';
  const startMinutes = parseMinutes(startTime);
  const endMinutes = parseMinutes(endTime);
  const ongoing = startMinutes != null && endMinutes != null && nowMinutes >= startMinutes && nowMinutes < endMinutes;
  const defaultProfile = P.resolveProfile(profiles, null);
  return {
    courseName: course.courseName,
    teacher: course.teacher || '',
    location: meeting.location || '',
    campusName: profile.id === defaultProfile.id ? '' : profile.name,
    startSection: meeting.startSection,
    endSection: meeting.endSection,
    startTime: startTime,
    endTime: endTime,
    isOngoing: ongoing,
    minutesUntilStart: startMinutes == null ? null : startMinutes - nowMinutes,
    weekday: meeting.weekday,
    weekRules: meeting.weekRules || []
  };
}

function weekdayIndexFromDate(dateIso) {
  const day = new Date(dateIso + 'T00:00:00').getDay();
  return day === 0 ? 7 : day;
}

/** 今日快照：items / ongoingCourse / nextCourse（与原生端同一套规则） */
function todaySnapshot(courses, semesterStart, profiles, dateIso, nowMinutes) {
  const date = dateIso || todayIso();
  const now = nowMinutes == null ? (new Date().getHours() * 60 + new Date().getMinutes()) : nowMinutes;
  const weeks = totalWeeks(courses);
  const week = guessCurrentWeek(semesterStart, date, weeks);
  const weekday = P.WEEKDAYS[weekdayIndexFromDate(date) - 1];
  const items = activeMeetingsInWeek(courses, week)
    .filter(function (item) { return item.meeting.weekday === weekday; })
    .map(function (item) { return toSlot(item.course, item.meeting, profiles, now); })
    .sort(function (a, b) { return a.startSection - b.startSection; });

  let nextCourse = null;
  let ongoing = null;
  items.forEach(function (item) {
    if (item.isOngoing && !ongoing) ongoing = item;
    if (!item.isOngoing && !nextCourse && item.minutesUntilStart != null && item.minutesUntilStart >= 0) nextCourse = item;
  });
  if (!nextCourse) {
    items.forEach(function (item) { if (!item.isOngoing && !nextCourse) nextCourse = item; });
  }
  return {
    date: date,
    week: week,
    totalWeeks: weeks,
    weekday: weekday,
    items: items,
    ongoingCourse: ongoing,
    nextCourse: nextCourse
  };
}

/** 下一节课（含跨天兜底），用于今日页与提醒条 */
function nextCourse(courses, semesterStart, profiles, dateIso, nowMinutes) {
  const base = dateIso || todayIso();
  for (let offset = 0; offset < 14; offset += 1) {
    const date = new Date(base + 'T00:00:00');
    date.setDate(date.getDate() + offset);
    const iso = date.toISOString().slice(0, 10);
    const snapshot = todaySnapshot(courses, semesterStart, profiles, iso, offset === 0 ? nowMinutes : 0);
    if (snapshot.ongoingCourse) return snapshot.ongoingCourse;
    if (snapshot.nextCourse) return snapshot.nextCourse;
  }
  return null;
}

function weekGrid(courses, week) {
  const rows = ['1-2', '3-4', '5-6', '7-8', '9-10', '11-12'];
  const cells = rows.map(function () { return P.WEEKDAYS.map(function () { return ''; }); });
  activeMeetingsInWeek(courses, week).forEach(function (item) {
    const rowIndex = Math.min(rows.length - 1, Math.max(0, Math.floor((item.meeting.startSection - 1) / 2)));
    const columnIndex = P.WEEKDAYS.indexOf(item.meeting.weekday);
    if (columnIndex < 0) return;
    const current = cells[rowIndex][columnIndex];
    cells[rowIndex][columnIndex] = current
      ? (current + '/' + item.course.courseName.slice(0, 2)).slice(0, 8)
      : item.course.courseName.slice(0, 4);
  });
  return {
    week: week,
    rowLabels: rows,
    columnLabels: ['一', '二', '三', '四', '五', '六', '日'],
    cells: cells
  };
}

/** 课表左侧时间列（某套时间表的小节起止） */
function sectionRanges(profile, count) {
  const total = count || 12;
  const result = [];
  for (let section = 1; section <= total; section += 1) {
    const range = P.timeRangeOf(profile, section, section);
    result.push(range ? range.startTime + '-' + range.endTime : '--:--');
  }
  return result;
}

function encodeBackup(profiles, timeProfiles, campusMap, defaultProfileId, exportedAt) {
  return JSON.stringify({
    version: BACKUP_VERSION,
    exportedAt: exportedAt || new Date().toISOString(),
    profiles: (profiles || []).map(function (profile) {
      return {
        name: profile.name,
        semesterStartDate: profile.semesterStartDate || null,
        notes: Array.isArray(profile.notes) ? profile.notes : [],
        courses: (profile.courses || []).map(function (course) {
          return {
            courseName: course.courseName,
            teacher: course.teacher || '',
            semester: course.semester || '',
            credit: course.credit == null ? null : course.credit,
            rawWeekText: course.rawWeekText || '',
            meetings: (course.meetings || []).map(function (meeting) {
              return {
                weekday: meeting.weekday,
                startSection: meeting.startSection,
                endSection: meeting.endSection,
                weekRules: meeting.weekRules || [],
                location: meeting.location || '',
                campusId: meeting.campusId == null ? null : meeting.campusId
              };
            })
          };
        })
      };
    }),
    timeProfiles: (timeProfiles || [])
      .filter(function (profile) { return profile.id !== P.DEFAULT_PROFILE_ID; })
      .map(function (profile) { return { id: profile.id, name: profile.name, blocks: profile.blocks || [] }; }),
    campusMap: campusMap || {},
    defaultProfileId: defaultProfileId || null
  }, null, 2);
}

function decodeBackup(content) {
  try {
    const file = JSON.parse(content);
    if (!file || typeof file.version !== 'number' || file.version < 1 || file.version > BACKUP_VERSION) return null;
    return {
      profiles: (file.profiles || []).map(function (profile) {
        return {
          name: profile.name || '课表',
          semesterStartDate: profile.semesterStartDate || null,
          notes: Array.isArray(profile.notes) ? profile.notes : [],
          courses: (profile.courses || []).map(function (course) {
            return {
              courseName: course.courseName,
              teacher: course.teacher || '',
              semester: course.semester || '',
              credit: course.credit == null ? null : course.credit,
              rawWeekText: course.rawWeekText || '',
              meetings: (course.meetings || []).map(function (meeting) {
                return {
                  weekday: meeting.weekday,
                  startSection: meeting.startSection,
                  endSection: meeting.endSection,
                  weekRules: meeting.weekRules || [],
                  location: meeting.location || '',
                  campusId: meeting.campusId || null
                };
              })
            };
          })
        };
      }),
      timeProfiles: file.timeProfiles || [],
      campusMap: file.campusMap || {},
      defaultProfileId: file.defaultProfileId || null
    };
  } catch (error) {
    return null;
  }
}

module.exports = {
  BACKUP_VERSION: BACKUP_VERSION,
  totalWeeks: totalWeeks,
  todayIso: todayIso,
  guessCurrentWeek: guessCurrentWeek,
  isActiveInWeek: isActiveInWeek,
  parseMinutes: parseMinutes,
  countdownText: countdownText,
  activeMeetingsInWeek: activeMeetingsInWeek,
  todaySnapshot: todaySnapshot,
  nextCourse: nextCourse,
  weekGrid: weekGrid,
  sectionRanges: sectionRanges,
  encodeBackup: encodeBackup,
  decodeBackup: decodeBackup
};
