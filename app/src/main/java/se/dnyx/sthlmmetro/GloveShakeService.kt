package se.dnyx.sthlmmetro

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlin.math.sqrt

class GloveShakeService : Service() {

    private var sensorManager: SensorManager? = null
    private var sensorRegistered = false
    private var screenReceiverRegistered = false
    private var lastShakeMs = 0L

    private val shakeListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent?) {
            if (appInForeground) return
            val v = event?.values ?: return
            if (v.size < 3) return
            val gx = v[0] / SensorManager.GRAVITY_EARTH
            val gy = v[1] / SensorManager.GRAVITY_EARTH
            val gz = v[2] / SensorManager.GRAVITY_EARTH
            val gForce = sqrt(gx * gx + gy * gy + gz * gz)
            if (gForce > 2.4f) {
                val now = System.currentTimeMillis()
                if (now - lastShakeMs > 2200L) {
                    lastShakeMs = now
                    vibrateShort()
                    Toast.makeText(applicationContext, "↻ Refreshing T-Radar widget…", Toast.LENGTH_SHORT).show()
                    MetroWidgetProvider.refreshAllWidgets(applicationContext, trigger = "home_shake")
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> unregisterAccel()
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> registerAccelIfInteractive()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(screenStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(screenStateReceiver, filter)
            }
            screenReceiverRegistered = true
        } catch (_: Exception) {
            // Ignore
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_TURN_OFF || !WidgetPrefs.isGloveHomeShakeEnabled(this)) {
            WidgetPrefs.setGloveHomeShakeEnabled(this, false)
            stopSelfCleanly()
            return START_NOT_STICKY
        }

        ensureChannel()
        val notif = buildOngoingNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceCompat.startForeground(
                    this,
                    NOTIF_ID,
                    notif,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIF_ID, notif)
            }
        } catch (_: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }

        registerAccelIfInteractive()
        return START_STICKY
    }

    override fun onDestroy() {
        unregisterAccel()
        if (screenReceiverRegistered) {
            try {
                unregisterReceiver(screenStateReceiver)
            } catch (_: Exception) {
                // Ignore
            }
            screenReceiverRegistered = false
        }
        super.onDestroy()
    }

    private fun registerAccelIfInteractive() {
        if (sensorRegistered) return
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (pm != null && !pm.isInteractive) return
        val accel = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
        try {
            sensorManager?.registerListener(shakeListener, accel, SensorManager.SENSOR_DELAY_UI)
            sensorRegistered = true
        } catch (_: Exception) {
            // Ignore
        }
    }

    private fun unregisterAccel() {
        if (!sensorRegistered) return
        try {
            sensorManager?.unregisterListener(shakeListener)
        } catch (_: Exception) {
            // Ignore
        }
        sensorRegistered = false
    }

    private fun stopSelfCleanly() {
        unregisterAccel()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun vibrateShort() {
        try {
            val vib = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            vib?.vibrate(VibrationEffect.createOneShot(45L, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (_: Exception) {
            // Ignore
        }
    }

    private fun ensureChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Glove Mode Home Screen Shake",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Listens for wrist shake while screen is awake to refresh widgets."
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            }
        )
    }

    private fun buildOngoingNotification(): Notification {
        val openPi = PendingIntent.getActivity(
            this,
            9301,
            Intent(this, SettingsActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val offPi = PendingIntent.getService(
            this,
            9302,
            Intent(this, GloveShakeService::class.java).apply { action = ACTION_TURN_OFF },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("🧤 Glove Shake active")
            .setContentText("Shake while screen is on to refresh T-Radar widgets")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(openPi)
            .addAction(0, "Turn off", offPi)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "tradar_glove_shake_v1"
        private const val NOTIF_ID = 9300
        const val ACTION_TURN_OFF = "se.dnyx.sthlmmetro.ACTION_STOP_GLOVE_SHAKE"

        @Volatile
        var appInForeground: Boolean = false

        fun syncState(context: Context) {
            val appCtx = context.applicationContext
            val intent = Intent(appCtx, GloveShakeService::class.java)
            if (WidgetPrefs.isGloveHomeShakeEnabled(appCtx)) {
                try {
                    ContextCompat.startForegroundService(appCtx, intent)
                } catch (_: Exception) {
                    // Ignore if blocked in background
                }
            } else {
                try {
                    appCtx.stopService(intent)
                } catch (_: Exception) {
                    // Ignore
                }
            }
        }
    }
}
