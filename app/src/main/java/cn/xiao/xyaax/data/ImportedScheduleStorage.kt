package cn.xiao.xyaax.data

import android.util.Log
import cn.xiao.xyaax.model.CourseSchedule
import cn.xiao.xyaax.model.MeetingTime
import cn.xiao.xyaax.model.WeekParity
import cn.xiao.xyaax.model.WeekRule
import cn.xiao.xyaax.model.Weekday
import cn.xiao.xyaax.model.SectionBlock
import cn.xiao.xyaax.model.SectionTimeProfile
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.UUID

object ImportedScheduleStorage {
    private const val TAG = "ImportedScheduleStorage"
    private const val STORAGE_DIR = "timetables"
    private const val META_FILE_NAME = "meta.json"
    private const val DEFAULT_PROFILE_NAME = "默认课表"
    private const val TEMP_FILE_SUFFIX = ".tmp"
    private const val COURSES_FILE_PREFIX = "courses_"
    private const val COURSES_FILE_SUFFIX = ".json"

    private val lock = Any()

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    data class TimetableProfile(
        val id: String,
        val name: String,
        val isActive: Boolean,
        val updatedAt: Long,
        val semesterStartDate: LocalDate
    )

    data class ManualCourseInput(
        val courseName: String,
        val teacher: String,
        val location: String,
        val weekday: Weekday,
        val startSection: Int,
        val endSection: Int,
        val startWeek: Int,
        val endWeek: Int,
        val parity: WeekParity = WeekParity.ALL,
        val semester: String = "手动添加"
    )

    enum class ImportMode { OVERWRITE_ACTIVE, CREATE_NEW }

    /** 导入向导解析结果入库：覆盖当前课表或新建课表，返回最终课表名。 */
    fun importCourses(
        filesDir: File,
        courses: List<CourseSchedule>,
        mode: ImportMode,
        newProfileName: String? = null,
        semesterStartDate: LocalDate? = null
    ): String = synchronized(lock) {
        val merged = mergeDuplicateCourses(courses)
        val meta = ensureInitialized(filesDir)
        val now = System.currentTimeMillis()
        val start = semesterStartDate ?: SemesterStartDatePolicy.inferFromCourses(merged)

        when (mode) {
            ImportMode.OVERWRITE_ACTIVE -> {
                val active = meta.profiles.firstOrNull { it.id == meta.activeId } ?: meta.profiles.first()
                writeCoursesFile(filesDir, active.coursesFile, merged)
                saveMeta(
                    filesDir,
                    meta.copy(
                        activeId = active.id,
                        profiles = meta.profiles.map {
                            if (it.id == active.id) it.copy(updatedAt = now, semesterStartDate = start.toString()) else it
                        }
                    )
                )
                active.name
            }

            ImportMode.CREATE_NEW -> {
                val id = UUID.randomUUID().toString()
                val name = newProfileName?.ifBlank { null } ?: "课表${timestampLabel()}"
                val fileName = coursesFileName(id)
                writeCoursesFile(filesDir, fileName, merged)
                saveMeta(
                    filesDir,
                    meta.copy(
                        activeId = id,
                        profiles = meta.profiles + StoredProfileMeta(
                            id = id,
                            name = name,
                            coursesFile = fileName,
                            createdAt = now,
                            updatedAt = now,
                            semesterStartDate = start.toString()
                        )
                    )
                )
                name
            }
        }
    }

    fun listProfiles(filesDir: File): List<TimetableProfile> = synchronized(lock) {
        val meta = ensureInitialized(filesDir)
        meta.profiles.map {
            TimetableProfile(
                id = it.id,
                name = it.name,
                isActive = it.id == meta.activeId,
                updatedAt = it.updatedAt,
                semesterStartDate = semesterStartDateForProfile(it)
            )
        }
    }

    fun setActiveProfile(filesDir: File, profileId: String): Boolean = synchronized(lock) {
        val meta = ensureInitialized(filesDir)
        if (meta.profiles.none { it.id == profileId }) {
            return false
        }
        saveMeta(filesDir, meta.copy(activeId = profileId))
        true
    }

    fun createEmptyProfile(filesDir: File, name: String): TimetableProfile = synchronized(lock) {
        val meta = ensureInitialized(filesDir)
        val now = System.currentTimeMillis()
        val profileId = UUID.randomUUID().toString()
        val profileName = name.ifBlank { "课表${timestampLabel()}" }
        val fileName = coursesFileName(profileId)
        writeCoursesFile(filesDir, fileName, emptyList())
        val semesterStartDate = SemesterStartDatePolicy.defaultForToday()

        val newProfile = StoredProfileMeta(
            id = profileId,
            name = profileName,
            coursesFile = fileName,
            createdAt = now,
            updatedAt = now,
            semesterStartDate = semesterStartDate.toString()
        )
        val newMeta = meta.copy(
            activeId = profileId,
            profiles = meta.profiles + newProfile
        )
        saveMeta(filesDir, newMeta)

        TimetableProfile(profileId, profileName, true, now, semesterStartDate)
    }

    fun renameProfile(filesDir: File, profileId: String, newName: String): Boolean = synchronized(lock) {
        val targetName = newName.trim()
        if (targetName.isBlank()) {
            return false
        }
        val meta = ensureInitialized(filesDir)
        if (meta.profiles.none { it.id == profileId }) {
            return false
        }
        val now = System.currentTimeMillis()
        val updatedProfiles = meta.profiles.map {
            if (it.id == profileId) {
                it.copy(name = targetName, updatedAt = now)
            } else {
                it
            }
        }
        saveMeta(filesDir, meta.copy(profiles = updatedProfiles))
        true
    }

    fun deleteProfile(filesDir: File, profileId: String): Boolean = synchronized(lock) {
        val meta = ensureInitialized(filesDir)
        val target = meta.profiles.firstOrNull { it.id == profileId } ?: return false
        if (meta.profiles.size <= 1) {
            return false
        }

        val remained = meta.profiles.filterNot { it.id == profileId }
        val nextActive = if (meta.activeId == profileId) remained.first().id else meta.activeId
        // 先写 meta 再删课程文件：中途失败只会残留孤儿文件，不会产生悬空引用
        saveMeta(filesDir, meta.copy(activeId = nextActive, profiles = remained))
        coursesFile(filesDir, target.coursesFile).takeIf { it.exists() }?.delete()
        true
    }

    fun getActiveSemesterStartDate(filesDir: File): LocalDate = synchronized(lock) {
        val meta = ensureInitialized(filesDir)
        val active = meta.profiles.firstOrNull { it.id == meta.activeId } ?: meta.profiles.first()
        semesterStartDateForProfile(active)
    }

    fun setActiveSemesterStartDate(filesDir: File, date: LocalDate): Unit = synchronized(lock) {
        val meta = ensureInitialized(filesDir)
        val active = meta.profiles.firstOrNull { it.id == meta.activeId } ?: meta.profiles.first()
        val normalized = SemesterStartDatePolicy.normalizeToWeekStart(date)
        val updatedProfiles = meta.profiles.map {
            if (it.id == active.id) {
                it.copy(updatedAt = System.currentTimeMillis(), semesterStartDate = normalized.toString())
            } else {
                it
            }
        }
        saveMeta(filesDir, meta.copy(activeId = active.id, profiles = updatedProfiles))
    }

    fun addManualCourseToActive(filesDir: File, input: ManualCourseInput): Unit = synchronized(lock) {
        require(input.courseName.isNotBlank()) { "课程名不能为空" }
        require(input.startSection in 1..12) { "开始节必须在 1-12 之间" }
        require(input.endSection in 1..12) { "结束节必须在 1-12 之间" }
        require(input.endSection >= input.startSection) { "结束节不能小于开始节" }
        require(input.startWeek in 1..30) { "开始周必须在 1-30 之间" }
        require(input.endWeek in 1..30) { "结束周必须在 1-30 之间" }
        require(input.endWeek >= input.startWeek) { "结束周不能小于开始周" }
        val meta = ensureInitialized(filesDir)
        val active = meta.profiles.firstOrNull { it.id == meta.activeId } ?: meta.profiles.first()
        val currentCourses = readCoursesFile(filesDir, active.coursesFile)
        writeCoursesFile(filesDir, active.coursesFile, currentCourses + input.toCourseSchedule())
        val now = System.currentTimeMillis()
        saveMeta(
            filesDir,
            meta.copy(
                activeId = active.id,
                profiles = meta.profiles.map {
                    if (it.id == active.id) it.copy(updatedAt = now) else it
                }
            )
        )
    }

    fun loadActiveCourses(filesDir: File): List<CourseSchedule> = synchronized(lock) {
        val meta = ensureInitialized(filesDir)
        val active = meta.profiles.firstOrNull { it.id == meta.activeId } ?: meta.profiles.first()
        readCoursesFile(filesDir, active.coursesFile)
    }

    /** 按课表 ID 加载课程（课表管理-查看完整课表）。 */
    fun loadCoursesForProfile(filesDir: File, profileId: String): List<CourseSchedule>? = synchronized(lock) {
        val meta = ensureInitialized(filesDir)
        val profile = meta.profiles.firstOrNull { it.id == profileId } ?: return null
        readCoursesFile(filesDir, profile.coursesFile)
    }

    /** 编辑课程：整体替换指定课表中的课程定义（含周规则），全局生效（所有周同步）。 */
    fun updateCourseInProfile(
        filesDir: File,
        profileId: String,
        courseIndex: Int,
        expected: CourseSchedule,
        newCourse: CourseSchedule
    ): Boolean = synchronized(lock) {
        val meta = ensureInitialized(filesDir)
        val profile = meta.profiles.firstOrNull { it.id == profileId } ?: return false
        val courses = readCoursesFile(filesDir, profile.coursesFile)
        if (courses.getOrNull(courseIndex) != expected) return false

        val updated = courses.toMutableList()
        updated[courseIndex] = newCourse
        writeCoursesFile(filesDir, profile.coursesFile, updated)
        val now = System.currentTimeMillis()
        saveMeta(filesDir, meta.copy(
            activeId = meta.activeId,
            profiles = meta.profiles.map { if (it.id == profile.id) it.copy(updatedAt = now) else it }
        ))
        true
    }

    fun exportBackup(filesDir: File): String = synchronized(lock) {
        val meta = ensureInitialized(filesDir)
        val profiles = meta.profiles.map { profile ->
            ScheduleBackupCodec.BackupProfile(
                name = profile.name,
                semesterStartDate = profile.semesterStartDate,
                courses = with(ScheduleBackupCodec) {
                    readCoursesFile(filesDir, profile.coursesFile).map { it.toBackupCourse() }
                }
            )
        }
        ScheduleBackupCodec.encode(
            exportedAt = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
            profiles = profiles,
            timeProfiles = TimeProfileStore.load(filesDir)
                .filter { it.id != TimeProfileStore.DEFAULT_PROFILE_ID }
                .map { profile ->
                    ScheduleBackupCodec.BackupTimeProfile(
                        id = profile.id,
                        name = profile.name,
                        blocks = profile.blocks.map { block ->
                            ScheduleBackupCodec.BackupBlock(
                                block.startSection, block.endSection, block.startTime, block.endTime
                            )
                        }
                    )
                },
            campusMap = TimeProfileStore.loadCampusMap(filesDir),
            defaultProfileId = TimeProfileStore.loadDefaultProfileId(filesDir)
        )
    }

    /** 用备份内容替换全部课表，返回恢复的课表数量；格式不合法时抛 IllegalArgumentException */
    fun importBackup(filesDir: File, content: String): Int = synchronized(lock) {
        val envelope = ScheduleBackupCodec.decodeEnvelope(content)
            ?: throw IllegalArgumentException("备份文件格式不正确")
        val backupProfiles = envelope.profiles
        require(backupProfiles.isNotEmpty()) { "备份文件不包含课表" }
        if (envelope.timeProfiles.isNotEmpty()) {
            TimeProfileStore.save(
                filesDir = filesDir,
                profiles = envelope.timeProfiles.map { profile ->
                    SectionTimeProfile(
                        id = profile.id,
                        name = profile.name,
                        blocks = profile.blocks.map {
                            SectionBlock(it.startSection, it.endSection, it.startTime, it.endTime)
                        }
                    )
                },
                campusMap = envelope.campusMap,
                defaultProfileId = envelope.defaultProfileId
            )
        }
        val storageDir = storageDir(filesDir)
        if (!storageDir.exists() && !storageDir.mkdirs()) {
            throw IOException("无法创建课表目录")
        }
        val oldCourseFiles = storageDir.listFiles { file ->
            file.isFile && file.name.startsWith(COURSES_FILE_PREFIX) && file.name.endsWith(COURSES_FILE_SUFFIX)
        }.orEmpty().toList()

        val now = System.currentTimeMillis()
        val stagedFiles = mutableListOf<File>()
        val newProfiles = try {
            backupProfiles.map { backup ->
                val id = UUID.randomUUID().toString()
                val fileName = coursesFileName(id)
                stagedFiles += coursesFile(filesDir, fileName)
                writeCoursesFile(
                    filesDir,
                    fileName,
                    with(ScheduleBackupCodec) { backup.courses.map { it.toCourseSchedule() } }
                )
                StoredProfileMeta(
                    id = id,
                    name = backup.name.ifBlank { "课表${timestampLabel()}" },
                    coursesFile = fileName,
                    createdAt = now,
                    updatedAt = now,
                    semesterStartDate = backup.semesterStartDate
                )
            }.also { profiles ->
                // 所有新课程文件写完后才切换元数据；失败时旧课表仍可读取。
                saveMeta(filesDir, StoredMeta(activeId = profiles.first().id, profiles = profiles))
            }
        } catch (error: Throwable) {
            stagedFiles.forEach { it.delete() }
            throw error
        }
        oldCourseFiles.filterNot { it in stagedFiles }.forEach { it.delete() }
        newProfiles.size
    }

    /** 仅删除当前课表中选中的课程；用快照比对避免页面过期后误删其他课程。 */
    fun deleteCourseFromActive(filesDir: File, courseIndex: Int, expected: CourseSchedule): Boolean = synchronized(lock) {
        val meta = ensureInitialized(filesDir)
        val active = meta.profiles.firstOrNull { it.id == meta.activeId } ?: meta.profiles.first()
        val courses = readCoursesFile(filesDir, active.coursesFile)
        if (courses.getOrNull(courseIndex) != expected) return@synchronized false

        writeCoursesFile(filesDir, active.coursesFile, courses.filterIndexed { index, _ -> index != courseIndex })
        val now = System.currentTimeMillis()
        saveMeta(filesDir, meta.copy(
            activeId = active.id,
            profiles = meta.profiles.map { if (it.id == active.id) it.copy(updatedAt = now) else it }
        ))
        true
    }

    fun exportActiveTimetableText(filesDir: File, periodRanges: List<String>): String = synchronized(lock) {
        val meta = ensureInitialized(filesDir)
        val active = meta.profiles.firstOrNull { it.id == meta.activeId } ?: meta.profiles.first()
        val courses = readCoursesFile(filesDir, active.coursesFile)
        val semesterStart = semesterStartDateForProfile(active)
        val maxWeek = courses.flatMap { it.meetings }
            .flatMap { it.weekRules }
            .maxOfOrNull { it.endWeek } ?: 0

        return buildString {
            append("课表：").append(active.name).append('\n')
            append("学期开始：").append(semesterStart).append("（第1周周一）\n")
            append("总周数：").append(maxWeek).append("\n")
            Weekday.entries.forEach { weekday ->
                append("\n【周").append(weekdayLabel(weekday)).append("】\n")
                val meetings = courses
                    .flatMap { course ->
                        course.meetings.filter { it.weekday == weekday }.map { course to it }
                    }
                    .sortedBy { it.second.startSection }
                    .distinctBy { (course, meeting) ->
                        listOf(
                            course.courseName,
                            meeting.startSection,
                            meeting.endSection,
                            meeting.location,
                            course.rawWeekText
                        )
                    }
                if (meetings.isEmpty()) {
                    append("（无课程）\n")
                }
                meetings.forEach { (course, meeting) ->
                    val startText = periodRanges.getOrNull(meeting.startSection - 1)?.substringBefore('-') ?: "--:--"
                    val endText = periodRanges.getOrNull(meeting.endSection - 1)?.substringAfter('-') ?: "--:--"
                    append("第").append(meeting.startSection).append("-").append(meeting.endSection)
                    append("节 ").append(startText).append("-").append(endText)
                    append("  ").append(course.courseName)
                    if (meeting.location.isNotBlank()) append("  ").append(meeting.location)
                    if (course.teacher.isNotBlank()) append("  ").append(course.teacher)
                    if (course.rawWeekText.isNotBlank()) append("  ").append(course.rawWeekText)
                    append('\n')
                }
            }
        }
    }

    private fun weekdayLabel(weekday: Weekday): String = when (weekday) {
        Weekday.MONDAY -> "一"
        Weekday.TUESDAY -> "二"
        Weekday.WEDNESDAY -> "三"
        Weekday.THURSDAY -> "四"
        Weekday.FRIDAY -> "五"
        Weekday.SATURDAY -> "六"
        Weekday.SUNDAY -> "日"
    }

    private fun ensureInitialized(filesDir: File): StoredMeta {
        val storageDir = storageDir(filesDir)
        if (!storageDir.exists()) {
            storageDir.mkdirs()
        }
        cleanupStaleTempFiles(storageDir)

        val metaFile = metaFile(filesDir)
        if (metaFile.exists() && metaFile.length() > 0L) {
            val existing = runCatching {
                json.decodeFromString<StoredMeta>(metaFile.readText(Charsets.UTF_8))
            }.onFailure { Log.w(TAG, "Failed to read timetable meta", it) }
                .getOrNull()
            if (existing != null && existing.profiles.isNotEmpty()) {
                val migrated = fillMissingSemesterStartDates(filesDir, existing)
                if (migrated != existing) {
                    saveMeta(filesDir, migrated)
                }
                return migrated
            }
            if (existing == null) {
                // meta 损坏：保留现场用于排查，随后尝试从孤儿课程文件恢复，避免清空用户数据
                val backup = File(storageDir, "$META_FILE_NAME.corrupt-${System.currentTimeMillis()}")
                runCatching { metaFile.renameTo(backup) }
                    .onFailure { Log.w(TAG, "Failed to back up corrupt meta file", it) }
            }
        }

        val salvaged = salvageProfilesFromOrphanFiles(storageDir)
        if (salvaged != null) {
            saveMeta(filesDir, salvaged)
            return salvaged
        }

        return initializeDefaultProfile(filesDir)
    }

    private fun initializeDefaultProfile(filesDir: File): StoredMeta {
        val now = System.currentTimeMillis()
        val defaultId = UUID.randomUUID().toString()
        val defaultFile = coursesFileName(defaultId)
        writeCoursesFile(filesDir, defaultFile, emptyList())
        val semesterStartDate = SemesterStartDatePolicy.defaultForToday()

        val initialized = StoredMeta(
            activeId = defaultId,
            profiles = listOf(
                StoredProfileMeta(
                    id = defaultId,
                    name = DEFAULT_PROFILE_NAME,
                    coursesFile = defaultFile,
                    createdAt = now,
                    updatedAt = now,
                    semesterStartDate = semesterStartDate.toString()
                )
            )
        )
        saveMeta(filesDir, initialized)
        return initialized
    }

    private fun salvageProfilesFromOrphanFiles(storageDir: File): StoredMeta? {
        val orphanFiles = storageDir.listFiles { file ->
            file.isFile &&
                file.name.startsWith(COURSES_FILE_PREFIX) &&
                file.name.endsWith(COURSES_FILE_SUFFIX)
        }?.filter { it.length() > 0L }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()
        if (orphanFiles.isEmpty()) {
            return null
        }

        val profiles = orphanFiles.mapIndexed { index, file ->
            val id = file.name.removePrefix(COURSES_FILE_PREFIX).removeSuffix(COURSES_FILE_SUFFIX)
            val courses = readCoursesFile(storageDir.parentFile ?: storageDir, file.name)
            StoredProfileMeta(
                id = id,
                name = if (index == 0) DEFAULT_PROFILE_NAME else "恢复课表${index + 1}",
                coursesFile = file.name,
                createdAt = file.lastModified(),
                updatedAt = file.lastModified(),
                semesterStartDate = SemesterStartDatePolicy.inferFromCourses(courses).toString()
            )
        }.filter { it.id.isNotBlank() }
        if (profiles.isEmpty()) {
            return null
        }

        Log.w(TAG, "Recovered ${profiles.size} profile(s) from orphan course files")
        return StoredMeta(activeId = profiles.first().id, profiles = profiles)
    }

    private fun cleanupStaleTempFiles(storageDir: File) {
        storageDir.listFiles { file -> file.isFile && file.name.endsWith(TEMP_FILE_SUFFIX) }
            ?.forEach { stale ->
                runCatching { stale.delete() }
                    .onFailure { Log.w(TAG, "Failed to delete stale temp file ${stale.name}", it) }
            }
    }

    private fun storageDir(filesDir: File): File = File(filesDir, STORAGE_DIR)

    private fun metaFile(filesDir: File): File = File(storageDir(filesDir), META_FILE_NAME)

    private fun coursesFile(filesDir: File, fileName: String): File = File(storageDir(filesDir), fileName)

    private fun coursesFileName(profileId: String): String = "$COURSES_FILE_PREFIX$profileId$COURSES_FILE_SUFFIX"

    private fun saveMeta(filesDir: File, meta: StoredMeta) {
        writeTextAtomically(metaFile(filesDir), json.encodeToString(meta))
    }

    private fun readCoursesFile(filesDir: File, fileName: String): List<CourseSchedule> {
        val file = coursesFile(filesDir, fileName)
        if (!file.exists() || file.length() == 0L) {
            return emptyList()
        }
        val persisted = runCatching {
            json.decodeFromString<List<PersistedCourse>>(file.readText(Charsets.UTF_8))
        }.onFailure {
            Log.w(TAG, "Failed to read courses file $fileName", it)
        }.getOrDefault(emptyList())
        val loaded = persisted.map { it.toCourseSchedule() }
        val merged = mergeDuplicateCourses(loaded)
        if (merged.size != loaded.size) {
            runCatching { writeCoursesFile(filesDir, fileName, merged) }
        }
        return merged
    }

    private fun writeCoursesFile(filesDir: File, fileName: String, courses: List<CourseSchedule>) {
        val persisted = courses.map { PersistedCourse.fromCourseSchedule(it) }
        writeTextAtomically(coursesFile(filesDir, fileName), json.encodeToString(persisted))
    }

    private fun writeTextAtomically(target: File, content: String) {
        val parent = target.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw IOException("Cannot create directory ${parent.absolutePath}")
        }
        val tempParent = parent ?: throw IOException("Cannot resolve parent for ${target.absolutePath}")
        val temp = File(tempParent, "${target.name}.${System.nanoTime()}$TEMP_FILE_SUFFIX")
        temp.writeText(content, Charsets.UTF_8)
        try {
            Files.move(
                temp.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } catch (error: Throwable) {
            temp.delete()
            throw error
        }
    }

    private fun fillMissingSemesterStartDates(filesDir: File, meta: StoredMeta): StoredMeta {
        var changed = false
        val migratedProfiles = meta.profiles.map { profile ->
            if (!profile.semesterStartDate.isNullOrBlank()) {
                profile
            } else {
                changed = true
                val courses = readCoursesFile(filesDir, profile.coursesFile)
                val inferred = SemesterStartDatePolicy.inferFromCourses(courses)
                profile.copy(semesterStartDate = inferred.toString())
            }
        }
        return if (changed) meta.copy(profiles = migratedProfiles) else meta
    }

    private fun semesterStartDateForProfile(profile: StoredProfileMeta): LocalDate {
        val raw = profile.semesterStartDate
        if (raw.isNullOrBlank()) {
            return SemesterStartDatePolicy.defaultForToday()
        }
        return try {
            SemesterStartDatePolicy.normalizeToWeekStart(LocalDate.parse(raw))
        } catch (error: DateTimeParseException) {
            Log.w(TAG, "Invalid semester start date in profile ${profile.id}: $raw", error)
            SemesterStartDatePolicy.defaultForToday()
        }
    }

    private fun mergeDuplicateCourses(courses: List<CourseSchedule>): List<CourseSchedule> {
        return courses.groupBy { course ->
            val semKey = SemesterStartDatePolicy.normalizedSemesterKey(course.semester) ?: course.semester.trim()
            "${course.courseName.trim()}|${course.teacher.trim()}|$semKey"
        }.values.map { group ->
            val first = group.first()
            if (group.size == 1) {
                first
            } else {
                val allMeetings = group.flatMap { it.meetings }.distinctBy { m ->
                    "${m.weekday}|${m.startSection}|${m.endSection}|${m.location.trim()}|" +
                        m.weekRules.sortedBy { "${it.startWeek}-${it.endWeek}-${it.parity}" }
                            .joinToString(",") { "${it.startWeek}-${it.endWeek}-${it.parity}" }
                }
                val credit = group.firstNotNullOfOrNull { it.credit }
                val weekText = group.map { it.rawWeekText.trim() }.filter { it.isNotBlank() }.distinct().joinToString(",")
                first.copy(
                    credit = credit,
                    rawWeekText = weekText.ifBlank { first.rawWeekText },
                    meetings = allMeetings
                )
            }
        }
    }

    private fun ManualCourseInput.toCourseSchedule(): CourseSchedule {
        return CourseSchedule(
            courseName = courseName.trim(),
            teacher = teacher,
            semester = semester,
            credit = null,
            rawWeekText = "${startWeek}-${endWeek}周",
            meetings = listOf(
                MeetingTime(
                    weekday = weekday,
                    startSection = startSection,
                    endSection = endSection,
                    weekRules = listOf(WeekRule(startWeek, endWeek, parity)),
                    location = location
                )
            )
        )
    }

    private fun timestampLabel(): String {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("MMdd_HHmm"))
    }

    @Serializable
    private data class StoredMeta(
        val activeId: String = "",
        val profiles: List<StoredProfileMeta> = emptyList()
    )

    @Serializable
    private data class StoredProfileMeta(
        val id: String,
        val name: String,
        val coursesFile: String,
        val createdAt: Long,
        val updatedAt: Long,
        val semesterStartDate: String? = null
    )

    @Serializable
    private data class PersistedCourse(
        val courseName: String,
        val teacher: String,
        val semester: String,
        val credit: Double?,
        val rawWeekText: String,
        val meetings: List<PersistedMeeting>
    ) {
        fun toCourseSchedule(): CourseSchedule {
            return CourseSchedule(
                courseName = courseName,
                teacher = teacher,
                semester = semester,
                credit = credit,
                rawWeekText = rawWeekText,
                meetings = meetings.map { it.toMeetingTime() }
            )
        }

        companion object {
            fun fromCourseSchedule(course: CourseSchedule): PersistedCourse {
                return PersistedCourse(
                    courseName = course.courseName,
                    teacher = course.teacher,
                    semester = course.semester,
                    credit = course.credit,
                    rawWeekText = course.rawWeekText,
                    meetings = course.meetings.map { PersistedMeeting.fromMeetingTime(it) }
                )
            }
        }
    }

    @Serializable
    private data class PersistedMeeting(
        val weekday: String,
        val startSection: Int,
        val endSection: Int,
        val weekRules: List<PersistedWeekRule>,
        val location: String,
        val campusId: String? = null
    ) {
        fun toMeetingTime(): MeetingTime {
            return MeetingTime(
                weekday = runCatching { Weekday.valueOf(weekday) }.getOrDefault(Weekday.MONDAY),
                startSection = startSection,
                endSection = endSection,
                weekRules = weekRules.map { it.toWeekRule() },
                location = location,
                campusId = campusId
            )
        }

        companion object {
            fun fromMeetingTime(meeting: MeetingTime): PersistedMeeting {
                return PersistedMeeting(
                    weekday = meeting.weekday.name,
                    startSection = meeting.startSection,
                    endSection = meeting.endSection,
                    weekRules = meeting.weekRules.map { PersistedWeekRule.fromWeekRule(it) },
                    location = meeting.location,
                    campusId = meeting.campusId
                )
            }
        }
    }

    @Serializable
    private data class PersistedWeekRule(
        val startWeek: Int,
        val endWeek: Int,
        val parity: String
    ) {
        fun toWeekRule(): WeekRule {
            return WeekRule(
                startWeek = startWeek,
                endWeek = endWeek,
                parity = runCatching { WeekParity.valueOf(parity) }.getOrDefault(WeekParity.ALL)
            )
        }

        companion object {
            fun fromWeekRule(rule: WeekRule): PersistedWeekRule {
                return PersistedWeekRule(rule.startWeek, rule.endWeek, rule.parity.name)
            }
        }
    }
}
