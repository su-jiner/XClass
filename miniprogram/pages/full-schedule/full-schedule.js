const core = require('../../utils/core.js');
const store = require('../../utils/store.js');
const theme = require('../../utils/theme.js');

const WEEK_LABELS = ['一', '二', '三', '四', '五', '六', '日'];
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
    titleText: '完整课表',
    dayHeaders: [],
    sections: [],
    columns: [],
    empty: false,
    rowHeight: 96,
    scale: 1,
    showManual: false,
    weekdayNames: ['周一', '周二', '周三', '周四', '周五', '周六', '周日'],
    manualForm: {
      name: '',
      weekdayIndex: 0,
      startSection: '1',
      endSection: '2',
      weekText: '1-16周',
      location: '',
      teacher: '',
    },
  },
  onLoad(options) {
    this.profileId = (options && options.profile) || '';
    this.profileName = (options && options.name) || '';
  },
  onShow() {
    if (this.data.statusBarHeight === 24) {
      const info = wx.getSystemInfoSync ? wx.getSystemInfoSync() : {};
      this.setData({ statusBarHeight: info.statusBarHeight || 24 });
    }
    this.reload();
  },
  reload() {
    const prefs = store.loadPrefs();
    const dark = prefs.darkMode === 'dark' || (prefs.darkMode === 'system' && systemDark());
    const palette = theme.palette(prefs.theme, dark);
    this.dark = dark;
    this.courses = store.loadCourses(this.profileId || null);
    this.profiles = store.loadTimeProfiles();
    this.setData({
      palette: palette,
      titleText: this.profileName ? ('完整课表 · ' + this.profileName) : '完整课表',
    });
    this.computeRowHeight();
    const scaleMap = { small: 0.9, normal: 1, large: 1.15 };
    this.setData({ scale: scaleMap[prefs.fontScale] || 1 });
    this.renderAll();
  },
  computeRowHeight() {
    const info = wx.getSystemInfoSync ? wx.getSystemInfoSync() : {};
    const windowWidth = info.windowWidth || 375;
    const windowHeight = info.windowHeight || 667;
    const statusBar = info.statusBarHeight || 24;
    const rpxPerPx = 750 / windowWidth;
    const reservedPx = statusBar + 8 + 62 + 44 + 46 + 56 + 12;
    const availablePx = windowHeight - reservedPx;
    const rowHeight = Math.max(88, Math.min(130, Math.floor((availablePx * rpxPerPx) / 12)));
    this.setData({ rowHeight: rowHeight });
  },
  /** 全周视图：所有周课程都在同一页（与课表页一致的长条列面板样式）。 */
  renderAll() {
    const dark = this.dark;
    const palette = this.data.palette;
    const rowHeight = this.data.rowHeight;

    const dayHeaders = WEEK_LABELS.map((label) => ({
      label: label,
      background: palette.gridHeader,
    }));

    const columns = [];
    for (let day = 0; day < 7; day += 1) {
      columns.push({
        panelBg: palette.gridDay,
        panelHeight: 12 * rowHeight,
        courses: [],
      });
    }
    this.courses.forEach((course, courseIndex) => {
      (course.meetings || []).forEach((meeting) => {
        const weekday = core.WEEKDAYS.indexOf(meeting.weekday);
        if (weekday < 0) return;
        const start = Math.max(1, meeting.startSection);
        const end = Math.min(12, meeting.endSection);
        columns[weekday].courses.push({
          top: (start - 1) * rowHeight + 2,
          height: (end - start + 1) * rowHeight - 4,
          startSection: start,
          courseIndex: courseIndex,
          name: course.courseName,
          location: [this.campusName(meeting.campusId), meeting.location || ''].filter(Boolean).join(' '),
          color: theme.courseColor(courseIndex, dark),
          textColor: theme.courseTextColor(courseIndex, dark),
        });
      });
    });

    const displayId = wx.getStorageSync('xclass.defaultTimeProfile') || null;
    const displayProfile = core.resolveProfile(this.profiles, displayId);
    const sections = [];
    for (let i = 0; i < 12; i += 1) {
      const range = core.timeRangeOf(displayProfile, i + 1, i + 1);
      sections.push({
        section: i + 1,
        startTime: range ? range.startTime : '--:--',
        endTime: range ? range.endTime : '--:--',
      });
    }

    this.setData({
      dayHeaders: dayHeaders,
      sections: sections,
      columns: columns,
      empty: this.courses.length === 0,
    });
  },
  campusName(campusId) {
    if (!campusId) return '';
    const profile = this.profiles.find((item) => item.id === campusId);
    return profile ? profile.name : '';
  },
  goBack() {
    wx.navigateBack({ delta: 1 });
  },
  noop() {},
  onCardTap(event) {
    const dataset = event.currentTarget.dataset;
    const day = dataset.day;
    const courseIndex = dataset.courseIndex;
    const column = this.data.columns[day];
    const card = column && column.courses[courseIndex];
    if (!card) return;
    const course = this.courses[card.courseIndex];
    const range = this.data.sections[card.startSection - 1];
    const that = this;
    wx.showModal({
      title: course.courseName,
      content: [
        '教师：' + (course.teacher || '未知'),
        '地点：' + (card.location || '未标注'),
        '节次：第' + card.startSection + '节 ' + (range ? range.startTime + '-' + range.endTime : ''),
        '周次：' + (course.rawWeekText || ''),
      ].join('\n'),
      showCancel: true,
      cancelText: '编辑课程',
      confirmText: '删除课程',
      confirmColor: '#e64340',
      success: (result) => {
        if (result.cancel) {
          that.editCourse(card.courseIndex);
        } else if (result.confirm) {
          that.deleteCourseConfirm(card.courseIndex);
        }
      },
    });
  },
  editCourse(courseIndex) {
    const course = this.courses[courseIndex];
    if (!course) return;
    const meeting = (course.meetings || [])[0] || {};
    const weekdayIndex = Math.max(0, core.WEEKDAYS.indexOf(meeting.weekday));
    this.editingCourseIndex = courseIndex;
    this.setData({
      showManual: true,
      manualForm: {
        name: course.courseName || '',
        weekdayIndex: weekdayIndex,
        startSection: String(meeting.startSection || 1),
        endSection: String(meeting.endSection || 1),
        weekText: course.rawWeekText || '1-16周',
        location: meeting.location || '',
        teacher: course.teacher || '',
      },
    });
  },
  deleteCourseConfirm(courseIndex) {
    const course = this.courses[courseIndex];
    if (!course) return;
    const that = this;
    wx.showModal({
      title: '删除课程',
      content: '确定从课表删除「' + course.courseName + '」吗？这会删除该课程的全部上课时间。',
      confirmText: '删除',
      confirmColor: '#e64340',
      success: (result) => {
        if (!result.confirm) return;
        const ok = this.profileId
          ? store.deleteCourseInProfile(courseIndex, this.profileId)
          : store.deleteCourse(courseIndex);
        if (ok) {
          wx.showToast({ title: '已删除课程', icon: 'none' });
          that.reload();
        } else {
          wx.showToast({ title: '删除失败，请重试', icon: 'none' });
        }
      },
    });
  },
  onManualField(event) {
    const field = event.currentTarget.dataset.field;
    const value = event.detail.value;
    this.setData({ ['manualForm.' + field]: value });
  },
  onManualWeekday(event) {
    this.setData({ 'manualForm.weekdayIndex': Number(event.detail.value) });
  },
  closeManual() {
    this.setData({ showManual: false });
  },
  saveManual() {
    const form = this.data.manualForm;
    const name = (form.name || '').trim();
    if (!name) {
      wx.showToast({ title: '课程名不能为空', icon: 'none' });
      return;
    }
    const startSection = Math.max(1, Math.min(12, Number(form.startSection) || 1));
    const endSection = Math.max(startSection, Math.min(12, Number(form.endSection) || startSection));
    const sections = core.sectionRangeFromLabel(startSection + '-' + endSection)
      || { startSection: startSection, endSection: endSection };
    const weekText = (form.weekText || '').trim() || '1-16周';
    const old = this.courses[this.editingCourseIndex];
    if (!old) {
      this.setData({ showManual: false });
      return;
    }
    const meetings = (old.meetings || []).slice();
    if (meetings.length > 0) {
      meetings[0] = Object.assign({}, meetings[0], {
        weekday: core.WEEKDAYS[form.weekdayIndex],
        startSection: sections.startSection,
        endSection: sections.endSection,
        location: (form.location || '').trim(),
        weekRules: core.parseWeekRules(weekText),
      });
    } else {
      meetings.push({
        weekday: core.WEEKDAYS[form.weekdayIndex],
        startSection: sections.startSection,
        endSection: sections.endSection,
        weekRules: core.parseWeekRules(weekText),
        location: (form.location || '').trim(),
        campusId: null,
      });
    }
    const updated = Object.assign({}, old, {
      courseName: name,
      teacher: (form.teacher || '').trim(),
      rawWeekText: weekText,
      meetings: meetings,
    });
    const ok = store.updateCourse(this.editingCourseIndex, updated, this.profileId || null);
    this.setData({ showManual: false });
    this.reload();
    wx.showToast({
      title: ok ? '已保存，修改同步到所有周' : '保存失败，请重试',
      icon: 'none',
    });
  },
});
