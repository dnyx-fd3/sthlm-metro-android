package se.dnyx.sthlmmetro

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private data class BoardDeparture(
        val item: DepartureItem,
        val siteId: Int,
        val stationName: String,
        val walkMinutes: Int
    )

    private val executor = Executors.newSingleThreadExecutor()
    private val nearbyPool = Executors.newFixedThreadPool(4)
    private val uiHandler = Handler(Looper.getMainLooper())
    private val timeFmt = DateTimeFormatter.ofPattern("HH:mm")

    private var allSlots: List<StationSlot> = emptyList()
    private var pinnedSlots: List<Pair<Int, StationSlot>> = emptyList()
    private var nearMeMode = false
    private var selectedPinnedIdx = 0
    private var nearbyStops: List<NearbyStop> = emptyList()
    private var selectedNearbyIdx = -1
    private var nearbyDeparturesBySite: Map<Int, List<DepartureItem>> = emptyMap()

    private var activeFilter: ModeFilter = ModeFilter.ALL
    private var dirFilter: Int = 0

    private var rawDepartures: List<BoardDeparture> = emptyList()
    private var visibleDepartures: List<BoardDeparture> = emptyList()
    private var activeRemindersMap: Map<String, ActiveReminder> = emptyMap()
    private var lastSyncedMs: Long = 0L

    private lateinit var pillNearMe: TextView
    private lateinit var stopPills: List<TextView>
    private lateinit var pillAddStop: TextView
    private lateinit var nearbyStripScroll: View
    private lateinit var nearbyStripContainer: LinearLayout
    private lateinit var stationTitle: TextView
    private lateinit var stationMeta: TextView
    private lateinit var dirToggleBtn: TextView
    private lateinit var filterChips: Map<ModeFilter, TextView>
    private lateinit var stateCard: View
    private lateinit var stateCatImg: ImageView
    private lateinit var stateText: TextView
    private lateinit var stateActionBtn: TextView
    private lateinit var listView: ListView
    private lateinit var updateDot: View
    private lateinit var adapter: DepartureAdapter

    private val locationPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            val ok = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
            if (ok) {
                loadNearMeStops()
            } else {
                showState("Location off", showCat = false, actionLabel = "ALLOW") {
                    requestLocationAndLoad()
                }
            }
        }

    private val notifPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ -> }

    private val tickRunnable = object : Runnable {
        override fun run() {
            activeRemindersMap = WidgetPrefs.loadActiveReminders(this@MainActivity).associateBy { it.key }
            applyFiltersAndRenderList()
            uiHandler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AnalyticsHelper.syncCollectionState(this)
        AppUpdater.cleanupOldApks(this)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)

        val insetsController = WindowInsetsControllerCompat(window, window.decorView)
        insetsController.isAppearanceLightStatusBars = false
        insetsController.isAppearanceLightNavigationBars = false

        val root = findViewById<View>(R.id.main_root)
        val baseH = (16 * resources.displayMetrics.density).toInt()
        val baseV = (12 * resources.displayMetrics.density).toInt()
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, windowInsets ->
            val insets = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.setPadding(baseH + insets.left, baseV + insets.top, baseH + insets.right, baseV + insets.bottom)
            windowInsets
        }

        pillNearMe = findViewById(R.id.pill_near_me)
        stopPills = listOf(
            findViewById(R.id.pill_stop_0),
            findViewById(R.id.pill_stop_1),
            findViewById(R.id.pill_stop_2)
        )
        pillAddStop = findViewById(R.id.pill_add_stop)
        nearbyStripScroll = findViewById(R.id.nearby_strip_scroll)
        nearbyStripContainer = findViewById(R.id.nearby_strip_container)
        stationTitle = findViewById(R.id.board_station_title)
        stationMeta = findViewById(R.id.board_station_meta)
        dirToggleBtn = findViewById(R.id.btn_dir_toggle)
        stateCard = findViewById(R.id.board_state_card)
        stateCatImg = findViewById(R.id.board_state_cat)
        stateText = findViewById(R.id.board_state_text)
        stateActionBtn = findViewById(R.id.board_state_action)
        listView = findViewById(R.id.board_list_view)
        updateDot = findViewById(R.id.settings_update_dot)

        filterChips = mapOf(
            ModeFilter.ALL to findViewById(R.id.filter_all),
            ModeFilter.METRO to findViewById(R.id.filter_metro),
            ModeFilter.BUS to findViewById(R.id.filter_bus),
            ModeFilter.PENDEL to findViewById(R.id.filter_pendel)
        )

        adapter = DepartureAdapter()
        listView.adapter = adapter

        findViewById<View>(R.id.btn_open_3d_web).setOnClickListener { open3dWebRadar() }
        findViewById<View>(R.id.btn_open_settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        pillAddStop.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<View>(R.id.btn_board_refresh).setOnClickListener {
            if (nearMeMode && nearbyStops.isEmpty()) {
                requestLocationAndLoad()
            } else {
                fetchActiveStopDepartures()
            }
        }

        pillNearMe.setOnClickListener {
            nearMeMode = true
            selectedNearbyIdx = -1
            dirFilter = 0
            activeFilter = ModeFilter.ALL
            renderTopPills()
            renderNearbyStrip()
            renderFilterChips()
            renderDirButton()
            requestLocationAndLoad()
        }

        stopPills.forEachIndexed { idx, pill ->
            pill.setOnClickListener {
                if (idx < pinnedSlots.size) {
                    nearMeMode = false
                    selectedPinnedIdx = idx
                    dirFilter = 0
                    val slotFilter = pinnedSlots[idx].second.filter
                    activeFilter = if (slotFilter == ModeFilter.AUTO) ModeFilter.ALL else slotFilter
                    renderTopPills()
                    renderFilterChips()
                    renderDirButton()
                    fetchActiveStopDepartures()
                }
            }
        }

        filterChips.forEach { (mode, chip) ->
            chip.setOnClickListener {
                activeFilter = mode
                renderFilterChips()
                applyFiltersAndRenderList()
            }
        }

        dirToggleBtn.setOnClickListener {
            dirFilter = (dirFilter + 1) % 3
            renderDirButton()
            applyFiltersAndRenderList()
        }

        listView.setOnItemClickListener { _, _, position, _ ->
            val row = visibleDepartures.getOrNull(position) ?: return@setOnItemClickListener
            val d = row.item
            val origSlotIdx = if (nearMeMode) -1 else currentStopInfo().third
            startActivity(
                Intent(this, DepartureReminderActivity::class.java).apply {
                    putExtra(DepartureReminderActivity.EXTRA_SITE_ID, row.siteId)
                    putExtra(DepartureReminderActivity.EXTRA_SLOT_INDEX, origSlotIdx)
                    putExtra(DepartureReminderActivity.EXTRA_STATION_NAME, row.stationName)
                    putExtra(DepartureReminderActivity.EXTRA_DESIGNATION, d.designation)
                    putExtra(DepartureReminderActivity.EXTRA_DESTINATION, d.destination)
                    putExtra(DepartureReminderActivity.EXTRA_CLOCK_TIME, d.clockTime)
                    putExtra(DepartureReminderActivity.EXTRA_EXPECTED_EPOCH_MS, d.expectedEpochMs)
                    putExtra(DepartureReminderActivity.EXTRA_BADGE_STYLE, d.badgeStyle.name)
                    putExtra(DepartureReminderActivity.EXTRA_WALK_MINUTES, row.walkMinutes)
                }
            )
        }

        val source = intent?.getStringExtra(EXTRA_LAUNCH_SOURCE) ?: "launcher"
        AnalyticsHelper.logAppOpened(this, source)

        // Politely request notification permission once on Android 13+ so reminder banners show cleanly
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !WidgetPrefs.wasNotifPrompted(this)) {
            val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
            WidgetPrefs.markNotifPrompted(this)
            if (!granted) {
                notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        AppUpdater.checkInBackground { info ->
            if (info?.isNewer == true) {
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        updateDot.visibility = View.VISIBLE
                    }
                }
            }
        }

        executor.execute {
            if (WidgetPrefs.loadFreshSitesCache(this) == null) {
                try {
                    val fresh = SlApiClient.fetchAllSites()
                    if (fresh.isNotEmpty()) WidgetPrefs.saveSitesCache(this, fresh)
                } catch (_: Exception) {
                    // Ignore offline
                }
            }
        }

        reloadSharedSlotsAndFetch()
    }

    override fun onResume() {
        super.onResume()
        AnalyticsHelper.syncCollectionState(this)
        activeRemindersMap = WidgetPrefs.loadActiveReminders(this).associateBy { it.key }
        val latestSlots = WidgetPrefs.loadSharedSlots(this)
        if (latestSlots != allSlots) {
            allSlots = latestSlots
            pinnedSlots = allSlots.mapIndexedNotNull { idx, s -> if (s.isNearMe) null else idx to s }
            if (selectedPinnedIdx >= pinnedSlots.size) selectedPinnedIdx = 0
            if (pinnedSlots.isEmpty()) nearMeMode = true
            renderTopPills()
            if (nearMeMode) requestLocationAndLoad() else fetchActiveStopDepartures()
        }
        uiHandler.post(tickRunnable)
    }

    override fun onPause() {
        super.onPause()
        uiHandler.removeCallbacks(tickRunnable)
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdownNow()
        nearbyPool.shutdownNow()
    }

    private fun reloadSharedSlotsAndFetch() {
        allSlots = WidgetPrefs.loadSharedSlots(this)
        pinnedSlots = allSlots.mapIndexedNotNull { idx, s -> if (s.isNearMe) null else idx to s }
        if (pinnedSlots.isEmpty()) {
            nearMeMode = true
            selectedNearbyIdx = -1
            renderTopPills()
            renderFilterChips()
            renderDirButton()
            requestLocationAndLoad()
            return
        }
        if (selectedPinnedIdx >= pinnedSlots.size) selectedPinnedIdx = 0
        val initFilter = pinnedSlots.getOrNull(selectedPinnedIdx)?.second?.filter ?: ModeFilter.AUTO
        activeFilter = if (initFilter == ModeFilter.AUTO) ModeFilter.ALL else initFilter
        renderTopPills()
        renderFilterChips()
        renderDirButton()
        fetchActiveStopDepartures()
    }

    private fun currentStopInfo(): Triple<String, Int, Int> {
        if (nearMeMode) {
            val near = if (selectedNearbyIdx >= 0) {
                nearbyStops.getOrNull(selectedNearbyIdx)
            } else {
                nearbyStops.firstOrNull { "METRO" in it.modes || "TRAIN" in it.modes } ?: nearbyStops.firstOrNull()
            }
            if (near != null) return Triple(near.name, near.walkMinutes, -1)
        }
        val pair = pinnedSlots.getOrNull(selectedPinnedIdx)
        if (pair != null) {
            return Triple(pair.second.stationName, pair.second.walkMinutes, pair.first)
        }
        return Triple(WidgetPrefs.DEFAULT_STATION_NAME, 3, -1)
    }

    private fun currentSiteId(): Int {
        if (nearMeMode) {
            val near = if (selectedNearbyIdx >= 0) {
                nearbyStops.getOrNull(selectedNearbyIdx)
            } else {
                nearbyStops.firstOrNull { "METRO" in it.modes || "TRAIN" in it.modes } ?: nearbyStops.firstOrNull()
            }
            near?.let { return it.siteId }
        }
        return pinnedSlots.getOrNull(selectedPinnedIdx)?.second?.siteId ?: WidgetPrefs.DEFAULT_SITE_ID
    }

    private fun open3dWebRadar() {
        val (stopName, _, _) = currentStopInfo()
        val siteId = currentSiteId()
        val slug = stopName.lowercase(Locale.US)
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
            .ifEmpty { "stockholm" }

        val devSuffix = if (AnalyticsHelper.isDevExcluded(this)) "&dev=1" else ""
        val url = "https://sthlm-under.lovable.app/" +
            "?utm_source=tradar_android" +
            "&utm_medium=app" +
            "&utm_campaign=3d_radar" +
            "&utm_content=$slug" +
            "&siteId=$siteId$devSuffix"

        AnalyticsHelper.logWeb3dOpened(this, stopName, siteId)

        try {
            val darkColors = CustomTabColorSchemeParams.Builder()
                .setToolbarColor(Color.parseColor("#040914"))
                .setNavigationBarColor(Color.parseColor("#040914"))
                .build()
            val customTabs = CustomTabsIntent.Builder()
                .setDefaultColorSchemeParams(darkColors)
                .setShowTitle(true)
                .build()
            customTabs.launchUrl(this, Uri.parse(url))
        } catch (_: Exception) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            } catch (_: Exception) {
                // Ignore
            }
        }
    }

    private fun requestLocationAndLoad() {
        if (LocationHelper.hasLocationPermission(this)) {
            loadNearMeStops()
        } else {
            locationPermLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    private fun loadNearMeStops() {
        stationTitle.text = "Near me"
        stationMeta.text = "Locating…"
        showState("Locating…", showCat = false)

        LocationHelper.getQuickLocation(this) { loc ->
            if (loc == null) {
                runOnUiThread {
                    showState("GPS unavailable", showCat = false, actionLabel = "RETRY") {
                        loadNearMeStops()
                    }
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
                val nearest = LocationHelper.findNearestDiverseStops(loc.latitude, loc.longitude, sites, limit = 4)
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    nearbyStops = nearest
                    selectedNearbyIdx = -1
                    nearbyDeparturesBySite = emptyMap()
                    renderNearbyStrip()
                    val first = nearest.firstOrNull()
                    if (first != null) {
                        AnalyticsHelper.logNearMeUsed(this, "app", first.name, first.walkMinutes)
                        fetchActiveStopDepartures()
                    } else {
                        showState("No stops nearby", showCat = false)
                    }
                }
            }
        }
    }

    private fun fetchActiveStopDepartures() {
        if (nearMeMode && selectedNearbyIdx == -1 && nearbyStops.isNotEmpty()) {
            fetchCombinedNearbyDepartures(nearbyStops)
            return
        }

        val siteId = currentSiteId()
        val (stopName, walkMin, _) = currentStopInfo()
        stationTitle.text = stopName
        stationMeta.text = "${walkMin}m walk · Updating…"

        if (rawDepartures.isEmpty()) {
            showState("Loading…", showCat = false)
        }

        executor.execute {
            try {
                val list = SlApiClient.fetchDepartures(siteId, ModeFilter.ALL, limit = 24)
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    if (nearMeMode && selectedNearbyIdx == -1) return@runOnUiThread
                    if (currentSiteId() != siteId) return@runOnUiThread
                    if (nearMeMode) {
                        nearbyDeparturesBySite = nearbyDeparturesBySite + (siteId to list)
                    }
                    rawDepartures = list.map { BoardDeparture(it, siteId, stopName, walkMin) }
                    lastSyncedMs = System.currentTimeMillis()
                    updateHeaderMeta()
                    applyFiltersAndRenderList()
                }
            } catch (_: SlRateLimitException) {
                runOnUiThread {
                    if (!isFinishing) showState("Rate limited · Tap ↻", showCat = false)
                }
            } catch (_: Exception) {
                runOnUiThread {
                    if (!isFinishing) {
                        showState("Offline", showCat = false, actionLabel = "RETRY") {
                            fetchActiveStopDepartures()
                        }
                    }
                }
            }
        }
    }

    private fun fetchCombinedNearbyDepartures(stops: List<NearbyStop>) {
        stationTitle.text = "Near me"
        stationMeta.text = "${stops.size} stops · Updating…"
        if (rawDepartures.isEmpty()) {
            showState("Loading…", showCat = false)
        }

        executor.execute {
            val futures = stops.map { stop ->
                stop to nearbyPool.submit(Callable {
                    try {
                        Result.success(SlApiClient.fetchDepartures(stop.siteId, ModeFilter.ALL, limit = 12))
                    } catch (e: Exception) {
                        Result.failure(e)
                    }
                })
            }

            val perSite = linkedMapOf<Int, List<DepartureItem>>()
            var rateLimited = false
            var succeeded = 0
            for ((stop, future) in futures) {
                try {
                    val res = future.get()
                    res.onSuccess { items ->
                        succeeded++
                        perSite[stop.siteId] = items
                    }.onFailure { err ->
                        if (err is SlRateLimitException) rateLimited = true
                    }
                } catch (_: Exception) {
                    // Ignore individual stop failure if others succeed
                }
            }

            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (!nearMeMode || selectedNearbyIdx != -1) return@runOnUiThread

                if (succeeded == 0) {
                    if (rateLimited) {
                        showState("Rate limited · Tap ↻", showCat = false)
                    } else {
                        showState("Offline", showCat = false, actionLabel = "RETRY") {
                            fetchActiveStopDepartures()
                        }
                    }
                    return@runOnUiThread
                }

                nearbyDeparturesBySite = perSite
                rawDepartures = buildCombinedNearbyList(stops, perSite)
                lastSyncedMs = System.currentTimeMillis()
                updateHeaderMeta()
                applyFiltersAndRenderList()
            }
        }
    }

    private fun buildCombinedNearbyList(
        stops: List<NearbyStop>,
        perSite: Map<Int, List<DepartureItem>>
    ): List<BoardDeparture> {
        return stops.flatMap { stop ->
            perSite[stop.siteId].orEmpty().take(8).map { item ->
                BoardDeparture(item, stop.siteId, stop.name, stop.walkMinutes)
            }
        }.sortedBy {
            if (it.item.expectedEpochMs > 0L) it.item.expectedEpochMs else Long.MAX_VALUE
        }
    }

    private fun updateHeaderMeta() {
        val clock = if (lastSyncedMs > 0L) {
            Instant.ofEpochMilli(lastSyncedMs).atZone(ZoneId.systemDefault()).toLocalTime().format(timeFmt)
        } else {
            ""
        }
        if (nearMeMode && selectedNearbyIdx == -1) {
            stationTitle.text = "Near me"
            stationMeta.text = "${nearbyStops.size} stops" + if (clock.isNotEmpty()) " · $clock" else ""
            return
        }
        val (stopName, walkMin, _) = currentStopInfo()
        stationTitle.text = stopName
        stationMeta.text = "${walkMin}m walk" + if (clock.isNotEmpty()) " · $clock" else ""
    }

    private fun applyFiltersAndRenderList() {
        val nowMs = System.currentTimeMillis()
        val live = rawDepartures.filter {
            it.item.expectedEpochMs <= 0L || it.item.expectedEpochMs > nowMs - 10_000L
        }

        val byMode = when (activeFilter) {
            ModeFilter.METRO -> live.filter { it.item.transportMode == "METRO" }
            ModeFilter.BUS -> live.filter { it.item.transportMode == "BUS" }
            ModeFilter.PENDEL -> live.filter {
                it.item.transportMode == "TRAIN" && it.item.badgeStyle == LineBadgeStyle.PENDEL
            }
            else -> live
        }

        visibleDepartures = if (dirFilter == 0) {
            byMode
        } else {
            byMode.filter { it.item.directionCode == dirFilter }
        }

        if (visibleDepartures.isEmpty()) {
            if (lastSyncedMs > 0L) {
                if (live.isEmpty()) {
                    showState("All departed · ↻", showCat = true, actionLabel = "REFRESH") {
                        fetchActiveStopDepartures()
                    }
                } else {
                    showState("No matches", showCat = false)
                }
            }
            adapter.notifyDataSetChanged()
        } else {
            stateCard.visibility = View.GONE
            listView.visibility = View.VISIBLE
            adapter.notifyDataSetChanged()
        }
    }

    private fun showState(msg: String, showCat: Boolean, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
        stateCard.visibility = View.VISIBLE
        listView.visibility = View.GONE
        stateCatImg.visibility = if (showCat) View.VISIBLE else View.GONE
        stateText.text = msg
        if (actionLabel != null && onAction != null) {
            stateActionBtn.visibility = View.VISIBLE
            stateActionBtn.text = actionLabel
            stateActionBtn.setOnClickListener { onAction() }
        } else {
            stateActionBtn.visibility = View.GONE
        }
    }

    private fun renderTopPills() {
        val dark = ContextCompat.getColor(this, R.color.bg_dark)
        val primary = ContextCompat.getColor(this, R.color.text_primary)

        pillNearMe.setBackgroundResource(if (nearMeMode) R.drawable.chip_active else R.drawable.chip_idle)
        pillNearMe.setTextColor(if (nearMeMode) dark else primary)

        for (i in 0 until 3) {
            if (i < pinnedSlots.size) {
                stopPills[i].visibility = View.VISIBLE
                stopPills[i].text = MetroWidgetProvider.shortenTabLabel(pinnedSlots[i].second.stationName)
                val active = !nearMeMode && i == selectedPinnedIdx
                stopPills[i].setBackgroundResource(if (active) R.drawable.chip_active else R.drawable.chip_idle)
                stopPills[i].setTextColor(if (active) dark else primary)
            } else {
                stopPills[i].visibility = View.GONE
            }
        }
        pillAddStop.visibility = if (allSlots.size < 3) View.VISIBLE else View.GONE
        nearbyStripScroll.visibility = if (nearMeMode && nearbyStops.isNotEmpty()) View.VISIBLE else View.GONE
    }

    private fun renderNearbyStrip() {
        nearbyStripContainer.removeAllViews()
        if (!nearMeMode || nearbyStops.isEmpty()) {
            nearbyStripScroll.visibility = View.GONE
            return
        }
        nearbyStripScroll.visibility = View.VISIBLE
        val density = resources.displayMetrics.density
        val dark = ContextCompat.getColor(this, R.color.bg_dark)
        val secondary = ContextCompat.getColor(this, R.color.text_secondary)

        fun addNearbyChip(label: String, sel: Boolean, onClick: () -> Unit) {
            val chip = TextView(this).apply {
                text = label
                textSize = 11f
                setPadding((10 * density).toInt(), (5 * density).toInt(), (10 * density).toInt(), (5 * density).toInt())
                setBackgroundResource(if (sel) R.drawable.chip_active else R.drawable.chip_idle)
                setTextColor(if (sel) dark else secondary)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = (6 * density).toInt() }
                setOnClickListener { onClick() }
            }
            nearbyStripContainer.addView(chip)
        }

        if (nearbyStops.size > 1) {
            addNearbyChip("All", selectedNearbyIdx == -1) {
                selectedNearbyIdx = -1
                dirFilter = 0
                renderNearbyStrip()
                renderDirButton()
                if (nearbyDeparturesBySite.isNotEmpty()) {
                    rawDepartures = buildCombinedNearbyList(nearbyStops, nearbyDeparturesBySite)
                    updateHeaderMeta()
                    applyFiltersAndRenderList()
                } else {
                    fetchActiveStopDepartures()
                }
            }
        }

        nearbyStops.forEachIndexed { idx, stop ->
            val label = "${stop.modeIcon()} ${MetroWidgetProvider.shortenTabLabel(stop.name)} · ${stop.walkMinutes}m"
            addNearbyChip(label, idx == selectedNearbyIdx) {
                selectedNearbyIdx = idx
                dirFilter = 0
                renderNearbyStrip()
                renderDirButton()
                val cached = nearbyDeparturesBySite[stop.siteId]
                if (cached != null) {
                    rawDepartures = cached.map { BoardDeparture(it, stop.siteId, stop.name, stop.walkMinutes) }
                    updateHeaderMeta()
                    applyFiltersAndRenderList()
                } else {
                    fetchActiveStopDepartures()
                }
            }
        }
    }

    private fun renderFilterChips() {
        val dark = ContextCompat.getColor(this, R.color.bg_dark)
        val primary = ContextCompat.getColor(this, R.color.text_primary)
        filterChips.forEach { (mode, chip) ->
            val sel = mode == activeFilter
            chip.setBackgroundResource(if (sel) R.drawable.chip_active else R.drawable.chip_idle)
            chip.setTextColor(if (sel) dark else primary)
        }
    }

    private fun renderDirButton() {
        dirToggleBtn.text = when (dirFilter) {
            1 -> "↑ 1"
            2 -> "↓ 2"
            else -> "⇅"
        }
        val active = dirFilter != 0
        dirToggleBtn.setTextColor(
            ContextCompat.getColor(this, if (active) R.color.cyan_accent else R.color.text_secondary)
        )
    }

    private inner class DepartureAdapter : BaseAdapter() {
        override fun getCount(): Int = visibleDepartures.size
        override fun getItem(position: Int): BoardDeparture = visibleDepartures[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView ?: LayoutInflater.from(this@MainActivity)
                .inflate(R.layout.item_departure, parent, false)
            val row = visibleDepartures[position]
            val d = row.item
            val nowMs = System.currentTimeMillis()
            val remainingSec = if (d.expectedEpochMs > 0L) ((d.expectedEpochMs - nowMs) / 1000L).toInt() else -1

            val remKey = ReminderManager.buildKey(row.siteId, d.designation, d.destination, d.expectedEpochMs)
            val activeRem = activeRemindersMap[remKey]

            val badge = v.findViewById<TextView>(R.id.dep_badge)
            badge.text = d.designation
            badge.setBackgroundResource(MetroWidgetProvider.badgeDrawableFor(d.badgeStyle))

            val dest = v.findViewById<TextView>(R.id.dep_dest)
            dest.text = d.destination
            dest.setTextColor(
                ContextCompat.getColor(this@MainActivity, if (d.cancelled) R.color.rose_cancel else R.color.text_primary)
            )

            val sub = v.findViewById<TextView>(R.id.dep_sub)
            val parts = mutableListOf<String>()
            if (activeRem != null) parts.add("🔔 ${ReminderManager.formatClock(activeRem.triggerAtMs)}")
            if (nearMeMode && selectedNearbyIdx == -1) {
                parts.add("${MetroWidgetProvider.shortenTabLabel(row.stationName)} · ${row.walkMinutes}m")
            }
            if (d.stopPoint.isNotEmpty()) parts.add("Bay ${d.stopPoint}")
            if (d.cancelled) parts.add("Cancelled")
            else if (d.delayMinutes > 0) parts.add("+${d.delayMinutes}m")

            if (parts.isNotEmpty()) {
                sub.visibility = View.VISIBLE
                sub.text = parts.joinToString(" · ")
                sub.setTextColor(
                    ContextCompat.getColor(
                        this@MainActivity,
                        when {
                            d.cancelled -> R.color.rose_cancel
                            activeRem != null -> R.color.cyan_accent
                            d.delayMinutes > 0 -> R.color.amber_warn
                            else -> R.color.text_secondary
                        }
                    )
                )
            } else {
                sub.visibility = View.GONE
            }

            val eta = v.findViewById<TextView>(R.id.dep_eta)
            val etaStr = when {
                d.cancelled -> "—"
                d.expectedEpochMs > 0L && remainingSec <= 5 -> "NOW"
                d.expectedEpochMs > 0L && remainingSec < 3600 -> {
                    String.format(Locale.US, "%02d:%02d", remainingSec / 60, remainingSec % 60)
                }
                d.clockTime.isNotEmpty() -> d.clockTime
                else -> d.display
            }
            eta.text = etaStr
            eta.setTextColor(
                ContextCompat.getColor(
                    this@MainActivity,
                    when {
                        d.cancelled -> R.color.rose_cancel
                        d.delayMinutes > 0 -> R.color.amber_warn
                        else -> R.color.cyan_accent
                    }
                )
            )

            v.findViewById<TextView>(R.id.dep_clock).text = d.clockTime
            return v
        }
    }

    companion object {
        const val EXTRA_LAUNCH_SOURCE = "launch_source"
    }
}
