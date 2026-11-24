package se.dnyx.sthlmmetro

import android.content.Context

data class WidgetConfig(
    val siteId: Int,
    val stationName: String,
    val includeBuses: Boolean
)

object WidgetPrefs {
    private const val PREFS_NAME = "sthlm_metro_widget_prefs"
    private const val KEY_SITE_ID = "site_id_"
    private const val KEY_STATION_NAME = "station_name_"
    private const val KEY_INCLUDE_BUSES = "include_buses_"

    const val DEFAULT_SITE_ID = 9001
    const val DEFAULT_STATION_NAME = "T-Centralen"

    fun save(context: Context, appWidgetId: Int, siteId: Int, stationName: String, includeBuses: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putInt(KEY_SITE_ID + appWidgetId, siteId)
            .putString(KEY_STATION_NAME + appWidgetId, stationName)
            .putBoolean(KEY_INCLUDE_BUSES + appWidgetId, includeBuses)
            .apply()
    }

    fun load(context: Context, appWidgetId: Int): WidgetConfig {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return WidgetConfig(
            siteId = prefs.getInt(KEY_SITE_ID + appWidgetId, DEFAULT_SITE_ID),
            stationName = prefs.getString(KEY_STATION_NAME + appWidgetId, DEFAULT_STATION_NAME) ?: DEFAULT_STATION_NAME,
            includeBuses = prefs.getBoolean(KEY_INCLUDE_BUSES + appWidgetId, false)
        )
    }

    fun delete(context: Context, appWidgetId: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .remove(KEY_SITE_ID + appWidgetId)
            .remove(KEY_STATION_NAME + appWidgetId)
            .remove(KEY_INCLUDE_BUSES + appWidgetId)
            .apply()
    }
}
