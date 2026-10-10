package cn.xiao.xyaax.model

enum class Weekday(val displayName: String) {
    MONDAY("Monday"),
    TUESDAY("Tuesday"),
    WEDNESDAY("Wednesday"),
    THURSDAY("Thursday"),
    FRIDAY("Friday"),
    SATURDAY("Saturday"),
    SUNDAY("Sunday")
}

enum class WeekParity {
    ALL,
    ODD,
    EVEN
}

data class WeekRule(
    val startWeek: Int,
    val endWeek: Int,
    val parity: WeekParity = WeekParity.ALL
)

data class MeetingTime(
    val weekday: Weekday,
    val startSection: Int,
    val endSection: Int,
    val weekRules: List<WeekRule>,
    val location: String,
    /** 校区/楼栋时间表 ID；为空表示使用默认节次时间表 */
    val campusId: String? = null
)

data class CourseSchedule(
    val courseName: String,
    val teacher: String,
    val semester: String,
    val credit: Double?,
    val rawWeekText: String,
    val meetings: List<MeetingTime>
)
