package cn.xiao.xyaax.ui.compose

import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.xiao.xyaax.R
import cn.xiao.xyaax.ui.theme.ThemePalette

/** 胶囊底栏数据项。 */
data class LiquidTabItem(
    val navId: Int,
    val icon: ImageVector,
    val label: String,
)

/**
 * 液态胶囊底栏（等效 SleepDown LiquidBottomTabs）：
 * 玻璃胶囊容器 + 单个滑动选中指示器 + 圆角 Tab 胶囊。
 */
@Composable
fun LiquidBottomTabs(
    selectedNavId: Int,
    onSelect: (Int) -> Unit,
    palette: ThemePalette,
    modifier: Modifier = Modifier,
    glassEnabled: Boolean = true,
    blurStrength: Float = 1f,
) {
    val labels = listOf(
        stringResource(R.string.nav_today),
        stringResource(R.string.nav_timetable),
        stringResource(R.string.nav_tools),
        stringResource(R.string.nav_settings),
    )
    val items = listOf(
        LiquidTabItem(R.id.nav_today, Icons.Outlined.Schedule, labels[0]),
        LiquidTabItem(R.id.nav_timetable, Icons.Outlined.CalendarMonth, labels[1]),
        LiquidTabItem(R.id.nav_tools, Icons.Outlined.GridView, labels[2]),
        LiquidTabItem(R.id.nav_settings, Icons.Outlined.Settings, labels[3]),
    )
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
    val bg = if (selected) {
        Color(palette.buttonBackground).copy(alpha = 0.92f)
    } else {
        Color.Transparent
    }
    val fg = if (selected) Color(palette.buttonText) else Color(palette.textSecondary)
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(26.dp))
            .background(bg)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
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
            tint = fg,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = item.label,
            color = fg,
            fontSize = 10.sp,
        )
    }
}
