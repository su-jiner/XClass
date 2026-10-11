package cn.xiao.xyaax.ui.settings

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.lifecycleScope
import cn.xiao.xyaax.R
import cn.xiao.xyaax.data.AppPreferences
import cn.xiao.xyaax.data.ImportedScheduleStorage
import cn.xiao.xyaax.data.ScheduleRepository
import cn.xiao.xyaax.domain.SectionTimes
import cn.xiao.xyaax.model.CourseSchedule
import cn.xiao.xyaax.model.MeetingTime
import cn.xiao.xyaax.model.WeekParity
import cn.xiao.xyaax.model.WeekRule
import cn.xiao.xyaax.model.Weekday
import cn.xiao.xyaax.ui.theme.ThemePaletteProvider
import cn.xiao.xyaax.ui.theme.UiFeedback
import cn.xiao.xyaax.ui.theme.UiTransitions
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * 编辑课程：以手动添加表单编辑课程的「第一个上课时段 + 课程名/教师」，
 * 保存即修改课程定义（含周规则），所有周同步生效。
 */
class EditCourseActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_PROFILE_ID = "extra_profile_id"
        const val EXTRA_COURSE_INDEX = "extra_course_index"
        const val EXTRA_MEETING_INDEX = "extra_meeting_index"
        const val MAX_COURSE_NAME_LENGTH = 24
        const val MAX_TEXT_LENGTH = 40

        fun start(context: Context, profileId: String?, courseIndex: Int, meetingIndex: Int) {
            context.startActivity(
                Intent(context, EditCourseActivity::class.java)
                    .putExtra(EXTRA_PROFILE_ID, profileId.orEmpty())
                    .putExtra(EXTRA_COURSE_INDEX, courseIndex)
                    .putExtra(EXTRA_MEETING_INDEX, meetingIndex)
            )
        }
    }

    private var courseIndex: Int = 0
    private var meetingIndex: Int = 0
    private var profileId: String? = null
    private var originalCourse: CourseSchedule? = null
    private var originalMeeting: MeetingTime? = null

    private lateinit var nameInput: EditText
    private lateinit var teacherInput: EditText
    private lateinit var locationInput: EditText
    private lateinit var weekdaySpinner: Spinner
    private lateinit var startSectionInput: EditText
    private lateinit var endSectionInput: EditText
    private lateinit var startWeekInput: EditText
    private lateinit var endWeekInput: EditText
    private lateinit var parityGroup: RadioGroup
    private lateinit var timePreview: TextView
    private lateinit var customTimeToggle: CheckBox
    private lateinit var customTimeRow: LinearLayout
    private lateinit var customStartInput: EditText
    private lateinit var customEndInput: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemePaletteProvider.applyNightMode(this)
        setTheme(ThemePaletteProvider.themeStyleFor(AppPreferences.getThemeColor(this)))
        super.onCreate(savedInstanceState)
        setContentView(R.layout.dialog_add_course)
        UiTransitions.applyEnter(this)

        title = getString(R.string.edit_course_title)
        courseIndex = intent.getIntExtra(EXTRA_COURSE_INDEX, 0)
        meetingIndex = intent.getIntExtra(EXTRA_MEETING_INDEX, 0)
        profileId = intent.getStringExtra(EXTRA_PROFILE_ID)?.takeIf { it.isNotBlank() }

        loadCourse()
        bindViews()
        themeForm()
        bindSave()
    }

    private fun loadCourse() {
        val courses = if (profileId != null) {
            ImportedScheduleStorage.loadCoursesForProfile(filesDir, profileId!!)
        } else {
            ImportedScheduleStorage.loadActiveCourses(filesDir)
        }
        val course = courses?.getOrNull(courseIndex) ?: run {
            finish()
            return
        }
        originalCourse = course
        originalMeeting = course.meetings.getOrNull(meetingIndex) ?: course.meetings.firstOrNull()
        if (originalMeeting == null) {
            finish()
            return
        }
    }

    @SuppressLint("SetTextI18n")
    private fun bindViews() {
        nameInput = findViewById(R.id.inputCourseName)
        teacherInput = findViewById(R.id.inputTeacher)
        locationInput = findViewById(R.id.inputLocation)
        weekdaySpinner = findViewById(R.id.inputWeekday)
        startSectionInput = findViewById(R.id.inputStartSection)
        endSectionInput = findViewById(R.id.inputEndSection)
        startWeekInput = findViewById(R.id.inputStartWeek)
        endWeekInput = findViewById(R.id.inputEndWeek)
        parityGroup = findViewById(R.id.inputParityGroup)
        timePreview = findViewById(R.id.timePreview)
        customTimeToggle = findViewById(R.id.customTimeToggle)
        customTimeRow = findViewById(R.id.customTimeRow)
        customStartInput = findViewById(R.id.inputCustomStart)
        customEndInput = findViewById(R.id.inputCustomEnd)

        val course = originalCourse!!
        val meeting = originalMeeting!!

        nameInput.setText(course.courseName)
        teacherInput.setText(course.teacher)
        locationInput.setText(meeting.location)

        val weekdayItems = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
        weekdaySpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, weekdayItems)
        weekdaySpinner.setSelection(meeting.weekday.ordinal)

        startSectionInput.setText(meeting.startSection.toString())
        endSectionInput.setText(meeting.endSection.toString())

        // 自定义上课时间回填
        val hasCustom = !meeting.customStartTime.isNullOrBlank()
        customTimeToggle.isChecked = hasCustom
        customTimeRow.visibility = if (hasCustom) View.VISIBLE else View.GONE
        customStartInput.setText(meeting.customStartTime ?: "")
        customEndInput.setText(meeting.customEndTime ?: "")

        refreshTimePreview()

        // 节次变化实时刷新时间预览
        val previewWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = refreshTimePreview()
        }
        startSectionInput.addTextChangedListener(previewWatcher)
        endSectionInput.addTextChangedListener(previewWatcher)

        // 自定义时间开关：展开/收起 HH:mm 输入
        customTimeToggle.setOnCheckedChangeListener { _, checked ->
            customTimeRow.visibility = if (checked) View.VISIBLE else View.GONE
        }

        val rule = meeting.weekRules.firstOrNull()
        val startWeek = rule?.startWeek ?: 1
        val endWeek = rule?.endWeek ?: 16
        startWeekInput.setText(String.format(Locale.getDefault(), "%d", startWeek))
        endWeekInput.setText(String.format(Locale.getDefault(), "%d", endWeek))
        when (rule?.parity ?: WeekParity.ALL) {
            WeekParity.ALL -> parityGroup.check(R.id.parityAll)
            WeekParity.ODD -> parityGroup.check(R.id.parityOdd)
            WeekParity.EVEN -> parityGroup.check(R.id.parityEven)
        }

        findViewById<TextView>(R.id.cancelManualAddButton).setOnClickListener { finish() }
    }

    private fun themeForm() {
        val palette = ThemePaletteProvider.fromContext(this)
        findViewById<View>(R.id.addCoursePanel).background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 20f
            setColor(palette.panelAltBackground)
            setStroke(2, ColorUtils.blendARGB(palette.panelAltBackground, palette.iconTint, 0.22f))
        }
        listOf(
            nameInput, teacherInput, locationInput,
            startSectionInput, endSectionInput, startWeekInput, endWeekInput, weekdaySpinner,
            customStartInput, customEndInput
        ).forEach { input ->
            input.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 10f
                setColor(palette.panelBackground)
                setStroke(2, ColorUtils.blendARGB(palette.panelBackground, palette.iconTint, 0.18f))
            }
        }
        val saveButton = findViewById<android.widget.Button>(R.id.saveManualAddButton)
        val cancelButton = findViewById<android.widget.Button>(R.id.cancelManualAddButton)
        UiFeedback.stylePrimaryButton(saveButton, palette)
        UiFeedback.styleSecondaryButton(cancelButton, palette)
    }

    @SuppressLint("SetTextI18n")
    private fun bindSave() {
        findViewById<android.widget.Button>(R.id.saveManualAddButton).setOnClickListener {
            val name = nameInput.text?.toString()?.trim().orEmpty()
            if (name.isBlank()) {
                nameInput.error = getString(R.string.manual_name_required)
                UiFeedback.showMessage(findViewById(android.R.id.content), getString(R.string.manual_name_required), ThemePaletteProvider.fromContext(this))
                return@setOnClickListener
            }
            if (name.length > MAX_COURSE_NAME_LENGTH) {
                nameInput.error = getString(R.string.edit_course_name_too_long)
                UiFeedback.showMessage(findViewById(android.R.id.content), getString(R.string.edit_course_name_too_long), ThemePaletteProvider.fromContext(this))
                return@setOnClickListener
            }
            val teacher = teacherInput.text?.toString()?.trim().orEmpty()
            val location = locationInput.text?.toString()?.trim().orEmpty()
            val startSection = parseBounded(startSectionInput, 1, 12, getString(R.string.manual_start_section)) ?: return@setOnClickListener
            val endSection = parseBounded(endSectionInput, 1, 12, getString(R.string.manual_end_section)) ?: return@setOnClickListener
            if (endSection < startSection) {
                endSectionInput.error = getString(R.string.manual_end_before_start)
                UiFeedback.showMessage(findViewById(android.R.id.content), getString(R.string.manual_end_before_start), ThemePaletteProvider.fromContext(this))
                return@setOnClickListener
            }
            val startWeek = parseBounded(startWeekInput, 1, 30, getString(R.string.manual_start_week)) ?: return@setOnClickListener
            val endWeek = parseBounded(endWeekInput, 1, 30, getString(R.string.manual_end_week)) ?: return@setOnClickListener
            if (endWeek < startWeek) {
                endWeekInput.error = getString(R.string.manual_end_before_start_week)
                UiFeedback.showMessage(findViewById(android.R.id.content), getString(R.string.manual_end_before_start_week), ThemePaletteProvider.fromContext(this))
                return@setOnClickListener
            }
            val weekday = Weekday.entries.getOrElse(weekdaySpinner.selectedItemPosition) { Weekday.MONDAY }
            val parity = when (parityGroup.checkedRadioButtonId) {
                R.id.parityOdd -> WeekParity.ODD
                R.id.parityEven -> WeekParity.EVEN
                else -> WeekParity.ALL
            }

            // 自定义上课时间：开启时校验 HH:mm 且开始早于结束
            var customStart: String? = null
            var customEnd: String? = null
            if (customTimeToggle.isChecked) {
                customStart = customStartInput.text?.toString()?.trim().orEmpty()
                customEnd = customEndInput.text?.toString()?.trim().orEmpty()
                if (!isValidTime(customStart) || !isValidTime(customEnd) || customStart >= customEnd) {
                    customStartInput.error = getString(R.string.manual_custom_time_invalid)
                    UiFeedback.showMessage(
                        findViewById(android.R.id.content),
                        getString(R.string.manual_custom_time_invalid),
                        ThemePaletteProvider.fromContext(this)
                    )
                    return@setOnClickListener
                }
            }

            val course = originalCourse!!
            val meeting = originalMeeting!!
            val newMeetings = course.meetings.toMutableList()
            val rule = meeting.weekRules.firstOrNull()
            val newRule = WeekRule(
                startWeek = startWeek,
                endWeek = endWeek,
                parity = parity
            )
            if (newMeetings.size > meetingIndex) {
                newMeetings[meetingIndex] = meeting.copy(
                    weekday = weekday,
                    startSection = startSection,
                    endSection = endSection,
                    location = location,
                    customStartTime = customStart,
                    customEndTime = customEnd,
                    weekRules = if (rule == null || meeting.weekRules.size <= 1) listOf(newRule) else {
                        // 多周规则：仅更新被编辑时段的主规则（其余规则原样保留）
                        meeting.weekRules.mapIndexed { index, r -> if (index == 0) newRule else r }
                    }
                )
            }
            val newCourse = course.copy(
                courseName = name,
                teacher = teacher,
                rawWeekText = buildRawWeekText(startWeek, endWeek, parity),
                meetings = newMeetings
            )

            val saveButton = findViewById<android.widget.Button>(R.id.saveManualAddButton)
            saveButton.isEnabled = false
            lifecycleScope.launch {
                // 拆分保存：课程含多个上课时段且课程名/教师被修改时，只改被编辑时段，
                // 其余时段（其他天）保留原名原师，避免跨天绑定连带修改
                val nameChanged = name != course.courseName
                val teacherChanged = teacher != course.teacher
                val shouldSplit = course.meetings.size > 1 &&
                    meetingIndex in course.meetings.indices &&
                    (nameChanged || teacherChanged)
                val result = if (shouldSplit) {
                    val keptMeetings = course.meetings.toMutableList().apply { removeAt(meetingIndex) }
                    val keptCourse = course.copy(meetings = keptMeetings)
                    val extractedMeeting = newMeetings.getOrNull(meetingIndex) ?: meeting
                    val extractedCourse = CourseSchedule(
                        courseName = name,
                        teacher = teacher,
                        semester = course.semester,
                        credit = course.credit,
                        rawWeekText = buildRawWeekText(startWeek, endWeek, parity),
                        meetings = listOf(extractedMeeting)
                    )
                    // 原课程被编辑格子的封面钉选置空（避免仍指向旧课程名）
                    for (section in meeting.startSection..meeting.endSection) {
                        if (AppPreferences.getPinnedCourse(this@EditCourseActivity, meeting.weekday, section) == course.courseName) {
                            AppPreferences.setPinnedCourseForSlot(this@EditCourseActivity, meeting.weekday, section..section, null)
                        }
                    }
                    ScheduleRepository.splitUpdateCourse(
                        this@EditCourseActivity,
                        profileId,
                        courseIndex,
                        course,
                        keptCourse,
                        extractedCourse
                    )
                } else {
                    ScheduleRepository.updateCourse(
                        this@EditCourseActivity,
                        profileId,
                        courseIndex,
                        course,
                        newCourse
                    )
                }
                result.onSuccess {
                    UiFeedback.showMessage(findViewById(android.R.id.content), getString(R.string.edit_course_saved), ThemePaletteProvider.fromContext(this@EditCourseActivity))
                    finish()
                }.onFailure { error ->
                    saveButton.isEnabled = true
                    UiFeedback.showMessage(findViewById(android.R.id.content), error.message ?: getString(R.string.edit_course_save_failed), ThemePaletteProvider.fromContext(this@EditCourseActivity))
                }
            }
        }
    }

    private fun parseBounded(input: EditText, min: Int, max: Int, label: String): Int? {
        val value = input.text?.toString()?.trim()?.toIntOrNull()
        if (value == null) {
            input.error = label + getString(R.string.edit_must_be_number)
            UiFeedback.showMessage(findViewById(android.R.id.content), label + getString(R.string.edit_must_be_number), ThemePaletteProvider.fromContext(this))
            return null
        }
        if (value !in min..max) {
            input.error = label + String.format(Locale.getDefault(), getString(R.string.edit_range_hint), min, max)
            UiFeedback.showMessage(findViewById(android.R.id.content), label + String.format(Locale.getDefault(), getString(R.string.edit_range_hint), min, max), ThemePaletteProvider.fromContext(this))
            return null
        }
        return value
    }

    /** 节次 → 时间预览：第X-Y节 = 08:00-09:40（跟随当前输入实时刷新） */
    @SuppressLint("SetTextI18n")
    private fun refreshTimePreview() {
        val start = startSectionInput.text?.toString()?.trim()?.toIntOrNull()
        val end = endSectionInput.text?.toString()?.trim()?.toIntOrNull()
        if (start == null || end == null || end < start) {
            timePreview.text = ""
            return
        }
        val startTime = SectionTimes.DEFAULT_RANGES.getOrNull(start - 1)?.substringBefore('-') ?: "--:--"
        val endTime = SectionTimes.DEFAULT_RANGES.getOrNull(end - 1)?.substringAfter('-') ?: "--:--"
        timePreview.text = getString(R.string.manual_time_preview, "$startTime-$endTime")
    }

    private fun isValidTime(value: String): Boolean =
        Regex("^([01]\\d|2[0-3]):[0-5]\\d$").matches(value)

    private fun buildRawWeekText(startWeek: Int, endWeek: Int, parity: WeekParity): String = when (parity) {
        WeekParity.ALL -> "${startWeek}-${endWeek}周"
        WeekParity.ODD -> "单周 ${startWeek}-${endWeek}周"
        WeekParity.EVEN -> "双周 ${startWeek}-${endWeek}周"
    }

    override fun finish() {
        super.finish()
        UiTransitions.applyExit(this)
    }
}
