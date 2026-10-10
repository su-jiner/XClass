package cn.xiao.xyaax.domain

import cn.xiao.xyaax.data.TimeProfileStore
import cn.xiao.xyaax.model.MeetingTime
import cn.xiao.xyaax.model.SectionTimeProfile
import java.io.File

/**
 * 节次时间解析：按课程所属校区/楼栋挑时间表，未命中则回落到默认 12 节表。
 * 今日页、桌面组件、提醒、文本导出共用，保证四处时间显示一致。
 */
object TimeProfileResolver {

    fun loadProfiles(filesDir: File): List<SectionTimeProfile> = TimeProfileStore.load(filesDir)

    fun resolve(profiles: List<SectionTimeProfile>, campusId: String?): SectionTimeProfile {
        if (campusId.isNullOrBlank()) return defaultProfile(profiles)
        return profiles.firstOrNull { it.id == campusId } ?: defaultProfile(profiles)
    }

    fun defaultProfile(profiles: List<SectionTimeProfile>): SectionTimeProfile =
        profiles.firstOrNull { it.id == TimeProfileStore.DEFAULT_PROFILE_ID }
            ?: profiles.firstOrNull()
            ?: TimeProfileStore.defaultProfile()

    /** 课程显示用的起止时间；解析不到时回落到默认表。 */
    fun timeRange(profiles: List<SectionTimeProfile>, meeting: MeetingTime): Pair<String, String>? {
        val profile = resolve(profiles, meeting.campusId)
        val range = profile.timeRange(meeting.startSection, meeting.endSection)
        if (range != null) return range
        val fallback = TimeProfileResolver.defaultProfile(profiles)
        if (fallback.id != profile.id) return fallback.timeRange(meeting.startSection, meeting.endSection)
        return null
    }

    fun timeText(profiles: List<SectionTimeProfile>, meeting: MeetingTime): String {
        val range = timeRange(profiles, meeting)
        return range?.let { "${it.first}-${it.second}" } ?: "--:--"
    }

    /** 课表左侧时间列：某套时间表下每个小节的起止时间。 */
    fun sectionRanges(profile: SectionTimeProfile, sectionCount: Int = 12): List<String> =
        (1..sectionCount).map { section -> profile.sectionRange(section) ?: "--:--" }
}
