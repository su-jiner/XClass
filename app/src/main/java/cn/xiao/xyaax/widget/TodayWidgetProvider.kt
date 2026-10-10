package cn.xiao.xyaax.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.view.View
import android.widget.RemoteViews
import cn.xiao.xyaax.R

/**
 * 今日课程列表组件：显示今天要上的课（含校区与节次时间）。
 * 每 30 分钟系统刷新一次；数据变化与课前/课后边界由 WidgetSupport 触发更新。
 */
class TodayWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val data = WidgetSupport.load(context)
        appWidgetIds.forEach { id ->
            appWidgetManager.updateAppWidget(id, buildViews(context, data))
        }
    }

    companion object {
        fun updateAll(context: Context) = WidgetSupport.updateAll(context)

        fun buildViews(context: Context, data: WidgetSupport.WidgetData): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_today)
            views.setTextViewText(R.id.widgetTitle, context.getString(R.string.widget_today_title))
            views.setTextViewText(R.id.widgetSubtitle, data.subtitle)
            views.removeAllViews(R.id.widgetCourseList)
            val items = data.today.items
            if (items.isEmpty()) {
                views.setViewVisibility(R.id.widgetEmpty, View.VISIBLE)
            } else {
                views.setViewVisibility(R.id.widgetEmpty, View.GONE)
                items.take(6).forEach { item ->
                    val row = RemoteViews(context.packageName, R.layout.widget_course_item)
                    row.setTextViewText(R.id.widgetItemName, item.courseName)
                    val detail = buildString {
                        append("第").append(item.startSection).append('-').append(item.endSection).append("节 ")
                        append(item.startTime).append('-').append(item.endTime)
                        if (item.isOngoing) append(" · 进行中")
                        listOf(item.campusName, item.location).filter { it.isNotBlank() }.forEach {
                            append(" · ").append(it)
                        }
                    }
                    row.setTextViewText(R.id.widgetItemDetail, detail)
                    views.addView(R.id.widgetCourseList, row)
                }
            }
            views.setOnClickPendingIntent(R.id.widgetRoot, WidgetSupport.openApp(context, 10))
            return views
        }
    }
}
