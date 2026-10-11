/**
 * 本地存储层：与 Android/iOS/鸿蒙 一致的 timetables/ 布局（meta.json + courses_*.json + time_profiles.json），
 * 备份格式 v2 可直接与三端互导。全部使用本地文件系统与本地缓存，不发起任何网络请求。
 */
const core = require('./core.js');

const FS = wx.getFileSystemManager();
const ROOT = `${wx.env.USER_DATA_PATH}/timetables`;
const META = `${ROOT}/meta.json`;
const TIME_PROFILES = `${ROOT}/time_profiles.json`;
const PREFS_KEY = 'xclass.prefs';

// 轻量内存缓存：避免每次切页/onShow 都做同步文件读取（小程序端明显的卡顿来源）
let coursesCache = null;
let timeProfileCache = null;
let prefsCache = null;

function invalidateCache() {
  coursesCache = null;
  timeProfileCache = null;
}

function clone(value) {
  return value == null ? value : JSON.parse(JSON.stringify(value));
}

function ensureRoot() {
  try {
    FS.accessSync(ROOT);
  } catch (error) {
    FS.mkdirSync(ROOT, true);
  }
}

function readJson(path, fallback) {
  try {
    return JSON.parse(FS.readFileSync(path, 'utf8'));
  } catch (error) {
    return fallback;
  }
}

function writeJson(path, value) {
  ensureRoot();
  FS.writeFileSync(path, JSON.stringify(value, null, 2), 'utf8');
}

function uuid() {
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (char) => {
    const random = Math.floor(Math.random() * 16);
    const value = char === 'x' ? random : ((random & 0x3) | 0x8);
    return value.toString(16);
  });
}

function todayIso() {
  const now = new Date();
  return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(now.getDate()).padStart(2, '0')}`;
}

function defaultSemesterStart() {
  const now = new Date();
  const year = now.getMonth() >= 6 ? now.getFullYear() : now.getFullYear() - 1;
  return `${year}-08-31`;
}

// ---------------------------------------------------------------- 课表元数据

function loadMeta() {
  ensureRoot();
  let meta = readJson(META, null);
  if (!meta || !meta.profiles || !meta.profiles.length) {
    const id = uuid();
    meta = {
      activeId: id,
      profiles: [{
        id,
        name: '默认课表',
        coursesFile: `courses_${id}.json`,
        updatedAt: Date.now(),
        semesterStartDate: defaultSemesterStart(),
      }],
    };
    writeJson(META, meta);
    writeJson(`${ROOT}/${meta.profiles[0].coursesFile}`, []);
  }
  return meta;
}

function saveMeta(meta) {
  writeJson(META, meta);
}

function listProfiles() {
  const meta = loadMeta();
  return meta.profiles.map((profile) => ({
    id: profile.id,
    name: profile.name,
    isActive: profile.id === meta.activeId,
    semesterStartDate: profile.semesterStartDate || defaultSemesterStart(),
  }));
}

function activeProfile() {
  const meta = loadMeta();
  return meta.profiles.find((profile) => profile.id === meta.activeId) || meta.profiles[0];
}

function setActiveProfile(id) {
  const meta = loadMeta();
  if (!meta.profiles.some((profile) => profile.id === id)) return false;
  meta.activeId = id;
  saveMeta(meta);
  return true;
}

function createProfile(name) {
  const meta = loadMeta();
  const id = uuid();
  const profile = {
    id,
    name: (name || '').trim() || `课表${new Date().toLocaleDateString('zh-CN').replace(/\//g, '')}`,
    coursesFile: `courses_${id}.json`,
    updatedAt: Date.now(),
    semesterStartDate: defaultSemesterStart(),
  };
  writeJson(`${ROOT}/${profile.coursesFile}`, []);
  meta.profiles.push(profile);
  meta.activeId = id;
  saveMeta(meta);
  invalidateCache();
  return profile;
}

function renameProfile(id, name) {
  const trimmed = (name || '').trim();
  if (!trimmed) return false;
  const meta = loadMeta();
  const profile = meta.profiles.find((item) => item.id === id);
  if (!profile) return false;
  profile.name = trimmed;
  profile.updatedAt = Date.now();
  saveMeta(meta);
  return true;
}

function deleteProfile(id) {
  const meta = loadMeta();
  if (meta.profiles.length <= 1) return false;
  const target = meta.profiles.find((item) => item.id === id);
  if (!target) return false;
  meta.profiles = meta.profiles.filter((item) => item.id !== id);
  if (meta.activeId === id) meta.activeId = meta.profiles[0].id;
  saveMeta(meta);
  try { FS.unlinkSync(`${ROOT}/${target.coursesFile}`); } catch (error) { /* 文件可能已不存在 */ }
  invalidateCache();
  return true;
}

// ---------------------------------------------------------------- 课程

function loadCourses(profileId) {
  const meta = loadMeta();
  const profile = profileId
    ? meta.profiles.find((item) => item.id === profileId)
    : (meta.profiles.find((item) => item.id === meta.activeId) || meta.profiles[0]);
  if (!profile) return [];
  if (coursesCache && coursesCache.profileId === profile.id && coursesCache.mtime === profile.updatedAt) {
    return clone(coursesCache.data);
  }
  const data = readJson(`${ROOT}/${profile.coursesFile}`, []);
  coursesCache = { profileId: profile.id, mtime: profile.updatedAt, data: data };
  return clone(data);
}

function saveCourses(courses, profileId) {
  const meta = loadMeta();
  const profile = profileId
    ? meta.profiles.find((item) => item.id === profileId)
    : (meta.profiles.find((item) => item.id === meta.activeId) || meta.profiles[0]);
  if (!profile) return;
  profile.updatedAt = Date.now();
  writeJson(`${ROOT}/${profile.coursesFile}`, courses || []);
  saveMeta(meta);
  coursesCache = { profileId: profile.id, mtime: profile.updatedAt, data: courses || [] };
}

function getActiveSemesterStart() {
  return activeProfile().semesterStartDate || defaultSemesterStart();
}

function setActiveSemesterStart(date) {
  const meta = loadMeta();
  const profile = meta.profiles.find((item) => item.id === meta.activeId) || meta.profiles[0];
  profile.semesterStartDate = date;
  saveMeta(meta);
}

function addManualCourse(input) {
  const courses = loadCourses();
  const meeting = {
    weekday: input.weekday,
    startSection: input.startSection,
    endSection: input.endSection,
    weekRules: core.parseWeekRules(input.weekText),
    location: input.location || '',
    campusId: input.campusId || null,
  };
  const existing = courses.find((course) => course.courseName === input.courseName && (course.teacher || '') === (input.teacher || ''));
  if (existing) {
    existing.meetings = (existing.meetings || []).concat([meeting]);
  } else {
    courses.push({
      courseName: input.courseName,
      teacher: input.teacher || '',
      semester: '手动添加',
      credit: null,
      rawWeekText: input.weekText || '',
      meetings: [meeting],
    });
  }
  saveCourses(courses);
}

function deleteCourse(index) {
  const courses = loadCourses();
  if (index < 0 || index >= courses.length) return false;
  courses.splice(index, 1);
  saveCourses(courses);
  return true;
}

/** 编辑课程定义（含周规则），全局生效：所有周同步。profileId 为空时作用于当前课表。 */
function updateCourse(courseIndex, newCourse, profileId) {
  const courses = loadCourses(profileId);
  if (courseIndex < 0 || courseIndex >= courses.length) return false;
  courses[courseIndex] = newCourse;
  saveCourses(courses, profileId);
  return true;
}

/** 删除指定课表中的课程（课表管理/完整课表视图使用）。 */
function deleteCourseInProfile(index, profileId) {
  const courses = loadCourses(profileId);
  if (!profileId || index < 0 || index >= courses.length) return false;
  courses.splice(index, 1);
  saveCourses(courses, profileId);
  return true;
}

/** 导入入库：覆盖当前课表或新建课表 */
function importCourses(parsedCourses, mode, newName, semesterStartDate, campusMap) {
  const resolved = (parsedCourses || []).map((course) => ({
    courseName: course.courseName,
    teacher: course.teacher || '',
    semester: course.semester || '',
    credit: course.credit == null ? null : course.credit,
    rawWeekText: course.rawWeekText || '',
    meetings: (course.meetings || [])
      .filter((meeting) => meeting.weekday)
      .map((meeting) => ({
        weekday: meeting.weekday,
        startSection: meeting.startSection,
        endSection: meeting.endSection,
        weekRules: meeting.weekRules || [],
        location: meeting.location || '',
        campusId: meeting.campusAlias && campusMap ? (campusMap[meeting.campusAlias] || null) : (meeting.campusId || null),
      })),
  }));

  if (mode === 'new') {
    const profile = createProfile(newName);
    saveCourses(resolved, profile.id);
    if (semesterStartDate) {
      const meta = loadMeta();
      const target = meta.profiles.find((item) => item.id === profile.id);
      target.semesterStartDate = semesterStartDate;
      saveMeta(meta);
    }
    return profile.name;
  }
  saveCourses(resolved);
  if (semesterStartDate) setActiveSemesterStart(semesterStartDate);
  return activeProfile().name;
}

// ---------------------------------------------------------------- 节次时间表

function loadTimeProfiles() {
  const stored = readJson(TIME_PROFILES, { profiles: [], campusMap: {}, defaultProfileId: null });
  const custom = (stored.profiles || []).filter((profile) => profile.blocks && profile.blocks.length);
  if (timeProfileCache && timeProfileCache.stamp === JSON.stringify(stored).length) {
    return clone(timeProfileCache.data);
  }
  const data = [core.defaultTimeProfile()].concat(custom);
  timeProfileCache = { stamp: JSON.stringify(stored).length, data: data };
  return clone(data);
}

function loadCampusMap() {
  return readJson(TIME_PROFILES, { campusMap: {} }).campusMap || {};
}

function saveTimeProfiles(profiles, campusMap, defaultProfileId) {
  writeJson(TIME_PROFILES, {
    version: 1,
    defaultProfileId: defaultProfileId || null,
    profiles: (profiles || []).filter((profile) => profile.id !== core.DEFAULT_PROFILE_ID),
    campusMap: campusMap || {},
  });
  timeProfileCache = null;
}

/** 导入时保存识别出的时间表，并建立「校区简称 → 时间表」映射 */
function saveParsedTimeTables(parsed) {
  const existing = loadTimeProfiles();
  const map = loadCampusMap();
  (parsed.timeTables || []).forEach((profile) => {
    const index = existing.findIndex((item) => item.id === profile.id || item.name === profile.name);
    if (index >= 0) existing[index] = profile; else existing.push(profile);
  });
  (parsed.campusAliases || []).forEach((alias) => {
    const matched = (parsed.timeTables || []).find((profile) => profile.name.indexOf(alias) >= 0);
    if (matched) map[alias] = matched.id;
  });
  saveTimeProfiles(existing, map, null);
  return map;
}

// ---------------------------------------------------------------- 偏好

const DEFAULT_PREFS = {
  theme: 'warm',
  darkMode: 'light',
  fontScale: 'normal',
  defaultPage: 'timetable',
  showNonCurrent: true,
  reminderEnabled: true,
};

function loadPrefs() {
  if (!prefsCache) prefsCache = Object.assign({}, DEFAULT_PREFS, wx.getStorageSync(PREFS_KEY) || {});
  return Object.assign({}, prefsCache);
}

function savePrefs(prefs) {
  prefsCache = Object.assign({}, loadPrefs(), prefs);
  wx.setStorageSync(PREFS_KEY, prefsCache);
}

// ---------------------------------------------------------------- 备份

function exportBackup() {
  const meta = loadMeta();
  const profiles = meta.profiles.map((profile) => ({
    name: profile.name,
    semesterStartDate: profile.semesterStartDate || null,
    courses: readJson(`${ROOT}/${profile.coursesFile}`, []),
  }));
  const json = core.encodeBackup(profiles, loadTimeProfiles(), loadCampusMap(), null);
  const path = `${wx.env.USER_DATA_PATH}/XClass_backup_${Date.now()}.json`;
  FS.writeFileSync(path, json, 'utf8');
  return path;
}

function importBackup(content) {
  const decoded = core.decodeBackup(content);
  if (!decoded || !decoded.profiles.length) return -1;
  const meta = { activeId: '', profiles: [] };
  decoded.profiles.forEach((profile) => {
    const id = uuid();
    const file = `courses_${id}.json`;
    writeJson(`${ROOT}/${file}`, profile.courses || []);
    meta.profiles.push({
      id,
      name: profile.name || '课表',
      coursesFile: file,
      updatedAt: Date.now(),
      semesterStartDate: profile.semesterStartDate || defaultSemesterStart(),
    });
  });
  meta.activeId = meta.profiles[0].id;
  saveMeta(meta);
  invalidateCache();
  if (decoded.timeProfiles && decoded.timeProfiles.length) {
    saveTimeProfiles(decoded.timeProfiles, decoded.campusMap, decoded.defaultProfileId);
  }
  return meta.profiles.length;
}

function readFileText(path) {
  return FS.readFileSync(path, 'utf8');
}

module.exports = {
  invalidateCache,
  todayIso,
  listProfiles,
  activeProfile,
  setActiveProfile,
  createProfile,
  renameProfile,
  deleteProfile,
  loadCourses,
  saveCourses,
  getActiveSemesterStart,
  setActiveSemesterStart,
  addManualCourse,
  deleteCourse,
  updateCourse,
  deleteCourseInProfile,
  importCourses,
  loadTimeProfiles,
  loadCampusMap,
  saveTimeProfiles,
  saveParsedTimeTables,
  loadPrefs,
  savePrefs,
  exportBackup,
  importBackup,
  readFileText
};
