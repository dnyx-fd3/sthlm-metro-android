package se.dnyx.sthlmmetro

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

sealed class ReminderToggleResult {
    data class Scheduled(val reminder: ActiveReminder, val ringTimeStr: String) : ReminderToggleResult()
    data object Cancelled : ReminderToggleResult()
    data class TooClose(val remainingSeconds: Int) : ReminderToggleResult()
    data object Departed : ReminderToggleResult()
}

object ReminderManager {
    private const val COUNTDOWN_CHANNEL_ID = "tradar_live_countdown_v1"
    private val clockFmt = DateTimeFormatter.ofPattern("HH:mm")

    fun buildKey(siteId: Int, designation: String, destination: String, expectedEpochMs: Long): String {
        return "${siteId}_${designation}_${destination}_$expectedEpochMs"
    }

    fun formatClock(epochMs: Long): String {
        return Instant.ofEpochMilli(epochMs)
            .atZone(ZoneId.systemDefault())
            .toLocalTime()
            .format(clockFmt)
    }

    fun toggleReminder(
        context: Context,
        siteId: Int,
        stationName: String,
        designation: String,
        destination: String,
        expectedEpochMs: Long,
        walkMinutes: Int
    ): ReminderToggleResult {
        val key = buildKey(siteId, designation, destination, expectedEpochMs)
        val existing = WidgetPrefs.findReminder(context, key)
        if (existing != null) {
            cancelReminder(context, key)
            return ReminderToggleResult.Cancelled
        }
        return scheduleReminder(
            context = context,
            siteId = siteId,
            stationName = stationName,
            designation = designation,
            destination = destination,
            expectedEpochMs = expectedEpochMs,
            walkMinutes = walkMinutes
        )
    }

    fun scheduleReminder(
        context: Context,
        siteId: Int,
        stationName: String,
        designation: String,
        destination: String,
        expectedEpochMs: Long,
        walkMinutes: Int
    ): ReminderToggleResult {
        val nowMs = System.currentTimeMillis()
        val remainingSec = ((expectedEpochMs - nowMs) / 1000L).toInt()
        if (expectedEpochMs <= 0L || remainingSec <= 0) {
            return ReminderToggleResult.Departed
        }

        val timerSec = remainingSec - walkMinutes * 60
        if (timerSec < 10) {
            return ReminderToggleResult.TooClose(remainingSec)
        }

        val triggerAtMs = nowMs + timerSec * 1000L
        val key = buildKey(siteId, designation, destination, expectedEpochMs)
        val reqCode = (key.hashCode() and 0x0FFFFFFF) + 10_000

        val reminder = ActiveReminder(
            key = key,
            requestCode = reqCode,
            stationName = stationName,
            designation = designation,
            destination = destination,
            walkMinutes = walkMinutes,
            expectedEpochMs = expectedEpochMs,
            triggerAtMs = triggerAtMs
        )

        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val firePi = buildAlarmPendingIntent(context, reminder)
        val showPi = PendingIntent.getActivity(
            context,
            reqCode + 1,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        try {
            // setAlarmClock() wakes through Doze & battery saver reliably with USE_EXACT_ALARM
            am.setAlarmClock(AlarmManager.AlarmClockInfo(triggerAtMs, showPi), firePi)
        } catch (_: SecurityException) {
            try {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, firePi)
            } catch (_: SecurityException) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, firePi)
            }
        }

        WidgetPrefs.saveReminder(context, reminder)
        postCountdownNotification(context, reminder)
        AnalyticsHelper.logReminderSet(context, stationName, designation, destination, walkMinutes, timerSec)
        return ReminderToggleResult.Scheduled(reminder, formatClock(triggerAtMs))
    }

    fun cancelReminder(context: Context, key: String): Boolean {
        val removed = WidgetPrefs.removeReminder(context, key) ?: return false
        dismissCountdownNotification(context, removed.requestCode)
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return true
        am.cancel(buildAlarmPendingIntent(context, removed))
        return true
    }

    fun dismissCountdownNotification(context: Context, requestCode: Int) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        nm.cancel(requestCode + 50_000)
    }

    private fun postCountdownNotification(context: Context, reminder: ActiveReminder) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (nm.getNotificationChannel(COUNTDOWN_CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    COUNTDOWN_CHANNEL_ID,
                    "Live Walk Countdown",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Lock-screen live countdown until you need to start walking."
                    setSound(null, null)
                    enableVibration(false)
                    lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                }
            )
        }

        val openPi = PendingIntent.getActivity(
            context,
            reminder.requestCode + 2,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val cancelPi = PendingIntent.getBroadcast(
            context,
            reminder.requestCode + 3,
            Intent(context, ReminderAlertReceiver::class.java).apply {
                action = ReminderAlertReceiver.ACTION_CANCEL_REMINDER
                putExtra(ReminderAlertReceiver.EXTRA_KEY, reminder.key)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val ringTime = formatClock(reminder.triggerAtMs)
        val depTime = formatClock(reminder.expectedEpochMs)
        val notif = NotificationCompat.Builder(context, COUNTDOWN_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("🔔 ${reminder.designation} → ${reminder.destination} ($depTime)")
            .setContentText("${reminder.stationName} · Leave at $ringTime (${reminder.walkMinutes} min walk)")
            .setWhen(reminder.triggerAtMs)
            .setShowWhen(true)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(openPi)
            .addAction(0, "Cancel", cancelPi)
            .build()

        nm.notify(reminder.requestCode + 50_000, notif)
    }

    private fun buildAlarmPendingIntent(context: Context, reminder: ActiveReminder): PendingIntent {
        val intent = Intent(context, ReminderAlertReceiver::class.java).apply {
            action = ReminderAlertReceiver.ACTION_FIRE_REMINDER
            putExtra(ReminderAlertReceiver.EXTRA_KEY, reminder.key)
            putExtra(ReminderAlertReceiver.EXTRA_STATION, reminder.stationName)
            putExtra(ReminderAlertReceiver.EXTRA_LINE, reminder.designation)
            putExtra(ReminderAlertReceiver.EXTRA_DEST, reminder.destination)
            putExtra(ReminderAlertReceiver.EXTRA_WALK, reminder.walkMinutes)
        }
        return PendingIntent.getBroadcast(
            context,
            reminder.requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
