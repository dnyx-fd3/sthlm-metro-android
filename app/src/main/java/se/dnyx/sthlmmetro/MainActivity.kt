package se.dnyx.sthlmmetro

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.webkit.GeolocationPermissions
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var spinner: ProgressBar
    private lateinit var updatePill: TextView
    private var updateAvailable = false
    private var pendingGeoOrigin: String? = null
    private var pendingGeoCallback: GeolocationPermissions.Callback? = null

    private val locationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            val allowed = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
            pendingGeoCallback?.invoke(pendingGeoOrigin, allowed, false)
            pendingGeoCallback = null
            pendingGeoOrigin = null
        }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AnalyticsHelper.syncCollectionState(this)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)

        val insetsController = WindowInsetsControllerCompat(window, window.decorView)
        insetsController.isAppearanceLightStatusBars = false
        insetsController.isAppearanceLightNavigationBars = false

        val rootContainer = findViewById<View>(R.id.root_container)
        ViewCompat.setOnApplyWindowInsetsListener(rootContainer) { v, windowInsets ->
            val insets = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.setPadding(insets.left, insets.top, insets.right, insets.bottom)
            windowInsets
        }

        webView = findViewById(R.id.radar_webview)
        spinner = findViewById(R.id.loading_spinner)
        updatePill = findViewById(R.id.btn_app_update)
        renderUpdatePill()

        updatePill.setOnClickListener {
            AppUpdater.showUpdateDialog(this, source = "main")
        }

        // Hidden Tester Mode toggle: long-press the version pill to pause/resume GA4 on this phone
        updatePill.setOnLongClickListener {
            val excluded = AnalyticsHelper.toggleDevExcluded(this)
            renderUpdatePill()
            val msg = if (excluded) {
                "Tester Mode ON — Your taps are hidden from GA4 & Firebase"
            } else {
                "Tester Mode OFF — Analytics resumed on this device"
            }
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            webView.loadUrl(buildRadarUrl())
            true
        }

        AppUpdater.checkInBackground { info ->
            if (info?.isNewer == true) {
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        updateAvailable = true
                        renderUpdatePill()
                    }
                }
            }
        }

        webView.setBackgroundColor(Color.parseColor("#030914"))
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            setGeolocationEnabled(true)
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            cacheMode = WebSettings.LOAD_DEFAULT
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                spinner.visibility = View.GONE
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val uri = request?.url ?: return false
                val host = uri.host.orEmpty()
                return if (host.endsWith("lovable.app") || host.endsWith("lovable.dev")) {
                    false
                } else {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, uri))
                    } catch (_: Exception) {
                        // Ignore unresolvable external intents
                    }
                    true
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onGeolocationPermissionsShowPrompt(
                origin: String?,
                callback: GeolocationPermissions.Callback?
            ) {
                val fine = ContextCompat.checkSelfPermission(
                    this@MainActivity,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
                val coarse = ContextCompat.checkSelfPermission(
                    this@MainActivity,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED

                if (fine || coarse) {
                    callback?.invoke(origin, true, false)
                } else {
                    pendingGeoOrigin = origin
                    pendingGeoCallback = callback
                    locationPermissionLauncher.launch(
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                        )
                    )
                }
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    finish()
                }
            }
        })

        if (savedInstanceState == null) {
            logLaunchIntent(intent)
            webView.loadUrl(buildRadarUrl())
        } else {
            webView.restoreState(savedInstanceState)
            spinner.visibility = View.GONE
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        logLaunchIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        AnalyticsHelper.syncCollectionState(this)
        renderUpdatePill()
    }

    private fun logLaunchIntent(launchIntent: Intent?) {
        val source = launchIntent?.getStringExtra(EXTRA_LAUNCH_SOURCE) ?: "launcher"
        val station = launchIntent?.getStringExtra(EXTRA_STATION_NAME).orEmpty()
        AnalyticsHelper.logRadarOpened(this, source = source, stationName = station)
    }

    private fun buildRadarUrl(): String {
        return if (AnalyticsHelper.isDevExcluded(this)) {
            "$RADAR_URL&dev=1"
        } else {
            RADAR_URL
        }
    }

    private fun renderUpdatePill() {
        val dev = AnalyticsHelper.isDevExcluded(this)
        if (updateAvailable) {
            updatePill.setBackgroundResource(R.drawable.update_pill_ready)
            updatePill.setTextColor(ContextCompat.getColor(this, R.color.bg_dark))
            updatePill.text = if (dev) "🧪 Update" else "⬇ Update ready"
        } else {
            updatePill.setBackgroundResource(R.drawable.update_pill_idle)
            updatePill.setTextColor(ContextCompat.getColor(this, R.color.cyan_accent))
            updatePill.text = if (dev) "🧪 v${BuildConfig.VERSION_NAME}" else "↓ v${BuildConfig.VERSION_NAME}"
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    companion object {
        private const val RADAR_URL = "https://sthlm-under.lovable.app/?platform=android"
        const val EXTRA_LAUNCH_SOURCE = "launch_source"
        const val EXTRA_STATION_NAME = "station_name"
    }
}
