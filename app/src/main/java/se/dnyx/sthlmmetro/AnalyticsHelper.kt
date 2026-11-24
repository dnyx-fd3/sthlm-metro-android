package se.dnyx.sthlmmetro

import android.content.Context
import android.os.Bundle
import com.google.firebase.analytics.FirebaseAnalytics

object AnalyticsHelper {
    private const val PREFS_NAME = "tradar_analytics_prefs"
    private const val KEY_DEV_EXCLUDED = "dev_analytics_excluded"

    fun isDevExcluded(context: Context): Boolean {
        return context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_DEV_EXCLUDED, false)
    }

    fun syncCollectionState(context: Context) {
        try {
            val excluded = isDevExcluded(context)
            FirebaseAnalytics.getInstance(context.applicationContext)
                .setAnalyticsCollectionEnabled(!excluded)
        } catch (_: Exception) {
            // Never crash if Play Services is unavailable
        }
    }

    fun toggleDevExcluded(context: Context): Boolean {
        val appCtx = context.applicationContext
        val newExcluded = !isDevExcluded(appCtx)
        appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_DEV_EXCLUDED, newExcluded)
            .apply()
        try {
            val fa = FirebaseAnalytics.getInstance(appCtx)
            if (newExcluded) {
                fa.setAnalyticsCollectionEnabled(false)
                fa.resetAnalyticsData()
            } else {
                fa.setAnalyticsCollectionEnabled(true)
            }
        } catch (_: Exception) {
            // Ignore
        }
        return newExcluded
    }

    private fun log(context: Context, eventName: String, fill: Bundle.() -> Unit = {}) {
        val appCtx = context.applicationContext
        if (isDevExcluded(appCtx)) return
        try {
            val bundle = Bundle().apply(fill)
            FirebaseAnalytics.getInstance(appCtx).logEvent(eventName, bundle)
        } catch (_: Exception) {
            // Never let analytics disrupt user actions
        }
    }

    private fun clip(value: String, maxLen: Int = 90): String {
        val trimmed = value.trim()
        return if (trimmed.length <= maxLen) trimmed else trimmed.substring(0, maxLen)
    }

    fun logRadarOpened(context: Context, source: String, stationName: String = "") {
        log(context, "app_open_radar") {
            putString("source", clip(source, 40))
            if (stationName.isNotEmpty()) putString("station_name", clip(stationName))
            putString("app_version", BuildConfig.VERSION_NAME)
        }
        if (source != "launcher") {
            log(context, "widget_open_3d") {
                putString("source", clip(source, 40))
                if (stationName.isNotEmpty()) putString("station_name", clip(stationName))
            }
        }
    }

    fun logWidgetRefreshed(
        context: Context,
        slot: StationSlot,
        slotIndex: Int,
        trigger: String
    ) {
        log(context, "widget_refreshed") {
            putString("station_name", clip(slot.stationName))
            putLong("site_id", slot.siteId.toLong())
            putString("mode_filter", slot.filter.code)
            putLong("slot_index", (slotIndex + 1).toLong())
            putString("trigger", clip(trigger, 30))
        }
    }

    fun logWidgetTabSwitched(context: Context, slot: StationSlot, slotIndex: Int) {
        log(context, "widget_tab_switched") {
            putString("station_name", clip(slot.stationName))
            putLong("site_id", slot.siteId.toLong())
            putString("mode_filter", slot.filter.code)
            putLong("slot_index", (slotIndex + 1).toLong())
        }
    }

    fun logWidgetCatShown(context: Context, slot: StationSlot) {
        log(context, "widget_cat_shown") {
            putString("station_name", clip(slot.stationName))
            putLong("site_id", slot.siteId.toLong())
        }
    }

    fun logWidgetCatPoked(context: Context, slot: StationSlot) {
        log(context, "widget_cat_poked") {
            putString("station_name", clip(slot.stationName))
            putLong("site_id", slot.siteId.toLong())
        }
    }

    fun logDepartureTapped(
        context: Context,
        stationName: String,
        lineNumber: String,
        destination: String,
        walkMinutes: Int
    ) {
        log(context, "widget_departure_tapped") {
            putString("station_name", clip(stationName))
            putString("line_number", clip(lineNumber, 20))
            putString("destination", clip(destination))
            putLong("walk_minutes", walkMinutes.toLong())
        }
    }

    fun logReminderSet(
        context: Context,
        stationName: String,
        lineNumber: String,
        destination: String,
        walkMinutes: Int,
        timerSeconds: Int
    ) {
        log(context, "widget_reminder_set") {
            putString("station_name", clip(stationName))
            putString("line_number", clip(lineNumber, 20))
            putString("destination", clip(destination))
            putLong("walk_minutes", walkMinutes.toLong())
            putLong("timer_seconds", timerSeconds.toLong())
        }
    }

    fun logStationPicked(context: Context, stationName: String, siteId: Int, slotIndex: Int) {
        log(context, "widget_station_picked") {
            putString("station_name", clip(stationName))
            putLong("site_id", siteId.toLong())
            putLong("slot_index", (slotIndex + 1).toLong())
        }
    }

    fun logWidgetConfigured(context: Context, slots: List<StationSlot>, opacityPercent: Int) {
        log(context, "widget_configured") {
            putLong("stop_count", slots.size.toLong())
            putLong("opacity_pct", opacityPercent.toLong())
            slots.getOrNull(0)?.let {
                putString("station_name", clip(it.stationName))
                putString("stop_1", clip(it.stationName))
            }
            slots.getOrNull(1)?.let { putString("stop_2", clip(it.stationName)) }
            slots.getOrNull(2)?.let { putString("stop_3", clip(it.stationName)) }
        }
    }

    fun logSlError(context: Context, slot: StationSlot, errorType: String) {
        log(context, "widget_sl_error") {
            putString("station_name", clip(slot.stationName))
            putLong("site_id", slot.siteId.toLong())
            putString("error_type", clip(errorType, 30))
        }
    }

    fun logUpdateDialogOpened(context: Context, source: String) {
        log(context, "app_update_checked") {
            putString("source", clip(source, 30))
            putString("app_version", BuildConfig.VERSION_NAME)
        }
    }

    fun logUpdateDownloadStarted(context: Context, tagName: String) {
        log(context, "app_update_download") {
            putString("target_tag", clip(tagName, 40))
            putString("from_version", BuildConfig.VERSION_NAME)
        }
    }
}
