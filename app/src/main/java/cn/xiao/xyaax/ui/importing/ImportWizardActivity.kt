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
    private lateinit var palette: ThemePalette
    private lateinit var summary: TextView
    private lateinit var listContainer: LinearLayout
    private lateinit var saveButton: Button

    private var result: CourseTableParseResult? = null
    private val courses = mutableListOf<ParsedCourse>()
    private var lastUri: Uri? = null

    private val pickFileLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { parseFile(it) } }

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
        courses.clear()
        courses.addAll(parsed.courses)
        summary.text = buildString {
            append(sourceNote)
            parsed.semester?.let { append(" · ").append(it) }
            parsed.totalWeeks?.let { append(" · 共").append(it).append("周") }
            append(" · 识别 ").append(parsed.courses.size).append(" 门课")
            if (parsed.warnings.isNotEmpty()) append("\n").append(parsed.warnings.take(3).joinToString("；"))
        }
        renderCourses()
        saveButton.isEnabled = courses.isNotEmpty()
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
        pickFileLauncher.launch(
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
                    document to CourseTableImporter.parse(document.blocks, document.rawText)
                }
            }

            outcome.onSuccess { (document, parsed) ->
                val note = buildString {
                    append(document.note)
                    if (parsed.timeTables.isNotEmpty()) {
                        append(" · 节次时间表 ").append(parsed.timeTables.size).append(" 套")
                    }
                }
                applyParseResult(note, parsed)
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

    private fun renderCourses() {
        listContainer.removeAllViews()
        if (courses.isEmpty()) {
            listContainer.addView(TextView(this).apply {
                text = getString(R.string.import_empty)
                setTextColor(palette.textSecondary)
                textSize = 13f
            })
            return
        }
        courses.forEachIndexed { index, course -> listContainer.addView(buildRow(index, course)) }
    }

    private fun buildRow(index: Int, course: ParsedCourse): View {
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
            setOnClickListener { editCourse(index) }
            setOnLongClickListener {
                confirmDelete(index)
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

    private fun editCourse(index: Int) {
        val course = courses.getOrNull(index) ?: return
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
                courses[index] = course.copy(
                    courseName = nameInput.text.toString().trim().ifBlank { course.courseName },
                    teacher = teacherInput.text.toString().trim(),
                    meetings = updatedMeetings
                )
                renderCourses()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun confirmDelete(index: Int) {
        AlertDialog.Builder(this)
            .setTitle(R.string.import_delete_course)
            .setMessage(getString(R.string.import_delete_message, courses.getOrNull(index)?.courseName.orEmpty()))
            .setPositiveButton(R.string.manage_delete) { _, _ ->
                courses.removeAt(index)
                renderCourses()
                saveButton.isEnabled = courses.isNotEmpty()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun confirmSave() {
        if (courses.isEmpty()) return
        val options = arrayOf(getString(R.string.import_mode_overwrite), getString(R.string.import_mode_new))
        AlertDialog.Builder(this)
            .setTitle(R.string.import_save_to_timetable)
            .setItems(options) { _, which ->
                persist(overwrite = which == 0)
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun persist(overwrite: Boolean) {
        val parsed = result ?: return
        lifecycleScope.launch {
            val campusMap = withContext(Dispatchers.IO) { persistTimeTables(parsed) }
            val schedules = courses.map { course ->
                course.toCourseSchedule { alias ->
                    if (alias.isNullOrBlank()) null else campusMap[alias]
                }
            }
            val mode = if (overwrite) {
                cn.xiao.xyaax.data.ImportedScheduleStorage.ImportMode.OVERWRITE_ACTIVE
            } else {
                cn.xiao.xyaax.data.ImportedScheduleStorage.ImportMode.CREATE_NEW
            }
            val name = parsed.semester?.takeIf { it.isNotBlank() }?.let { "$it 导入" }
            ScheduleRepository.importCourses(
                context = this@ImportWizardActivity,
                courses = schedules,
                mode = mode,
                newProfileName = if (overwrite) null else name,
                semesterStartDate = parsed.semesterStartDate?.let { raw ->
                    runCatching { java.time.LocalDate.parse(raw) }.getOrNull()
                }
            ).onSuccess { profileName ->
                UiFeedback.showMessage(listContainer, getString(R.string.import_saved, profileName, schedules.size), palette)
                setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_IMPORTED, schedules.size))
                finish()
            }.onFailure { error ->
                UiFeedback.showMessage(listContainer, getString(R.string.import_failed, error.message ?: ""), palette)
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
