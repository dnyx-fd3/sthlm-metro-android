package se.dnyx.sthlmmetro

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

data class NearbyStop(
    val siteId: Int,
    val name: String,
    val distanceMeters: Int,
    val walkMinutes: Int,
    val modes: Set<String> = emptySet()
) {
    fun modeIcon(): String = when {
        "METRO" in modes -> "🚇"
        "TRAIN" in modes -> "🚆"
        "TRAM" in modes -> "🚊"
        "SHIP" in modes -> "⛴"
        else -> "🚌"
    }
}

object LocationHelper {
    private val locExecutor = Executors.newSingleThreadExecutor()

    fun hasLocationPermission(context: Context): Boolean {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    @SuppressLint("MissingPermission")
    fun getQuickLocation(context: Context, onResult: (Location?) -> Unit) {
        if (!hasLocationPermission(context)) {
            onResult(null)
            return
        }
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (lm == null) {
            onResult(null)
            return
        }

        val bestLast = getBestLastKnown(lm)
        val nowMs = System.currentTimeMillis()
        // If we have a recent fix (< 90 seconds old), return instantaneously
        if (bestLast != null && (nowMs - bestLast.time) < 90_000L) {
            onResult(bestLast)
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val provider = when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    lm.allProviders.contains(LocationManager.FUSED_PROVIDER) -> LocationManager.FUSED_PROVIDER
                lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
                lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
                else -> null
            }
            if (provider != null) {
                val signal = CancellationSignal()
                try {
                    lm.getCurrentLocation(provider, signal, locExecutor) { loc ->
                        onResult(loc ?: bestLast)
                    }
                    return
                } catch (_: Exception) {
                    // Fall through to bestLast
                }
            }
        }
        onResult(bestLast)
    }

    @SuppressLint("MissingPermission")
    private fun getBestLastKnown(lm: LocationManager): Location? {
        var best: Location? = null
        val providers = try {
            lm.getProviders(true)
        } catch (_: Exception) {
            emptyList()
        }
        for (p in providers) {
            val loc = try {
                lm.getLastKnownLocation(p)
            } catch (_: Exception) {
                null
            } ?: continue
            if (best == null || loc.time > best.time) {
                best = loc
            }
        }
        return best
    }

    fun formatDistance(meters: Int): String {
        return if (meters < 1000) {
            "$meters m"
        } else {
            String.format(Locale.US, "%.1f km", meters / 1000.0)
        }
    }

    fun findNearestStops(lat: Double, lon: Double, sites: List<StationOption>, limit: Int = 4): List<NearbyStop> {
        val candidates = sites.filter { !it.lat.isNaN() && !it.lon.isNaN() }
        val source = candidates.ifEmpty { SlApiClient.POPULAR_STATIONS }
        val popularModes = SlApiClient.POPULAR_STATIONS.associate { it.siteId to it.modes }
        return source.map { s ->
            val dist = haversineMeters(lat, lon, s.lat, s.lon)
            // Account for street detours (1.25x) at ~80 m/min walking pace
            val walkMin = ((dist * 1.25) / 80.0).roundToInt().coerceIn(1, 99)
            val mergedModes = if (s.modes.isNotEmpty()) s.modes else (popularModes[s.siteId] ?: emptySet())
            NearbyStop(s.siteId, s.name, dist.roundToInt(), walkMin, mergedModes)
        }.sortedBy { it.distanceMeters }.take(limit)
    }

    /**
     * Picks walkable nearby stops ordered strictly nearest-first while ensuring nearby Metro/Rail
     * hubs (like Universitetet from Lappis) aren't crowded out by local bus stops, and avoiding
     * cross-water ferry piers or distant 3 km+ stations.
     */
    fun findNearestDiverseStops(lat: Double, lon: Double, sites: List<StationOption>, limit: Int = 4): List<NearbyStop> {
        val allSorted = findNearestStops(lat, lon, sites, limit = Int.MAX_VALUE)
        if (allSorted.isEmpty()) return emptyList()

        val chosen = linkedMapOf<Int, NearbyStop>()
        fun addStop(stop: NearbyStop?) {
            if (stop != null && chosen.size < limit) {
                chosen.putIfAbsent(stop.siteId, stop)
            }
        }

        // 1. Always include the 2 closest stops overall so immediate neighborhood stops come first
        addStop(allSorted.getOrNull(0))
        allSorted.getOrNull(1)?.takeIf { it.distanceMeters <= 1200 }?.let { addStop(it) }

        // 2. Include nearest walkable Metro within 2.0 km (~25 min walk)
        addStop(allSorted.firstOrNull { "METRO" in it.modes && it.distanceMeters <= 2000 })

        // 3. Include nearest Train (Pendel / Roslagsbanan) within 1.8 km if not already covered
        if (chosen.values.none { "TRAIN" in it.modes }) {
            addStop(allSorted.firstOrNull { "TRAIN" in it.modes && it.distanceMeters <= 1800 })
        }

        // 4. Include nearest Tram within 1.0 km if not already covered (skip SHIP to avoid cross-bay piers)
        if (chosen.values.none { "TRAM" in it.modes }) {
            addStop(allSorted.firstOrNull { "TRAM" in it.modes && it.distanceMeters <= 1000 })
        }

        // 5. Fill remaining slots with next closest walkable stops
        for (stop in allSorted) {
            if (chosen.size >= limit) break
            if (stop.distanceMeters <= 2000 || chosen.isEmpty()) {
                addStop(stop)
            }
        }

        return chosen.values.sortedBy { it.distanceMeters }
    }

    private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLon / 2) * sin(dLon / 2)
        return 2 * r * atan2(sqrt(a), sqrt(1 - a))
    }
}
