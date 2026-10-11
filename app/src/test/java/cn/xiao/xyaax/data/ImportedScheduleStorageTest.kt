package cn.xiao.xyaax.data

import cn.xiao.xyaax.model.Weekday
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.LocalDate
import kotlin.concurrent.thread

class ImportedScheduleStorageTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val filesDir: File
        get() = tmp.root

    private fun storageDir(): File = File(tmp.root, "timetables")

    private fun manualCourse(name: String = "手动课程"): ImportedScheduleStorage.ManualCourseInput {
        return ImportedScheduleStorage.ManualCourseInput(
            courseName = name,
            teacher = "老师",
            location = "教室",
            weekday = Weekday.MONDAY,
            startSection = 1,
            endSection = 2,
            startWeek = 1,
            endWeek = 16
        )
    }

    @Test
    fun init_createsSingleDefaultProfileWithEmptyCourses() {
        val profiles = ImportedScheduleStorage.listProfiles(filesDir)

        assertEquals(1, profiles.size)
        assertTrue(profiles.single().isActive)
        assertEquals("默认课表", profiles.single().name)
        assertTrue(ImportedScheduleStorage.loadActiveCourses(filesDir).isEmpty())
    }

    @Test
    fun addManualCourse_persistsAndReloads() {
        ImportedScheduleStorage.listProfiles(filesDir)
        ImportedScheduleStorage.addManualCourseToActive(filesDir, manualCourse("大学物理"))

        val courses = ImportedScheduleStorage.loadActiveCourses(filesDir)
        assertEquals(1, courses.size)
        assertEquals("大学物理", courses.single().courseName)
        assertEquals(Weekday.MONDAY, courses.single().meetings.single().weekday)
    }

    @Test
    fun deleteCourse_removesOnlySelectedManualCourseAndRejectsStaleSnapshot() {
        ImportedScheduleStorage.addManualCourseToActive(filesDir, manualCourse("同名课程"))
        ImportedScheduleStorage.addManualCourseToActive(filesDir, manualCourse("同名课程").copy(teacher = "另一位老师"))
        val courses = ImportedScheduleStorage.loadActiveCourses(filesDir)

        assertFalse(ImportedScheduleStorage.deleteCourseFromActive(filesDir, 1, courses[0]))
        assertEquals(2, ImportedScheduleStorage.loadActiveCourses(filesDir).size)
        assertTrue(ImportedScheduleStorage.deleteCourseFromActive(filesDir, 1, courses[1]))
        assertEquals(courses[0], ImportedScheduleStorage.loadActiveCourses(filesDir).single())
    }

    @Test
    fun addManualCourse_rejectsInvalidInput() {
        ImportedScheduleStorage.listProfiles(filesDir)

        assertThrows(IllegalArgumentException::class.java) {
            ImportedScheduleStorage.addManualCourseToActive(
                filesDir,
                manualCourse().copy(startSection = 0)
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ImportedScheduleStorage.addManualCourseToActive(
                filesDir,
                manualCourse().copy(endWeek = 31)
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ImportedScheduleStorage.addManualCourseToActive(
                filesDir,
                manualCourse().copy(courseName = "  ")
            )
        }
        assertTrue(ImportedScheduleStorage.loadActiveCourses(filesDir).isEmpty())
    }

    @Test
    fun createEmptyProfile_switchesActiveAndAddsProfile() {
        val original = ImportedScheduleStorage.listProfiles(filesDir).single()

        val created = ImportedScheduleStorage.createEmptyProfile(filesDir, "第二课表")

        val profiles = ImportedScheduleStorage.listProfiles(filesDir)
        assertEquals(2, profiles.size)
        assertTrue(profiles.single { it.id == created.id }.isActive)
        assertFalse(profiles.single { it.id == original.id }.isActive)
        assertTrue(ImportedScheduleStorage.loadActiveCourses(filesDir).isEmpty())
    }

    @Test
    fun deleteProfile_removesProfileAndCoursesFile() {
        val original = ImportedScheduleStorage.listProfiles(filesDir).single()
        ImportedScheduleStorage.addManualCourseToActive(filesDir, manualCourse())
        val created = ImportedScheduleStorage.createEmptyProfile(filesDir, "待删除")

        val deleted = ImportedScheduleStorage.deleteProfile(filesDir, created.id)

        assertTrue(deleted)
        val profiles = ImportedScheduleStorage.listProfiles(filesDir)
        assertEquals(1, profiles.size)
        assertEquals(original.id, profiles.single().id)
        assertTrue(profiles.single().isActive)
        // 被删除课表的课程文件应一并清理
        assertEquals(1, storageDir().listFiles { f -> f.name.startsWith("courses_") }!!.size)
    }

    @Test
    fun deleteProfile_refusesToRemoveLastProfile() {
        val only = ImportedScheduleStorage.listProfiles(filesDir).single()

        assertFalse(ImportedScheduleStorage.deleteProfile(filesDir, only.id))
        assertEquals(1, ImportedScheduleStorage.listProfiles(filesDir).size)
    }

    @Test
    fun corruptedMeta_recoversProfilesFromOrphanCourseFiles() {
        ImportedScheduleStorage.addManualCourseToActive(filesDir, manualCourse("持久课程"))
        val metaFile = File(storageDir(), "meta.json")
        assertTrue(metaFile.exists())
        metaFile.writeText("{corrupted json", Charsets.UTF_8)

        val profiles = ImportedScheduleStorage.listProfiles(filesDir)

        assertEquals(1, profiles.size)
        val courses = ImportedScheduleStorage.loadActiveCourses(filesDir)
        assertEquals(1, courses.size)
        assertEquals("持久课程", courses.single().courseName)
        // 损坏的 meta 已备份留存
        val backups = storageDir().listFiles { f -> f.name.startsWith("meta.json.corrupt") }
        assertTrue(backups.orEmpty().isNotEmpty())
    }

    @Test
    fun concurrentManualAdds_doNotLoseUpdates() {
        ImportedScheduleStorage.listProfiles(filesDir)

        val workers = (1..4).map { worker ->
            thread {
                repeat(10) { index ->
                    ImportedScheduleStorage.addManualCourseToActive(
                        filesDir,
                        manualCourse("课${worker}-$index")
                    )
                }
            }
        }
        workers.forEach { it.join() }

        assertEquals(40, ImportedScheduleStorage.loadActiveCourses(filesDir).size)
    }

    @Test
    fun setActiveSemesterStartDate_normalizesToMonday() {
        ImportedScheduleStorage.listProfiles(filesDir)

        ImportedScheduleStorage.setActiveSemesterStartDate(filesDir, LocalDate.of(2026, 9, 2))

        assertEquals(LocalDate.of(2026, 8, 31), ImportedScheduleStorage.getActiveSemesterStartDate(filesDir))
    }

    @Test
    fun backup_roundTrip_restoresAllProfiles() {
        ImportedScheduleStorage.listProfiles(filesDir)
        ImportedScheduleStorage.addManualCourseToActive(filesDir, manualCourse("课A"))
        ImportedScheduleStorage.createEmptyProfile(filesDir, "空课表")
        ImportedScheduleStorage.addManualCourseToActive(filesDir, manualCourse("课B"))

        val backup = ImportedScheduleStorage.exportBackup(filesDir)
        assertTrue(backup.contains("课A"))
        // 备份后再改动数据，恢复应回到备份时点
        ImportedScheduleStorage.addManualCourseToActive(filesDir, manualCourse("多余课程"))

        val restored = ImportedScheduleStorage.importBackup(filesDir, backup)

        assertEquals(2, restored)
        val profiles = ImportedScheduleStorage.listProfiles(filesDir)
        assertEquals(2, profiles.size)
        // 恢复后激活第一个备份课表（默认课表，含 课A）
        val courses = ImportedScheduleStorage.loadActiveCourses(filesDir)
        assertEquals(1, courses.size)
        assertEquals("课A", courses.single().courseName)
    }

    @Test
    fun importBackup_rejectsInvalidContent() {
        ImportedScheduleStorage.listProfiles(filesDir)

        assertThrows(IllegalArgumentException::class.java) {
            ImportedScheduleStorage.importBackup(filesDir, "not a json {")
        }
        assertEquals(1, ImportedScheduleStorage.listProfiles(filesDir).size)
    }

    @Test
    fun importBackup_rejectsEmptyProfilesWithoutRemovingExistingCourses() {
        ImportedScheduleStorage.addManualCourseToActive(filesDir, manualCourse("保留课程"))

        assertThrows(IllegalArgumentException::class.java) {
            ImportedScheduleStorage.importBackup(filesDir, """{"version":1,"profiles":[]}""")
        }

        assertEquals("保留课程", ImportedScheduleStorage.loadActiveCourses(filesDir).single().courseName)
    }

    @Test
    fun exportActiveTimetableText_containsCourseInfo() {
        ImportedScheduleStorage.listProfiles(filesDir)
        ImportedScheduleStorage.addManualCourseToActive(filesDir, manualCourse("大学物理"))

        val text = ImportedScheduleStorage.exportActiveTimetableText(
            filesDir,
            listOf("08:00-08:45", "08:45-09:40")
        )

        assertTrue(text.contains("大学物理"))
        assertTrue(text.contains("第1-2节"))
        assertTrue(text.contains("08:00"))
        assertTrue(text.contains("总周数：16"))
    }

    @Test
    fun importCourses_persistsNotesAndReloads() {
        ImportedScheduleStorage.listProfiles(filesDir)
        val notes = listOf("军事技能（张爽）1-19周", "学期时间：2026-08-31至2027-01-22，共21周")

        ImportedScheduleStorage.importCourses(
            filesDir,
            listOf(),
            ImportedScheduleStorage.ImportMode.OVERWRITE_ACTIVE,
            notes = notes
        )

        val profile = ImportedScheduleStorage.listProfiles(filesDir).single()
        assertEquals(notes, profile.notes)
    }

    @Test
    fun oldProfilesWithoutNotes_defaultToEmptyList() {
        // 旧数据：meta.json 不含 notes 字段，读取必须兜底为空列表、不报错
        ImportedScheduleStorage.listProfiles(filesDir)
        val metaFile = File(storageDir(), "meta.json")
        val legacyMeta = metaFile.readText(Charsets.UTF_8)
            .replace(Regex(""","notes":\[[^\]]*\]"""), "")
        metaFile.writeText(legacyMeta, Charsets.UTF_8)

        val profile = ImportedScheduleStorage.listProfiles(filesDir).single()
        assertTrue(profile.notes.isEmpty())
    }

    @Test
    fun backup_roundTrip_restoresNotes() {
        ImportedScheduleStorage.listProfiles(filesDir)
        ImportedScheduleStorage.importCourses(
            filesDir,
            listOf(),
            ImportedScheduleStorage.ImportMode.OVERWRITE_ACTIVE,
            notes = listOf("入学教育（张爽）1-19周")
        )

        val backup = ImportedScheduleStorage.exportBackup(filesDir)
        assertTrue(backup.contains("入学教育（张爽）1-19周"))

        ImportedScheduleStorage.importBackup(filesDir, backup)
        val restored = ImportedScheduleStorage.listProfiles(filesDir).single()
        assertEquals(listOf("入学教育（张爽）1-19周"), restored.notes)
    }

    @Test
    fun renameProfile_updatesNameAndRejectsBlank() {
        val profile = ImportedScheduleStorage.listProfiles(filesDir).single()

        assertFalse(ImportedScheduleStorage.renameProfile(filesDir, profile.id, "   "))
        assertTrue(ImportedScheduleStorage.renameProfile(filesDir, profile.id, "主课表"))
        assertEquals("主课表", ImportedScheduleStorage.listProfiles(filesDir).single().name)
    }
}
