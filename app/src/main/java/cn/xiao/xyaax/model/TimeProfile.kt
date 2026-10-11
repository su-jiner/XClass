package cn.xiao.xyaax.model

import kotlinx.serialization.Serializable

/**
 * 一个「节次段 → 上课时间」映射块，例如 1-2 节 08:00-09:40。
 * 同一套时间表内的段互不重叠，允许 9-12 节这种一个段覆盖多小节。
 */
@Serializable
data class SectionBlock(
    val startSection: Int,
    val endSection: Int,
    val startTime: String,
    val endTime: String
)

/** 一套节次时间表，可对应校区或校区内的某个楼栋（如「真理道·实训楼」）。 */
@Serializable
data class SectionTimeProfile(
    val id: String,
    val name: String,
    val blocks: List<SectionBlock>
) {
    /** 返回某节课小节的起止时间：起点取覆盖 startSection 的段，终点取覆盖 endSection 的段。 */
    fun timeRange(startSection: Int, endSection: Int): Pair<String, String>? {
        if (blocks.isEmpty()) return null
        val start = blocks.firstOrNull { startSection in it.startSection..it.endSection }
            ?: blocks.minByOrNull { kotlin.math.abs(it.startSection - startSection) }
            ?: return null
        val end = blocks.firstOrNull { endSection in it.startSection..it.endSection } ?: start
        return start.startTime to end.endTime
    }

    /** 某个小节的 "HH:mm-HH:mm"，用于课表左侧时间列。 */
    fun sectionRange(section: Int): String? =
        timeRange(section, section)?.let { "${it.first}-${it.second}" }
}
