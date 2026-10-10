package cn.xiao.xyaax.ui.theme

import android.app.Activity
import cn.xiao.xyaax.R

/** 统一子页面（工具页 / 课表管理 / 导入向导等）的左右转场，与底栏切页保持一致的节奏。 */
object UiTransitions {
    fun applyEnter(activity: Activity) {
        @Suppress("DEPRECATION")
        activity.overridePendingTransition(R.anim.activity_enter, R.anim.activity_exit)
    }

    fun applyExit(activity: Activity) {
        @Suppress("DEPRECATION")
        activity.overridePendingTransition(R.anim.nav_enter_left, R.anim.nav_exit_right)
    }
}
