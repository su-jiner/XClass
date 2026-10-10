package cn.xiao.xyaax.ui.tools

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.core.graphics.ColorUtils
import androidx.fragment.app.Fragment
import cn.xiao.xyaax.R
import cn.xiao.xyaax.ui.theme.ThemePaletteProvider

/** 工具页：本地学习工具（成绩查询、绩点计算器、考试安排、学业完成），全部离线可用 */
class ToolsFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_tools, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val ctx = requireContext()
        val palette = ThemePaletteProvider.fromContext(ctx)

        fun styleCard(cardId: Int, iconId: Int) {
            view.findViewById<View>(cardId)?.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 18f
                setColor(palette.panelAltBackground)
                setStroke(1, ColorUtils.blendARGB(palette.panelAltBackground, palette.iconTint, 0.16f))
            }
            view.findViewById<ImageView>(iconId)?.imageTintList = ColorStateList.valueOf(palette.iconTint)
        }

        styleCard(R.id.toolsCardAcademics, R.id.iconToolAcademics)
        styleCard(R.id.toolsCardExams, R.id.iconToolExams)

        // 学业与成绩卡片
        view.findViewById<View>(R.id.rowGradeInquiry)?.setOnClickListener {
            startActivity(Intent(ctx, GradeInquiryActivity::class.java))
        }
        view.findViewById<View>(R.id.rowGpaCalculator)?.setOnClickListener {
            startActivity(Intent(ctx, GpaCalculatorActivity::class.java))
        }
        view.findViewById<View>(R.id.rowAcademicProgress)?.setOnClickListener {
            startActivity(Intent(ctx, AcademicProgressActivity::class.java))
        }

        // 考务与日程卡片
        view.findViewById<View>(R.id.rowExamSchedule)?.setOnClickListener {
            startActivity(Intent(ctx, ExamScheduleActivity::class.java))
        }
    }
}
