package cn.xiao.xyaax.importing

import cn.xiao.xyaax.model.CourseSchedule
import cn.xiao.xyaax.model.MeetingTime
import cn.xiao.xyaax.model.SectionBlock
import cn.xiao.xyaax.model.SectionTimeProfile
import cn.xiao.xyaax.model.WeekRule
import cn.xiao.xyaax.model.Weekday

/** 导入中间结果中的一节课。 */
data class ParsedMeeting(
    val weekday: Weekday,
    val startSection: Int,
    val endSection: Int,
    val weekRules: List<WeekRule>,
    val location: String,
    val campusAlias: String?
)

/** 导入中间结果中的一门课（可含多次上课时间）。 */
data class ParsedCourse(
    val courseName: String,
    val teacher: String,
    val semester: String?,
    val meetings: List<ParsedMeeting>,
    val warnings: List<String> = emptyList()
) {
    fun rawWeekText(): String = meetings.firstOrNull()?.let { meeting ->
        meeting.weekRules.joinToString(",") { rule ->
            val base = if (rule.startWeek == rule.endWeek) "${rule.startWeek}周" else "${rule.startWeek}-${rule.endWeek}周"
            when (rule.parity) {
                cn.xiao.xyaax.model.WeekParity.ODD -> "$base(单)"
                cn.xiao.xyaax.model.WeekParity.EVEN -> "$base(双)"
                else -> base
            }
        }
    }.orEmpty()

    fun toCourseSchedule(campusIdResolver: (String?) -> String?): CourseSchedule = CourseSchedule(
        courseName = courseName,
        teacher = teacher,
        semester = semester.orEmpty(),
        credit = null,
        rawWeekText = rawWeekText(),
        meetings = meetings.map { meeting ->
            MeetingTime(
                weekday = meeting.weekday,
                startSection = meeting.startSection,
                endSection = meeting.endSection,
                weekRules = meeting.weekRules,
                location = meeting.location,
                campusId = campusIdResolver(meeting.campusAlias)
            )
        }
    )
}

/** 解析总结果。 */
data class CourseTableParseResult(
    val courses: List<ParsedCourse>,
    val semester: String?,
    val semesterStartDate: String?,
    val totalWeeks: Int?,
    val timeTables: List<SectionTimeProfile>,
    val campusAliases: List<String>,
    val warnings: List<String>,
    /** 课表级公共备注（每行原样保留，最多 10 条） */
    val notes: List<String> = emptyList()
)

/**
 * 课表导入解析入口：把「文本块 + 原文」解析成课程、元数据与节次时间表。
 * 只依赖纯逻辑，便于单元测试；文件读取与 OCR 由 DocumentTextExtractor 负责。
 */
object CourseTableImporter {

    fun parse(blocks: List<TextBlock>, rawText: String): CourseTableParseResult {
        val metadata = PageMetadataParser.parse(rawText.ifBlank { blocks.joinToString("\n") { it.text } })
        val totalWeeks = metadata.totalWeeks ?: 20
        val warnings = mutableListOf<String>()

        val gridRows = TableGridBuilder.buildRows(blocks)
        val headerRowIndex = findHeaderRow(gridRows)
        val columnAnchors = headerRowIndex?.let { headerColumns(gridRows[it]) }.orEmpty()

        val collected = mutableListOf<ParsedCourse>()
        if (headerRowIndex != null && columnAnchors.isNotEmpty()) {
            for (rowIndex in (headerRowIndex + 1) until gridRows.size) {
                val rowBlocks = gridRows[rowIndex]
                // 行级噪声过滤：表头/页脚/「其他课程」/「注一」等整行文本不得进入星期列
                val rowText = rowBlocks.joinToString(" ") { it.text }
                if (ImportNoiseFilter.isNoiseLine(rowText)) continue
                val row = rowCells(rowBlocks, columnAnchors)
                if (row.isEmpty()) continue
                val rowSectionRange = row.firstOrNull()?.let { ImportLabels.sectionRange(it) }
                columnAnchors.forEachIndexed { columnIndex, anchor ->
                    val weekday = anchor.second
                    val cell = row.getOrNull(columnIndex + 1)?.trim().orEmpty()
                    if (cell.isEmpty()) return@forEachIndexed
                    val entries = CellEntryParser.parseCell(cell, totalWeeks)
                    if (entries.isEmpty()) {
                        warnings += "第 ${rowIndex + 1} 行「$cell」未能识别，建议手动补充"
                        return@forEachIndexed
                    }
                    entries.forEach { entry ->
                        val sections = CellEntryParser.sectionsOf(entry.rawText) ?: rowSectionRange
                        if (sections == null) {
                            warnings += "「${entry.courseName}」缺少节次信息，已跳过"
                            return@forEachIndexed
                        }
                        collected += ParsedCourse(
                            courseName = entry.courseName,
                            teacher = entry.teacher,
                            semester = metadata.semester,
                            meetings = listOf(
                                ParsedMeeting(
                                    weekday = weekday,
                                    startSection = sections.first,
                                    endSection = sections.last,
                                    weekRules = entry.weekRules,
                                    location = entry.location,
                                    campusAlias = entry.campusAlias
                                )
                            )
                        )
                    }
                }
            }
        } else {
            collected += parseListStyle(blocks, totalWeeks, warnings)
        }

        // 表外课程（「其他课程：」汇总行）：缺少星期/节次，无法放入课表网格，仅提示
        if (metadata.otherCourseLines.isNotEmpty()) {
            warnings += "检测到表外课程（缺少星期与节次，未纳入课表）：" +
                metadata.otherCourseLines.take(5).joinToString("；")
        }

        val merged = mergeCourses(collected)
        val timeTables = TimeTableTextParser.parse(rawText.ifBlank { blocks.joinToString("\n") { it.text } })
        val aliases = merged.flatMap { course -> course.meetings.mapNotNull { it.campusAlias } }.distinct()

        return CourseTableParseResult(
            courses = merged,
            semester = metadata.semester,
            semesterStartDate = metadata.startYear?.let { year ->
                "%04d-%02d-%02d".format(year, metadata.startMonth ?: 9, metadata.startDay ?: 1)
            },
            totalWeeks = metadata.totalWeeks,
            timeTables = timeTables,
            campusAliases = aliases,
            warnings = warnings,
            notes = metadata.notes
        )
    }

    private fun findHeaderRow(rows: List<List<TextBlock>>): Int? {
        rows.forEachIndexed { rowIndex, row ->
            val weekdays = row.count { ImportLabels.weekdayIndex(it.text) != null }
            if (weekdays >= 4) return rowIndex
        }
        return null
    }

    /** 表头 → 列锚点：列中心的 x 坐标 + 对应的星期。 */
    private fun headerColumns(headerRow: List<TextBlock>): List<Pair<Float, Weekday>> =
        headerRow.mapNotNull { block ->
            val index = ImportLabels.weekdayIndex(block.text) ?: return@mapNotNull null
            block.centerX to Weekday.entries[index - 1]
        }.sortedBy { it.first }

    /**
     * 按表头列锚点把一行的文本块分到各列：第一列留给「节次」行标签，其余依次对齐到星期列。
     * 用最近锚点归属，避免固定间距在长文本单元格上误合并。
     */
    private fun rowCells(row: List<TextBlock>, anchors: List<Pair<Float, Weekday>>): List<String> {
        if (row.isEmpty() || anchors.isEmpty()) return emptyList()
        val firstAnchor = anchors.first().first
        val labelBlocks = row.filter { it.centerX < firstAnchor - (anchors.getOrNull(1)?.first?.minus(firstAnchor) ?: 120f) / 2f }
        val cells = MutableList(anchors.size + 1) { StringBuilder() }
        labelBlocks.sortedBy { it.x }.forEach { cells[0].append(it.text).append(' ') }

        val pitch = if (anchors.size >= 2) anchors[1].first - anchors[0].first else 200f
        row.filterNot { it in labelBlocks }.sortedBy { it.x }.forEach { block ->
            var bestIndex = 0
            var bestDistance = Float.MAX_VALUE
            anchors.forEachIndexed { index, anchor ->
                val distance = kotlin.math.abs(block.centerX - anchor.first)
                if (distance < bestDistance) {
                    bestDistance = distance
                    bestIndex = index
                }
            }
            if (bestDistance > pitch * 1.2f) return@forEach
            cells[bestIndex + 1].append(block.text).append(' ')
        }
        return cells.map { it.toString().trim() }
    }

    /** 列表式：每行一门课，行内包含星期/节次/周次/地点/教师信息。 */
    private fun parseListStyle(
        blocks: List<TextBlock>,
        totalWeeks: Int,
        warnings: MutableList<String>
    ): List<ParsedCourse> {
        val result = mutableListOf<ParsedCourse>()
        blocks.map { it.text }.forEach { line ->
            val weekdayIndex = ImportLabels.weekdayIndex(line) ?: return@forEach
            val sections = ImportLabels.sectionRange(line) ?: return@forEach
            val entries = CellEntryParser.parseCell(line.replace(Regex("""星期[一二三四五六日天]|周[一二三四五六日天]"""), ""), totalWeeks)
            entries.forEach { entry ->
                result += ParsedCourse(
                    courseName = entry.courseName,
                    teacher = entry.teacher,
                    semester = null,
                    meetings = listOf(
                        ParsedMeeting(
                            weekday = Weekday.entries[weekdayIndex - 1],
                            startSection = sections.first,
                            endSection = sections.last,
                            weekRules = entry.weekRules,
                            location = entry.location,
                            campusAlias = entry.campusAlias
                        )
                    )
                )
            }
        }
        if (result.isEmpty()) warnings += "未识别到课表结构，请确认文件内容或改用粘贴文本导入"
        return result
    }

    /** 同名同教师的多次上课时间合并为一门课。 */
    private fun mergeCourses(courses: List<ParsedCourse>): List<ParsedCourse> {
        val grouped = LinkedHashMap<String, MutableList<ParsedCourse>>()
        courses.forEach { course ->
            val key = course.courseName.trim() + "|" + course.teacher.trim()
            grouped.getOrPut(key) { mutableListOf() }.add(course)
        }
        return grouped.values.map { group ->
            val first = group.first()
            ParsedCourse(
                courseName = first.courseName,
                teacher = first.teacher,
                semester = first.semester,
                meetings = group.flatMap { it.meetings }.distinct(),
                warnings = group.flatMap { it.warnings }.distinct()
            )
        }
    }
}

/**
 * 节次时间表识别：识别「XX校区上课时间」分块表，例如
 * 真理道校区上课时间 / 节次 | 教学楼、体育场 | 实训楼 / 12节 | 8:30-10:05 | 8:30-10:05 ...
 */
object TimeTableTextParser {
    private val headerRegex = Regex("""^(.{2,20}?校区).*上课时间""")
    private val sectionLabelRegex = Regex("""^\s*(\d{1,2})\s*[-—–~～]?\s*(\d{1,2})?\s*节""")
    private val timeRegex = Regex("""\d{1,2}\s*[:：]\s*\d{2}\s*[-—–~～至]\s*\d{1,2}\s*[:：]\s*\d{2}""")

    fun parse(rawText: String): List<SectionTimeProfile> {
        val lines = rawText.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val profiles = mutableListOf<SectionTimeProfile>()

        var currentGroup: String? = null
        var columns: List<String> = emptyList()
        val blockRows = mutableListOf<Pair<IntRange, List<String>>>()

        fun flush() {
            val group = currentGroup ?: return
            if (blockRows.isEmpty()) return
            if (columns.size <= 1) {
                val blocks = blockRows.mapNotNull { (section, times) ->
                    times.firstOrNull()?.let { time ->
                        val parts = time.split(Regex("""\s*[-—–~～至]\s*"""))
                        if (parts.size < 2) null else SectionBlock(section.first, section.last, parts[0], parts[1])
                    }
                }
                if (blocks.isNotEmpty()) {
                    profiles += SectionTimeProfile(id = "ocr-" + group, name = group, blocks = blocks)
                }
            } else {
                columns.forEachIndexed { columnIndex, columnName ->
                    val blocks = blockRows.mapNotNull { (section, times) ->
                        times.getOrNull(columnIndex)?.let { time ->
                            val parts = time.split(Regex("""\s*[-—–~～至]\s*"""))
                            if (parts.size < 2) null else SectionBlock(section.first, section.last, parts[0], parts[1])
                        }
                    }
                    if (blocks.isNotEmpty()) {
                        profiles += SectionTimeProfile(
                            id = "ocr-" + group + "-" + columnName,
                            name = "$group·$columnName",
                            blocks = blocks
                        )
                    }
                }
            }
            blockRows.clear()
            columns = emptyList()
        }

        lines.forEach { line ->
            headerRegex.find(line)?.let { match ->
                flush()
                currentGroup = match.groupValues[1].trim()
                return@forEach
            }
            if (currentGroup == null) return@forEach

            val times = timeRegex.findAll(line).map { it.value.replace("：", ":") }.toList()
            val sectionMatch = sectionLabelRegex.find(line)
            if (sectionMatch != null) {
                val (start, end) = resolveSectionBlock(
                    raw = sectionMatch.groupValues[1],
                    explicitEnd = sectionMatch.groupValues[2].toIntOrNull()
                )
                if (times.isNotEmpty()) {
                    blockRows += (start..end) to times
                    return@forEach
                }
            }
            // 列名行：含有「教学楼/实训楼/体育场」等且没有时间
            if (times.isEmpty() && (line.contains("楼") || line.contains("场") || line.contains("楼栋"))) {
                columns = line.split(Regex("""[\s|｜]+""")).map { it.trim() }.filter { it.isNotEmpty() && it != "节次" }
            }
        }
        flush()
        return profiles
    }

    /**
     * 「12节 / 34节 / 9-12节」三种写法统一成节次区间：
     * 紧凑两位数字视为「起始小节 + 结束小节」（12→1-2、34→3-4），单数字默认两小节，9 及以上视为 9-12。
     */
    private fun resolveSectionBlock(raw: String, explicitEnd: Int?): Pair<Int, Int> {
        val digits = raw.trim()
        if (explicitEnd != null && explicitEnd in 1..12) {
            val start = digits.toIntOrNull() ?: 1
            return if (start <= explicitEnd) start to explicitEnd else explicitEnd to start
        }
        if (digits.length == 2) {
            val first = digits[0].digitToInt()
            val second = digits[1].digitToInt()
            if (first in 1..9 && second in 1..9 && second > first) return first to second
        }
        val single = digits.toIntOrNull() ?: 1
        return if (single >= 9) 9 to 12 else single to (single + 1).coerceAtMost(12)
    }
}
