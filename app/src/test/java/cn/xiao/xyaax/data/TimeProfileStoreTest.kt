package cn.xiao.xyaax.data

import cn.xiao.xyaax.data.ScheduleBackupCodec.BackupBlock
import cn.xiao.xyaax.data.ScheduleBackupCodec.BackupCourse
import cn.xiao.xyaax.data.ScheduleBackupCodec.BackupMeeting
import cn.xiao.xyaax.data.ScheduleBackupCodec.BackupProfile
import cn.xiao.xyaax.data.ScheduleBackupCodec.BackupTimeProfile
import cn.xiao.xyaax.data.ScheduleBackupCodec.BackupWeekRule
import cn.xiao.xyaax.domain.SectionTimes
import cn.xiao.xyaax.domain.TimeProfileResolver
import cn.xiao.xyaax.model.MeetingTime
import cn.xiao.xyaax.model.SectionBlock
import cn.xiao.xyaax.model.SectionTimeProfile
import cn.xiao.xyaax.model.Weekday
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TimeProfileStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val campusProfile = SectionTimeProfile(
        id = "campus-zhenli-training",
        name = "真理道校区·实训楼",
        blocks = listOf(
            SectionBlock(1, 2, "08:30", "10:05"),
            SectionBlock(3, 4, "10:10", "11:30"),
            SectionBlock(9, 12, "18:00", "20:30")
        )
    )

    @Test
    fun `default profile matches 12 period table`() {
        val profile = TimeProfileStore.defaultProfile()
        assertEquals(TimeProfileStore.DEFAULT_PROFILE_ID, profile.id)
        assertEquals(12, profile.blocks.size)
        assertEquals("08:00-08:45", profile.sectionRange(1))
        assertEquals("20:45-21:30", profile.sectionRange(12))
        assertEquals(SectionTimes.DEFAULT_RANGES.first().substringBefore('-'), profile.blocks.first().startTime)
    }

    @Test
    fun `profiles and campus map survive save and load`() {
        TimeProfileStore.save(
            filesDir = tmp.root,
            profiles = listOf(campusProfile),
            campusMap = mapOf("真" to campusProfile.id),
            defaultProfileId = campusProfile.id
        )
        val loaded = TimeProfileStore.load(tmp.root)
        assertEquals(2, loaded.size)
        assertEquals(TimeProfileStore.DEFAULT_PROFILE_ID, loaded.first().id)
        assertEquals("真理道校区·实训楼", loaded.last().name)
        assertEquals(mapOf("真" to campusProfile.id), TimeProfileStore.loadCampusMap(tmp.root))
        assertEquals(campusProfile.id, TimeProfileStore.loadDefaultProfileId(tmp.root))
    }

    @Test
    fun `resolver uses campus time and falls back to default`() {
        val profiles = listOf(TimeProfileStore.defaultProfile(), campusProfile)
        val campusMeeting = MeetingTime(
            weekday = Weekday.MONDAY,
            startSection = 3,
            endSection = 4,
            weekRules = emptyList(),
            location = "实训楼301",
            campusId = campusProfile.id
        )
        assertEquals("10:10" to "11:30", TimeProfileResolver.timeRange(profiles, campusMeeting))
        assertEquals("10:10-11:30", TimeProfileResolver.timeText(profiles, campusMeeting))

        val plainMeeting = campusMeeting.copy(campusId = null)
        assertEquals("10:00" to "11:40", TimeProfileResolver.timeRange(profiles, plainMeeting))
    }

    @Test
    fun `resolver falls back when campus id is unknown`() {
        val profiles = listOf(TimeProfileStore.defaultProfile())
        val meeting = MeetingTime(Weekday.MONDAY, 1, 2, emptyList(), "教室", "missing-profile")
        assertEquals("08:00" to "09:40", TimeProfileResolver.timeRange(profiles, meeting))
    }

    @Test
    fun `backup v2 keeps campus id and time profiles`() {
        val course = BackupCourse(
            courseName = "信息技术",
            teacher = "刘文杰",
            semester = "2026-2027-1",
            credit = null,
            rawWeekText = "1-20周",
            meetings = listOf(
                BackupMeeting(
                    weekday = Weekday.MONDAY.name,
                    startSection = 1,
                    endSection = 2,
                    weekRules = listOf(BackupWeekRule(1, 20, "ALL")),
                    location = "E406",
                    campusId = campusProfile.id
                )
            )
        )
        val json = ScheduleBackupCodec.encode(
            exportedAt = "2026-10-10T00:00:00",
            profiles = listOf(BackupProfile("主课表", "2026-08-31", listOf(course))),
            timeProfiles = listOf(
                BackupTimeProfile(
                    id = campusProfile.id,
                    name = campusProfile.name,
                    blocks = campusProfile.blocks.map { BackupBlock(it.startSection, it.endSection, it.startTime, it.endTime) }
                )
            ),
            campusMap = mapOf("真" to campusProfile.id),
            defaultProfileId = campusProfile.id
        )

        val envelope = ScheduleBackupCodec.decodeEnvelope(json)
        assertNotNull(envelope)
        assertEquals(1, envelope!!.profiles.size)
        assertEquals(1, envelope.timeProfiles.size)
        assertEquals(campusProfile.id, envelope.campusMap["真"])
        assertEquals(campusProfile.id, envelope.defaultProfileId)
        val restored = with(ScheduleBackupCodec) { envelope.profiles.first().courses.first().toCourseSchedule() }
        assertEquals(campusProfile.id, restored.meetings.first().campusId)
    }

    @Test
    fun `v1 backup without campus fields still decodes`() {
        val v1 = """
            {"version":1,"exportedAt":"2026-01-01T00:00:00","profiles":[{"name":"旧课表","semesterStartDate":"2025-09-01",
            "courses":[{"courseName":"高等数学","meetings":[{"weekday":"MONDAY","startSection":1,"endSection":2,
            "weekRules":[{"startWeek":1,"endWeek":16,"parity":"ALL"}],"location":"A101"}]}]}]}
        """.trimIndent()
        val envelope = ScheduleBackupCodec.decodeEnvelope(v1)
        assertNotNull(envelope)
        assertTrue(envelope!!.timeProfiles.isEmpty())
        val restored = with(ScheduleBackupCodec) { envelope.profiles.first().courses.first().toCourseSchedule() }
        assertEquals(null, restored.meetings.first().campusId)
    }
}
