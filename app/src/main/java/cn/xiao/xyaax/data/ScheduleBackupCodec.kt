package cn.xiao.xyaax.data

import cn.xiao.xyaax.model.CourseSchedule
import cn.xiao.xyaax.model.MeetingTime
import cn.xiao.xyaax.model.WeekParity
import cn.xiao.xyaax.model.WeekRule
import cn.xiao.xyaax.model.Weekday
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 课表备份文件的 JSON 编解码。
 * 独立于存储层的内部持久化模型，保证备份格式稳定、可跨版本迁移。
 */
object ScheduleBackupCodec {
    private const val BACKUP_VERSION = 2

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        isLenient = true
    }

    @Serializable
    data class BackupFile(
        val version: Int = BACKUP_VERSION,
        val exportedAt: String = "",
        val profiles: List<BackupProfile> = emptyList(),
        /** version 2 起：节次时间表（含校区映射），旧版备份缺省为空 */
        val timeProfiles: List<BackupTimeProfile> = emptyList(),
        val campusMap: Map<String, String> = emptyMap(),
        val defaultProfileId: String? = null
    )

    @Serializable
    data class BackupTimeProfile(
        val id: String,
        val name: String,
        val blocks: List<BackupBlock> = emptyList()
    )

    @Serializable
    data class BackupBlock(
        val startSection: Int,
        val endSection: Int,
        val startTime: String,
        val endTime: String
    )

    data class BackupEnvelope(
        val profiles: List<BackupProfile>,
        val timeProfiles: List<BackupTimeProfile>,
        val campusMap: Map<String, String>,
        val defaultProfileId: String?
    )

    @Serializable
    data class BackupProfile(
        val name: String,
        val semesterStartDate: String? = null,
        val courses: List<BackupCourse> = emptyList(),
        /** 课表级公共备注；旧版备份缺省为空列表（向后兼容，version 保持 2） */
        val notes: List<String> = emptyList()
    )

    @Serializable
    data class BackupCourse(
        val courseName: String,
        val teacher: String = "",
        val semester: String = "",
        val credit: Double? = null,
        val rawWeekText: String = "",
        val meetings: List<BackupMeeting> = emptyList()
    )

    @Serializable
    data class BackupMeeting(
        val weekday: String,
        val startSection: Int,
        val endSection: Int,
        val weekRules: List<BackupWeekRule> = emptyList(),
        val location: String = "",
        val campusId: String? = null,
        val customStartTime: String? = null,
        val customEndTime: String? = null
    )

    @Serializable
    data class BackupWeekRule(
        val startWeek: Int,
        val endWeek: Int,
        val parity: String = "ALL"
    )

    fun encode(
        exportedAt: String,
        profiles: List<BackupProfile>,
        timeProfiles: List<BackupTimeProfile> = emptyList(),
        campusMap: Map<String, String> = emptyMap(),
        defaultProfileId: String? = null
    ): String {
        return json.encodeToString(
            BackupFile(
                version = BACKUP_VERSION,
                exportedAt = exportedAt,
                profiles = profiles,
                timeProfiles = timeProfiles,
                campusMap = campusMap,
                defaultProfileId = defaultProfileId
            )
        )
    }

    /** 解码备份文件内容，格式不合法时返回 null */
    fun decode(content: String): List<BackupProfile>? {
        return decodeEnvelope(content)?.profiles
    }

    /** 解码完整信封（含 v2 的时间表与校区映射）。 */
    fun decodeEnvelope(content: String): BackupEnvelope? = runCatching {
        val file = json.decodeFromString<BackupFile>(content)
        if (file.version !in 1..BACKUP_VERSION) return null
        BackupEnvelope(file.profiles, file.timeProfiles, file.campusMap, file.defaultProfileId)
    }.getOrNull()

    fun BackupCourse.toCourseSchedule(): CourseSchedule {
        return CourseSchedule(
            courseName = courseName,
            teacher = teacher,
            semester = semester,
            credit = credit,
            rawWeekText = rawWeekText,
            meetings = meetings.map { it.toMeetingTime() }
        )
    }

    fun CourseSchedule.toBackupCourse(): BackupCourse {
        return BackupCourse(
            courseName = courseName,
            teacher = teacher,
            semester = semester,
            credit = credit,
            rawWeekText = rawWeekText,
            meetings = meetings.map { it.toBackupMeeting() }
        )
    }

    private fun BackupMeeting.toMeetingTime(): MeetingTime {
        return MeetingTime(
            weekday = parseWeekday(weekday),
            startSection = startSection,
            endSection = endSection,
            weekRules = weekRules.map { it.toWeekRule() },
            location = location,
            campusId = campusId,
            customStartTime = customStartTime,
            customEndTime = customEndTime
        )
    }

    private fun parseWeekday(raw: String): Weekday {
        val intVal = raw.trim().toIntOrNull()
        if (intVal != null && intVal in 1..Weekday.entries.size) {
            return Weekday.entries[intVal - 1]
        }
        return runCatching { Weekday.valueOf(raw.trim().uppercase()) }.getOrDefault(Weekday.MONDAY)
    }

    private fun MeetingTime.toBackupMeeting(): BackupMeeting {
        return BackupMeeting(
            weekday = weekday.name,
            startSection = startSection,
            endSection = endSection,
            weekRules = weekRules.map { it.toBackupWeekRule() },
            location = location,
            campusId = campusId,
            customStartTime = customStartTime,
            customEndTime = customEndTime
        )
    }

    private fun BackupWeekRule.toWeekRule(): WeekRule {
        return WeekRule(
            startWeek = startWeek,
            endWeek = endWeek,
            parity = parseWeekParity(parity)
        )
    }

    private fun parseWeekParity(raw: String): WeekParity {
        return runCatching { WeekParity.valueOf(raw.trim().uppercase()) }.getOrDefault(WeekParity.ALL)
    }

    private fun WeekRule.toBackupWeekRule(): BackupWeekRule {
        return BackupWeekRule(startWeek = startWeek, endWeek = endWeek, parity = parity.name)
    }
}
