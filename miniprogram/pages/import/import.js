const core = require('../../utils/core.js');
const store = require('../../utils/store.js');
const theme = require('../../utils/theme.js');

Page({
  data: {
    palette: theme.palette('warm', false),
    statusBarHeight: 24,
    mode: 'paste',
    text: '',
    parsed: null,
    preview: [],
    warnings: [],
    saving: false,
  },
  onLoad(options) {
    const prefs = store.loadPrefs();
    const info = wx.getSystemInfoSync ? wx.getSystemInfoSync() : {};
    this.setData({
      palette: theme.palette(prefs.theme, false),
      statusBarHeight: info.statusBarHeight || 24,
      mode: (options && options.mode) || 'paste',
    });
  },
  onTextInput(event) {
    this.setData({ text: event.detail.value });
  },
  chooseFile() {
    wx.chooseMessageFile({
      count: 1,
      type: 'file',
      extension: ['txt', 'md', 'csv', 'json', 'tsv'],
      success: (result) => {
        const file = result.tempFiles[0];
        try {
          const content = store.readFileText(file.path);
          this.parse(content, file.name);
        } catch (error) {
          wx.showToast({ title: '读取文件失败', icon: 'none' });
        }
      },
      fail: () => wx.showToast({ title: '未选择文件', icon: 'none' }),
    });
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
    const preview = parsed.courses.map((course) => ({
      courseName: course.courseName,
      teacher: course.teacher,
      detail: (course.meetings || []).map((meeting) => {
        const weekday = ['一', '二', '三', '四', '五', '六', '日'][core.WEEKDAYS.indexOf(meeting.weekday)];
        const campus = meeting.campusAlias ? `（${meeting.campusAlias}）` : '';
        return `周${weekday} 第${meeting.startSection}-${meeting.endSection}节 ${core.weekRulesText(meeting.weekRules)}${campus}${meeting.location || ''}`;
      }).join('；'),
    }));
    this.setData({ parsed: parsed, preview: preview, warnings: parsed.warnings || [] });
    if (!preview.length && parsed.warnings.length) {
      wx.showToast({ title: parsed.warnings[0], icon: 'none' });
    }
  },
  removeCourse(event) {
    const index = Number(event.currentTarget.dataset.index);
    const parsed = this.data.parsed;
    parsed.courses.splice(index, 1);
    const preview = this.data.preview.filter((item, itemIndex) => itemIndex !== index);
    this.setData({ parsed: parsed, preview: preview });
  },
  save() {
    const parsed = this.data.parsed;
    if (!parsed || !parsed.courses.length) {
      wx.showToast({ title: '暂无可导入课程', icon: 'none' });
      return;
    }
    wx.showActionSheet({
      itemList: ['覆盖当前课表', '新建课表导入'],
      success: (result) => {
        const campusMap = store.saveParsedTimeTables(parsed);
        const name = store.importCourses(
          parsed.courses,
          result.tapIndex === 0 ? 'overwrite' : 'new',
          parsed.semester ? `${parsed.semester} 导入` : null,
          parsed.semesterStartDate,
          campusMap
        );
        wx.showToast({ title: `已导入到「${name}」`, icon: 'none' });
        setTimeout(() => wx.switchTab({ url: '/pages/timetable/timetable' }), 800);
      },
    });
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
      idToAlias
    );
    wx.setClipboardData({
      data: text,
      success: () => wx.showToast({ title: '已复制课表分享文本', icon: 'none' }),
      fail: () => wx.showToast({ title: '复制失败，请重试', icon: 'none' }),
    });
  },
});
