package cn.xiao.xyaax.ui.tools

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.Log
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
import cn.xiao.xyaax.data.AcademicProgressPlan
import cn.xiao.xyaax.data.AcademicProgressStore
import cn.xiao.xyaax.data.AcademicRequirement
import cn.xiao.xyaax.data.AppPreferences
import cn.xiao.xyaax.data.GradeStore
import cn.xiao.xyaax.ui.theme.ThemePalette
import cn.xiao.xyaax.ui.theme.ThemePaletteProvider
import cn.xiao.xyaax.ui.theme.UiFeedback
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * 学业完成查询界面：
 * 1. 支持从教务学业完成查询页拉取真实学分达成度；
 * 2. 彻底废除比例平摊计算，本地成绩改为智能启发式分类；
 * 3. 支持全功能手动编辑指标与已修学分，自由增删模块；
 * 4. 教务原系统网页直达。
 */
class AcademicProgressActivity : AppCompatActivity() {

    private lateinit var palette: ThemePalette
    private lateinit var percentText: TextView
    private lateinit var summaryText: TextView
    private lateinit var sourceHintText: TextView
    private lateinit var totalProgressBar: ProgressBar
    private lateinit var categoryContainer: LinearLayout
    private lateinit var editPlanBtn: Button
    private lateinit var autoCalcBtn: Button

    private var currentPlan: AcademicProgressPlan = AcademicProgressPlan()

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemePaletteProvider.applyNightMode(this)
        setTheme(ThemePaletteProvider.themeStyleFor(AppPreferences.getThemeColor(this)))
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_academic_progress)

        palette = ThemePaletteProvider.fromContext(this)
        initViews()
        loadPlan()
    }

    override fun onResume() {
        super.onResume()
        // 若用户从原网页返回，重新载入最新数据
        loadPlan()
    }

    private fun initViews() {
        findViewById<View>(R.id.academicRoot).setBackgroundColor(palette.pageBackground)
        findViewById<View>(R.id.academicHeader).setBackgroundColor(palette.panelAltBackground)

        val backBtn = findViewById<ImageView>(R.id.academicBackBtn)
        backBtn.imageTintList = ColorStateList.valueOf(palette.iconTint)
        backBtn.setOnClickListener { finish() }

        percentText = findViewById(R.id.academicPercentText)
        summaryText = findViewById(R.id.academicCreditsSummary)
        sourceHintText = findViewById(R.id.academicSourceHint)
        totalProgressBar = findViewById(R.id.academicTotalProgress)
        categoryContainer = findViewById(R.id.academicCategoryContainer)

        editPlanBtn = findViewById(R.id.academicEditPlanBtn)
        autoCalcBtn = findViewById(R.id.academicAutoCalcBtn)

        UiFeedback.styleSecondaryButton(editPlanBtn, palette)
        UiFeedback.styleSecondaryButton(autoCalcBtn, palette)

        findViewById<View>(R.id.academicHeroCard).background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 18f
            setColor(palette.panelAltBackground)
            setStroke(1, ColorUtils.blendARGB(palette.panelAltBackground, palette.iconTint, 0.16f))
        }

        totalProgressBar.progressTintList = ColorStateList.valueOf(palette.iconTint)
        totalProgressBar.progressBackgroundTintList = ColorStateList.valueOf(
            ColorUtils.setAlphaComponent(palette.iconTint, 40)
        )
        editPlanBtn.setOnClickListener { showEditPlanDialog() }
        autoCalcBtn.setOnClickListener { autoCalculateFromGrades() }
    }

    private fun loadPlan() {
        currentPlan = AcademicProgressStore.load(filesDir)
        renderPlan()
    }

    private fun renderPlan() {
        val totalReq = if (currentPlan.totalRequiredCredits > 0.0) currentPlan.totalRequiredCredits else 160.0
        val totalEarned = currentPlan.totalEarnedCredits
        val percent = ((totalEarned / totalReq) * 100).toInt().coerceIn(0, 100)

        percentText.text = "$percent%"
        percentText.setTextColor(palette.textPrimary)
        summaryText.text = String.format(Locale.CHINA, getString(R.string.academic_credits_summary), totalEarned, totalReq, percent)
        totalProgressBar.progress = percent

        sourceHintText.text = when (currentPlan.source) {
            AcademicProgressPlan.SOURCE_WEB -> getString(R.string.academic_source_web)
            AcademicProgressPlan.SOURCE_ESTIMATED -> getString(R.string.academic_source_estimated)
            else -> getString(R.string.academic_source_manual)
        }

        categoryContainer.removeAllViews()

        for (req in currentPlan.requirements) {
            val reqCredits = if (req.requiredCredits > 0.0) req.requiredCredits else 1.0
            val catPercent = ((req.earnedCredits / reqCredits) * 100).toInt().coerceIn(0, 100)

            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(24, 18, 24, 18)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = 12
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 14f
                    setColor(palette.panelAltBackground)
                    setStroke(1, ColorUtils.blendARGB(palette.panelAltBackground, palette.iconTint, 0.12f))
                }
            }

            // 标题行
            val headerRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            val titleView = TextView(this).apply {
                text = req.categoryName
                textSize = 14f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(palette.textPrimary)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val completionColor = if (palette.isDark) Color.parseColor("#81C784") else Color.parseColor("#388E3C")

            val scoreView = TextView(this).apply {
                text = String.format(Locale.CHINA, "%.1f / %.1f 学分 (%d%%)", req.earnedCredits, req.requiredCredits, catPercent)
                textSize = 13f
                setTextColor(if (catPercent >= 100) completionColor else palette.iconTint)
            }

            headerRow.addView(titleView)
            headerRow.addView(scoreView)
            card.addView(headerRow)

            // 进度条
            val pb = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100
                progress = catPercent
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    14
                ).apply {
                    topMargin = 16
                }
                progressTintList = ColorStateList.valueOf(
                    if (catPercent >= 100) completionColor else palette.iconTint
                )
                progressBackgroundTintList = ColorStateList.valueOf(
                    ColorUtils.setAlphaComponent(palette.iconTint, 30)
                )
            }
            card.addView(pb)

            categoryContainer.addView(card)
        }
    }

    private fun autoCalculateFromGrades() {
        val grades = GradeStore.load(filesDir)
        if (grades.isEmpty()) {
            UiFeedback.showMessage(categoryContainer, "暂无本地成绩，请先在「成绩查询」中添加成绩", palette)
            return
        }

        var totalPassedCredits = 0.0
        var passedCount = 0

        var compulsoryEarned = 0.0
        var electiveEarned = 0.0
        var generalEarned = 0.0
        var practiceEarned = 0.0
        var otherEarned = 0.0

        for (g in grades) {
            val num = g.scoreText.toDoubleOrNull()
            val passed = when {
                num != null && num.isFinite() -> num >= 60.0
                g.scoreText in listOf("优秀", "良好", "中等", "及格", "通过", "合格") -> true
                else -> false
            }
            if (!passed || g.credit <= 0.0) continue

            totalPassedCredits += g.credit
            passedCount++

            val name = g.name
            when {
                isPracticeCourse(name) -> practiceEarned += g.credit
                isGeneralCourse(name) -> generalEarned += g.credit
                isElectiveCourse(name) -> electiveEarned += g.credit
                else -> compulsoryEarned += g.credit
            }
        }

        // 映射到现有的模块配置上
        val currentReqs = currentPlan.requirements
        val updatedReqs = if (currentReqs.size >= 4) {
            listOf(
                currentReqs[0].copy(earnedCredits = roundOneDecimal(compulsoryEarned)),
                currentReqs[1].copy(earnedCredits = roundOneDecimal(electiveEarned)),
                currentReqs[2].copy(earnedCredits = roundOneDecimal(generalEarned)),
                currentReqs[3].copy(earnedCredits = roundOneDecimal(practiceEarned))
            )
        } else {
            listOf(
                AcademicRequirement(getString(R.string.academic_category_compulsory), 75.0, roundOneDecimal(compulsoryEarned)),
                AcademicRequirement(getString(R.string.academic_category_elective), 35.0, roundOneDecimal(electiveEarned)),
                AcademicRequirement(getString(R.string.academic_category_general), 16.0, roundOneDecimal(generalEarned)),
                AcademicRequirement(getString(R.string.academic_category_practice), 34.0, roundOneDecimal(practiceEarned))
            )
        }

        currentPlan = currentPlan.copy(
            totalEarnedCredits = roundOneDecimal(totalPassedCredits),
            requirements = updatedReqs,
            lastUpdated = System.currentTimeMillis(),
            source = AcademicProgressPlan.SOURCE_ESTIMATED
        )
        AcademicProgressStore.save(filesDir, currentPlan)
        renderPlan()

        UiFeedback.showMessage(
            categoryContainer,
            String.format(Locale.CHINA, getString(R.string.academic_auto_calc_success), passedCount, totalPassedCredits),
            palette
        )
    }

    private fun isPracticeCourse(name: String): Boolean {
        val keywords = listOf("实习", "课程设计", "毕业设计", "毕业论文", "实验", "实训", "见习", "军事训练", "军训", "社会实践", "劳动")
        return keywords.any { name.contains(it) }
    }

    private fun isGeneralCourse(name: String): Boolean {
        val keywords = listOf("通识", "体育", "美育", "艺术", "音乐", "戏剧", "电影", "大学英语", "外语", "思想道德", "马克思", "近现代史", "毛泽东", "形势与政策", "军事理论", "国家安全", "心理健康")
        return keywords.any { name.contains(it) }
    }

    private fun isElectiveCourse(name: String): Boolean {
        val keywords = listOf("选修", "任选", "限选", "方向", "导论", "前沿")
        return keywords.any { name.contains(it) }
    }

    private fun roundOneDecimal(value: Double): Double =
        kotlin.math.round(value * 10.0) / 10.0

    // ==========================================
    // 3. 全功能动态手动编辑弹窗 (支持增删改模块与需修/已修)
    // ==========================================

    private fun showEditPlanDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_edit_academic_plan, null)
        val inputTotalReq = dialogView.findViewById<EditText>(R.id.inputTotalReqCredits)
        val inputTotalEarned = dialogView.findViewById<EditText>(R.id.inputTotalEarnedCredits)
        val autoSumBtn = dialogView.findViewById<TextView>(R.id.btnAutoSumCredits)
        val categoriesContainer = dialogView.findViewById<LinearLayout>(R.id.dialogCategoriesContainer)
        val addCategoryBtn = dialogView.findViewById<Button>(R.id.btnAddCategoryBtn)

        UiFeedback.styleInput(inputTotalReq, palette)
        UiFeedback.styleInput(inputTotalEarned, palette)
        autoSumBtn.setTextColor(palette.iconTint)
        UiFeedback.styleSecondaryButton(addCategoryBtn, palette)

        inputTotalReq.setText(currentPlan.totalRequiredCredits.toString())
        inputTotalEarned.setText(currentPlan.totalEarnedCredits.toString())

        // 填充每个模块视图
        fun addCategoryRow(categoryName: String, reqCredits: Double, earnedCredits: Double) {
            val itemView = layoutInflater.inflate(R.layout.item_edit_academic_module, categoriesContainer, false)
            val nameEdit = itemView.findViewById<EditText>(R.id.editCategoryName)
            val reqEdit = itemView.findViewById<EditText>(R.id.editCategoryReq)
            val earnedEdit = itemView.findViewById<EditText>(R.id.editCategoryEarned)
            val deleteBtn = itemView.findViewById<ImageView>(R.id.btnDeleteCategory)

            itemView.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 12f
                setColor(palette.panelAltBackground)
                setStroke(1, ColorUtils.blendARGB(palette.panelAltBackground, palette.iconTint, 0.22f))
            }
            nameEdit.setTextColor(palette.textPrimary)
            nameEdit.setHintTextColor(palette.textSecondary)
            UiFeedback.styleInput(reqEdit, palette)
            UiFeedback.styleInput(earnedEdit, palette)
            deleteBtn.imageTintList = ColorStateList.valueOf(
                if (palette.isDark) Color.parseColor("#E57373") else Color.parseColor("#FF7043")
            )

            nameEdit.setText(categoryName)
            reqEdit.setText(reqCredits.toString())
            earnedEdit.setText(earnedCredits.toString())

            deleteBtn.setOnClickListener {
                categoriesContainer.removeView(itemView)
            }
            categoriesContainer.addView(itemView)
        }

        for (req in currentPlan.requirements) {
            addCategoryRow(req.categoryName, req.requiredCredits, req.earnedCredits)
        }

        addCategoryBtn.setOnClickListener {
            addCategoryRow("", 10.0, 0.0)
        }

        autoSumBtn.setOnClickListener {
            var sumReq = 0.0
            var sumEarned = 0.0
            for (i in 0 until categoriesContainer.childCount) {
                val item = categoriesContainer.getChildAt(i)
                val reqVal = item.findViewById<EditText>(R.id.editCategoryReq).text.toString().toDoubleOrNull() ?: 0.0
                val earnedVal = item.findViewById<EditText>(R.id.editCategoryEarned).text.toString().toDoubleOrNull() ?: 0.0
                sumReq += reqVal
                sumEarned += earnedVal
            }
            inputTotalReq.setText(roundOneDecimal(sumReq).toString())
            inputTotalEarned.setText(roundOneDecimal(sumEarned).toString())
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.academic_edit_plan_button)
            .setView(dialogView)
            .setPositiveButton("保存") { _, _ ->
                val newReqs = mutableListOf<AcademicRequirement>()
                var sumReq = 0.0
                var sumEarned = 0.0

                for (i in 0 until categoriesContainer.childCount) {
                    val item = categoriesContainer.getChildAt(i)
                    val name = item.findViewById<EditText>(R.id.editCategoryName).text.toString().trim()
                    val reqVal = item.findViewById<EditText>(R.id.editCategoryReq).text.toString().toDoubleOrNull() ?: 0.0
                    val earnedVal = item.findViewById<EditText>(R.id.editCategoryEarned).text.toString().toDoubleOrNull() ?: 0.0

                    if (name.isNotBlank()) {
                        newReqs.add(
                            AcademicRequirement(
                                categoryName = name,
                                requiredCredits = roundOneDecimal(reqVal),
                                earnedCredits = roundOneDecimal(earnedVal)
                            )
                        )
                        sumReq += reqVal
                        sumEarned += earnedVal
                    }
                }

                val customTotalReq = inputTotalReq.text.toString().toDoubleOrNull()
                val customTotalEarned = inputTotalEarned.text.toString().toDoubleOrNull()

                val totalReq = if (customTotalReq != null && customTotalReq > 0.0) customTotalReq else sumReq.coerceAtLeast(160.0)
                val totalEarned = customTotalEarned ?: sumEarned

                currentPlan = currentPlan.copy(
                    totalRequiredCredits = roundOneDecimal(totalReq),
                    totalEarnedCredits = roundOneDecimal(totalEarned),
                    requirements = if (newReqs.isNotEmpty()) newReqs else currentPlan.requirements,
                    lastUpdated = System.currentTimeMillis(),
                    source = AcademicProgressPlan.SOURCE_MANUAL
                )
                AcademicProgressStore.save(filesDir, currentPlan)
                renderPlan()
                UiFeedback.showMessage(categoryContainer, "培养方案与学业指标已更新", palette)
            }
            .setNegativeButton("取消", null)
            .create()

        UiFeedback.styleDialogSurface(dialog, palette)
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.let { UiFeedback.stylePrimaryButton(it, palette) }
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.let { UiFeedback.styleSecondaryButton(it, palette) }
    }
}
