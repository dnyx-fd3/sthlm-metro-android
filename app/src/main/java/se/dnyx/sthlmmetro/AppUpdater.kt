package se.dnyx.sthlmmetro

import android.app.Activity
import android.app.Dialog
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
import java.util.concurrent.Executors

data class ReleaseInfo(
    val tagName: String,
    val title: String,
    val htmlUrl: String,
    val apkDownloadUrl: String?,
    val apkSizeBytes: Long,
    val publishedAtMs: Long,
    val isNewer: Boolean
)

object AppUpdater {
    private val executor = Executors.newSingleThreadExecutor()
    private val releasesPageUrl = "https://github.com/${BuildConfig.GITHUB_REPO}/releases"
    private val latestApiUrl = "https://api.github.com/repos/${BuildConfig.GITHUB_REPO}/releases/latest"

    @Volatile
    var cachedRelease: ReleaseInfo? = null
        private set

    fun checkInBackground(onResult: (ReleaseInfo?) -> Unit) {
        executor.execute {
            val info = fetchLatestReleaseBlocking()
            cachedRelease = info
            onResult(info)
        }
    }

    fun showUpdateDialog(activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed) return

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
                bodyView.text = "Installed: T-Radar v${BuildConfig.VERSION_NAME}
Check your connection or open GitHub Releases below."
                primaryBtn.text = "↻ TRY AGAIN"
                primaryBtn.setOnClickListener {
                    titleView.text = "Checking GitHub Releases…"
                    progressBar.isIndeterminate = true
                    progressBar.visibility = View.VISIBLE
                    primaryBtn.visibility = View.GONE
                    checkInBackground { latest -> activity.runOnUiThread { bindRelease(latest) } }
                }
                return
            }

            val sizeMb = if (info.apkSizeBytes > 0) {
                String.format("%.1f MB", info.apkSizeBytes / (1024.0 * 1024.0))
            } else {
                "APK"
            }

            if (info.isNewer && !info.apkDownloadUrl.isNullOrEmpty()) {
                titleView.text = "Update ready (${info.tagName})"
                bodyView.text = "Installed: v${BuildConfig.VERSION_NAME}
Latest build: ${info.tagName} ($sizeMb)"
                primaryBtn.text = "⬇ DOWNLOAD & INSTALL"
                primaryBtn.setOnClickListener {
                    startApkDownloadAndInstall(
                        activity = activity,
                        dialog = dialog,
                        apkUrl = info.apkDownloadUrl,
                        titleView = titleView,
                        bodyView = bodyView,
                        progressBar = progressBar,
                        primaryBtn = primaryBtn
                    )
                }
            } else {
                titleView.text = "You're up to date!"
                bodyView.text = "Installed: T-Radar v${BuildConfig.VERSION_NAME}
Latest GitHub build: ${info.tagName}"
                primaryBtn.text = if (!info.apkDownloadUrl.isNullOrEmpty()) {
                    "↻ RE-DOWNLOAD LATEST ($sizeMb)"
                } else {
                    "✓ CLOSE"
                }
                primaryBtn.setOnClickListener {
                    val url = info.apkDownloadUrl
                    if (!url.isNullOrEmpty()) {
                        startApkDownloadAndInstall(
                            activity = activity,
                            dialog = dialog,
                            apkUrl = url,
                            titleView = titleView,
                            bodyView = bodyView,
                            progressBar = progressBar,
                            primaryBtn = primaryBtn
                        )
                    } else {
                        dialog.dismiss()
                    }
                }
            }
        }

        dialog.show()

        // Always perform a fresh check when user explicitly taps the Update button
        titleView.text = "Checking GitHub Releases…"
        bodyView.text = "Installed: T-Radar v${BuildConfig.VERSION_NAME}"
        progressBar.isIndeterminate = true
        progressBar.visibility = View.VISIBLE
        primaryBtn.visibility = View.GONE

        checkInBackground { latest ->
            activity.runOnUiThread { bindRelease(latest) }
        }
    }

    private fun startApkDownloadAndInstall(
        activity: Activity,
        dialog: Dialog,
        apkUrl: String,
        titleView: TextView,
        bodyView: TextView,
        progressBar: ProgressBar,
        primaryBtn: TextView
    ) {
        if (!activity.packageManager.canRequestPackageInstalls()) {
            Toast.makeText(
                activity,
                "Allow T-Radar to install updates, then tap Download again.",
                Toast.LENGTH_LONG
            ).show()
            try {
                val permIntent = Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${activity.packageName}")
                )
                activity.startActivity(permIntent)
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
        titleView.text = "Downloading T-Radar…"
        bodyView.text = "0% — fetching latest APK from GitHub"

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
                    val code = conn.responseCode
                    if (code !in 200..299) throw IllegalStateException("HTTP $code")
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
                                                bodyView.text = "$pct% downloaded…"
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
            } catch (e: Exception) {
                activity.runOnUiThread {
                    if (!dialog.isShowing) return@runOnUiThread
                    progressBar.visibility = View.GONE
                    primaryBtn.visibility = View.VISIBLE
                    titleView.text = "Download failed"
                    bodyView.text = "Opening Releases in browser works as a fallback."
                    primaryBtn.text = "↻ RETRY DOWNLOAD"
                }
            }
        }
    }

    private fun launchInstaller(activity: Activity, apkFile: File) {
        try {
            val contentUri = FileProvider.getUriForFile(
                activity,
                "${activity.packageName}.fileprovider",
                apkFile
            )
            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(contentUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activity.startActivity(installIntent)
        } catch (_: Exception) {
            Toast.makeText(activity, "Couldn't open installer — try GitHub Releases.", Toast.LENGTH_LONG).show()
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
                val name = json.optString("name", tag)
                val htmlUrl = json.optString("html_url", releasesPageUrl)
                val publishedStr = json.optString("published_at", "")
                val publishedMs = try {
                    if (publishedStr.isNotEmpty()) Instant.parse(publishedStr).toEpochMilli() else 0L
                } catch (_: Exception) {
                    0L
                }

                var apkUrl: String? = null
                var apkSize = 0L
                val assets = json.optJSONArray("assets")
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val a = assets.optJSONObject(i) ?: continue
                        val fileName = a.optString("name", "")
                        if (fileName.endsWith(".apk", ignoreCase = true)) {
                            apkUrl = a.optString("browser_download_url", "").ifEmpty { null }
                            apkSize = a.optLong("size", 0L)
                            break
                        }
                    }
                }

                // A release published >5 minutes after this APK was compiled is genuinely newer
                val isNewer = publishedMs > (BuildConfig.BUILD_TIME_MS + 5L * 60L * 1000L)

                ReleaseInfo(
                    tagName = tag,
                    title = name,
                    htmlUrl = htmlUrl,
                    apkDownloadUrl = apkUrl,
                    apkSizeBytes = apkSize,
                    publishedAtMs = publishedMs,
                    isNewer = isNewer
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
