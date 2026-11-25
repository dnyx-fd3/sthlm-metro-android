package se.dnyx.sthlmmetro

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

enum class ModeFilter(val code: String, val label: String) {
    AUTO("AUTO", "Auto"),
    METRO("METRO", "Metro"),
    BUS("BUS", "Bus"),
    PENDEL("PENDEL", "Pendel"),
    ALL("ALL", "All");

    companion object {
        fun fromCode(code: String?): ModeFilter =
            entries.firstOrNull { it.code.equals(code, ignoreCase = true) } ?: AUTO
    }
}

enum class WidgetTheme(val code: String, val label: String) {
    AUTO("AUTO", "Auto"),
    DARK("DARK", "Dark"),
    LIGHT("LIGHT", "Light");

    companion object {
        fun fromCode(code: String?): WidgetTheme =
            entries.firstOrNull { it.code.equals(code, ignoreCase = true) } ?: AUTO
    }
}

data class StationSlot(
    val siteId: Int,
    val stationName: String,
    val filter: ModeFilter = ModeFilter.AUTO,
    val walkMinutes: Int = 3
) {
    val isNearMe: Boolean
        get() = siteId == WidgetPrefs.NEAR_ME_SITE_ID
}

data class NearbyOverride(
    val siteId: Int,
    val stationName: String,
    val walkMinutes: Int,
    val distanceMeters: Int,
    val updatedAtMs: Long
)

data class ActiveReminder(
    val key: String,
    val requestCode: Int,
    val stationName: String,
    val designation: String,
    val destination: String,
    val walkMinutes: Int,
    val expectedEpochMs: Long,
    val triggerAtMs: Long
)

data class WidgetConfig(
    val slots: List<StationSlot>,
    val activeSlotIndex: Int,
    val opacityPercent: Int = 85,
    val theme: WidgetTheme = WidgetTheme.AUTO,
    val resolvedNearMe: NearbyOverride? = null
) {
    val selectedSlot: StationSlot
        get() = slots.getOrElse(activeSlotIndex) {
            slots.firstOrNull() ?: StationSlot(WidgetPrefs.DEFAULT_SITE_ID, WidgetPrefs.DEFAULT_STATION_NAME)
        }

    val isActiveNearMe: Boolean
        get() = selectedSlot.isNearMe

    val effectiveSlot: StationSlot
        get() {
            val sel = selectedSlot
            if (sel.isNearMe) {
                val near = resolvedNearMe
                if (near != null) {
                    return StationSlot(
                        siteId = near.siteId,
                        stationName = near.stationName,
                        filter = sel.filter,
                        walkMinutes = near.walkMinutes
                    )
                }
            }
            return sel
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

    private const val KEY_SHARED_SLOTS = "shared_slots_json"
    private const val KEY_SHARED_OPACITY = "shared_opacity_pct"
    private const val KEY_SHARED_THEME = "shared_theme"
    private const val KEY_ACTIVE_SLOT = "active_slot_"
    private const val KEY_NEARBY_OVERRIDE = "nearby_override_"
    private const val KEY_ACTIVE_REMINDERS = "active_reminders_json"
    private const val KEY_NOTIF_PROMPTED = "notif_perm_prompted_v21"

    private const val LEGACY_SLOTS_JSON = "slots_json_"
    private const val LEGACY_OPACITY = "opacity_pct_"

    private const val KEY_SITES_JSON = "cached_sites_v2_json"
    private const val KEY_SITES_FETCHED_MS = "cached_sites_v2_ms"
    private const val SITES_CACHE_TTL_MS = 7L * 24L * 60L * 60L * 1000L

    const val NEAR_ME_SITE_ID = -100
    const val NEAR_ME_LABEL = "📍 Near me"
    const val DEFAULT_SITE_ID = 9001
    const val DEFAULT_STATION_NAME = "T-Centralen"

    fun nearMeSlot(filter: ModeFilter = ModeFilter.ALL, walkMinutes: Int = 3): StationSlot {
        return StationSlot(NEAR_ME_SITE_ID, NEAR_ME_LABEL, filter, walkMinutes)
    }

    fun wasNotifPrompted(context: Context): Boolean {
        return context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_NOTIF_PROMPTED, false)
    }

    fun markNotifPrompted(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_NOTIF_PROMPTED, true)
            .apply()
    }

    fun saveSharedSettings(
        context: Context,
        slots: List<StationSlot>,
        opacityPercent: Int,
        theme: WidgetTheme
    ) {
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
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_SHARED_SLOTS, arr.toString())
            .putInt(KEY_SHARED_OPACITY, opacityPercent.coerceIn(0, 100))
            .putString(KEY_SHARED_THEME, theme.code)
            .apply()
    }

    fun loadSharedSlots(context: Context): List<StationSlot> {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_SHARED_SLOTS, null)
        val parsed = parseSlotsJson(raw)
        if (parsed.isNotEmpty()) return parsed

        for ((k, v) in prefs.all) {
            if (k.startsWith(LEGACY_SLOTS_JSON) && v is String) {
                val migrated = parseSlotsJson(v)
                if (migrated.isNotEmpty()) {
                    val legacyOpacity = prefs.getInt(LEGACY_OPACITY + k.removePrefix(LEGACY_SLOTS_JSON), 85)
                    saveSharedSettings(context, migrated, legacyOpacity, WidgetTheme.AUTO)
                    return migrated
                }
            }
        }
        return listOf(
            StationSlot(DEFAULT_SITE_ID, DEFAULT_STATION_NAME, ModeFilter.AUTO, 3),
            nearMeSlot(ModeFilter.ALL, 3)
        )
    }

    fun loadSharedOpacity(context: Context): Int {
        return context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_SHARED_OPACITY, 85).coerceIn(0, 100)
    }

    fun loadSharedTheme(context: Context): WidgetTheme {
        val code = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_SHARED_THEME, WidgetTheme.AUTO.code)
        return WidgetTheme.fromCode(code)
    }

    fun updateSlotWalkMinutes(context: Context, slotIndex: Int, walkMinutes: Int) {
        val slots = loadSharedSlots(context)
        if (slotIndex !in slots.indices) return
        val updated = slots.mapIndexed { idx, s ->
            if (idx == slotIndex) s.copy(walkMinutes = walkMinutes.coerceIn(0, 30)) else s
        }
        saveSharedSettings(context, updated, loadSharedOpacity(context), loadSharedTheme(context))
    }

    fun setActiveSlot(context: Context, appWidgetId: Int, slotIndex: Int) {
        val slots = loadSharedSlots(context)
        val safeIdx = slotIndex.coerceIn(0, (slots.size - 1).coerceAtLeast(0))
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putInt(KEY_ACTIVE_SLOT + appWidgetId, safeIdx)
            .apply()
    }

    fun setResolvedNearMe(context: Context, appWidgetId: Int, override: NearbyOverride) {
        val json = JSONObject()
            .put("siteId", override.siteId)
            .put("name", override.stationName)
            .put("walkMinutes", override.walkMinutes)
            .put("distMeters", override.distanceMeters)
            .put("updatedAtMs", override.updatedAtMs)
            .toString()
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_NEARBY_OVERRIDE + appWidgetId, json)
            .apply()
    }

    fun load(context: Context, appWidgetId: Int): WidgetConfig {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val slots = loadSharedSlots(context)
        val active = prefs.getInt(KEY_ACTIVE_SLOT + appWidgetId, 0).coerceIn(0, slots.lastIndex)
        val opacity = loadSharedOpacity(context)
        val theme = loadSharedTheme(context)
        val near = parseNearby(prefs.getString(KEY_NEARBY_OVERRIDE + appWidgetId, null))
        return WidgetConfig(
            slots = slots,
            activeSlotIndex = active,
            opacityPercent = opacity,
            theme = theme,
            resolvedNearMe = near
        )
    }

    // Active Departure Reminders (auto-pruned past departure time)
    fun loadActiveReminders(context: Context): List<ActiveReminder> {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_ACTIVE_REMINDERS, null) ?: return emptyList()
        val nowMs = System.currentTimeMillis()
        return try {
            val arr = JSONArray(raw)
            val live = mutableListOf<ActiveReminder>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val trigger = o.optLong("triggerAtMs", 0L)
                val expected = o.optLong("expectedEpochMs", 0L)
                if (trigger > nowMs - 15_000L && expected > nowMs - 15_000L) {
                    live.add(
                        ActiveReminder(
                            key = o.optString("key", ""),
                            requestCode = o.optInt("req", 0),
                            stationName = o.optString("station", ""),
                            designation = o.optString("line", ""),
                            destination = o.optString("dest", ""),
                            walkMinutes = o.optInt("walk", 3),
                            expectedEpochMs = expected,
                            triggerAtMs = trigger
                        )
                    )
                }
            }
            if (live.size != arr.length()) {
                persistReminders(context, live)
            }
            live
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun findReminder(context: Context, key: String): ActiveReminder? {
        return loadActiveReminders(context).firstOrNull { it.key == key }
    }

    fun saveReminder(context: Context, reminder: ActiveReminder) {
        val updated = loadActiveReminders(context).filterNot { it.key == reminder.key } + reminder
        persistReminders(context, updated)
    }

    fun removeReminder(context: Context, key: String): ActiveReminder? {
        val current = loadActiveReminders(context)
        val removed = current.firstOrNull { it.key == key }
        if (removed != null) {
            persistReminders(context, current.filterNot { it.key == key })
        }
        return removed
    }

    private fun persistReminders(context: Context, list: List<ActiveReminder>) {
        val arr = JSONArray()
        for (r in list) {
            arr.put(
                JSONObject()
                    .put("key", r.key)
                    .put("req", r.requestCode)
                    .put("station", r.stationName)
                    .put("line", r.designation)
                    .put("dest", r.destination)
                    .put("walk", r.walkMinutes)
                    .put("expectedEpochMs", r.expectedEpochMs)
                    .put("triggerAtMs", r.triggerAtMs)
            )
        }
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_ACTIVE_REMINDERS, arr.toString())
            .apply()
    }

    private fun parseNearby(raw: String?): NearbyOverride? {
        if (raw.isNullOrBlank()) return null
        return try {
            val o = JSONObject(raw)
            val id = o.optInt("siteId", -1)
            val name = o.optString("name", "")
            val updated = o.optLong("updatedAtMs", 0L)
            if (id <= 0 || name.isEmpty()) return null
            NearbyOverride(
                siteId = id,
                stationName = name,
                walkMinutes = o.optInt("walkMinutes", 3).coerceIn(1, 30),
                distanceMeters = o.optInt("distMeters", 0),
                updatedAtMs = updated
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun parseSlotsJson(rawJson: String?): List<StationSlot> {
        if (rawJson.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(rawJson)
            val parsed = mutableListOf<StationSlot>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val id = obj.optInt("siteId", -1)
                val name = obj.optString("name", "").trim()
                val filter = ModeFilter.fromCode(obj.optString("filter", "AUTO"))
                val walk = obj.optInt("walkMinutes", 3).coerceIn(0, 30)
                if ((id > 0 || id == NEAR_ME_SITE_ID) && name.isNotEmpty()) {
                    parsed.add(StationSlot(id, name, filter, walk))
                }
            }
            parsed.take(3)
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun saveSitesCache(context: Context, sites: List<StationOption>) {
        val arr = JSONArray()
        for (s in sites) {
            val o = JSONObject().put("id", s.siteId).put("n", s.name)
            if (!s.lat.isNaN() && !s.lon.isNaN()) {
                o.put("la", s.lat).put("lo", s.lon)
            }
            arr.put(o)
        }
        context.applicationContext.getSharedPreferences(CACHE_PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_SITES_JSON, arr.toString())
            .putLong(KEY_SITES_FETCHED_MS, System.currentTimeMillis())
            .apply()
    }

    fun loadFreshSitesCache(context: Context): List<StationOption>? {
        val prefs = context.applicationContext.getSharedPreferences(CACHE_PREFS_NAME, Context.MODE_PRIVATE)
        val fetchedAt = prefs.getLong(KEY_SITES_FETCHED_MS, 0L)
        if (System.currentTimeMillis() - fetchedAt > SITES_CACHE_TTL_MS) return null
        val raw = prefs.getString(KEY_SITES_JSON, null) ?: return null
        return try {
            val arr = JSONArray(raw)
            val out = ArrayList<StationOption>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optInt("id", -1)
                val name = o.optString("n", "")
                if (id > 0 && name.isNotEmpty()) {
                    out.add(
                        StationOption(
                            siteId = id,
                            name = name,
                            subtitle = "SL #$id",
                            lat = o.optDouble("la", Double.NaN),
                            lon = o.optDouble("lo", Double.NaN)
                        )
                    )
                }
            }
            out.takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
    }

    fun saveCache(
        context: Context,
        cacheKey: String,
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
                    .put("transportMode", d.transportMode)
                    .put("stopPoint", d.stopPoint)
                    .put("directionCode", d.directionCode)
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

        context.applicationContext.getSharedPreferences(CACHE_PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(cacheKey, payload)
            .apply()
    }

    fun loadCache(context: Context, cacheKey: String): CachedSlotDepartures? {
        val raw = context.applicationContext.getSharedPreferences(CACHE_PREFS_NAME, Context.MODE_PRIVATE)
            .getString(cacheKey, null) ?: return null
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
                        transportMode = item.optString("transportMode", ""),
                        stopPoint = item.optString("stopPoint", ""),
                        directionCode = item.optInt("directionCode", 0),
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

    fun clearCache(context: Context, cacheKey: String) {
        context.applicationContext.getSharedPreferences(CACHE_PREFS_NAME, Context.MODE_PRIVATE).edit()
            .remove(cacheKey)
            .apply()
    }

    fun deleteWidgetState(context: Context, appWidgetId: Int) {
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .remove(KEY_ACTIVE_SLOT + appWidgetId)
            .remove(KEY_NEARBY_OVERRIDE + appWidgetId)
            .apply()
        context.applicationContext.getSharedPreferences(CACHE_PREFS_NAME, Context.MODE_PRIVATE).edit()
            .remove("w_$appWidgetId")
            .apply()
    }
}
