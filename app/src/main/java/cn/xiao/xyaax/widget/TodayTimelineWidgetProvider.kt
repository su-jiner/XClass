package cn.xiao.xyaax.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.view.View
import android.widget.RemoteViews
import cn.xiao.xyaax.R

/** 今日时间轴：4x2，按节次纵向排列，进行中高亮。 */
class TodayTimelineWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val data = WidgetSupport.load(context)
        appWidgetIds.forEach { id -> appWidgetManager.updateAppWidget(id, buildViews(context, data)) }
    }

    companion object {
        fun buildViews(context: Context, data: WidgetSupport.WidgetData): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_timeline)
            views.setTextViewText(R.id.timelineSubtitle, data.subtitle)
            views.removeAllViews(R.id.timelineList)
            val items = data.today.items
            if (items.isEmpty()) {
                views.setViewVisibility(R.id.timelineEmpty, View.VISIBLE)
            } else {
                views.setViewVisibility(R.id.timelineEmpty, View.GONE)
                items.take(8).forEach { item ->
                    val row = RemoteViews(context.packageName, R.layout.widget_timeline_item)
                    row.setTextViewText(R.id.timelineItemTime, item.startTime)
                    row.setTextViewText(R.id.timelineItemEnd, item.endTime)
                    row.setTextViewText(R.id.timelineItemName, item.courseName)
                    val detail = listOf(
                        "第${item.startSection}-${item.endSection}节",
                        item.campusName,
                        item.location
                    ).filter { it.isNotBlank() }.joinToString(" · ")
                    row.setTextViewText(R.id.timelineItemDetail, detail)
                    row.setViewVisibility(R.id.timelineItemBadge, if (item.isOngoing) View.VISIBLE else View.GONE)
                    views.addView(R.id.timelineList, row)
                }
            }
            views.setOnClickPendingIntent(R.id.timelineRoot, WidgetSupport.openApp(context, 12))
            return views
        }
    }
}
