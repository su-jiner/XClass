/** 主题色板：与 Android 三套主题（暖色/海蓝/薄荷）对齐 + 深色模式 */

const PALETTES = {
  warm: {
    label: '暖色',
    light: { page: '#F7E7CC', panel: '#FFF3E0', panelAlt: '#FFE2BF', text: '#3B2A17', textSecondary: '#8A6A45', icon: '#5A3D1F', nav: '#FFF6E8' },
    dark: { page: '#221A12', panel: '#2E2418', panelAlt: '#3A2C1C', text: '#F3E4D2', textSecondary: '#C3A582', icon: '#E9C89A', nav: '#2A2015' },
  },
  ocean: {
    label: '海蓝',
    light: { page: '#E4EEF9', panel: '#F3F8FF', panelAlt: '#D6E5F7', text: '#16283C', textSecondary: '#4E6A88', icon: '#2E4E72', nav: '#EDF4FC' },
    dark: { page: '#131D2A', panel: '#1B2836', panelAlt: '#243447', text: '#E4EEF9', textSecondary: '#A7BDD4', icon: '#8FB6DC', nav: '#182534' },
  },
  mint: {
    label: '薄荷',
    light: { page: '#E2F1EA', panel: '#F2FBF6', panelAlt: '#D3EADF', text: '#15302A', textSecondary: '#4C7367', icon: '#2D5A4E', nav: '#EAF7F1' },
    dark: { page: '#12211D', panel: '#193029', panelAlt: '#214038', text: '#DFF3EA', textSecondary: '#9CC3B5', icon: '#7FC6AC', nav: '#16251F' },
  },
};

function palette(themeName, dark) {
  const theme = PALETTES[themeName] || PALETTES.warm;
  return dark ? theme.dark : theme.light;
}

module.exports = { PALETTES, palette };
