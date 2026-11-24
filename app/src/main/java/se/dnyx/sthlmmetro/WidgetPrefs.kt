package se.dnyx.sthlmmetro

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

enum class ModeFilter(val code: String, val label: String) {
    AUTO("AUTO", "Auto"),
    BUS("BUS", "Bus"),
    PENDEL("PENDEL", "Pendel"),
    ALL("ALL", "All");

    companion object {
        fun fromCode(code: String?): ModeFilter =
            entries.firstOrNull { it.code.equals(code, ignoreCase = true) } ?: AUTO
    }
}

data class StationSlot(
    val siteId: Int,
    val stationName: String,
    val filter: ModeFilter = ModeFilter.AUTO,
    val walkMinutes: Int = 3
)

data class WidgetConfig(
    val slots: List<StationSlot>,
    val activeSlotIndex: Int
) {
    val activeSlot: StationSlot
        get() = slots.getOrElse(activeSlotIndex) {
            slots.firstOrNull() ?: StationSlot(WidgetPrefs.DEFAULT_SITE_ID, WidgetPrefs.DEFAULT_STATION_NAME)
        }
}

data class CachedSlotDepartures(
    val siteId: Int,
    val filterCode: String,
    val fetchedAtMs: Long,
    val items: List<DepartureItem>
)

object WidgetPrefs {
    private const val PREFS_NAME = "sthlm_metro_widget_prefs"
    private const val CACHE_PREFS_NAME = "sthlm_metro_widget_cache"
    private const val KEY_SLOTS_JSON = "slots_json_"
    private const val KEY_ACTIVE_SLOT = "active_slot_"

    // Legacy v1.0 keys
    private const val LEGACY_SITE_ID = "site_id_"
    private const val LEGACY_STATION_NAME = "station_name_"
    private const val LEGACY_INCLUDE_BUSES = "include_buses_"

    const val DEFAULT_SITE_ID = 9001
    const val DEFAULT_STATION_NAME = "T-Centralen"

    fun saveSlots(context: Context, appWidgetId: Int, slots: List<StationSlot>, activeIndex: Int = 0) {
        val clean = slots.take(3).ifEmpty {
            listOf(StationSlot(DEFAULT_SITE_ID, DEFAULT_STATION_NAME, ModeFilter.AUTO, 3))
        }
        val arr = JSONArray()
        for (slot in clean) {
            arr.put(
                JSONObject()
                    .put("siteId", slot.siteId)
                    .put("name", slot.stationName)
                    .put("filter", slot.filter.code)
                    .put("walkMinutes", slot.walkMinutes.coerceIn(0, 30))
            )
        }
        val safeIdx = activeIndex.coerceIn(0, clean.lastIndex)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_SLOTS_JSON + appWidgetId, arr.toString())
            .putInt(KEY_ACTIVE_SLOT + appWidgetId, safeIdx)
            .apply()
    }

    fun setActiveSlot(context: Context, appWidgetId: Int, slotIndex: Int) {
        val cfg = load(context, appWidgetId)
        val safeIdx = slotIndex.coerceIn(0, (cfg.slots.size - 1).coerceAtLeast(0))
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putInt(KEY_ACTIVE_SLOT + appWidgetId, safeIdx)
            .apply()
    }

    fun updateSlotWalkMinutes(context: Context, appWidgetId: Int, slotIndex: Int, walkMinutes: Int) {
        val cfg = load(context, appWidgetId)
        if (slotIndex !in cfg.slots.indices) return
        val updated = cfg.slots.mapIndexed { idx, slot ->
            if (idx == slotIndex) slot.copy(walkMinutes = walkMinutes.coerceIn(0, 30)) else slot
        }
        saveSlots(context, appWidgetId, updated, cfg.activeSlotIndex)
    }

    fun load(context: Context, appWidgetId: Int): WidgetConfig {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val rawJson = prefs.getString(KEY_SLOTS_JSON + appWidgetId, null)
        if (!rawJson.isNullOrBlank()) {
            try {
                val arr = JSONArray(rawJson)
                val parsed = mutableListOf<StationSlot>()
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val id = obj.optInt("siteId", -1)
                    val name = obj.optString("name", "").trim()
                    val filter = ModeFilter.fromCode(obj.optString("filter", "AUTO"))
                    val walk = obj.optInt("walkMinutes", 3).coerceIn(0, 30)
                    if (id > 0 && name.isNotEmpty()) {
                        parsed.add(StationSlot(id, name, filter, walk))
                    }
                }
                if (parsed.isNotEmpty()) {
                    val active = prefs.getInt(KEY_ACTIVE_SLOT + appWidgetId, 0).coerceIn(0, parsed.lastIndex)
                    return WidgetConfig(parsed.take(3), active)
                }
            } catch (_: Exception) {
                // Fall through to legacy/default
            }
        }

        val legacyId = prefs.getInt(LEGACY_SITE_ID + appWidgetId, DEFAULT_SITE_ID)
        val legacyName = prefs.getString(LEGACY_STATION_NAME + appWidgetId, DEFAULT_STATION_NAME) ?: DEFAULT_STATION_NAME
        val legacyBuses = prefs.getBoolean(LEGACY_INCLUDE_BUSES + appWidgetId, false)
        val legacyFilter = if (legacyBuses) ModeFilter.ALL else ModeFilter.AUTO
        return WidgetConfig(
            slots = listOf(StationSlot(legacyId, legacyName, legacyFilter, 3)),
            activeSlotIndex = 0
        )
    }

    fun saveCache(
        context: Context,
        appWidgetId: Int,
        slotIndex: Int,
        siteId: Int,
        filter: ModeFilter,
        fetchedAtMs: Long,
        items: List<DepartureItem>
    ) {
        val arr = JSONArray()
        for (d in items) {
            arr.put(
                JSONObject()
                    .put("designation", d.designation)
                    .put("destination", d.destination)
                    .put("display", d.display)
                    .put("clockTime", d.clockTime)
                    .put("expectedEpochMs", d.expectedEpochMs)
                    .put("modeLabel", d.modeLabel)
                    .put("delayMinutes", d.delayMinutes)
                    .put("cancelled", d.cancelled)
                    .put("badgeStyle", d.badgeStyle.name)
            )
        }
        val payload = JSONObject()
            .put("siteId", siteId)
            .put("filter", filter.code)
            .put("fetchedAtMs", fetchedAtMs)
            .put("items", arr)
            .toString()

        context.getSharedPreferences(CACHE_PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString("cache_${appWidgetId}_$slotIndex", payload)
            .apply()
    }

    fun loadCache(context: Context, appWidgetId: Int, slotIndex: Int): CachedSlotDepartures? {
        val raw = context.getSharedPreferences(CACHE_PREFS_NAME, Context.MODE_PRIVATE)
            .getString("cache_${appWidgetId}_$slotIndex", null) ?: return null
        return try {
            val obj = JSONObject(raw)
            val arr = obj.optJSONArray("items") ?: JSONArray()
            val list = ArrayList<DepartureItem>(arr.length())
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val styleName = item.optString("badgeStyle", "CYAN")
                val style = LineBadgeStyle.entries.firstOrNull { it.name == styleName } ?: LineBadgeStyle.CYAN
                list.add(
                    DepartureItem(
                        designation = item.optString("designation", "SL"),
                        destination = item.optString("destination", "Stockholm"),
                        display = item.optString("display", ""),
                        clockTime = item.optString("clockTime", ""),
                        expectedEpochMs = item.optLong("expectedEpochMs", 0L),
                        modeLabel = item.optString("modeLabel", "SL"),
                        delayMinutes = item.optInt("delayMinutes", 0),
                        cancelled = item.optBoolean("cancelled", false),
                        badgeStyle = style
                    )
                )
            }
            CachedSlotDepartures(
                siteId = obj.optInt("siteId", -1),
                filterCode = obj.optString("filter", "AUTO"),
                fetchedAtMs = obj.optLong("fetchedAtMs", 0L),
                items = list
            )
        } catch (_: Exception) {
            null
        }
    }

    fun delete(context: Context, appWidgetId: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .remove(KEY_SLOTS_JSON + appWidgetId)
            .remove(KEY_ACTIVE_SLOT + appWidgetId)
            .remove(LEGACY_SITE_ID + appWidgetId)
            .remove(LEGACY_STATION_NAME + appWidgetId)
            .remove(LEGACY_INCLUDE_BUSES + appWidgetId)
            .apply()
        context.getSharedPreferences(CACHE_PREFS_NAME, Context.MODE_PRIVATE).edit()
            .remove("cache_${appWidgetId}_0")
            .remove("cache_${appWidgetId}_1")
            .remove("cache_${appWidgetId}_2")
            .apply()
    }
}
