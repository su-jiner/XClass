package cn.xiao.xyaax.importing

/**
 * HTML 课表解析器（算法「格式检测与预处理」的 HTML 分支）：
 * 解析 `<table>` 的 `<tr>/<td>/<th>` 为统一二维网格，支持 colspan / rowspan 合并单元格，
 * 输出与 OCR/Excel/CSV 一致的 [TableGrid]，供通用解析管线使用。仅依赖正则，无第三方库。
 */
object HtmlTableParser {

    private val tableRegex = Regex("""(?is)<table\b[^>]*>(.*?)</table>""")
    private val trRegex = Regex("""(?is)<tr\b[^>]*>(.*?)</tr>""")
    private val cellRegex = Regex("""(?is)<(td|th)\b([^>]*)>(.*?)</\1>""")
    private val attrRegex = Regex("""(?is)\b(rowspan|colspan)\s*=\s*["']?\s*(\d+)""")

    private val tagRegex = Regex("""<[^>]+>""")

    /**
     * 解析 HTML 中所有课表表格。返回每个表格的二维网格（已处理合并单元格，缺位用空串补齐）。
     */
    fun parseTables(html: String): List<TableGrid> {
        if (html.isBlank()) return emptyList()
        return tableRegex.findAll(html).mapNotNull { tableMatch ->
            buildGrid(tableMatch.groupValues[1])
        }.filter { !it.isEmpty }.toList()
    }

    private fun buildGrid(tableBody: String): TableGrid? {
        val rows = trRegex.findAll(tableBody).map { it.groupValues[1] }.toList()
        if (rows.isEmpty()) return null

        // 二维数组构建：rowspan 的单元格在后续行对应列继承文本
        val grid = mutableListOf<MutableList<String>>()
        // rowspan 继承表：colIndex -> 剩余行数（该列当前沿用上方文本）
        val rowspanPending = mutableMapOf<Int, Int>()
        val rowspanText = mutableMapOf<Int, String>()

        rows.forEachIndexed { rowIndex, rowBody ->
            val cells = cellRegex.findAll(rowBody).map { match ->
                val tagName = match.groupValues[1]
                val attrs = match.groupValues[2]
                val raw = match.groupValues[3]
                HtmlCell(
                    text = decodeEntities(stripTags(raw)).trim(),
                    rowspan = attrValue(attrs, "rowspan"),
                    colspan = attrValue(attrs, "colspan")
                )
            }.toList()

            val rowCells = mutableListOf<String>()
            var col = 0
            cells.forEach { cell ->
                // 跳过被 rowspan 占用的列
                while (rowspanPending[col]?.let { it > 0 } == true) {
                    rowCells.add(rowspanText[col].orEmpty())
                    rowspanPending[col] = (rowspanPending[col] ?: 1) - 1
                    if (rowspanPending[col] == 0) rowspanText.remove(col)
                    col += 1
                }
                // 写入本单元格文本（colspan 重复填）
                repeat(cell.colspan) {
                    rowCells.add(cell.text)
                    col += 1
                }
                if (cell.rowspan > 1) {
                    rowspanPending[col - cell.colspan] = cell.rowspan - 1
                    rowspanText[col - cell.colspan] = cell.text
                }
            }
            // 行末继续继承尚未结束的 rowspan
            while (rowspanPending[col]?.let { it > 0 } == true) {
                rowCells.add(rowspanText[col].orEmpty())
                rowspanPending[col] = (rowspanPending[col] ?: 1) - 1
                if (rowspanPending[col] == 0) rowspanText.remove(col)
                col += 1
            }
            grid.add(rowCells)
        }

        // 补齐各行到最大列数
        val maxColumns = grid.maxOfOrNull { it.size } ?: 0
        grid.forEach { row -> while (row.size < maxColumns) row.add("") }
        return TableGrid(grid.map { it.toList() })
    }

    private fun attrValue(attrs: String, name: String): Int =
        attrRegex.findAll(attrs).firstOrNull { it.groupValues[1].equals(name, ignoreCase = true) }
            ?.groupValues?.get(2)?.toIntOrNull() ?: 1

    private fun stripTags(text: String): String = tagRegex.replace(text, " ")

    private fun decodeEntities(text: String): String {
        var result = text
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
        // 数字实体 &#123;
        result = Regex("""&#(\d+);""").replace(result) { match ->
            match.groupValues[1].toIntOrNull()?.let { code ->
                runCatching { String(Character.toChars(code)) }.getOrDefault(match.value)
            } ?: match.value
        }
        return result
    }

    private data class HtmlCell(
        val text: String,
        val rowspan: Int = 1,
        val colspan: Int = 1,
    )

    /** 把 HTML 表格网格转换为带行列坐标的文本块（供通用管线/分享文本导出使用）。 */
    fun gridToBlocks(grid: TableGrid): List<TextBlock> =
        grid.cells.flatMapIndexed { rowIndex, row ->
            row.mapIndexedNotNull { columnIndex, cell ->
                cell.takeIf { it.isNotEmpty() }?.let {
                    TextBlock(it, columnIndex * 300f, rowIndex * 40f, 280f, 36f, columnIndex, rowIndex)
                }
            }
        }
}
