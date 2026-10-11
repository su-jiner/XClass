package cn.xiao.xyaax.importing

import android.content.Context
import android.graphics.Color
import androidx.browser.customtabs.CustomTabsIntent
import androidx.browser.customtabs.CustomTabsSession
import cn.xiao.xyaax.ui.theme.ThemePaletteProvider

/**
 * Custom Tabs 封装（等效 SleepDown CustomTabs）：用系统 Chrome Custom Tabs 打开教务系统等链接。
 */
object CustomTabs {
    private var session: CustomTabsSession? = null

    fun open(context: Context, url: String, toolbarColor: Int? = null) {
        if (url.isBlank()) return
        val palette = ThemePaletteProvider.fromContext(context)
        val color = toolbarColor ?: palette.buttonBackground
        val builder = CustomTabsIntent.Builder()
            .setToolbarColor(color)
            .setShowTitle(true)
            .enableUrlBarHiding()
            .setColorScheme(
                if (palette.isDark) CustomTabsIntent.COLOR_SCHEME_DARK
                else CustomTabsIntent.COLOR_SCHEME_LIGHT
            )
        session?.let { builder.setSession(it) }
        val intent = builder.build()
        intent.launchUrl(context, android.net.Uri.parse(url))
    }
}
