package cn.xiao.xyaax.importing

import cn.xiao.xyaax.domain.SectionTimes
import cn.xiao.xyaax.model.CourseSchedule
import cn.xiao.xyaax.model.effectiveEndTime
import cn.xiao.xyaax.model.effectiveStartTime
import cn.xiao.xyaax.model.effectiveTimeText
import cn.xiao.xyaax.model.MeetingTime
import cn.xiao.xyaax.model.WeekParity
import cn.xiao.xyaax.model.WeekRule
import cn.xiao.xyaax.model.Weekday
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** 图片/文本识别噪声过滤 + XClass 分享文本导出/导入回路。 */
class ShareTextCodecTest {

    // ---------------------------------------------------------------- 噪声过滤

    @Test
    fun `filters footer noise rows from grid`() {
        // 真实教务课表页脚：整行跨列，旧逻辑会被当作课程
        val blocks = mutableListOf<TextBlock>()
        val weekdays = listOf("星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日")
        weekdays.forEachIndexed { index, label ->
            blocks += TextBlock(label, 200f + index * 300f, 0f, 120f, 30f)
        }
        blocks += TextBlock("节次", 0f, 0f, 100f, 30f)
        blocks += TextBlock("一", 0f, 60f, 60f, 30f)
        blocks += TextBlock("体育（一）/(1-2节)7-19周/ 体育场1/殷乐", 200f, 60f, 260f, 30f)
        // 页脚噪声行（跨整行、y 在表体下方）
        blocks += TextBlock("注一内容顺序为：课程◇周次◇地点◇教师 本学期2026-08-31正式上课至2027-01-22结束，共21周. 打印时间：2026-10-09", 0f, 120f, 1000f, 30f)
        blocks += TextBlock("其他课程：军事技能张爽(共19周)/1-19周； 入学教育张爽(共19周)/1-19周；", 0f, 160f, 1000f, 30f)
        blocks += TextBlock("2026-2027年第1学期 云计算261课表", 0f, 200f, 800f, 30f)

        val result = CourseTableImporter.parse(blocks, blocks.joinToString("\n") { it.text })
        // 只应识别出 1 门真实课程；噪声行不得成为课程
        assertEquals(1, result.courses.size)
        assertEquals("体育（一）", result.courses.first().courseName)
        // 表外课程应以 warning 提示而非课程
        assertTrue(result.warnings.any { it.contains("表外课程") })
    }

    @Test
    fun `filters noise course names inside cells`() {
        val entries = CellEntryParser.parseCell("注一内容顺序为：课程◇周次◇地点◇教师\n打印时间：2026-10-09\n体育（一）/(1-2节)7-19周/ 体育场1/殷乐")
        assertEquals(1, entries.size)
        assertEquals("体育（一）", entries.first().courseName)
    }

    @Test
    fun `keeps real course with weekday prefix in list mode`() {
        val lines = "星期一 体育（一）/(1-2节)7-19周/ 体育场1/殷乐\n" +
            "星期二 信息技术/(1-2节)12-14周(双),15-19周/（乌）E406/刘文杰"
        val result = CourseTableImporter.parse(PlainTextLayout.toBlocks(lines), lines)
        assertEquals(2, result.courses.size)
        assertTrue(result.courses.any { it.courseName == "体育（一）" })
    }

    @Test
    fun `normalizes share placeholders`() {
        val entry = CellEntryParser.parseCell("自习/(1-2节)1-16周/-/-").first()
        assertEquals("自习", entry.courseName)
        assertEquals("", entry.location)
        assertEquals("", entry.teacher)
    }

    // ---------------------------------------------------------------- 分享文本

    private fun sampleCourses(): List<CourseSchedule> = listOf(
        CourseSchedule(
            courseName = "体育（一）",
            teacher = "殷乐",
            semester = "2026-2027年第1学期",
            credit = null,
            rawWeekText = "7-19周",
            meetings = listOf(
                MeetingTime(
                    weekday = Weekday.MONDAY,
                    startSection = 1,
                    endSection = 2,
                    weekRules = listOf(WeekRule(7, 19, WeekParity.ALL)),
                    location = "体育场1",
                    campusId = null
                )
            )
        ),
        CourseSchedule(
            courseName = "信息技术",
            teacher = "刘文杰",
            semester = "2026-2027年第1学期",
            credit = null,
            rawWeekText = "12-14周(双),15-19周",
            meetings = listOf(
                MeetingTime(
                    weekday = Weekday.TUESDAY,
                    startSection = 1,
                    endSection = 2,
                    weekRules = listOf(WeekRule(12, 14, WeekParity.EVEN), WeekRule(15, 19, WeekParity.ALL)),
                    location = "E406",
                    campusId = "campus-wu"
                )
            )
        ),
        CourseSchedule(
            courseName = "自习",
            teacher = "",
            semester = "",
            credit = null,
            rawWeekText = "1-16周",
            meetings = listOf(
                MeetingTime(
                    weekday = Weekday.FRIDAY,
                    startSection = 9,
                    endSection = 10,
                    weekRules = listOf(WeekRule(1, 16, WeekParity.ALL)),
                    location = "",
                    campusId = null
                )
            )
        )
    )

    @Test
    fun `encodes share text with header and sorted rows`() {
        val text = ShareTextCodec.encode(
            courses = sampleCourses(),
            profileName = "默认课表",
            semester = "2026-2027年第1学期",
            semesterStart = LocalDate.of(2026, 8, 31),
            totalWeeks = 21,
            campusIdToAlias = mapOf("campus-wu" to "乌")
        )
        assertTrue(text.startsWith("# XClass 课表分享 v1"))
        assertTrue(text.contains("# 课表：默认课表"))
        assertTrue(text.contains("# 学期开始：2026-08-31"))
        assertTrue(text.contains("星期一 体育（一）/(1-2节)7-19周/体育场1/殷乐"))
        assertTrue(text.contains("星期二 信息技术/(1-2节)12-14周(双),15-19周/（乌）E406/刘文杰"))
        assertTrue(text.contains("星期五 自习/(9-10节)1-16周/-/-"))
    }

    @Test
    fun `share text round-trips back into courses`() {
        val text = ShareTextCodec.encode(
            courses = sampleCourses(),
            profileName = "默认课表",
            semester = "2026-2027年第1学期",
            semesterStart = LocalDate.of(2026, 8, 31),
            totalWeeks = 21,
            campusIdToAlias = mapOf("campus-wu" to "乌")
        )
        val result = CourseTableImporter.parse(PlainTextLayout.toBlocks(text), text)
        assertEquals(3, result.courses.size)

        val pe = result.courses.first { it.courseName == "体育（一）" }
        assertEquals("殷乐", pe.teacher)
        assertEquals(1, pe.meetings.first().startSection)
        assertEquals(2, pe.meetings.first().endSection)
        assertEquals(7, pe.meetings.first().weekRules.first().startWeek)

        val it = result.courses.first { it.courseName == "信息技术" }
        assertEquals("刘文杰", it.teacher)
        assertEquals("乌", it.meetings.first().campusAlias)
        assertEquals(WeekParity.EVEN, it.meetings.first().weekRules.first().parity)

        // 空地点/教师占位符还原为空
        val study = result.courses.first { it.courseName == "自习" }
        assertEquals("", study.teacher)
        assertEquals("", study.meetings.first().location)
    }

    // ---------------------------------------------------------------- 自定义上课时间（P2）

    @Test
    fun `share text carries custom time and round-trips`() {
        val custom = CourseSchedule(
            courseName = "英语（一）",
            teacher = "章斌",
            semester = "2026-2027年第1学期",
            credit = null,
            rawWeekText = "6-7周,9-17周,19周",
            meetings = listOf(
                MeetingTime(
                    weekday = Weekday.FRIDAY,
                    startSection = 5,
                    endSection = 6,
                    weekRules = listOf(WeekRule(6, 7, WeekParity.ALL), WeekRule(9, 17, WeekParity.ALL), WeekRule(19, 19, WeekParity.ALL)),
                    location = "多媒体202",
                    campusId = "campus-wu",
                    customStartTime = "13:30",
                    customEndTime = "15:10"
                )
            )
        )
        val text = ShareTextCodec.encode(
            courses = listOf(custom),
            totalWeeks = 21,
            campusIdToAlias = mapOf("campus-wu" to "乌")
        )
        assertTrue(text.contains("星期五 英语（一）/(5-6节)[13:30-15:10]6-7周,9-17周,19周/（乌）多媒体202/章斌"))

        val result = CourseTableImporter.parse(PlainTextLayout.toBlocks(text), text)
        val back = result.courses.first { it.courseName == "英语（一）" }
        assertEquals("13:30", back.meetings.first().customStartTime)
        assertEquals("15:10", back.meetings.first().customEndTime)
        // 周次解析不受自定义时间段污染：仍是三段
        assertEquals(3, back.meetings.first().weekRules.size)
        assertEquals(19, back.meetings.first().weekRules.last().endWeek)
    }

    @Test
    fun `effective time prefers custom over default section ranges`() {
        val meeting = MeetingTime(
            weekday = Weekday.FRIDAY,
            startSection = 5,
            endSection = 6,
            weekRules = listOf(WeekRule(1, 16, WeekParity.ALL)),
            location = "多媒体202",
            customStartTime = "13:30",
            customEndTime = "15:10"
        )
        assertEquals("13:30", meeting.effectiveStartTime(SectionTimes.DEFAULT_RANGES))
        assertEquals("15:10", meeting.effectiveEndTime(SectionTimes.DEFAULT_RANGES))
        assertEquals("13:30 - 15:10", meeting.effectiveTimeText(SectionTimes.DEFAULT_RANGES))
    }
}
