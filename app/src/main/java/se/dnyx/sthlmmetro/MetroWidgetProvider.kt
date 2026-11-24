package se.dnyx.sthlmmetro

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.view.View
import android.widget.RemoteViews
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MetroWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        ensurePeriodicWork(context)
        val pendingResult = goAsync()
        executor.execute {
            try {
                for (widgetId in appWidgetIds) {
                    refreshWidgetBlocking(context, appWidgetManager, widgetId)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH) {
            val widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val ids = if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                intArrayOf(widgetId)
            } else {
                appWidgetManager.getAppWidgetIds(ComponentName(context, MetroWidgetProvider::class.java))
            }
            if (ids.isEmpty()) return

            // Show instant feedback on tap
            for (id in ids) {
                val cfg = WidgetPrefs.load(context, id)
                val loadingViews = buildBaseViews(context, id, cfg)
                loadingViews.setTextViewText(R.id.widget_updated_at, "Refreshing live SL…")
                appWidgetManager.partiallyUpdateAppWidget(id, loadingViews)
            }

            val pendingResult = goAsync()
            executor.execute {
                try {
                    for (id in ids) {
                        refreshWidgetBlocking(context, appWidgetManager, id)
                    }
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        for (id in appWidgetIds) {
            WidgetPrefs.delete(context, id)
        }
    }

    override fun onDisabled(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }

    companion object {
        const val ACTION_REFRESH = "se.dnyx.sthlmmetro.ACTION_REFRESH_WIDGET"
        private const val WORK_NAME = "sthlm_metro_widget_periodic_refresh"
        private val executor = Executors.newSingleThreadExecutor()
        private val timeFmt = DateTimeFormatter.ofPattern("HH:mm")

        private val ROW_IDS = intArrayOf(R.id.row_0, R.id.row_1, R.id.row_2, R.id.row_3)
        private val BADGE_IDS = intArrayOf(R.id.row_0_badge, R.id.row_1_badge, R.id.row_2_badge, R.id.row_3_badge)
        private val DEST_IDS = intArrayOf(R.id.row_0_dest, R.id.row_1_dest, R.id.row_2_dest, R.id.row_3_dest)
        private val SUB_IDS = intArrayOf(R.id.row_0_sub, R.id.row_1_sub, R.id.row_2_sub, R.id.row_3_sub)
        private val ETA_IDS = intArrayOf(R.id.row_0_eta, R.id.row_1_eta, R.id.row_2_eta, R.id.row_3_eta)
        private val CLOCK_IDS = intArrayOf(R.id.row_0_clock, R.id.row_1_clock, R.id.row_2_clock, R.id.row_3_clock)

        fun ensurePeriodicWork(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val req = PeriodicWorkRequestBuilder<WidgetRefreshWorker>(30, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                req
            )
        }

        fun triggerAsyncRefresh(context: Context, appWidgetId: Int) {
            ensurePeriodicWork(context)
            val appWidgetManager = AppWidgetManager.getInstance(context)
            executor.execute {
                refreshWidgetBlocking(context, appWidgetManager, appWidgetId)
            }
        }

        fun refreshWidgetBlocking(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
            val cfg = WidgetPrefs.load(context, appWidgetId)
            val views = buildBaseViews(context, appWidgetId, cfg)

            try {
                val departures = SlApiClient.fetchDepartures(cfg.siteId, cfg.includeBuses, limit = 4)
                val nowStr = LocalTime.now().format(timeFmt)
                views.setTextViewText(R.id.widget_updated_at, "LIVE SL · Updated $nowStr")

                if (departures.isEmpty()) {
                    views.setViewVisibility(R.id.widget_status_text, View.VISIBLE)
                    views.setTextViewText(R.id.widget_status_text, "No upcoming departures reported.")
                    for (i in 0 until 4) {
                        views.setViewVisibility(ROW_IDS[i], View.GONE)
                    }
                } else {
                    views.setViewVisibility(R.id.widget_status_text, View.GONE)
                    for (i in 0 until 4) {
                        if (i < departures.size) {
                            val d = departures[i]
                            views.setViewVisibility(ROW_IDS[i], View.VISIBLE)
                            views.setTextViewText(BADGE_IDS[i], d.designation)
                            views.setInt(BADGE_IDS[i], "setBackgroundResource", badgeDrawableFor(d.badgeStyle))
                            views.setTextColor(
                                BADGE_IDS[i],
                                if (d.badgeStyle == LineBadgeStyle.BLUE || d.badgeStyle == LineBadgeStyle.RED)
                                    Color.WHITE else Color.parseColor("#030914")
                            )

                            views.setTextViewText(DEST_IDS[i], d.destination)
                            views.setTextColor(
                                DEST_IDS[i],
                                if (d.cancelled) Color.parseColor("#FB7185") else Color.parseColor("#F1F5F9")
                            )

                            val subText = when {
                                d.cancelled -> "CANCELLED · ${d.modeLabel}"
                                d.delayMinutes > 0 -> "+${d.delayMinutes}m late · ${d.modeLabel}"
                                else -> d.modeLabel
                            }
                            views.setTextViewText(SUB_IDS[i], subText)
                            views.setTextColor(
                                SUB_IDS[i],
                                when {
                                    d.cancelled -> Color.parseColor("#FB7185")
                                    d.delayMinutes > 0 -> Color.parseColor("#FBBF24")
                                    else -> Color.parseColor("#94A3B8")
                                }
                            )

                            views.setTextViewText(ETA_IDS[i], d.display)
                            views.setTextColor(
                                ETA_IDS[i],
                                when {
                                    d.cancelled -> Color.parseColor("#FB7185")
                                    d.delayMinutes > 0 -> Color.parseColor("#FBBF24")
                                    else -> Color.parseColor("#29E6FF")
                                }
                            )
                            views.setTextViewText(CLOCK_IDS[i], d.clockTime)
                        } else {
                            views.setViewVisibility(ROW_IDS[i], View.GONE)
                        }
                    }
                }
            } catch (_: Exception) {
                views.setTextViewText(R.id.widget_updated_at, "Offline · Tap ↻ to retry")
                views.setViewVisibility(R.id.widget_status_text, View.VISIBLE)
                views.setTextViewText(R.id.widget_status_text, "Couldn't reach SL right now. Tap ↻ to retry.")
                for (i in 0 until 4) {
                    views.setViewVisibility(ROW_IDS[i], View.GONE)
                }
            }

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }

        private fun buildBaseViews(context: Context, appWidgetId: Int, cfg: WidgetConfig): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_departures)
            views.setTextViewText(R.id.widget_station_name, "${cfg.stationName} ▾")

            // Tap refresh icon -> immediate SL fetch
            val refreshIntent = Intent(context, MetroWidgetProvider::class.java).apply {
                action = ACTION_REFRESH
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            }
            val refreshPi = PendingIntent.getBroadcast(
                context,
                appWidgetId * 10 + 1,
                refreshIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_refresh_btn, refreshPi)

            // Tap station title -> open station picker to change station anytime
            val configIntent = Intent(context, WidgetConfigActivity::class.java).apply {
                action = AppWidgetManager.ACTION_APPWIDGET_CONFIGURE
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val configPi = PendingIntent.getActivity(
                context,
                appWidgetId * 10 + 2,
                configIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_header_tap, configPi)

            // Tap departures body -> launch fullscreen 3D Stockholm Metro Radar
            val launchIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val launchPi = PendingIntent.getActivity(
                context,
                appWidgetId * 10 + 3,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_rows_container, launchPi)
            views.setOnClickPendingIntent(R.id.widget_status_text, launchPi)

            return views
        }

        private fun badgeDrawableFor(style: LineBadgeStyle): Int = when (style) {
            LineBadgeStyle.RED -> R.drawable.badge_red
            LineBadgeStyle.GREEN -> R.drawable.badge_green
            LineBadgeStyle.BLUE -> R.drawable.badge_blue
            LineBadgeStyle.PENDEL -> R.drawable.badge_pendel
            LineBadgeStyle.ROSLAG -> R.drawable.badge_roslag
            LineBadgeStyle.TRAM -> R.drawable.badge_tram
            LineBadgeStyle.CYAN -> R.drawable.badge_cyan
        }
    }
}
