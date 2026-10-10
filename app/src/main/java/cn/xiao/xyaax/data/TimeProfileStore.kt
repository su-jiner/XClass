package cn.xiao.xyaax.data

import android.util.Log
import cn.xiao.xyaax.domain.SectionTimes
import cn.xiao.xyaax.model.SectionBlock
import cn.xiao.xyaax.model.SectionTimeProfile
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class PersistedBlock(
    val startSection: Int,
    val endSection: Int,
    val startTime: String,
    val endTime: String
)

@Serializable
data class PersistedTimeProfile(
    val id: String,
    val name: String,
    val blocks: List<PersistedBlock> = emptyList()
)

@Serializable
data class PersistedTimeProfiles(
    val version: Int = 1,
    val defaultProfileId: String? = null,
    val profiles: List<PersistedTimeProfile> = emptyList(),
    /** 校区简称（如「乌」）→ 时间表 ID */
    val campusMap: Map<String, String> = emptyMap()
)

/**
 * 节次时间表存储：默认 12 节表 + 用户在设置页/导入向导里维护的多套校区时间表。
 * 文件与课表放在同一目录（filesDir/time_profiles.json），随备份一起导出。
 */
object TimeProfileStore {
    const val DEFAULT_PROFILE_ID = "default"
    const val DEFAULT_PROFILE_NAME = "默认（12 节）"
    private const val TAG = "TimeProfileStore"
    private const val FILE_NAME = "time_profiles.json"

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }
    private val lock = Any()

    fun defaultProfile(): SectionTimeProfile = SectionTimeProfile(
        id = DEFAULT_PROFILE_ID,
        name = DEFAULT_PROFILE_NAME,
        blocks = SectionTimes.DEFAULT_RANGES.mapIndexed { index, range ->
            val parts = range.split('-')
            SectionBlock(
                startSection = index + 1,
                endSection = index + 1,
                startTime = parts.getOrElse(0) { "" }.trim(),
                endTime = parts.getOrElse(1) { "" }.trim()
            )
        }
    )

    fun load(filesDir: File): List<SectionTimeProfile> = synchronized(lock) {
        val persisted = read(filesDir)
        val custom = persisted.profiles.map { it.toModel() }.filter { it.blocks.isNotEmpty() && it.id != DEFAULT_PROFILE_ID }
        buildList {
            add(defaultProfile())
            addAll(custom)
        }
    }

    fun loadCampusMap(filesDir: File): Map<String, String> = synchronized(lock) { read(filesDir).campusMap }

    fun loadDefaultProfileId(filesDir: File): String? = synchronized(lock) { read(filesDir).defaultProfileId }

    fun save(filesDir: File, profiles: List<SectionTimeProfile>, campusMap: Map<String, String>, defaultProfileId: String?) =
        synchronized(lock) {
            val persisted = PersistedTimeProfiles(
                version = 1,
                defaultProfileId = defaultProfileId,
                profiles = profiles.filter { it.id != DEFAULT_PROFILE_ID }.map { it.toPersisted() },
                campusMap = campusMap
            )
            runCatching { file(filesDir).writeText(json.encodeToString(persisted)) }
                .onFailure { Log.w(TAG, "save time profiles failed", it) }
        }

    fun saveProfiles(filesDir: File, profiles: List<SectionTimeProfile>): Unit {
        val current = read(filesDir)
        save(filesDir, profiles, current.campusMap, current.defaultProfileId)
    }

    fun saveCampusMap(filesDir: File, campusMap: Map<String, String>): Unit {
        val current = read(filesDir)
        save(filesDir, current.profiles.map { it.toModel() }, campusMap, current.defaultProfileId)
    }

    fun saveDefaultProfileId(filesDir: File, profileId: String?): Unit {
        val current = read(filesDir)
        save(filesDir, current.profiles.map { it.toModel() }, current.campusMap, profileId)
    }

    private fun file(filesDir: File) = File(filesDir, FILE_NAME)

    private fun read(filesDir: File): PersistedTimeProfiles {
        val target = file(filesDir)
        if (!target.exists() || target.length() == 0L) return PersistedTimeProfiles()
        return runCatching { json.decodeFromString<PersistedTimeProfiles>(target.readText(Charsets.UTF_8)) }
            .onFailure { Log.w(TAG, "read time profiles failed", it) }
            .getOrDefault(PersistedTimeProfiles())
    }

    private fun PersistedTimeProfile.toModel() = SectionTimeProfile(
        id = id,
        name = name,
        blocks = blocks.map { SectionBlock(it.startSection, it.endSection, it.startTime, it.endTime) }
    )

    private fun SectionTimeProfile.toPersisted() = PersistedTimeProfile(
        id = id,
        name = name,
        blocks = blocks.map { PersistedBlock(it.startSection, it.endSection, it.startTime, it.endTime) }
    )
}
