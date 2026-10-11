package cn.xiao.xyaax.importing

import cn.xiao.xyaax.model.SectionTimeProfile
import cn.xiao.xyaax.model.WeekParity
import cn.xiao.xyaax.model.WeekRule

/**
 * 通用多源识别与合并算法（Universal Course Schedule Extraction & Merging Algorithm）：
 * 「多文件合并与去重」「校区与时间校准」「节次映射与时间对齐」三个子算法的落地实现。
 * 主键 = 课程名 + 教师 + 地点；同时间段冲突全部保留并标记。
 */
object UniversalMerge {

    /** 按规格合并：以课程名 + 教师 + 地点为主键，同名同师同地点的多次上课合并为一门课。 */
    fun merge(courses: List<ParsedCourse>): List<ParsedCourse> {
        val grouped = LinkedHashMap<String, MutableList<ParsedCourse>>()
        courses.forEach { course ->
            val key = course.courseName.trim() + "|" + course.teacher.trim() + "|" +
                (course.meetings.firstOrNull()?.location ?: "").trim()
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

    /**
     * 冲突检测：同一星期、节次区间重叠、周次规则相交的不同课程 → 冲突标记（保留全部课程）。
     * 周次相交判定：区间有交集，且奇偶性兼容（单/双/全）。
     */
    fun detectConflicts(courses: List<ParsedCourse>): List<String> {
        val warnings = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        val bySlot = LinkedHashMap<String, MutableList<ParsedCourse>>()
        courses.forEach { course ->
            course.meetings.forEach { meeting ->
                val slotKey = "${meeting.weekday.ordinal + 1}|${meeting.startSection}-${meeting.endSection}"
                bySlot.getOrPut(slotKey) { mutableListOf() }.add(course)
            }
        }
        bySlot.forEach { (slot, slotCourses) ->
            val distinct = slotCourses.distinctBy { it.courseName.trim() }
            if (distinct.size < 2) return@forEach
            // 逐对判断周次相交，只有真冲突才警告
            val conflicts = mutableListOf<Pair<ParsedCourse, ParsedCourse>>()
            for (i in distinct.indices) {
                for (j in (i + 1) until distinct.size) {
                    val a = distinct[i]
                    val b = distinct[j]
                    if (sameSlotMeetingsOverlap(a, b)) {
                        conflicts += a to b
                    }
                }
            }
            conflicts.forEach { (a, b) ->
                val message = "时间冲突：${slotText(slot)} ${a.courseName} 与 ${b.courseName} 同时段，已全部保留，请核对"
                if (seen.add(message)) warnings += message
            }
        }
        return warnings
    }

    private fun sameSlotMeetingsOverlap(a: ParsedCourse, b: ParsedCourse): Boolean {
        a.meetings.forEach { ma ->
            b.meetings.forEach { mb ->
                if (ma.weekday != mb.weekday) return@forEach
                if (ma.startSection > mb.endSection || mb.startSection > ma.endSection) return@forEach
                if (weekRulesOverlap(ma.weekRules, mb.weekRules)) return true
            }
        }
        return false
    }

    /** 两组周次规则是否有交集（区间重叠 + 奇偶兼容）。 */
    fun weekRulesOverlap(a: List<WeekRule>, b: List<WeekRule>): Boolean {
        a.forEach { ra ->
            b.forEach { rb ->
                if (ra.endWeek < rb.startWeek || rb.endWeek < ra.startWeek) return@forEach
                if (parityCompatible(ra.parity, rb.parity)) return true
            }
        }
        return false
    }

    private fun parityCompatible(a: WeekParity, b: WeekParity): Boolean =
        a == WeekParity.ALL || b == WeekParity.ALL || a == b

    private fun slotText(slot: String): String {
        val parts = slot.split('|')
        val weekday = parts.getOrNull(0)?.toIntOrNull() ?: return slot
        val range = parts.getOrNull(1).orEmpty()
        val label = listOf(
            "星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日"
        ).getOrNull(weekday - 1) ?: "周$weekday"
        return "$label $range 节"
    }
}

/**
 * 节次映射与时间对齐（规格第 5、6 条）：
 * 当单元格只写时间（如 08:00-09:40）而没有节次数字时，用校区时间表把时间区间映射回标准节次。
 */
object TimeToSectionMapper {
    private val timeRangeRegex = Regex("""(\d{1,2}\s*[:：]\s*\d{2})\s*[-—–~～至]\s*(\d{1,2}\s*[:：]\s*\d{2})""")

    /** 单元格文本 → 节次区间；无时间或匹配不到返回 null。 */
    fun map(text: String, profiles: List<SectionTimeProfile>): IntRange? {
        val match = timeRangeRegex.find(text) ?: return null
        val start = match.groupValues[1].replace("：", ":").replace(" ", "")
        val end = match.groupValues[2].replace("：", ":").replace(" ", "")
        if (profiles.isEmpty()) return null

        // 精确匹配
        profiles.forEach { profile ->
            profile.blocks.forEach { block ->
                if (block.startTime == start && block.endTime == end) return block.startSection..block.endSection
            }
        }
        // 容错：起止时间其中一个匹配（不同校区表边界略有差异）
        profiles.forEach { profile ->
            profile.blocks.forEach { block ->
                if (block.startTime == start) return block.startSection..block.endSection
                if (block.endTime == end) return block.startSection..block.endSection
            }
        }
        return null
    }

    /** 文本中是否含时间区间（供快速判断是否需要映射）。 */
    fun containsTime(text: String): Boolean = timeRangeRegex.containsMatchIn(text)
}
