package se.dnyx.sthlmmetro

import android.app.Activity
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
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

object FeedbackHelper {
    const val COFFEE_URL = "https://buymeacoffee.com/dnyx"
    private val ISSUES_NEW_URL = "https://github.com/${BuildConfig.GITHUB_REPO}/issues/new"

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

        val input = view.findViewById<EditText>(R.id.fb_input)
        view.findViewById<View>(R.id.fb_close).setOnClickListener { dialog.dismiss() }

        view.findViewById<View>(R.id.fb_coffee_link).setOnClickListener {
            dialog.dismiss()
            openCoffeeLink(activity, "feedback_dialog")
        }

        view.findViewById<View>(R.id.fb_submit_github).setOnClickListener {
            val message = input.text?.toString().orEmpty().trim()
            if (message.isNotEmpty()) {
                // Copy note to clipboard so nothing is lost if GitHub asks the user to sign in first
                try {
                    val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    cm?.setPrimaryClip(ClipData.newPlainText("T-Radar Feedback", message))
                    Toast.makeText(activity, "Copied backup & opening GitHub…", Toast.LENGTH_SHORT).show()
                } catch (_: Exception) {
                    // Ignore
                }
            }

            val firstLine = message.lineSequence().firstOrNull()?.trim().orEmpty()
            val summary = when {
                firstLine.isEmpty() -> "Feedback"
                firstLine.length <= 52 -> firstLine
                else -> firstLine.substring(0, 52).trimEnd() + "…"
            }
            val title = "[${selectedType.tag}] $summary"
            val metaFooter = "---\n*App: T-Radar v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})*"
            val body = if (message.isNotEmpty()) {
                "$message\n\n$metaFooter"
            } else {
                "Describe your ${selectedType.tag.lowercase()} here…\n\n$metaFooter"
            }

            val uri = Uri.parse(ISSUES_NEW_URL).buildUpon()
                .appendQueryParameter("title", title)
                .appendQueryParameter("body", body)
                .build()

            AnalyticsHelper.logFeedbackSubmitted(activity, selectedType.tag)
            dialog.dismiss()
            openDarkCustomTab(activity, uri.toString())
        }

        dialog.show()
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
