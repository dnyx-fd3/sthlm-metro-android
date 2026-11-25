package se.dnyx.sthlmmetro

import android.appwidget.AppWidgetManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.AlarmClock
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class DepartureReminderActivity : AppCompatActivity() {

    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private var slotIndex = -1
    private var stationName = ""
    private var designation = ""
    private var destination = ""
    private var clockTime = ""
    private var expectedEpochMs = 0L
    private var walkMinutes = 3

    private lateinit var countdownBanner: TextView
    private lateinit var walkValueText: TextView
    private lateinit var setTimerBtn: TextView

    private val handler = Handler(Looper.getMainLooper())
    private val clockFmt = DateTimeFormatter.ofPattern("HH:mm")

    private val tickRunnable = object : Runnable {
        override fun run() {
            updateCountdownUI()
            handler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AnalyticsHelper.syncCollectionState(this)
        setContentView(R.layout.activity_departure_reminder)

        appWidgetId = intent.getIntExtra(EXTRA_WIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        slotIndex = intent.getIntExtra(EXTRA_SLOT_INDEX, -1)
        stationName = intent.getStringExtra(EXTRA_STATION_NAME).orEmpty()
        designation = intent.getStringExtra(EXTRA_DESIGNATION).orEmpty()
        destination = intent.getStringExtra(EXTRA_DESTINATION).orEmpty()
        clockTime = intent.getStringExtra(EXTRA_CLOCK_TIME).orEmpty()
        expectedEpochMs = intent.getLongExtra(EXTRA_EXPECTED_EPOCH_MS, 0L)
        walkMinutes = intent.getIntExtra(EXTRA_WALK_MINUTES, 3).coerceIn(0, 30)

        AnalyticsHelper.logDepartureTapped(this, stationName, designation, destination, walkMinutes)

        val styleName = intent.getStringExtra(EXTRA_BADGE_STYLE) ?: "CYAN"
        val badgeStyle = LineBadgeStyle.entries.firstOrNull { it.name == styleName } ?: LineBadgeStyle.CYAN

        val badgeView = findViewById<TextView>(R.id.rem_badge)
        badgeView.text = designation.ifEmpty { "SL" }
        badgeView.setBackgroundResource(MetroWidgetProvider.badgeDrawableFor(badgeStyle))

        findViewById<TextView>(R.id.rem_dest).text = destination.ifEmpty { "Stockholm" }
        findViewById<TextView>(R.id.rem_meta).text =
            if (clockTime.isNotEmpty()) "$stationName · $clockTime" else stationName

        countdownBanner = findViewById(R.id.rem_countdown_banner)
        walkValueText = findViewById(R.id.rem_walk_value)
        setTimerBtn = findViewById(R.id.rem_btn_set_timer)

        findViewById<View>(R.id.reminder_backdrop).setOnClickListener { finish() }
        findViewById<View>(R.id.rem_close).setOnClickListener { finish() }

        findViewById<View>(R.id.rem_walk_minus).setOnClickListener {
            if (walkMinutes > 0) {
                walkMinutes--
                persistWalkDefault()
                updateCountdownUI()
            }
        }

        findViewById<View>(R.id.rem_walk_plus).setOnClickListener {
            if (walkMinutes < 30) {
                walkMinutes++
                persistWalkDefault()
                updateCountdownUI()
            }
        }

        setTimerBtn.setOnClickListener { onSetTimerClicked() }
        updateCountdownUI()
    }

    override fun onResume() {
        super.onResume()
        handler.post(tickRunnable)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(tickRunnable)
    }

    private fun persistWalkDefault() {
        if (slotIndex >= 0) {
            WidgetPrefs.updateSlotWalkMinutes(this, slotIndex, walkMinutes)
        }
    }

    private fun updateCountdownUI() {
        walkValueText.text = "$walkMinutes min"
        val nowMs = System.currentTimeMillis()
        val remainingSec = ((expectedEpochMs - nowMs) / 1000L).toInt()

        if (expectedEpochMs <= 0L || remainingSec <= 0) {
            countdownBanner.text = "Already departed"
            setTimerBtn.text = "↻ REFRESH"
            return
        }

        countdownBanner.text = "Departs in ${formatDuration(remainingSec)}"
        val timerSec = remainingSec - walkMinutes * 60
        if (timerSec >= 10) {
            val ringAtStr = Instant.ofEpochMilli(nowMs + timerSec * 1000L)
                .atZone(ZoneId.systemDefault())
                .toLocalTime()
                .format(clockFmt)
            setTimerBtn.text = "🔔 RING IN ${formatDuration(timerSec)} ($ringAtStr)"
        } else {
            setTimerBtn.text = "🏃 LEAVE NOW (${formatDuration(remainingSec)})"
        }
    }

    private fun onSetTimerClicked() {
        val nowMs = System.currentTimeMillis()
        val remainingSec = ((expectedEpochMs - nowMs) / 1000L).toInt()

        if (expectedEpochMs <= 0L || remainingSec <= 0) {
            if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                MetroWidgetProvider.triggerAsyncRefresh(this, appWidgetId, trigger = "reminder_stale")
            }
            finish()
            return
        }

        val timerSec = remainingSec - walkMinutes * 60
        if (timerSec < 10) {
            Toast.makeText(this, "Leaves in ${formatDuration(remainingSec)} — head out now!", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        val label = "Catch $designation → $destination ($stationName)"
        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, timerSec)
            putExtra(AlarmClock.EXTRA_MESSAGE, label)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        }
        try {
            startActivity(intent)
            AnalyticsHelper.logReminderSet(this, stationName, designation, destination, walkMinutes, timerSec)
            Toast.makeText(this, "Timer set: ${formatDuration(timerSec)}", Toast.LENGTH_SHORT).show()
            finish()
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "No Clock app found.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun formatDuration(totalSec: Int): String {
        val mins = totalSec / 60
        val secs = totalSec % 60
        return if (mins > 0) "${mins}m ${secs}s" else "${secs}s"
    }

    companion object {
        const val EXTRA_WIDGET_ID = "widget_id"
        const val EXTRA_SLOT_INDEX = "slot_index"
        const val EXTRA_STATION_NAME = "station_name"
        const val EXTRA_DESIGNATION = "designation"
        const val EXTRA_DESTINATION = "destination"
        const val EXTRA_CLOCK_TIME = "clock_time"
        const val EXTRA_EXPECTED_EPOCH_MS = "expected_epoch_ms"
        const val EXTRA_BADGE_STYLE = "badge_style"
        const val EXTRA_WALK_MINUTES = "walk_minutes"
    }
}
