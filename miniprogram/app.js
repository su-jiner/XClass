const store = require('./utils/store.js');

App({
  globalData: {
    palette: null,
    prefs: null,
  },
  onLaunch() {
    // 首次启动初始化默认课表，并把偏好读入全局
    store.listProfiles();
    this.refreshTheme();
  },
  onShow() {
    this.refreshTheme();
  },
  refreshTheme() {
    const theme = require('./utils/theme.js');
    const prefs = store.loadPrefs();
    const systemDark = wx.getSystemInfoSync
      ? (wx.getSystemInfoSync().theme === 'dark')
      : false;
    const dark = prefs.darkMode === 'dark' || (prefs.darkMode === 'system' && systemDark);
    this.globalData.prefs = prefs;
    this.globalData.palette = theme.palette(prefs.theme, dark);
  },
});
