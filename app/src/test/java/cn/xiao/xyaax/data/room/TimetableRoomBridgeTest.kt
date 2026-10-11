package cn.xiao.xyaax.data.room

import cn.xiao.xyaax.model.CourseSchedule
import cn.xiao.xyaax.model.MeetingTime
import cn.xiao.xyaax.model.SectionBlock
import cn.xiao.xyaax.model.SectionTimeProfile
import cn.xiao.xyaax.model.WeekParity
import cn.xiao.xyaax.model.WeekRule
import cn.xiao.xyaax.model.Weekday
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Room 数据通道映射层单测：课程/时间表与 Room 实体往返一致，
 * 覆盖周规则、单双周、多段节次、地点/校区字段的序列化保真。
 */
class TimetableRoomBridgeTest {

    private val sampleCourse = CourseSchedule(
        courseName = "程序设计基础（Python）",
        teacher = "李老师",
        semester = "2026-2027-1",
        credit = 3.0,
        rawWeekText = "7-14周(双),15-19周",
        meetings = listOf(
            MeetingTime(
                weekday = Weekday.FRIDAY,
                startSection = 3,
                endSection = 4,
                weekRules = listOf(
                    WeekRule(7, 14, WeekParity.EVEN),
                    WeekRule(15, 19, WeekParity.ALL),
                ),
                location = "乌江路校区 E406",
                campusId = "wujiang",
            ),
            MeetingTime(
                weekday = Weekday.MONDAY,
                startSection = 1,
                endSection = 2,
                weekRules = listOf(WeekRule(1, 21, WeekParity.ALL)),
                location = "真理道校区 A101",
                campusId = null,
            ),
        ),
    )

    @Test
    fun courseEntityRoundTrip_preservesAllFields() {
        val entity = TimetableRoomBridge.run { sampleCourse.toEntity() }
        val back = TimetableRoomBridge.run { entity.toSchedule() }

        assertEquals("courseName", sampleCourse.courseName, back.courseName)
        assertEquals("teacher", sampleCourse.teacher, back.teacher)
        assertEquals("rawWeekText", sampleCourse.rawWeekText, back.rawWeekText)
        assertEquals("meetings count", 2, back.meetings.size)

        val friday = back.meetings.first { it.weekday == Weekday.FRIDAY }
        assertEquals("startSection", 3, friday.startSection)
        assertEquals("endSection", 4, friday.endSection)
        assertEquals("location", "乌江路校区 E406", friday.location)
        assertEquals("campusId", "wujiang", friday.campusId)
        assertEquals("weekRules count", 2, friday.weekRules.size)

        val evenRule = friday.weekRules.first { it.parity == WeekParity.EVEN }
        assertEquals("even rule start", 7, evenRule.startWeek)
        assertEquals("even rule end", 14, evenRule.endWeek)

        val monday = back.meetings.first { it.weekday == Weekday.MONDAY }
        assertEquals("default campusId null", null, monday.campusId)
        assertEquals("all-week rule", WeekParity.ALL, monday.weekRules.single().parity)
    }

    @Test
    fun timeProfileRoundTrip_preservesBlocks() {
        val profile = SectionTimeProfile(
            id = "wujiang",
            name = "乌江路·泰兴路校区",
            blocks = listOf(
                SectionBlock(1, 2, "08:00", "09:40"),
                SectionBlock(3, 4, "10:00", "11:40"),
                SectionBlock(5, 6, "13:30", "15:10"),
                SectionBlock(9, 12, "18:20", "21:30"),
            ),
        )
        val entity = TimetableRoomBridge.run { profile.toEntity(isDefault = true) }
        assertEquals("isDefault preserved", true, entity.isDefault)

        val back = TimetableRoomBridge.run { entity.toProfile() }
        assertEquals("id", "wujiang", back.id)
        assertEquals("name", "乌江路·泰兴路校区", back.name)
        assertEquals("blocks count", 4, back.blocks.size)

        val last = back.blocks.last()
        assertEquals("9-12 block", 9, last.startSection)
        assertEquals("9-12 end", 12, last.endSection)
        assertEquals("evening time", "18:20", last.startTime)
        assertEquals("evening end", "21:30", last.endTime)

        val time = back.timeRange(5, 6)
        assertNotNull("5-6 timeRange", time)
        assertEquals("5-6 start", "13:30", time!!.first)
        assertEquals("5-6 end", "15:10", time.second)
    }

    @Test
    fun corruptedJson_fallsBackToEmpty() {
        val entity = TimetableRoomBridge.run {
            sampleCourse.toEntity().copy(meetingsJson = "{not-valid-json")
        }
        val back = TimetableRoomBridge.run { entity.toSchedule() }
        assertTrue("corrupt meetings -> empty list", back.meetings.isEmpty())
    }
}
