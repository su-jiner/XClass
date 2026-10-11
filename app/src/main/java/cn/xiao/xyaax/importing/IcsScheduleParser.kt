package cn.xiao.xyaax.importing

import cn.xiao.xyaax.model.CourseSchedule
import cn.xiao.xyaax.model.MeetingTime
import cn.xiao.xyaax.model.SectionBlock
import cn.xiao.xyaax.model.WeekParity
import cn.xiao.xyaax.model.WeekRule
import cn.xiao.xyaax.model.Weekday
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * ICS 课表解析（等效 SleepDown IcsSchedule）：
 * - 解析 VEVENT（SUMMARY/DTSTART/DTEND/LOCATION/DESCRIPTION/RRULE）
 * - RRULE：FREQ=WEEKLY;BYDAY=MO,WE;UNTIL=... 或 COUNT=n；无 RRULE 视为单次事件
 * - 周次推算：以学期起始日为第 1 周（周一为每周第一天）
 * - 时间→节次：按传入的时间表 blocks 区间匹配（含结束时刻）
 */
object IcsScheduleParser {

    private val dateTimeFormats = listOf(
        DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss"),
        DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmm"),
        DateTimeFormatter.ofPattern("yyyyMMdd"),
    )

    fun parse(
        icsText: String,
        semesterStart: LocalDate,
        blocks: List<SectionBlock>,
    ): List<CourseSchedule> {
        val lines = icsText.replace("\r\n", "\n").split("\n")
        val events = extractEvents(lines)
        return events.mapNotNull { event -> event.toCourse(semesterStart, blocks) }
    }

    private fun extractEvents(lines: List<String>): List<MutableMap<String, String>> {
        val events = mutableListOf<MutableMap<String, String>>()
        var current: MutableMap<String, String>? = null
        for (line in lines) {
            if (line.isBlank()) continue
            if (line.startsWith("BEGIN:VEVENT")) {
                current = mutableMapOf()
                events.add(current)
            } else if (line.startsWith("END:VEVENT")) {
                current = null
            } else {
                val colon = line.indexOf(':')
                if (colon <= 0) continue
                val key = line.substring(0, colon).trim().uppercase()
                val value = line.substring(colon + 1).trim()
                current?.put(key, value)
            }
        }
        return events
    }

    private fun MutableMap<String, String>.toCourse(
        semesterStart: LocalDate,
        blocks: List<SectionBlock>,
    ): CourseSchedule? {
        val summary = this["SUMMARY"]?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val dtStartRaw = this["DTSTART"] ?: return null
        val dtEndRaw = this["DTEND"]
        val location = this["LOCATION"]?.trim().orEmpty()
        val teacher = this["DESCRIPTION"]
            ?.lineSequence()
            ?.firstOrNull { it.contains("教师") }
            ?.substringAfter("：", "")
            ?.takeIf { it.isNotBlank() }
            ?: ""

        val start = parseDateTime(dtStartRaw) ?: return null
        val end = dtEndRaw?.let { parseDateTime(it) } ?: start.plusMinutes(90)

        val weekday = start.toLocalDate().dayOfWeek.toWeekday()
        val startSection = sectionFor(start.toLocalTime(), blocks) ?: return null
        val endSection = sectionForEnd(end.toLocalTime(), blocks) ?: startSection

        val rrule = this["RRULE"]?.uppercase() ?: ""
        val weekRules = resolveWeekRules(start.toLocalDate(), semesterStart, rrule)
        if (weekRules.isEmpty()) return null

        val rawWeekText = weekRules.joinToString(",") { rule ->
            val base = if (rule.startWeek == rule.endWeek) "${rule.startWeek}周" else "${rule.startWeek}-${rule.endWeek}周"
            when (rule.parity) {
                WeekParity.ODD -> "$base(单)"
                WeekParity.EVEN -> "$base(双)"
                WeekParity.ALL -> base
            }
        }

        return CourseSchedule(
            courseName = summary,
            teacher = teacher,
            semester = "",
            credit = null,
            rawWeekText = rawWeekText,
            meetings = listOf(
                MeetingTime(
                    weekday = weekday,
                    startSection = startSection,
                    endSection = endSection,
                    weekRules = weekRules,
                    location = location,
                )
            ),
        )
    }

    private fun resolveWeekRules(
        eventDate: LocalDate,
        semesterStart: LocalDate,
        rrule: String,
    ): List<WeekRule> {
        val eventWeek = weekOf(eventDate, semesterStart)
        if (rrule.isBlank() || !rrule.contains("FREQ=WEEKLY")) {
            return listOf(WeekRule(eventWeek, eventWeek))
        }
        val untilText = rrule.substringAfter("UNTIL=", "").takeIf { it.isNotBlank() }
        val countText = rrule.substringAfter("COUNT=", "").takeIf { it.isNotBlank() }
        val lastWeek = if (untilText != null) {
            val until = runCatching {
                if (untilText.length >= 8) {
                    LocalDate.parse(untilText.substring(0, 8), DateTimeFormatter.BASIC_ISO_DATE)
                } else {
                    null
                }
            }.getOrNull()
            if (until != null) weekOf(until, semesterStart) else eventWeek + 20
        } else if (countText != null) {
            val count = countText.toIntOrNull() ?: 1
            eventWeek + count - 1
        } else {
            eventWeek + 20
        }
        val start = minOf(eventWeek, lastWeek)
        val end = maxOf(eventWeek, lastWeek)
        // BYDAY 不引入单双周语义；全周次
        return listOf(WeekRule(start, end, WeekParity.ALL))
    }

    private fun parseDateTime(raw: String): LocalDateTime? {
        val normalized = raw.substringAfter(':')
        for (format in dateTimeFormats) {
            runCatching { return LocalDateTime.parse(normalized, format) }
        }
        return null
    }

    /** 时间→节次：按 blocks 前闭后开区间匹配；恰好落在某节结束时刻时归入该节。 */
    private fun matchBlock(time: LocalTime, blocks: List<SectionBlock>): SectionBlock? {
        for (block in blocks) {
            val start = LocalTime.parse(block.startTime)
            val end = LocalTime.parse(block.endTime)
            if (!time.isBefore(start) && time.isBefore(end)) return block
        }
        for (block in blocks) {
            if (time == LocalTime.parse(block.endTime)) return block
        }
        return null
    }

    /** 开始节次：取所在大节的起始节。 */
    private fun sectionFor(time: LocalTime, blocks: List<SectionBlock>): Int? =
        matchBlock(time, blocks)?.startSection

    /** 结束节次：取所在大节的结束节。 */
    private fun sectionForEnd(time: LocalTime, blocks: List<SectionBlock>): Int? =
        matchBlock(time, blocks)?.endSection

    private fun weekOf(date: LocalDate, semesterStart: LocalDate): Int {
        val days = java.time.temporal.ChronoUnit.DAYS.between(semesterStart, date).toInt()
        return (days / 7) + 1
    }

    private fun DayOfWeek.toWeekday(): Weekday = when (this) {
        DayOfWeek.MONDAY -> Weekday.MONDAY
        DayOfWeek.TUESDAY -> Weekday.TUESDAY
        DayOfWeek.WEDNESDAY -> Weekday.WEDNESDAY
        DayOfWeek.THURSDAY -> Weekday.THURSDAY
        DayOfWeek.FRIDAY -> Weekday.FRIDAY
        DayOfWeek.SATURDAY -> Weekday.SATURDAY
        DayOfWeek.SUNDAY -> Weekday.SUNDAY
    }
}
