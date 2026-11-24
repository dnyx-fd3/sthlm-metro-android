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

class SlRateLimitException : Exception("SL HTTP 429 rate limit")

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
    val delayMinutes: Int,
    val cancelled: Boolean,
    val badgeStyle: LineBadgeStyle
)

data class StationOption(
    val siteId: Int,
    val name: String,
    val subtitle: String = ""
)

object SlApiClient {
    private const val BASE_URL = "https://transport.integration.sl.se/v1"
    private val STOCKHOLM_ZONE = ZoneId.of("Europe/Stockholm")

    private val BLUE_TRUNK_BUSES = setOf(
        "1", "2", "3", "4", "6",
        "172", "173", "176", "177", "178", "179",
        "471", "474", "670", "676", "873", "875"
    )

    val POPULAR_STATIONS = listOf(
        StationOption(9001, "T-Centralen", "Metro Red/Green/Blue · Pendeltåg"),
        StationOption(9203, "Universitetet", "Metro Red 14 · Roslagsbanan · Bus 50"),
        StationOption(9117, "Odenplan", "Metro Green · Pendeltåg · Buses"),
        StationOption(9600, "Stockholms östra", "Roslagsbanan 27/28/29 · KTH"),
        StationOption(9192, "Slussen", "Metro Red/Green · Buses"),
        StationOption(9189, "Gullmarsplan", "Metro Green · Tvärbanan"),
        StationOption(9220, "Ropsten", "Metro Red 13 · Lidingöbanan 21"),
        StationOption(9293, "Liljeholmen", "Metro Red 13/14 · Tvärbanan"),
        StationOption(9112, "Alvik", "Metro Green · Tvärbanan · Line 12"),
        StationOption(9305, "Västra skogen", "Metro Blue 10/11"),
        StationOption(9529, "Älvsjö", "Pendeltåg 40–48"),
        StationOption(9509, "Solna", "Pendeltåg · Tvärbanan"),
        StationOption(9109, "Brommaplan", "Metro Green · Buses"),
        StationOption(7006, "Flemingsberg", "Pendeltåg · Campus")
    )

    private fun httpGet(urlStr: String, timeoutMs: Int = 9000): String {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "TRadarAndroid/${BuildConfig.VERSION_NAME}")
        }
        try {
            val code = conn.responseCode
            if (code == 429) {
                throw SlRateLimitException()
            }
            if (code !in 200..299) {
                throw IllegalStateException("HTTP $code")
            }
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
            conn.disconnect()
        }
    }

    fun fetchDepartures(siteId: Int, filter: ModeFilter, limit: Int = 20): List<DepartureItem> {
        val raw = httpGet("$BASE_URL/sites/$siteId/departures?forecast=45")
        val root = JSONObject(raw)
        val arr = root.optJSONArray("departures") ?: return emptyList()

        data class ParsedDep(val mode: String, val item: DepartureItem)
        val allItems = mutableListOf<ParsedDep>()

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
                ParsedDep(
                    mode = mode,
                    item = DepartureItem(
                        designation = designation.ifEmpty { "SL" },
                        destination = destination.ifEmpty { "Stockholm" },
                        display = display,
                        clockTime = clockTime,
                        expectedEpochMs = expectedEpochMs,
                        modeLabel = modeLabel,
                        delayMinutes = delayMin,
                        cancelled = cancelled,
                        badgeStyle = badgeStyle
                    )
                )
            )
        }

        val railModes = setOf("METRO", "TRAIN", "TRAM")
        val filtered = when (filter) {
            ModeFilter.ALL -> allItems.map { it.item }
            ModeFilter.BUS -> allItems.filter { it.mode == "BUS" }.map { it.item }
            ModeFilter.PENDEL -> allItems.filter {
                it.mode == "TRAIN" && it.item.badgeStyle == LineBadgeStyle.PENDEL
            }.map { it.item }
            ModeFilter.AUTO -> {
                val railOnly = allItems.filter { it.mode in railModes }.map { it.item }
                if (railOnly.isNotEmpty()) railOnly else allItems.map { it.item }
            }
        }
        return filtered.sortedBy { if (it.expectedEpochMs > 0L) it.expectedEpochMs else Long.MAX_VALUE }.take(limit)
    }

    fun fetchAllSites(): List<StationOption> {
        val raw = httpGet("$BASE_URL/sites", timeoutMs = 14000)
        val arr = JSONArray(raw)
        val out = ArrayList<StationOption>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val id = obj.optInt("id", -1)
            val name = obj.optString("name", "").trim()
            if (id > 0 && name.isNotEmpty()) {
                out.add(StationOption(id, name, "SL Site #$id"))
            }
        }
        return out
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
