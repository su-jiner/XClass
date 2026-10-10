package cn.xiao.xyaax.importing

/**
 * 从 PDF / 图片 OCR / 文本文件统一归一化出来的文本块（坐标为页面像素，左上原点）。
 */
data class TextBlock(
    val text: String,
    val x: Float,
    val y: Float,
    val width: Float = 0f,
    val height: Float = 0f,
    /** 表格结构中还原出来的列序号（0 基），非表格来源为 null */
    val column: Int? = null,
    /** 表格结构中还原出来的行序号（0 基），非表格来源为 null */
    val row: Int? = null
) {
    val centerX: Float get() = x + width / 2f
    val centerY: Float get() = y + height / 2f
}

/** 表格结构：行 × 列 的单元格文本（已按坐标聚类）。 */
data class TableGrid(val cells: List<List<String>>) {
    val rowCount: Int get() = cells.size
    val columnCount: Int get() = cells.maxOfOrNull { it.size } ?: 0
    val isEmpty: Boolean get() = cells.all { row -> row.all { it.isBlank() } }
}

/**
 * 把散落的文本块还原成表格：
 * 1. 以 y 方向投影聚类成行；2. 行内以 x 方向聚类成列；3. 按列数最多的一行确定总列数后对齐。
 */
object TableGridBuilder {
    private const val ROW_TOLERANCE_RATIO = 0.6f
    private const val COLUMN_GAP_RATIO = 0.9f

    fun build(blocks: List<TextBlock>): TableGrid {
        val grid = buildRows(blocks).map { row -> groupColumns(row, COLUMN_GAP_RATIO) }
        return TableGrid(grid)
    }

    /** 只做纵向分组建行，列由调用方按表头坐标对齐（比固定间距更稳）。 */
    fun buildRows(blocks: List<TextBlock>): List<List<TextBlock>> {
        val usable = blocks.filter { it.text.isNotBlank() }
        if (usable.isEmpty()) return emptyList()

        val avgHeight = usable.map { it.height }.filter { it > 0f }.average().takeIf { !it.isNaN() } ?: 20.0
        val rowTolerance = (avgHeight * ROW_TOLERANCE_RATIO).toFloat()

        val rows = mutableListOf<MutableList<TextBlock>>()
        usable.sortedBy { it.centerY }.forEach { block ->
            val current = rows.lastOrNull()
            val anchor = current?.firstOrNull()?.centerY
            if (current != null && anchor != null && kotlin.math.abs(block.centerY - anchor) <= rowTolerance) {
                current.add(block)
            } else {
                rows.add(mutableListOf(block))
            }
        }
        return rows.map { row -> row.sortedBy { it.x } }
    }

    private fun groupColumns(row: List<TextBlock>, gapRatio: Float): List<String> {
        if (row.isEmpty()) return emptyList()
        val sorted = row.sortedBy { it.x }
        val avgWidth = sorted.map { it.width }.filter { it > 0f }.average().takeIf { !it.isNaN() } ?: 40.0
        val gapTolerance = (avgWidth * gapRatio).toFloat().coerceAtLeast(12f)

        val cells = mutableListOf<StringBuilder>()
        var previousEnd = Float.NEGATIVE_INFINITY
        sorted.forEach { block ->
            val isNewCell = block.x - previousEnd > gapTolerance
            if (isNewCell || cells.isEmpty()) {
                cells.add(StringBuilder(block.text))
            } else {
                cells.last().append(' ').append(block.text)
            }
            previousEnd = (block.x + block.width).coerceAtLeast(block.x)
        }
        return cells.map { it.toString().trim() }
    }
}

/** 纯文本按行转换：每行一个块，非表格来源（txt/md/粘贴）走这里。 */
object PlainTextLayout {
    fun toBlocks(text: String): List<TextBlock> =
        text.lines().mapIndexedNotNull { index, line ->
            line.trim().takeIf { it.isNotEmpty() }?.let { TextBlock(it, 0f, index * 20f, it.length * 12f, 20f) }
        }
}
