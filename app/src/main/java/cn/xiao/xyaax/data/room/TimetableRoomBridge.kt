package cn.xiao.xyaax.data.room

import android.content.Context
import cn.xiao.xyaax.model.CourseSchedule
import cn.xiao.xyaax.model.MeetingTime
import cn.xiao.xyaax.model.SectionBlock
import cn.xiao.xyaax.model.SectionTimeProfile
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Room 数据通道（P2）：现有 JSON 课表与 Room 实体之间的双向桥。
 *
 * 设计原则：与现有文件存储并存（双写兼容），任何一端写、另一端可读回，
 * 迁移零风险。课程与时间表通过 kotlinx-serialization 编码为 JSON 列。
 */
object TimetableRoomBridge {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val meetingListSerializer = ListSerializer(MeetingTime.serializer())
    private val courseListSerializer = ListSerializer(CourseSchedule.serializer())

    // ---- 课程映射 ----

    fun CourseSchedule.toEntity(): CourseEntity = CourseEntity(
        courseName = courseName,
        teacher = teacher,
        rawWeekText = rawWeekText,
        meetingsJson = json.encodeToString(meetingListSerializer, meetings),
        colorIndex = 0,
        pinned = false,
    )

    fun CourseEntity.toSchedule(): CourseSchedule {
        val meetings = runCatching {
            json.decodeFromString(meetingListSerializer, meetingsJson)
        }.getOrDefault(emptyList())
        return CourseSchedule(
            courseName = courseName,
            teacher = teacher,
            semester = "",
            credit = null,
            rawWeekText = rawWeekText,
            meetings = meetings,
        )
    }

    // ---- 时间表映射 ----

    private val blockListSerializer = ListSerializer(SectionBlock.serializer())

    fun SectionTimeProfile.toEntity(isDefault: Boolean = false): TimeProfileEntity = TimeProfileEntity(
        id = id,
        name = name,
        isDefault = isDefault,
        blocksJson = json.encodeToString(blockListSerializer, blocks),
    )

    fun TimeProfileEntity.toProfile(): SectionTimeProfile {
        val blocks = runCatching {
            json.decodeFromString(blockListSerializer, blocksJson)
        }.getOrDefault(emptyList())
        return SectionTimeProfile(id = id, name = name, blocks = blocks)
    }

    // ---- 同步通道 ----

    /** 把现有 JSON 课表整体写入 Room（幂等替换）。 */
    fun syncLegacyCourses(context: Context, courses: List<CourseSchedule>) {
        val dao = XClassDatabase.get(context).courseDao()
        kotlinx.coroutines.runBlocking {
            dao.replaceAll(courses.map { it.toEntity() })
        }
    }

    /** 从 Room 读回全部课程。 */
    fun loadCourses(context: Context): List<CourseSchedule> =
        kotlinx.coroutines.runBlocking {
            XClassDatabase.get(context).courseDao().getAll().map { it.toSchedule() }
        }

    /** 把时间表写入 Room（按 id 覆盖）。 */
    fun syncTimeProfiles(context: Context, profiles: List<SectionTimeProfile>, defaultId: String? = null) {
        val dao = XClassDatabase.get(context).timeProfileDao()
        kotlinx.coroutines.runBlocking {
            dao.clearAll()
            dao.upsertAll(profiles.map { it.toEntity(isDefault = it.id == defaultId) })
        }
    }

    /** 从 Room 读回全部时间表。 */
    fun loadTimeProfiles(context: Context): List<SectionTimeProfile> =
        kotlinx.coroutines.runBlocking {
            XClassDatabase.get(context).timeProfileDao().getAll().map { it.toProfile() }
        }

    /** 清空 Room 课程表（用于卸载/重置）。 */
    fun clearCourses(context: Context) {
        kotlinx.coroutines.runBlocking {
            XClassDatabase.get(context).courseDao().clearAll()
        }
    }

    /** 导出当前全部课程为序列化 JSON 文本（专属分享格式的数据源）。 */
    fun exportCoursesJson(context: Context): String {
        val courses = loadCourses(context)
        return json.encodeToString(courseListSerializer, courses)
    }
}
