package cn.xiao.xyaax.importing

import cn.xiao.xyaax.model.CourseSchedule
import cn.xiao.xyaax.model.Weekday
import java.time.LocalDate

/**
 * XClass 专属课表分享文本（Share Text v1）：
 *
 * 一套软件自有的、可在四端（Android/iOS/鸿蒙/小程序）之间「导出 → 导入」的课表文本格式，
 * 朋友之间可直接粘贴或发文件分享。格式与现有列表式解析器完全兼容：
 *
 * ```
 * # XClass 课表分享 v1
 * # 课表：默认课表
 * # 学期：2026-2027年第1学期
 * # 学期开始：2026-08-31
 * # 总周数：21
 * 星期一 高等数学/(1-2节)1-16周/教三302/张三
 * 星期二 信息技术/(1-2节)12-14周(双),15-19周/（乌）E406/刘文杰
 * ```
 *
 * - 每行一门课的一次上课：`星期X 课程名/(节次)周次/地点/教师`；
 * - 空的地点/教师用 `-` 占位（导入时归一化为空）；
 * - `#` 开头的元数据行为注释，导入时自动忽略；
 * - 导出文本可直接粘贴进任意端的「粘贴文本导入」，无需新解析器。
 */
object ShareTextCodec {

    const val FORMAT_VERSION = "1"
    const val HEADER = "# XClass 课表分享 v$FORMAT_VERSION"

    private val weekdayLabel = mapOf(
        Weekday.MONDAY to "星期一", Weekday.TUESDAY to "星期二", Weekday.WEDNESDAY to "星期三",
        Weekday.THURSDAY to "星期四", Weekday.FRIDAY to "星期五", Weekday.SATURDAY to "星期六",
        Weekday.SUNDAY to "星期日"
    )

    /** 周次文本（如 1-16周 / 12-14周(双),15-19周）。优先用课程已存的 rawWeekText，缺失时由规则推导。 */
    private fun weekText(course: CourseSchedule): String {
        if (course.rawWeekText.isNotBlank()) return course.rawWeekText
        return course.meetings.firstOrNull()?.weekRules
            ?.joinToString(",") { rule ->
                val base = if (rule.startWeek == rule.endWeek) "${rule.startWeek}周" else "${rule.startWeek}-${rule.endWeek}周"
                when (rule.parity) {
                    cn.xiao.xyaax.model.WeekParity.ODD -> "$base(单)"
                    cn.xiao.xyaax.model.WeekParity.EVEN -> "$base(双)"
                    else -> base
                }
            }.orEmpty()
    }

    /**
     * 生成分享文本。
     *
     * @param courses 当前课表课程
     * @param profileName 课表名（可空）
     * @param semester 学期文本（可空）
     * @param semesterStart 学期开始日期（可空）
     * @param totalWeeks 总周数（可空）
     * @param campusIdToAlias 校区时间表 ID → 校区简称（如 "ocr-真理道校区-教学楼" → "乌"），
     *                        用于把 campusId 还原为分享文本里的（乌）前缀；无映射时省略前缀。
     */
    fun encode(
        courses: List<CourseSchedule>,
        profileName: String? = null,
        semester: String? = null,
        semesterStart: LocalDate? = null,
        totalWeeks: Int? = null,
        campusIdToAlias: Map<String, String> = emptyMap()
    ): String = buildString {
        append(HEADER).append('\n')
        profileName?.takeIf { it.isNotBlank() }?.let { append("# 课表：").append(it).append('\n') }
        semester?.takeIf { it.isNotBlank() }?.let { append("# 学期：").append(it).append('\n') }
        semesterStart?.let { append("# 学期开始：").append(it).append('\n') }
        totalWeeks?.takeIf { it in 1..60 }?.let { append("# 总周数：").append(it).append('\n') }

        val rows = mutableListOf<String>()
        courses.forEach { course ->
            course.meetings.forEach { meeting ->
                val day = weekdayLabel[meeting.weekday] ?: return@forEach
                val sections = "${meeting.startSection}-${meeting.endSection}节"
                // 自定义上课时间：`(1-2节)[09:00-10:40]`，导入端自动还原
                val custom = meeting.customStartTime?.takeIf { it.isNotBlank() }?.let { start ->
                    val end = meeting.customEndTime?.takeIf { it.isNotBlank() } ?: start
                    "[$start-$end]"
                }.orEmpty()
                val weeks = weekText(course)
                val alias = meeting.campusId?.let { campusIdToAlias[it] }
                val location = buildString {
                    alias?.takeIf { it.isNotBlank() }?.let { append('（').append(it).append('）') }
                    append(meeting.location.trim())
                }.ifBlank { "-" }
                val teacher = course.teacher.trim().ifBlank { "-" }
                rows += "$day ${course.courseName}/($sections)$custom$weeks/$location/$teacher"
            }
        }
        rows.distinct().sorted().forEach { append(it).append('\n') }
    }
}
