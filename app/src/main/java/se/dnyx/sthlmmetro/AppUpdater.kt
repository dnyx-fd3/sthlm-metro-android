package se.dnyx.sthlmmetro

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.Window
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.util.Locale
import java.util.concurrent.Executors

data class ReleaseInfo(
    val tagName: String,
    val htmlUrl: String,
    val apkDownloadUrl: String?,
    val apkSizeBytes: Long,
    val isNewer: Boolean
)

object AppUpdater {
    private val executor = Executors.newSingleThreadExecutor()
    private val releasesPageUrl = "https://github.com/${BuildConfig.GITHUB_REPO}/releases"
    private val latestApiUrl = "https://api.github.com/repos/${BuildConfig.GITHUB_REPO}/releases/latest"

    @Volatile
    var cachedRelease: ReleaseInfo? = null
        private set

    fun cleanupOldApks(context: Context) {
        executor.execute {
            try {
                val dir = File(context.applicationContext.cacheDir, "updates")
                if (dir.exists()) {
                    dir.listFiles()?.forEach { it.delete() }
                }
            } catch (_: Exception) {
                // Ignore
            }
        }
    }

    fun checkInBackground(onResult: (ReleaseInfo?) -> Unit) {
        executor.execute {
            val info = fetchLatestReleaseBlocking()
            cachedRelease = info
            onResult(info)
        }
    }

    fun showUpdateDialog(activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed) return
        AnalyticsHelper.logUpdateChecked(activity)

        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_app_update, null)
        dialog.setContentView(view)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout(
            (activity.resources.displayMetrics.widthPixels * 0.90).toInt(),
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        )

        val titleView = view.findViewById<TextView>(R.id.upd_title)
        val bodyView = view.findViewById<TextView>(R.id.upd_body)
        val progressBar = view.findViewById<ProgressBar>(R.id.upd_progress)
        val primaryBtn = view.findViewById<TextView>(R.id.upd_primary_btn)
        val secondaryBtn = view.findViewById<TextView>(R.id.upd_secondary_btn)

        view.findViewById<View>(R.id.upd_close).setOnClickListener { dialog.dismiss() }
        secondaryBtn.setOnClickListener {
            openUrl(activity, cachedRelease?.htmlUrl ?: releasesPageUrl)
            dialog.dismiss()
        }

        fun bindRelease(info: ReleaseInfo?) {
            if (activity.isFinishing || activity.isDestroyed || !dialog.isShowing) return
            progressBar.visibility = View.GONE
            primaryBtn.visibility = View.VISIBLE

            if (info == null) {
                titleView.text = "Couldn't check releases"
                bodyView.text = "Installed: T-Radar v${BuildConfig.VERSION_NAME}"
                primaryBtn.text = "↻ TRY AGAIN"
                primaryBtn.setOnClickListener {
                    titleView.text = "Checking…"
                    progressBar.isIndeterminate = true
                    progressBar.visibility = View.VISIBLE
                    primaryBtn.visibility = View.GONE
                    checkInBackground { latest -> activity.runOnUiThread { bindRelease(latest) } }
                }
                return
            }

            val sizeMb = if (info.apkSizeBytes > 0) {
                String.format(Locale.US, "%.1f MB", info.apkSizeBytes / (1024.0 * 1024.0))
            } else {
                "APK"
            }

            if (info.isNewer && !info.apkDownloadUrl.isNullOrEmpty()) {
                titleView.text = "Update ${info.tagName}"
                bodyView.text = "Installed: v${BuildConfig.VERSION_NAME} ($sizeMb)"
                primaryBtn.text = "⬇ INSTALL"
                primaryBtn.setOnClickListener {
                    startDownload(activity, dialog, info.apkDownloadUrl, titleView, bodyView, progressBar, primaryBtn)
                }
            } else {
                titleView.text = "Up to date"
                bodyView.text = "v${BuildConfig.VERSION_NAME} · Latest ${info.tagName}"
                primaryBtn.text = if (!info.apkDownloadUrl.isNullOrEmpty()) "↻ RE-INSTALL ($sizeMb)" else "CLOSE"
                primaryBtn.setOnClickListener {
                    val url = info.apkDownloadUrl
                    if (!url.isNullOrEmpty()) {
                        startDownload(activity, dialog, url, titleView, bodyView, progressBar, primaryBtn)
                    } else {
                        dialog.dismiss()
                    }
                }
            }
        }

        dialog.show()
        titleView.text = "Checking updates…"
        bodyView.text = "v${BuildConfig.VERSION_NAME}"
        progressBar.isIndeterminate = true
        progressBar.visibility = View.VISIBLE
        primaryBtn.visibility = View.GONE

        checkInBackground { latest ->
            activity.runOnUiThread { bindRelease(latest) }
        }
    }

    private fun startDownload(
        activity: Activity,
        dialog: Dialog,
        apkUrl: String,
        titleView: TextView,
        bodyView: TextView,
        progressBar: ProgressBar,
        primaryBtn: TextView
    ) {
        if (!activity.packageManager.canRequestPackageInstalls()) {
            Toast.makeText(activity, "Allow T-Radar to install updates, then tap Download again.", Toast.LENGTH_LONG).show()
            try {
                activity.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}"))
                )
            } catch (_: Exception) {
                openUrl(activity, apkUrl)
            }
            return
        }

        primaryBtn.visibility = View.GONE
        progressBar.visibility = View.VISIBLE
        progressBar.isIndeterminate = false
        progressBar.max = 100
        progressBar.progress = 0
        titleView.text = "Downloading…"
        bodyView.text = "0%"

        executor.execute {
            try {
                val updateDir = File(activity.cacheDir, "updates").apply { mkdirs() }
                val outFile = File(updateDir, "t-radar-update.apk")
                if (outFile.exists()) outFile.delete()

                val conn = (URL(apkUrl).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = true
                    connectTimeout = 15000
                    readTimeout = 30000
                    setRequestProperty("User-Agent", "TRadarAndroid/${BuildConfig.VERSION_NAME}")
                }
                try {
                    if (conn.responseCode !in 200..299) throw IllegalStateException("HTTP ${conn.responseCode}")
                    val total = conn.contentLengthLong
                    var downloaded = 0L
                    var lastPct = -1
                    conn.inputStream.use { input ->
                        FileOutputStream(outFile).use { output ->
                            val buf = ByteArray(16 * 1024)
                            while (true) {
                                val read = input.read(buf)
                                if (read <= 0) break
                                output.write(buf, 0, read)
                                downloaded += read
                                if (total > 0) {
                                    val pct = ((downloaded * 100L) / total).toInt().coerceIn(0, 100)
                                    if (pct != lastPct) {
                                        lastPct = pct
                                        activity.runOnUiThread {
                                            if (dialog.isShowing) {
                                                progressBar.progress = pct
                                                bodyView.text = "$pct%"
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                } finally {
                    conn.disconnect()
                }

                activity.runOnUiThread {
                    if (dialog.isShowing) dialog.dismiss()
                    launchInstaller(activity, outFile)
                }
            } catch (_: Exception) {
                activity.runOnUiThread {
                    if (!dialog.isShowing) return@runOnUiThread
                    progressBar.visibility = View.GONE
                    primaryBtn.visibility = View.VISIBLE
                    titleView.text = "Download failed"
                    bodyView.text = "Try opening GitHub Releases below."
                    primaryBtn.text = "↻ RETRY"
                }
            }
        }
    }

    private fun launchInstaller(activity: Activity, apkFile: File) {
        try {
            val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", apkFile)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activity.startActivity(intent)
        } catch (_: Exception) {
            openUrl(activity, releasesPageUrl)
        }
    }

    private fun fetchLatestReleaseBlocking(): ReleaseInfo? {
        return try {
            val conn = (URL(latestApiUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 9000
                readTimeout = 9000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "TRadarAndroid/${BuildConfig.VERSION_NAME}")
            }
            try {
                if (conn.responseCode !in 200..299) return null
                val raw = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { it.readText() }
                val json = JSONObject(raw)
                val tag = json.optString("tag_name", "latest")
                val htmlUrl = json.optString("html_url", releasesPageUrl)
                val pubStr = json.optString("published_at", "")
                val pubMs = try {
                    if (pubStr.isNotEmpty()) Instant.parse(pubStr).toEpochMilli() else 0L
                } catch (_: Exception) {
                    0L
                }
                var apkUrl: String? = null
                var apkSize = 0L
                val assets = json.optJSONArray("assets")
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val a = assets.optJSONObject(i) ?: continue
                        if (a.optString("name", "").endsWith(".apk", ignoreCase = true)) {
                            apkUrl = a.optString("browser_download_url", "").ifEmpty { null }
                            apkSize = a.optLong("size", 0L)
                            break
                        }
                    }
                }
                ReleaseInfo(
                    tagName = tag,
                    htmlUrl = htmlUrl,
                    apkDownloadUrl = apkUrl,
                    apkSizeBytes = apkSize,
                    isNewer = pubMs > (BuildConfig.BUILD_TIME_MS + 5L * 60L * 1000L)
                )
            } finally {
                conn.disconnect()
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun openUrl(activity: Activity, url: String) {
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {
            // Ignore
        }
    }
}
