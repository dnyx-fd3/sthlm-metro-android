package se.dnyx.sthlmmetro

import android.appwidget.AppWidgetManager
import android.content.Context
import android.os.Build
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

class WidgetRefreshWorker(
    appContext: Context,
    params: WorkerParameters
) : Worker(appContext, params) {

    override fun doWork(): Result {
        val widgetId = inputData.getInt(KEY_WIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return Result.success()
        val trigger = inputData.getString(KEY_TRIGGER) ?: "button"

        val manager = AppWidgetManager.getInstance(applicationContext)
        val outcome = MetroWidgetProvider.refreshWidgetBlocking(
            context = applicationContext,
            appWidgetManager = manager,
            appWidgetId = widgetId,
            trigger = trigger,
            isFinalAttempt = runAttemptCount >= MAX_RETRIES
        )

        return when (outcome) {
            MetroWidgetProvider.RefreshOutcome.RETRYABLE_NETWORK -> {
                if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.failure()
            }
            MetroWidgetProvider.RefreshOutcome.DONE -> Result.success()
        }
    }

    companion object {
        private const val KEY_WIDGET_ID = "widget_id"
        private const val KEY_TRIGGER = "trigger"
        private const val MAX_RETRIES = 2

        fun enqueue(context: Context, appWidgetId: Int, trigger: String) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val builder = OneTimeWorkRequestBuilder<WidgetRefreshWorker>()
                .setConstraints(constraints)
                .setInputData(
                    workDataOf(
                        KEY_WIDGET_ID to appWidgetId,
                        KEY_TRIGGER to trigger
                    )
                )
                .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.SECONDS)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                builder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            }

            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                "tradar_refresh_$appWidgetId",
                ExistingWorkPolicy.REPLACE,
                builder.build()
            )
        }
    }
}
