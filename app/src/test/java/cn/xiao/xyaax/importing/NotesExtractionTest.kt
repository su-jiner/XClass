package cn.xiao.xyaax.importing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 课表级底部备注（notes）提取规则回归：备注 = 其他课程行 + 名称（教师）周次行；学期时间行不收录。 */
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
    fun `semester time date range line is not a note`() {
        // 学期时间行（本学期…至…，共N周）不属于备注，不再收录显示
        val text = "学期时间：2026-08-31至2027-01-22，共21周"
        val notes = PageMetadataParser.extractNotes(text)
        assertTrue(notes.isEmpty())
    }

    @Test
    fun `total-weeks only line is not a note`() {
        val text = "本学期共21周"
        val notes = PageMetadataParser.extractNotes(text)
        assertTrue(notes.isEmpty())
    }

    @Test
    fun `total-weeks line with holiday word is not a note`() {
        // '节假日' 含 '节' 但不是节次标记；学期时间/共N周行不属于备注
        val text = "本学期共21周（含节假日调休）"
        val notes = PageMetadataParser.extractNotes(text)
        assertTrue(notes.isEmpty())
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
    fun `other-courses summary line becomes the note content`() {
        // 备注 = 其他课程（用户指定）；其他课程汇总行进入备注，同时仍保留导入 warnings 提示
        val text = "其他课程：军训技能张爽(共19周)/1-19周； 入学教育张爽(共19周)/1-19周；"
        val notes = PageMetadataParser.extractNotes(text)
        assertEquals(1, notes.size)
        assertEquals(text, notes[0])
    }

    @Test
    fun `ocr-variant other-courses line is normalized in stored notes`() {
        // 旧数据 OCR 把「其」识别成「淇」：读取时规范为「其他课程：」，学期时间行被丢弃
        val stored = listOf(
            "淇他课程:军事技能张爽(共19周)/1-19周;入学教育张爽(共19周)/1-19周;",
            "本学期2026-08-31正式上课至202701-22结束,共21周."
        )
        val notes = PageMetadataParser.sanitizeStoredNotes(stored)
        assertEquals(1, notes.size)
        assertTrue(notes[0].startsWith("其他课程："))
        assertTrue(notes[0].contains("军事技能张爽"))
    }

    @Test
    fun `stored semester time line is dropped by sanitize`() {
        val stored = listOf("本学期2026-08-31正式上课至202701-22结束,共21周.")
        val notes = PageMetadataParser.sanitizeStoredNotes(stored)
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
            本学期2026-08-31正式上课至2027-01-22结束，共21周
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
        // 学期时间行不再收录；只保留名称（教师）周次行
        assertEquals(1, withNotes.notes.size)
        assertTrue(withNotes.notes.contains("军事技能（张爽）1-19周"))
    }
}
