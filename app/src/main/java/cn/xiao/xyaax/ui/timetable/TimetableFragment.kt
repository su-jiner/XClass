package cn.xiao.xyaax.ui.timetable

import android.content.Intent
import android.graphics.Color
import androidx.activity.result.contract.ActivityResultContracts
import android.content.res.ColorStateList
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.graphics.ColorUtils
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.viewpager2.widget.ViewPager2
import cn.xiao.xyaax.R
import cn.xiao.xyaax.data.AppPreferences
import cn.xiao.xyaax.data.ImportedScheduleStorage
import cn.xiao.xyaax.data.ScheduleRepository
import cn.xiao.xyaax.data.TimeProfileStore
import cn.xiao.xyaax.domain.SectionTimes
import cn.xiao.xyaax.importing.ShareTextCodec
import cn.xiao.xyaax.model.WeekParity
import cn.xiao.xyaax.model.Weekday
import cn.xiao.xyaax.ui.theme.ThemePaletteProvider
import cn.xiao.xyaax.ui.theme.UiFeedback
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

class TimetableFragment : Fragment() {
    private val weekdayLabels = mapOf(
        Weekday.MONDAY to "一",
        Weekday.TUESDAY to "二",
        Weekday.WEDNESDAY to "三",
        Weekday.THURSDAY to "四",
        Weekday.FRIDAY to "五",
        Weekday.SATURDAY to "六",
        Weekday.SUNDAY to "日"
    )

    private lateinit var weekText: TextView
    private lateinit var dateText: TextView
    private lateinit var weekPager: ViewPager2
    private lateinit var addCourseButton: ImageButton
    private var currentWeekIndex: Int = 1
    private var pageChangeCallback: ViewPager2.OnPageChangeCallback? = null
    private var pageChangeRegistered: Boolean = false

    private val importLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            UiFeedback.showMessage(view, "课表导入完成", ThemePaletteProvider.fromContext(requireContext()))
        }
        ScheduleRepository.refresh(requireContext())
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_timetable, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        weekText = view.findViewById(R.id.currentWeekText)
        dateText = view.findViewById(R.id.currentDateText)
        weekPager = view.findViewById(R.id.weekPager)
        addCourseButton = view.findViewById(R.id.addCourseButton)

        val palette = ThemePaletteProvider.fromContext(requireContext())
        addCourseButton.imageTintList = ColorStateList.valueOf(palette.iconTint)

        observeTimetable()

        addCourseButton.setOnClickListener {
            showAddChooser()
        }
    }

    override fun onResume() {
        super.onResume()
        ScheduleRepository.refresh(requireContext())
    }

    override fun onDestroyView() {
        pageChangeCallback?.let { callback ->
            if (pageChangeRegistered) {
                runCatching { weekPager.unregisterOnPageChangeCallback(callback) }
                pageChangeRegistered = false
            }
        }
        pageChangeCallback = null
        super.onDestroyView()
    }

    private fun observeTimetable() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                ScheduleRepository.timetable.collect { data ->
                    if (data != null) {
                        renderTimetable(data)
                    }
                }
            }
        }
    }

    private var currentUiData: ScheduleRepository.TimetableUiData? = null

    private fun renderTimetable(data: ScheduleRepository.TimetableUiData) {
        if (!isAdded || view == null) {
            return
        }
        currentUiData = data
        val ctx = requireContext()

        // 网格始终渲染（时间列时间跟随课程：无课节次时间为空，空课表只显示空时间列而非整页空白）
        weekPager.visibility = View.VISIBLE

        pageChangeCallback?.let { callback ->
            if (pageChangeRegistered) {
                runCatching { weekPager.unregisterOnPageChangeCallback(callback) }
                pageChangeRegistered = false
            }
        }

        val fontScale = AppPreferences.getTimetableFontScale(ctx)
        val palette = ThemePaletteProvider.fromContext(ctx)
        val hasCustomBackground = !AppPreferences.getCustomBackgroundUri(ctx).isNullOrBlank()

        // 左侧时间列与详情时间跟随「当前显示时间表」（多校区）
        val timeProfiles = cn.xiao.xyaax.data.TimeProfileStore.load(ctx.filesDir)
        val displayProfile = cn.xiao.xyaax.domain.TimeProfileResolver.resolve(
            timeProfiles,
            cn.xiao.xyaax.data.TimeProfileStore.loadDefaultProfileId(ctx.filesDir)
        )
        val displayPeriodRanges = cn.xiao.xyaax.domain.TimeProfileResolver.sectionRanges(displayProfile)

        currentWeekIndex = data.currentWeek
        val today = LocalDate.now()
        val currentSection = WeekTimetableRenderer.resolveCurrentSection(displayPeriodRanges)
        val adapter = WeekPagerAdapter(
            courses = data.courses,
            totalWeeks = data.totalWeeks,
            periodRanges = displayPeriodRanges,
            weekdayLabels = weekdayLabels,
            today = today,
            currentSection = currentSection,
            semesterStart = data.semesterStart,
            baseWeek = currentWeekIndex,
            showNonCurrent = false,
            fontScale = fontScale,
            palette = palette,
            hasCustomBackground = hasCustomBackground,
            notes = data.activeNotes,
            onCourseClick = { primary, allItems ->
                if (isAdded && !parentFragmentManager.isStateSaved) {
                    runCatching {
                        CourseDetailBottomSheet.show(
                            context = requireContext(),
                            primary = primary,
                            allCourses = allItems,
                            periodRanges = displayPeriodRanges,
                            onCoverPinned = {
                                currentUiData?.let { renderTimetable(it) }
                            },
                            onDeleteCourse = { selected ->
                                viewLifecycleOwner.lifecycleScope.launch {
                                    val result = ScheduleRepository.deleteCourse(
                                        requireContext(), selected.courseIndex, selected.course
                                    )
                                    if (!isAdded) return@launch
                                    val message = when {
                                        result.getOrNull() == true -> getString(R.string.course_detail_deleted)
                                        result.getOrNull() == false -> getString(R.string.course_detail_delete_failed)
                                        else -> result.exceptionOrNull()?.message ?: getString(R.string.course_detail_delete_failed)
                                    }
                                    UiFeedback.showMessage(view, message, ThemePaletteProvider.fromContext(requireContext()))
                                }
                            },
                            onEditCourse = { selected ->
                                cn.xiao.xyaax.ui.settings.EditCourseActivity.start(
                                    requireContext(), null, selected.courseIndex, selected.meetingIndex
                                )
                            }
                        )
                    }
                }
            }
        )
        // 保留用户当前浏览的周，避免数据刷新时被强制跳回本周
        val previousPosition = weekPager.adapter?.let { weekPager.currentItem } ?: -1
        weekPager.adapter = adapter
        val targetPosition = if (previousPosition >= 0) {
            previousPosition.coerceAtMost(data.totalWeeks - 1)
        } else {
            currentWeekIndex - 1
        }
        weekPager.setCurrentItem(targetPosition, false)
        updateHeader(targetPosition + 1)

        val callback = object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updateHeader(position + 1)
            }
        }
        pageChangeCallback = callback
        weekPager.registerOnPageChangeCallback(callback)
        pageChangeRegistered = true
    }

    private fun updateHeader(week: Int) {
        val today = LocalDate.now()
        val weekDay = weekdayLabels[weekdayFromJava(today.dayOfWeek.value)] ?: "一"
        weekText.text = if (week == currentWeekIndex) {
            "第${week}周 周${weekDay}"
        } else {
            "第${week}周（非本周）"
        }
        dateText.text = today.format(DateTimeFormatter.ofPattern("yyyy/M/d"))
    }

    /** 「+」按钮：选择手动添加 / 导入文件 / 粘贴文本。 */
    private fun showAddChooser() {
        val ctx = requireContext()
        val palette = ThemePaletteProvider.fromContext(ctx)
        val panel = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(34, 30, 34, 20)
        }
        val manual = android.widget.Button(ctx).apply { text = getString(R.string.add_chooser_manual) }
        val file = android.widget.Button(ctx).apply { text = getString(R.string.add_chooser_file) }
        val paste = android.widget.Button(ctx).apply { text = getString(R.string.add_chooser_paste) }
        val cancel = android.widget.Button(ctx).apply { text = getString(R.string.action_cancel) }
        UiFeedback.stylePrimaryButton(manual, palette)
        UiFeedback.styleSecondaryButton(file, palette)
        UiFeedback.styleSecondaryButton(paste, palette)
        UiFeedback.styleSecondaryButton(cancel, palette)
        listOf(file, paste, cancel).forEach { button ->
            panel.addView(button, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 10 })
        }
        panel.addView(manual, 0, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        val dialog = AlertDialog.Builder(ctx)
            .setTitle(R.string.add_chooser_title)
            .setView(panel)
            .create()
        dialog.show()
        UiFeedback.styleDialogSurface(dialog, palette)
        manual.setOnClickListener {
            dialog.dismiss()
            showManualAddDialog()
        }
        file.setOnClickListener {
            dialog.dismiss()
            importLauncher.launch(
                android.content.Intent(requireContext(), cn.xiao.xyaax.ui.importing.ImportWizardActivity::class.java)
            )
        }
        paste.setOnClickListener {
            dialog.dismiss()
            showPasteDialog()
        }
        cancel.setOnClickListener { dialog.dismiss() }
    }

    /** 粘贴文本导入：直接复用导入向导的解析与校对流程。 */
    private fun showPasteDialog() {
        val ctx = requireContext()
        val input = EditText(ctx).apply {
            gravity = android.view.Gravity.TOP
            minLines = 6
            hint = getString(R.string.add_chooser_paste_hint)
            setPadding(24, 20, 24, 20)
        }
        val dialog = AlertDialog.Builder(ctx)
            .setTitle(R.string.add_chooser_paste)
            .setView(input)
            .setPositiveButton(R.string.import_wizard_title) { _, _ ->
                val text = input.text?.toString().orEmpty()
                if (text.isNotBlank()) {
                    importLauncher.launch(
                        android.content.Intent(requireContext(), cn.xiao.xyaax.ui.importing.ImportWizardActivity::class.java)
                            .putExtra(cn.xiao.xyaax.ui.importing.ImportWizardActivity.EXTRA_PASTE_TEXT, text)
                    )
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .setNeutralButton(R.string.add_chooser_export) { _, _ -> exportShareText() }
            .create()
        dialog.show()
        UiFeedback.styleDialogSurface(dialog, ThemePaletteProvider.fromContext(ctx))
    }

    /** 导出当前课表为 XClass 分享文本：通过系统分享发给朋友，对方粘贴/打开即可导入。 */
    private fun exportShareText() {
        val ctx = requireContext()
        lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) {
                val filesDir = ctx.filesDir
                val courses = ImportedScheduleStorage.loadActiveCourses(filesDir)
                if (courses.isEmpty()) return@withContext ""
                val active = ImportedScheduleStorage.listProfiles(filesDir).firstOrNull { it.isActive }
                val semesterStart = ImportedScheduleStorage.getActiveSemesterStartDate(filesDir)
                val campusMap = TimeProfileStore.loadCampusMap(filesDir)
                val idToAlias = campusMap.entries.associate { (alias, id) -> id to alias }
                val semester = courses.firstOrNull { it.semester.isNotBlank() }?.semester
                val totalWeeks = courses.flatMap { it.meetings }.flatMap { it.weekRules }.maxOfOrNull { it.endWeek }
                ShareTextCodec.encode(
                    courses = courses,
                    profileName = active?.name,
                    semester = semester,
                    semesterStart = semesterStart,
                    totalWeeks = totalWeeks,
                    campusIdToAlias = idToAlias
                )
            }
            if (text.isBlank()) {
                UiFeedback.showMessage(requireView(), getString(R.string.import_empty), ThemePaletteProvider.fromContext(ctx))
                return@launch
            }
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
            }
            startActivity(Intent.createChooser(send, getString(R.string.add_chooser_export_title)))
        }
    }

    private fun showManualAddDialog() {
        val ctx = requireContext()
        val formView = layoutInflater.inflate(R.layout.dialog_add_course, null)
        val nameInput = formView.findViewById<EditText>(R.id.inputCourseName)
        val teacherInput = formView.findViewById<EditText>(R.id.inputTeacher)
        val locationInput = formView.findViewById<EditText>(R.id.inputLocation)
        val weekdaySpinner = formView.findViewById<Spinner>(R.id.inputWeekday)
        val startSectionInput = formView.findViewById<EditText>(R.id.inputStartSection)
        val endSectionInput = formView.findViewById<EditText>(R.id.inputEndSection)
        val startWeekInput = formView.findViewById<EditText>(R.id.inputStartWeek)
        val endWeekInput = formView.findViewById<EditText>(R.id.inputEndWeek)
        val parityGroup = formView.findViewById<RadioGroup>(R.id.inputParityGroup)

        // 面板与输入框按当前主题着色
        val dialogPalette = ThemePaletteProvider.fromContext(ctx)
        formView.findViewById<LinearLayout>(R.id.addCoursePanel).background =
            GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 20f
                setColor(dialogPalette.panelAltBackground)
                setStroke(2, ColorUtils.blendARGB(dialogPalette.panelAltBackground, dialogPalette.iconTint, 0.22f))
            }
        // 每个输入框必须持有独立的 GradientDrawable 实例：共享实例会在首帧后被
        // 最后一个不同尺寸的视图改写 bounds，聚焦重绘时边框按过期尺寸渲染（框变短）
        listOf(
            nameInput, teacherInput, locationInput,
            startSectionInput, endSectionInput, startWeekInput, endWeekInput, weekdaySpinner
        ).forEach { input ->
            input.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 10f
                setColor(dialogPalette.panelBackground)
                setStroke(2, ColorUtils.blendARGB(dialogPalette.panelBackground, dialogPalette.iconTint, 0.18f))
            }
        }

        val weekdayItems = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
        weekdaySpinner.adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, weekdayItems)

        val guessWeek = currentWeekIndex.coerceAtLeast(1)
        startSectionInput.setText("1")
        endSectionInput.setText("2")
        startWeekInput.setText(String.format(Locale.getDefault(), "%d", guessWeek))
        endWeekInput.setText(String.format(Locale.getDefault(), "%d", guessWeek))

        val cancelButton = formView.findViewById<android.widget.Button>(R.id.cancelManualAddButton)
        val saveButton = formView.findViewById<android.widget.Button>(R.id.saveManualAddButton)

        val dialog = AlertDialog.Builder(ctx)
            .setView(formView)
            .create()

        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            UiFeedback.styleSecondaryButton(cancelButton, dialogPalette)
            UiFeedback.stylePrimaryButton(saveButton, dialogPalette)
        }

        cancelButton.setOnClickListener {
            dialog.dismiss()
        }
        saveButton.setOnClickListener {
            val name = nameInput.text?.toString()?.trim().orEmpty()
            if (name.isBlank()) {
                nameInput.error = "课程名不能为空"
                UiFeedback.showMessage(view, "课程名不能为空", ThemePaletteProvider.fromContext(ctx))
                return@setOnClickListener
            }
            if (name.length > MAX_MANUAL_COURSE_NAME_LENGTH) {
                nameInput.error = "课程名过长"
                UiFeedback.showMessage(view, "课程名最多 ${MAX_MANUAL_COURSE_NAME_LENGTH} 个字符", ThemePaletteProvider.fromContext(ctx))
                return@setOnClickListener
            }

            val teacher = teacherInput.text?.toString()?.trim().orEmpty()
            if (teacher.length > MAX_MANUAL_TEXT_LENGTH) {
                teacherInput.error = "教师名称过长"
                UiFeedback.showMessage(view, "教师名称最多 ${MAX_MANUAL_TEXT_LENGTH} 个字符", ThemePaletteProvider.fromContext(ctx))
                return@setOnClickListener
            }
            val location = locationInput.text?.toString()?.trim().orEmpty()
            if (location.length > MAX_MANUAL_TEXT_LENGTH) {
                locationInput.error = "地点过长"
                UiFeedback.showMessage(view, "地点最多 ${MAX_MANUAL_TEXT_LENGTH} 个字符", ThemePaletteProvider.fromContext(ctx))
                return@setOnClickListener
            }

            val startSection = parseBoundedInt(startSectionInput, 1, 12, "开始节")
                ?: return@setOnClickListener
            val endSection = parseBoundedInt(endSectionInput, 1, 12, "结束节")
                ?: return@setOnClickListener
            if (endSection < startSection) {
                endSectionInput.error = "结束节不能小于开始节"
                UiFeedback.showMessage(view, "结束节不能小于开始节", ThemePaletteProvider.fromContext(ctx))
                return@setOnClickListener
            }

            val startWeek = parseBoundedInt(startWeekInput, 1, 30, "开始周")
                ?: return@setOnClickListener
            val endWeek = parseBoundedInt(endWeekInput, 1, 30, "结束周")
                ?: return@setOnClickListener
            if (endWeek < startWeek) {
                endWeekInput.error = "结束周不能小于开始周"
                UiFeedback.showMessage(view, "结束周不能小于开始周", ThemePaletteProvider.fromContext(ctx))
                return@setOnClickListener
            }
            val weekday = when (weekdaySpinner.selectedItemPosition) {
                0 -> Weekday.MONDAY
                1 -> Weekday.TUESDAY
                2 -> Weekday.WEDNESDAY
                3 -> Weekday.THURSDAY
                4 -> Weekday.FRIDAY
                5 -> Weekday.SATURDAY
                else -> Weekday.SUNDAY
            }
            val parity = when (parityGroup.checkedRadioButtonId) {
                R.id.parityOdd -> WeekParity.ODD
                R.id.parityEven -> WeekParity.EVEN
                else -> WeekParity.ALL
            }

            // 防抖：保存开始后立即禁用按钮，避免双击重复添加
            saveButton.isEnabled = false
            viewLifecycleOwner.lifecycleScope.launch {
                val result = ScheduleRepository.addManualCourse(
                    ctx,
                    ImportedScheduleStorage.ManualCourseInput(
                        courseName = name,
                        teacher = teacher,
                        location = location,
                        weekday = weekday,
                        startSection = startSection,
                        endSection = endSection,
                        startWeek = startWeek,
                        endWeek = endWeek,
                        parity = parity
                    )
                )
                result.onSuccess {
                    UiFeedback.showMessage(view, "已添加到当前课表", ThemePaletteProvider.fromContext(ctx))
                    dialog.dismiss()
                }.onFailure { error ->
                    saveButton.isEnabled = true
                    UiFeedback.showMessage(view, error.message ?: "添加失败", ThemePaletteProvider.fromContext(ctx))
                }
            }
        }
        dialog.show()
    }

    private fun parseBoundedInt(input: EditText, min: Int, max: Int, label: String): Int? {
        val raw = input.text?.toString()?.trim().orEmpty()
        val value = raw.toIntOrNull()
        if (value == null) {
            input.error = "${label}必须是数字"
            UiFeedback.showMessage(view, "${label}必须是数字", ThemePaletteProvider.fromContext(requireContext()))
            return null
        }
        if (value !in min..max) {
            input.error = "${label}范围是 ${min}-${max}"
            UiFeedback.showMessage(view, "${label}范围是 ${min}-${max}", ThemePaletteProvider.fromContext(requireContext()))
            return null
        }
        input.error = null
        return value
    }

    private fun weekdayFromJava(dayValue: Int): Weekday {
        return when (dayValue) {
            1 -> Weekday.MONDAY
            2 -> Weekday.TUESDAY
            3 -> Weekday.WEDNESDAY
            4 -> Weekday.THURSDAY
            5 -> Weekday.FRIDAY
            6 -> Weekday.SATURDAY
            else -> Weekday.SUNDAY
        }
    }

    companion object {
        private const val MAX_MANUAL_COURSE_NAME_LENGTH = 40
        private const val MAX_MANUAL_TEXT_LENGTH = 40
    }
}
