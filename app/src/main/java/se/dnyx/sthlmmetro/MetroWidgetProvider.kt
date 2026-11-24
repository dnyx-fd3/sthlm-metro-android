package se.dnyx.sthlmmetro

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors

class MetroWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val pendingResult = goAsync()
        executor.execute {
            try {
                for (widgetId in appWidgetIds) {
                    val cfg = WidgetPrefs.load(context, widgetId)
                    val cached = WidgetPrefs.loadCache(context, widgetId, cfg.activeSlotIndex)
                    val cacheMatches = cached != null &&
                        cached.siteId == cfg.activeSlot.siteId &&
                        cached.filterCode == cfg.activeSlot.filter.code
                    if (cacheMatches) {
                        renderFromCache(context, appWidgetManager, widgetId, cfg, cached!!)
                    } else {
                        refreshWidgetBlocking(context, appWidgetManager, widgetId)
                    }
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val action = intent.action ?: return
        if (action != ACTION_REFRESH && action != ACTION_SELECT_SLOT && action != ACTION_LOCAL_TICK) return

        val widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        val appWidgetManager = AppWidgetManager.getInstance(context)
        val ids = if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            intArrayOf(widgetId)
        } else {
            appWidgetManager.getAppWidgetIds(ComponentName(context, MetroWidgetProvider::class.java))
        }
        if (ids.isEmpty()) return

        // Pure local rollover (0 network calls): shifts departed buses off & reveals cat when list empties
        if (action == ACTION_LOCAL_TICK) {
            for (id in ids) {
                val cfg = WidgetPrefs.load(context, id)
                val cached = WidgetPrefs.loadCache(context, id, cfg.activeSlotIndex)
                if (cached != null &&
                    cached.siteId == cfg.activeSlot.siteId &&
                    cached.filterCode == cfg.activeSlot.filter.code
                ) {
                    renderFromCache(context, appWidgetManager, id, cfg, cached)
                }
            }
            return
        }

        if (action == ACTION_SELECT_SLOT && widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            val slotIndex = intent.getIntExtra(EXTRA_SLOT_INDEX, 0)
            WidgetPrefs.setActiveSlot(context, widgetId, slotIndex)
        }

        // Instant visual feedback without wiping current rows on refresh
        for (id in ids) {
            val cfg = WidgetPrefs.load(context, id)
            val cached = WidgetPrefs.loadCache(context, id, cfg.activeSlotIndex)
            val hasWarmCache = cached != null &&
                cached.siteId == cfg.activeSlot.siteId &&
                cached.filterCode == cfg.activeSlot.filter.code &&
                action == ACTION_REFRESH
            if (hasWarmCache) {
                val partial = RemoteViews(context.packageName, R.layout.widget_departures)
                partial.setTextViewText(R.id.widget_updated_at, "Refreshing live SL…")
                appWidgetManager.partiallyUpdateAppWidget(id, partial)
            } else {
                val loadingViews = buildBaseViews(context, id, cfg)
                loadingViews.setTextViewText(
                    R.id.widget_updated_at,
                    "Loading ${cfg.activeSlot.stationName}…"
                )
                appWidgetManager.updateAppWidget(id, loadingViews)
            }
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

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        for (id in appWidgetIds) {
            cancelLocalRollover(context, id)
            WidgetPrefs.delete(context, id)
        }
    }

    companion object {
        const val ACTION_REFRESH = "se.dnyx.sthlmmetro.ACTION_REFRESH_WIDGET"
        const val ACTION_SELECT_SLOT = "se.dnyx.sthlmmetro.ACTION_SELECT_SLOT"
        const val ACTION_LOCAL_TICK = "se.dnyx.sthlmmetro.ACTION_LOCAL_TICK"
        const val EXTRA_SLOT_INDEX = "slot_index"

        private val executor = Executors.newSingleThreadExecutor()
        private val timeFmt = DateTimeFormatter.ofPattern("HH:mm")

        private val TAB_IDS = intArrayOf(R.id.widget_tab_0, R.id.widget_tab_1, R.id.widget_tab_2)
        private val ROW_IDS = intArrayOf(R.id.row_0, R.id.row_1, R.id.row_2, R.id.row_3)
        private val BADGE_IDS = intArrayOf(R.id.row_0_badge, R.id.row_1_badge, R.id.row_2_badge, R.id.row_3_badge)
        private val DEST_IDS = intArrayOf(R.id.row_0_dest, R.id.row_1_dest, R.id.row_2_dest, R.id.row_3_dest)
        private val SUB_IDS = intArrayOf(R.id.row_0_sub, R.id.row_1_sub, R.id.row_2_sub, R.id.row_3_sub)
        private val CHRONO_IDS = intArrayOf(R.id.row_0_chrono, R.id.row_1_chrono, R.id.row_2_chrono, R.id.row_3_chrono)
        private val ETA_IDS = intArrayOf(R.id.row_0_eta, R.id.row_1_eta, R.id.row_2_eta, R.id.row_3_eta)
        private val CLOCK_IDS = intArrayOf(R.id.row_0_clock, R.id.row_1_clock, R.id.row_2_clock, R.id.row_3_clock)

        fun triggerAsyncRefresh(context: Context, appWidgetId: Int) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            executor.execute {
                refreshWidgetBlocking(context, appWidgetManager, appWidgetId)
            }
        }

        fun refreshWidgetBlocking(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
            val cfg = WidgetPrefs.load(context, appWidgetId)
            val active = cfg.activeSlot

            try {
                val fetched = SlApiClient.fetchDepartures(active.siteId, active.filter, limit = 20)
                val fetchedAtMs = System.currentTimeMillis()
                WidgetPrefs.saveCache(
                    context = context,
                    appWidgetId = appWidgetId,
                    slotIndex = cfg.activeSlotIndex,
                    siteId = active.siteId,
                    filter = active.filter,
                    fetchedAtMs = fetchedAtMs,
                    items = fetched
                )
                val cache = CachedSlotDepartures(active.siteId, active.filter.code, fetchedAtMs, fetched)
                renderFromCache(context, appWidgetManager, appWidgetId, cfg, cache)
            } catch (_: Exception) {
                val views = buildBaseViews(context, appWidgetId, cfg)
                views.setTextViewText(R.id.widget_updated_at, "Offline · Tap ↻ to retry")
                views.setViewVisibility(R.id.widget_cat_empty, View.GONE)
                views.setViewVisibility(R.id.widget_status_text, View.VISIBLE)
                views.setTextViewText(R.id.widget_status_text, "Couldn't reach SL right now. Tap ↻ to retry.")
                for (i in 0 until 4) {
                    views.setViewVisibility(ROW_IDS[i], View.GONE)
                }
                cancelLocalRollover(context, appWidgetId)
                appWidgetManager.updateAppWidget(appWidgetId, views)
            }
        }

        private fun renderFromCache(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int,
            cfg: WidgetConfig,
            cache: CachedSlotDepartures
        ) {
            val active = cfg.activeSlot
            val views = buildBaseViews(context, appWidgetId, cfg)

            val textPrimary = ContextCompat.getColor(context, R.color.widget_text_primary)
            val textSecondary = ContextCompat.getColor(context, R.color.widget_text_secondary)
            val accentColor = ContextCompat.getColor(context, R.color.widget_accent)
            val warnColor = ContextCompat.getColor(context, R.color.widget_warn)
            val cancelColor = ContextCompat.getColor(context, R.color.widget_cancel)

            val nowMs = System.currentTimeMillis()
            val elapsedNow = SystemClock.elapsedRealtime()
            val syncClock = Instant.ofEpochMilli(cache.fetchedAtMs)
                .atZone(ZoneId.systemDefault())
                .toLocalTime()
                .format(timeFmt)
            val modeTag = if (active.filter != ModeFilter.AUTO) " · ${active.filter.label}" else ""

            // Keep only departures that haven't passed yet (10s grace for boarding)
            val upcoming = cache.items.filter { d ->
                d.expectedEpochMs <= 0L || d.expectedEpochMs > nowMs - 10_000L
            }

            if (upcoming.isEmpty()) {
                // Inactivity / all cached buses departed -> show roaming cat pointing to ↻!
                views.setTextViewText(R.id.widget_updated_at, "Last sync $syncClock$modeTag")
                views.setViewVisibility(R.id.widget_status_text, View.GONE)
                views.setViewVisibility(R.id.widget_cat_empty, View.VISIBLE)
                views.setTextViewText(R.id.widget_cat_caption, "All buses left without you... poke ↻")
                for (i in 0 until 4) {
                    views.setViewVisibility(ROW_IDS[i], View.GONE)
                }
                cancelLocalRollover(context, appWidgetId)
                appWidgetManager.updateAppWidget(appWidgetId, views)
                return
            }

            views.setTextViewText(R.id.widget_updated_at, "Synced $syncClock$modeTag · Tap row to remind")
            views.setViewVisibility(R.id.widget_status_text, View.GONE)
            views.setViewVisibility(R.id.widget_cat_empty, View.GONE)

            val shown = upcoming.take(4)
            var nextRolloverMs = Long.MAX_VALUE

            for (i in 0 until 4) {
                if (i < shown.size) {
                    val d = shown[i]
                    views.setViewVisibility(ROW_IDS[i], View.VISIBLE)
                    views.setTextViewText(BADGE_IDS[i], d.designation)
                    views.setInt(BADGE_IDS[i], "setBackgroundResource", badgeDrawableFor(d.badgeStyle))

                    views.setTextViewText(DEST_IDS[i], d.destination)
                    views.setTextColor(DEST_IDS[i], if (d.cancelled) cancelColor else textPrimary)

                    val subText = when {
                        d.cancelled -> "Cancelled · ${d.modeLabel}"
                        d.delayMinutes > 0 -> "+${d.delayMinutes}m late · ${d.modeLabel}"
                        else -> d.modeLabel
                    }
                    views.setTextViewText(SUB_IDS[i], subText)
                    views.setTextColor(
                        SUB_IDS[i],
                        when {
                            d.cancelled -> cancelColor
                            d.delayMinutes > 0 -> warnColor
                            else -> textSecondary
                        }
                    )

                    val remainingMs = if (d.expectedEpochMs > 0L) d.expectedEpochMs - nowMs else -1L
                    val etaColor = when {
                        d.cancelled -> cancelColor
                        d.delayMinutes > 0 -> warnColor
                        else -> accentColor
                    }

                    // Live ticking local countdown via native Android Chronometer!
                    if (!d.cancelled && remainingMs in 6_000L..3_599_000L) {
                        views.setViewVisibility(CHRONO_IDS[i], View.VISIBLE)
                        views.setViewVisibility(ETA_IDS[i], View.GONE)
                        views.setChronometerCountDown(CHRONO_IDS[i], true)
                        views.setChronometer(CHRONO_IDS[i], elapsedNow + remainingMs, null, true)
                        views.setTextColor(CHRONO_IDS[i], etaColor)
                    } else {
                        views.setChronometer(CHRONO_IDS[i], elapsedNow, null, false)
                        views.setViewVisibility(CHRONO_IDS[i], View.GONE)
                        views.setViewVisibility(ETA_IDS[i], View.VISIBLE)
                        val fallbackLabel = when {
                            d.cancelled -> "—"
                            d.expectedEpochMs > 0L && remainingMs <= 5_999L -> "NOW"
                            d.clockTime.isNotEmpty() -> d.clockTime
                            else -> d.display
                        }
                        views.setTextViewText(ETA_IDS[i], fallbackLabel)
                        views.setTextColor(ETA_IDS[i], etaColor)
                    }

                    views.setTextViewText(CLOCK_IDS[i], d.clockTime)

                    if (d.expectedEpochMs > 0L) {
                        val candidateRollover = d.expectedEpochMs + 8_000L
                        if (candidateRollover < nextRolloverMs) {
                            nextRolloverMs = candidateRollover
                        }
                    }

                    // Tap this departure row -> open walk-buffer reminder dialog
                    val remIntent = Intent(context, DepartureReminderActivity::class.java).apply {
                        putExtra(DepartureReminderActivity.EXTRA_WIDGET_ID, appWidgetId)
                        putExtra(DepartureReminderActivity.EXTRA_SLOT_INDEX, cfg.activeSlotIndex)
                        putExtra(DepartureReminderActivity.EXTRA_STATION_NAME, active.stationName)
                        putExtra(DepartureReminderActivity.EXTRA_DESIGNATION, d.designation)
                        putExtra(DepartureReminderActivity.EXTRA_DESTINATION, d.destination)
                        putExtra(DepartureReminderActivity.EXTRA_CLOCK_TIME, d.clockTime)
                        putExtra(DepartureReminderActivity.EXTRA_EXPECTED_EPOCH_MS, d.expectedEpochMs)
                        putExtra(DepartureReminderActivity.EXTRA_BADGE_STYLE, d.badgeStyle.name)
                        putExtra(DepartureReminderActivity.EXTRA_WALK_MINUTES, active.walkMinutes)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    }
                    val remPi = PendingIntent.getActivity(
                        context,
                        appWidgetId * 50 + 30 + i,
                        remIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                    views.setOnClickPendingIntent(ROW_IDS[i], remPi)
                } else {
                    views.setChronometer(CHRONO_IDS[i], elapsedNow, null, false)
                    views.setViewVisibility(ROW_IDS[i], View.GONE)
                }
            }

            if (nextRolloverMs < Long.MAX_VALUE) {
                val triggerAt = maxOf(nowMs + 15_000L, nextRolloverMs)
                scheduleLocalRollover(context, appWidgetId, triggerAt)
            } else {
                cancelLocalRollover(context, appWidgetId)
            }

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }

        private fun scheduleLocalRollover(context: Context, appWidgetId: Int, triggerAtEpochMs: Long) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val pi = localTickPendingIntent(context, appWidgetId)
            try {
                am.setAndAllowWhileIdle(AlarmManager.RTC, triggerAtEpochMs, pi)
            } catch (_: SecurityException) {
                am.set(AlarmManager.RTC, triggerAtEpochMs, pi)
            }
        }

        private fun cancelLocalRollover(context: Context, appWidgetId: Int) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            am.cancel(localTickPendingIntent(context, appWidgetId))
        }

        private fun localTickPendingIntent(context: Context, appWidgetId: Int): PendingIntent {
            val intent = Intent(context, MetroWidgetProvider::class.java).apply {
                action = ACTION_LOCAL_TICK
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            }
            return PendingIntent.getBroadcast(
                context,
                appWidgetId * 50 + 49,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        private fun buildBaseViews(context: Context, appWidgetId: Int, cfg: WidgetConfig): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_departures)
            val active = cfg.activeSlot
            views.setTextViewText(R.id.widget_station_name, "${active.stationName} ▾")

            val tabActiveText = ContextCompat.getColor(context, R.color.widget_tab_active_text)
            val tabIdleText = ContextCompat.getColor(context, R.color.widget_text_secondary)

            if (cfg.slots.size > 1) {
                views.setViewVisibility(R.id.widget_tabs_bar, View.VISIBLE)
                for (i in 0 until 3) {
                    if (i < cfg.slots.size) {
                        val slot = cfg.slots[i]
                        val isSelected = i == cfg.activeSlotIndex
                        views.setViewVisibility(TAB_IDS[i], View.VISIBLE)
                        views.setTextViewText(TAB_IDS[i], shortenTabLabel(slot.stationName))
                        views.setInt(
                            TAB_IDS[i],
                            "setBackgroundResource",
                            if (isSelected) R.drawable.widget_tab_active else R.drawable.widget_tab_idle
                        )
                        views.setTextColor(TAB_IDS[i], if (isSelected) tabActiveText else tabIdleText)

                        val tabIntent = Intent(context, MetroWidgetProvider::class.java).apply {
                            action = ACTION_SELECT_SLOT
                            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                            putExtra(EXTRA_SLOT_INDEX, i)
                        }
                        val tabPi = PendingIntent.getBroadcast(
                            context,
                            appWidgetId * 50 + 10 + i,
                            tabIntent,
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                        )
                        views.setOnClickPendingIntent(TAB_IDS[i], tabPi)
                    } else {
                        views.setViewVisibility(TAB_IDS[i], View.GONE)
                    }
                }
            } else {
                views.setViewVisibility(R.id.widget_tabs_bar, View.GONE)
            }

            // Tap ↻ (or the cat card!) -> immediate SL refresh
            val refreshIntent = Intent(context, MetroWidgetProvider::class.java).apply {
                action = ACTION_REFRESH
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            }
            val refreshPi = PendingIntent.getBroadcast(
                context,
                appWidgetId * 50 + 1,
                refreshIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_refresh_btn, refreshPi)
            views.setOnClickPendingIntent(R.id.widget_cat_empty, refreshPi)

            // Tap station title -> open 3-stop configurator
            val configIntent = Intent(context, WidgetConfigActivity::class.java).apply {
                action = AppWidgetManager.ACTION_APPWIDGET_CONFIGURE
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val configPi = PendingIntent.getActivity(
                context,
                appWidgetId * 50 + 2,
                configIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_header_tap, configPi)

            // Tap "3D" button (or status text) -> launch fullscreen 3D Stockholm Metro Radar
            val launchIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val launchPi = PendingIntent.getActivity(
                context,
                appWidgetId * 50 + 3,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_radar_btn, launchPi)
            views.setOnClickPendingIntent(R.id.widget_status_text, launchPi)

            return views
        }

        private fun shortenTabLabel(name: String): String {
            return name
                .replace("Stockholms östra", "Sthlm Östra", ignoreCase = true)
                .replace("Stockholm City", "Sthlm City", ignoreCase = true)
                .replace("Stockholm Odenplan", "Odenplan", ignoreCase = true)
                .replace("Lappkärrsberget", "Lappis", ignoreCase = true)
                .replace("Tekniska högskolan", "Tekniska", ignoreCase = true)
                .replace(" centrum", " C", ignoreCase = true)
        }

        fun badgeDrawableFor(style: LineBadgeStyle): Int = when (style) {
            LineBadgeStyle.RED -> R.drawable.badge_red
            LineBadgeStyle.GREEN -> R.drawable.badge_green
            LineBadgeStyle.BLUE -> R.drawable.badge_blue
            LineBadgeStyle.PENDEL -> R.drawable.badge_pendel
            LineBadgeStyle.ROSLAG -> R.drawable.badge_roslag
            LineBadgeStyle.TRAM -> R.drawable.badge_tram
            LineBadgeStyle.BUS_BLUE -> R.drawable.badge_bus_blue
            LineBadgeStyle.BUS_RED -> R.drawable.badge_bus_red
            LineBadgeStyle.CYAN -> R.drawable.badge_cyan
        }
    }
}
