package cn.xiao.xyaax.ui.tools

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.lifecycleScope
import cn.xiao.xyaax.R
import cn.xiao.xyaax.data.AppPreferences
import cn.xiao.xyaax.data.ExamItem
import cn.xiao.xyaax.data.ExamStore
import cn.xiao.xyaax.ui.theme.ThemePalette
import cn.xiao.xyaax.ui.theme.ThemePaletteProvider
import cn.xiao.xyaax.ui.theme.UiFeedback
import cn.xiao.xyaax.ui.theme.applySystemBarPadding
import cn.xiao.xyaax.ui.theme.applySystemBarIcons
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.launch

/**
 * 考试查询界面：期末/期中考试日程、地点、座位号及倒计时，支持手动自定义添加（全程离线）。
 */
class ExamScheduleActivity : AppCompatActivity() {

    private lateinit var palette: ThemePalette
    private lateinit var countdownTitle: TextView
    private lateinit var countdownText: TextView
    private lateinit var countdownDetail: TextView
    private lateinit var listContainer: LinearLayout
    private lateinit var emptyHint: TextView
    private lateinit var addBtn: Button

    private val allExams = mutableListOf<ExamItem>()

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemePaletteProvider.applyNightMode(this)
        setTheme(ThemePaletteProvider.themeStyleFor(AppPreferences.getThemeColor(this)))
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_exam_schedule)
        findViewById<View>(R.id.examRoot).applySystemBarPadding()

        palette = ThemePaletteProvider.fromContext(this)
        window.applySystemBarIcons(palette.isDark)
        initViews()
        loadLocalData()
    }

    private fun initViews() {
        findViewById<View>(R.id.examRoot).setBackgroundColor(palette.pageBackground)
        findViewById<View>(R.id.examHeader).setBackgroundColor(palette.panelAltBackground)

        val backBtn = findViewById<ImageView>(R.id.examBackBtn)
        backBtn.imageTintList = ColorStateList.valueOf(palette.iconTint)
        backBtn.setOnClickListener { finish() }

        countdownTitle = findViewById(R.id.examCountdownTitle)
        countdownText = findViewById(R.id.examCountdownText)
        countdownDetail = findViewById(R.id.examCountdownDetail)
        listContainer = findViewById(R.id.examListContainer)
        emptyHint = findViewById(R.id.examEmptyHint)

        addBtn = findViewById(R.id.examAddBtn)

        UiFeedback.styleSecondaryButton(addBtn, palette)

        findViewById<View>(R.id.examCountdownCard).background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 16f
            setColor(palette.panelAltBackground)
            setStroke(1, ColorUtils.blendARGB(palette.panelAltBackground, palette.iconTint, 0.16f))
        }

        addBtn.setOnClickListener { showAddExamDialog() }
    }

    private fun loadLocalData() {
        allExams.clear()
        allExams.addAll(ExamStore.load(filesDir))
        renderExams()
    }

    private fun renderExams() {
        allExams.sortBy { if (it.timestamp > 0) it.timestamp else Long.MAX_VALUE }

        val now = System.currentTimeMillis()
        val upcoming = allExams.filter { it.timestamp == 0L || it.timestamp > now }
        val nextExam = allExams.filter { it.timestamp > now }.minByOrNull { it.timestamp }

        if (nextExam != null) {
            val diff = nextExam.timestamp - now
            val days = diff / (1000 * 60 * 60 * 24)
            val hours = (diff % (1000 * 60 * 60 * 24)) / (1000 * 60 * 60)

            countdownTitle.text = String.format(Locale.CHINA, getString(R.string.exam_countdown_next), nextExam.courseName)
            countdownText.text = if (days > 0) {
                String.format(Locale.CHINA, getString(R.string.exam_countdown_days), days, hours)
            } else {
                val mins = (diff % (1000 * 60 * 60)) / (1000 * 60)
                String.format(Locale.CHINA, getString(R.string.exam_countdown_hours), hours, mins)
            }
            countdownDetail.text = "${nextExam.examTimeText} · ${nextExam.location} · ${nextExam.seatNumber}"
        } else {
            countdownTitle.text = "近期考试倒计时"
            if (allExams.isEmpty()) {
                countdownText.text = "暂无考试安排"
                countdownDetail.text = "点击下方同步或手动添加考试日程"
            } else {
                countdownText.text = "全部考试已结束 🎉"
                countdownDetail.text = "本学期安排的 ${allExams.size} 门考试均已完成"
            }
        }

        if (allExams.isEmpty()) {
            emptyHint.visibility = View.VISIBLE
            listContainer.visibility = View.GONE
        } else {
            emptyHint.visibility = View.GONE
            listContainer.visibility = View.VISIBLE
            renderExamList(allExams)
        }
    }

    private fun renderExamList(exams: List<ExamItem>) {
        listContainer.removeAllViews()

        for (exam in exams) {
            val isPast = exam.timestamp in 1 until System.currentTimeMillis()

            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(24, 20, 24, 20)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = 14
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 14f
                    setColor(palette.panelAltBackground)
                    setStroke(1, ColorUtils.blendARGB(palette.panelAltBackground, palette.iconTint, 0.12f))
                }
                if (isPast) alpha = 0.55f
            }

            // 顶行：课程名与状态标签
            val topRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            val nameView = TextView(this).apply {
                text = exam.courseName
                textSize = 16f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(palette.textPrimary)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val badgeView = TextView(this).apply {
                text = if (isPast) "已结束" else exam.examType
                textSize = 12f
                setPadding(16, 6, 16, 6)
                val badgeColor = if (isPast) Color.GRAY else palette.iconTint
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 8f
                    setColor(ColorUtils.setAlphaComponent(badgeColor, 35))
                }
                setTextColor(badgeColor)
            }

            topRow.addView(nameView)
            topRow.addView(badgeView)
            card.addView(topRow)

            // 信息行：时间
            val timeView = TextView(this).apply {
                text = "🕒  ${if (exam.examTimeText.isNotBlank()) exam.examTimeText else "时间待定"}"
                textSize = 13f
                setTextColor(ColorUtils.blendARGB(palette.textPrimary, palette.pageBackground, 0.35f))
                setPadding(0, 10, 0, 4)
            }
            card.addView(timeView)

            // 地点与座位
            val placeText = buildString {
                if (exam.location.isNotBlank()) append("📍  ${exam.location}") else append("📍  地点待通知")
                if (exam.seatNumber.isNotBlank()) append("  ·  座位 ${exam.seatNumber}")
            }
            val placeView = TextView(this).apply {
                text = placeText
                textSize = 13f
                setTextColor(ColorUtils.blendARGB(palette.textPrimary, palette.pageBackground, 0.35f))
            }
            card.addView(placeView)

            // 长按删除操作
            card.setOnLongClickListener {
                showDeleteExamDialog(exam)
                true
            }

            listContainer.addView(card)
        }
    }

    private fun showDeleteExamDialog(exam: ExamItem) {
        val dialog = AlertDialog.Builder(this)
            .setTitle("删除考试")
            .setMessage("确定从考程列表中移除「${exam.courseName}」吗？")
            .setPositiveButton("删除") { _, _ ->
                allExams.removeAll { it.id == exam.id }
                ExamStore.save(filesDir, allExams)
                renderExams()
            }
            .setNegativeButton("取消", null)
            .create()
        UiFeedback.styleDialogSurface(dialog, palette)
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.let { UiFeedback.styleDangerButton(it, palette) }
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.let { UiFeedback.styleSecondaryButton(it, palette) }
    }

    private fun showAddExamDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_add_exam, null)
        val courseInput = view.findViewById<EditText>(R.id.inputExamCourse)
        val timeInput = view.findViewById<EditText>(R.id.inputExamTime)
        val locationInput = view.findViewById<EditText>(R.id.inputExamLocation)
        val seatInput = view.findViewById<EditText>(R.id.inputExamSeat)
        val typeInput = view.findViewById<EditText>(R.id.inputExamType)

        UiFeedback.styleInput(courseInput, palette)
        UiFeedback.styleInput(timeInput, palette)
        UiFeedback.styleInput(locationInput, palette)
        UiFeedback.styleInput(seatInput, palette)
        UiFeedback.styleInput(typeInput, palette)

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.exam_dialog_title)
            .setView(view)
            .setPositiveButton("添加") { _, _ ->
                val course = courseInput.text.toString().trim()
                if (course.isBlank()) return@setPositiveButton
                val time = timeInput.text.toString().trim()
                val location = locationInput.text.toString().trim()
                val seat = seatInput.text.toString().trim()
                val type = typeInput.text.toString().trim().ifBlank { "期末考试" }

                val timestamp = runCatching {
                    val match = Regex("""\d{4}-\d{2}-\d{2}\s+\d{2}:\d{2}""").find(time)
                    if (match != null) {
                        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).parse(match.value)?.time ?: 0L
                    } else 0L
                }.getOrDefault(0L)

                val item = ExamItem(
                    id = UUID.randomUUID().toString(),
                    courseName = course,
                    examTimeText = time,
                    location = location,
                    seatNumber = seat,
                    examType = type,
                    timestamp = timestamp,
                    isCustom = true
                )
                allExams.add(item)
                ExamStore.save(filesDir, allExams)
                renderExams()
            }
            .setNegativeButton("取消", null)
            .create()
        UiFeedback.styleDialogSurface(dialog, palette)
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.let { UiFeedback.stylePrimaryButton(it, palette) }
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.let { UiFeedback.styleSecondaryButton(it, palette) }
    }
}
