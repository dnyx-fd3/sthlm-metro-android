package se.dnyx.sthlmmetro

import android.Manifest
import android.appwidget.AppWidgetManager
import android.os.Bundle
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.util.concurrent.Executors

class WidgetLocationActivity : AppCompatActivity() {

    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private lateinit var titleView: TextView
    private lateinit var bodyView: TextView
    private lateinit var spinner: ProgressBar
    private lateinit var primaryBtn: TextView

    private val executor = Executors.newSingleThreadExecutor()

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            val ok = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
            if (ok) {
                locateAndApply()
            } else {
                Toast.makeText(this, "Location permission needed for Near Me", Toast.LENGTH_SHORT).show()
                finish()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AnalyticsHelper.syncCollectionState(this)
        setContentView(R.layout.activity_widget_location)

        appWidgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        titleView = findViewById(R.id.loc_title)
        bodyView = findViewById(R.id.loc_body)
        spinner = findViewById(R.id.loc_spinner)
        primaryBtn = findViewById(R.id.loc_primary_btn)

        findViewById<View>(R.id.loc_backdrop).setOnClickListener { finish() }
        findViewById<View>(R.id.loc_cancel_btn).setOnClickListener { finish() }

        if (LocationHelper.hasLocationPermission(this)) {
            locateAndApply()
        } else {
            titleView.text = "Allow location for Near Me?"
            bodyView.text = "T-Radar uses your GPS only when you tap 📍 to show your closest SL stop and walk time."
            spinner.visibility = View.GONE
            primaryBtn.visibility = View.VISIBLE
            primaryBtn.setOnClickListener {
                permLauncher.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    )
                )
            }
        }
    }

    private fun locateAndApply() {
        titleView.text = "Finding closest SL stop…"
        bodyView.text = "Checking GPS and nearby Stockholm stops."
        spinner.visibility = View.VISIBLE
        primaryBtn.visibility = View.GONE

        LocationHelper.getQuickLocation(this) { loc ->
            if (loc == null) {
                runOnUiThread {
                    if (isFinishing) return@runOnUiThread
                    spinner.visibility = View.GONE
                    titleView.text = "GPS unavailable"
                    bodyView.text = "Turn on Location Services and try tapping 📍 again."
                    primaryBtn.visibility = View.VISIBLE
                    primaryBtn.text = "TRY AGAIN"
                    primaryBtn.setOnClickListener { locateAndApply() }
                }
                return@getQuickLocation
            }

            executor.execute {
                val sites = WidgetPrefs.loadFreshSitesCache(this) ?: try {
                    val fresh = SlApiClient.fetchAllSites()
                    if (fresh.isNotEmpty()) WidgetPrefs.saveSitesCache(this, fresh)
                    fresh
                } catch (_: Exception) {
                    SlApiClient.POPULAR_STATIONS
                }

                val nearest = LocationHelper.findNearestStops(loc.latitude, loc.longitude, sites, limit = 1).firstOrNull()
                if (nearest == null) {
                    runOnUiThread { finish() }
                    return@execute
                }

                AnalyticsHelper.logNearMeUsed(this, "widget", nearest.name, nearest.walkMinutes)

                if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                    WidgetPrefs.setNearbyOverride(
                        this,
                        appWidgetId,
                        NearbyOverride(
                            siteId = nearest.siteId,
                            stationName = nearest.name,
                            walkMinutes = nearest.walkMinutes,
                            distanceMeters = nearest.distanceMeters,
                            updatedAtMs = System.currentTimeMillis()
                        )
                    )
                    MetroWidgetProvider.triggerAsyncRefresh(this, appWidgetId, trigger = "widget_near_me")
                }

                runOnUiThread {
                    Toast.makeText(
                        this,
                        "📍 ${nearest.name} (${nearest.walkMinutes} min walk)",
                        Toast.LENGTH_SHORT
                    ).show()
                    finish()
                }
            }
        }
    }
}
