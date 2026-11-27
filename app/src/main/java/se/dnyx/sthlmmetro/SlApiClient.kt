package se.dnyx.sthlmmetro

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.time.Duration
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap

class SlRateLimitException : Exception("SL HTTP 429 rate limit")
class SlHttpException(val statusCode: Int) : Exception("SL HTTP $statusCode")

enum class LineBadgeStyle {
    RED, GREEN, BLUE, PENDEL, ROSLAG, TRAM, BUS_BLUE, BUS_RED, CYAN
}

data class DepartureItem(
    val designation: String,
    val destination: String,
    val display: String,
    val clockTime: String,
    val expectedEpochMs: Long,
    val modeLabel: String,
    val transportMode: String,
    val stopPoint: String,
    val directionCode: Int,
    val delayMinutes: Int,
    val cancelled: Boolean,
    val badgeStyle: LineBadgeStyle,
    val originSiteId: Int = 0,
    val originStationName: String = "",
    val originWalkMinutes: Int = 0,
    val originDistanceMeters: Int = 0,
    val originModeIcon: String = ""
) {
    fun standOrTrackLabel(): String {
        val clean = stopPoint.trim()
        if (clean.isEmpty() || clean.equals("null", ignoreCase = true)) return ""
        if (clean.length > 5) return clean
        return when (transportMode) {
            "BUS" -> "Stand $clean"
            "METRO", "TRAIN", "TRAM" -> "Track $clean"
            "SHIP" -> "Quay $clean"
            else -> "Stand $clean"
        }
    }
}

data class SlDeviation(
    val header: String,
    val details: String,
    val scopeLabel: String
)

data class StationOption(
    val siteId: Int,
    val name: String,
    val subtitle: String = "",
    val lat: Double = Double.NaN,
    val lon: Double = Double.NaN,
    val modes: Set<String> = emptySet()
)

object SlApiClient {
    private val WORKER_BASE_URL = "${BuildConfig.RADAR_API_URL.trimEnd('/')}/v1"
    private const val DIRECT_SL_BASE_URL = "https://transport.integration.sl.se/v1"
    private const val DEVIATIONS_URL = "https://deviations.integration.sl.se/v1/messages"
    private val STOCKHOLM_ZONE = ZoneId.of("Europe/Stockholm")

    @Volatile
    var lastSource: String = "edge"
        private set

    private val cachedStopDeviations = ConcurrentHashMap<Int, List<SlDeviation>>()

    private val BLUE_TRUNK_BUSES = setOf(
        "1", "2", "3", "4", "6",
        "172", "173", "176", "177", "178", "179",
        "471", "474", "670", "676", "873", "875"
    )

    val POPULAR_STATIONS = listOf(
        StationOption(9001, "T-Centralen", "Metro · Pendel · Tram", 59.3310, 18.0593, setOf("METRO", "TRAIN", "TRAM", "BUS")),
        StationOption(9203, "Universitetet", "Metro 14 · Roslagsbanan · Bus", 59.3656, 18.0550, setOf("METRO", "TRAIN", "BUS")),
        StationOption(9117, "Odenplan", "Metro · Pendel · Bus", 59.3428, 18.0499, setOf("METRO", "TRAIN", "BUS")),
        StationOption(9600, "Stockholms östra", "Roslagsbanan · Metro", 59.3457, 18.0715, setOf("TRAIN", "METRO", "BUS")),
        StationOption(9192, "Slussen", "Metro · Saltsjöbanan · Bus", 59.3195, 18.0722, setOf("METRO", "TRAIN", "BUS", "SHIP")),
        StationOption(9189, "Gullmarsplan", "Metro · Tvärbanan · Bus", 59.2989, 18.0810, setOf("METRO", "TRAM", "BUS")),
        StationOption(9220, "Ropsten", "Metro 13 · Lidingöbanan", 59.3574, 18.1024, setOf("METRO", "TRAM", "BUS")),
        StationOption(9293, "Liljeholmen", "Metro · Tvärbanan · Bus", 59.3106, 18.0227, setOf("METRO", "TRAM", "BUS")),
        StationOption(9112, "Alvik", "Metro · Tvärbanan · Nockeby", 59.3335, 17.9806, setOf("METRO", "TRAM", "BUS")),
        StationOption(9305, "Västra skogen", "Metro Blue 10/11", 59.3474, 18.0038, setOf("METRO", "BUS")),
        StationOption(9529, "Älvsjö", "Pendeltåg 40–48", 59.2784, 18.0108, setOf("TRAIN", "BUS")),
        StationOption(9509, "Solna", "Pendeltåg · Tvärbanan", 59.3653, 18.0099, setOf("TRAIN", "TRAM", "BUS")),
        StationOption(9109, "Brommaplan", "Metro Green · Bus", 59.3385, 17.9396, setOf("METRO", "BUS")),
        StationOption(7006, "Flemingsberg", "Pendeltåg · Campus", 59.2185, 17.9467, setOf("TRAIN", "BUS"))
    )

    private val POPULAR_MODES_BY_ID: Map<Int, Set<String>> =
        POPULAR_STATIONS.associate { it.siteId to it.modes }

    private fun httpGet(urlStr: String, timeoutMs: Int = 12000, keepAlive: Boolean = false): String {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            setRequestProperty("Accept", "application/json")
            if (!keepAlive) {
                setRequestProperty("Connection", "close")
            }
            setRequestProperty("User-Agent", "TRadarAndroid/${BuildConfig.VERSION_NAME}")
        }
        try {
            val code = conn.responseCode
            if (code == 429) throw SlRateLimitException()
            if (code !in 200..299) throw SlHttpException(code)
            BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { reader ->
                val sb = StringBuilder()
                var line = reader.readLine()
                while (line != null) {
                    sb.append(line)
                    line = reader.readLine()
                }
                return sb.toString()
            }
        } finally {
            if (!keepAlive) {
                conn.disconnect()
            }
        }
    }

    private fun fetchDeparturesRaw(siteId: Int): String {
        val path = "/sites/$siteId/departures?forecast=45"
        return try {
            val res = httpGet("$WORKER_BASE_URL$path", timeoutMs = 8000, keepAlive = true)
            lastSource = "edge"
            res
        } catch (_: Exception) {
            val res = httpGet("$DIRECT_SL_BASE_URL$path", timeoutMs = 12000, keepAlive = false)
            lastSource = "direct"
            res
        }
    }

    fun fetchDepartures(siteId: Int, filter: ModeFilter, limit: Int = 24): List<DepartureItem> {
        val raw = fetchDeparturesRaw(siteId)
        val root = JSONObject(raw)
        cacheStopDeviationsFromPayload(siteId, root.optJSONArray("stop_deviations"))
        val arr = root.optJSONArray("departures") ?: return emptyList()

        val allItems = mutableListOf<DepartureItem>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val line = obj.optJSONObject("line") ?: continue
            val mode = line.optString("transport_mode", "").uppercase()
            val designation = line.optString("designation", "SL").trim().uppercase()
            val destination = obj.optString("destination", "Stockholm").trim()
            val displayRaw = obj.optString("display", "").trim()
            val expected = obj.optString("expected", "")
            val scheduled = obj.optString("scheduled", expected)
            val state = obj.optString("state", "").uppercase()
            val cancelled = state == "CANCELLED"
            val dirCode = obj.optInt("direction_code", 0)
            val stopPointObj = obj.optJSONObject("stop_point")
            val rawStopPoint = if (stopPointObj != null && !stopPointObj.isNull("designation")) {
                stopPointObj.optString("designation", "").trim()
            } else {
                ""
            }
            val stopPoint = if (rawStopPoint.equals("null", ignoreCase = true)) "" else rawStopPoint

            val effectiveIso = expected.ifEmpty { scheduled }
            val expectedEpochMs = parseStockholmEpochMs(effectiveIso)
            val clockTime = extractClock(effectiveIso)
            val display = when {
                cancelled -> "—"
                displayRaw.equals("Nu", ignoreCase = true) -> "NOW"
                displayRaw.isNotEmpty() -> displayRaw
                else -> clockTime
            }

            val delayMin = computeDelayMinutes(scheduled, expected)
            val badgeStyle = resolveBadgeStyle(mode, designation)
            val modeLabel = resolveModeLabel(mode, designation)

            allItems.add(
                DepartureItem(
                    designation = designation.ifEmpty { "SL" },
                    destination = destination.ifEmpty { "Stockholm" },
                    display = display,
                    clockTime = clockTime,
                    expectedEpochMs = expectedEpochMs,
                    modeLabel = modeLabel,
                    transportMode = mode,
                    stopPoint = stopPoint,
                    directionCode = dirCode,
                    delayMinutes = delayMin,
                    cancelled = cancelled,
                    badgeStyle = badgeStyle
                )
            )
        }

        val railModes = setOf("METRO", "TRAIN", "TRAM")
        val filtered = when (filter) {
            ModeFilter.ALL -> allItems
            ModeFilter.METRO -> allItems.filter { it.transportMode == "METRO" }
            ModeFilter.BUS -> allItems.filter { it.transportMode == "BUS" }
            ModeFilter.PENDEL -> allItems.filter {
                it.transportMode == "TRAIN" && it.badgeStyle == LineBadgeStyle.PENDEL
            }
            ModeFilter.AUTO -> {
                val railOnly = allItems.filter { it.transportMode in railModes }
                if (railOnly.isNotEmpty()) railOnly else allItems
            }
        }
        return filtered.sortedBy { if (it.expectedEpochMs > 0L) it.expectedEpochMs else Long.MAX_VALUE }.take(limit)
    }

    private fun cacheStopDeviationsFromPayload(siteId: Int, stopDevs: JSONArray?) {
        if (stopDevs == null || stopDevs.length() == 0) {
            cachedStopDeviations.remove(siteId)
            return
        }
        val list = mutableListOf<SlDeviation>()
        val seen = HashSet<String>()
        for (i in 0 until stopDevs.length()) {
            val sd = stopDevs.optJSONObject(i) ?: continue
            val msg = if (!sd.isNull("message")) sd.optString("message", "").trim() else ""
            if (msg.isNotEmpty() && seen.add(msg.lowercase())) {
                val firstLine = msg.lineSequence().firstOrNull()?.trim().orEmpty().ifEmpty { msg }
                val header = if (firstLine.length > 90) firstLine.take(87) + "…" else firstLine
                list.add(SlDeviation(header = header, details = msg, scopeLabel = "Stop alert"))
            }
            if (list.size >= 3) break
        }
        if (list.isNotEmpty()) {
            cachedStopDeviations[siteId] = list
        } else {
            cachedStopDeviations.remove(siteId)
        }
    }

    fun fetchAllSites(): List<StationOption> {
        val raw = try {
            httpGet("$DIRECT_SL_BASE_URL/sites?expand=true", timeoutMs = 20000)
        } catch (_: Exception) {
            httpGet("$DIRECT_SL_BASE_URL/sites", timeoutMs = 15000)
        }
        val arr = JSONArray(raw)
        val out = ArrayList<StationOption>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val id = obj.optInt("id", -1)
            val name = obj.optString("name", "").trim()
            if (id > 0 && name.isNotEmpty()) {
                val lat = obj.optDouble("lat", Double.NaN)
                val lon = obj.optDouble("lon", Double.NaN)
                val modes = mutableSetOf<String>()
                val stopAreas = obj.optJSONArray("stop_areas")
                if (stopAreas != null) {
                    for (j in 0 until stopAreas.length()) {
                        val sa = stopAreas.optJSONObject(j) ?: continue
                        val type = sa.optString("type", "").uppercase()
                        when {
                            type.contains("METRO") -> modes.add("METRO")
                            type.contains("RAIL") || type.contains("TRAIN") -> modes.add("TRAIN")
                            type.contains("TRAM") -> modes.add("TRAM")
                            type.contains("SHIP") || type.contains("FERRY") -> modes.add("SHIP")
                            type.contains("BUS") -> modes.add("BUS")
                        }
                    }
                }
                POPULAR_MODES_BY_ID[id]?.let { modes.addAll(it) }
                out.add(StationOption(id, name, "SL #$id", lat, lon, modes))
            }
        }
        return out
    }

    fun fetchSiteDeviations(siteIds: List<Int>): List<SlDeviation> {
        val cleanIds = siteIds.filter { it > 0 }.distinct().take(4)
        if (cleanIds.isEmpty()) return emptyList()
        val seenHeaders = HashSet<String>()
        val results = mutableListOf<SlDeviation>()

        val raw = try {
            val siteParams = cleanIds.joinToString("&") { "site=$it" }
            httpGet("$DEVIATIONS_URL?future=false&$siteParams", timeoutMs = 8000)
        } catch (_: Exception) {
            null
        }
        val arr = if (raw != null) {
            try {
                JSONArray(raw)
            } catch (_: Exception) {
                JSONArray()
            }
        } else {
            JSONArray()
        }

        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            var header = ""
            var details = ""
            var scopeAlias = ""

            val variants = obj.optJSONArray("message_variants")
            if (variants != null && variants.length() > 0) {
                var chosen: JSONObject? = null
                for (j in 0 until variants.length()) {
                    val v = variants.optJSONObject(j) ?: continue
                    val lang = v.optString("language", "").lowercase()
                    if (lang.startsWith("en")) {
                        chosen = v
                        break
                    }
                    if (chosen == null) chosen = v
                }
                if (chosen != null) {
                    if (!chosen.isNull("header")) header = chosen.optString("header", "").trim()
                    if (!chosen.isNull("details")) details = chosen.optString("details", "").trim()
                    if (!chosen.isNull("scope_alias")) scopeAlias = chosen.optString("scope_alias", "").trim()
                }
            }

            if (header.isEmpty() && !obj.isNull("header")) {
                header = obj.optString("header", "").trim()
            }
            if (details.isEmpty() && !obj.isNull("details")) {
                details = obj.optString("details", "").trim()
            }
            if (header.isEmpty()) continue

            if (scopeAlias.isEmpty()) {
                val scopeObj = obj.optJSONObject("scope")
                val linesArr = scopeObj?.optJSONArray("lines")
                if (linesArr != null && linesArr.length() > 0) {
                    val lineNames = mutableListOf<String>()
                    for (k in 0 until minOf(linesArr.length(), 4)) {
                        val lObj = linesArr.optJSONObject(k) ?: continue
                        if (!lObj.isNull("designation")) {
                            val d = lObj.optString("designation", "").trim()
                            if (d.isNotEmpty()) lineNames.add(d)
                        }
                    }
                    if (lineNames.isNotEmpty()) {
                        scopeAlias = "Line ${lineNames.joinToString(", ")}"
                    }
                }
            }

            if (seenHeaders.add(header.lowercase())) {
                results.add(SlDeviation(header = header, details = details, scopeLabel = scopeAlias))
            }
            if (results.size >= 5) break
        }

        if (results.size < 5) {
            for (id in cleanIds) {
                for (sd in cachedStopDeviations[id].orEmpty()) {
                    if (seenHeaders.add(sd.header.lowercase())) {
                        results.add(sd)
                    }
                    if (results.size >= 5) break
                }
                if (results.size >= 5) break
            }
        }
        return results
    }

    private fun parseStockholmEpochMs(iso: String): Long {
        if (iso.length < 19) return 0L
        return try {
            val hasOffset = iso.endsWith("Z", ignoreCase = true) ||
                iso.indexOf('+', 10) >= 0 ||
                iso.indexOf('-', 10) >= 0
            if (hasOffset) {
                OffsetDateTime.parse(iso).toInstant().toEpochMilli()
            } else {
                LocalDateTime.parse(iso.substring(0, 19))
                    .atZone(STOCKHOLM_ZONE)
                    .toInstant()
                    .toEpochMilli()
            }
        } catch (_: Exception) {
            0L
        }
    }

    private fun extractClock(iso: String): String {
        val tIdx = iso.indexOf('T')
        if (tIdx >= 0 && iso.length >= tIdx + 6) {
            return iso.substring(tIdx + 1, tIdx + 6)
        }
        return ""
    }

    private fun computeDelayMinutes(scheduled: String, expected: String): Int {
        if (scheduled.length < 19 || expected.length < 19) return 0
        return try {
            val s = LocalDateTime.parse(scheduled.substring(0, 19))
            val e = LocalDateTime.parse(expected.substring(0, 19))
            val secs = Duration.between(s, e).seconds
            if (secs >= 60) (secs / 60).toInt() else 0
        } catch (_: Exception) {
            0
        }
    }

    private fun resolveBadgeStyle(mode: String, designation: String): LineBadgeStyle {
        val base = designation.replace(Regex("[SXNV]$"), "")
        return when {
            mode == "BUS" && base in BLUE_TRUNK_BUSES -> LineBadgeStyle.BUS_BLUE
            mode == "BUS" -> LineBadgeStyle.BUS_RED
            base in setOf("13", "14") && mode == "METRO" -> LineBadgeStyle.RED
            base in setOf("17", "18", "19") && mode == "METRO" -> LineBadgeStyle.GREEN
            base in setOf("10", "11") && mode == "METRO" -> LineBadgeStyle.BLUE
            base in setOf("27", "28", "29") -> LineBadgeStyle.ROSLAG
            mode == "TRAIN" || base in setOf("40", "41", "42", "43", "44", "48") -> LineBadgeStyle.PENDEL
            mode == "TRAM" || base in setOf("7", "12", "21", "30", "31") -> LineBadgeStyle.TRAM
            else -> LineBadgeStyle.CYAN
        }
    }

    private fun resolveModeLabel(mode: String, designation: String): String {
        val base = designation.replace(Regex("[SXNV]$"), "")
        return when {
            base in setOf("27", "28", "29") -> "Roslagsbanan"
            mode == "METRO" -> "Tunnelbana"
            mode == "TRAIN" -> "Pendeltåg"
            mode == "TRAM" -> "Tram"
            mode == "BUS" -> if (base in BLUE_TRUNK_BUSES) "Blue Bus" else "Bus"
            mode == "SHIP" -> "Ferry"
            else -> "SL"
        }
    }
}
