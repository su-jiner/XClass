package cn.xiao.xyaax

import android.graphics.ImageDecoder
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.core.view.WindowInsetsControllerCompat
import cn.xiao.xyaax.data.AppPreferences
import cn.xiao.xyaax.data.ScheduleRepository
import cn.xiao.xyaax.ui.compose.LiquidBottomTabs
import cn.xiao.xyaax.ui.settings.SettingsFragment
import cn.xiao.xyaax.ui.timetable.TimetableFragment
import cn.xiao.xyaax.ui.theme.ThemePaletteProvider
import cn.xiao.xyaax.ui.today.TodayScheduleFragment
import cn.xiao.xyaax.ui.tools.ToolsFragment

class MainActivity : AppCompatActivity() {
    private lateinit var rootContainer: FrameLayout
    private lateinit var backgroundImage: ImageView
    private var currentNavId = 0
    private var composeBar: androidx.compose.ui.platform.ComposeView? = null

    /** 底栏顺序，用于判断切页方向（往右的 Tab 从右侧滑入）。 */
    private val navOrder = listOf(R.id.nav_today, R.id.nav_timetable, R.id.nav_tools, R.id.nav_settings)

    private var cachedFragments = mutableMapOf<Int, Fragment>()

    private fun fragmentFor(navId: Int): Fragment = cachedFragments.getOrPut(navId) {
        when (navId) {
            R.id.nav_today -> TodayScheduleFragment()
            R.id.nav_tools -> ToolsFragment()
            R.id.nav_settings -> SettingsFragment()
            else -> TimetableFragment()
        }
    }

    private fun switchTo(navId: Int) {
        if (navId == currentNavId) return
        val fromIndex = navOrder.indexOf(currentNavId)
        val toIndex = navOrder.indexOf(navId)
        val forward = fromIndex < 0 || toIndex >= fromIndex
        val transaction = supportFragmentManager.beginTransaction()
            .setCustomAnimations(
                if (forward) R.anim.nav_enter_right else R.anim.nav_enter_left,
                if (forward) R.anim.nav_exit_left else R.anim.nav_exit_right
            )
        cachedFragments[currentNavId]?.takeIf { it.isAdded }?.let { transaction.hide(it) }
        val target = fragmentFor(navId)
        if (target.isAdded) transaction.show(target) else transaction.add(R.id.mainContainer, target, navId.toString())
        transaction.commit()
        currentNavId = navId
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemePaletteProvider.applyNightMode(this)
        setTheme(ThemePaletteProvider.themeStyleFor(AppPreferences.getThemeColor(this)))
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        rootContainer = findViewById(R.id.rootContainer)
        backgroundImage = findViewById(R.id.backgroundImage)
        applyUserAppearance()
        refreshCustomBackground()
        ScheduleRepository.refresh(this)

        if (savedInstanceState != null) {
            navOrder.forEach { navId ->
                supportFragmentManager.findFragmentByTag(navId.toString())?.let { cachedFragments[navId] = it }
            }
            currentNavId = savedInstanceState.getInt(KEY_NAV_ID, R.id.nav_timetable)
        }

        if (savedInstanceState == null) {
            val defaultPage = AppPreferences.getDefaultOpenPage(this)
            currentNavId = if (defaultPage == AppPreferences.PAGE_TODAY) R.id.nav_today else R.id.nav_timetable
            supportFragmentManager.beginTransaction()
                .add(R.id.mainContainer, fragmentFor(currentNavId), currentNavId.toString())
                .commit()
        }

        // 液态胶囊底栏（Compose）
        val composeView = findViewById<androidx.compose.ui.platform.ComposeView>(R.id.composeBottomBar)
        composeBar = composeView
        bindComposeBar()
    }

    private fun bindComposeBar() {
        val composeView = composeBar ?: return
        composeView.setContent {
            LiquidBottomTabs(
                selectedNavId = currentNavId,
                onSelect = { navId ->
                    if (navId != currentNavId) {
                        switchTo(navId)
                    }
                },
                palette = ThemePaletteProvider.fromContext(this),
                glassEnabled = AppPreferences.getGlassEnabled(this),
                blurStrength = AppPreferences.getGlassBlur(this),
            )
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_NAV_ID, currentNavId)
    }

    companion object {
        private const val KEY_NAV_ID = "xclass_current_nav_id"
    }

    fun refreshCustomBackground() {
        val uri = AppPreferences.getCustomBackgroundUri(this)?.let { runCatching { Uri.parse(it) }.getOrNull() }
        if (uri == null) {
            backgroundImage.setImageDrawable(null)
            backgroundImage.visibility = ImageView.GONE
            return
        }

        val bitmap = runCatching {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                val source = ImageDecoder.createSource(contentResolver, uri)
                ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    val targetW = resources.displayMetrics.widthPixels.coerceAtLeast(1)
                    val targetH = resources.displayMetrics.heightPixels.coerceAtLeast(1)
                    val srcW = info.size.width.coerceAtLeast(1)
                    val srcH = info.size.height.coerceAtLeast(1)
                    val sample = maxOf(srcW / targetW, srcH / targetH).coerceAtLeast(1)
                    decoder.setTargetSampleSize(sample)
                }
            } else {
                @Suppress("DEPRECATION")
                contentResolver.openInputStream(uri)?.use { stream ->
                    val options = BitmapFactory.Options().apply {
                        inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
                    }
                    BitmapFactory.decodeStream(stream, null, options)
                }
            }
        }.getOrNull()

        if (bitmap == null) {
            backgroundImage.setImageDrawable(null)
            backgroundImage.visibility = ImageView.GONE
            return
        }

        backgroundImage.setImageBitmap(bitmap)
        backgroundImage.visibility = ImageView.VISIBLE
    }

    fun applyUserAppearance() {
        val palette = ThemePaletteProvider.fromContext(this)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = !palette.isDark
            isAppearanceLightNavigationBars = !palette.isDark
        }
        rootContainer.setBackgroundColor(palette.pageBackground)
        bindComposeBar()
    }
}
