package cn.xiao.xyaax.ui.tools

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
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
import cn.xiao.xyaax.data.GpaCourseStore
import cn.xiao.xyaax.data.GradeStore
import cn.xiao.xyaax.domain.GpaCalculator
import cn.xiao.xyaax.domain.ImportedGrade
import cn.xiao.xyaax.ui.theme.ThemePalette
import cn.xiao.xyaax.ui.theme.ThemePaletteProvider
import cn.xiao.xyaax.ui.theme.UiFeedback
import cn.xiao.xyaax.ui.theme.applySystemBarPadding
import cn.xiao.xyaax.ui.theme.applySystemBarIcons
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * 成绩查询界面：按学期汇总课程成绩、单科绩点、加权均分，支持手动录入与绩点计算器双向打通（全程离线）。
 */
class GradeInquiryActivity : AppCompatActivity() {

    private lateinit var palette: ThemePalette
    private lateinit var gpaText: TextView
    private lateinit var avgText: TextView
    private lateinit var creditsText: TextView
    private lateinit var detailText: TextView
    private lateinit var semesterChips: LinearLayout
    private lateinit var listContainer: LinearLayout
    private lateinit var emptyHint: TextView
    private lateinit var addBtn: Button
    private lateinit var fromGpaBtn: Button
    private lateinit var toGpaBtn: Button

    private val allGrades = mutableListOf<ImportedGrade>()
    private var selectedSemester: String? = null // null means 全部学期

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemePaletteProvider.applyNightMode(this)
        setTheme(ThemePaletteProvider.themeStyleFor(AppPreferences.getThemeColor(this)))
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_grade_inquiry)
        findViewById<View>(R.id.gradeRoot).applySystemBarPadding()

        palette = ThemePaletteProvider.fromContext(this)
        window.applySystemBarIcons(palette.isDark)
        initViews()
        loadLocalData()
    }

    private fun initViews() {
        findViewById<View>(R.id.gradeRoot).setBackgroundColor(palette.pageBackground)
        findViewById<View>(R.id.gradeHeader).setBackgroundColor(palette.panelAltBackground)

        val backBtn = findViewById<ImageView>(R.id.gradeBackBtn)
        backBtn.imageTintList = ColorStateList.valueOf(palette.iconTint)
        backBtn.setOnClickListener { finish() }

        gpaText = findViewById(R.id.gradeSummaryGpa)
        avgText = findViewById(R.id.gradeSummaryAvg)
        creditsText = findViewById(R.id.gradeSummaryCredits)
        detailText = findViewById(R.id.gradeSummaryDetail)
        semesterChips = findViewById(R.id.gradeSemesterChips)
        listContainer = findViewById(R.id.gradeListContainer)
        emptyHint = findViewById(R.id.gradeEmptyHint)

        addBtn = findViewById(R.id.gradeAddBtn)
        fromGpaBtn = findViewById(R.id.gradeFromGpaBtn)
        toGpaBtn = findViewById(R.id.gradeToGpaBtn)

        UiFeedback.styleSecondaryButton(addBtn, palette)
        UiFeedback.styleSecondaryButton(fromGpaBtn, palette)
        UiFeedback.styleSecondaryButton(toGpaBtn, palette)

        findViewById<View>(R.id.gradeSummaryCard).background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 16f
            setColor(palette.panelAltBackground)
            setStroke(1, ColorUtils.blendARGB(palette.panelAltBackground, palette.iconTint, 0.16f))
        }

        addBtn.setOnClickListener { showAddGradeDialog(null) }
        fromGpaBtn.setOnClickListener { syncFromGpaCalculator() }
        toGpaBtn.setOnClickListener { exportToGpaCalculator() }
    }

    private fun loadLocalData() {
        allGrades.clear()
        allGrades.addAll(GradeStore.load(filesDir))
        renderGrades()
    }

    private fun renderGrades() {
        val filtered = if (selectedSemester == null) {
            allGrades
        } else {
            allGrades.filter { it.semesterCode == selectedSemester }
        }

        if (filtered.isEmpty()) {
            emptyHint.visibility = View.VISIBLE
            listContainer.visibility = View.GONE
            gpaText.text = "0.00"
            avgText.text = "0.00"
            creditsText.text = "0.0"
            detailText.text = getString(R.string.grade_empty_hint)
        } else {
            emptyHint.visibility = View.GONE
            listContainer.visibility = View.VISIBLE
            computeAndRenderSummary(filtered)
            renderGradeList(filtered)
        }

        renderSemesterChips()
    }

    private fun computeAndRenderSummary(grades: List<ImportedGrade>) {
        var totalCreditWeighted = 0.0
        var totalScoreWeighted = 0.0
        var totalGradePoints = 0.0

        for (grade in grades) {
            val scoreNum = grade.scoreText.toDoubleOrNull()
            val credit = grade.credit
            val effectiveScore = when {
                scoreNum != null && scoreNum.isFinite() && scoreNum in GpaCalculator.MIN_SCORE..GpaCalculator.MAX_SCORE -> scoreNum
                else -> GpaCalculator.LEVEL_SCORES[grade.scoreText] ?: -1.0
            }
            if (effectiveScore >= 0.0 && credit > 0.0) {
                totalCreditWeighted += credit
                totalScoreWeighted += effectiveScore * credit
                totalGradePoints += GpaCalculator.gradePointFor(effectiveScore) * credit
            }
        }

        val gpa = if (totalCreditWeighted > 0.0) totalGradePoints / totalCreditWeighted else 0.0
        val avg = if (totalCreditWeighted > 0.0) totalScoreWeighted / totalCreditWeighted else 0.0

        gpaText.text = String.format(Locale.CHINA, "%.2f", gpa)
        avgText.text = String.format(Locale.CHINA, "%.2f", avg)
        creditsText.text = String.format(Locale.CHINA, "%.1f", totalCreditWeighted)
        detailText.text = String.format(Locale.CHINA, getString(R.string.grade_summary_format), grades.size, totalCreditWeighted, avg)
    }

    private fun renderSemesterChips() {
        semesterChips.removeAllViews()
        val semesters = allGrades.map { it.semesterCode }.filter { it.isNotBlank() }.distinct()

        // 全部学期 chip
        addSemesterChip(getString(R.string.grade_semester_all), selectedSemester == null) {
            selectedSemester = null
            renderGrades()
        }

        for (sem in semesters) {
            addSemesterChip(sem, selectedSemester == sem) {
                selectedSemester = sem
                renderGrades()
            }
        }
    }

    private fun addSemesterChip(title: String, isSelected: Boolean, onClick: () -> Unit) {
        val chip = TextView(this).apply {
            text = title
            textSize = 13f
            setPadding(28, 14, 28, 14)
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = 16
            }
            layoutParams = params
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 24f
                if (isSelected) {
                    setColor(palette.buttonBackground)
                    setStroke(1, ColorUtils.blendARGB(palette.buttonBackground, palette.iconTint, 0.4f))
                } else {
                    setColor(palette.panelAltBackground)
                    setStroke(1, ColorUtils.blendARGB(palette.panelAltBackground, palette.iconTint, 0.2f))
                }
            }
            setTextColor(if (isSelected) palette.buttonText else palette.textPrimary)
            setOnClickListener { onClick() }
        }
        semesterChips.addView(chip)
    }

    private fun renderGradeList(grades: List<ImportedGrade>) {
        listContainer.removeAllViews()

        for (grade in grades) {
            val scoreNum = grade.scoreText.toDoubleOrNull()
            val effectiveScore = when {
                scoreNum != null && scoreNum.isFinite() && scoreNum in GpaCalculator.MIN_SCORE..GpaCalculator.MAX_SCORE -> scoreNum
                else -> GpaCalculator.LEVEL_SCORES[grade.scoreText] ?: -1.0
            }
            val gp = if (effectiveScore >= 0.0) GpaCalculator.gradePointFor(effectiveScore) else 0.0

            val card = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
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
                setOnClickListener { showEditDeleteDialog(grade) }
                setOnLongClickListener {
                    showEditDeleteDialog(grade)
                    true
                }
            }

            val textColumn = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val nameView = TextView(this).apply {
                text = grade.name
                textSize = 15f
                setTextColor(palette.textPrimary)
            }
            val subView = TextView(this).apply {
                val codeInfo = if (grade.courseCode.isNotBlank()) "${grade.courseCode} · " else ""
                val semInfo = if (grade.semesterCode.isNotBlank()) " · ${grade.semesterCode}" else ""
                text = "$codeInfo${grade.credit}学分 · 绩点 ${String.format(Locale.CHINA, "%.1f", gp)}$semInfo"
                textSize = 12f
                setTextColor(ColorUtils.blendARGB(palette.textPrimary, palette.pageBackground, 0.4f))
            }
            textColumn.addView(nameView)
            textColumn.addView(subView)

            val badgeView = TextView(this).apply {
                text = grade.scoreText
                textSize = 15f
                gravity = Gravity.CENTER
                setPadding(24, 8, 24, 8)
                val badgeColor = if (palette.isDark) {
                    when {
                        effectiveScore >= 90.0 -> Color.parseColor("#81C784") // 柔和绿
                        effectiveScore >= 80.0 -> Color.parseColor("#90CAF9") // 柔和蓝
                        effectiveScore >= 60.0 -> Color.parseColor("#FFB74D") // 柔和琥珀橙
                        else -> Color.parseColor("#E57373") // 柔和珊瑚红
                    }
                } else {
                    when {
                        effectiveScore >= 90.0 -> Color.parseColor("#388E3C") // 绿色
                        effectiveScore >= 80.0 -> Color.parseColor("#1976D2") // 蓝色
                        effectiveScore >= 60.0 -> Color.parseColor("#F57C00") // 橙色
                        else -> Color.parseColor("#D32F2F") // 红色
                    }
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 10f
                    val bgAlpha = if (palette.isDark) 45 else 35
                    setColor(ColorUtils.setAlphaComponent(badgeColor, bgAlpha))
                }
                setTextColor(badgeColor)
            }

            card.addView(textColumn)
            card.addView(badgeView)
            listContainer.addView(card)
        }
    }

    private fun showEditDeleteDialog(grade: ImportedGrade) {
        val options = arrayOf(
            getString(R.string.grade_edit_title),
            getString(R.string.manage_delete)
        )
        AlertDialog.Builder(this)
            .setTitle(grade.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showAddGradeDialog(grade)
                    1 -> confirmDeleteGrade(grade)
                }
            }
            .create()
            .also {
                UiFeedback.styleDialogSurface(it, palette)
                it.show()
            }
    }

    private fun confirmDeleteGrade(grade: ImportedGrade) {
        AlertDialog.Builder(this)
            .setTitle(R.string.grade_delete_title)
            .setMessage(getString(R.string.grade_delete_message, grade.name))
            .setPositiveButton(R.string.manage_delete) { _, _ ->
                allGrades.removeAll { it == grade || (it.name == grade.name && it.courseCode == grade.courseCode && it.semesterCode == grade.semesterCode) }
                GradeStore.save(filesDir, allGrades)
                GradeStore.syncToGpaCourses(filesDir, allGrades)
                renderGrades()
                UiFeedback.showMessage(listContainer, getString(R.string.manage_deleted), palette)
            }
            .setNegativeButton(R.string.action_cancel, null)
            .create()
            .also {
                UiFeedback.styleDialogSurface(it, palette)
                it.show()
                it.getButton(AlertDialog.BUTTON_POSITIVE)?.let { btn -> UiFeedback.styleDangerButton(btn, palette) }
                it.getButton(AlertDialog.BUTTON_NEGATIVE)?.let { btn -> UiFeedback.styleSecondaryButton(btn, palette) }
            }
    }

    private fun showAddGradeDialog(existing: ImportedGrade?) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_add_grade, null)
        val nameInput = dialogView.findViewById<EditText>(R.id.inputGradeName)
        val creditInput = dialogView.findViewById<EditText>(R.id.inputGradeCredit)
        val scoreInput = dialogView.findViewById<EditText>(R.id.inputGradeScore)
        val semesterInput = dialogView.findViewById<EditText>(R.id.inputGradeSemester)
        val codeInput = dialogView.findViewById<EditText>(R.id.inputGradeCode)

        UiFeedback.styleInput(nameInput, palette)
        UiFeedback.styleInput(creditInput, palette)
        UiFeedback.styleInput(scoreInput, palette)
        UiFeedback.styleInput(semesterInput, palette)
        UiFeedback.styleInput(codeInput, palette)

        if (existing != null) {
            nameInput.setText(existing.name)
            creditInput.setText(if (existing.credit % 1.0 == 0.0) existing.credit.toInt().toString() else existing.credit.toString())
            scoreInput.setText(existing.scoreText)
            semesterInput.setText(existing.semesterCode)
            codeInput.setText(existing.courseCode)
        }

        AlertDialog.Builder(this)
            .setTitle(if (existing != null) R.string.grade_edit_title else R.string.grade_dialog_title)
            .setView(dialogView)
            .setPositiveButton(R.string.action_save) { _, _ ->
                val name = nameInput.text.toString().trim()
                val credit = creditInput.text.toString().trim().toDoubleOrNull()
                val score = scoreInput.text.toString().trim()
                val semester = semesterInput.text.toString().trim()
                val code = codeInput.text.toString().trim()

                if (name.isBlank()) {
                    UiFeedback.showMessage(listContainer, getString(R.string.manage_name_empty), palette)
                    return@setPositiveButton
                }
                if (credit == null || credit <= 0.0) {
                    UiFeedback.showMessage(listContainer, getString(R.string.gpa_invalid_credit), palette)
                    return@setPositiveButton
                }
                if (score.isBlank()) {
                    UiFeedback.showMessage(listContainer, getString(R.string.gpa_invalid_score), palette)
                    return@setPositiveButton
                }

                val newGrade = ImportedGrade(
                    courseCode = code,
                    name = name,
                    credit = credit,
                    scoreText = score,
                    semesterCode = semester,
                    isCustom = true
                )

                if (existing != null) {
                    val idx = allGrades.indexOfFirst { it == existing }
                    if (idx >= 0) allGrades[idx] = newGrade else allGrades.add(newGrade)
                } else {
                    allGrades.add(0, newGrade)
                }

                GradeStore.save(filesDir, allGrades)
                GradeStore.syncToGpaCourses(filesDir, allGrades)
                renderGrades()
                UiFeedback.showMessage(listContainer, if (existing != null) "已修改成绩" else "已添加成绩", palette)
            }
            .setNegativeButton(R.string.action_cancel, null)
            .create()
            .also {
                UiFeedback.styleDialogSurface(it, palette)
                it.show()
                it.getButton(AlertDialog.BUTTON_POSITIVE)?.let { btn -> UiFeedback.stylePrimaryButton(btn, palette) }
                it.getButton(AlertDialog.BUTTON_NEGATIVE)?.let { btn -> UiFeedback.styleSecondaryButton(btn, palette) }
            }
    }

    private fun syncFromGpaCalculator() {
        val gpaCourses = GpaCourseStore.load(filesDir)
        if (gpaCourses.isEmpty()) {
            UiFeedback.showMessage(listContainer, "绩点计算器中暂无课程数据", palette)
            return
        }
        val converted = GradeStore.convertFromGpaCourses(gpaCourses)
        if (converted.isEmpty()) {
            UiFeedback.showMessage(listContainer, "未找到可导入的课程数据", palette)
            return
        }

        allGrades.clear()
        allGrades.addAll(converted)
        GradeStore.save(filesDir, allGrades)
        renderGrades()
        UiFeedback.showMessage(
            listContainer,
            String.format(Locale.CHINA, getString(R.string.grade_imported_from_gpa), converted.size),
            palette
        )
    }

    private fun exportToGpaCalculator() {
        if (allGrades.isNotEmpty()) {
            GradeStore.syncToGpaCourses(filesDir, allGrades)
        }
        startActivity(Intent(this, GpaCalculatorActivity::class.java))
    }
}
