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
    showDetail: false,
    detailCourses: [],
    detailIndex: 0,
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
    // 最后一节（第 12 节）额外加长：多行课程内容（如「教室待定」）完整显示，而非靠间距留白
    const lastRowExtra = Math.round(rowHeight * 0.38);
    this.setData({ rowHeight: rowHeight, lastRowExtra: lastRowExtra });
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
    const lastRowExtra = this.data.lastRowExtra || 0;

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
        panelHeight: 12 * rowHeight + lastRowExtra,
        courses: [],
      });
    }
    this.courses.forEach((course, courseIndex) => {
      (course.meetings || []).forEach((meeting, meetingIndex) => {
        const weekday = core.WEEKDAYS.indexOf(meeting.weekday);
        if (weekday < 0) return;
        // 只显示本周课程：非本周课程一律不渲染
        if (!core.isActiveInWeek(meeting, week)) return;
        const start = Math.max(1, meeting.startSection);
        const end = Math.min(12, meeting.endSection);
        columns[weekday].courses.push({
          top: (start - 1) * rowHeight + 2,
          height: (end - start + 1) * rowHeight - 4 + (end >= 12 ? lastRowExtra : 0),
          startSection: start,
          endSection: end,
          courseIndex: courseIndex,
          meetingIndex: meetingIndex,
          customStartTime: meeting.customStartTime || '',
          customEndTime: meeting.customEndTime || '',
          name: course.courseName,
          location: [this.campusName(meeting.campusId), meeting.location || ''].filter(Boolean).join(' '),
          badge: '',
          dimmed: false,
          color: theme.courseColor(courseIndex, dark),
          textColor: theme.courseTextColor(courseIndex, dark),
        });
      });
    });

    // 左侧时间列（跟随「当前显示时间表」）：固定编码时间，1-12 节始终显示起止时间
    const displayId = wx.getStorageSync('xclass.defaultTimeProfile') || null;
    const displayProfile = core.resolveProfile(this.profiles, displayId);
    const sections = [];
    for (let i = 0; i < 12; i += 1) {
      const range = core.timeRangeOf(displayProfile, i + 1, i + 1);
      sections.push({
        section: i + 1,
        startTime: range ? range.startTime : '',
        endTime: range ? range.endTime : '',
        height: (i === 11 ? rowHeight + lastRowExtra : rowHeight),
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
    // 收集同格子重叠课程组：节次区间相交且课程不同的卡，按纵向位置排序
    const members = column.courses.filter((c) => {
      const sectionOverlap = c.startSection <= (card.endSection || card.startSection)
        && (card.startSection) <= (c.endSection || c.startSection);
      return sectionOverlap && c.courseIndex !== card.courseIndex;
    });
    const allMembers = [card].concat(members).sort((a, b) => a.top - b.top);
    const tappedIndex = allMembers.findIndex((m) => m.courseIndex === card.courseIndex && m.startSection === card.startSection);
    const detailCourses = allMembers.map((m) => {
      const c = this.courses[m.courseIndex];
      const range = this.data.sections[m.startSection - 1];
      const time = m.customStartTime ? (m.customStartTime + '-' + (m.customEndTime || ''))
        : (range ? (range.startTime + '-' + range.endTime) : '');
      return {
        courseIndex: m.courseIndex,
        meetingIndex: m.meetingIndex || 0,
        name: c ? (c.courseName || '') : '',
        teacher: c ? (c.teacher || '未知') : '未知',
        location: m.location || '未标注',
        sectionText: '第' + m.startSection + '-' + (m.endSection || m.startSection) + '节',
        timeText: time,
        weekText: c ? (c.rawWeekText || '未标注') : '未标注',
      };
    });
    this.setData({
      showDetail: true,
      detailCourses: detailCourses,
      detailIndex: Math.max(0, tappedIndex),
    });
  },
  onDetailChange(event) {
    this.setData({ detailIndex: event.detail.current });
  },
  closeDetail() {
    this.setData({ showDetail: false });
  },
  editDetail() {
    const item = this.data.detailCourses[this.data.detailIndex];
    this.setData({ showDetail: false });
    if (item) this.editCourse(item.courseIndex, item.meetingIndex);
  },
  deleteDetail() {
    const item = this.data.detailCourses[this.data.detailIndex];
    this.setData({ showDetail: false });
    if (item) this.deleteCourseConfirm(item.courseIndex);
  },
  /** 打开编辑表单：预填课程信息，保存时更新课程定义（所有周同步）。meetingIndex 指定被编辑的上课时段。 */
  editCourse(courseIndex, meetingIndex) {
    const course = this.courses[courseIndex];
    if (!course) return;
    const meetings = course.meetings || [];
    const meeting = meetings[meetingIndex || 0] || meetings[0] || {};
    const weekdayIndex = Math.max(0, core.WEEKDAYS.indexOf(meeting.weekday));
    this.editingCourseIndex = courseIndex;
    this.editingMeetingIndex = Math.min(meetingIndex || 0, Math.max(0, meetings.length - 1));
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
        customStart: meeting.customStartTime || '',
        customEnd: meeting.customEndTime || '',
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
        customStart: '',
        customEnd: '',
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
      const meetingIndex = Math.min(this.editingMeetingIndex || 0, Math.max(0, meetings.length - 1));
      // 自定义上课时间：两个都填才生效，校验 HH:mm 且开始早于结束
      const customStart = (form.customStart || '').trim();
      const customEnd = (form.customEnd || '').trim();
      let customStartTime = null;
      let customEndTime = null;
      if (customStart || customEnd) {
        if (!/^([01]\d|2[0-3]):[0-5]\d$/.test(customStart) || !/^([01]\d|2[0-3]):[0-5]\d$/.test(customEnd)) {
          wx.showToast({ title: '时间格式应为 HH:mm', icon: 'none' });
          return;
        }
        if (customStart >= customEnd) {
          wx.showToast({ title: '结束时间须晚于开始时间', icon: 'none' });
          return;
        }
        customStartTime = customStart;
        customEndTime = customEnd;
      }
      const editedMeeting = {
        weekday: core.WEEKDAYS[form.weekdayIndex],
        startSection: sections.startSection,
        endSection: sections.endSection,
        location: (form.location || '').trim(),
        weekRules: core.parseWeekRules(weekText),
        customStartTime: customStartTime,
        customEndTime: customEndTime,
      };
      const nameChanged = name !== (old.courseName || '');
      const teacherChanged = (form.teacher || '').trim() !== (old.teacher || '');
      // 拆分保存：同名同师课程含多个上课时段且改了名/师时，只改被编辑时段，
      // 其余时段（其他天）保留原名原师，避免跨天绑定连带修改
      if (meetings.length > 1 && (nameChanged || teacherChanged)) {
        const keptMeetings = meetings.slice();
        keptMeetings.splice(meetingIndex, 1);
        const keptCourse = Object.assign({}, old, {
          courseName: old.courseName,
          teacher: old.teacher,
          rawWeekText: old.rawWeekText || '',
          meetings: keptMeetings,
        });
        const extractedCourse = Object.assign({}, old, {
          courseName: name,
          teacher: (form.teacher || '').trim(),
          rawWeekText: weekText,
          meetings: [Object.assign({}, meetings[meetingIndex] || {}, editedMeeting)],
        });
        const ok = store.updateCourse(this.editingCourseIndex, keptCourse, this.viewProfileId)
          && store.appendCourse(extractedCourse, this.viewProfileId);
        this.setData({ showManual: false });
        this.reload();
        wx.showToast({
          title: ok ? '已保存，仅修改该上课时间' : '保存失败，请重试',
          icon: 'none',
        });
        return;
      }
      if (meetings.length > 0) {
        meetings[meetingIndex] = Object.assign({}, meetings[meetingIndex] || {}, editedMeeting);
      } else {
        meetings.push(Object.assign({}, editedMeeting, { campusId: null }));
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
