package cn.xiao.xyaax.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.view.View
import android.widget.RemoteViews
import cn.xiao.xyaax.R
import cn.xiao.xyaax.domain.WidgetSnapshotBuilder

/** 下一节课：1x1 / 2x1 紧凑组件，显示课程名、时间与倒计时。 */
class NextCourseWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val data = WidgetSupport.load(context)
        appWidgetIds.forEach { id -> appWidgetManager.updateAppWidget(id, buildViews(context, data)) }
    }

    companion object {
        fun buildViews(context: Context, data: WidgetSupport.WidgetData): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_next_course)
            val course = data.today.ongoingCourse ?: data.today.nextCourse
            if (course == null) {
                views.setViewVisibility(R.id.nextCourseEmpty, View.VISIBLE)
                views.setViewVisibility(R.id.nextCourseBody, View.GONE)
                views.setTextViewText(R.id.nextCourseEmpty, context.getString(R.string.widget_no_courses))
            } else {
                views.setViewVisibility(R.id.nextCourseEmpty, View.GONE)
                views.setViewVisibility(R.id.nextCourseBody, View.VISIBLE)
                views.setTextViewText(R.id.nextCourseName, course.courseName)
                views.setTextViewText(
                    R.id.nextCourseTime,
                    "第${course.startSection}-${course.endSection}节 ${course.startTime}-${course.endTime}"
                )
                views.setTextViewText(
                    R.id.nextCourseCountdown,
                    if (course.isOngoing) "进行中 · ${course.endTime} 下课"
                    else WidgetSnapshotBuilder.countdownText(course.minutesUntilStart)
                )
                val location = listOf(course.campusName, course.location).filter { it.isNotBlank() }.joinToString(" ")
                views.setTextViewText(R.id.nextCourseLocation, location)
            }
            views.setOnClickPendingIntent(R.id.nextCourseRoot, WidgetSupport.openApp(context, 11))
            return views
        }
    }
}
