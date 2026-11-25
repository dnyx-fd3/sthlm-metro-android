package se.dnyx.sthlmmetro

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.util.concurrent.Executors

class WidgetConfigActivity : AppCompatActivity() {

    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private val slots = arrayOfNulls<StationSlot>(3)
    private var editingIndex = 0
    private var opacityPercent = 100
    private var brandTapCount = 0
    private var lastBrandTapMs = 0L

    private var allSites: List<StationOption> = SlApiClient.POPULAR_STATIONS
    private var displayedSites: List<StationOption> = SlApiClient.POPULAR_STATIONS
    private lateinit var adapter: StationAdapter

    private lateinit var brandTitleView: TextView
    private lateinit var searchInput: EditText
    private lateinit var subtitleView: TextView
    private lateinit var clearSlotBtn: TextView
    private lateinit var walkBar: View
    private lateinit var walkValueText: TextView
    private lateinit var opacitySeek: SeekBar
    private lateinit var opacityValueText: TextView
    private lateinit var updateBtn: TextView
    private lateinit var slotContainers: List<LinearLayout>
    private lateinit var slotLabels: List<TextView>
    private lateinit var slotModes: List<TextView>
    private lateinit var filterChips: Map<ModeFilter, TextView>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AnalyticsHelper.syncCollectionState(this)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        setResult(RESULT_CANCELED)
        setContentView(R.layout.activity_widget_config)

        val insetsController = WindowInsetsControllerCompat(window, window.decorView)
        insetsController.isAppearanceLightStatusBars = false
        insetsController.isAppearanceLightNavigationBars = false

        val root = findViewById<View>(R.id.config_root)
        val basePad = (16 * resources.displayMetrics.density).toInt()
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, windowInsets ->
            val insets = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.setPadding(
                basePad + insets.left,
                basePad + insets.top,
                basePad + insets.right,
                basePad + insets.bottom
            )
            windowInsets
        }

        appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        val existing = WidgetPrefs.load(this, appWidgetId)
        for (i in existing.slots.indices.take(3)) {
            slots[i] = existing.slots[i]
        }
        if (slots[0] == null) {
            slots[0] = StationSlot(WidgetPrefs.DEFAULT_SITE_ID, WidgetPrefs.DEFAULT_STATION_NAME, ModeFilter.AUTO, 3)
        }
        editingIndex = existing.activeSlotIndex.coerceIn(0, 2)
        opacityPercent = existing.opacityPercent.coerceIn(0, 100)

        brandTitleView = findViewById(R.id.config_brand_title)
        searchInput = findViewById(R.id.station_search_input)
        subtitleView = findViewById(R.id.config_subtitle)
        clearSlotBtn = findViewById(R.id.btn_clear_slot)
        walkBar = findViewById(R.id.config_walk_bar)
        walkValueText = findViewById(R.id.config_walk_value)
        opacitySeek = findViewById(R.id.config_opacity_seek)
        opacityValueText = findViewById(R.id.config_opacity_value)
        updateBtn = findViewById(R.id.config_update_btn)

        renderBrandHeader()

        // Hidden Tester Mode: tap "T-RADAR" 5 times quickly (or long-press Update)
        brandTitleView.setOnClickListener {
            val now = System.currentTimeMillis()
            if (now - lastBrandTapMs > 1500L) {
                brandTapCount = 0
            }
            lastBrandTapMs = now
            brandTapCount++
            if (brandTapCount >= 5) {
                brandTapCount = 0
                toggleTesterMode()
            }
        }

        updateBtn.setOnClickListener { AppUpdater.showUpdateDialog(this, source = "config") }
        updateBtn.setOnLongClickListener {
            toggleTesterMode()
            true
        }

        opacitySeek.progress = opacityPercent
        opacityValueText.text = "$opacityPercent%"
        opacitySeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                opacityPercent = progress.coerceIn(0, 100)
                opacityValueText.text = "$opacityPercent%"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        slotContainers = listOf(
            findViewById(R.id.slot_btn_0),
            findViewById(R.id.slot_btn_1),
            findViewById(R.id.slot_btn_2)
        )
        slotLabels = listOf(
            findViewById(R.id.slot_label_0),
            findViewById(R.id.slot_label_1),
            findViewById(R.id.slot_label_2)
        )
        slotModes = listOf(
            findViewById(R.id.slot_mode_0),
            findViewById(R.id.slot_mode_1),
            findViewById(R.id.slot_mode_2)
        )
        filterChips = mapOf(
            ModeFilter.AUTO to findViewById(R.id.chip_auto),
            ModeFilter.BUS to findViewById(R.id.chip_bus),
            ModeFilter.PENDEL to findViewById(R.id.chip_pendel),
            ModeFilter.ALL to findViewById(R.id.chip_all)
        )

        slotContainers.forEachIndexed { idx, container ->
            container.setOnClickListener {
                editingIndex = idx
                renderSlotUI()
            }
        }

        filterChips.forEach { (mode, chip) ->
            chip.setOnClickListener {
                val cur = slots[editingIndex]
                if (cur != null) {
                    slots[editingIndex] = cur.copy(filter = mode)
                    renderSlotUI()
                }
            }
        }

        findViewById<View>(R.id.config_walk_minus).setOnClickListener {
            val cur = slots[editingIndex] ?: return@setOnClickListener
            if (cur.walkMinutes > 0) {
                slots[editingIndex] = cur.copy(walkMinutes = cur.walkMinutes - 1)
                renderSlotUI()
            }
        }

        findViewById<View>(R.id.config_walk_plus).setOnClickListener {
            val cur = slots[editingIndex] ?: return@setOnClickListener
            if (cur.walkMinutes < 30) {
                slots[editingIndex] = cur.copy(walkMinutes = cur.walkMinutes + 1)
                renderSlotUI()
            }
        }

        clearSlotBtn.setOnClickListener {
            if (editingIndex > 0) {
                slots[editingIndex] = null
                renderSlotUI()
            }
        }

        val listView = findViewById<ListView>(R.id.station_list_view)
        adapter = StationAdapter()
        listView.adapter = adapter

        listView.setOnItemClickListener { _, _, position, _ ->
            val picked = displayedSites[position]
            val cur = slots[editingIndex]
            slots[editingIndex] = StationSlot(
                siteId = picked.siteId,
                stationName = picked.name,
                filter = cur?.filter ?: ModeFilter.AUTO,
                walkMinutes = cur?.walkMinutes ?: 3
            )
            AnalyticsHelper.logStationPicked(this, picked.name, picked.siteId, editingIndex)
            searchInput.setText("")
            renderSlotUI()
        }

        findViewById<TextView>(R.id.btn_save_widget).setOnClickListener {
            val nonEmpty = slots.filterNotNull()
            val finalSlots = nonEmpty.ifEmpty {
                listOf(StationSlot(WidgetPrefs.DEFAULT_SITE_ID, WidgetPrefs.DEFAULT_STATION_NAME, ModeFilter.AUTO, 3))
            }
            WidgetPrefs.saveSlots(
                context = this,
                appWidgetId = appWidgetId,
                slots = finalSlots,
                activeIndex = 0,
                opacityPercent = opacityPercent
            )
            AnalyticsHelper.logWidgetConfigured(this, finalSlots, opacityPercent)
            MetroWidgetProvider.triggerAsyncRefresh(this, appWidgetId, trigger = "config_save")

            val result = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            setResult(RESULT_OK, result)
            finish()
        }

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filterList(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })

        renderSlotUI()

        AppUpdater.checkInBackground { info ->
            if (info?.isNewer == true) {
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        updateBtn.setBackgroundResource(R.drawable.update_pill_ready)
                        updateBtn.setTextColor(ContextCompat.getColor(this, R.color.bg_dark))
                        updateBtn.text = "⬇ Update!"
                    }
                }
            }
        }

        Executors.newSingleThreadExecutor().execute {
            val cachedSites = WidgetPrefs.loadFreshSitesCache(this)
            val remoteSites = if (cachedSites != null) {
                cachedSites
            } else {
                try {
                    val fresh = SlApiClient.fetchAllSites()
                    if (fresh.isNotEmpty()) {
                        WidgetPrefs.saveSitesCache(this, fresh)
                    }
                    fresh
                } catch (_: Exception) {
                    emptyList()
                }
            }
            if (remoteSites.isNotEmpty()) {
                val popularIds = SlApiClient.POPULAR_STATIONS.map { it.siteId }.toSet()
                val merged = SlApiClient.POPULAR_STATIONS +
                    remoteSites.filter { it.siteId !in popularIds }.sortedBy { it.name.lowercase() }
                runOnUiThread {
                    allSites = merged
                    subtitleView.text = "Search ${remoteSites.size} SL bus stops, metro & pendeltåg stations"
                    filterList(searchInput.text?.toString().orEmpty())
                }
            }
        }
    }

    private fun toggleTesterMode() {
        val excluded = AnalyticsHelper.toggleDevExcluded(this)
        renderBrandHeader()
        val msg = if (excluded) {
            "Tester Mode ON — Your taps are hidden from GA4 & Firebase"
        } else {
            "Tester Mode OFF — Analytics resumed on this device"
        }
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    private fun renderBrandHeader() {
        brandTitleView.text = if (AnalyticsHelper.isDevExcluded(this)) {
            "T-RADAR · 🧪 TESTER (NO GA4)"
        } else {
            "T-RADAR"
        }
    }

    private fun renderSlotUI() {
        val primary = ContextCompat.getColor(this, R.color.text_primary)
        val secondary = ContextCompat.getColor(this, R.color.text_secondary)
        val dark = ContextCompat.getColor(this, R.color.bg_dark)

        for (i in 0 until 3) {
            val isEditing = i == editingIndex
            slotContainers[i].setBackgroundResource(
                if (isEditing) R.drawable.slot_pill_active else R.drawable.slot_pill_idle
            )
            val s = slots[i]
            if (s != null) {
                slotLabels[i].text = s.stationName
                slotLabels[i].setTextColor(primary)
                slotModes[i].text = "${s.filter.label} · ${s.walkMinutes}m"
            } else {
                slotLabels[i].text = "+ Add stop"
                slotLabels[i].setTextColor(secondary)
                slotModes[i].text = "Optional"
            }
        }

        val activeSlot = slots[editingIndex]
        val activeFilter = activeSlot?.filter ?: ModeFilter.AUTO
        filterChips.forEach { (mode, chip) ->
            val selected = mode == activeFilter && activeSlot != null
            chip.setBackgroundResource(if (selected) R.drawable.chip_active else R.drawable.chip_idle)
            chip.setTextColor(if (selected) dark else primary)
            chip.alpha = if (activeSlot != null) 1f else 0.45f
        }

        walkBar.alpha = if (activeSlot != null) 1f else 0.45f
        walkValueText.text = "${activeSlot?.walkMinutes ?: 3} min"

        clearSlotBtn.visibility = if (editingIndex > 0 && activeSlot != null) View.VISIBLE else View.GONE
        searchInput.hint = "Search stop for Stop ${editingIndex + 1} (e.g. Lappkärrsberget, Odenplan…)"
        adapter.notifyDataSetChanged()
    }

    private fun filterList(query: String) {
        val q = query.trim().lowercase()
        displayedSites = if (q.isEmpty()) {
            SlApiClient.POPULAR_STATIONS
        } else {
            val starts = allSites.filter { it.name.lowercase().startsWith(q) }
            val contains = allSites.filter { !it.name.lowercase().startsWith(q) && it.name.lowercase().contains(q) }
            (starts + contains).take(60)
        }
        adapter.notifyDataSetChanged()
    }

    private inner class StationAdapter : BaseAdapter() {
        override fun getCount(): Int = displayedSites.size
        override fun getItem(position: Int): StationOption = displayedSites[position]
        override fun getItemId(position: Int): Long = displayedSites[position].siteId.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView ?: LayoutInflater.from(this@WidgetConfigActivity)
                .inflate(R.layout.item_station, parent, false)
            val item = displayedSites[position]
            v.findViewById<TextView>(R.id.item_station_name).text = item.name
            v.findViewById<TextView>(R.id.item_station_meta).text =
                item.subtitle.ifEmpty { "SL Site #${item.siteId}" }
            v.findViewById<TextView>(R.id.item_station_action).text = "STOP ${editingIndex + 1} →"
            return v
        }
    }
}
