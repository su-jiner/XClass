package cn.xiao.xyaax.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import cn.xiao.xyaax.MainActivity
import cn.xiao.xyaax.data.ImportedScheduleStorage
import cn.xiao.xyaax.domain.TimeProfileResolver
import cn.xiao.xyaax.domain.TodaySnapshot
import cn.xiao.xyaax.domain.WeekGridSnapshot
import cn.xiao.xyaax.domain.WidgetSnapshotBuilder
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 四个小组件共用的数据加载、点击跳转与刷新调度。 */
object WidgetSupport {

    data class WidgetData(
        val today: TodaySnapshot,
        val grid: WeekGridSnapshot,
        val subtitle: String
    )

    fun load(context: Context, showNonCurrentWeek: Boolean = true): WidgetData {
        val files = context.filesDir
        val courses = runCatching { ImportedScheduleStorage.loadActiveCourses(files) }.getOrDefault(emptyList())
        val semesterStart = runCatching { ImportedScheduleStorage.getActiveSemesterStartDate(files) }
            .getOrDefault(LocalDate.now())
        val profiles = TimeProfileResolver.loadProfiles(files)
        val now = LocalDateTime.now()
        val today = WidgetSnapshotBuilder.today(courses, semesterStart, profiles, now = now, showNonCurrentWeek = showNonCurrentWeek)
        val grid = WidgetSnapshotBuilder.weekGrid(courses, semesterStart, today.week)
        val subtitle = "${now.format(DateTimeFormatter.ofPattern("M月d日"))} 周${weekdayLabel(now)} · 第${today.week}周"
        return WidgetData(today, grid, subtitle)
    }

    private fun weekdayLabel(now: LocalDateTime): String =
        listOf("一", "二", "三", "四", "五", "六", "日")[now.dayOfWeek.value - 1]

    fun openApp(context: Context, requestCode: Int = 0): PendingIntent =
        PendingIntent.getActivity(
            context,
            requestCode,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    /** 刷新全部组件；数据变化与定时触发都走这里。 */
    fun updateAll(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        val data = runCatching { load(context) }.getOrNull() ?: return
        listOf(
            TodayWidgetProvider::class.java,
            NextCourseWidgetProvider::class.java,
            TodayTimelineWidgetProvider::class.java,
            WeekGridWidgetProvider::class.java
        ).forEach { provider ->
            val ids = manager.getAppWidgetIds(ComponentName(context, provider))
            if (ids.isEmpty()) return@forEach
            ids.forEach { id ->
                val views = when (provider) {
                    TodayWidgetProvider::class.java -> TodayWidgetProvider.buildViews(context, data)
                    NextCourseWidgetProvider::class.java -> NextCourseWidgetProvider.buildViews(context, data)
                    TodayTimelineWidgetProvider::class.java -> TodayTimelineWidgetProvider.buildViews(context, data)
                    else -> WeekGridWidgetProvider.buildViews(context, data)
                }
                manager.updateAppWidget(id, views)
            }
        }
        scheduleBoundaryRefresh(context, data)
    }

    /**
     * 课前 5 分钟与下课时各触发一次刷新（不申请精确闹钟权限，用 5 分钟窗口的模糊闹钟）。
     */
    fun scheduleBoundaryRefresh(context: Context, data: WidgetData) {
        val now = LocalDateTime.now()
        val candidates = buildList {
            data.today.ongoingCourse?.let { add(it.endTime) }
            data.today.nextCourse?.let { add(it.startTime) }
        }.mapNotNull { WidgetSnapshotBuilder.parseMinutes(it) }
            .map { minutes -> now.toLocalDate().atTime(minutes / 60, minutes % 60) }
            .map { time -> if (time.isBefore(now)) time.plusDays(1) else time }
            .sorted()

        val target = candidates.firstOrNull() ?: return
        val triggerAt = target.minusMinutes(5).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        if (triggerAt <= System.currentTimeMillis()) return

        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        val intent = Intent(context, WidgetRefreshReceiver::class.java)
        val pending = PendingIntent.getBroadcast(
            context,
            2001,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        runCatching {
            alarmManager.setWindow(AlarmManager.RTC, triggerAt, 5 * 60 * 1000L, pending)
        }
    }
}

/** 课前/课后边界刷新触发器。 */
class WidgetRefreshReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        WidgetSupport.updateAll(context)
    }
}
