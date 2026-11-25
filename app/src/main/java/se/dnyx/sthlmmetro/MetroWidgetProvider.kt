package se.dnyx.sthlmmetro

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews
import android.widget.Toast
import androidx.core.content.ContextCompat
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors

open class MetroWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        AnalyticsHelper.syncCollectionState(context)
        val pendingResult = goAsync()
        executor.execute {
            try {
                for (widgetId in appWidgetIds) {
                    val cfg = WidgetPrefs.load(context, widgetId)
                    val eff = cfg.effectiveSlot
                    val cached = WidgetPrefs.loadCache(context, "w_$widgetId")
                    val cacheMatches = cached != null &&
                        cached.siteId == eff.siteId &&
                        cached.filterCode == eff.filter.code
                    if (cacheMatches) {
                        renderFromCache(context, appWidgetManager, widgetId, cfg, cached!!, logEmptyCat = false)
                    } else if (eff.siteId > 0) {
                        refreshWidgetBlocking(context, appWidgetManager, widgetId, trigger = "system", isFinalAttempt = true)
                    } else {
                        showWidgetStatus(context, appWidgetManager, widgetId, "Tap 📍 Near me", "Tap 📍 Near me or ↻ to locate")
                    }
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        val cfg = WidgetPrefs.load(context, appWidgetId)
        val eff = cfg.effectiveSlot
        val cached = WidgetPrefs.loadCache(context, "w_$appWidgetId")
        if (cached != null &&
            cached.siteId == eff.siteId &&
            cached.filterCode == eff.filter.code
        ) {
            renderFromCache(context, appWidgetManager, appWidgetId, cfg, cached, logEmptyCat = false)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val action = intent.action ?: return
        if (action != ACTION_REFRESH &&
            action != ACTION_CAT_POKE &&
            action != ACTION_SELECT_SLOT &&
            action != ACTION_TOGGLE_REMINDER &&
            action != ACTION_LOCAL_TICK
        ) return

        AnalyticsHelper.syncCollectionState(context)

        val widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        val appWidgetManager = AppWidgetManager.getInstance(context)

        // Silent 1-tap 🔔 Reminder Toggle right inside the widget (NO app opens!)
        if (action == ACTION_TOGGLE_REMINDER) {
            handleSilentReminderToggle(context, appWidgetManager, intent)
            return
        }

        val ids = if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            intArrayOf(widgetId)
        } else {
            allWidgetIds(context, appWidgetManager)
        }
        if (ids.isEmpty()) return

        if (action == ACTION_LOCAL_TICK) {
            for (id in ids) {
                val cfg = WidgetPrefs.load(context, id)
                val eff = cfg.effectiveSlot
                val cached = WidgetPrefs.loadCache(context, "w_$id")
                if (cached != null &&
                    cached.siteId == eff.siteId &&
                    cached.filterCode == eff.filter.code
                ) {
                    renderFromCache(context, appWidgetManager, id, cfg, cached, logEmptyCat = true)
                }
            }
            return
        }

        if (action == ACTION_SELECT_SLOT && widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            val slotIndex = intent.getIntExtra(EXTRA_SLOT_INDEX, 0)
            WidgetPrefs.setActiveSlot(context, widgetId, slotIndex)
            val updatedCfg = WidgetPrefs.load(context, widgetId)
            AnalyticsHelper.logWidgetTabSwitched(context, updatedCfg.effectiveSlot, updatedCfg.activeSlotIndex)
        }

        val nowMs = System.currentTimeMillis()
        val triggerLabel = when (action) {
            ACTION_CAT_POKE -> "cat"
            ACTION_SELECT_SLOT -> "tab"
            else -> "button"
        }

        for (id in ids) {
            val cfg = WidgetPrefs.load(context, id)

            // If active slot is 📍 Near me -> run SILENT in-widget GPS refresh (only prompt if permission missing!)
            if (cfg.isActiveNearMe) {
                if (LocationHelper.hasLocationPermission(context)) {
                    showWidgetStatus(context, appWidgetManager, id, "Locating…", "Finding closest SL stop…")
                    val svcIntent = Intent(context, WidgetLocationService::class.java).apply {
                        putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                    }
                    try {
                        ContextCompat.startForegroundService(context, svcIntent)
                    } catch (_: Exception) {
                        launchLocationPermissionPopup(context, id)
                    }
                } else {
                    launchLocationPermissionPopup(context, id)
                }
                continue
            }

            val eff = cfg.effectiveSlot
            val cached = WidgetPrefs.loadCache(context, "w_$id")
            val cacheMatches = cached != null &&
                cached.siteId == eff.siteId &&
                cached.filterCode == eff.filter.code

            if (action == ACTION_CAT_POKE) {
                AnalyticsHelper.logWidgetCatPoked(context, eff)
            }

            if ((action == ACTION_REFRESH || action == ACTION_CAT_POKE) &&
                cacheMatches &&
                (nowMs - cached!!.fetchedAtMs) < REFRESH_COOLDOWN_MS
            ) {
                renderFromCache(context, appWidgetManager, id, cfg, cached, logEmptyCat = false)
                continue
            }

            showWidgetStatus(context, appWidgetManager, id, "Refreshing SL…", "Loading ${eff.stationName}…")
            WidgetRefreshWorker.enqueue(context, id, triggerLabel)
        }
    }

    private fun handleSilentReminderToggle(context: Context, manager: AppWidgetManager, intent: Intent) {
        val siteId = intent.getIntExtra(EXTRA_REM_SITE_ID, -1)
        val stationName = intent.getStringExtra(EXTRA_REM_STATION).orEmpty()
        val designation = intent.getStringExtra(EXTRA_REM_LINE).orEmpty()
        val destination = intent.getStringExtra(EXTRA_REM_DEST).orEmpty()
        val expectedMs = intent.getLongExtra(EXTRA_REM_EXPECTED_MS, 0L)
        val walkMin = intent.getIntExtra(EXTRA_REM_WALK_MIN, 3)

        if (siteId <= 0 || expectedMs <= 0L) return

        AnalyticsHelper.logDepartureTapped(context, stationName, designation, destination, walkMin)

        val result = ReminderManager.toggleReminder(
            context = context,
            siteId = siteId,
            stationName = stationName,
            designation = designation,
            destination = destination,
            expectedEpochMs = expectedMs,
            walkMinutes = walkMin
        )

        val toastMsg = when (result) {
            is ReminderToggleResult.Scheduled ->
                "🔔 ${result.ringTimeStr} · $designation → $destination"
            is ReminderToggleResult.Cancelled ->
                "🔕 Cancelled"
            is ReminderToggleResult.TooClose -> {
                val mins = result.remainingSeconds / 60
                val secs = result.remainingSeconds % 60
                val timeStr = if (mins > 0) "${mins}m ${secs}s" else "${secs}s"
                "🏃 Leaves in $timeStr"
            }
            is ReminderToggleResult.Departed ->
                "Departed · tap ↻"
        }

        mainHandler.post {
            Toast.makeText(context.applicationContext, toastMsg, Toast.LENGTH_SHORT).show()
        }

        // Re-render widgets immediately so 🔔 HH:mm appears/clears right inside the row!
        rerenderAllFromCache(context, manager)
    }

    private fun launchLocationPermissionPopup(context: Context, appWidgetId: Int) {
        val intent = Intent(context, WidgetLocationActivity::class.java).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        try {
            context.startActivity(intent)
        } catch (_: Exception) {
            // Ignore
        }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        for (id in appWidgetIds) {
            cancelLocalRollover(context, id)
            WidgetPrefs.deleteWidgetState(context, id)
        }
    }

    enum class RefreshOutcome {
        DONE,
        RETRYABLE_NETWORK
    }

    private enum class LayoutType {
        MINI, COMPACT, BOARD
    }

    private data class DualColor(val light: Int, val dark: Int) {
        companion object {
            fun fixed(color: Int) = DualColor(color, color)
        }
    }

    private data class WidgetPalette(
        val bgRes: Int,
        val rowRes: Int,
        val tabActiveRes: Int,
        val tabIdleRes: Int,
        val btnRes: Int,
        val textPrimary: DualColor,
        val textSecondary: DualColor,
        val tabActiveText: DualColor,
        val accent: DualColor,
        val warn: DualColor,
        val cancel: DualColor
    )

    companion object {
        const val ACTION_REFRESH = "se.dnyx.sthlmmetro.ACTION_REFRESH_WIDGET"
        const val ACTION_CAT_POKE = "se.dnyx.sthlmmetro.ACTION_CAT_POKE"
        const val ACTION_SELECT_SLOT = "se.dnyx.sthlmmetro.ACTION_SELECT_SLOT"
        const val ACTION_TOGGLE_REMINDER = "se.dnyx.sthlmmetro.ACTION_TOGGLE_REMINDER"
        const val ACTION_LOCAL_TICK = "se.dnyx.sthlmmetro.ACTION_LOCAL_TICK"
        const val EXTRA_SLOT_INDEX = "slot_index"

        private const val EXTRA_REM_SITE_ID = "rem_site_id"
        private const val EXTRA_REM_STATION = "rem_station"
        private const val EXTRA_REM_LINE = "rem_line"
        private const val EXTRA_REM_DEST = "rem_dest"
        private const val EXTRA_REM_EXPECTED_MS = "rem_expected_ms"
        private const val EXTRA_REM_WALK_MIN = "rem_walk_min"

        private const val REFRESH_COOLDOWN_MS = 5_000L

        private val executor = Executors.newSingleThreadExecutor()
        private val mainHandler = Handler(Looper.getMainLooper())
        private val timeFmt = DateTimeFormatter.ofPattern("HH:mm")

        private val TAB_IDS = intArrayOf(R.id.widget_tab_0, R.id.widget_tab_1, R.id.widget_tab_2)
        private val ROW_IDS = intArrayOf(R.id.row_0, R.id.row_1, R.id.row_2, R.id.row_3)
        private val ROW_BG_IDS = intArrayOf(R.id.row_0_bg, R.id.row_1_bg, R.id.row_2_bg, R.id.row_3_bg)
        private val BADGE_IDS = intArrayOf(R.id.row_0_badge, R.id.row_1_badge, R.id.row_2_badge, R.id.row_3_badge)
        private val DEST_IDS = intArrayOf(R.id.row_0_dest, R.id.row_1_dest, R.id.row_2_dest, R.id.row_3_dest)
        private val SUB_IDS = intArrayOf(R.id.row_0_sub, R.id.row_1_sub, R.id.row_2_sub, R.id.row_3_sub)
        private val CHRONO_IDS = intArrayOf(R.id.row_0_chrono, R.id.row_1_chrono, R.id.row_2_chrono, R.id.row_3_chrono)
        private val ETA_IDS = intArrayOf(R.id.row_0_eta, R.id.row_1_eta, R.id.row_2_eta, R.id.row_3_eta)

        fun allWidgetIds(context: Context, manager: AppWidgetManager = AppWidgetManager.getInstance(context)): IntArray {
            val boardIds = manager.getAppWidgetIds(ComponentName(context, MetroWidgetProvider::class.java))
            val miniIds = manager.getAppWidgetIds(ComponentName(context, MiniWidgetProvider::class.java))
            return (boardIds.toList() + miniIds.toList()).distinct().toIntArray()
        }

        fun rerenderAllFromCache(context: Context, manager: AppWidgetManager = AppWidgetManager.getInstance(context)) {
            for (id in allWidgetIds(context, manager)) {
                val cfg = WidgetPrefs.load(context, id)
                val eff = cfg.effectiveSlot
                val cached = WidgetPrefs.loadCache(context, "w_$id")
                if (cached != null &&
                    cached.siteId == eff.siteId &&
                    cached.filterCode == eff.filter.code
                ) {
                    renderFromCache(context, manager, id, cfg, cached, logEmptyCat = false)
                }
            }
        }

        fun refreshAllWidgets(context: Context, trigger: String = "settings_save") {
            val manager = AppWidgetManager.getInstance(context)
            for (id in allWidgetIds(context, manager)) {
                val cfg = WidgetPrefs.load(context, id)
                if (cfg.isActiveNearMe && LocationHelper.hasLocationPermission(context)) {
                    try {
                        ContextCompat.startForegroundService(
                            context,
                            Intent(context, WidgetLocationService::class.java).putExtra(
                                AppWidgetManager.EXTRA_APPWIDGET_ID,
                                id
                            )
                        )
                        continue
                    } catch (_: Exception) {
                        // Fall through
                    }
                }
                WidgetRefreshWorker.enqueue(context, id, trigger)
            }
        }

        fun triggerAsyncRefresh(context: Context, appWidgetId: Int, trigger: String = "config_save") {
            WidgetRefreshWorker.enqueue(context, appWidgetId, trigger)
        }

        fun showWidgetStatus(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int,
            headerNote: String,
            bodyNote: String
        ) {
            val cfg = WidgetPrefs.load(context, appWidgetId)
            val views = buildResponsiveViews(context, appWidgetManager, appWidgetId, cfg) { layoutType ->
                buildStatusLayout(context, appWidgetId, cfg, layoutType, headerNote, bodyNote)
            }
            appWidgetManager.updateAppWidget(appWidgetId, views)
        }

        fun refreshWidgetBlocking(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int,
            trigger: String = "button",
            isFinalAttempt: Boolean = true
        ): RefreshOutcome {
            val cfg = WidgetPrefs.load(context, appWidgetId)
            val eff = cfg.effectiveSlot
            if (eff.siteId <= 0) {
                showWidgetStatus(context, appWidgetManager, appWidgetId, "Tap 📍 Near me", "Tap 📍 Near me to locate")
                return RefreshOutcome.DONE
            }

            AnalyticsHelper.logWidgetRefreshed(context, eff, cfg.activeSlotIndex, trigger)

            try {
                val fetched = try {
                    SlApiClient.fetchDepartures(eff.siteId, eff.filter, limit = 20)
                } catch (_: IOException) {
                    Thread.sleep(1200L)
                    SlApiClient.fetchDepartures(eff.siteId, eff.filter, limit = 20)
                }
                val fetchedAtMs = System.currentTimeMillis()
                WidgetPrefs.saveCache(
                    context = context,
                    cacheKey = "w_$appWidgetId",
                    siteId = eff.siteId,
                    filter = eff.filter,
                    fetchedAtMs = fetchedAtMs,
                    items = fetched
                )
                val cache = CachedSlotDepartures(eff.siteId, eff.filter.code, fetchedAtMs, fetched)
                renderFromCache(context, appWidgetManager, appWidgetId, cfg, cache, logEmptyCat = true)
                return RefreshOutcome.DONE
            } catch (_: SlRateLimitException) {
                AnalyticsHelper.logSlError(context, eff, "429_rate_limit")
                renderFailure(context, appWidgetManager, appWidgetId, cfg, "SL 429 limit", "SL rate limit (429). Tap ↻.")
                return RefreshOutcome.DONE
            } catch (e: SlHttpException) {
                AnalyticsHelper.logSlError(context, eff, "http_${e.statusCode}")
                renderFailure(context, appWidgetManager, appWidgetId, cfg, "SL HTTP ${e.statusCode}", "SL HTTP ${e.statusCode}. Tap ↻.")
                return RefreshOutcome.DONE
            } catch (e: IOException) {
                if (!isFinalAttempt) return RefreshOutcome.RETRYABLE_NETWORK
                val tag = when (e) {
                    is UnknownHostException -> "dns"
                    is SocketTimeoutException -> "timeout"
                    else -> "io"
                }
                AnalyticsHelper.logSlError(context, eff, "network_$tag")
                val shortMsg = if (e is SocketTimeoutException) "Timed out" else "Offline"
                renderFailure(context, appWidgetManager, appWidgetId, cfg, "$shortMsg · Tap ↻", "$shortMsg · Tap ↻")
                return RefreshOutcome.DONE
            } catch (_: Exception) {
                AnalyticsHelper.logSlError(context, eff, "unknown")
                renderFailure(context, appWidgetManager, appWidgetId, cfg, "Offline · Tap ↻", "Offline · Tap ↻")
                return RefreshOutcome.DONE
            }
        }

        private fun renderFailure(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int,
            cfg: WidgetConfig,
            headerNote: String,
            bodyNote: String
        ) {
            val eff = cfg.effectiveSlot
            val cached = WidgetPrefs.loadCache(context, "w_$appWidgetId")
            val nowMs = System.currentTimeMillis()
            val hasLiveCache = cached != null &&
                cached.siteId == eff.siteId &&
                cached.filterCode == eff.filter.code &&
                cached.items.any { it.expectedEpochMs <= 0L || it.expectedEpochMs > nowMs - 10_000L }

            if (hasLiveCache) {
                renderFromCache(context, appWidgetManager, appWidgetId, cfg, cached!!, logEmptyCat = false, overrideSubtitle = headerNote)
                return
            }

            val views = buildResponsiveViews(context, appWidgetManager, appWidgetId, cfg) { layoutType ->
                buildStatusLayout(context, appWidgetId, cfg, layoutType, headerNote, bodyNote)
            }
            cancelLocalRollover(context, appWidgetId)
            appWidgetManager.updateAppWidget(appWidgetId, views)
        }

        private fun renderFromCache(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int,
            cfg: WidgetConfig,
            cache: CachedSlotDepartures,
            logEmptyCat: Boolean,
            overrideSubtitle: String? = null
        ) {
            val eff = cfg.effectiveSlot
            val nowMs = System.currentTimeMillis()
            val syncClock = Instant.ofEpochMilli(cache.fetchedAtMs)
                .atZone(ZoneId.systemDefault())
                .toLocalTime()
                .format(timeFmt)

            val upcoming = cache.items.filter { d ->
                d.expectedEpochMs <= 0L || d.expectedEpochMs > nowMs - 10_000L
            }

            if (upcoming.isEmpty()) {
                WidgetPrefs.clearCache(context, "w_$appWidgetId")
                if (logEmptyCat) {
                    AnalyticsHelper.logWidgetCatShown(context, eff)
                }
                val views = buildResponsiveViews(context, appWidgetManager, appWidgetId, cfg) { layoutType ->
                    buildEmptyCatLayout(context, appWidgetId, cfg, layoutType, overrideSubtitle ?: syncClock)
                }
                cancelLocalRollover(context, appWidgetId)
                appWidgetManager.updateAppWidget(appWidgetId, views)
                return
            }

            val remindersMap = WidgetPrefs.loadActiveReminders(context).associateBy { it.key }
            val subtitle = overrideSubtitle ?: buildHeaderMeta(cfg, syncClock)
            val views = buildResponsiveViews(context, appWidgetManager, appWidgetId, cfg) { layoutType ->
                buildDeparturesLayout(context, appWidgetId, cfg, layoutType, subtitle, upcoming, remindersMap, nowMs)
            }

            val nextRolloverMs = upcoming.take(4)
                .mapNotNull { if (it.expectedEpochMs > 0L) it.expectedEpochMs + 8_000L else null }
                .minOrNull()

            if (nextRolloverMs != null) {
                scheduleLocalRollover(context, appWidgetId, maxOf(nowMs + 15_000L, nextRolloverMs))
            } else {
                cancelLocalRollover(context, appWidgetId)
            }

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }

        private fun buildHeaderMeta(cfg: WidgetConfig, syncClock: String): String {
            return if (cfg.isActiveNearMe && cfg.resolvedNearMe != null) {
                "📍 ${cfg.resolvedNearMe.walkMinutes} min walk · $syncClock"
            } else {
                syncClock
            }
        }

        private fun buildResponsiveViews(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int,
            cfg: WidgetConfig,
            builder: (LayoutType) -> RemoteViews
        ): RemoteViews {
            val info = appWidgetManager.getAppWidgetInfo(appWidgetId)
            val isDedicatedMini = info?.provider?.className == MiniWidgetProvider::class.java.name
            if (isDedicatedMini) {
                return builder(LayoutType.MINI)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val sizeMap = mapOf(
                    SizeF(140f, 36f) to builder(LayoutType.MINI),
                    SizeF(140f, 78f) to builder(LayoutType.COMPACT),
                    SizeF(140f, 162f) to builder(LayoutType.BOARD)
                )
                return RemoteViews(sizeMap)
            }

            val opts = appWidgetManager.getAppWidgetOptions(appWidgetId)
            val minH = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 140)
            val chosen = when {
                minH < 78 -> LayoutType.MINI
                minH < 162 -> LayoutType.COMPACT
                else -> LayoutType.BOARD
            }
            return builder(chosen)
        }

        private fun applyTextColor(context: Context, views: RemoteViews, viewId: Int, color: DualColor) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                views.setColorInt(viewId, "setTextColor", color.light, color.dark)
            } else {
                val isNight = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                    Configuration.UI_MODE_NIGHT_YES
                views.setTextColor(viewId, if (isNight) color.dark else color.light)
            }
        }

        private fun resolvePalette(context: Context, theme: WidgetTheme): WidgetPalette {
            return when (theme) {
                WidgetTheme.DARK -> WidgetPalette(
                    bgRes = R.drawable.widget_bg_dark,
                    rowRes = R.drawable.widget_row_dark,
                    tabActiveRes = R.drawable.widget_tab_active_dark,
                    tabIdleRes = R.drawable.widget_tab_idle_dark,
                    btnRes = R.drawable.widget_btn_dark,
                    textPrimary = DualColor.fixed(ContextCompat.getColor(context, R.color.w_dark_text_primary)),
                    textSecondary = DualColor.fixed(ContextCompat.getColor(context, R.color.w_dark_text_secondary)),
                    tabActiveText = DualColor.fixed(ContextCompat.getColor(context, R.color.w_dark_tab_active_text)),
                    accent = DualColor.fixed(ContextCompat.getColor(context, R.color.w_dark_accent)),
                    warn = DualColor.fixed(ContextCompat.getColor(context, R.color.widget_warn)),
                    cancel = DualColor.fixed(ContextCompat.getColor(context, R.color.widget_cancel))
                )
                WidgetTheme.LIGHT -> WidgetPalette(
                    bgRes = R.drawable.widget_bg_light,
                    rowRes = R.drawable.widget_row_light,
                    tabActiveRes = R.drawable.widget_tab_active_light,
                    tabIdleRes = R.drawable.widget_tab_idle_light,
                    btnRes = R.drawable.widget_btn_light,
                    textPrimary = DualColor.fixed(ContextCompat.getColor(context, R.color.w_light_text_primary)),
                    textSecondary = DualColor.fixed(ContextCompat.getColor(context, R.color.w_light_text_secondary)),
                    tabActiveText = DualColor.fixed(ContextCompat.getColor(context, R.color.w_light_tab_active_text)),
                    accent = DualColor.fixed(ContextCompat.getColor(context, R.color.w_light_accent)),
                    warn = DualColor.fixed(ContextCompat.getColor(context, R.color.widget_warn)),
                    cancel = DualColor.fixed(ContextCompat.getColor(context, R.color.widget_cancel))
                )
                WidgetTheme.AUTO -> {
                    val baseCfg = context.resources.configuration
                    val lightCtx = context.createConfigurationContext(
                        Configuration(baseCfg).apply {
                            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or Configuration.UI_MODE_NIGHT_NO
                        }
                    )
                    val darkCtx = context.createConfigurationContext(
                        Configuration(baseCfg).apply {
                            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or Configuration.UI_MODE_NIGHT_YES
                        }
                    )
                    fun dual(resId: Int) = DualColor(
                        ContextCompat.getColor(lightCtx, resId),
                        ContextCompat.getColor(darkCtx, resId)
                    )
                    WidgetPalette(
                        bgRes = R.drawable.widget_bg_auto,
                        rowRes = R.drawable.widget_row_auto,
                        tabActiveRes = R.drawable.widget_tab_active_auto,
                        tabIdleRes = R.drawable.widget_tab_idle_auto,
                        btnRes = R.drawable.widget_btn_auto,
                        textPrimary = dual(R.color.widget_text_primary),
                        textSecondary = dual(R.color.widget_text_secondary),
                        tabActiveText = dual(R.color.widget_tab_active_text),
                        accent = dual(R.color.widget_accent),
                        warn = dual(R.color.widget_warn),
                        cancel = dual(R.color.widget_cancel)
                    )
                }
            }
        }

        private fun buildStatusLayout(
            context: Context,
            appWidgetId: Int,
            cfg: WidgetConfig,
            layoutType: LayoutType,
            headerNote: String,
            bodyNote: String
        ): RemoteViews {
            val palette = resolvePalette(context, cfg.theme)
            val views = createThemedBase(context, appWidgetId, cfg, layoutType, palette)
            val eff = cfg.effectiveSlot
            if (layoutType == LayoutType.MINI) {
                views.setViewVisibility(R.id.mini_dep_container, View.GONE)
                views.setViewVisibility(R.id.mini_cat_container, View.GONE)
                views.setViewVisibility(R.id.mini_status, View.VISIBLE)
                val shortStop = shortenTabLabel(eff.stationName)
                val statusLabel = if (eff.siteId > 0 && !bodyNote.contains(shortStop, ignoreCase = true)) {
                    "$shortStop · $bodyNote"
                } else {
                    bodyNote
                }
                views.setTextViewText(R.id.mini_status, statusLabel)
                applyTextColor(context, views, R.id.mini_status, palette.textPrimary)
                views.setOnClickPendingIntent(R.id.mini_status, openAppPendingIntent(context, appWidgetId))
                return views
            }

            val maxRows = if (layoutType == LayoutType.COMPACT) 2 else 4
            views.setTextViewText(R.id.widget_updated_at, headerNote)
            views.setViewVisibility(R.id.widget_cat_empty, View.GONE)
            views.setViewVisibility(R.id.widget_status_text, View.VISIBLE)
            views.setTextViewText(R.id.widget_status_text, bodyNote)
            applyTextColor(context, views, R.id.widget_status_text, palette.textSecondary)
            for (i in 0 until maxRows) {
                views.setViewVisibility(ROW_IDS[i], View.GONE)
            }
            return views
        }

        private fun buildEmptyCatLayout(
            context: Context,
            appWidgetId: Int,
            cfg: WidgetConfig,
            layoutType: LayoutType,
            subtitle: String
        ): RemoteViews {
            val palette = resolvePalette(context, cfg.theme)
            val views = createThemedBase(context, appWidgetId, cfg, layoutType, palette)
            val catPi = catPokePendingIntent(context, appWidgetId)
            val eff = cfg.effectiveSlot

            if (layoutType == LayoutType.MINI) {
                views.setViewVisibility(R.id.mini_dep_container, View.GONE)
                views.setViewVisibility(R.id.mini_status, View.GONE)
                views.setViewVisibility(R.id.mini_cat_container, View.VISIBLE)
                views.setTextViewText(R.id.mini_cat_text, "${shortenTabLabel(eff.stationName)} · All left ↻")
                applyTextColor(context, views, R.id.mini_cat_text, palette.textPrimary)
                views.setOnClickPendingIntent(R.id.mini_cat_container, catPi)
                return views
            }

            val maxRows = if (layoutType == LayoutType.COMPACT) 2 else 4
            views.setTextViewText(R.id.widget_updated_at, subtitle)
            views.setViewVisibility(R.id.widget_status_text, View.GONE)
            views.setViewVisibility(R.id.widget_cat_empty, View.VISIBLE)
            views.setTextViewText(R.id.widget_cat_caption, "All departed · tap cat or ↻")
            applyTextColor(context, views, R.id.widget_cat_caption, palette.textPrimary)
            views.setOnClickPendingIntent(R.id.widget_cat_empty, catPi)
            for (i in 0 until maxRows) {
                views.setViewVisibility(ROW_IDS[i], View.GONE)
            }
            return views
        }

        private fun buildDeparturesLayout(
            context: Context,
            appWidgetId: Int,
            cfg: WidgetConfig,
            layoutType: LayoutType,
            subtitle: String,
            upcoming: List<DepartureItem>,
            remindersMap: Map<String, ActiveReminder>,
            nowMs: Long
        ): RemoteViews {
            val palette = resolvePalette(context, cfg.theme)
            val views = createThemedBase(context, appWidgetId, cfg, layoutType, palette)
            val eff = cfg.effectiveSlot
            val elapsedNow = SystemClock.elapsedRealtime()

            if (layoutType == LayoutType.MINI) {
                val d = upcoming.first()
                val remKey = ReminderManager.buildKey(eff.siteId, d.designation, d.destination, d.expectedEpochMs)
                val activeRem = remindersMap[remKey]

                views.setViewVisibility(R.id.mini_status, View.GONE)
                views.setViewVisibility(R.id.mini_cat_container, View.GONE)
                views.setViewVisibility(R.id.mini_dep_container, View.VISIBLE)

                views.setTextViewText(R.id.mini_badge, d.designation)
                views.setInt(R.id.mini_badge, "setBackgroundResource", badgeDrawableFor(d.badgeStyle))
                views.setTextViewText(R.id.mini_dest, d.destination)
                applyTextColor(context, views, R.id.mini_dest, if (d.cancelled) palette.cancel else palette.textPrimary)

                val standStr = d.standOrTrackLabel()
                val stopBase = if (standStr.isNotEmpty()) {
                    "${shortenTabLabel(eff.stationName)} · $standStr"
                } else {
                    shortenTabLabel(eff.stationName)
                }
                val stopLine = when {
                    activeRem != null -> "⏱ ${ReminderManager.formatClock(activeRem.triggerAtMs)} · $stopBase"
                    cfg.isActiveNearMe && cfg.resolvedNearMe != null -> "📍 $stopBase · ${cfg.resolvedNearMe.walkMinutes} min"
                    else -> "📍 $stopBase"
                }
                views.setTextViewText(R.id.mini_stop, stopLine)
                applyTextColor(context, views, R.id.mini_stop, palette.accent)

                val remainingMs = if (d.expectedEpochMs > 0L) d.expectedEpochMs - nowMs else -1L
                val etaColor = when {
                    d.cancelled -> palette.cancel
                    d.delayMinutes > 0 -> palette.warn
                    else -> palette.accent
                }

                if (!d.cancelled && remainingMs in 6_000L..3_599_000L) {
                    views.setViewVisibility(R.id.mini_chrono, View.VISIBLE)
                    views.setViewVisibility(R.id.mini_eta, View.GONE)
                    views.setChronometerCountDown(R.id.mini_chrono, true)
                    views.setChronometer(R.id.mini_chrono, elapsedNow + remainingMs, null, true)
                    applyTextColor(context, views, R.id.mini_chrono, etaColor)
                } else {
                    views.setChronometer(R.id.mini_chrono, elapsedNow, null, false)
                    views.setViewVisibility(R.id.mini_chrono, View.GONE)
                    views.setViewVisibility(R.id.mini_eta, View.VISIBLE)
                    views.setTextViewText(R.id.mini_eta, fallbackEtaLabel(d, remainingMs))
                    applyTextColor(context, views, R.id.mini_eta, etaColor)
                }

                views.setOnClickPendingIntent(
                    R.id.mini_dep_container,
                    toggleReminderPendingIntent(context, appWidgetId, eff, d, rowIndex = 0)
                )
                return views
            }

            val maxRows = if (layoutType == LayoutType.COMPACT) 2 else 4
            views.setTextViewText(R.id.widget_updated_at, subtitle)
            views.setViewVisibility(R.id.widget_status_text, View.GONE)
            views.setViewVisibility(R.id.widget_cat_empty, View.GONE)

            val shown = upcoming.take(maxRows)
            for (i in 0 until maxRows) {
                if (i < shown.size) {
                    val d = shown[i]
                    val remKey = ReminderManager.buildKey(eff.siteId, d.designation, d.destination, d.expectedEpochMs)
                    val activeRem = remindersMap[remKey]

                    views.setViewVisibility(ROW_IDS[i], View.VISIBLE)
                    views.setTextViewText(BADGE_IDS[i], d.designation)
                    views.setInt(BADGE_IDS[i], "setBackgroundResource", badgeDrawableFor(d.badgeStyle))
                    views.setTextViewText(DEST_IDS[i], d.destination)
                    applyTextColor(context, views, DEST_IDS[i], if (d.cancelled) palette.cancel else palette.textPrimary)

                    // Show Stand/Track, 🔔 HH:mm when reminded, or delay/cancel status
                    val subParts = mutableListOf<String>()
                    val standStr = d.standOrTrackLabel()
                    if (standStr.isNotEmpty()) {
                        subParts.add(standStr)
                    }
                    if (activeRem != null) {
                        subParts.add("⏱ ${ReminderManager.formatClock(activeRem.triggerAtMs)}")
                    }
                    if (d.cancelled) {
                        subParts.add("Cancelled")
                    } else if (d.delayMinutes > 0) {
                        subParts.add("+${d.delayMinutes} min")
                    }

                    if (subParts.isNotEmpty()) {
                        views.setViewVisibility(SUB_IDS[i], View.VISIBLE)
                        views.setTextViewText(SUB_IDS[i], subParts.joinToString(" · "))
                        applyTextColor(
                            context,
                            views,
                            SUB_IDS[i],
                            when {
                                d.cancelled -> palette.cancel
                                activeRem != null -> palette.accent
                                d.delayMinutes > 0 -> palette.warn
                                else -> palette.textSecondary
                            }
                        )
                    } else {
                        views.setViewVisibility(SUB_IDS[i], View.GONE)
                    }

                    val remainingMs = if (d.expectedEpochMs > 0L) d.expectedEpochMs - nowMs else -1L
                    val etaColor = when {
                        d.cancelled -> palette.cancel
                        d.delayMinutes > 0 -> palette.warn
                        else -> palette.accent
                    }

                    if (!d.cancelled && remainingMs in 6_000L..3_599_000L) {
                        views.setViewVisibility(CHRONO_IDS[i], View.VISIBLE)
                        views.setViewVisibility(ETA_IDS[i], View.GONE)
                        views.setChronometerCountDown(CHRONO_IDS[i], true)
                        views.setChronometer(CHRONO_IDS[i], elapsedNow + remainingMs, null, true)
                        applyTextColor(context, views, CHRONO_IDS[i], etaColor)
                    } else {
                        views.setChronometer(CHRONO_IDS[i], elapsedNow, null, false)
                        views.setViewVisibility(CHRONO_IDS[i], View.GONE)
                        views.setViewVisibility(ETA_IDS[i], View.VISIBLE)
                        views.setTextViewText(ETA_IDS[i], fallbackEtaLabel(d, remainingMs))
                        applyTextColor(context, views, ETA_IDS[i], etaColor)
                    }

                    // 1-tap row toggles 🔔 reminder SILENTLY inside the widget!
                    views.setOnClickPendingIntent(
                        ROW_IDS[i],
                        toggleReminderPendingIntent(context, appWidgetId, eff, d, rowIndex = i)
                    )
                } else {
                    views.setChronometer(CHRONO_IDS[i], elapsedNow, null, false)
                    views.setViewVisibility(ROW_IDS[i], View.GONE)
                }
            }

            return views
        }

        private fun createThemedBase(
            context: Context,
            appWidgetId: Int,
            cfg: WidgetConfig,
            layoutType: LayoutType,
            palette: WidgetPalette
        ): RemoteViews {
            val layoutId = when (layoutType) {
                LayoutType.MINI -> R.layout.widget_mini
                LayoutType.COMPACT -> R.layout.widget_compact
                LayoutType.BOARD -> R.layout.widget_board
            }
            val views = RemoteViews(context.packageName, layoutId)

            val bgAlpha = (cfg.opacityPercent.coerceIn(0, 100) * 255) / 100
            val cardAlpha = if (cfg.opacityPercent == 0) 0 else ((bgAlpha * 0.85f).toInt().coerceAtLeast(36))
            views.setImageViewResource(R.id.widget_bg_image, palette.bgRes)
            views.setInt(R.id.widget_bg_image, "setImageAlpha", bgAlpha)

            views.setInt(R.id.widget_refresh_btn, "setBackgroundResource", palette.btnRes)
            applyTextColor(context, views, R.id.widget_refresh_btn, palette.accent)
            views.setOnClickPendingIntent(R.id.widget_refresh_btn, refreshPendingIntent(context, appWidgetId))

            if (layoutType == LayoutType.MINI) {
                return views
            }

            val maxRows = if (layoutType == LayoutType.COMPACT) 2 else 4
            views.setImageViewResource(R.id.widget_cat_bg, palette.rowRes)
            views.setInt(R.id.widget_cat_bg, "setImageAlpha", cardAlpha)
            for (i in 0 until maxRows) {
                views.setImageViewResource(ROW_BG_IDS[i], palette.rowRes)
                views.setInt(ROW_BG_IDS[i], "setImageAlpha", cardAlpha)
            }

            val eff = cfg.effectiveSlot
            val stationHeader = if (cfg.isActiveNearMe && cfg.resolvedNearMe != null) {
                "📍 ${eff.stationName}"
            } else {
                eff.stationName
            }
            views.setTextViewText(R.id.widget_station_name, stationHeader)
            applyTextColor(context, views, R.id.widget_station_name, palette.textPrimary)
            applyTextColor(context, views, R.id.widget_updated_at, palette.textSecondary)

            val openAppPi = openAppPendingIntent(context, appWidgetId)
            views.setOnClickPendingIntent(R.id.widget_header_tap, openAppPi)
            views.setOnClickPendingIntent(R.id.widget_status_text, openAppPi)

            if (cfg.slots.size > 1) {
                views.setViewVisibility(R.id.widget_tabs_bar, View.VISIBLE)
                for (i in 0 until 3) {
                    if (i < cfg.slots.size) {
                        val slot = cfg.slots[i]
                        val isSelected = i == cfg.activeSlotIndex
                        views.setViewVisibility(TAB_IDS[i], View.VISIBLE)
                        val tabText = if (slot.isNearMe) "📍 Near me" else shortenTabLabel(slot.stationName)
                        views.setTextViewText(TAB_IDS[i], tabText)
                        views.setInt(
                            TAB_IDS[i],
                            "setBackgroundResource",
                            if (isSelected) palette.tabActiveRes else palette.tabIdleRes
                        )
                        applyTextColor(
                            context,
                            views,
                            TAB_IDS[i],
                            if (isSelected) palette.tabActiveText else palette.textSecondary
                        )

                        val tabIntent = Intent(context, MetroWidgetProvider::class.java).apply {
                            action = ACTION_SELECT_SLOT
                            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                            putExtra(EXTRA_SLOT_INDEX, i)
                            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                        }
                        val tabPi = PendingIntent.getBroadcast(
                            context,
                            appWidgetId * 60 + 10 + i,
                            tabIntent,
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                        )
                        views.setOnClickPendingIntent(TAB_IDS[i], tabPi)
                    } else {
                        views.setViewVisibility(TAB_IDS[i], View.GONE)
                    }
                }
            } else {
                views.setViewVisibility(R.id.widget_tabs_bar, View.GONE)
            }

            return views
        }

        private fun fallbackEtaLabel(d: DepartureItem, remainingMs: Long): String = when {
            d.cancelled -> "—"
            d.expectedEpochMs > 0L && remainingMs <= 5_999L -> "NOW"
            d.clockTime.isNotEmpty() -> d.clockTime
            else -> d.display
        }

        private fun openAppPendingIntent(context: Context, appWidgetId: Int): PendingIntent {
            val intent = Intent(context, MainActivity::class.java).apply {
                putExtra(MainActivity.EXTRA_LAUNCH_SOURCE, "widget")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            return PendingIntent.getActivity(
                context,
                appWidgetId * 60 + 1,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        private fun refreshPendingIntent(context: Context, appWidgetId: Int): PendingIntent {
            val intent = Intent(context, MetroWidgetProvider::class.java).apply {
                action = ACTION_REFRESH
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            }
            return PendingIntent.getBroadcast(
                context,
                appWidgetId * 60 + 3,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        private fun catPokePendingIntent(context: Context, appWidgetId: Int): PendingIntent {
            val intent = Intent(context, MetroWidgetProvider::class.java).apply {
                action = ACTION_CAT_POKE
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            }
            return PendingIntent.getBroadcast(
                context,
                appWidgetId * 60 + 4,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        private fun toggleReminderPendingIntent(
            context: Context,
            appWidgetId: Int,
            eff: StationSlot,
            d: DepartureItem,
            rowIndex: Int
        ): PendingIntent {
            val intent = Intent(context, DepartureReminderActivity::class.java).apply {
                putExtra(DepartureReminderActivity.EXTRA_SITE_ID, eff.siteId)
                putExtra(DepartureReminderActivity.EXTRA_SLOT_INDEX, -1)
                putExtra(DepartureReminderActivity.EXTRA_STATION_NAME, eff.stationName)
                putExtra(DepartureReminderActivity.EXTRA_DESIGNATION, d.designation)
                putExtra(DepartureReminderActivity.EXTRA_DESTINATION, d.destination)
                putExtra(DepartureReminderActivity.EXTRA_CLOCK_TIME, d.clockTime)
                putExtra(DepartureReminderActivity.EXTRA_STOP_POINT, d.standOrTrackLabel())
                putExtra(DepartureReminderActivity.EXTRA_EXPECTED_EPOCH_MS, d.expectedEpochMs)
                putExtra(DepartureReminderActivity.EXTRA_BADGE_STYLE, d.badgeStyle.name)
                putExtra(DepartureReminderActivity.EXTRA_WALK_MINUTES, eff.walkMinutes)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            return PendingIntent.getActivity(
                context,
                appWidgetId * 60 + 20 + rowIndex,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        private fun scheduleLocalRollover(context: Context, appWidgetId: Int, triggerAtEpochMs: Long) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val pi = localTickPendingIntent(context, appWidgetId)
            try {
                am.setAndAllowWhileIdle(AlarmManager.RTC, triggerAtEpochMs, pi)
            } catch (_: SecurityException) {
                am.set(AlarmManager.RTC, triggerAtEpochMs, pi)
            }
        }

        private fun cancelLocalRollover(context: Context, appWidgetId: Int) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            am.cancel(localTickPendingIntent(context, appWidgetId))
        }

        private fun localTickPendingIntent(context: Context, appWidgetId: Int): PendingIntent {
            val intent = Intent(context, MetroWidgetProvider::class.java).apply {
                action = ACTION_LOCAL_TICK
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            }
            return PendingIntent.getBroadcast(
                context,
                appWidgetId * 60 + 55,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        fun shortenTabLabel(name: String): String {
            return name
                .replace("Stockholms östra", "Sthlm Östra", ignoreCase = true)
                .replace("Stockholm City", "Sthlm City", ignoreCase = true)
                .replace("Stockholm Odenplan", "Odenplan", ignoreCase = true)
                .replace("Stora Lappkärrsberget", "Stora Lappis", ignoreCase = true)
                .replace("Lappkärrsberget", "Lappis", ignoreCase = true)
                .replace("Tekniska högskolan", "Tekniska", ignoreCase = true)
                .replace(" centrum", " C", ignoreCase = true)
        }

        fun badgeDrawableFor(style: LineBadgeStyle): Int = when (style) {
            LineBadgeStyle.RED -> R.drawable.badge_red
            LineBadgeStyle.GREEN -> R.drawable.badge_green
            LineBadgeStyle.BLUE -> R.drawable.badge_blue
            LineBadgeStyle.PENDEL -> R.drawable.badge_pendel
            LineBadgeStyle.ROSLAG -> R.drawable.badge_roslag
            LineBadgeStyle.TRAM -> R.drawable.badge_tram
            LineBadgeStyle.BUS_BLUE -> R.drawable.badge_bus_blue
            LineBadgeStyle.BUS_RED -> R.drawable.badge_bus_red
            LineBadgeStyle.CYAN -> R.drawable.badge_cyan
        }
    }
}
