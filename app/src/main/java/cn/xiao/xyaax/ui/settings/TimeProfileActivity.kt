package cn.xiao.xyaax.ui.settings

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.ColorUtils
import cn.xiao.xyaax.R
import cn.xiao.xyaax.data.AppPreferences
import cn.xiao.xyaax.data.TimeProfileStore
import cn.xiao.xyaax.importing.TimeTableTextParser
import cn.xiao.xyaax.model.SectionBlock
import cn.xiao.xyaax.model.SectionTimeProfile
import cn.xiao.xyaax.ui.theme.ThemePalette
import cn.xiao.xyaax.ui.theme.ThemePaletteProvider
import cn.xiao.xyaax.ui.theme.UiFeedback
import cn.xiao.xyaax.ui.theme.UiTransitions
import cn.xiao.xyaax.ui.theme.applySystemBarIcons
import cn.xiao.xyaax.ui.theme.applySystemBarPadding

/**
 * 节次时间表管理：多校区/楼栋各一套「节次段 → 起止时间」，供今日页、小组件与提醒按课程校区取时间。
 * 支持手动编辑，也可粘贴「XX校区上课时间」文本由解析器自动识别。
 */
class TimeProfileActivity : AppCompatActivity() {
    private lateinit var palette: ThemePalette
    private lateinit var listContainer: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemePaletteProvider.applyNightMode(this)
        setTheme(ThemePaletteProvider.themeStyleFor(AppPreferences.getThemeColor(this)))
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_time_profiles)
        findViewById<View>(R.id.timeProfileRoot).applySystemBarPadding()
        palette = ThemePaletteProvider.fromContext(this)
        window.applySystemBarIcons(palette.isDark)
        UiTransitions.applyEnter(this)

        listContainer = findViewById(R.id.timeProfileList)
        findViewById<ImageView>(R.id.timeProfileBackBtn).setOnClickListener { finish() }
        findViewById<ImageView>(R.id.timeProfileAddBtn).setOnClickListener { editProfile(null) }
        findViewById<Button>(R.id.timeProfilePasteBtn).setOnClickListener { parseFromText() }
        findViewById<View>(R.id.timeProfileRoot).setBackgroundColor(palette.pageBackground)
        render()
    }

    override fun finish() {
        super.finish()
        UiTransitions.applyExit(this)
    }

    private fun render() {
        val profiles = TimeProfileStore.load(filesDir)
        val defaultId = TimeProfileStore.loadDefaultProfileId(filesDir) ?: TimeProfileStore.DEFAULT_PROFILE_ID
        val campusMap = TimeProfileStore.loadCampusMap(filesDir)
        listContainer.removeAllViews()
        profiles.forEach { profile ->
            listContainer.addView(buildRow(profile, profile.id == defaultId, campusMap))
        }
    }

    private fun buildRow(profile: SectionTimeProfile, isDefault: Boolean, campusMap: Map<String, String>): View {
        val density = resources.displayMetrics.density
        val aliases = campusMap.filterValues { it == profile.id }.keys.joinToString("、")
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = 14f * density
                setColor(palette.panelAltBackground)
                setStroke(maxOf(1, density.toInt()), ColorUtils.blendARGB(palette.panelAltBackground, palette.iconTint, 0.18f))
            }
            setPadding((14 * density).toInt(), (12 * density).toInt(), (14 * density).toInt(), (12 * density).toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (10 * density).toInt() }

            addView(TextView(this@TimeProfileActivity).apply {
                text = profile.name + if (isDefault) "  ·  当前显示" else ""
                setTextColor(palette.textPrimary)
                textSize = 15f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            addView(TextView(this@TimeProfileActivity).apply {
                text = profile.blocks.joinToString("  ") {
                    val label = if (it.startSection == it.endSection) "${it.startSection}节" else "${it.startSection}-${it.endSection}节"
                    "$label ${it.startTime}-${it.endTime}"
                }
                setTextColor(palette.textSecondary)
                textSize = 12f
            })
            if (aliases.isNotEmpty()) {
                addView(TextView(this@TimeProfileActivity).apply {
                    text = "校区简称：$aliases"
                    setTextColor(palette.textSecondary)
                    textSize = 12f
                })
            }
            addView(LinearLayout(this@TimeProfileActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(actionButton(R.string.time_profile_set_default) {
                    TimeProfileStore.saveDefaultProfileId(filesDir, profile.id)
                    render()
                    UiFeedback.showMessage(listContainer, getString(R.string.time_profile_default_set, profile.name), palette)
                })
                if (profile.id != TimeProfileStore.DEFAULT_PROFILE_ID) {
                    addView(actionButton(R.string.manage_rename) { editProfile(profile) })
                    addView(actionButton(R.string.manage_delete) { confirmDelete(profile) })
                }
            })
        }
    }

    private fun actionButton(textRes: Int, onClick: () -> Unit): Button = Button(this).apply {
        text = getString(textRes)
        textSize = 12f
        setTextColor(palette.iconTint)
        background = null
        minWidth = 0
        minimumWidth = 0
        setPadding(0, 0, (18 * resources.displayMetrics.density).toInt(), 0)
        setOnClickListener { onClick() }
    }

    private fun confirmDelete(profile: SectionTimeProfile) {
        AlertDialog.Builder(this)
            .setTitle(R.string.manage_delete)
            .setMessage(getString(R.string.time_profile_delete_message, profile.name))
            .setPositiveButton(R.string.manage_delete) { _, _ ->
                val remaining = TimeProfileStore.load(filesDir).filter { it.id != profile.id }
                val campusMap = TimeProfileStore.loadCampusMap(filesDir).filterValues { it != profile.id }
                TimeProfileStore.save(filesDir, remaining, campusMap, TimeProfileStore.DEFAULT_PROFILE_ID)
                render()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    /** 编辑格式：第一行名称，其余每行「起始节-结束节 起:止」，例如 1-2 08:30-10:05 */
    private fun editProfile(profile: SectionTimeProfile?) {
        val input = EditText(this).apply {
            setText(
                buildString {
                    append(profile?.name ?: "")
                    append('\n')
                    (profile?.blocks ?: listOf(
                        SectionBlock(1, 2, "08:30", "10:05"),
                        SectionBlock(3, 4, "10:20", "11:55"),
                        SectionBlock(5, 6, "13:30", "15:05"),
                        SectionBlock(7, 8, "15:20", "16:55"),
                        SectionBlock(9, 12, "18:00", "20:30")
                    )).forEach { block ->
                        append("${block.startSection}-${block.endSection} ${block.startTime}-${block.endTime}\n")
                    }
                }.trim()
            )
            setMinLines(6)
            gravity = android.view.Gravity.TOP
        }
        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((24 * resources.displayMetrics.density).toInt(), (16 * resources.displayMetrics.density).toInt(), (24 * resources.displayMetrics.density).toInt(), 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle(if (profile == null) R.string.time_profile_add else R.string.time_profile_edit)
            .setView(wrapper)
            .setPositiveButton(R.string.action_save) { _, _ ->
                val parsed = parseEditorText(input.text.toString())
                if (parsed == null) {
                    UiFeedback.showMessage(listContainer, getString(R.string.time_profile_format_error), palette)
                    return@setPositiveButton
                }
                val id = profile?.id ?: ("custom-" + System.currentTimeMillis())
                val updated = SectionTimeProfile(id, parsed.first, parsed.second)
                val others = TimeProfileStore.load(filesDir).filter { it.id != id && it.id != TimeProfileStore.DEFAULT_PROFILE_ID }
                TimeProfileStore.save(
                    filesDir = filesDir,
                    profiles = others + updated,
                    campusMap = TimeProfileStore.loadCampusMap(filesDir),
                    defaultProfileId = TimeProfileStore.loadDefaultProfileId(filesDir)
                )
                render()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun parseEditorText(text: String): Pair<String, List<SectionBlock>>? {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.size < 2) return null
        val name = lines.first()
        val blocks = lines.drop(1).mapNotNull { line ->
            val sections = Regex("""(\d{1,2})\s*[-—–~～]\s*(\d{1,2})""").find(line) ?: return@mapNotNull null
            val times = Regex("""(\d{1,2}:\d{2})\s*[-—–~～]\s*(\d{1,2}:\d{2})""").find(line) ?: return@mapNotNull null
            val start = sections.groupValues[1].toInt()
            val end = sections.groupValues[2].toInt()
            SectionBlock(
                startSection = minOf(start, end),
                endSection = maxOf(start, end),
                startTime = times.groupValues[1],
                endTime = times.groupValues[2]
            )
        }
        if (blocks.isEmpty()) return null
        return name to blocks
    }

    /** 粘贴「XX校区上课时间」文本，交给与导入向导相同的解析器识别多套时间表。 */
    private fun parseFromText() {
        val input = EditText(this).apply {
            hint = getString(R.string.time_profile_parse_text_hint)
            setMinLines(6)
            gravity = android.view.Gravity.TOP
        }
        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((24 * resources.displayMetrics.density).toInt(), (16 * resources.displayMetrics.density).toInt(), (24 * resources.displayMetrics.density).toInt(), 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.time_profile_parse_text)
            .setView(wrapper)
            .setPositiveButton(R.string.import_wizard_title) { _, _ ->
                val profiles = TimeTableTextParser.parse(input.text.toString())
                if (profiles.isEmpty()) {
                    UiFeedback.showMessage(listContainer, getString(R.string.time_profile_parse_failed), palette)
                    return@setPositiveButton
                }
                val existing = TimeProfileStore.load(filesDir)
                    .filter { it.id != TimeProfileStore.DEFAULT_PROFILE_ID }
                    .filterNot { old -> profiles.any { it.id == old.id || it.name == old.name } }
                TimeProfileStore.save(
                    filesDir = filesDir,
                    profiles = existing + profiles,
                    campusMap = TimeProfileStore.loadCampusMap(filesDir),
                    defaultProfileId = TimeProfileStore.loadDefaultProfileId(filesDir)
                )
                render()
                UiFeedback.showMessage(listContainer, getString(R.string.time_profile_parsed, profiles.size), palette)
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }
}
