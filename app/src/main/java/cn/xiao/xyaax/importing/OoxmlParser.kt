package cn.xiao.xyaax.importing

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader

/**
 * 极简 OOXML（docx / xlsx）解析：只依赖系统 XmlPullParser，直接读 zip 里的 XML。
 * docx 取段落与表格单元格（带行列坐标），xlsx 按单元格引用（如 B3）还原表格。
 */
object OoxmlParser {

    private const val CELL_WIDTH = 300f
    private const val ROW_HEIGHT = 40f

    fun parseDocx(xml: String): List<TextBlock> {
        val blocks = mutableListOf<TextBlock>()
        var paragraph = StringBuilder()
        var rowIndex = 0
        var columnIndex = 0
        var freeRowIndex = 0
        var inTable = false
        var inCell = false

        val parser = Xml.newPullParser().apply { setInput(StringReader(xml)) }
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name.removePrefix("w:")) {
                    "tbl" -> { inTable = true; rowIndex = 0 }
                    "tr" -> { columnIndex = 0 }
                    "tc" -> { inCell = true; paragraph.setLength(0) }
                    "t" -> paragraph.append(parser.nextText())
                    "tab" -> paragraph.append('\t')
                }

                XmlPullParser.END_TAG -> when (parser.name.removePrefix("w:")) {
                    "p" -> {
                        val text = paragraph.toString().trim()
                        paragraph = StringBuilder()
                        if (text.isNotEmpty()) {
                            if (inTable && inCell) {
                                blocks += TextBlock(text, columnIndex * CELL_WIDTH, rowIndex * ROW_HEIGHT, CELL_WIDTH - 10f, ROW_HEIGHT - 4f, columnIndex, rowIndex)
                            } else if (!inTable) {
                                blocks += TextBlock(text, 0f, freeRowIndex * ROW_HEIGHT, text.length * 12f, ROW_HEIGHT)
                                freeRowIndex++
                            }
                        }
                    }

                    "tc" -> { inCell = false; columnIndex++ }
                    "tr" -> rowIndex++
                    "tbl" -> inTable = false
                }
            }
            event = parser.next()
        }
        return blocks
    }

    fun parseSharedStrings(xml: String): List<String> {
        val values = mutableListOf<String>()
        var current = StringBuilder()
        var inItem = false
        val parser = Xml.newPullParser().apply { setInput(StringReader(xml)) }
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "si" -> { inItem = true; current = StringBuilder() }
                    "t" -> if (inItem) current.append(parser.nextText())
                }

                XmlPullParser.END_TAG -> if (parser.name == "si") {
                    values += current.toString()
                    inItem = false
                }
            }
            event = parser.next()
        }
        return values
    }

    fun parseSheet(xml: String, sharedStrings: List<String>): List<TextBlock> {
        val blocks = mutableListOf<TextBlock>()
        var rowIndex = 0
        var cellColumn = 0
        var cellType = ""
        var cellRef = ""
        var value = StringBuilder()
        var inValue = false
        var inInline = false

        val parser = Xml.newPullParser().apply { setInput(StringReader(xml)) }
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "row" -> rowIndex = (parser.getAttributeValue(null, "r")?.toIntOrNull() ?: (rowIndex + 1)) - 1
                    "c" -> {
                        cellType = parser.getAttributeValue(null, "t").orEmpty()
                        cellRef = parser.getAttributeValue(null, "r").orEmpty()
                        cellColumn = columnIndex(cellRef).takeIf { it >= 0 } ?: cellColumn
                        value = StringBuilder()
                        inValue = false
                    }

                    "v" -> { inValue = true; value.append(parser.nextText()) }
                    "is" -> inInline = true
                    "t" -> if (inInline) value.append(parser.nextText())
                }

                XmlPullParser.END_TAG -> when (parser.name) {
                    "c" -> {
                        val raw = value.toString().trim()
                        val text = when {
                            raw.isEmpty() -> ""
                            cellType == "s" -> sharedStrings.getOrNull(raw.toIntOrNull() ?: -1).orEmpty()
                            else -> raw
                        }
                        if (text.isNotBlank()) {
                            blocks += TextBlock(text, cellColumn * CELL_WIDTH, rowIndex * ROW_HEIGHT, CELL_WIDTH - 10f, ROW_HEIGHT - 4f, cellColumn, rowIndex)
                        }
                        inValue = false
                        inInline = false
                    }
                }
            }
            event = parser.next()
        }
        return blocks
    }

    /** "B3" → 1（0 基列号） */
    private fun columnIndex(ref: String): Int {
        var result = -1
        for (char in ref) {
            if (!char.isLetter()) break
            result = if (result < 0) (char.uppercaseChar() - 'A') else result * 26 + (char.uppercaseChar() - 'A' + 1)
        }
        return result
    }
}
