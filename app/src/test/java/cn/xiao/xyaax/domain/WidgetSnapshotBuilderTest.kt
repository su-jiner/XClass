package cn.xiao.xyaax.domain

import cn.xiao.xyaax.model.CourseSchedule
import cn.xiao.xyaax.model.MeetingTime
import cn.xiao.xyaax.model.SectionBlock
import cn.xiao.xyaax.model.SectionTimeProfile
import cn.xiao.xyaax.model.WeekParity
import cn.xiao.xyaax.model.WeekRule
import cn.xiao.xyaax.model.Weekday
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class WidgetSnapshotBuilderTest {

    private val semesterStart = LocalDate.of(2026, 8, 31)

    private fun course(
        name: String,
        weekday: Weekday,
        start: Int,
        end: Int,
        campusId: String? = null,
        location: String = "教室"
    ) = CourseSchedule(
        courseName = name,
        teacher = "老师",
        semester = "2026-2027-1",
        credit = null,
        rawWeekText = "1-20周",
        meetings = listOf(
            MeetingTime(
                weekday = weekday,
                startSection = start,
                endSection = end,
                weekRules = listOf(WeekRule(1, 20, WeekParity.ALL)),
                location = location,
                campusId = campusId
            )
        )
    )

    private val campusProfile = SectionTimeProfile(
        id = "campus-wu",
        name = "乌江路、泰兴路校区",
        blocks = listOf(
            SectionBlock(1, 2, "08:30", "10:05"),
            SectionBlock(3, 4, "10:20", "11:55")
        )
    )

    @Test
    fun `today snapshot sorts courses and marks ongoing`() {
        val monday = LocalDate.of(2026, 8, 31)
        val courses = listOf(
            course("下午课", Weekday.MONDAY, 5, 6),
            course("上午课", Weekday.MONDAY, 1, 2)
        )
        val snapshot = WidgetSnapshotBuilder.today(
            courses = courses,
            semesterStart = semesterStart,
            profiles = listOf(TimeProfileStoreDefault.profile),
            today = monday,
            now = LocalDateTime.of(2026, 8, 31, 8, 10)
        )
        assertEquals(listOf("上午课", "下午课"), snapshot.items.map { it.courseName })
        assertEquals("上午课", snapshot.ongoingCourse?.courseName)
        assertEquals("下午课", snapshot.nextCourse?.courseName)
    }

    @Test
    fun `campus time table overrides default times`() {
        val monday = LocalDate.of(2026, 8, 31)
        val courses = listOf(course("信息技术", Weekday.MONDAY, 1, 2, campusId = "campus-wu"))
        val snapshot = WidgetSnapshotBuilder.today(
            courses = courses,
            semesterStart = semesterStart,
            profiles = listOf(TimeProfileStoreDefault.profile, campusProfile),
            today = monday,
            now = LocalDateTime.of(2026, 8, 31, 9, 0)
        )
        val item = snapshot.items.single()
        assertEquals("08:30", item.startTime)
        assertEquals("10:05", item.endTime)
        assertEquals("乌江路、泰兴路校区", item.campusName)
    }

    @Test
    fun `next course falls back to later days when today is empty`() {
        val friday = LocalDate.of(2026, 9, 4)
        val courses = listOf(course("周一课", Weekday.MONDAY, 1, 2))
        val next = WidgetSnapshotBuilder.nextCourse(
            courses = courses,
            semesterStart = semesterStart,
            profiles = listOf(TimeProfileStoreDefault.profile),
            today = friday,
            now = LocalDateTime.of(2026, 9, 4, 12, 0)
        )
        assertEquals("周一课", next?.courseName)
    }

    @Test
    fun `week grid places courses by section block and weekday`() {
        val courses = listOf(
            course("高等数学", Weekday.MONDAY, 1, 2),
            course("大学物理", Weekday.WEDNESDAY, 5, 6)
        )
        val grid = WidgetSnapshotBuilder.weekGrid(courses, semesterStart, week = 1)
        assertEquals(6, grid.cells.size)
        assertEquals(7, grid.cells.first().size)
        assertEquals("高等数学", grid.cells[0][0])
        assertEquals("大学物理", grid.cells[2][2])
        assertEquals("", grid.cells[0][1])
    }

    @Test
    fun `countdown text formats minutes and hours`() {
        assertEquals("即将开始", WidgetSnapshotBuilder.countdownText(0))
        assertEquals("15 分钟后", WidgetSnapshotBuilder.countdownText(15))
        assertEquals("1 小时 5 分后", WidgetSnapshotBuilder.countdownText(65))
        assertEquals("已结束", WidgetSnapshotBuilder.countdownText(-3))
        assertNull(WidgetSnapshotBuilder.parseMinutes("--:--"))
    }

    @Test
    fun `parity rules are respected in active week filter`() {
        val odd = MeetingTime(
            weekday = Weekday.MONDAY,
            startSection = 1,
            endSection = 2,
            weekRules = listOf(WeekRule(1, 20, WeekParity.ODD)),
            location = "教室"
        )
        assertTrue(WidgetSnapshotBuilder.isActiveInWeek(odd, 1))
        assertTrue(!WidgetSnapshotBuilder.isActiveInWeek(odd, 2))
    }

    /** 默认 12 节表（与 TimeProfileStore.defaultProfile 等价，测试里避免依赖 Android 环境）。 */
    private object TimeProfileStoreDefault {
        val profile = SectionTimeProfile(
            id = "default",
            name = "默认（12 节）",
            blocks = listOf(
                SectionBlock(1, 1, "08:00", "08:45"),
                SectionBlock(2, 2, "08:45", "09:40"),
                SectionBlock(3, 3, "10:00", "10:45"),
                SectionBlock(4, 4, "10:45", "11:40"),
                SectionBlock(5, 5, "13:30", "14:15"),
                SectionBlock(6, 6, "14:15", "15:10"),
                SectionBlock(7, 7, "15:30", "16:15"),
                SectionBlock(8, 8, "16:15", "17:10"),
                SectionBlock(9, 9, "18:20", "19:05"),
                SectionBlock(10, 10, "19:05", "19:50"),
                SectionBlock(11, 11, "20:00", "20:45"),
                SectionBlock(12, 12, "20:45", "21:30")
            )
        )
    }
}
