package cn.xiao.xyaax.importing

import cn.xiao.xyaax.model.WeekParity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 用真实教务课表文本（含多校区标记）回归单元格语法与表格重建。 */
class CourseTableImporterTest {

    @Test
    fun `parses real cell texts from printed timetable`() {
        val cases = listOf(
            Triple("体育（一）/(1-2节)7-19周/ 体育场1/殷乐", "体育（一）", "殷乐"),
            Triple("信息技术/(1-2节)12-14周(双),15-19周/（乌）E406/刘文杰", "信息技术", "刘文杰"),
            Triple("思想道德与法治/(1-2节)7-17周/（乌）报告厅506/张娜(小)", "思想道德与法治", "张娜"),
            Triple("计算机网路技术/(1-2节)7-14周,16-19周/（乌）多媒体202/张晓锋", "计算机网路技术", "张晓锋"),
            Triple("操作系统管理/(5-6节)8-19周/（乌）E504/李健波", "操作系统管理", "李健波"),
            Triple("程序设计基础（python）/(5-6节)7-14周/（乌）E504/张铮", "程序设计基础（python）", "张铮"),
            Triple("信息技术/(5-6节)19周/（乌）E406/刘文杰", "信息技术", "刘文杰"),
            Triple("创新创业教育/(5-6节)10-13周/（乌）多媒体207/刘魏", "创新创业教育", "刘魏"),
            Triple("新时代大学生心理健康/(5-6节)14-15周/（乌）多媒体201/靳云华", "新时代大学生心理健康", "靳云华"),
            Triple("形势与政策5-1/(5-6节)16-17周/（乌）多媒体202/张娜 (小)", "形势与政策5-1", "张娜"),
            Triple("英语（一）/(5-6节)6-7周,9-17周,19周/（乌）多媒体202/章斌", "英语（一）", "章斌")
        )

        cases.forEach { (raw, expectedName, expectedTeacher) ->
            val entries = CellEntryParser.parseCell(raw)
            assertEquals("应从单元格解析出一门课: $raw", 1, entries.size)
            val entry = entries.first()
            assertEquals(expectedName, entry.courseName)
            assertEquals(expectedTeacher, entry.teacher)
        }
    }

    @Test
    fun `extracts campus alias and sections from cell`() {
        val entry = CellEntryParser.parseCell("信息技术/(1-2节)12-14周(双),15-19周/（乌）E406/刘文杰").first()
        assertEquals("乌", entry.campusAlias)
        assertEquals("E406", entry.location)
        assertEquals(2, entry.weekRules.size)
        assertEquals(WeekParity.EVEN, entry.weekRules.first().parity)
        assertEquals(12, entry.weekRules.first().startWeek)
        assertEquals(15, entry.weekRules.last().startWeek)
        assertEquals(1..2, CellEntryParser.sectionsOf("信息技术/(1-2节)12-14周(双),15-19周/（乌）E406/刘文杰"))
    }

    @Test
    fun `parses week list with mixed ranges and singles`() {
        val rules = WeekRuleTextParser.parse("(5-6节)6-7周,9-17周,19周")
        assertEquals(listOf(6 to 7, 9 to 17, 19 to 19), rules.map { it.startWeek to it.endWeek })
        assertTrue(rules.all { it.parity == WeekParity.ALL })
    }

    @Test
    fun `parses discrete weeks with parity`() {
        val rules = WeekRuleTextParser.parse("第1,3,5周（单）")
        assertEquals(listOf(1, 3, 5), rules.map { it.startWeek })
        assertTrue(rules.all { it.parity == WeekParity.ODD })
    }

    @Test
    fun `maps section row labels`() {
        assertEquals(1..2, ImportLabels.sectionRange("一"))
        assertEquals(3..4, ImportLabels.sectionRange("二"))
        assertEquals(5..8, ImportLabels.sectionRange("三四"))
        assertEquals(9..12, ImportLabels.sectionRange("9-12节"))
    }

    @Test
    fun `reads header metadata and other courses`() {
        val footer = "其他课程：军训技能张爽(共19周)/1-19周； 入学教育张爽(共19周)/1-19周；\n" +
            "本学期2026-08-31正式上课至2027-01-22结束，共21周.\n" +
            "2026-2027年第1学期 云计算261课表"
        val metadata = PageMetadataParser.parse(footer)
        assertEquals("2026-2027年第1学期", metadata.semester)
        assertEquals(21, metadata.totalWeeks)
        assertEquals(2026, metadata.startYear)
        assertEquals(8, metadata.startMonth)
        assertEquals(31, metadata.startDay)
        assertEquals(2, metadata.otherCourseLines.size)
    }

    @Test
    fun `rebuilds grid from positioned text blocks`() {
        val blocks = mutableListOf<TextBlock>()
        val weekdays = listOf("星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日")
        weekdays.forEachIndexed { index, label ->
            blocks += TextBlock(label, 200f + index * 300f, 0f, 120f, 30f)
        }
        blocks += TextBlock("节次", 0f, 0f, 100f, 30f)
        blocks += TextBlock("一", 0f, 60f, 60f, 30f)
        blocks += TextBlock("体育（一）/(1-2节)7-19周/ 体育场1/殷乐", 200f, 60f, 260f, 30f)
        blocks += TextBlock("信息技术/(1-2节)12-14周(双),15-19周/（乌）E406/刘文杰", 500f, 60f, 260f, 30f)

        val result = CourseTableImporter.parse(blocks, blocks.joinToString("\n") { it.text })
        assertEquals(2, result.courses.size)
        val pe = result.courses.first { it.courseName.startsWith("体育") }
        assertEquals(1, pe.meetings.size)
        assertEquals(1, pe.meetings.first().startSection)
        assertEquals(2, pe.meetings.first().endSection)
        val it = result.courses.first { it.courseName == "信息技术" }
        assertEquals("乌", it.meetings.first().campusAlias)
        assertTrue(result.campusAliases.contains("乌"))
    }

    @Test
    fun `splits multiple courses inside one cell`() {
        val cell = "程序设计基础（python）/(5-6节)7-14周/（乌）E504/张铮\n信息技术/(5-6节)19周/（乌）E406/刘文杰"
        val entries = CellEntryParser.parseCell(cell)
        assertEquals(2, entries.size)
        assertEquals("程序设计基础（python）", entries[0].courseName)
        assertEquals("信息技术", entries[1].courseName)
    }

    @Test
    fun `parses multi campus section time tables`() {
        val text = """
            真理道校区上课时间
            节次 | 教学楼、体育场 | 实训楼
            12节 | 8:30-10:05 | 8:30-10:05
            34节 | 10:25-12:00 | 10:10-11:30
            56节 | 13:30-15:05 | 13:30-15:05
            78节 | 15:20-16:55 | 15:20-16:55
            9-12节 | 18:00-20:30 | 18:00-20:30
        """.trimIndent()

        val profiles = TimeTableTextParser.parse(text)
        assertEquals(2, profiles.size)
        val building = profiles.first { it.name.contains("教学楼") }
        val training = profiles.first { it.name.contains("实训楼") }
        assertEquals("真理道校区·教学楼、体育场", building.name)
        assertEquals("10:25-12:00", building.timeRange(3, 4)?.let { "${it.first}-${it.second}" })
        assertEquals("10:10-11:30", training.timeRange(3, 4)?.let { "${it.first}-${it.second}" })
        assertEquals("18:00-20:30", building.timeRange(9, 12)?.let { "${it.first}-${it.second}" })
    }

    @Test
    fun `parses shared section table for two campuses`() {
        val text = """
            乌江路、泰兴路校区上课时间
            12节 | 8:30-10:05
            34节 | 10:20-11:55
            56节 | 13:30-15:05
            78节 | 15:20-16:55
            9-12节 | 18:00-20:30
        """.trimIndent()

        val profiles = TimeTableTextParser.parse(text)
        assertEquals(1, profiles.size)
        val profile = profiles.first()
        assertNotNull(profile.timeRange(1, 2))
        assertEquals("10:20-11:55", profile.timeRange(3, 4)?.let { "${it.first}-${it.second}" })
    }

    @Test
    fun `parses plain text timetable lines`() {
        val lines = """
            星期一 第1-2节 高等数学 A101 张三
            星期三 第5-6节 大学物理 B202 李四
        """.trimIndent()
        val blocks = PlainTextLayout.toBlocks(lines)
        val result = CourseTableImporter.parse(blocks, lines)
        assertEquals(2, result.courses.size)
        assertEquals(1, result.courses.first().meetings.first().weekday.ordinal + 1)
    }
}
