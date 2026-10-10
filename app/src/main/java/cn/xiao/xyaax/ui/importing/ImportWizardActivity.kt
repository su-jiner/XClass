package cn.xiao.xyaax.ui.importing

import android.app.Activity
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.lifecycleScope
import cn.xiao.xyaax.R
import cn.xiao.xyaax.data.AppPreferences
import cn.xiao.xyaax.data.ScheduleRepository
import cn.xiao.xyaax.data.TimeProfileStore
import cn.xiao.xyaax.importing.CourseTableImporter
import cn.xiao.xyaax.importing.CourseTableParseResult
import cn.xiao.xyaax.importing.DocumentTextExtractor
import cn.xiao.xyaax.importing.ParsedCourse
import cn.xiao.xyaax.importing.ParsedMeeting
import cn.xiao.xyaax.importing.WeekRuleTextParser
import cn.xiao.xyaax.ui.theme.ThemePalette
import cn.xiao.xyaax.ui.theme.ThemePaletteProvider
import cn.xiao.xyaax.ui.theme.UiFeedback
import cn.xiao.xyaax.ui.theme.UiTransitions
import cn.xiao.xyaax.ui.theme.applySystemBarIcons
import cn.xiao.xyaax.ui.theme.applySystemBarPadding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 课表导入向导：选择或接收文件 → 本地解析（文本层 / 离线 OCR / OOXML）→ 预览校对 → 入库。
 * 全程离线，导入结果一律先经用户确认再写入课表。
 */
class ImportWizardActivity : AppCompatActivity() {
    /** 一组待导入内容：一个来源（文件/粘贴）= 一个课表 */
    private data class ParsedGroup(
        val name: String,
        val parsed: CourseTableParseResult,
        val courses: MutableList<ParsedCourse>
    )
    private lateinit var palette: ThemePalette
    private lateinit var summary: TextView
    private lateinit var listContainer: LinearLayout
    private lateinit var saveButton: Button

    private var result: CourseTableParseResult? = null
    /** 多文件/粘贴导入分组：一个来源 = 一个课表（课程时间） */
    private val groups = mutableListOf<ParsedGroup>()
    private var lastUri: Uri? = null

    private val pickFilesLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> uris.takeIf { it.isNotEmpty() }?.let { parseFiles(it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemePaletteProvider.applyNightMode(this)
        setTheme(ThemePaletteProvider.themeStyleFor(AppPreferences.getThemeColor(this)))
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_import_wizard)
        findViewById<View>(R.id.importRoot).applySystemBarPadding()
        palette = ThemePaletteProvider.fromContext(this)
        window.applySystemBarIcons(palette.isDark)
        UiTransitions.applyEnter(this)

        summary = findViewById(R.id.importSummary)
        listContainer = findViewById(R.id.importListContainer)
        saveButton = findViewById(R.id.importSaveBtn)

        findViewById<ImageView>(R.id.importBackBtn).setOnClickListener { finish() }
        findViewById<Button>(R.id.importPickBtn).setOnClickListener { openPicker() }
        saveButton.setOnClickListener { confirmSave() }
        saveButton.isEnabled = false

        val shared = intent?.let { resolveSharedUri(it) }
        val pastedText = intent?.getStringExtra(EXTRA_PASTE_TEXT)
        when {
            !pastedText.isNullOrBlank() -> parsePastedText(pastedText)
            shared != null -> parseFile(shared)
            else -> openPicker()
        }
    }

    /** 粘贴文本导入：与文件导入共用解析管线，只跳过提取步骤。 */
    private fun parsePastedText(text: String) {
        summary.text = getString(R.string.import_parsing)
        listContainer.removeAllViews()
        saveButton.isEnabled = false
        lifecycleScope.launch {
            val parsed = withContext(Dispatchers.IO) {
                CourseTableImporter.parse(cn.xiao.xyaax.importing.PlainTextLayout.toBlocks(text), text)
            }
            applyParseResult("粘贴文本", parsed)
        }
    }

    private fun applyParseResult(sourceNote: String, parsed: CourseTableParseResult) {
        result = parsed
        groups.clear()
        groups.add(ParsedGroup(sourceNote, parsed, parsed.courses.toMutableList()))
        summary.text = buildString {
            append(sourceNote)
            parsed.semester?.let { append(" · ").append(it) }
            parsed.totalWeeks?.let { append(" · 共").append(it).append("周") }
            append(" · 识别 ").append(parsed.courses.size).append(" 门课")
            if (parsed.warnings.isNotEmpty()) append("\n").append(parsed.warnings.take(3).joinToString("；"))
        }
        renderGroups()
        saveButton.isEnabled = groups.any { it.courses.isNotEmpty() }
    }

    override fun finish() {
        super.finish()
        UiTransitions.applyExit(this)
    }

    private fun resolveSharedUri(intent: Intent): Uri? {
        return intent.getParcelableExtra(Intent.EXTRA_STREAM)
            ?: intent.data?.takeIf { it.scheme == "content" || it.scheme == "file" }
    }

    private fun openPicker() {
        // 多选：一个文件 = 一个课表
        pickFilesLauncher.launch(
            arrayOf(
                "application/pdf",
                "image/*",
                "text/*",
                "text/csv",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "*/*"
            )
        )
    }

    private fun parseFiles(uris: List<Uri>) {
        summary.text = getString(R.string.import_parsing)
        saveButton.isEnabled = false
        listContainer.removeAllViews()
        lifecycleScope.launch {
            var total = 0
            uris.forEach { uri ->
                val group = runCatching {
                    val (name, mime) = queryMeta(uri)
                    withContext(Dispatchers.IO) {
                        val document = DocumentTextExtractor.extract(this@ImportWizardActivity, uri, name, mime)
                        val parsed = CourseTableImporter.parse(document.blocks, document.rawText)
                        ParsedGroup(name, parsed, parsed.courses.toMutableList())
                    }
                }
                group.onSuccess {
                    total += it.courses.size
                    groups.add(it)
                }.onFailure { error ->
                    UiFeedback.showMessage(listContainer, getString(R.string.import_failed, error.message ?: "未知错误"), palette)
                }
            }
            summary.text = buildString {
                append("已解析 ").append(groups.size).append(" 份课表文件 · 识别 ").append(total).append(" 门课")
            }
            renderGroups()
            saveButton.isEnabled = groups.any { it.courses.isNotEmpty() }
        }
    }

    /** 单文件/共享 Intent 场景：包装为单组。 */
    private fun parseFile(uri: Uri) {
        lastUri = uri
        summary.text = getString(R.string.import_parsing)
        saveButton.isEnabled = false
        listContainer.removeAllViews()
        lifecycleScope.launch {
            val outcome = runCatching {
                val (name, mime) = queryMeta(uri)
                withContext(Dispatchers.IO) {
                    val document = DocumentTextExtractor.extract(this@ImportWizardActivity, uri, name, mime)
                    val parsed = CourseTableImporter.parse(document.blocks, document.rawText)
                    ParsedGroup(name, parsed, parsed.courses.toMutableList())
                }
            }
            outcome.onSuccess { group ->
                groups.add(group)
                summary.text = buildString {
                    append(group.name)
                    group.parsed.semester?.let { append(" · ").append(it) }
                    group.parsed.totalWeeks?.let { append(" · 共").append(it).append("周") }
                    append(" · 识别 ").append(group.courses.size).append(" 门课")
                    if (group.parsed.warnings.isNotEmpty()) append("\n").append(group.parsed.warnings.take(3).joinToString("；"))
                }
                renderGroups()
                saveButton.isEnabled = true
            }.onFailure { error ->
                summary.text = getString(R.string.import_failed, error.message ?: "未知错误")
                UiFeedback.showMessage(listContainer, getString(R.string.import_failed, error.message ?: ""), palette)
            }
        }
    }

    private fun queryMeta(uri: Uri): Pair<String, String?> {
        var name = uri.lastPathSegment.orEmpty()
        val mime = contentResolver.getType(uri)
        runCatching {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) name = cursor.getString(index) ?: name
            }
        }
        return name to mime
    }

    private fun renderGroups() {
        listContainer.removeAllViews()
        if (groups.isEmpty() || groups.none { it.courses.isNotEmpty() }) {
            listContainer.addView(TextView(this).apply {
                text = getString(R.string.import_empty)
                setTextColor(palette.textSecondary)
                textSize = 13f
            })
            return
        }
        groups.forEachIndexed { groupIndex, group ->
            if (group.courses.isEmpty()) return@forEachIndexed
            if (groups.count { it.courses.isNotEmpty() } > 1) {
                listContainer.addView(TextView(this).apply {
                    text = group.name
                    setTextColor(palette.textPrimary)
                    textSize = 14f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setPadding(0, if (groupIndex == 0) 0 else (14 * resources.displayMetrics.density).toInt(), 0, (6 * resources.displayMetrics.density).toInt())
                })
            }
            group.courses.forEachIndexed { courseIndex, course ->
                listContainer.addView(buildRow(groupIndex, courseIndex, course))
            }
        }
    }

    private fun buildRow(groupIndex: Int, index: Int, course: ParsedCourse): View {
        val density = resources.displayMetrics.density
        val hasWarning = course.warnings.isNotEmpty() || course.meetings.any { it.campusAlias != null }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = 14f * density
                setColor(palette.panelAltBackground)
                setStroke(maxOf(1, density.toInt()), ColorUtils.blendARGB(palette.panelAltBackground, palette.iconTint, 0.18f))
            }
            setPadding((14 * density).toInt(), (12 * density).toInt(), (14 * density).toInt(), (12 * density).toInt())
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (10 * density).toInt() }
            layoutParams = params
            addView(TextView(this@ImportWizardActivity).apply {
                text = course.courseName
                setTextColor(palette.textPrimary)
                textSize = 15f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            addView(TextView(this@ImportWizardActivity).apply {
                text = course.meetings.joinToString("；") { meeting -> meetingLabel(meeting) }
                setTextColor(if (hasWarning) palette.iconTint else palette.textSecondary)
                textSize = 12f
            })
            if (course.teacher.isNotBlank()) {
                addView(TextView(this@ImportWizardActivity).apply {
                    text = course.teacher
                    setTextColor(palette.textSecondary)
                    textSize = 12f
                })
            }
            setOnClickListener { editCourse(groupIndex, index) }
            setOnLongClickListener {
                confirmDelete(groupIndex, index)
                true
            }
        }
    }

    private fun meetingLabel(meeting: ParsedMeeting): String {
        val weekday = when (meeting.weekday.ordinal) {
            0 -> "一"; 1 -> "二"; 2 -> "三"; 3 -> "四"; 4 -> "五"; 5 -> "六"; else -> "日"
        }
        val weeks = meeting.weekRules.joinToString(",") { rule ->
            val base = if (rule.startWeek == rule.endWeek) "${rule.startWeek}" else "${rule.startWeek}-${rule.endWeek}"
            when (rule.parity) {
                cn.xiao.xyaax.model.WeekParity.ODD -> "${base}周(单)"
                cn.xiao.xyaax.model.WeekParity.EVEN -> "${base}周(双)"
                else -> "${base}周"
            }
        }
        val campus = meeting.campusAlias?.let { "（$it）" } ?: ""
        val location = meeting.location.takeIf { it.isNotBlank() }?.let { "  $campus$it" } ?: ""
        return "周$weekday 第${meeting.startSection}-${meeting.endSection}节 $weeks$location"
    }

    private fun editCourse(groupIndex: Int, index: Int) {
        val course = groups.getOrNull(groupIndex)?.courses?.getOrNull(index) ?: return
        val density = resources.displayMetrics.density
        val nameInput = EditText(this).apply { setText(course.courseName); hint = "课程名称" }
        val teacherInput = EditText(this).apply { setText(course.teacher); hint = "教师" }
        val locationInput = EditText(this).apply { setText(course.meetings.firstOrNull()?.location.orEmpty()); hint = "地点" }
        val weekInput = EditText(this).apply {
            setText(course.meetings.firstOrNull()?.let { meetingLabel(it) }.orEmpty())
            hint = "周次（如 1-16周(单)）"
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((28 * density).toInt(), (20 * density).toInt(), (28 * density).toInt(), 0)
            addView(nameInput); addView(teacherInput); addView(locationInput); addView(weekInput)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.import_edit_course)
            .setView(panel)
            .setPositiveButton(R.string.action_save) { _, _ ->
                val updatedMeetings = course.meetings.mapIndexed { meetingIndex, meeting ->
                    if (meetingIndex != 0) meeting else meeting.copy(
                        location = locationInput.text.toString().trim(),
                        weekRules = WeekRuleTextParser.parse(weekInput.text.toString())
                    )
                }
                groups[groupIndex].courses[index] = course.copy(
                    courseName = nameInput.text.toString().trim().ifBlank { course.courseName },
                    teacher = teacherInput.text.toString().trim(),
                    meetings = updatedMeetings
                )
                renderGroups()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun confirmDelete(groupIndex: Int, index: Int) {
        val group = groups.getOrNull(groupIndex) ?: return
        AlertDialog.Builder(this)
            .setTitle(R.string.import_delete_course)
            .setMessage(getString(R.string.import_delete_message, group.courses.getOrNull(index)?.courseName.orEmpty()))
            .setPositiveButton(R.string.manage_delete) { _, _ ->
                group.courses.removeAt(index)
                renderGroups()
                saveButton.isEnabled = groups.any { it.courses.isNotEmpty() }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun confirmSave() {
        if (groups.none { it.courses.isNotEmpty() }) return
        if (groups.count { it.courses.isNotEmpty() } == 1) {
            val options = arrayOf(getString(R.string.import_mode_overwrite), getString(R.string.import_mode_new))
            AlertDialog.Builder(this)
                .setTitle(R.string.import_save_to_timetable)
                .setItems(options) { _, which ->
                    persist(overwrite = which == 0)
                }
                .setNegativeButton(R.string.action_cancel, null)
                .show()
        } else {
            // 多文件：一个文件 = 一个课表，全部新建课表导入
            AlertDialog.Builder(this)
                .setTitle(R.string.import_save_to_timetable)
                .setMessage(getString(R.string.import_save_multiple_message, groups.count { it.courses.isNotEmpty() }))
                .setPositiveButton(R.string.action_confirm) { _, _ -> persist(overwrite = false) }
                .setNegativeButton(R.string.action_cancel, null)
                .show()
        }
    }

    private fun persist(overwrite: Boolean) {
        val valid = groups.filter { it.courses.isNotEmpty() }
        lifecycleScope.launch {
            var imported = 0
            val onSuccess: (String, Int) -> Unit = { profileName, count ->
                imported += count
                UiFeedback.showMessage(listContainer, getString(R.string.import_saved, profileName, count), palette)
            }
            valid.forEachIndexed { index, group ->
                val parsed = group.parsed
                val campusMap = withContext(Dispatchers.IO) { persistTimeTables(parsed) }
                val schedules = group.courses.map { course ->
                    course.toCourseSchedule { alias ->
                        if (alias.isNullOrBlank()) null else campusMap[alias]
                    }
                }
                val mode = if (overwrite && index == 0) {
                    cn.xiao.xyaax.data.ImportedScheduleStorage.ImportMode.OVERWRITE_ACTIVE
                } else {
                    cn.xiao.xyaax.data.ImportedScheduleStorage.ImportMode.CREATE_NEW
                }
                val name = parsed.semester?.takeIf { it.isNotBlank() }?.let { "$it 导入" }
                ScheduleRepository.importCourses(
                    context = this@ImportWizardActivity,
                    courses = schedules,
                    mode = mode,
                    newProfileName = if (overwrite && index == 0) null else (name ?: group.name.removeSuffix(".txt").removeSuffix(".md").removeSuffix(".csv")),
                    semesterStartDate = parsed.semesterStartDate?.let { raw ->
                        runCatching { java.time.LocalDate.parse(raw) }.getOrNull()
                    }
                ).onSuccess { profileName ->
                    onSuccess(profileName, schedules.size)
                }.onFailure { error ->
                    UiFeedback.showMessage(listContainer, getString(R.string.import_failed, error.message ?: ""), palette)
                }
            }
            if (imported > 0) {
                setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_IMPORTED, imported))
                finish()
            }
        }
    }

    /** 保存解析出的节次时间表，并返回「校区简称 → 时间表 ID」映射。 */
    private fun persistTimeTables(parsed: CourseTableParseResult): Map<String, String> {
        val filesDir = filesDir
        if (parsed.timeTables.isEmpty()) return TimeProfileStore.loadCampusMap(filesDir)

        val existing = TimeProfileStore.load(filesDir).toMutableList()
        parsed.timeTables.forEach { profile ->
            existing.removeAll { it.id == profile.id || it.name == profile.name }
            existing.add(profile)
        }
        TimeProfileStore.saveProfiles(filesDir, existing)

        val campusMap = TimeProfileStore.loadCampusMap(filesDir).toMutableMap()
        parsed.campusAliases.forEach { alias ->
            val matched = parsed.timeTables.firstOrNull { it.name.contains(alias) } ?: return@forEach
            campusMap[alias] = matched.id
        }
        TimeProfileStore.saveCampusMap(filesDir, campusMap)
        return campusMap
    }

    companion object {
        const val EXTRA_IMPORTED = "extra_imported_count"
        const val EXTRA_PASTE_TEXT = "extra_paste_text"
    }
}
