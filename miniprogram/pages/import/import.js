const core = require('../../utils/core.js');
const store = require('../../utils/store.js');
const theme = require('../../utils/theme.js');

let cachedDark = null;
function systemDark() {
  if (cachedDark == null) {
    const info = wx.getSystemInfoSync ? wx.getSystemInfoSync() : {};
    cachedDark = info.theme === 'dark';
  }
  return cachedDark;
}

Page({
  data: {
    palette: theme.palette('warm', false),
    statusBarHeight: 24,
    mode: 'paste',
    text: '',
    groups: [],
    pendingFiles: [],
    warnings: [],
    saving: false,
    // 编辑弹窗
    editGroupIndex: -1,
    editCourseIndex: -1,
    editForm: {
      courseName: '',
      teacher: '',
      location: '',
      weekdayIndex: 0,
      startSection: '1',
      endSection: '2',
      weekText: '1-16周',
    },
    weekdayNames: ['周一', '周二', '周三', '周四', '周五', '周六', '周日'],
  },
  onLoad(options) {
    const prefs = store.loadPrefs();
    const info = wx.getSystemInfoSync ? wx.getSystemInfoSync() : {};
    const dark = prefs.darkMode === 'dark' || (prefs.darkMode === 'system' && systemDark());
    this.setData({
      palette: theme.palette(prefs.theme, dark),
      statusBarHeight: info.statusBarHeight || 24,
      mode: (options && options.mode) || 'paste',
    });
    // 课表页粘贴弹窗传递的待解析文本：自动解析进入预览
    const pending = getApp().globalData.pendingImportText;
    getApp().globalData.pendingImportText = null;
    if (pending && typeof pending === 'string' && pending.trim()) {
      this.setData({ text: pending }, () => this.parseText());
    }
  },
  onTextInput(event) {
    this.setData({ text: event.detail.value });
  },
  /** 弹窗内容区点击占位：阻止冒泡到遮罩层，避免误关弹窗。 */
  noop() {},
  chooseFile() {
    // 只收集待识别文件列表（一个文件 = 一个课表），不立即解析；支持多次选择累加，按路径/文件名+大小去重
    wx.chooseMessageFile({
      count: 9,
      type: 'file',
      extension: ['txt', 'md', 'csv', 'json', 'tsv'],
      success: (result) => {
        const files = result.tempFiles || [];
        if (!files.length) return;
        const pending = this.data.pendingFiles.slice();
        let added = 0;
        files.forEach((file) => {
          const sizeKey = `${file.name}|${file.size || 0}`;
          const duplicated = pending.some((item) => item.path === file.path || item.sizeKey === sizeKey);
          if (duplicated) return;
          pending.push({ name: file.name, path: file.path, size: file.size || 0, sizeKey: sizeKey });
          added += 1;
        });
        this.setData({ pendingFiles: pending });
        wx.showToast({
          title: added ? `已加入 ${added} 个待识别文件，点「识别全部」` : '所选文件已在列表中',
          icon: 'none',
        });
      },
      fail: () => wx.showToast({ title: '未选择文件', icon: 'none' }),
    });
  },
  removePendingFile(event) {
    const index = Number(event.currentTarget.dataset.index);
    const pending = this.data.pendingFiles.slice();
    pending.splice(index, 1);
    this.setData({ pendingFiles: pending });
  },
  /** 识别全部：统一遍历待识别列表逐文件解析进入预览，复用现有解析管线 */
  recognizeAll() {
    const pending = this.data.pendingFiles;
    if (!pending.length) {
      wx.showToast({ title: '请先从聊天记录选择文件', icon: 'none' });
      return;
    }
    let ok = 0;
    const failed = [];
    pending.forEach((file) => {
      try {
        const content = store.readFileText(file.path);
        this.parse(content, file.name);
        ok += 1;
      } catch (error) {
        failed.push(file.name);
      }
    });
    this.setData({ pendingFiles: [] });
    if (failed.length) {
      wx.showToast({ title: `部分文件读取失败：${failed.slice(0, 3).join('、')}`, icon: 'none' });
    } else {
      wx.showToast({ title: `已识别 ${ok} 个文件，可预览编辑后保存`, icon: 'none' });
    }
  },
  parseText() {
    if (!this.data.text.trim()) {
      wx.showToast({ title: '请先粘贴课表文本', icon: 'none' });
      return;
    }
    this.parse(this.data.text, '粘贴文本');
  },
  parse(content, sourceName) {
    let rows = null;
    if (/\.csv$/i.test(sourceName)) rows = core.splitDelimited(content, ',');
    if (/\.tsv$/i.test(sourceName)) rows = core.splitDelimited(content, '\t');
    const parsed = core.parseCourseTable(content, rows);
    const preview = parsed.courses.map((course) => this.previewRow(course));
    const groups = this.data.groups.concat([{
      name: sourceName,
      sourceName: sourceName,
      parsed: parsed,
      preview: preview,
    }]);
    this.setData({ groups: groups, warnings: parsed.warnings || [] });
    if (!preview.length && parsed.warnings.length) {
      wx.showToast({ title: parsed.warnings[0], icon: 'none' });
    }
  },
  previewRow(course) {
    return {
      courseName: course.courseName,
      teacher: course.teacher,
      detail: (course.meetings || []).map((meeting) => {
        const weekday = ['一', '二', '三', '四', '五', '六', '日'][core.WEEKDAYS.indexOf(meeting.weekday)];
        const campus = meeting.campusAlias ? `（${meeting.campusAlias}）` : '';
        return `周${weekday} 第${meeting.startSection}-${meeting.endSection}节 ${core.weekRulesText(meeting.weekRules)}${campus}${meeting.location || ''}`;
      }).join('；'),
    };
  },
  removeGroup(event) {
    const groupIndex = Number(event.currentTarget.dataset.group);
    const groups = this.data.groups.slice();
    groups.splice(groupIndex, 1);
    this.setData({ groups: groups });
  },
  removeCourse(event) {
    const groupIndex = Number(event.currentTarget.dataset.group);
    const courseIndex = Number(event.currentTarget.dataset.index);
    const groups = this.data.groups.slice();
    groups[groupIndex].parsed.courses.splice(courseIndex, 1);
    groups[groupIndex].preview.splice(courseIndex, 1);
    this.setData({ groups: groups });
  },
  openEdit(event) {
    const groupIndex = Number(event.currentTarget.dataset.group);
    const courseIndex = Number(event.currentTarget.dataset.index);
    const course = this.data.groups[groupIndex].parsed.courses[courseIndex];
    const meeting = (course.meetings || [])[0] || {};
    this.setData({
      editGroupIndex: groupIndex,
      editCourseIndex: courseIndex,
      editForm: {
        courseName: course.courseName || '',
        teacher: course.teacher || '',
        location: meeting.location || '',
        weekdayIndex: Math.max(0, core.WEEKDAYS.indexOf(meeting.weekday)),
        startSection: String(meeting.startSection || 1),
        endSection: String(meeting.endSection || 2),
        weekText: course.rawWeekText || core.weekRulesText(meeting.weekRules || []) || '1-16周',
      },
    });
  },
  closeEdit() {
    this.setData({ editGroupIndex: -1, editCourseIndex: -1 });
  },
  onEditField(event) {
    const field = event.currentTarget.dataset.field;
    const value = event.detail.value;
    this.setData({ ['editForm.' + field]: value });
  },
  onEditWeekday(event) {
    this.setData({ 'editForm.weekdayIndex': Number(event.detail.value) });
  },
  saveEdit() {
    const form = this.data.editForm;
    const groupIndex = this.data.editGroupIndex;
    const courseIndex = this.data.editCourseIndex;
    if (groupIndex < 0 || courseIndex < 0) return;
    const name = (form.courseName || '').trim();
    if (!name) {
      wx.showToast({ title: '课程名不能为空', icon: 'none' });
      return;
    }
    const startSection = Math.max(1, Math.min(12, Number(form.startSection) || 1));
    const endSection = Math.max(startSection, Math.min(12, Number(form.endSection) || startSection));
    const weekRules = core.parseWeekRules((form.weekText || '').trim() || '1-16周');
    const weekday = core.WEEKDAYS[form.weekdayIndex] || '周一';
    const groups = this.data.groups.slice();
    const course = groups[groupIndex].parsed.courses[courseIndex];
    course.courseName = name;
    course.teacher = (form.teacher || '').trim();
    course.rawWeekText = (form.weekText || '').trim();
    (course.meetings || []).forEach((meeting) => {
      meeting.weekday = weekday;
      meeting.startSection = startSection;
      meeting.endSection = endSection;
      meeting.weekRules = weekRules;
      meeting.location = (form.location || '').trim();
    });
    if (!course.meetings || !course.meetings.length) {
      course.meetings = [{
        weekday: weekday,
        startSection: startSection,
        endSection: endSection,
        weekRules: weekRules,
        location: (form.location || '').trim(),
        campusId: null,
      }];
    }
    groups[groupIndex].preview[courseIndex] = this.previewRow(course);
    this.setData({ groups: groups, editGroupIndex: -1, editCourseIndex: -1 });
    wx.showToast({ title: '已更新，保存后生效', icon: 'none' });
  },
  saveGroup(group, mode, newName, campusMap) {
    // 解析出校区时间表时，自动把首个校区时间表设为当前显示时间表（timetable.js 已动态跟随）
    if (group.parsed.timeTables && group.parsed.timeTables.length) {
      wx.setStorageSync('xclass.defaultTimeProfile', group.parsed.timeTables[0].id);
    }
    return store.importCourses(
      group.parsed.courses,
      mode,
      newName,
      group.parsed.semesterStartDate,
      campusMap,
      group.parsed.notes || []
    );
  },
  save() {
    const groups = this.data.groups;
    const total = groups.reduce((sum, group) => sum + (group.parsed.courses.length || 0), 0);
    if (!total) {
      wx.showToast({ title: '暂无可导入课程', icon: 'none' });
      return;
    }
    if (this.data.saving) return;
    this.setData({ saving: true });
    const finish = () => {
      this.setData({ saving: false });
      wx.showToast({ title: `已导入 ${groups.length} 份课表`, icon: 'none' });
      setTimeout(() => wx.switchTab({ url: '/pages/timetable/timetable' }), 800);
    };
    if (groups.length === 1) {
      // 单组：保留覆盖/新建选择
      wx.showActionSheet({
        itemList: ['覆盖当前课表', '新建课表导入'],
        success: (result) => {
          const group = groups[0];
          const campusMap = store.saveParsedTimeTables(group.parsed);
          const name = this.saveGroup(
            group,
            result.tapIndex === 0 ? 'overwrite' : 'new',
            group.parsed.semester ? `${group.parsed.semester} 导入` : null,
            campusMap
          );
          wx.showToast({ title: `已导入到「${name}」`, icon: 'none' });
          setTimeout(() => wx.switchTab({ url: '/pages/timetable/timetable' }), 800);
        },
        complete: () => this.setData({ saving: false }),
      });
      return;
    }
    // 多组：一个文件 = 一个课表，逐个新建课表导入
    groups.forEach((group, index) => {
      const campusMap = store.saveParsedTimeTables(group.parsed);
      const baseName = (group.parsed.semester ? `${group.parsed.semester} ` : '') + (group.sourceName || `课表${index + 1}`).replace(/\.(txt|md|csv|json|tsv)$/i, '');
      this.saveGroup(group, 'new', baseName, campusMap);
    });
    finish();
  },
  /** 导出当前课表为 XClass 分享文本：复制到剪贴板，朋友粘贴到任意端即可导入。 */
  exportShareText() {
    const courses = store.loadCourses();
    if (!courses.length) {
      wx.showToast({ title: '当前课表还没有课程', icon: 'none' });
      return;
    }
    const active = store.activeProfile();
    const campusMap = store.loadCampusMap();
    const idToAlias = {};
    Object.keys(campusMap).forEach((alias) => { idToAlias[campusMap[alias]] = alias; });
    const semester = (courses.find((c) => c.semester && String(c.semester).trim()) || {}).semester || null;
    let totalWeeks = null;
    courses.forEach((course) => (course.meetings || []).forEach((meeting) => (meeting.weekRules || []).forEach((rule) => {
      if (totalWeeks === null || rule.endWeek > totalWeeks) totalWeeks = rule.endWeek;
    })));
    const text = core.encodeShareText(
      courses,
      active ? active.name : null,
      semester,
      store.getActiveSemesterStart(),
      totalWeeks,
      idToAlias,
      store.loadProfileNotes()
    );
    wx.setClipboardData({
      data: text,
      success: () => wx.showToast({ title: '已复制课表分享文本', icon: 'none' }),
      fail: () => wx.showToast({ title: '复制失败，请重试', icon: 'none' }),
    });
  },
});
