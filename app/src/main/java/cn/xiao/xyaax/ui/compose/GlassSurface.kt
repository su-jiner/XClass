package cn.xiao.xyaax.ui.compose

import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.view.View
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import cn.xiao.xyaax.ui.theme.ThemePalette

/**
 * 玻璃表面：Android 12+ 使用 RenderEffect 实时模糊（等效 SleepDown GlassSurface），
 * 低版本降级为半透明纯色 + 边框，保证视觉一致。
 */
object Glass {

    /** 给传统 View 附加玻璃模糊（API 31+），返回是否生效。 */
    fun applyBlur(view: View, radiusPx: Float, dark: Boolean): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return runCatching {
                view.setRenderEffect(
                    RenderEffect.createBlurEffect(radiusPx, radiusPx, Shader.TileMode.CLAMP)
                )
                true
            }.getOrDefault(false)
        }
        return false
    }

    /** Compose 侧：玻璃容器背景色（半透明 + 主题色叠加），供 Compose 表面使用。 */
    fun containerColor(palette: ThemePalette, blurStrength: Float): Color {
        val base = if (palette.isDark) Color(0xE6121216) else Color(0xE6F2F0EC)
        val tint = Color(palette.buttonBackground).copy(alpha = 0.08f * blurStrength)
        return base.copy(alpha = (0.72f + 0.18f * blurStrength).coerceIn(0f, 1f))
    }

    /** 胶囊底栏容器圆角。 */
    val barCorner = 32.dp
}
