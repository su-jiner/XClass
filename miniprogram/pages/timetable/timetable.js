const core = require('../../utils/core.js');
const store = require('../../utils/store.js');
const theme = require('../../utils/theme.js');

const ROW_LABELS = ['1-2', '3-4', '5-6', '7-8', '9-10', '11-12'];
const WEEK_LABELS = ['一', '二', '三', '四', '五', '六', '日'];

Page({
  data: {
    palette: theme.palette('warm', false),
    weeks: 20,
    currentWeek: 1,
    weekIndex: 0,
    rowLabels: ROW_LABELS,
    columnLabels: WEEK_LABELS,
    grids: [],
    sectionRanges: [],
    profileName: '',
  },
  onShow() {
    if (this.getTabBar && this.getTabBar()) this.getTabBar().setData({ selected: 1 });
    this.reload();
  },
  reload() {
    const prefs = store.loadPrefs();
    const systemDark = wx.getSystemInfoSync && wx.getSystemInfoSync().theme === 'dark';
    const dark = prefs.darkMode === 'dark' || (prefs.darkMode === 'system' && systemDark);
    const palette = theme.palette(prefs.theme, dark);
    const courses = store.loadCourses();
    const semesterStart = store.getActiveSemesterStart();
    const totalWeeks = core.totalWeeks(courses);
    const currentWeek = core.guessCurrentWeek(semesterStart, store.todayIso(), totalWeeks);
    const profiles = store.loadTimeProfiles();
    const grids = [];
    for (let week = 1; week <= totalWeeks; week += 1) grids.push(core.weekGrid(courses, week).cells);
    this.setData({
      palette: palette,
      weeks: totalWeeks,
      currentWeek: currentWeek,
      weekIndex: currentWeek - 1,
      grids: grids,
      sectionRanges: core.sectionRanges(core.resolveProfile(profiles, null)),
      profileName: store.activeProfile().name,
    });
  },
  onWeekChange(event) {
    this.setData({ weekIndex: event.detail.current });
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
    const weekdayList = ['周一', '周二', '周三', '周四', '周五', '周六', '周日'];
    wx.showActionSheet({
      itemList: weekdayList,
      success: (result) => {
        const weekday = core.WEEKDAYS[result.tapIndex];
        this.pendingWeekday = weekday;
        wx.showModal({
          title: '手动添加课程',
          editable: true,
          placeholderText: '课程名|节次|地点|教师，例如：高等数学/1-2/教三302/张三',
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
