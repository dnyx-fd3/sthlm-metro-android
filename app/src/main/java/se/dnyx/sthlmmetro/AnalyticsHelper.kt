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
            FirebaseAnalytics.getInstance(context.applicationContext)
                .setAnalyticsCollectionEnabled(!isDevExcluded(context))
        } catch (_: Exception) {
            // Ignore
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
            FirebaseAnalytics.getInstance(appCtx).logEvent(eventName, Bundle().apply(fill))
        } catch (_: Exception) {
            // Never disrupt user actions
        }
    }

    private fun clip(value: String, maxLen: Int = 90): String {
        val trimmed = value.trim()
        return if (trimmed.length <= maxLen) trimmed else trimmed.substring(0, maxLen)
    }

    fun logAppOpened(context: Context, source: String) {
        log(context, "app_board_opened") {
            putString("source", clip(source, 40))
            putString("app_version", BuildConfig.VERSION_NAME)
        }
    }

    fun logWeb3dOpened(context: Context, stationName: String, siteId: Int) {
        log(context, "radar_3d_web_opened") {
            putString("station_name", clip(stationName))
            putLong("site_id", siteId.toLong())
        }
    }

    fun logNearMeUsed(context: Context, source: String, stopName: String, walkMinutes: Int) {
        log(context, "near_me_used") {
            putString("source", clip(source, 24))
            putString("station_name", clip(stopName))
            putLong("walk_minutes", walkMinutes.toLong())
        }
    }

    fun logWidgetRefreshed(context: Context, slot: StationSlot, slotIndex: Int, trigger: String) {
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

    fun logDepartureTapped(context: Context, stationName: String, lineNumber: String, destination: String, walkMinutes: Int) {
        log(context, "departure_tapped") {
            putString("station_name", clip(stationName))
            putString("line_number", clip(lineNumber, 20))
            putString("destination", clip(destination))
            putLong("walk_minutes", walkMinutes.toLong())
        }
    }

    fun logReminderSet(context: Context, stationName: String, lineNumber: String, destination: String, walkMinutes: Int, timerSeconds: Int) {
        log(context, "reminder_timer_set") {
            putString("station_name", clip(stationName))
            putString("line_number", clip(lineNumber, 20))
            putString("destination", clip(destination))
            putLong("walk_minutes", walkMinutes.toLong())
            putLong("timer_seconds", timerSeconds.toLong())
        }
    }

    fun logWidgetConfigured(context: Context, slots: List<StationSlot>, opacityPercent: Int, theme: WidgetTheme) {
        log(context, "widget_configured") {
            putLong("stop_count", slots.size.toLong())
            putLong("opacity_pct", opacityPercent.toLong())
            putString("theme", theme.code)
            slots.getOrNull(0)?.let { putString("station_name", clip(it.stationName)) }
        }
    }

    fun logWidgetPinned(context: Context, style: String) {
        log(context, "widget_pinned") {
            putString("widget_style", clip(style, 24))
        }
    }

    fun logSlError(context: Context, slot: StationSlot, errorType: String) {
        log(context, "sl_api_error") {
            putString("station_name", clip(slot.stationName))
            putLong("site_id", slot.siteId.toLong())
            putString("error_type", clip(errorType, 30))
        }
    }

    fun logUpdateChecked(context: Context) {
        log(context, "app_update_checked") {
            putString("app_version", BuildConfig.VERSION_NAME)
        }
    }

    fun logFeedbackOpened(context: Context) {
        log(context, "feedback_opened") {
            putString("app_version", BuildConfig.VERSION_NAME)
        }
    }

    fun logFeedbackSubmitted(context: Context, category: String) {
        log(context, "feedback_submitted") {
            putString("category", clip(category, 24))
            putString("app_version", BuildConfig.VERSION_NAME)
        }
    }

    fun logCoffeeClicked(context: Context, source: String) {
        log(context, "buy_coffee_clicked") {
            putString("source", clip(source, 24))
            putString("app_version", BuildConfig.VERSION_NAME)
        }
    }
}
