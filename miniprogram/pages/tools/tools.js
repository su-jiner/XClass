const core = require('../../utils/core.js');
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

function scoreOf(grade) {
  if (grade.gradeType === 'level') return LEVEL_SCORES[grade.level] || 0;
  return Number(grade.score || 0);
}

Page({
  data: {
    palette: theme.palette('warm', false),
    statusBarHeight: 24,
    tab: 0,
    tabs: ['绩点计算器', '成绩查询', '考试安排', '学业完成'],
    gpaCourses: [],
    summary: { gpa: '0.00', weighted: '0.00', arithmetic: '0.00', credits: '0.0' },
    semesterGroups: [],
    examGroups: [],
    countdown: '',
    progress: { totalRequired: 160, totalEarned: 0 },
    modules: [],
  },
  onShow() {
    if (this.getTabBar && this.getTabBar()) this.getTabBar().setData({ selected: 2 });
    const info = wx.getSystemInfoSync ? wx.getSystemInfoSync() : {};
    const prefs = store.loadPrefs();
    this.setData({
      palette: theme.palette(prefs.theme, false),
      statusBarHeight: info.statusBarHeight || 24,
    });
    this.reload();
  },
  reload() {
    const gpaCourses = wx.getStorageSync('xclass.gpaCourses') || [];
    const grades = wx.getStorageSync('xclass.grades') || [];
    const exams = wx.getStorageSync('xclass.exams') || [];
    const progress = wx.getStorageSync('xclass.progress') || { totalRequired: 160, totalEarned: 0 };
    const modules = wx.getStorageSync('xclass.progressModules') || [
      { name: '学科基础与专业必修', required: 0, earned: 0 },
      { name: '专业选修课', required: 0, earned: 0 },
      { name: '通识教育选修/核心课', required: 0, earned: 0 },
      { name: '实践教学与毕业设计', required: 0, earned: 0 },
    ];
    this.setData({
      gpaCourses: gpaCourses,
      progress: progress,
      modules: modules,
      semesterGroups: this.groupBySemester(grades),
      examGroups: exams.slice().sort((a, b) => String(a.time).localeCompare(String(b.time))),
      countdown: this.countdownText(exams),
    });
    this.computeSummary();
  },
  groupBySemester(grades) {
    const order = [];
    const grouped = {};
    grades.forEach((grade) => {
      const key = (grade.semester || '未标注学期').trim() || '未标注学期';
      if (!grouped[key]) { grouped[key] = { name: key, items: [], credits: 0, average: 0, count: 0 }; order.push(key); }
      grouped[key].items.push(grade);
    });
    return order.map((key) => {
      const group = grouped[key];
      let creditSum = 0;
      let weighted = 0;
      group.items.forEach((item) => {
        const credit = Number(item.credit || 0);
        creditSum += credit;
        weighted += scoreOf(item) * credit;
      });
      group.credits = creditSum;
      group.average = creditSum > 0 ? (weighted / creditSum) : 0;
      group.count = group.items.length;
      return group;
    });
  },
  countdownText(exams) {
    if (!exams.length) return '暂无考试安排';
    const now = Date.now();
    const upcoming = exams
      .map((exam) => ({ exam, time: this.parseExamTime(exam.time) }))
      .filter((item) => item.time && item.time.getTime() >= now)
      .sort((a, b) => a.time - b.time)[0];
    if (!upcoming) return '考试均已结束';
    const days = Math.floor((upcoming.time.getTime() - now) / 86400000);
    const hours = Math.floor(((upcoming.time.getTime() - now) % 86400000) / 3600000);
    return `距离「${upcoming.exam.courseName}」还有 ${days} 天 ${hours} 小时`;
  },
  parseExamTime(text) {
    const match = String(text || '').match(/(\d{4})-(\d{1,2})-(\d{1,2})[ T]?(\d{1,2})?:?(\d{2})?/);
    if (!match) return null;
    return new Date(
      Number(match[1]), Number(match[2]) - 1, Number(match[3]),
      Number(match[4] || 8), Number(match[5] || 30)
    );
  },
  computeSummary() {
    const courses = this.data.gpaCourses.filter((course) => course.included !== false);
    let creditSum = 0;
    let weighted = 0;
    let gpaSum = 0;
    let arithmetic = 0;
    courses.forEach((course) => {
      const score = scoreOf(course);
      const credit = Number(course.credit || 0);
      creditSum += credit;
      weighted += score * credit;
      gpaSum += gpaOf(score) * credit;
      arithmetic += score;
    });
    this.setData({
      summary: {
        gpa: creditSum ? (gpaSum / creditSum).toFixed(2) : '0.00',
        weighted: creditSum ? (weighted / creditSum).toFixed(2) : '0.00',
        arithmetic: courses.length ? (arithmetic / courses.length).toFixed(2) : '0.00',
        credits: creditSum.toFixed(1),
      },
    });
  },
  switchTab(event) {
    this.setData({ tab: Number(event.currentTarget.dataset.index) });
  },
  // ---------------- 绩点计算器 ----------------
  addGpaCourse() {
    wx.showActionSheet({
      itemList: ['百分制（课程名/分数/学分）', '五级制（课程名/等级/学分）'],
      success: (result) => {
        const isLevel = result.tapIndex === 1;
        wx.showModal({
          title: isLevel ? '添加五级制课程' : '添加百分制课程',
          editable: true,
          placeholderText: isLevel ? '高等数学/优秀/4' : '高等数学/88/4',
          success: (modal) => {
            if (!modal.confirm || !modal.content) return;
            const parts = modal.content.split('/').map((item) => item.trim());
            const course = {
              courseName: parts[0] || '未命名课程',
              credit: Number(parts[2] || 0),
              gradeType: isLevel ? 'level' : 'percent',
              level: isLevel ? (parts[1] || '优秀') : '',
              score: isLevel ? 0 : Number(parts[1] || 0),
              included: true,
            };
            const courses = this.data.gpaCourses.concat([course]);
            wx.setStorageSync('xclass.gpaCourses', courses);
            this.reload();
          },
        });
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
  // ---------------- 成绩查询 ----------------
  addGrade() {
    wx.showModal({
      title: '添加成绩',
      editable: true,
      placeholderText: '高等数学/4/89/2026-2027-1',
      success: (result) => {
        if (!result.confirm || !result.content) return;
        const parts = result.content.split('/').map((item) => item.trim());
        const grades = (wx.getStorageSync('xclass.grades') || []).concat([{
          courseName: parts[0] || '未命名课程',
          credit: Number(parts[1] || 0),
          score: Number(parts[2] || 0),
          semester: parts[3] || '未标注学期',
        }]);
        wx.setStorageSync('xclass.grades', grades);
        this.reload();
      },
    });
  },
  deleteGrade(event) {
    const semester = event.currentTarget.dataset.semester;
    const name = event.currentTarget.dataset.name;
    const grades = (wx.getStorageSync('xclass.grades') || [])
      .filter((grade) => !(grade.semester === semester && grade.courseName === name));
    wx.setStorageSync('xclass.grades', grades);
    this.reload();
  },
  importFromGpa() {
    const converted = this.data.gpaCourses.map((course) => ({
      courseName: course.courseName,
      credit: Number(course.credit || 0),
      score: scoreOf(course),
      semester: '来自绩点计算器',
    }));
    if (!converted.length) {
      wx.showToast({ title: '绩点计算器中暂无课程', icon: 'none' });
      return;
    }
    wx.setStorageSync('xclass.grades', converted);
    this.reload();
    wx.showToast({ title: `已导入 ${converted.length} 门成绩`, icon: 'none' });
  },
  sendToGpa() {
    const grades = wx.getStorageSync('xclass.grades') || [];
    if (!grades.length) {
      wx.showToast({ title: '暂无成绩可转入', icon: 'none' });
      return;
    }
    const courses = grades.map((grade) => ({
      courseName: grade.courseName,
      credit: Number(grade.credit || 0),
      gradeType: 'percent',
      score: Number(grade.score || 0),
      included: true,
    }));
    wx.setStorageSync('xclass.gpaCourses', courses);
    this.reload();
    wx.showToast({ title: `已转入 ${courses.length} 门`, icon: 'none' });
    this.setData({ tab: 0 });
  },
  // ---------------- 考试安排 ----------------
  addExam() {
    wx.showModal({
      title: '添加考试',
      editable: true,
      placeholderText: '高等数学/2026-06-20 08:30/逸夫楼101/32号',
      success: (result) => {
        if (!result.confirm || !result.content) return;
        const parts = result.content.split('/').map((item) => item.trim());
        const exams = (wx.getStorageSync('xclass.exams') || []).concat([{
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
  deleteExam(event) {
    const index = Number(event.currentTarget.dataset.index);
    const exams = (wx.getStorageSync('xclass.exams') || []).filter((item, itemIndex) => itemIndex !== index);
    wx.setStorageSync('xclass.exams', exams);
    this.reload();
  },
  // ---------------- 学业完成 ----------------
  editProgress() {
    wx.showModal({
      title: '毕业学分',
      editable: true,
      placeholderText: '已修/需修，例如 120/160',
      success: (result) => {
        if (!result.confirm || !result.content) return;
        const parts = result.content.split('/').map((item) => Number(item.trim()));
        const progress = { totalEarned: parts[0] || 0, totalRequired: parts[1] || 160 };
        wx.setStorageSync('xclass.progress', progress);
        this.reload();
      },
    });
  },
  editModule(event) {
    const index = Number(event.currentTarget.dataset.index);
    const modules = this.data.modules;
    wx.showModal({
      title: modules[index].name,
      editable: true,
      placeholderText: '已修/需修，例如 30/40',
      success: (result) => {
        if (!result.confirm || !result.content) return;
        const parts = result.content.split('/').map((item) => Number(item.trim()));
        modules[index].earned = parts[0] || 0;
        modules[index].required = parts[1] || 0;
        wx.setStorageSync('xclass.progressModules', modules);
        this.reload();
      },
    });
  },
  calculateFromGrades() {
    const grades = wx.getStorageSync('xclass.grades') || [];
    const passed = grades.filter((grade) => scoreOf(grade) >= 60);
    const earned = passed.reduce((sum, grade) => sum + Number(grade.credit || 0), 0);
    const progress = { totalEarned: Number(earned.toFixed(1)), totalRequired: this.data.progress.totalRequired || 160 };
    wx.setStorageSync('xclass.progress', progress);
    this.reload();
    wx.showToast({ title: `已按 ${passed.length} 门通过课程累计 ${progress.totalEarned} 学分`, icon: 'none' });
  },
});
