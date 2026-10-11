package cn.xiao.xyaax.importing

import cn.xiao.xyaax.domain.SectionTimes
import cn.xiao.xyaax.model.WeekParity
import cn.xiao.xyaax.model.Weekday
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * 通用多源识别与合并算法（Universal Course Schedule Extraction & Merging Algorithm）：
 * 输出格式层——Markdown / JSON / CSV / ICS（iCalendar），统一由课表结构生成。
 */
object ScheduleExporter {

    private val weekdayNames = listOf(
        "星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日"
    )

    /** Markdown 表格：星期 | 节次 | 课程 | 教师 | 地点 | 周次。 */
    fun toMarkdown(courses: List<ParsedCourse>): String {
        val sb = StringBuilder()
        sb.append("| 星期 | 节次 | 课程 | 教师 | 地点 | 周次 |\n")
        sb.append("| --- | --- | --- | --- | --- | --- |\n")
        courses.forEach { course ->
            course.meetings.forEach { meeting ->
                sb.append("| ${weekdayNames.getOrNull(meeting.weekday.ordinal) ?: ""} | ")
                    .append("${meeting.startSection}-${meeting.endSection}节 | ")
                    .append(course.courseName).append(" | ")
                    .append(course.teacher.ifEmpty { "-" }).append(" | ")
                    .append(meeting.location.ifEmpty { "-" }).append(" | ")
                    .append(meeting.weekRules.joinToString(",") { ruleText(it) })
                    .append(" |\n")
            }
        }
        return sb.toString().trim()
    }

    /** CSV：course_name,teacher,location,day_of_week,period_start,period_end,weeks。 */
    fun toCsv(courses: List<ParsedCourse>): String {
        val sb = StringBuilder()
        sb.append("course_name,teacher,location,day_of_week,period_start,period_end,weeks\n")
        courses.forEach { course ->
            course.meetings.forEach { meeting ->
                sb.append(csv(course.courseName)).append(',')
                    .append(csv(course.teacher)).append(',')
                    .append(csv(meeting.location)).append(',')
                    .append(csv(weekdayNames.getOrNull(meeting.weekday.ordinal) ?: "")).append(',')
                    .append(meeting.startSection).append(',')
                    .append(meeting.endSection).append(',')
                    .append(csv(meeting.weekRules.joinToString(",") { ruleText(it) }))
                    .append('\n')
            }
        }
        return sb.toString().trim()
    }

    /** JSON：结构化完整输出（课程名/教师/地点/校区/星期/节次/周次集合）。 */
    fun toJson(
        courses: List<ParsedCourse>,
        semester: String? = null,
        semesterStartDate: String? = null,
        totalWeeks: Int? = null
    ): String {
        val sb = StringBuilder()
        sb.append("{\n  \"semester\": ").append(js(semester ?: "")).append(",\n")
        sb.append("  \"semester_start_date\": ").append(js(semesterStartDate ?: "")).append(",\n")
        sb.append("  \"total_weeks\": ").append(totalWeeks ?: "null").append(",\n")
        sb.append("  \"courses\": [\n")
        courses.forEachIndexed { courseIndex, course ->
            sb.append("    {\n")
            sb.append("      \"course_name\": ").append(js(course.courseName)).append(",\n")
            sb.append("      \"teacher\": ").append(js(course.teacher)).append(",\n")
            sb.append("      \"meetings\": [\n")
            course.meetings.forEachIndexed { meetingIndex, meeting ->
                sb.append("        {\n")
                sb.append("          \"day_of_week\": ").append(js(weekdayNames.getOrNull(meeting.weekday.ordinal) ?: "")).append(",\n")
                sb.append("          \"period_start\": ").append(meeting.startSection).append(",\n")
                sb.append("          \"period_end\": ").append(meeting.endSection).append(",\n")
                sb.append("          \"location\": ").append(js(meeting.location)).append(",\n")
                sb.append("          \"campus\": ").append(js(meeting.campusAlias ?: "")).append(",\n")
                sb.append("          \"weeks\": [")
                sb.append(expandWeeks(meeting.weekRules).joinToString(","))
                sb.append("]\n")
                sb.append("        }").append(if (meetingIndex < course.meetings.size - 1) "," else "").append("\n")
            }
            sb.append("      ]\n")
            sb.append("    }").append(if (courseIndex < courses.size - 1) "," else "").append("\n")
        }
        sb.append("  ]\n}")
        return sb.toString()
    }

    /** ICS（iCalendar）：按周次集合展开每节课为学期内具体日期的 VEVENT，可导入手机日历。 */
    fun toIcs(
        courses: List<ParsedCourse>,
        semesterStartDate: String?,
        totalWeeks: Int? = null
    ): String {
        val start = semesterStartDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        val sb = StringBuilder()
        sb.append("BEGIN:VCALENDAR\nVERSION:2.0\nPRODID:-//XClass//UniversalSchedule//CN\n")
        var eventId = 0
        courses.forEach { course ->
            course.meetings.forEach { meeting ->
                val weeks = expandWeeks(meeting.weekRules, totalWeeks)
                val weekdayOffset = meeting.weekday.ordinal // 0=周一
                weeks.forEach { week ->
                    if (start != null) {
                        val date = start.plusDays((week - 1) * 7L + weekdayOffset)
                        // 自定义上课时间优先，否则按节次默认时间表
                        val startTime = meeting.customStartTime?.takeIf { it.isNotBlank() }
                            ?: (SectionTimes.DEFAULT_RANGES.getOrNull(meeting.startSection - 1)
                                ?.substringBefore('-') ?: "08:00")
                        val endTime = meeting.customEndTime?.takeIf { it.isNotBlank() }
                            ?: (SectionTimes.DEFAULT_RANGES.getOrNull(meeting.endSection - 1)
                                ?.substringAfter('-') ?: "09:40")
                        val dtStart = date.format(DateTimeFormatter.BASIC_ISO_DATE) + "T" + startTime.replace(":", "") + "00"
                        val dtEnd = date.format(DateTimeFormatter.BASIC_ISO_DATE) + "T" + endTime.replace(":", "") + "00"
                        sb.append("BEGIN:VEVENT\n")
                        sb.append("UID:xclass-${eventId++}@xclass\n")
                        sb.append("DTSTAMP:").append(dtStart).append("Z\n")
                        sb.append("DTSTART:").append(dtStart).append("\n")
                        sb.append("DTEND:").append(dtEnd).append("\n")
                        sb.append("SUMMARY:").append(escapeIcs(course.courseName)).append("\n")
                        if (meeting.location.isNotEmpty()) {
                            sb.append("LOCATION:").append(escapeIcs(meeting.location)).append("\n")
                        }
                        if (course.teacher.isNotEmpty()) {
                            sb.append("DESCRIPTION:").append(escapeIcs("教师：" + course.teacher)).append("\n")
                        }
                        sb.append("END:VEVENT\n")
                    }
                }
            }
        }
        sb.append("END:VCALENDAR\n")
        return sb.toString()
    }

    // ---------- 内部工具 ----------

    private fun ruleText(rule: cn.xiao.xyaax.model.WeekRule): String {
        val base = if (rule.startWeek == rule.endWeek) "${rule.startWeek}周" else "${rule.startWeek}-${rule.endWeek}周"
        return when (rule.parity) {
            WeekParity.ODD -> "$base(单)"
            WeekParity.EVEN -> "$base(双)"
            else -> base
        }
    }

    /** 周次规则 → 具体周次集合（去重升序）。 */
    fun expandWeeks(rules: List<cn.xiao.xyaax.model.WeekRule>, totalWeeks: Int? = null): List<Int> {
        val set = LinkedHashSet<Int>()
        rules.forEach { rule ->
            for (week in rule.startWeek..rule.endWeek) {
                val ok = when (rule.parity) {
                    WeekParity.ODD -> week % 2 == 1
                    WeekParity.EVEN -> week % 2 == 0
                    else -> true
                }
                if (ok && (totalWeeks == null || week <= totalWeeks)) set.add(week)
            }
        }
        return set.sorted()
    }

    private fun csv(value: String): String {
        val escaped = value.replace("\"", "\"\"")
        return if (escaped.contains(',') || escaped.contains('"') || escaped.contains('\n')) {
            "\"$escaped\""
        } else escaped
    }

    private fun js(value: String): String = "\"" + value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n") + "\""

    private fun escapeIcs(value: String): String = value
        .replace("\\", "\\\\")
        .replace(",", "\\,")
        .replace(";", "\\;")
        .replace("\n", "\\n")
}
