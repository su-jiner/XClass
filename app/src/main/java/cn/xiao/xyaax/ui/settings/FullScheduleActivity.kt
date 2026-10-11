package cn.xiao.xyaax.ui.settings

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import cn.xiao.xyaax.R
import cn.xiao.xyaax.data.AppPreferences
import cn.xiao.xyaax.data.ImportedScheduleStorage
import cn.xiao.xyaax.data.TimeProfileStore
import cn.xiao.xyaax.domain.TimeProfileResolver
import cn.xiao.xyaax.domain.WeekScheduleCalculator
import cn.xiao.xyaax.ui.theme.ThemePalette
import cn.xiao.xyaax.ui.theme.ThemePaletteProvider
import cn.xiao.xyaax.ui.theme.UiTransitions
import cn.xiao.xyaax.ui.timetable.CourseDetailBottomSheet
import cn.xiao.xyaax.ui.timetable.TimetableMetrics
import cn.xiao.xyaax.ui.timetable.WeekTimetableRenderer

/**
 * 课表管理 → 查看完整课表：所有周的课程在同一页面展示（与课表页一致的网格/长条样式），
 * 点击课程可编辑，修改即同步所有周。
 */
class FullScheduleActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_PROFILE_ID = "extra_profile_id"
        const val EXTRA_PROFILE_NAME = "extra_profile_name"

        fun start(context: Context, profileId: String, profileName: String) {
            context.startActivity(
                Intent(context, FullScheduleActivity::class.java)
                    .putExtra(EXTRA_PROFILE_ID, profileId)
                    .putExtra(EXTRA_PROFILE_NAME, profileName)
            )
        }
    }

    private lateinit var headerRow: LinearLayout
    private lateinit var bodyRow: LinearLayout
    private lateinit var emptyView: TextView
    private lateinit var palette: ThemePalette
    private var profileId: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemePaletteProvider.applyNightMode(this)
        setTheme(ThemePaletteProvider.themeStyleFor(AppPreferences.getThemeColor(this)))
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_full_schedule)
        UiTransitions.applyEnter(this)

        profileId = intent.getStringExtra(EXTRA_PROFILE_ID).orEmpty()
        val profileName = intent.getStringExtra(EXTRA_PROFILE_NAME).orEmpty()
        title = getString(R.string.full_schedule_title)
        headerRow = findViewById(R.id.headerRow)
        bodyRow = findViewById(R.id.bodyRow)
        emptyView = findViewById(R.id.fullEmpty)
        val titleView = findViewById<TextView>(R.id.fullTitle)
        val subtitleView = findViewById<TextView>(R.id.fullSubtitle)

        palette = ThemePaletteProvider.fromContext(this)
        findViewById<View>(R.id.fullRoot).setBackgroundColor(palette.pageBackground)
        titleView.setTextColor(palette.textPrimary)
        subtitleView.setTextColor(palette.textSecondary)
        emptyView.setTextColor(palette.textSecondary)

        titleView.text = if (profileName.isNotBlank()) "${getString(R.string.full_schedule_title)} · $profileName" else getString(R.string.full_schedule_title)
        subtitleView.text = getString(R.string.full_schedule_subtitle)

        applySystemBarInsets()
        render()
    }

    private var renderedOnce = false

    override fun onResume() {
        super.onResume()
        // 编辑课程返回后重新渲染（读取最新数据）
        render()
    }

    private fun render() {
        val courses = ImportedScheduleStorage.loadCoursesForProfile(filesDir, profileId)
            ?: run { finish(); return }
        renderedOnce = true
        val fontScale = AppPreferences.getTimetableFontScale(this)
        val hasCustomBackground = !AppPreferences.getCustomBackgroundUri(this).isNullOrBlank()
        val timeProfiles = TimeProfileStore.load(filesDir)
        val displayProfile = TimeProfileResolver.resolve(
            timeProfiles,
            TimeProfileStore.loadDefaultProfileId(filesDir)
        )
        val displayPeriodRanges = TimeProfileResolver.sectionRanges(displayProfile)
        val weekdayLabels = mapOf(
            cn.xiao.xyaax.model.Weekday.MONDAY to "一",
            cn.xiao.xyaax.model.Weekday.TUESDAY to "二",
            cn.xiao.xyaax.model.Weekday.WEDNESDAY to "三",
            cn.xiao.xyaax.model.Weekday.THURSDAY to "四",
            cn.xiao.xyaax.model.Weekday.FRIDAY to "五",
            cn.xiao.xyaax.model.Weekday.SATURDAY to "六",
            cn.xiao.xyaax.model.Weekday.SUNDAY to "日"
        )

        val allItems = WeekScheduleCalculator.meetingsForFullView(courses)
        if (allItems.isEmpty()) {
            emptyView.visibility = View.VISIBLE
            emptyView.text = getString(R.string.full_schedule_empty)
            return
        }

        val metrics = TimetableMetrics.create(this)
        val renderer = WeekTimetableRenderer(
            periodRanges = displayPeriodRanges,
            weekdayLabels = weekdayLabels,
            fontScale = fontScale,
            palette = palette,
            hasCustomBackground = hasCustomBackground,
            allWeeksMode = true
        )
        renderer.render(
            headerRow = headerRow,
            bodyRow = bodyRow,
            notesBar = findViewById(R.id.notesBar),
            metrics = metrics,
            items = allItems,
            notes = cn.xiao.xyaax.importing.PageMetadataParser.sanitizeStoredNotes(
                ImportedScheduleStorage.loadNotesForProfile(filesDir, profileId)
            ),
            onCourseClick = { primary, _ ->
                CourseDetailBottomSheet.show(
                    context = this,
                    primary = primary,
                    allCourses = listOf(primary),
                    periodRanges = displayPeriodRanges,
                    onEditCourse = { item ->
                        EditCourseActivity.start(this, profileId, item.courseIndex, item.meetingIndex)
                    }
                )
            },
            weekStart = java.time.LocalDate.now(),
            today = java.time.LocalDate.now(),
            currentSection = null
        )
    }

    private fun applySystemBarInsets() {
        val root = findViewById<View>(R.id.fullRoot)
        val baseLeft = root.paddingLeft
        val baseTop = root.paddingTop
        val baseRight = root.paddingRight
        val baseBottom = root.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(
                left = baseLeft + bars.left,
                top = baseTop + bars.top,
                right = baseRight + bars.right,
                bottom = baseBottom + bars.bottom
            )
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    override fun finish() {
        super.finish()
        UiTransitions.applyExit(this)
    }
}
