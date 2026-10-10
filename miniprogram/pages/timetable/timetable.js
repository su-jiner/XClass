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
    rows: [],
    touchStartX: 0,
    rowHeight: 96,
  },
  onShow() {
    if (this.getTabBar && this.getTabBar()) this.getTabBar().setData({ selected: 1 });
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
  /** 与 Android 版一致：左侧 12 节 + 节次时间，右侧 7 列课程卡片，今天列高亮 */
  renderWeek(week) {
    const dark = this.dark;
    const palette = this.data.palette;
    const weekStart = this.weekStartDate(week);
    const today = new Date(store.todayIso() + 'T00:00:00');
    const todayIndex = (today.getDay() === 0 ? 7 : today.getDay()) - 1;
    const showToday = week === this.data.currentWeek;

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

    const cells = [];
    for (let row = 0; row < 12; row += 1) {
      cells.push(new Array(7).fill(null));
    }
    this.courses.forEach((course, courseIndex) => {
      (course.meetings || []).forEach((meeting) => {
        const weekday = core.WEEKDAYS.indexOf(meeting.weekday);
        if (weekday < 0) return;
        let active = core.isActiveInWeek(meeting, week);
        let badge = '';
        if (!active && this.showNonCurrent && week === this.data.currentWeek) {
          const nextWeek = this.nextActiveWeek(meeting, week);
          if (nextWeek) {
            active = true;
            badge = '非本周·第' + nextWeek + '周';
          }
        }
        if (!active) return;
        for (let section = meeting.startSection; section <= meeting.endSection && section <= 12; section += 1) {
          cells[section - 1][weekday] = {
            courseIndex: courseIndex,
            name: course.courseName,
            location: meeting.location || '',
            teacher: course.teacher || '',
            campus: this.campusName(meeting.campusId),
            isStart: section === meeting.startSection,
            isEnd: section === meeting.endSection || section === 12,
            badge: section === meeting.startSection ? badge : '',
            color: theme.courseColor(courseIndex, dark),
            textColor: theme.courseTextColor(courseIndex, dark),
          };
        }
      });
    });

    // 左侧时间列跟随「当前显示时间表」（设置页可切换）
    const displayId = wx.getStorageSync('xclass.defaultTimeProfile') || null;
    const displayProfile = core.resolveProfile(this.profiles, displayId);
    const rows = cells.map((rowCells, rowIndex) => {
      const range = core.timeRangeOf(displayProfile, rowIndex + 1, rowIndex + 1);
      const rendered = rowCells.map((cell, columnIndex) => {
        if (!cell) return { empty: true };
        const isTodayColumn = showToday && columnIndex === todayIndex;
        return {
          empty: false,
          courseIndex: cell.courseIndex,
          name: cell.isStart ? cell.name : '',
          detail: cell.isStart ? [cell.campus, cell.location].filter(Boolean).join(' ') : '',
          badge: cell.badge,
          background: cell.color,
          textColor: cell.textColor,
          isTodayColumn: isTodayColumn,
          radius: (cell.isStart ? '12rpx 12rpx ' : '0 0 ') + (cell.isEnd ? '12rpx 12rpx' : '0 0'),
        };
      });
      return {
        section: rowIndex + 1,
        startTime: range ? range.startTime : '--:--',
        endTime: range ? range.endTime : '--:--',
        cells: rendered,
      };
    });

    const weekdayLabel = WEEK_LABELS[todayIndex];
    this.setData({
      week: week,
      headerWeek: '第' + week + '周 ' + (week === this.data.currentWeek ? '周' + weekdayLabel : '（非本周）'),
      headerDate: store.todayIso().replace(/-/g, '/'),
      monthLabel: (weekStart.getMonth() + 1) + '月',
      dayHeaders: dayHeaders,
      rows: rows,
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
  onCellTap(event) {
    const dataset = event.currentTarget.dataset;
    const row = this.data.rows[dataset.row];
    const cell = row && row.cells[dataset.column];
    if (!cell || cell.empty || !cell.name) return;
    const course = this.courses[cell.courseIndex];
    wx.showModal({
      title: course.courseName,
      content: [
        '教师：' + (course.teacher || '未知'),
        '地点：' + (cell.detail || '未标注'),
        '节次：第' + row.section + '节 ' + row.startTime + '-' + row.endTime,
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
