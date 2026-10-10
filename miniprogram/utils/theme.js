/**
 * 主题色板：与 Android 端 values/colors.xml、values-night/colors.xml 逐色对齐。
 * 半透明色（导航栏）从 ARGB 转为 CSS rgba。
 */

function rgba(argb) {
  const alpha = parseInt(argb.slice(1, 3), 16) / 255;
  const r = parseInt(argb.slice(3, 5), 16);
  const g = parseInt(argb.slice(5, 7), 16);
  const b = parseInt(argb.slice(7, 9), 16);
  return 'rgba(' + r + ',' + g + ',' + b + ',' + alpha.toFixed(2) + ')';
}

const THEMES = {
  warm: {
    label: '暖色',
    light: {
      page: '#F7E7CC', nav: rgba('#D9FFF2DA'), panel: '#FFE2BF', panelAlt: '#FFEED8',
      textPrimary: '#5C3C1E', textSecondary: '#7A5A37', iconTint: '#5A3D1F',
      buttonBackground: '#EFD7B1', buttonText: '#3A2A1A',
      gridHeader: '#FFE9C9', gridHeaderToday: '#FFD7B5',
      gridLeft: '#FFEFD6', gridDay: '#FFF5E6', gridDayToday: '#FFE8CD',
      isDark: false
    },
    dark: {
      page: '#221A12', nav: rgba('#F01A140D'), panel: '#33281C', panelAlt: '#2B2116',
      textPrimary: '#F3E7D3', textSecondary: '#C9B697', iconTint: '#E8D5B5',
      buttonBackground: '#4A3924', buttonText: '#F3E7D3',
      gridHeader: '#3A2C1B', gridHeaderToday: '#4C3A22',
      gridLeft: '#2E2315', gridDay: '#271E12', gridDayToday: '#342819',
      isDark: true
    }
  },
  ocean: {
    label: '海蓝',
    light: {
      page: '#EAF5FF', nav: rgba('#D9DCEEFF'), panel: '#E1F1FF', panelAlt: '#F0F8FF',
      textPrimary: '#1F3A56', textSecondary: '#3E5876', iconTint: '#2E4E72',
      buttonBackground: '#D6E7FA', buttonText: '#1F3A56',
      gridHeader: '#DCEEFF', gridHeaderToday: '#BFDFFF',
      gridLeft: '#E8F4FF', gridDay: '#F2F8FF', gridDayToday: '#D6EBFF',
      isDark: false
    },
    dark: {
      page: '#101B26', nav: rgba('#F00D1722'), panel: '#1B2C3D', panelAlt: '#16232F',
      textPrimary: '#DDEAF6', textSecondary: '#A3BDD3', iconTint: '#BAD2E8',
      buttonBackground: '#274058', buttonText: '#DDEAF6',
      gridHeader: '#1E3247', gridHeaderToday: '#2A4560',
      gridLeft: '#18293A', gridDay: '#142230', gridDayToday: '#1D3143',
      isDark: true
    }
  },
  mint: {
    label: '薄荷',
    light: {
      page: '#E8FAF2', nav: rgba('#D9D6F5E5'), panel: '#DCF4E8', panelAlt: '#ECF9F2',
      textPrimary: '#1D4A3A', textSecondary: '#396458', iconTint: '#2D5A4E',
      buttonBackground: '#CFEADF', buttonText: '#1D4A3A',
      gridHeader: '#D7F3E6', gridHeaderToday: '#BFEAD6',
      gridLeft: '#E7F8EF', gridDay: '#F0FBF5', gridDayToday: '#D4F4E4',
      isDark: false
    },
    dark: {
      page: '#0F1F19', nav: rgba('#F00B1A14'), panel: '#1A3028', panelAlt: '#152620',
      textPrimary: '#D9EFE4', textSecondary: '#A2C4B6', iconTint: '#B8D9CB',
      buttonBackground: '#24453A', buttonText: '#D9EFE4',
      gridHeader: '#1C362C', gridHeaderToday: '#27493A',
      gridLeft: '#182B23', gridDay: '#14241C', gridDayToday: '#1B3127',
      isDark: true
    }
  }
};

/** 课程卡片 8 色循环（与 Android CourseCardColors 一致） */
const COURSE_COLORS_LIGHT = ['#FAD8C0', '#C9E7FF', '#D8F4D2', '#FFE6A8', '#E6D7FF', '#FFD7E0', '#D8F0EE', '#FFE1C4'];
const COURSE_COLORS_DARK = ['#4A3221', '#21384A', '#2B4625', '#4A3E21', '#30214A', '#4A212A', '#2A413F', '#4A3521'];
const COURSE_TEXT_LIGHT = '#37312A';

function palette(themeName, dark) {
  const theme = THEMES[themeName] || THEMES.warm;
  return dark ? theme.dark : theme.light;
}

function courseColor(index, dark) {
  const list = dark ? COURSE_COLORS_DARK : COURSE_COLORS_LIGHT;
  return list[Math.abs(index) % list.length];
}

function courseTextColor(index, dark) {
  return dark ? COURSE_COLORS_LIGHT[Math.abs(index) % COURSE_COLORS_LIGHT.length] : COURSE_TEXT_LIGHT;
}

module.exports = {
  THEMES: THEMES,
  palette: palette,
  courseColor: courseColor,
  courseTextColor: courseTextColor,
  COURSE_COLORS_LIGHT: COURSE_COLORS_LIGHT
};
