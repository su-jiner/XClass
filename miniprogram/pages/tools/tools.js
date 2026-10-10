const store = require('../../utils/store.js');
const theme = require('../../utils/theme.js');

const LEVEL_SCORES = { 优秀: 95, 良好: 85, 中等: 75, 及格: 65, 不及格: 50 };

function gpaOf(score) {
  if (score >= 90) return 4.0;
  if (score >= 85) return 3.7;
  if (score >= 82) return 3.3;
  if (score >= 78) return 3.0;
  if (score >= 75) return 2.7;
  if (score >= 72) return 2.3;
  if (score >= 68) return 2.0;
  if (score >= 64) return 1.5;
  if (score >= 60) return 1.0;
  return 0;
}

Page({
  data: {
    palette: theme.palette('warm', false),
    statusBarHeight: 24,
    tab: 0,
    tabs: ['绩点计算器', '成绩查询', '考试安排', '学业完成'],
    gpaCourses: [],
    summary: { gpa: '0.00', weighted: '0.00', credits: '0.0' },
    grades: [],
    exams: [],
    progress: { totalRequired: 160, totalEarned: 0, note: '手动维护' },
  },
  onShow() {
    if (this.getTabBar && this.getTabBar()) this.getTabBar().setData({ selected: 2 });
    if (this.data.statusBarHeight === 24) {
      const info = wx.getSystemInfoSync ? wx.getSystemInfoSync() : {};
      this.setData({ statusBarHeight: info.statusBarHeight || 24 });
    }
    const prefs = store.loadPrefs();
    this.setData({ palette: theme.palette(prefs.theme, false) });
    this.reload();
  },
  reload() {
    const gpaCourses = wx.getStorageSync('xclass.gpaCourses') || [];
    const grades = wx.getStorageSync('xclass.grades') || [];
    const exams = wx.getStorageSync('xclass.exams') || [];
    this.setData({ gpaCourses: gpaCourses, grades: grades, exams: exams });
    this.computeSummary();
  },
  computeSummary() {
    const courses = this.data.gpaCourses.filter((course) => course.included !== false);
    let creditSum = 0;
    let weighted = 0;
    let gpaSum = 0;
    courses.forEach((course) => {
      const score = course.gradeType === 'level' ? (LEVEL_SCORES[course.level] || 0) : Number(course.score || 0);
      const credit = Number(course.credit || 0);
      creditSum += credit;
      weighted += score * credit;
      gpaSum += gpaOf(score) * credit;
    });
    this.setData({
      summary: {
        gpa: creditSum ? (gpaSum / creditSum).toFixed(2) : '0.00',
        weighted: creditSum ? (weighted / creditSum).toFixed(2) : '0.00',
        credits: creditSum.toFixed(1),
      },
    });
  },
  switchTab(event) {
    this.setData({ tab: Number(event.currentTarget.dataset.index) });
  },
  addGpaCourse() {
    wx.showModal({
      title: '添加课程',
      editable: true,
      placeholderText: '课程名/分数/学分，例如：高等数学/88/4',
      success: (result) => {
        if (!result.confirm || !result.content) return;
        const parts = result.content.split('/').map((item) => item.trim());
        const course = {
          courseName: parts[0] || '未命名课程',
          score: Number(parts[1] || 0),
          credit: Number(parts[2] || 0),
          gradeType: 'percent',
          included: true,
        };
        const courses = this.data.gpaCourses.concat([course]);
        wx.setStorageSync('xclass.gpaCourses', courses);
        this.reload();
      },
    });
  },
  clearGpa() {
    wx.showModal({
      title: '清空',
      content: '确定清空全部已录入课程？',
      success: (result) => {
        if (!result.confirm) return;
        wx.setStorageSync('xclass.gpaCourses', []);
        this.reload();
      },
    });
  },
  toggleInclude(event) {
    const index = Number(event.currentTarget.dataset.index);
    const courses = this.data.gpaCourses;
    courses[index].included = !courses[index].included;
    wx.setStorageSync('xclass.gpaCourses', courses);
    this.reload();
  },
  addGrade() {
    wx.showModal({
      title: '添加成绩',
      editable: true,
      placeholderText: '课程名/学分/成绩/学期，例如：高等数学/4/89/2026-2027-1',
      success: (result) => {
        if (!result.confirm || !result.content) return;
        const parts = result.content.split('/').map((item) => item.trim());
        const grades = this.data.grades.concat([{
          courseName: parts[0] || '未命名课程',
          credit: Number(parts[1] || 0),
          score: parts[2] || '',
          semester: parts[3] || '',
        }]);
        wx.setStorageSync('xclass.grades', grades);
        this.reload();
      },
    });
  },
  addExam() {
    wx.showModal({
      title: '添加考试',
      editable: true,
      placeholderText: '课程名/时间/地点/座位，例如：高等数学/2026-06-20 08:30/逸夫楼101/32号',
      success: (result) => {
        if (!result.confirm || !result.content) return;
        const parts = result.content.split('/').map((item) => item.trim());
        const exams = this.data.exams.concat([{
          courseName: parts[0] || '未命名考试',
          time: parts[1] || '',
          location: parts[2] || '',
          seat: parts[3] || '',
        }]);
        wx.setStorageSync('xclass.exams', exams);
        this.reload();
      },
    });
  },
  editProgress() {
    wx.showModal({
      title: '学业完成',
      editable: true,
      placeholderText: '已修学分/毕业需修学分，例如：120/160',
      success: (result) => {
        if (!result.confirm || !result.content) return;
        const parts = result.content.split('/').map((item) => Number(item.trim()));
        this.setData({
          progress: {
            totalRequired: parts[1] || 160,
            totalEarned: parts[0] || 0,
            note: '手动维护',
          },
        });
      },
    });
  },
});
