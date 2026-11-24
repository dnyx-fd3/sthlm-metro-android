package se.dnyx.sthlmmetro

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters

class WidgetRefreshWorker(
    appContext: Context,
    params: WorkerParameters
) : Worker(appContext, params) {

    override fun doWork(): Result {
        val manager = AppWidgetManager.getInstance(applicationContext)
        val ids = manager.getAppWidgetIds(ComponentName(applicationContext, MetroWidgetProvider::class.java))
        for (id in ids) {
            MetroWidgetProvider.refreshWidgetBlocking(applicationContext, manager, id)
        }
        return Result.success()
    }
}
