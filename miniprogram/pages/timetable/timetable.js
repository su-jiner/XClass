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

function formatMonthDay(date) {
  return (date.getMonth() + 1) + '/' + date.getDate();
}

Page({
  data: {
    palette: theme.palette('warm', false),
    statusBarHeight: 24,
    week: 0,
    currentWeek: 1,
    totalWeeks: 20,
    profileName: '',
    headerWeek: '',
    headerDate: '',
    monthLabel: '',
    dayHeaders: [],
    sections: [],
    columns: [],
    touchStartX: 0,
    rowHeight: 96,
    scale: 1,
    showManual: false,
    showPaste: false,
    pasteText: '',
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
    isAllMode: false,
    manualTitle: '手动添加课程',
    allModeHeader: '',
    notes: [],
    empty: false,
  },
  onShow() {
    if (this.getTabBar && this.getTabBar()) this.getTabBar().setData({ selected: 0 });
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
    this.courses = store.loadCourses();
    this.semesterStart = store.getActiveSemesterStart();
    this.profiles = store.loadTimeProfiles();
    this.notes = store.loadProfileNotes();
    this.dark = dark;
    const totalWeeks = core.totalWeeks(this.courses);
    const currentWeek = core.guessCurrentWeek(this.semesterStart, store.todayIso(), totalWeeks);
    const keepWeek = this.data.week ? Math.min(this.data.week, totalWeeks) : currentWeek;
    this.setData({
      palette: palette,
      totalWeeks: totalWeeks,
      currentWeek: currentWeek,
      week: keepWeek,
      profileName: store.activeProfile().name,
      isAllMode: false,
      notes: this.notes,
      empty: this.courses.length === 0,
    });
    this.computeRowHeight();
    const scaleMap = { small: 0.9, normal: 1, large: 1.15 };
    this.setData({ scale: scaleMap[prefs.fontScale] || 1 });
    this.renderWeek(keepWeek);
  },
  /**
   * 与 Android TimetableMetrics 一致：按可用高度算行高，让 12 节全部铺满一屏。
   * 预留：状态栏 + 标题区 + 表头行 + 周切换提示 + 自定义 tabBar。
   */
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
  weekStartDate(week) {
    const start = new Date(this.semesterStart + 'T00:00:00');
    start.setDate(start.getDate() + (week - 1) * 7);
    return start;
  },
  /** 与 Android WeekTimetableRenderer 一致：左侧 12 节时间列 + 右侧 7 个整列长条面板，课程卡在列内绝对定位 */
  renderWeek(week) {
    const dark = this.dark;
    const palette = this.data.palette;
    const weekStart = this.weekStartDate(week);
    const today = new Date(store.todayIso() + 'T00:00:00');
    const todayIndex = (today.getDay() === 0 ? 7 : today.getDay()) - 1;
    const showToday = week === this.data.currentWeek;
    const rowHeight = this.data.rowHeight;

    const dayHeaders = WEEK_LABELS.map((label, index) => {
      const date = new Date(weekStart);
      date.setDate(date.getDate() + index);
      const isToday = showToday && index === todayIndex;
      return {
        label: label,
        date: formatMonthDay(date),
        isToday: isToday,
        background: isToday ? palette.gridHeaderToday : palette.gridHeader,
      };
    });

    // 7 列长条面板：每列一个整块圆角面板，课程卡绝对定位
    const columns = [];
    for (let day = 0; day < 7; day += 1) {
      const isTodayColumn = showToday && day === todayIndex;
      columns.push({
        isTodayColumn: isTodayColumn,
        panelBg: isTodayColumn ? palette.gridDayToday : palette.gridDay,
        panelHeight: 12 * rowHeight,
        courses: [],
      });
    }
    this.courses.forEach((course, courseIndex) => {
      (course.meetings || []).forEach((meeting) => {
        const weekday = core.WEEKDAYS.indexOf(meeting.weekday);
        if (weekday < 0) return;
        // 只显示本周课程：非本周课程一律不渲染
        if (!core.isActiveInWeek(meeting, week)) return;
        const start = Math.max(1, meeting.startSection);
        const end = Math.min(12, meeting.endSection);
        columns[weekday].courses.push({
          top: (start - 1) * rowHeight + 2,
          height: (end - start + 1) * rowHeight - 4,
          startSection: start,
          courseIndex: courseIndex,
          name: course.courseName,
          location: [this.campusName(meeting.campusId), meeting.location || ''].filter(Boolean).join(' '),
          badge: '',
          dimmed: false,
          color: theme.courseColor(courseIndex, dark),
          textColor: theme.courseTextColor(courseIndex, dark),
        });
      });
    });

    // 左侧时间列（跟随「当前显示时间表」）
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

    const weekdayLabel = WEEK_LABELS[todayIndex];
    this.setData({
      week: week,
      headerWeek: '第' + week + '周 ' + (week === this.data.currentWeek ? '周' + weekdayLabel : '（非本周）'),
      headerDate: store.todayIso().replace(/-/g, '/'),
      monthLabel: (weekStart.getMonth() + 1) + '月',
      dayHeaders: dayHeaders,
      sections: sections,
      columns: columns,
    });
  },
  nextActiveWeek(meeting, week) {
    for (let candidate = week + 1; candidate <= week + 30; candidate += 1) {
      if (core.isActiveInWeek(meeting, candidate)) return candidate;
    }
    return null;
  },
  campusName(campusId) {
    if (!campusId) return '';
    const profile = this.profiles.find((item) => item.id === campusId);
    return profile ? profile.name : '';
  },
  goPrevWeek() {
    const target = Math.max(1, this.data.week - 1);
    if (target !== this.data.week) this.renderWeek(target);
  },
  goNextWeek() {
    const target = Math.min(this.data.totalWeeks, this.data.week + 1);
    if (target !== this.data.week) this.renderWeek(target);
  },
  goCurrentWeek() {
    if (this.data.week !== this.data.currentWeek) this.renderWeek(this.data.currentWeek);
  },
  onTouchStart(event) {
    this.setData({ touchStartX: event.touches[0].clientX });
  },
  onTouchEnd(event) {
    const deltaX = event.changedTouches[0].clientX - this.data.touchStartX;
    if (Math.abs(deltaX) < 60) return;
    if (deltaX < 0) this.goNextWeek(); else this.goPrevWeek();
  },
  onCardTap(event) {
    const dataset = event.currentTarget.dataset;
    const day = dataset.day;
    const courseIndex = dataset.courseIndex;
    const column = this.data.columns[day];
    const card = column && column.courses[courseIndex];
    if (!card) return;
    const course = this.courses[card.courseIndex];
    const range = this.data.sections[card.startSection - 1];
    const content = [
      '教师：' + (course.teacher || '未知'),
      '地点：' + (card.location || '未标注'),
      '节次：第' + card.startSection + '节 ' + (range ? range.startTime + '-' + range.endTime : ''),
      '周次：' + (course.rawWeekText || ''),
    ].join('\n');
    const that = this;
    wx.showModal({
      title: course.courseName,
      content: content,
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
  /** 打开编辑表单：预填课程信息，保存时更新课程定义（所有周同步）。 */
  editCourse(courseIndex) {
    const course = this.courses[courseIndex];
    if (!course) return;
    const meeting = (course.meetings || [])[0] || {};
    const weekdayIndex = Math.max(0, core.WEEKDAYS.indexOf(meeting.weekday));
    this.editingCourseIndex = courseIndex;
    this.setData({
      showManual: true,
      manualTitle: '编辑课程',
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
        const ok = this.viewProfileId
          ? store.deleteCourseInProfile(courseIndex, this.viewProfileId)
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
  onAddTap() {
    wx.showActionSheet({
      itemList: ['手动添加课程', '导入课表文件/文本', '粘贴文本导入'],
      success: (result) => {
        if (result.tapIndex === 0) this.showManualDialog();
        if (result.tapIndex === 1) wx.navigateTo({ url: '/pages/import/import?mode=file' });
        if (result.tapIndex === 2) this.showPasteDialog();
      },
    });
  },
  /** 弹窗内容区点击占位：阻止冒泡到遮罩层，避免误关弹窗。 */
  noop() {},
  showPasteDialog() {
    this.setData({ showPaste: true, pasteText: '' });
  },
  closePaste() {
    this.setData({ showPaste: false });
  },
  onPasteInput(event) {
    this.setData({ pasteText: event.detail.value });
  },
  doPasteParse() {
    const text = (this.data.pasteText || '').trim();
    if (!text) {
      wx.showToast({ title: '请先粘贴课表文本', icon: 'none' });
      return;
    }
    getApp().globalData.pendingImportText = text;
    this.setData({ showPaste: false });
    wx.navigateTo({ url: '/pages/import/import?mode=paste&auto=1' });
  },
  /** 导出当前课表为 XClass 分享文本：复制到剪贴板，朋友粘贴到任意端即可导入。 */
  exportShareTextFromDialog() {
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
      success: () => wx.showToast({ title: '已复制课表分享文本，可直接粘贴给朋友', icon: 'none' }),
      fail: () => wx.showToast({ title: '复制失败，请重试', icon: 'none' }),
    });
  },
  showManualDialog() {
    this.editingCourseIndex = -1;
    this.setData({
      showManual: true,
      manualTitle: '手动添加课程',
      manualForm: {
        name: '',
        weekdayIndex: 0,
        startSection: '1',
        endSection: '2',
        weekText: '1-16周',
        location: '',
        teacher: '',
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
    if (this.editingCourseIndex >= 0) {
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
      const ok = store.updateCourse(this.editingCourseIndex, updated, this.viewProfileId);
      this.setData({ showManual: false });
      this.reload();
      wx.showToast({
        title: ok ? '已保存，修改同步到所有周' : '保存失败，请重试',
        icon: 'none',
      });
      return;
    }
    store.addManualCourse({
      courseName: name,
      teacher: (form.teacher || '').trim(),
      location: (form.location || '').trim(),
      weekday: core.WEEKDAYS[form.weekdayIndex],
      startSection: sections.startSection,
      endSection: sections.endSection,
      weekText: weekText,
    });
    this.setData({ showManual: false });
    this.reload();
    wx.showToast({ title: '已添加到当前课表', icon: 'none' });
  },
});
