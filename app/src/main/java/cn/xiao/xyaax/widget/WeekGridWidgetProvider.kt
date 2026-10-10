package cn.xiao.xyaax.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.widget.RemoteViews
import cn.xiao.xyaax.R

/** 本周课表微缩：7 列 × 6 行的网格缩略，点击进入课表页。 */
class WeekGridWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val data = WidgetSupport.load(context)
        appWidgetIds.forEach { id -> appWidgetManager.updateAppWidget(id, buildViews(context, data)) }
    }

    companion object {
        fun buildViews(context: Context, data: WidgetSupport.WidgetData): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_week_grid)
            views.setTextViewText(R.id.gridSubtitle, "第${data.grid.week}周 / 共${data.grid.totalWeeks}周")
            views.removeAllViews(R.id.gridHeader)
            val header = RemoteViews(context.packageName, R.layout.widget_week_grid_row)
            header.removeAllViews(R.id.gridRowCells)
            data.grid.columnLabels.forEachIndexed { index, label ->
                val cell = RemoteViews(context.packageName, R.layout.widget_week_grid_cell)
                cell.setTextViewText(R.id.gridCellText, label)
                if (index == data.grid.currentWeekdayIndex) {
                    cell.setInt(R.id.gridCellText, "setBackgroundColor", 0x22FF9800)
                }
                header.addView(R.id.gridRowCells, cell)
            }
            views.addView(R.id.gridHeader, header)

            views.removeAllViews(R.id.gridBody)
            data.grid.cells.forEachIndexed { rowIndex, row ->
                val rowView = RemoteViews(context.packageName, R.layout.widget_week_grid_row)
                rowView.removeAllViews(R.id.gridRowCells)
                row.forEachIndexed { columnIndex, text ->
                    val cell = RemoteViews(context.packageName, R.layout.widget_week_grid_cell)
                    cell.setTextViewText(R.id.gridCellText, text)
                    val onClick = WidgetSupport.openApp(context, 100 + rowIndex * 10 + columnIndex)
                    cell.setOnClickPendingIntent(R.id.gridCellText, onClick)
                    rowView.addView(R.id.gridRowCells, cell)
                }
                views.addView(R.id.gridBody, rowView)
            }
            views.setOnClickPendingIntent(R.id.gridRoot, WidgetSupport.openApp(context, 13))
            return views
        }
    }
}
