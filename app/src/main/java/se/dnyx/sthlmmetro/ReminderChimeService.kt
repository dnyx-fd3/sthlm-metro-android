package se.dnyx.sthlmmetro

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

class ReminderChimeService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var mediaPlayer: MediaPlayer? = null
    private var playCount = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISMISS) {
            stopChimeAndSelf()
            return START_NOT_STICKY
        }

        val station = intent?.getStringExtra(ReminderAlertReceiver.EXTRA_STATION).orEmpty()
        val line = intent?.getStringExtra(ReminderAlertReceiver.EXTRA_LINE).orEmpty()
        val dest = intent?.getStringExtra(ReminderAlertReceiver.EXTRA_DEST).orEmpty()
        val walk = intent?.getIntExtra(ReminderAlertReceiver.EXTRA_WALK, 3) ?: 3

        ensureChannel(this)
        val notif = buildNotification(this, station, line, dest, walk, includeDismiss = true)

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceCompat.startForeground(
                    this,
                    NOTIF_ID_ACTIVE,
                    notif,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED
                )
            } else {
                startForeground(NOTIF_ID_ACTIVE, notif)
            }
        } catch (_: Exception) {
            // Continue even if OEM throttles FGS
        }

        // Play custom T-Radar chime (twice gently) + rhythmic commuter vibration
        triggerCommuterVibration(this)
        playCustomChimeTwice()

        // Auto-stop foreground chime service after ~4.5s, leaving a clean summary banner
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({
            postSummaryNotification(this, station, line, dest, walk)
            stopChimeAndSelf()
        }, 4500L)

        return START_NOT_STICKY
    }

    private fun playCustomChimeTwice() {
        val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        // Respect Silent/Vibrate mode so we never blast audio in a library (vibration still runs!)
        if (am != null && am.ringerMode != AudioManager.RINGER_MODE_NORMAL) {
            return
        }
        playCount = 0
        startSingleChime()
    }

    private fun startSingleChime() {
        releasePlayer()
        try {
            val afd = resources.openRawResourceFd(R.raw.tradar_chime) ?: return
            val mp = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                afd.close()
                setVolume(0.85f, 0.85f)
                setOnCompletionListener {
                    playCount++
                    if (playCount < 2) {
                        handler.postDelayed({
                            triggerCommuterVibration(this@ReminderChimeService)
                            startSingleChime()
                        }, 450L)
                    }
                }
                prepare()
                start()
            }
            mediaPlayer = mp
        } catch (_: Exception) {
            releasePlayer()
        }
    }

    private fun releasePlayer() {
        try {
            mediaPlayer?.stop()
        } catch (_: Exception) {
            // Ignore
        }
        try {
            mediaPlayer?.release()
        } catch (_: Exception) {
            // Ignore
        }
        mediaPlayer = null
    }

    private fun stopChimeAndSelf() {
        handler.removeCallbacksAndMessages(null)
        releasePlayer()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        releasePlayer()
        super.onDestroy()
    }

    companion object {
        const val ACTION_PLAY = "se.dnyx.sthlmmetro.ACTION_CHIME_PLAY"
        const val ACTION_DISMISS = "se.dnyx.sthlmmetro.ACTION_CHIME_DISMISS"
        private const val CHANNEL_ID = "tradar_commute_chime_v1"
        private const val NOTIF_ID_ACTIVE = 9101
        private const val NOTIF_ID_SUMMARY = 9102

        fun ensureChannel(context: Context) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            if (nm.getNotificationChannel(CHANNEL_ID) != null) return
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Commute Departure Reminders",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alerts when it is time to walk to your SL stop."
                // Sound & vibration are driven directly by T-Radar's custom chime & waveform
                setSound(null, null)
                enableVibration(false)
            }
            nm.createNotificationChannel(channel)
        }

        fun triggerCommuterVibration(context: Context) {
            val pattern = longArrayOf(0, 200, 110, 200, 110, 340)
            try {
                val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                    vm?.defaultVibrator
                } else {
                    @Suppress("DEPRECATION")
                    context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                }
                vibrator?.vibrate(VibrationEffect.createWaveform(pattern, -1))
            } catch (_: Exception) {
                // Ignore
            }
        }

        fun buildNotification(
            context: Context,
            station: String,
            line: String,
            dest: String,
            walkMinutes: Int,
            includeDismiss: Boolean
        ): Notification {
            val openPi = PendingIntent.getActivity(
                context,
                9100,
                Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val title = "Leave now · $line → $dest"
            val text = if (walkMinutes > 0) {
                "Departs in $walkMinutes min from $station"
            } else {
                "Departing soon from $station"
            }

            val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(title)
                .setContentText(text)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setAutoCancel(true)
                .setContentIntent(openPi)

            if (includeDismiss) {
                val dismissPi = PendingIntent.getService(
                    context,
                    9101,
                    Intent(context, ReminderChimeService::class.java).apply { action = ACTION_DISMISS },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                builder.addAction(0, "Dismiss", dismissPi)
            }
            return builder.build()
        }

        fun postSummaryNotification(context: Context, station: String, line: String, dest: String, walkMinutes: Int) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED
                if (!granted) return
            }
            ensureChannel(context)
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            nm.notify(NOTIF_ID_SUMMARY, buildNotification(context, station, line, dest, walkMinutes, includeDismiss = false))
        }

        fun playFallbackVibrateAndNotify(context: Context, station: String, line: String, dest: String, walkMinutes: Int) {
            triggerCommuterVibration(context)
            postSummaryNotification(context, station, line, dest, walkMinutes)
        }
    }
}
