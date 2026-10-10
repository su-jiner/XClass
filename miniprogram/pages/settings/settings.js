const core = require('../../utils/core.js');
const store = require('../../utils/store.js');
const theme = require('../../utils/theme.js');

Page({
  data: {
    palette: theme.palette('warm', false),
    statusBarHeight: 24,
    prefs: {},
    themes: [['warm', '暖色'], ['ocean', '海蓝'], ['mint', '薄荷'], ['sakura', '樱粉'], ['midnight', '墨夜']],
    darkModes: [['system', '跟随系统'], ['light', '浅色'], ['dark', '深色']],
    fontScales: [['small', '小号'], ['normal', '标准'], ['large', '大号']],
    defaultPages: [['timetable', '课表'], ['today', '日程']],
    profiles: [],
    timeProfiles: [],
    defaultTimeProfileId: 'default',
    version: '1.1.0-Beta',
  },
  onShow() {
    if (this.getTabBar && this.getTabBar()) this.getTabBar().setData({ selected: 3 });
    if (this.data.statusBarHeight === 24) {
      const info = wx.getSystemInfoSync ? wx.getSystemInfoSync() : {};
      this.setData({ statusBarHeight: info.statusBarHeight || 24 });
    }
    this.reload();
  },
  reload() {
    const prefs = store.loadPrefs();
    const systemDark = wx.getSystemInfoSync && wx.getSystemInfoSync().theme === 'dark';
    const dark = prefs.darkMode === 'dark' || (prefs.darkMode === 'system' && systemDark);
    this.setData({
      palette: theme.palette(prefs.theme, dark),
      prefs: prefs,
      profiles: store.listProfiles(),
      timeProfiles: store.loadTimeProfiles(),
      defaultTimeProfileId: wx.getStorageSync('xclass.defaultTimeProfile') || 'default',
    });
  },
  updatePrefs(event) {
    const field = event.currentTarget.dataset.field;
    const value = event.currentTarget.dataset.value;
    const patch = {};
    patch[field] = value;
    store.savePrefs(patch);
    this.reload();
  },
  updateFontScale(event) {
    store.savePrefs({ fontScale: event.currentTarget.dataset.value });
    this.reload();
  },
  updateDefaultPage(event) {
    store.savePrefs({ defaultPage: event.currentTarget.dataset.value });
    this.reload();
  },
  toggleNonCurrent(event) {
    store.savePrefs({ showNonCurrent: event.detail.value });
    this.reload();
  },
  switchProfile(event) {
    store.setActiveProfile(event.currentTarget.dataset.id);
    this.reload();
    wx.showToast({ title: '已切换课表', icon: 'none' });
  },
  renameProfile(event) {
    const id = event.currentTarget.dataset.id;
    wx.showModal({
      title: '重命名课表',
      editable: true,
      success: (result) => {
        if (result.confirm) {
          store.renameProfile(id, result.content);
          this.reload();
        }
      },
    });
  },
  deleteProfile(event) {
    const id = event.currentTarget.dataset.id;
    wx.showModal({
      title: '删除课表',
      content: '确认删除该课表？',
      success: (result) => {
        if (!result.confirm) return;
        const ok = store.deleteProfile(id);
        if (!ok) wx.showToast({ title: '至少保留一个课表', icon: 'none' });
        this.reload();
      },
    });
  },
  createProfile() {
    wx.showModal({
      title: '新建课表',
      editable: true,
      success: (result) => {
        if (result.confirm) {
          store.createProfile(result.content);
          this.reload();
        }
      },
    });
  },
  exportBackup() {
    const path = store.exportBackup();
    if (wx.shareFileMessage) {
      wx.shareFileMessage({
        filePath: path,
        success: () => wx.showToast({ title: '已发送备份', icon: 'none' }),
        fail: () => wx.showToast({ title: `备份已生成：${path}`, icon: 'none' }),
      });
    } else {
      wx.setClipboardData({ data: path });
    }
  },
  importBackup() {
    wx.chooseMessageFile({
      count: 1,
      type: 'file',
      extension: ['json'],
      success: (result) => {
        try {
          const content = store.readFileText(result.tempFiles[0].path);
          const count = store.importBackup(content);
          if (count > 0) {
            wx.showToast({ title: `已恢复 ${count} 个课表`, icon: 'none' });
            this.reload();
          } else {
            wx.showToast({ title: '备份文件格式不正确', icon: 'none' });
          }
        } catch (error) {
          wx.showToast({ title: '读取备份失败', icon: 'none' });
        }
      },
    });
  },
  deleteTimeProfile(event) {
    const id = event.currentTarget.dataset.id;
    const profiles = store.loadTimeProfiles().filter((profile) => profile.id !== id);
    store.saveTimeProfiles(profiles, store.loadCampusMap(), null);
    this.reload();
  },
  setDefaultTimeProfile(event) {
    const id = event.currentTarget.dataset.id;
    wx.setStorageSync('xclass.defaultTimeProfile', id);
    wx.showToast({ title: '已设为当前显示', icon: 'none' });
    this.reload();
  },
  addTimeProfileByText() {
    wx.showModal({
      title: '识别节次时间表',
      editable: true,
      placeholderText: '粘贴如：真理道校区上课时间 / 34节 | 10:25-12:00',
      success: (result) => {
        if (!result.confirm || !result.content) return;
        const profiles = core.parseTimeTables(result.content);
        if (!profiles.length) {
          wx.showToast({ title: '未识别到时间表', icon: 'none' });
          return;
        }
        store.saveParsedTimeTables({ timeTables: profiles, campusAliases: [] });
        this.reload();
        wx.showToast({ title: `已添加 ${profiles.length} 套时间表`, icon: 'none' });
      },
    });
  },
  checkUpdate() {
    wx.setClipboardData({
      data: 'https://github.com/su-jiner/XClass/releases',
      success: () => wx.showToast({ title: '已复制版本下载页链接', icon: 'none' }),
    });
  },
  writeCalendar() {
    const courses = store.loadCourses();
    const semesterStart = store.getActiveSemesterStart();
    const totalWeeks = core.totalWeeks(courses);
    const week = core.guessCurrentWeek(semesterStart, store.todayIso(), totalWeeks);
    const meetings = core.activeMeetingsInWeek(courses, week);
    if (!meetings.length) {
      wx.showToast({ title: '本周没有课程', icon: 'none' });
      return;
    }
    let index = 0;
    const addNext = () => {
      if (index >= meetings.length) {
        wx.showToast({ title: '本周课程已写入日历', icon: 'none' });
        return;
      }
      const item = meetings[index];
      index += 1;
      // 本周周一 = 学期第一周周一 + (当前周 - 1) * 7 天；再按星期偏移
      const weekStart = new Date(`${semesterStart}T00:00:00`);
      weekStart.setDate(weekStart.getDate() + (week - 1) * 7);
      const target = new Date(weekStart);
      target.setDate(weekStart.getDate() + (core.WEEKDAYS.indexOf(item.meeting.weekday)));
      const range = core.timeRangeOf(
        core.resolveProfile(store.loadTimeProfiles(), item.meeting.campusId),
        item.meeting.startSection,
        item.meeting.endSection
      );
      const startMinutes = range ? Number(String(range.startTime).split(':')[0]) * 60 + Number(String(range.startTime).split(':')[1]) : 8 * 60;
      target.setHours(Math.floor(startMinutes / 60), startMinutes % 60, 0, 0);
      const duration = range
        ? Math.max(45 * 60, (Number(String(range.endTime).split(':')[0]) * 60 + Number(String(range.endTime).split(':')[1])) - startMinutes)
        : 90 * 60;
      if (wx.addPhoneCalendar) {
        wx.addPhoneCalendar({
          title: item.course.courseName,
          location: item.meeting.location,
          startTime: Math.floor(target.getTime() / 1000),
          endTime: Math.floor(target.getTime() / 1000) + duration,
          alarm: true,
          alarmOffset: 600,
          success: addNext,
          fail: addNext,
        });
      } else {
        wx.showToast({ title: '当前基础库不支持写入日历', icon: 'none' });
      }
    };
    addNext();
  },
});
