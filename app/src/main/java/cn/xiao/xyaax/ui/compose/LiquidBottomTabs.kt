package cn.xiao.xyaax.ui.compose

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.xiao.xyaax.ui.theme.ThemePalette

/** 胶囊底栏数据项（由调用方传入，不写死）。 */
data class LiquidTabItem(
    val navId: Int,
    val icon: ImageVector,
    val label: String,
)

/**
 * 液态胶囊底栏（等效 SleepDown LiquidBottomTabs）：
 * 玻璃胶囊容器 + 圆角 Tab 胶囊；选中态颜色渐变过渡，按压有缩放反馈动画。
 */
@Composable
fun LiquidBottomTabs(
    items: List<LiquidTabItem>,
    selectedNavId: Int,
    onSelect: (Int) -> Unit,
    palette: ThemePalette,
    modifier: Modifier = Modifier,
    glassEnabled: Boolean = true,
    blurStrength: Float = 1f,
) {
    val selectedIndex = items.indexOfFirst { it.navId == selectedNavId }.coerceAtLeast(0)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .clip(RoundedCornerShape(32.dp))
            .background(
                if (glassEnabled) Glass.containerColor(palette, blurStrength)
                else Color(if (palette.isDark) 0xFF1A1A1E else 0xFFF2F0EC)
            )
            .border(1.dp, Color(palette.textSecondary).copy(alpha = 0.12f), RoundedCornerShape(32.dp))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items.forEachIndexed { index, item ->
                val selected = index == selectedIndex
                TabCapsule(
                    item = item,
                    selected = selected,
                    palette = palette,
                    modifier = Modifier.weight(1f),
                    onClick = { onSelect(item.navId) },
                )
            }
        }
    }
}

@Composable
private fun TabCapsule(
    item: LiquidTabItem,
    selected: Boolean,
    palette: ThemePalette,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()

    // 按压反馈：按下时整体轻微缩小
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.9f else 1f,
        animationSpec = tween(durationMillis = 140),
        label = "tabPressScale",
    )
    // 选中态背景渐变过渡
    val bgColor by animateColorAsState(
        targetValue = if (selected) Color(palette.buttonBackground).copy(alpha = 0.92f) else Color.Transparent,
        animationSpec = tween(durationMillis = 240),
        label = "tabBg",
    )
    // 前景色渐变过渡
    val fgColor by animateColorAsState(
        targetValue = if (selected) Color(palette.buttonText) else Color(palette.textSecondary),
        animationSpec = tween(durationMillis = 240),
        label = "tabFg",
    )
    // 选中时图标轻微放大（弹性动画）
    val iconScale by animateFloatAsState(
        targetValue = if (selected) 1.12f else 1f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = 420f),
        label = "tabIconScale",
    )

    Column(
        modifier = modifier
            .scale(scale)
            .clip(RoundedCornerShape(26.dp))
            .background(bgColor)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = item.icon,
            contentDescription = item.label,
            tint = fgColor,
            modifier = Modifier
                .size(20.dp)
                .scale(iconScale),
        )
        Text(
            text = item.label,
            color = fgColor,
            fontSize = 10.sp,
        )
    }
}
