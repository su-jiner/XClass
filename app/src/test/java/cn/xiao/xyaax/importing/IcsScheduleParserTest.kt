package cn.xiao.xyaax.importing

import cn.xiao.xyaax.model.SectionBlock
import cn.xiao.xyaax.model.WeekParity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class IcsScheduleParserTest {

    private val semesterStart = LocalDate.of(2026, 8, 31) // 第 1 周周一

    /** 默认 12 节时间表 blocks */
    private val blocks = listOf(
        SectionBlock(1, 2, "08:00", "09:40"),
        SectionBlock(3, 4, "10:00", "11:40"),
        SectionBlock(5, 6, "13:30", "15:10"),
        SectionBlock(7, 8, "15:30", "17:10"),
        SectionBlock(9, 10, "18:20", "19:50"),
        SectionBlock(11, 12, "20:00", "21:30"),
    )

    @Test
    fun `解析每周事件并推算周次`() {
        val ics = """
            BEGIN:VCALENDAR
            BEGIN:VEVENT
            SUMMARY:英语（一）
            DTSTART:20260904T083000
            DTEND:20260904T094000
            LOCATION:乌江路校区 多媒体202
            RRULE:FREQ=WEEKLY;BYDAY=FR;UNTIL=20261231T235959Z
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()
        val courses = IcsScheduleParser.parse(ics, semesterStart, blocks)
        assertEquals(1, courses.size)
        val course = courses[0]
        assertEquals("英语（一）", course.courseName)
        assertEquals(1, course.meetings.size)
        val meeting = course.meetings[0]
        assertEquals(1, meeting.startSection) // 08:30 → 第 1 节
        assertEquals(2, meeting.endSection)   // 09:40 → 第 2 节
        assertEquals("乌江路校区 多媒体202", meeting.location)
        assertEquals(1, meeting.weekRules.size)
        assertEquals(1, meeting.weekRules[0].startWeek)
        assertEquals(18, meeting.weekRules[0].endWeek) // 9/4 为第 1 周，UNTIL 12/31 约第 18 周
        assertEquals(WeekParity.ALL, meeting.weekRules[0].parity)
        assertTrue(course.rawWeekText.contains("1-18周"))
    }

    @Test
    fun `单次事件仅映射到该周`() {
        val ics = """
            BEGIN:VCALENDAR
            BEGIN:VEVENT
            SUMMARY:考试
            DTSTART:20261006T100000
            DTEND:20261006T114000
            LOCATION:教学楼101
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()
        val courses = IcsScheduleParser.parse(ics, semesterStart, blocks)
        assertEquals(1, courses.size)
        val meeting = courses[0].meetings[0]
        assertEquals(3, meeting.startSection) // 10:00 → 第 3 节
        assertEquals(4, meeting.endSection)   // 11:40 → 第 4 节
        assertEquals(6, meeting.weekRules[0].startWeek)
        assertEquals(6, meeting.weekRules[0].endWeek)
    }

    @Test
    fun `COUNT 限制周次`() {
        val ics = """
            BEGIN:VCALENDAR
            BEGIN:VEVENT
            SUMMARY:形势与政策
            DTSTART:20260907T133000
            DTEND:20260907T151000
            LOCATION:教室B
            RRULE:FREQ=WEEKLY;COUNT=5
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()
        val courses = IcsScheduleParser.parse(ics, semesterStart, blocks)
        assertEquals(1, courses.size)
        val meeting = courses[0].meetings[0]
        assertEquals(2, meeting.weekRules[0].startWeek)
        assertEquals(6, meeting.weekRules[0].endWeek)
        assertEquals(5, meeting.startSection) // 13:30 → 第 5 节
        assertEquals(6, meeting.endSection)   // 15:10 → 第 6 节
    }

    @Test
    fun `空事件与无效时间被忽略`() {
        val ics = """
            BEGIN:VCALENDAR
            BEGIN:VEVENT
            SUMMARY:未标注时间的课程
            END:VEVENT
            BEGIN:VEVENT
            SUMMARY:
            DTSTART:20260901T080000
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()
        val courses = IcsScheduleParser.parse(ics, semesterStart, blocks)
        assertTrue(courses.isEmpty())
    }

    @Test
    fun `解析结果可转换为课表实体`() {
        val ics = """
            BEGIN:VCALENDAR
            BEGIN:VEVENT
            SUMMARY:计算机网络技术
            DTSTART:20260902T133000
            DTEND:20260902T151000
            LOCATION:实训楼306
            RRULE:FREQ=WEEKLY;BYDAY=WE;COUNT=10
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()
        val courses = IcsScheduleParser.parse(ics, semesterStart, blocks)
        assertTrue(courses.isNotEmpty())
        val course = courses[0]
        assertEquals("计算机网络技术", course.courseName)
        assertEquals(5, course.meetings[0].startSection)
        assertEquals(6, course.meetings[0].endSection)
    }
}
