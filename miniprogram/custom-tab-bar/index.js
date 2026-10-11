const store = require('../utils/store.js');
const theme = require('../utils/theme.js');

let cachedDark = null;
function systemDark() {
  if (cachedDark == null) {
    const info = wx.getSystemInfoSync ? wx.getSystemInfoSync() : {};
    cachedDark = info.theme === 'dark';
  }
  return cachedDark;
}

const ICONS = {
  today: '<circle cx="12" cy="12" r="8.5" fill="none" stroke="COLOR" stroke-width="2"/>'
    + '<path d="M12 7.2v5l3.2 2" fill="none" stroke="COLOR" stroke-width="2" stroke-linecap="round"/>',
  timetable: '<rect x="3.6" y="5" width="16.8" height="15" rx="3" fill="none" stroke="COLOR" stroke-width="2"/>'
    + '<path d="M3.6 9.6h16.8" stroke="COLOR" stroke-width="2"/>'
    + '<path d="M8 3.6v3M16 3.6v3" stroke="COLOR" stroke-width="2" stroke-linecap="round"/>',
  tools: '<path d="M20.6 6.4a4.6 4.6 0 01-6.1 4.2l-7.2 7.2a1.9 1.9 0 11-2.7-2.7l7.2-7.2A4.6 4.6 0 0118.2 2.6l-2.8 2.8 2.4 2.4 2.8-2.8z" fill="COLOR"/>',
  settings: '<path d="M12 2.6l1.5 2.5 2.9-.6 1 2.8 2.8 1-.6 2.9 2 1.8-2 1.8.6 2.9-2.8 1-1 2.8-2.9-.6L12 21.4l-1.5-2.5-2.9.6-1-2.8-2.8-1 .6-2.9L2.4 12l2-1.8-.6-2.9 2.8-1 1-2.8 2.9.6z" fill="COLOR"/>'
    + '<circle cx="12" cy="12" r="3.1" fill="none" stroke="BGC" stroke-width="2"/>',
};

function iconUri(name, color, bg) {
  const svg = '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24">'
    + ICONS[name].replace(/COLOR/g, color).replace(/BGC/g, bg || 'none')
    + '</svg>';
  return 'data:image/svg+xml,' + encodeURIComponent(svg);
}

Component({
  data: {
    selected: 1,
    palette: theme.palette('warm', false),
    list: [],
  },
  attached() {
    const prefs = store.loadPrefs();
    const dark = prefs.darkMode === 'dark' || (prefs.darkMode === 'system' && systemDark());
    const palette = theme.palette(prefs.theme, dark);
    const defs = [
      { pagePath: '/pages/timetable/timetable', text: '课表', icon: 'timetable' },
      { pagePath: '/pages/today/today', text: '日程', icon: 'today' },
      { pagePath: '/pages/tools/tools', text: '工具', icon: 'tools' },
      { pagePath: '/pages/settings/settings', text: '设置', icon: 'settings' },
    ];
    const list = defs.map((item) => ({
      pagePath: item.pagePath,
      text: item.text,
      iconNormal: iconUri(item.icon, palette.textSecondary, palette.panelAlt),
      iconActive: iconUri(item.icon, palette.iconTint, palette.panelAlt),
    }));
    this.setData({ palette: palette, dark: dark, list: list });
  },
  methods: {
    switchTab(event) {
      const index = Number(event.currentTarget.dataset.index);
      if (index === this.data.selected) return;
      wx.switchTab({ url: this.data.list[index].pagePath });
    },
  },
});
