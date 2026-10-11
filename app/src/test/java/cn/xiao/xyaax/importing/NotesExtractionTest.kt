package cn.xiao.xyaax.importing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 课表级底部备注（notes）提取规则回归：a/b/c 模式、去重、上限、课程行与其他课程行排除。 */
class NotesExtractionTest {

    @Test
    fun `rule a collects parenthesized teacher week-range note lines`() {
        val text = """
            军事技能（张爽）1-19周
            入学教育（张爽）1-19周
        """.trimIndent()
        val notes = PageMetadataParser.extractNotes(text)
        assertEquals(
            listOf("军事技能（张爽）1-19周", "入学教育（张爽）1-19周"),
            notes
        )
    }

    @Test
    fun `rule b collects semester time date range line`() {
        val text = "学期时间：2026-08-31至2027-01-22，共21周"
        val notes = PageMetadataParser.extractNotes(text)
        assertEquals(listOf("学期时间：2026-08-31至2027-01-22，共21周"), notes)
    }

    @Test
    fun `rule c collects total-weeks line`() {
        val text = "本学期共21周"
        val notes = PageMetadataParser.extractNotes(text)
        assertEquals(listOf("本学期共21周"), notes)
    }

    @Test
    fun `keeps total-weeks note that contains holiday word`() {
        // '节假日' 含 '节' 但不是节次标记，共N周备注应照常收录
        val text = "本学期共21周（含节假日调休）"
        val notes = PageMetadataParser.extractNotes(text)
        assertEquals(listOf("本学期共21周（含节假日调休）"), notes)
    }

    @Test
    fun `excludes course lines even when they look like rule a`() {
        // 单元格课程行里含 (1-2节)7-19周，命中 a 的表面形态，但带节次标记，必须排除
        val text = "体育（一）/(1-2节)7-19周/ 体育场1/殷乐"
        val notes = PageMetadataParser.extractNotes(text)
        assertTrue(notes.isEmpty())
    }

    @Test
    fun `excludes weekday section list-style course lines`() {
        val text = "星期一 第1-2节 高等数学 A101 张三"
        val notes = PageMetadataParser.extractNotes(text)
        assertTrue(notes.isEmpty())
    }

    @Test
    fun `excludes other-courses summary line which stays in warnings`() {
        val text = "其他课程：军训技能张爽(共19周)/1-19周； 入学教育张爽(共19周)/1-19周；"
        val notes = PageMetadataParser.extractNotes(text)
        assertTrue(notes.isEmpty())
    }

    @Test
    fun `dedupes by exact string and keeps order`() {
        val text = """
            军事技能（张爽）1-19周
            军事技能（张爽）1-19周
            入学教育（张爽）1-19周
        """.trimIndent()
        val notes = PageMetadataParser.extractNotes(text)
        assertEquals(
            listOf("军事技能（张爽）1-19周", "入学教育（张爽）1-19周"),
            notes
        )
    }

    @Test
    fun `caps at 10 notes`() {
        val lines = (1..15).joinToString("\n") { "备注条目$it（老师）1-19周" }
        val notes = PageMetadataParser.extractNotes(lines)
        assertEquals(10, notes.size)
    }

    @Test
    fun `empty when no matching lines`() {
        val text = """
            2026-2027年第1学期 云计算261课表
            星期一 第1-2节 高等数学 A101 张三
        """.trimIndent()
        val notes = PageMetadataParser.extractNotes(text)
        assertTrue(notes.isEmpty())
    }

    @Test
    fun `parse result exposes notes and defaults to empty list`() {
        // 无备注文本：courses 为空时 notes 仍默认为空列表
        val parsed = CourseTableImporter.parse(emptyList(), "")
        assertTrue(parsed.notes.isEmpty())

        val text = """
            军事技能（张爽）1-19周
            学期时间：2026-08-31至2027-01-22，共21周
        """.trimIndent()
        val withNotes = CourseTableImporter.parse(emptyList(), text)
        assertEquals(2, withNotes.notes.size)
        assertTrue(withNotes.notes.contains("军事技能（张爽）1-19周"))
    }
}
