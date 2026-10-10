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
    this.dark = dark;
    this.showNonCurrent = prefs.showNonCurrent !== false;
    const totalWeeks = core.totalWeeks(this.courses);
    const currentWeek = core.guessCurrentWeek(this.semesterStart, store.todayIso(), totalWeeks);
    const keepWeek = this.data.week ? Math.min(this.data.week, totalWeeks) : currentWeek;
    this.setData({
      palette: palette,
      totalWeeks: totalWeeks,
      currentWeek: currentWeek,
      week: keepWeek,
      profileName: store.activeProfile().name,
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
        let active = core.isActiveInWeek(meeting, week);
        let badge = '';
        let dimmed = false;
        if (!active && this.showNonCurrent && week === this.data.currentWeek) {
          const nextWeek = this.nextActiveWeek(meeting, week);
          if (nextWeek) {
            active = true;
            badge = '非本周·第' + nextWeek + '周';
            dimmed = true;
          }
        }
        if (!active) return;
        const start = Math.max(1, meeting.startSection);
        const end = Math.min(12, meeting.endSection);
        columns[weekday].courses.push({
          top: (start - 1) * rowHeight + 2,
          height: (end - start + 1) * rowHeight - 4,
          startSection: start,
          courseIndex: courseIndex,
          name: course.courseName,
          location: [this.campusName(meeting.campusId), meeting.location || ''].filter(Boolean).join(' '),
          badge: badge,
          dimmed: dimmed,
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
    wx.showModal({
      title: course.courseName,
      content: [
        '教师：' + (course.teacher || '未知'),
        '地点：' + (card.location || '未标注'),
        '节次：第' + card.startSection + '节 ' + (range ? range.startTime + '-' + range.endTime : ''),
        '周次：' + (course.rawWeekText || ''),
      ].join('\n'),
      showCancel: false,
      confirmText: '知道了',
    });
  },
  onAddTap() {
    wx.showActionSheet({
      itemList: ['手动添加课程', '导入课表文件/文本', '粘贴文本导入'],
      success: (result) => {
        if (result.tapIndex === 0) this.showManualDialog();
        if (result.tapIndex === 1) wx.navigateTo({ url: '/pages/import/import?mode=file' });
        if (result.tapIndex === 2) wx.navigateTo({ url: '/pages/import/import?mode=paste' });
      },
    });
  },
  showManualDialog() {
    wx.showActionSheet({
      itemList: ['周一', '周二', '周三', '周四', '周五', '周六', '周日'],
      success: (result) => {
        const weekday = core.WEEKDAYS[result.tapIndex];
        wx.showModal({
          title: '手动添加课程',
          editable: true,
          placeholderText: '课程名/节次/地点/教师，如：高等数学/1-2/教三302/张三',
          success: (modal) => {
            if (!modal.confirm || !modal.content) return;
            const parts = modal.content.split(/[\/|｜]/).map((item) => item.trim());
            const sections = core.sectionRangeFromLabel(parts[1] || '1-2') || { startSection: 1, endSection: 2 };
            store.addManualCourse({
              courseName: parts[0] || '未命名课程',
              teacher: parts[3] || '',
              location: parts[2] || '',
              weekday: weekday,
              startSection: sections.startSection,
              endSection: sections.endSection,
              weekText: '1-16周',
            });
            this.reload();
            wx.showToast({ title: '已添加到当前课表', icon: 'none' });
          },
        });
      },
    });
  },
});
