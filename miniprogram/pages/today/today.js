const core = require('../../utils/core.js');
const store = require('../../utils/store.js');
const theme = require('../../utils/theme.js');

Page({
  data: {
    palette: theme.palette('warm', false),
    snapshot: null,
    countdown: '',
    reminderEnabled: true,
  },
  onShow() {
    if (this.getTabBar && this.getTabBar()) this.getTabBar().setData({ selected: 0 });
    this.reload();
  },
  reload() {
    const prefs = store.loadPrefs();
    const systemDark = wx.getSystemInfoSync && wx.getSystemInfoSync().theme === 'dark';
    const dark = prefs.darkMode === 'dark' || (prefs.darkMode === 'system' && systemDark);
    const courses = store.loadCourses();
    const snapshot = core.todaySnapshot(courses, store.getActiveSemesterStart(), store.loadTimeProfiles(), store.todayIso());
    const next = snapshot.ongoingCourse || snapshot.nextCourse;
    this.setData({
      palette: theme.palette(prefs.theme, dark),
      snapshot: snapshot,
      reminderEnabled: prefs.reminderEnabled,
      countdown: next
        ? (snapshot.ongoingCourse
          ? `进行中 · ${next.endTime} 下课`
          : `${next.startTime} 开始 · ${core.countdownText(next.minutesUntilStart)}`)
        : '今天没有课程安排',
    });
  },
  openImport() {
    wx.navigateTo({ url: '/pages/import/import' });
  },
});
