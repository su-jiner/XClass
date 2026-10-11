package cn.xiao.xyaax.importing

import cn.xiao.xyaax.model.SectionTimeProfile
import cn.xiao.xyaax.model.WeekParity
import cn.xiao.xyaax.model.WeekRule
import cn.xiao.xyaax.model.Weekday
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 通用多源课表识别与合并算法（Universal Course Schedule Extraction & Merging Algorithm）核心单测：
 * HTML 表格重建、合并主键、冲突标记、时间→节次映射、输出格式。
 */
class UniversalScheduleAlgorithmTest {

    // ---------- HTML 表格重建（格式检测与预处理 / 表格结构重建） ----------

    @Test
    fun htmlBasicTable() {
        val html = """
            <table>
              <tr><th>节次</th><th>星期一</th><th>星期二</th></tr>
              <tr><td>1-2节</td><td>英语/(1-2节)1-16周/多媒体202/章斌</td><td></td></tr>
            </table>
        """.trimIndent()
        val grids = HtmlTableParser.parseTables(html)
        assertEquals(1, grids.size)
        val grid = grids[0]
        assertEquals(2, grid.rowCount)
        assertEquals(3, grid.columnCount)
        assertEquals("节次", grid.cells[0][0])
        assertEquals("星期一", grid.cells[0][1])
        assertTrue(grid.cells[1][1].contains("英语"))
    }

    @Test
    fun htmlColspanRowspan() {
        val html = """
            <table>
              <tr><td rowspan="2">上午</td><td>1-2节</td><td>语文/1-16周</td></tr>
              <tr><td>3-4节</td><td>数学/1-16周</td></tr>
            </table>
        """.trimIndent()
        val grids = HtmlTableParser.parseTables(html)
        val grid = grids[0]
        assertEquals(2, grid.rowCount)
        // rowspan 合并：第二行第一列继承「上午」
        assertEquals("上午", grid.cells[1][0])
        assertEquals("数学/1-16周", grid.cells[1][2])
    }

    @Test
    fun htmlEntitiesDecoded() {
        val html = "<table><tr><td>英语&amp;听力 &lt;A&gt; &#25968;&#23398;</td></tr></table>"
        val grids = HtmlTableParser.parseTables(html)
        assertEquals("英语&听力 <A> 数学", grids[0].cells[0][0].replace("\\s+".toRegex(), " ").trim())
    }

    // ---------- 合并与去重（多文件合并） ----------

    @Test
    fun mergeKeyIncludesLocation() {
        val a = ParsedCourse(
            courseName = "英语（一）", teacher = "章斌", semester = null,
            meetings = listOf(ParsedMeeting(Weekday.FRIDAY, 5, 6, listOf(WeekRule(6, 7)), "多媒体202", null))
        )
        val b = ParsedCourse(
            courseName = "英语（一）", teacher = "章斌", semester = null,
            meetings = listOf(ParsedMeeting(Weekday.FRIDAY, 5, 6, listOf(WeekRule(9, 17)), "多媒体202", null))
        )
        val c = ParsedCourse(
            courseName = "英语（一）", teacher = "章斌", semester = null,
            meetings = listOf(ParsedMeeting(Weekday.FRIDAY, 5, 6, listOf(WeekRule(9, 17)), "多媒体303", null))
        )
        val merged = UniversalMerge.merge(listOf(a, b, c))
        assertEquals(2, merged.size)
        // 同地点合并周次，异地点保留
        val sameLocation = merged.first { it.meetings.first().location == "多媒体202" }
        assertEquals(2, sameLocation.meetings.size)
    }

    @Test
    fun conflictDetectedWithWeekOverlap() {
        val a = ParsedCourse(
            courseName = "英语（一）", teacher = "章斌", semester = null,
            meetings = listOf(ParsedMeeting(Weekday.FRIDAY, 5, 6, listOf(WeekRule(1, 20)), "多媒体202", null))
        )
        val b = ParsedCourse(
            courseName = "体育（一）", teacher = "张爽", semester = null,
            meetings = listOf(ParsedMeeting(Weekday.FRIDAY, 5, 6, listOf(WeekRule(7, 19)), "操场", null))
        )
        val conflicts = UniversalMerge.detectConflicts(listOf(a, b))
        assertTrue(conflicts.any { it.contains("时间冲突") && it.contains("英语（一）") && it.contains("体育（一）") })
    }

    @Test
    fun noConflictWhenWeeksDisjoint() {
        val a = ParsedCourse(
            courseName = "英语（一）", teacher = "章斌", semester = null,
            meetings = listOf(ParsedMeeting(Weekday.FRIDAY, 5, 6, listOf(WeekRule(1, 6)), "多媒体202", null))
        )
        val b = ParsedCourse(
            courseName = "体育（一）", teacher = "张爽", semester = null,
            meetings = listOf(ParsedMeeting(Weekday.FRIDAY, 5, 6, listOf(WeekRule(7, 19)), "操场", null))
        )
        assertTrue(UniversalMerge.detectConflicts(listOf(a, b)).isEmpty())
    }

    @Test
    fun parityCompatibleCheck() {
        val odd = listOf(WeekRule(2, 14, WeekParity.ODD))
        val even = listOf(WeekRule(2, 14, WeekParity.EVEN))
        val all = listOf(WeekRule(2, 14, WeekParity.ALL))
        assertFalse(UniversalMerge.weekRulesOverlap(odd, even))
        assertTrue(UniversalMerge.weekRulesOverlap(odd, all))
    }

    // ---------- 时间→节次映射（节次映射与时间对齐） ----------

    @Test
    fun timeMapsToSection() {
        val profile = SectionTimeProfile(
            id = "campus", name = "乌江路/泰兴路校区",
            blocks = listOf(
                cn.xiao.xyaax.model.SectionBlock(5, 6, "13:30", "15:10"),
                cn.xiao.xyaax.model.SectionBlock(7, 8, "15:30", "17:10")
            )
        )
        val mapped = TimeToSectionMapper.map("高等数学 13:30-15:10 多媒体202", listOf(profile))
        assertNotNull(mapped)
        assertEquals(5, mapped!!.first)
        assertEquals(6, mapped.last)
        assertFalse(TimeToSectionMapper.containsTime("高等数学 1-16周"))
        assertTrue(TimeToSectionMapper.containsTime("13:30-15:10"))
    }

    // ---------- 输出格式（Markdown / JSON / CSV / ICS） ----------

    private fun sampleCourse(): ParsedCourse = ParsedCourse(
        courseName = "英语（一）", teacher = "章斌", semester = "2026-2027年第1学期",
        meetings = listOf(
            ParsedMeeting(
                Weekday.FRIDAY, 5, 6,
                listOf(WeekRule(6, 7), WeekRule(9, 17, WeekParity.ODD)),
                "(乌)多媒体202", "乌"
            )
        )
    )

    @Test
    fun markdownOutput() {
        val md = ScheduleExporter.toMarkdown(listOf(sampleCourse()))
        assertTrue(md.contains("| 星期五 | 5-6节 | 英语（一） | 章斌 | (乌)多媒体202 |"))
    }

    @Test
    fun csvOutput() {
        val csv = ScheduleExporter.toCsv(listOf(sampleCourse()))
        assertTrue(csv.startsWith("course_name,teacher,location"))
        assertTrue(csv.contains("英语（一）,章斌,(乌)多媒体202,星期五,5,6"))
    }

    @Test
    fun jsonOutput() {
        val json = ScheduleExporter.toJson(
            listOf(sampleCourse()),
            semester = "2026-2027年第1学期",
            semesterStartDate = "2026-08-31",
            totalWeeks = 21
        )
        assertTrue(json.contains("\"course_name\": \"英语（一）\""))
        assertTrue(json.contains("\"day_of_week\": \"星期五\""))
        assertTrue(json.contains("\"weeks\": [6,7,9,11,13,15,17]"))
    }

    @Test
    fun icsOutputExpandsWeeks() {
        val ics = ScheduleExporter.toIcs(listOf(sampleCourse()), "2026-08-31", 21)
        assertTrue(ics.startsWith("BEGIN:VCALENDAR"))
        assertTrue(ics.contains("SUMMARY:英语（一）"))
        // 第6周周五 = 2026-08-31 + 5周 + 4天 = 2026-10-09
        assertTrue(ics.contains("DTSTART:20261009T133000"))
        assertTrue(ics.endsWith("END:VCALENDAR\n"))
        // 周次展开 {6,7,9,11,13,15,17} → 7 个 VEVENT
        val events = ics.lines().count { it == "BEGIN:VEVENT" }
        assertEquals(7, events)
    }

    @Test
    fun expandWeeksRespectsParity() {
        val weeks = ScheduleExporter.expandWeeks(
            listOf(WeekRule(12, 19, WeekParity.EVEN)),
            totalWeeks = 21
        )
        assertEquals(listOf(12, 14, 16, 18), weeks)
    }
}
