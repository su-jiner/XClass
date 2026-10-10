package cn.xiao.xyaax

import android.graphics.ImageDecoder
import android.graphics.BitmapFactory
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.bottomnavigation.BottomNavigationView
import cn.xiao.xyaax.data.AppPreferences
import cn.xiao.xyaax.data.ScheduleRepository
import cn.xiao.xyaax.ui.settings.SettingsFragment
import cn.xiao.xyaax.ui.timetable.TimetableFragment
import cn.xiao.xyaax.ui.theme.ThemePaletteProvider
import cn.xiao.xyaax.ui.today.TodayScheduleFragment
import cn.xiao.xyaax.ui.tools.ToolsFragment

class MainActivity : AppCompatActivity() {
    private lateinit var rootContainer: FrameLayout
    private lateinit var backgroundImage: ImageView
    private lateinit var bottomNav: BottomNavigationView

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemePaletteProvider.applyNightMode(this)
        setTheme(ThemePaletteProvider.themeStyleFor(AppPreferences.getThemeColor(this)))
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        rootContainer = findViewById(R.id.rootContainer)
        backgroundImage = findViewById(R.id.backgroundImage)
        bottomNav = findViewById(R.id.bottomNav)
        applyUserAppearance()
        refreshCustomBackground()
        ScheduleRepository.refresh(this)

        if (savedInstanceState == null) {
            val defaultPage = AppPreferences.getDefaultOpenPage(this)
            if (defaultPage == AppPreferences.PAGE_TODAY) {
                supportFragmentManager.beginTransaction()
                    .replace(R.id.mainContainer, TodayScheduleFragment())
                    .commit()
                bottomNav.selectedItemId = R.id.nav_today
            } else {
                supportFragmentManager.beginTransaction()
                    .replace(R.id.mainContainer, TimetableFragment())
                    .commit()
                bottomNav.selectedItemId = R.id.nav_timetable
            }
        }

        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_timetable -> {
                    supportFragmentManager.beginTransaction()
                        .replace(R.id.mainContainer, TimetableFragment())
                        .commit()
                    true
                }

                R.id.nav_today -> {
                    supportFragmentManager.beginTransaction()
                        .replace(R.id.mainContainer, TodayScheduleFragment())
                        .commit()
                    true
                }

                R.id.nav_tools -> {
                    supportFragmentManager.beginTransaction()
                        .replace(R.id.mainContainer, ToolsFragment())
                        .commit()
                    true
                }

                R.id.nav_settings -> {
                    supportFragmentManager.beginTransaction()
                        .replace(R.id.mainContainer, SettingsFragment())
                        .commit()
                    true
                }

                else -> false
            }
        }
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
        bottomNav.setBackgroundColor(palette.navBackground)
        val itemColors = ColorStateList(
            arrayOf(
                intArrayOf(android.R.attr.state_checked),
                intArrayOf()
            ),
            intArrayOf(palette.buttonText, palette.textSecondary)
        )
        bottomNav.itemIconTintList = itemColors
        bottomNav.itemTextColor = itemColors
    }
}
