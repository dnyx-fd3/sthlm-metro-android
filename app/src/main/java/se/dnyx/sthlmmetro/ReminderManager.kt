package se.dnyx.sthlmmetro

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.provider.AlarmClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

sealed class ReminderToggleResult {
    data class Scheduled(
        val reminder: ActiveReminder,
        val ringTimeStr: String,
        val phoneTimerSet: Boolean
    ) : ReminderToggleResult()
    data object Cancelled : ReminderToggleResult()
    data class TooClose(val remainingSeconds: Int) : ReminderToggleResult()
    data object Departed : ReminderToggleResult()
}

object ReminderManager {
    private const val COUNTDOWN_CHANNEL_ID = "tradar_live_countdown_v1"
    private const val HURRY_ALERT_CHANNEL_ID = "tradar_hurry_banner_v1"
    private const val HURRY_NOTIF_ID = 9105
    private val clockFmt = DateTimeFormatter.ofPattern("HH:mm")

    @Volatile
    private var cachedCatIcon: Bitmap? = null

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

        // 1. Set real countdown timer in the user's native Phone Clock app!
        val phoneTimerSet = launchPhoneClockTimer(
            context = context,
            timerSeconds = timerSec,
            designation = designation,
            destination = destination,
            stationName = stationName
        )

        // 2. Schedule lightweight wakeup at triggerAtMs to flip the "Leave in..." notification to "Hurry up!"
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val firePi = buildAlarmPendingIntent(context, reminder)
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, firePi)
        } catch (_: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, firePi)
        }

        WidgetPrefs.saveReminder(context, reminder)
        postCountdownNotification(context, reminder)
        AnalyticsHelper.logReminderSet(context, stationName, designation, destination, walkMinutes, timerSec)
        return ReminderToggleResult.Scheduled(reminder, formatClock(triggerAtMs), phoneTimerSet)
    }

    private fun launchPhoneClockTimer(
        context: Context,
        timerSeconds: Int,
        designation: String,
        destination: String,
        stationName: String
    ): Boolean {
        val label = "Hurry! $designation → $destination ($stationName)"
        val silentIntent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, timerSeconds)
            putExtra(AlarmClock.EXTRA_MESSAGE, label)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            if (context !is Activity) {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
        try {
            context.startActivity(silentIntent)
            return true
        } catch (_: Exception) {
            // Fallback without EXTRA_SKIP_UI if OEM clock requires opening UI
            return try {
                val visibleIntent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
                    putExtra(AlarmClock.EXTRA_LENGTH, timerSeconds)
                    putExtra(AlarmClock.EXTRA_MESSAGE, label)
                    if (context !is Activity) {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                }
                context.startActivity(visibleIntent)
                true
            } catch (_: Exception) {
                false
            }
        }
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

    private fun hasNotifPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun buildCatNotificationIcon(context: Context): Bitmap? {
        cachedCatIcon?.let { if (!it.isRecycled) return it }
        return try {
            val raw = BitmapFactory.decodeResource(context.resources, R.drawable.cat_hurry_small) ?: return null
            val size = 128
            val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(out)

            val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#0B1629")
                style = Paint.Style.FILL
            }
            val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#5529E6FF")
                style = Paint.Style.STROKE
                strokeWidth = 3f
            }
            val cardRect = RectF(2f, 2f, size - 2f, size - 2f)
            canvas.drawRoundRect(cardRect, 24f, 24f, bgPaint)
            canvas.drawRoundRect(cardRect, 24f, 24f, borderPaint)

            val targetW = size * 0.86f
            val scale = targetW / raw.width.toFloat()
            val targetH = raw.height * scale
            val left = (size - targetW) / 2f
            val top = (size - targetH) / 2f
            val dst = RectF(left, top, left + targetW, top + targetH)
            canvas.drawBitmap(raw, null, dst, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))

            cachedCatIcon = out
            out
        } catch (_: Exception) {
            null
        }
    }

    private fun postCountdownNotification(context: Context, reminder: ActiveReminder) {
        if (!hasNotifPermission(context)) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (nm.getNotificationChannel(COUNTDOWN_CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    COUNTDOWN_CHANNEL_ID,
                    "Live Walk Countdown",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Lock-screen live countdown until you need to leave."
                    setSound(null, null)
                    enableVibration(false)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
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
        val builder = NotificationCompat.Builder(context, COUNTDOWN_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("⏱ Leave in countdown · ${reminder.designation} → ${reminder.destination}")
            .setContentText("${reminder.stationName} ($depTime) · Hurry out at $ringTime (${reminder.walkMinutes} min walk)")
            .setWhen(reminder.triggerAtMs)
            .setShowWhen(true)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(openPi)
            .addAction(0, "Dismiss", cancelPi)

        buildCatNotificationIcon(context)?.let { builder.setLargeIcon(it) }
        nm.notify(reminder.requestCode + 50_000, builder.build())
    }

    fun postHurryUpNowNotification(
        context: Context,
        stationName: String,
        designation: String,
        destination: String,
        walkMinutes: Int
    ) {
        if (!hasNotifPermission(context)) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (nm.getNotificationChannel(HURRY_ALERT_CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    HURRY_ALERT_CHANNEL_ID,
                    "Hurry Up Leave Now Banner",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Visual banner when your phone timer hits walk time."
                    setSound(null, null)
                    enableVibration(true)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                }
            )
        }

        val openPi = PendingIntent.getActivity(
            context,
            9199,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val walkInfo = if (walkMinutes > 0) "$walkMinutes min walk to $stationName" else "Head to $stationName now"
        val builder = NotificationCompat.Builder(context, HURRY_ALERT_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("🏃 Hurry up! Leave now · $designation → $destination")
            .setContentText(walkInfo)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setTimeoutAfter(5 * 60 * 1000L)
            .setContentIntent(openPi)

        buildCatNotificationIcon(context)?.let { builder.setLargeIcon(it) }
        nm.notify(HURRY_NOTIF_ID, builder.build())
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
