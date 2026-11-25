package se.dnyx.sthlmmetro

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class ReminderAlertReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_CANCEL_REMINDER) {
            val key = intent.getStringExtra(EXTRA_KEY).orEmpty()
            if (key.isNotEmpty()) {
                ReminderManager.cancelReminder(context, key)
                MetroWidgetProvider.rerenderAllFromCache(context)
            }
            return
        }
        if (intent.action != ACTION_FIRE_REMINDER) return

        val key = intent.getStringExtra(EXTRA_KEY).orEmpty()
        val station = intent.getStringExtra(EXTRA_STATION).orEmpty()
        val line = intent.getStringExtra(EXTRA_LINE).orEmpty()
        val dest = intent.getStringExtra(EXTRA_DEST).orEmpty()
        val walk = intent.getIntExtra(EXTRA_WALK, 3)

        if (key.isNotEmpty()) {
            val removed = WidgetPrefs.removeReminder(context, key)
            if (removed != null) {
                ReminderManager.dismissCountdownNotification(context, removed.requestCode)
            }
        }

        MetroWidgetProvider.rerenderAllFromCache(context)
        ReminderManager.postHurryUpNowNotification(context, station, line, dest, walk)
    }

    companion object {
        const val ACTION_FIRE_REMINDER = "se.dnyx.sthlmmetro.ACTION_FIRE_REMINDER"
        const val ACTION_CANCEL_REMINDER = "se.dnyx.sthlmmetro.ACTION_CANCEL_REMINDER"
        const val EXTRA_KEY = "rem_key"
        const val EXTRA_STATION = "rem_station"
        const val EXTRA_LINE = "rem_line"
        const val EXTRA_DEST = "rem_dest"
        const val EXTRA_WALK = "rem_walk"
    }
}
