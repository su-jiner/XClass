package cn.xiao.xyaax.model

import kotlinx.serialization.Serializable

@Serializable
enum class Weekday(val displayName: String) {
    MONDAY("Monday"),
    TUESDAY("Tuesday"),
    WEDNESDAY("Wednesday"),
    THURSDAY("Thursday"),
    FRIDAY("Friday"),
    SATURDAY("Saturday"),
    SUNDAY("Sunday")
}

@Serializable
enum class WeekParity {
    ALL,
    ODD,
    EVEN
}

@Serializable
data class WeekRule(
    val startWeek: Int,
    val endWeek: Int,
    val parity: WeekParity = WeekParity.ALL
)

@Serializable
data class MeetingTime(
    val weekday: Weekday,
    val startSection: Int,
    val endSection: Int,
    val weekRules: List<WeekRule>,
    val location: String,
    /** 校区/楼栋时间表 ID；为空表示使用默认节次时间表 */
    val campusId: String? = null,
    /** 自定义上课起止时间（HH:mm）；为空时沿用节次默认时间 */
    val customStartTime: String? = null,
    val customEndTime: String? = null
)

@Serializable
data class CourseSchedule(
    val courseName: String,
    val teacher: String,
    val semester: String,
    val credit: Double?,
    val rawWeekText: String,
    val meetings: List<MeetingTime>
)

/** 上课时间的实际起止：优先自定义时间，否则按节次默认时间表映射。 */
fun MeetingTime.effectiveStartTime(periodRanges: List<String>): String =
    customStartTime ?: periodRanges.getOrNull(startSection - 1)?.substringBefore('-') ?: "--:--"

/** 上课时间的实际结束：优先自定义时间，否则按节次默认时间表映射。 */
fun MeetingTime.effectiveEndTime(periodRanges: List<String>): String =
    customEndTime ?: periodRanges.getOrNull(endSection - 1)?.substringAfter('-') ?: "--:--"

/** 上课时间文本（"HH:mm - HH:mm"），用于详情横幅与导出。 */
fun MeetingTime.effectiveTimeText(periodRanges: List<String>): String =
    "${effectiveStartTime(periodRanges)} - ${effectiveEndTime(periodRanges)}"
