const store = require('../utils/store.js');
const theme = require('../utils/theme.js');

Component({
  data: {
    selected: 1,
    palette: theme.palette('warm', false),
    list: [
      { pagePath: '/pages/today/today', text: '日程', icon: '☀' },
      { pagePath: '/pages/timetable/timetable', text: '课表', icon: '▦' },
      { pagePath: '/pages/tools/tools', text: '工具', icon: '⚙' },
      { pagePath: '/pages/settings/settings', text: '设置', icon: '☰' },
    ],
  },
  attached() {
    const prefs = store.loadPrefs();
    const systemDark = wx.getSystemInfoSync && wx.getSystemInfoSync().theme === 'dark';
    const dark = prefs.darkMode === 'dark' || (prefs.darkMode === 'system' && systemDark);
    this.setData({ palette: theme.palette(prefs.theme, dark) });
  },
  methods: {
    switchTab(event) {
      const index = Number(event.currentTarget.dataset.index);
      wx.switchTab({ url: this.data.list[index].pagePath });
    },
  },
});
