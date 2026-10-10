package cn.xiao.xyaax.importing

import cn.xiao.xyaax.model.WeekParity
import cn.xiao.xyaax.model.WeekRule

/** 周次文本解析：支持 `7-19周`、`12-14周(双),15-19周`、`第1,3,5周（单）`、`单周`、`1-16`。 */
object WeekRuleTextParser {
    private val rangeRegex = Regex("""(\d{1,2})\s*[-—–~～至]\s*(\d{1,2})""")
    private val singleRegex = Regex("""\d{1,2}""")

    fun parse(raw: String, defaultTotalWeeks: Int = 20): List<WeekRule> {
        // 去掉节次段（含 "(1-2节)" / "第1-2节"），避免把节次当成周次
        val text = raw.replace(Regex("""[（(]?\s*第?\s*\d{1,2}\s*[-—–~～至]\s*\d{1,2}\s*节\s*[）)]?"""), " ").trim()
        if (text.isEmpty()) return listOf(WeekRule(1, defaultTotalWeeks, WeekParity.ALL))

        val globalParity = when {
            text.contains("单") -> WeekParity.ODD
            text.contains("双") -> WeekParity.EVEN
            else -> WeekParity.ALL
        }

        val tokens = text.split(',', '，', '、', ';', '；')
            .map { it.trim() }
            .filter { it.isNotEmpty() && it.any { ch -> ch.isDigit() } }

        val rules = mutableListOf<WeekRule>()
        tokens.forEach { token ->
            val parity = when {
                token.contains("单") -> WeekParity.ODD
                token.contains("双") -> WeekParity.EVEN
                else -> globalParity
            }
            val range = rangeRegex.find(token)
            if (range != null) {
                val start = range.groupValues[1].toInt()
                val end = range.groupValues[2].toInt()
                rules += WeekRule(minOf(start, end), maxOf(start, end), parity)
                return@forEach
            }
            singleRegex.findAll(token).forEach { match ->
                val week = match.value.toInt()
                if (week in 1..30) rules += WeekRule(week, week, parity)
            }
        }

        if (rules.isEmpty()) return listOf(WeekRule(1, defaultTotalWeeks, WeekParity.ALL))
        return rules.distinct().sortedWith(compareBy({ it.startWeek }, { it.endWeek }))
    }
}

/** 表头/行标签识别。 */
object ImportLabels {
    private val weekdayMap = listOf(
        listOf("星期一", "周一", "礼拜一", "mon", "monday"),
        listOf("星期二", "周二", "礼拜二", "tue", "tuesday"),
        listOf("星期三", "周三", "礼拜三", "wed", "wednesday"),
        listOf("星期四", "周四", "礼拜四", "thu", "thursday"),
        listOf("星期五", "周五", "礼拜五", "fri", "friday"),
        listOf("星期六", "周六", "礼拜六", "sat", "saturday"),
        listOf("星期日", "星期天", "周日", "周天", "sun", "sunday")
    )

    fun weekdayIndex(text: String): Int? {
        val normalized = text.trim().lowercase().replace(" ", "")
        if (normalized.isEmpty()) return null
        weekdayMap.forEachIndexed { index, aliases ->
            if (aliases.any { normalized.contains(it) }) return index + 1
        }
        return null
    }

    private val chineseSectionDigits = mapOf(
        "一" to 1, "二" to 2, "三" to 3, "四" to 4, "五" to 5, "六" to 6,
        "七" to 7, "八" to 8, "九" to 9, "十" to 10
    )

    /** 行标签「一…六」对应的 2 小节区块（一→1-2、二→3-4 … 六→11-12）。 */
    private val chineseRowBlocks = mapOf(
        "一" to 1, "二" to 3, "三" to 5, "四" to 7, "五" to 9, "六" to 11, "七" to 11, "八" to 11
    )

    /** 行标签 → 节次区间，例如「一」→1-2、「三四」→3-4、「9-12节」→9-12。 */
    fun sectionRange(text: String): IntRange? {
        val normalized = text.trim().replace(" ", "")
        if (normalized.isEmpty()) return null

        Regex("""(\d{1,2})\s*[-—–~～至]\s*(\d{1,2})""").find(normalized)?.let { match ->
            val start = match.groupValues[1].toInt()
            val end = match.groupValues[2].toInt()
            if (start in 1..12 && end in 1..12) return minOf(start, end)..maxOf(start, end)
        }

        val digits = Regex("""\d{1,2}""").findAll(normalized).map { it.value.toInt() }.toList()
        if (digits.size >= 2) return minOf(digits[0], digits[1])..maxOf(digits[0], digits[1])
        if (digits.size == 1) {
            val single = digits[0]
            if (normalized.contains('节') || normalized.length <= 2) {
                val start = if (single % 2 == 0) single - 1 else single
                return start..(start + 1).coerceAtMost(12)
            }
        }

        if (normalized.contains("十一") || normalized.contains("十二")) {
            val start = if (normalized.contains("十二")) 11 else 11
            return start..12
        }

        val rowStarts = normalized.mapNotNull { chineseRowBlocks[it.toString()] }
        if (rowStarts.isNotEmpty()) {
            val start = rowStarts.min()
            val end = if (rowStarts.size == 1) (start + 1) else (rowStarts.max() + 1)
            return start..end.coerceAtMost(12)
        }
        return null
    }
}

/** 单个单元格里的课程条目。 */
data class ParsedCellEntry(
    val courseName: String,
    val teacher: String,
    val location: String,
    val campusAlias: String?,
    val weekRules: List<WeekRule>,
    val rawText: String
)

/**
 * 单元格语法：`课程◇(节次)周次◇地点◇教师`，例如
 * `信息技术/(1-2节)12-14周(双),15-19周/（乌）E406/刘文杰`；
 * 一格多门课以换行分隔；地点里的 `（乌）` 提取为校区简称；教师尾部 `(小)` 等标记剥离。
 */
object CellEntryParser {
    private val separators = charArrayOf('/', '／', '⁄', '|')
    private val campusRegex = Regex("""^[（(]\s*([^）)]{1,4})\s*[）)]""")
    private val teacherTailRegex = Regex("""[（(][^）)]{1,3}[）)]\s*$""")
    private val sectionRegex = Regex("""[（(]?\s*(\d{1,2})\s*[-—–~～至]\s*(\d{1,2})\s*节?\s*[）)]?""")

    fun parseCell(text: String, defaultTotalWeeks: Int = 20): List<ParsedCellEntry> {
        return text.split('\n', '\r')
            .flatMap { it.split('；', ';') }
            .map { it.trim() }
            .filter { it.length >= 2 }
            .mapNotNull { parseEntry(it, defaultTotalWeeks) }
    }

    private fun parseEntry(raw: String, defaultTotalWeeks: Int): ParsedCellEntry? {
        val parts = raw.split(*separators).map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null

        val courseName = parts.first().trim()
        if (courseName.isEmpty()) return null
        // 噪声守卫：表头/页脚/说明文字不应成为课程
        if (ImportNoiseFilter.isNoiseCourseName(courseName)) return null

        val sectionAndWeeks = parts.getOrNull(1).orEmpty()
        val locationRaw = parts.getOrNull(2).orEmpty()
        val teacherRaw = parts.drop(3).joinToString("/").trim()
        val teacher = ImportNoiseFilter.normalizePlaceholder(teacherTailRegex.replace(teacherRaw, "").trim())

        val campusAlias = campusRegex.find(locationRaw)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
        val locationClean = ImportNoiseFilter.normalizePlaceholder(
            locationRaw.replaceFirst(campusRegex, "").trim().ifEmpty { locationRaw.trim() }
        )

        return ParsedCellEntry(
            courseName = courseName,
            teacher = teacher,
            location = locationClean,
            campusAlias = campusAlias,
            weekRules = WeekRuleTextParser.parse(sectionAndWeeks, defaultTotalWeeks),
            rawText = raw
        )
    }

    fun sectionsOf(text: String): IntRange? = sectionRegex.find(text)?.let { match ->
        val start = match.groupValues[1].toInt()
        val end = match.groupValues[2].toInt()
        if (start in 1..12 && end in 1..12) minOf(start, end)..maxOf(start, end) else null
    }
}

/**
 * 非课程噪声过滤：识别并丢弃课表图片/文本中「课程之外」的内容，
 * 例如表头（学期/课表名/专业）、行标签（上午/下午/晚上）、页脚注释（注一/打印时间/本学期）与
 * 「其他课程：」汇总行，避免它们被当作课程入库。四端（Android/iOS/鸿蒙/小程序）保持一致。
 */
object ImportNoiseFilter {
    /** 行级噪声前缀：整行以这些开头时整行跳过（表格模式与列表模式通用）。 */
    private val noiseLinePrefix = Regex(
        "^(其他课程[：:]?|注[一二三四五]?[：:]?|说明[：:]?|备注[：:]?|打印时间|本学期|内容顺序|" +
            "专业[：:]?|节次[：:]?|校区|上课时间|课程表?[：:]?|星期[一二三四五六日天]?[：:]?|" +
            "上午|下午|晚上|学期|\\d{4}\\s*[-—–~～至/]\\s*\\d{4}\\s*年?)"
    )

    /** 单元格级噪声前缀：课程名命中时丢弃该条目。 */
    private val noiseCellPrefix = Regex(
        "^(其他课程[：:]?|注[一二三四五]?[：:]?|说明[：:]?|备注[：:]?|打印时间|本学期|内容顺序|" +
            "专业[：:]?|节次[：:]?|校区|上课时间|课程表?[：:]?|星期[一二三四五六日天]$|上午|下午|晚上|" +
            "学期|\\d{4}\\s*[-—–~～至/]\\s*\\d{4}\\s*年?)"
    )

    private const val MAX_COURSE_NAME_LENGTH = 40

    /** 整行是否应被当作噪声跳过（不解析为课程）。 */
    fun isNoiseLine(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return true
        if (noiseLinePrefix.containsMatchIn(trimmed)) return true
        // 兜底：真正的课程行必然带「/」分隔或「节/周」标记；
        // 纯说明文字（如"2026-2027年第1学期"）不含这些结构，直接跳过。
        val hasCourseMark = trimmed.contains('/') || trimmed.contains('节') || trimmed.contains('周')
        return !hasCourseMark
    }

    /** 单元格中的课程名是否像噪声（丢弃）。 */
    fun isNoiseCourseName(name: String): Boolean {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return true
        if (trimmed.length > MAX_COURSE_NAME_LENGTH) return true
        if (noiseCellPrefix.containsMatchIn(trimmed)) return true
        // 课程名不应以时间/日期片段开头
        return trimmed.matches(Regex("""^\d{1,2}\s*[:：]\s*\d{2}"""))
    }

    /** 导出占位符归一化：分享文本中空字段用 "-" 占位，解析时还原为空。 */
    fun normalizePlaceholder(value: String): String =
        if (value.trim() == "-" || value.trim() == "—") "" else value.trim()
}

/** 页眉/页脚元数据。 */
object PageMetadataParser {
    private val semesterRegex = Regex("""(\d{4})\s*-\s*(\d{4})\s*年\s*第?\s*([一二1-3])\s*学期""")
    private val startDateRegex = Regex("""(\d{4})\s*年?\s*[-/年]\s*(\d{1,2})\s*[-/月]\s*(\d{1,2})""")
    private val totalWeeksRegex = Regex("""共\s*(\d{1,2})\s*周""")
    private val otherCoursesRegex = Regex("""其他课程[：:]\s*(.+)""")

    data class Metadata(
        val semester: String? = null,
        val startYear: Int? = null,
        val startMonth: Int? = null,
        val startDay: Int? = null,
        val totalWeeks: Int? = null,
        val otherCourseLines: List<String> = emptyList()
    )

    fun parse(text: String): Metadata {
        val semesterMatch = semesterRegex.find(text)
        val semester = semesterMatch?.let {
            "${it.groupValues[1]}-${it.groupValues[2]}年${termLabel(it.groupValues[3])}"
        }

        val startMatch = Regex("""本学期\s*(\d{4})\s*[-/年]\s*(\d{1,2})\s*[-/月]\s*(\d{1,2})""").find(text)
            ?: startDateRegex.find(text)

        val other = otherCoursesRegex.find(text)?.groupValues?.get(1)
            ?.split('；', ';')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()

        return Metadata(
            semester = semester,
            startYear = startMatch?.groupValues?.getOrNull(1)?.toIntOrNull(),
            startMonth = startMatch?.groupValues?.getOrNull(2)?.toIntOrNull(),
            startDay = startMatch?.groupValues?.getOrNull(3)?.toIntOrNull(),
            totalWeeks = totalWeeksRegex.findAll(text)
                .mapNotNull { it.groupValues.getOrNull(1)?.toIntOrNull() }
                .filter { it in 1..30 }
                .maxOrNull(),
            otherCourseLines = other
        )
    }

    private fun termLabel(raw: String): String = when (raw) {
        "一", "1" -> "第1学期"
        "二", "2" -> "第2学期"
        "三", "3" -> "第3学期"
        else -> "第${raw}学期"
    }
}
