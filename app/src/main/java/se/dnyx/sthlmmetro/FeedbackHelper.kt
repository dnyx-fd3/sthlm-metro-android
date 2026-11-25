package se.dnyx.sthlmmetro

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.util.concurrent.Executors

object FeedbackHelper {
    const val COFFEE_URL = "https://buymeacoffee.com/dnyx"
    private const val PREFS_NAME = "tradar_feedback_prefs"
    private const val KEY_SAVED_NAME = "saved_sender_name"
    private const val KEY_SAVED_STATIONS = "saved_frequent_stations"
    private const val KEY_PENDING_QUEUE = "pending_feedback_v1"

    private val executor = Executors.newSingleThreadExecutor()

    private enum class FeedbackType(val tag: String) {
        IDEA("Idea"),
        UI_UX("UI/UX"),
        BUG("Bug")
    }

    fun openCoffeeLink(context: Context, source: String) {
        AnalyticsHelper.logCoffeeClicked(context, source)
        openDarkCustomTab(context, COFFEE_URL)
    }

    fun showFeedbackDialog(activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed) return
        AnalyticsHelper.logFeedbackOpened(activity)

        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_feedback, null)
        dialog.setContentView(view)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout(
            (activity.resources.displayMetrics.widthPixels * 0.92).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        var selectedType = FeedbackType.IDEA
        val chips = mapOf(
            FeedbackType.IDEA to view.findViewById<TextView>(R.id.fb_cat_idea),
            FeedbackType.UI_UX to view.findViewById<TextView>(R.id.fb_cat_ui),
            FeedbackType.BUG to view.findViewById<TextView>(R.id.fb_cat_bug)
        )
        val dark = ContextCompat.getColor(activity, R.color.bg_dark)
        val primary = ContextCompat.getColor(activity, R.color.text_primary)

        fun renderCategoryChips() {
            chips.forEach { (type, chip) ->
                val sel = type == selectedType
                chip.setBackgroundResource(if (sel) R.drawable.chip_active else R.drawable.chip_idle)
                chip.setTextColor(if (sel) dark else primary)
            }
        }

        chips.forEach { (type, chip) ->
            chip.setOnClickListener {
                selectedType = type
                renderCategoryChips()
            }
        }
        renderCategoryChips()

        val prefs = activity.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val nameInput = view.findViewById<EditText>(R.id.fb_name)
        val stationsInput = view.findViewById<EditText>(R.id.fb_stations)
        val messageInput = view.findViewById<EditText>(R.id.fb_input)
        val submitBtn = view.findViewById<TextView>(R.id.fb_submit)

        // Pre-fill previously entered optional name/stations (or saved T-Radar stops on first open)
        val prevName = prefs.getString(KEY_SAVED_NAME, "").orEmpty()
        val prevStations = prefs.getString(KEY_SAVED_STATIONS, null) ?: run {
            WidgetPrefs.loadSharedSlots(activity)
                .filterNot { it.isNearMe }
                .joinToString(", ") { it.stationName }
        }
        if (prevName.isNotEmpty()) nameInput.setText(prevName)
        if (prevStations.isNotEmpty()) stationsInput.setText(prevStations)

        view.findViewById<View>(R.id.fb_close).setOnClickListener { dialog.dismiss() }

        view.findViewById<View>(R.id.fb_coffee_link).setOnClickListener {
            dialog.dismiss()
            openCoffeeLink(activity, "feedback_dialog")
        }

        submitBtn.setOnClickListener {
            val message = messageInput.text?.toString().orEmpty().trim()
            if (message.isEmpty()) {
                Toast.makeText(activity, "Write a short note first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val name = nameInput.text?.toString().orEmpty().trim()
            val stations = stationsInput.text?.toString().orEmpty().trim()
            prefs.edit()
                .putString(KEY_SAVED_NAME, name)
                .putString(KEY_SAVED_STATIONS, stations)
                .apply()

            submitBtn.isEnabled = false
            submitBtn.text = "SENDING…"

            val entry = JSONObject()
                .put("category", selectedType.tag)
                .put("name", name)
                .put("frequentStations", stations)
                .put("message", message)
                .put("appVersion", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                .put("androidVersion", "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
                .put("createdAt", Instant.now().toString())

            AnalyticsHelper.logFeedbackSubmitted(
                context = activity,
                category = selectedType.tag,
                stations = stations,
                preview = message
            )

            val appCtx = activity.applicationContext
            executor.execute {
                val sentNow = postToFirestoreBlocking(appCtx, entry)
                if (!sentNow) {
                    enqueuePending(appCtx, entry)
                }
                activity.runOnUiThread {
                    if (!activity.isFinishing && !activity.isDestroyed && dialog.isShowing) {
                        dialog.dismiss()
                    }
                    Toast.makeText(appCtx, "✓ Thanks for your feedback!", Toast.LENGTH_SHORT).show()
                }
            }
        }

        dialog.show()
    }

    fun flushPendingAsync(context: Context) {
        val appCtx = context.applicationContext
        executor.execute {
            val prefs = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val raw = prefs.getString(KEY_PENDING_QUEUE, null) ?: return@execute
            val arr = try {
                JSONArray(raw)
            } catch (_: Exception) {
                return@execute
            }
            if (arr.length() == 0) return@execute

            val remaining = JSONArray()
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                if (!postToFirestoreBlocking(appCtx, item)) {
                    remaining.put(item)
                }
            }
            prefs.edit().putString(KEY_PENDING_QUEUE, remaining.toString()).apply()
        }
    }

    private fun enqueuePending(context: Context, entry: JSONObject) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val existing = try {
            JSONArray(prefs.getString(KEY_PENDING_QUEUE, "[]") ?: "[]")
        } catch (_: Exception) {
            JSONArray()
        }
        val trimmed = JSONArray()
        val start = (existing.length() - 19).coerceAtLeast(0)
        for (i in start until existing.length()) {
            existing.optJSONObject(i)?.let { trimmed.put(it) }
        }
        trimmed.put(entry)
        prefs.edit().putString(KEY_PENDING_QUEUE, trimmed.toString()).apply()
    }

    private fun postToFirestoreBlocking(context: Context, entry: JSONObject): Boolean {
        return try {
            val projectId = getStringResourceByName(context, "project_id").ifEmpty { "t-radar-53ccc" }
            val apiKey = getStringResourceByName(context, "google_api_key")
            val keyParam = if (apiKey.isNotEmpty()) "?key=$apiKey" else ""
            val endpoint = "https://firestore.googleapis.com/v1/projects/$projectId/databases/(default)/documents/feedback$keyParam"

            fun strField(v: String) = JSONObject().put("stringValue", v)
            val fields = JSONObject()
                .put("category", strField(entry.optString("category", "Idea")))
                .put("name", strField(entry.optString("name", "")))
                .put("frequentStations", strField(entry.optString("frequentStations", "")))
                .put("message", strField(entry.optString("message", "")))
                .put("appVersion", strField(entry.optString("appVersion", BuildConfig.VERSION_NAME)))
                .put("androidVersion", strField(entry.optString("androidVersion", "")))
                .put("createdAt", JSONObject().put("timestampValue", entry.optString("createdAt", Instant.now().toString())))

            val payload = JSONObject().put("fields", fields).toString()

            val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 9000
                readTimeout = 9000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                setRequestProperty("Accept", "application/json")
            }
            try {
                OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(payload) }
                conn.responseCode in 200..299
            } finally {
                conn.disconnect()
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun getStringResourceByName(context: Context, name: String): String {
        val id = context.resources.getIdentifier(name, "string", context.packageName)
        return if (id != 0) context.getString(id) else ""
    }

    fun openDarkCustomTab(context: Context, url: String) {
        val uri = Uri.parse(url)
        try {
            val darkColors = CustomTabColorSchemeParams.Builder()
                .setToolbarColor(Color.parseColor("#040914"))
                .setNavigationBarColor(Color.parseColor("#040914"))
                .build()
            val customTabs = CustomTabsIntent.Builder()
                .setDefaultColorSchemeParams(darkColors)
                .setShowTitle(true)
                .build()
            if (context !is Activity) {
                customTabs.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            customTabs.launchUrl(context, uri)
        } catch (_: Exception) {
            try {
                val fallback = Intent(Intent.ACTION_VIEW, uri).apply {
                    if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(fallback)
            } catch (_: Exception) {
                // Ignore
            }
        }
    }
}
