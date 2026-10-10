package cn.xiao.xyaax.domain

import cn.xiao.xyaax.model.CourseSchedule
import cn.xiao.xyaax.model.MeetingTime
import cn.xiao.xyaax.model.SectionTimeProfile
import cn.xiao.xyaax.model.Weekday
import java.time.LocalDate
import java.time.LocalDateTime

/** 组件/今日页共用的一条课程卡片数据。 */
data class CourseSlot(
    val courseName: String,
    val teacher: String,
    val location: String,
    val campusName: String,
    val startSection: Int,
    val endSection: Int,
    val startTime: String,
    val endTime: String,
    val isOngoing: Boolean,
    val minutesUntilStart: Int?
)

/** 今日视图快照（列表 / 下一个 / 时间轴共用）。 */
data class TodaySnapshot(
    val date: LocalDate,
    val weekday: Weekday,
    val week: Int,
    val items: List<CourseSlot>,
    val nextCourse: CourseSlot?,
    val ongoingCourse: CourseSlot?
) {
    val isEmpty: Boolean get() = items.isEmpty()
}

/** 本周课表微缩：行=节次块，列=周一…周日。 */
data class WeekGridSnapshot(
    val week: Int,
    val totalWeeks: Int,
    val columnLabels: List<String>,
    val rowLabels: List<String>,
    val cells: List<List<String>>,
    val currentWeekdayIndex: Int
)

/**
 * 组件与今日页的统一数据构建（纯函数，可单测）：
 * 排序、进行中判定、下一节课倒计时、跨周处理都在这里，保证三端显示一致。
 */
object WidgetSnapshotBuilder {

    private val weekdayLabels = listOf("一", "二", "三", "四", "五", "六", "日")
    private val rowLabels = listOf("1-2", "3-4", "5-6", "7-8", "9-10", "11-12")

    fun today(
        courses: List<CourseSchedule>,
        semesterStart: LocalDate,
        profiles: List<SectionTimeProfile>,
        today: LocalDate = LocalDate.now(),
        now: LocalDateTime = LocalDateTime.now(),
        showNonCurrentWeek: Boolean = true
    ): TodaySnapshot {
        val weekday = Weekday.entries[today.dayOfWeek.value - 1]
        val totalWeeks = WeekScheduleCalculator.totalWeeks(courses)
        val week = WeekScheduleCalculator.guessCurrentWeek(semesterStart, today, totalWeeks)

        val slots = courses.flatMap { course ->
            course.meetings
                .filter { it.weekday == weekday && isActiveInWeek(it, week) }
                .map { meeting -> course to meeting }
        }.filter { showNonCurrentWeek || isActiveInWeek(it.second, week) }
            .map { (course, meeting) -> toSlot(course, meeting, profiles, now) }
            .sortedBy { it.startSection }

        val ongoing = slots.firstOrNull { it.isOngoing }
        val next = slots.firstOrNull { !it.isOngoing && (it.minutesUntilStart ?: Int.MAX_VALUE) >= 0 }
            ?: slots.firstOrNull { !it.isOngoing }
        return TodaySnapshot(today, weekday, week, slots, next, ongoing)
    }

    /** 下一天/下一次有课的时间，用于「下一节课」组件在没有当天课程时兜底。 */
    fun nextCourse(
        courses: List<CourseSchedule>,
        semesterStart: LocalDate,
        profiles: List<SectionTimeProfile>,
        today: LocalDate = LocalDate.now(),
        now: LocalDateTime = LocalDateTime.now()
    ): CourseSlot? {
        (0..13).forEach { offset ->
            val date = today.plusDays(offset.toLong())
            val snapshot = today(courses, semesterStart, profiles, date, now)
            val candidate = snapshot.ongoingCourse
                ?: snapshot.nextCourse?.takeIf { offset > 0 || (it.minutesUntilStart ?: 0) >= 0 }
            if (candidate != null) return candidate
        }
        return null
    }

    fun weekGrid(
        courses: List<CourseSchedule>,
        semesterStart: LocalDate,
        week: Int
    ): WeekGridSnapshot {
        val totalWeeks = WeekScheduleCalculator.totalWeeks(courses)
        val cells = MutableList(rowLabels.size) { MutableList(weekdayLabels.size) { "" } }
        courses.forEach { course ->
            course.meetings.filter { isActiveInWeek(it, week) }.forEach { meeting ->
                val rowIndex = ((meeting.startSection - 1) / 2).coerceIn(0, rowLabels.size - 1)
                val columnIndex = meeting.weekday.ordinal
                val existing = cells[rowIndex][columnIndex]
                cells[rowIndex][columnIndex] = if (existing.isEmpty()) {
                    course.courseName.take(4)
                } else {
                    (existing + "/" + course.courseName.take(2)).take(8)
                }
            }
        }
        val todayIndex = LocalDate.now().dayOfWeek.value - 1
        return WeekGridSnapshot(week, totalWeeks, weekdayLabels, rowLabels, cells.map { it.toList() }, todayIndex)
    }

    fun isActiveInWeek(meeting: MeetingTime, week: Int): Boolean =
        meeting.weekRules.isEmpty() || meeting.weekRules.any { rule ->
            week in rule.startWeek..rule.endWeek && when (rule.parity) {
                cn.xiao.xyaax.model.WeekParity.ODD -> week % 2 == 1
                cn.xiao.xyaax.model.WeekParity.EVEN -> week % 2 == 0
                else -> true
            }
        }

    private fun toSlot(
        course: CourseSchedule,
        meeting: MeetingTime,
        profiles: List<SectionTimeProfile>,
        now: LocalDateTime
    ): CourseSlot {
        val profile = TimeProfileResolver.resolve(profiles, meeting.campusId)
        val range = profile.timeRange(meeting.startSection, meeting.endSection)
        val startTime = range?.first ?: "--:--"
        val endTime = range?.second ?: "--:--"
        val nowMinutes = now.hour * 60 + now.minute
        val startMinutes = parseMinutes(startTime)
        val endMinutes = parseMinutes(endTime)
        val ongoing = startMinutes != null && endMinutes != null && nowMinutes in startMinutes until endMinutes
        return CourseSlot(
            courseName = course.courseName,
            teacher = course.teacher,
            location = meeting.location,
            campusName = if (profile.id == TimeProfileResolver.defaultProfile(profiles).id) "" else profile.name,
            startSection = meeting.startSection,
            endSection = meeting.endSection,
            startTime = startTime,
            endTime = endTime,
            isOngoing = ongoing,
            minutesUntilStart = startMinutes?.let { it - nowMinutes }
        )
    }

    fun parseMinutes(time: String): Int? {
        val parts = time.split(':')
        if (parts.size != 2) return null
        val hour = parts[0].trim().toIntOrNull() ?: return null
        val minute = parts[1].trim().toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null
        return hour * 60 + minute
    }

    fun countdownText(minutes: Int?): String = when {
        minutes == null -> ""
        minutes < 0 -> "已结束"
        minutes == 0 -> "即将开始"
        minutes < 60 -> "${minutes} 分钟后"
        else -> "${minutes / 60} 小时 ${minutes % 60} 分后"
    }
}
