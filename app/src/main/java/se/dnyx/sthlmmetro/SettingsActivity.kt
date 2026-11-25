package se.dnyx.sthlmmetro

import android.app.Dialog
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.BaseAdapter
import android.widget.EditText
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

class SettingsActivity : AppCompatActivity() {

    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private val slots = arrayOfNulls<StationSlot>(3)
    private var opacityPercent = 85
    private var themeChoice = WidgetTheme.AUTO

    private var allSites: List<StationOption> = SlApiClient.POPULAR_STATIONS
    private val executor = Executors.newSingleThreadExecutor()

    private var versionTapCount = 0
    private var lastVersionTapMs = 0L

    private lateinit var slotNames: List<TextView>
    private lateinit var slotMetas: List<TextView>
    private lateinit var slotActions: List<TextView?>
    private lateinit var themeChips: Map<WidgetTheme, TextView>
    private lateinit var opacitySeek: SeekBar
    private lateinit var opacityValue: TextView
    private lateinit var versionLabel: TextView
    private lateinit var updateBadge: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AnalyticsHelper.syncCollectionState(this)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_settings)

        val insetsController = WindowInsetsControllerCompat(window, window.decorView)
        insetsController.isAppearanceLightStatusBars = false
        insetsController.isAppearanceLightNavigationBars = false

        val root = findViewById<View>(R.id.settings_root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, windowInsets ->
            val insets = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.setPadding(insets.left, insets.top, insets.right, insets.bottom)
            windowInsets
        }

        appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            setResult(RESULT_CANCELED)
        }

        val saved = WidgetPrefs.loadSharedSlots(this)
        for (i in saved.indices.take(3)) {
            slots[i] = saved[i]
        }
        if (slots[0] == null) {
            slots[0] = StationSlot(WidgetPrefs.DEFAULT_SITE_ID, WidgetPrefs.DEFAULT_STATION_NAME, ModeFilter.AUTO, 3)
        }
        opacityPercent = WidgetPrefs.loadSharedOpacity(this)
        themeChoice = WidgetPrefs.loadSharedTheme(this)

        slotNames = listOf(
            findViewById(R.id.set_slot_name_0),
            findViewById(R.id.set_slot_name_1),
            findViewById(R.id.set_slot_name_2)
        )
        slotMetas = listOf(
            findViewById(R.id.set_slot_meta_0),
            findViewById(R.id.set_slot_meta_1),
            findViewById(R.id.set_slot_meta_2)
        )
        slotActions = listOf(
            null,
            findViewById(R.id.set_slot_action_1),
            findViewById(R.id.set_slot_action_2)
        )
        themeChips = mapOf(
            WidgetTheme.AUTO to findViewById(R.id.theme_auto),
            WidgetTheme.DARK to findViewById(R.id.theme_dark),
            WidgetTheme.LIGHT to findViewById(R.id.theme_light)
        )
        opacitySeek = findViewById(R.id.set_opacity_seek)
        opacityValue = findViewById(R.id.set_opacity_value)
        versionLabel = findViewById(R.id.set_version_label)
        updateBadge = findViewById(R.id.set_update_badge)

        findViewById<View>(R.id.settings_back).setOnClickListener { saveAndClose() }
        findViewById<View>(R.id.btn_settings_done).setOnClickListener { saveAndClose() }

        findViewById<View>(R.id.set_slot_0).setOnClickListener { openStationPicker(0) }
        findViewById<View>(R.id.set_slot_1).setOnClickListener { openStationPicker(1) }
        findViewById<View>(R.id.set_slot_2).setOnClickListener { openStationPicker(2) }

        themeChips.forEach { (theme, chip) ->
            chip.setOnClickListener {
                themeChoice = theme
                renderThemeChips()
            }
        }

        opacitySeek.progress = opacityPercent
        opacityValue.text = "$opacityPercent%"
        opacitySeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                opacityPercent = progress.coerceIn(0, 100)
                opacityValue.text = "$opacityPercent%"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        findViewById<View>(R.id.btn_pin_board).setOnClickListener {
            saveCurrentSettings()
            pinWidget(MetroWidgetProvider::class.java, "board")
        }
        findViewById<View>(R.id.btn_pin_mini).setOnClickListener {
            saveCurrentSettings()
            pinWidget(MiniWidgetProvider::class.java, "mini")
        }

        findViewById<View>(R.id.row_check_updates).setOnClickListener {
            AppUpdater.showUpdateDialog(this)
        }

        // Hidden Tester Mode: tap version text 5 times to exclude your own device from GA4/Firebase
        versionLabel.setOnClickListener {
            val now = System.currentTimeMillis()
            if (now - lastVersionTapMs > 1500L) versionTapCount = 0
            lastVersionTapMs = now
            versionTapCount++
            if (versionTapCount >= 5) {
                versionTapCount = 0
                val excluded = AnalyticsHelper.toggleDevExcluded(this)
                renderVersionLabel()
                val msg = if (excluded) "Tester Mode ON (GA4 paused)" else "Tester Mode OFF (GA4 active)"
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            }
        }

        renderSlots()
        renderThemeChips()
        renderVersionLabel()

        AppUpdater.checkInBackground { info ->
            if (info?.isNewer == true) {
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        updateBadge.text = "⬇ NEW (${info.tagName})"
                    }
                }
            }
        }

        // Load full Stockholm SL stops list (7-day offline cache first)
        executor.execute {
            val cached = WidgetPrefs.loadFreshSitesCache(this)
            val remote = cached ?: try {
                val fresh = SlApiClient.fetchAllSites()
                if (fresh.isNotEmpty()) WidgetPrefs.saveSitesCache(this, fresh)
                fresh
            } catch (_: Exception) {
                emptyList()
            }
            if (remote.isNotEmpty()) {
                val popIds = SlApiClient.POPULAR_STATIONS.map { it.siteId }.toSet()
                val merged = SlApiClient.POPULAR_STATIONS +
                    remote.filter { it.siteId !in popIds }.sortedBy { it.name.lowercase() }
                runOnUiThread { allSites = merged }
            }
        }
    }

    private fun pinWidget(providerClass: Class<*>, styleName: String) {
        val manager = AppWidgetManager.getInstance(this)
        val provider = ComponentName(this, providerClass)
        if (manager.isRequestPinAppWidgetSupported) {
            AnalyticsHelper.logWidgetPinned(this, styleName)
            manager.requestPinAppWidget(provider, null, null)
        } else {
            Toast.makeText(
                this,
                "Long-press your Home Screen → Widgets → T-Radar to add it.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun openStationPicker(slotIdx: Int) {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_station_picker, null)
        dialog.setContentView(view)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.92).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        var chosenFilter = slots[slotIdx]?.filter ?: ModeFilter.AUTO
        var chosenWalk = slots[slotIdx]?.walkMinutes ?: 3
        var filteredSites: List<StationOption> = SlApiClient.POPULAR_STATIONS

        view.findViewById<TextView>(R.id.picker_title).text = "Stop ${slotIdx + 1}"
        view.findViewById<View>(R.id.picker_close).setOnClickListener { dialog.dismiss() }

        val removeBtn = view.findViewById<TextView>(R.id.picker_remove)
        if (slotIdx > 0 && slots[slotIdx] != null) {
            removeBtn.visibility = View.VISIBLE
            removeBtn.setOnClickListener {
                slots[slotIdx] = null
                renderSlots()
                dialog.dismiss()
            }
        }

        val chips = mapOf(
            ModeFilter.AUTO to view.findViewById<TextView>(R.id.pk_auto),
            ModeFilter.BUS to view.findViewById<TextView>(R.id.pk_bus),
            ModeFilter.PENDEL to view.findViewById<TextView>(R.id.pk_pendel),
            ModeFilter.ALL to view.findViewById<TextView>(R.id.pk_all)
        )
        val dark = ContextCompat.getColor(this, R.color.bg_dark)
        val primary = ContextCompat.getColor(this, R.color.text_primary)

        fun renderChips() {
            chips.forEach { (m, c) ->
                val sel = m == chosenFilter
                c.setBackgroundResource(if (sel) R.drawable.chip_active else R.drawable.chip_idle)
                c.setTextColor(if (sel) dark else primary)
            }
        }
        chips.forEach { (m, c) ->
            c.setOnClickListener {
                chosenFilter = m
                slots[slotIdx]?.let { slots[slotIdx] = it.copy(filter = m); renderSlots() }
                renderChips()
            }
        }
        renderChips()

        val walkVal = view.findViewById<TextView>(R.id.pk_walk_val)
        walkVal.text = "$chosenWalk min"
        view.findViewById<View>(R.id.pk_walk_minus).setOnClickListener {
            if (chosenWalk > 0) {
                chosenWalk--
                walkVal.text = "$chosenWalk min"
                slots[slotIdx]?.let { slots[slotIdx] = it.copy(walkMinutes = chosenWalk); renderSlots() }
            }
        }
        view.findViewById<View>(R.id.pk_walk_plus).setOnClickListener {
            if (chosenWalk < 30) {
                chosenWalk++
                walkVal.text = "$chosenWalk min"
                slots[slotIdx]?.let { slots[slotIdx] = it.copy(walkMinutes = chosenWalk); renderSlots() }
            }
        }

        val listView = view.findViewById<ListView>(R.id.picker_list)
        val listAdapter = object : BaseAdapter() {
            override fun getCount(): Int = filteredSites.size
            override fun getItem(position: Int): StationOption = filteredSites[position]
            override fun getItemId(position: Int): Long = filteredSites[position].siteId.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val row = convertView ?: LayoutInflater.from(this@SettingsActivity)
                    .inflate(R.layout.item_station, parent, false)
                val item = filteredSites[position]
                row.findViewById<TextView>(R.id.item_station_name).text = item.name
                row.findViewById<TextView>(R.id.item_station_meta).text = item.subtitle.ifEmpty { "SL #${item.siteId}" }
                return row
            }
        }
        listView.adapter = listAdapter

        listView.setOnItemClickListener { _, _, pos, _ ->
            val picked = filteredSites[pos]
            slots[slotIdx] = StationSlot(picked.siteId, picked.name, chosenFilter, chosenWalk)
            renderSlots()
            dialog.dismiss()
        }

        val searchBox = view.findViewById<EditText>(R.id.picker_search)
        searchBox.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val q = s?.toString().orEmpty().trim().lowercase()
                filteredSites = if (q.isEmpty()) {
                    SlApiClient.POPULAR_STATIONS
                } else {
                    val starts = allSites.filter { it.name.lowercase().startsWith(q) }
                    val contains = allSites.filter { !it.name.lowercase().startsWith(q) && it.name.lowercase().contains(q) }
                    (starts + contains).take(60)
                }
                listAdapter.notifyDataSetChanged()
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })

        dialog.show()
    }

    private fun renderSlots() {
        val primary = ContextCompat.getColor(this, R.color.text_primary)
        val secondary = ContextCompat.getColor(this, R.color.text_secondary)
        for (i in 0 until 3) {
            val s = slots[i]
            if (s != null) {
                slotNames[i].text = s.stationName
                slotNames[i].setTextColor(primary)
                slotMetas[i].text = "${s.filter.label} · ${s.walkMinutes}m walk"
                slotActions[i]?.text = "EDIT"
            } else {
                slotNames[i].text = "+ Add stop ${i + 1}"
                slotNames[i].setTextColor(secondary)
                slotMetas[i].text = "Optional"
                slotActions[i]?.text = "ADD"
            }
        }
    }

    private fun renderThemeChips() {
        val dark = ContextCompat.getColor(this, R.color.bg_dark)
        val primary = ContextCompat.getColor(this, R.color.text_primary)
        themeChips.forEach { (t, chip) ->
            val sel = t == themeChoice
            chip.setBackgroundResource(if (sel) R.drawable.chip_active else R.drawable.chip_idle)
            chip.setTextColor(if (sel) dark else primary)
        }
    }

    private fun renderVersionLabel() {
        versionLabel.text = if (AnalyticsHelper.isDevExcluded(this)) {
            "T-Radar v${BuildConfig.VERSION_NAME} · 🧪 TESTER"
        } else {
            "T-Radar v${BuildConfig.VERSION_NAME}"
        }
    }

    private fun saveCurrentSettings(): List<StationSlot> {
        val clean = slots.filterNotNull().ifEmpty {
            listOf(StationSlot(WidgetPrefs.DEFAULT_SITE_ID, WidgetPrefs.DEFAULT_STATION_NAME, ModeFilter.AUTO, 3))
        }
        WidgetPrefs.saveSharedSettings(this, clean, opacityPercent, themeChoice)
        AnalyticsHelper.logWidgetConfigured(this, clean, opacityPercent, themeChoice)
        return clean
    }

    private fun saveAndClose() {
        saveCurrentSettings()
        if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            WidgetPrefs.clearNearbyOverride(this, appWidgetId)
            MetroWidgetProvider.triggerAsyncRefresh(this, appWidgetId, trigger = "config_save")
            setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))
        } else {
            MetroWidgetProvider.refreshAllWidgets(this, trigger = "settings_save")
        }
        finish()
    }
}
