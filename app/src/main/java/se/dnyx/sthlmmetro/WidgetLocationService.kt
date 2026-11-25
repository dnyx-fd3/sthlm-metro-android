package se.dnyx.sthlmmetro

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import java.util.concurrent.Executors

class WidgetLocationService : Service() {

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val widgetId = intent?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID

        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID || !LocationHelper.hasLocationPermission(this)) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        ensureChannel()
        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("T-Radar")
            .setContentText("Finding closest SL stop…")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_DEFERRED)
            .build()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceCompat.startForeground(
                    this,
                    NOTIF_ID,
                    notif,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                )
            } else {
                startForeground(NOTIF_ID, notif)
            }
        } catch (_: Exception) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        // Safety timeout so service can never hang if GPS is off
        val timeoutRunnable = Runnable { finishCleanly(startId) }
        mainHandler.postDelayed(timeoutRunnable, 11_000L)

        LocationHelper.getQuickLocation(this) { loc ->
            executor.execute {
                try {
                    val manager = AppWidgetManager.getInstance(this)
                    if (loc == null) {
                        // Fall back to last resolved near-me stop if any, or show helpful GPS note
                        val cfg = WidgetPrefs.load(this, widgetId)
                        if (cfg.resolvedNearMe != null) {
                            MetroWidgetProvider.refreshWidgetBlocking(this, manager, widgetId, trigger = "near_me_cached_gps")
                        } else {
                            MetroWidgetProvider.showWidgetStatus(this, manager, widgetId, "GPS off · Tap ↻", "Enable GPS & tap ↻")
                        }
                        return@execute
                    }

                    val sites = WidgetPrefs.loadFreshSitesCache(this) ?: try {
                        val fresh = SlApiClient.fetchAllSites()
                        if (fresh.isNotEmpty()) WidgetPrefs.saveSitesCache(this, fresh)
                        fresh
                    } catch (_: Exception) {
                        SlApiClient.POPULAR_STATIONS
                    }

                    val nearest = LocationHelper.findNearestStops(loc.latitude, loc.longitude, sites, limit = 1).firstOrNull()
                    if (nearest != null) {
                        AnalyticsHelper.logNearMeUsed(this, "widget_silent", nearest.name, nearest.walkMinutes)
                        WidgetPrefs.setResolvedNearMe(
                            this,
                            widgetId,
                            NearbyOverride(
                                siteId = nearest.siteId,
                                stationName = nearest.name,
                                walkMinutes = nearest.walkMinutes,
                                distanceMeters = nearest.distanceMeters,
                                updatedAtMs = System.currentTimeMillis()
                            )
                        )
                        MetroWidgetProvider.refreshWidgetBlocking(this, manager, widgetId, trigger = "near_me_silent")
                    }
                } finally {
                    mainHandler.removeCallbacks(timeoutRunnable)
                    finishCleanly(startId)
                }
            }
        }

        return START_NOT_STICKY
    }

    private fun finishCleanly(startId: Int) {
        mainHandler.post {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
        }
    }

    private fun ensureChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Widget Location Lookup", NotificationManager.IMPORTANCE_LOW).apply {
                setSound(null, null)
                enableVibration(false)
            }
        )
    }

    companion object {
        private const val CHANNEL_ID = "tradar_widget_loc_v1"
        private const val NOTIF_ID = 9201
    }
}
