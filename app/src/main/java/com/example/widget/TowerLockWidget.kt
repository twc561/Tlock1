package com.example.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.example.MainActivity
import com.example.R
import com.example.telephony.CellModel

class TowerLockWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val cell = readState(context)
        for (appWidgetId in appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId, cell, readAddress(context))
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH) {
            // State is read from app-private prefs rather than intent extras, so a
            // forged broadcast can at most trigger a refresh of trusted data —
            // it can never inject spoofed tower info into the widget.
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val thisWidget = ComponentName(context, TowerLockWidget::class.java)
            val appWidgetIds = appWidgetManager.getAppWidgetIds(thisWidget)

            val cell = readState(context)
            for (appWidgetId in appWidgetIds) {
                updateAppWidget(context, appWidgetManager, appWidgetId, cell, readAddress(context))
            }
        }
    }

    private fun readState(context: Context): CellModel? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.contains("tech")) return null
        return CellModel(
            tech = prefs.getString("tech", "Unknown") ?: "Unknown",
            bandName = prefs.getString("band", "Unknown") ?: "Unknown",
            rsrp = prefs.getInt("rsrp", -140)
        )
    }

    private fun readAddress(context: Context): String? =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString("address", null)

    private fun updateAppWidget(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        cell: CellModel?,
        addressStr: String? = null
    ) {
        val views = RemoteViews(context.packageName, R.layout.widget_layout)

        if (cell != null) {
            views.setTextViewText(R.id.widget_tech, cell.tech)
            views.setTextViewText(R.id.widget_band, cell.bandName)
            views.setTextViewText(R.id.widget_rsrp, "${cell.rsrp} dBm")
            views.setTextViewText(R.id.widget_address, addressStr ?: "Locating active tower...")

            // Set background tech color
            val techColor = if (cell.tech.contains("5G")) 0xFF2E7D32.toInt() else 0xFF1565C0.toInt()
            // Views don't have direct color state modifiers easily in older API, but we can set text color or drawables
            views.setInt(R.id.widget_tech, "setBackgroundColor", techColor)

            // Set RSRP color
            val rsrpColor = when {
                cell.rsrp >= -80 -> 0xFF4CAF50.toInt()
                cell.rsrp >= -95 -> 0xFF8BC34A.toInt()
                cell.rsrp >= -110 -> 0xFFFFB74D.toInt()
                else -> 0xFFE57373.toInt()
            }
            views.setTextColor(R.id.widget_rsrp, rsrpColor)
        } else {
            views.setTextViewText(R.id.widget_tech, "OFFLINE")
            views.setTextViewText(R.id.widget_band, "Tap to start")
            views.setTextViewText(R.id.widget_rsrp, "---")
            views.setTextViewText(R.id.widget_address, "No active monitoring service")
        }

        // Tap opens app
        val intent = Intent(context, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        views.setOnClickPendingIntent(R.id.widget_root, pendingIntent)

        appWidgetManager.updateAppWidget(appWidgetId, views)
    }

    companion object {
        private const val PREFS_NAME = "TowerLockWidgetState"
        private const val ACTION_REFRESH = "com.example.widget.UPDATE_STATE"

        fun sendWidgetUpdate(context: Context, cell: CellModel, address: String) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putString("tech", cell.tech)
                .putString("band", cell.bandName)
                .putInt("rsrp", cell.rsrp)
                .putString("address", address)
                .apply()
            val intent = Intent(context, TowerLockWidget::class.java).apply {
                action = ACTION_REFRESH
            }
            context.sendBroadcast(intent)
        }

        /** Drops the cached state so the widget renders OFFLINE (monitoring stopped). */
        fun clearWidgetState(context: Context) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .clear()
                .apply()
            val intent = Intent(context, TowerLockWidget::class.java).apply {
                action = ACTION_REFRESH
            }
            context.sendBroadcast(intent)
        }
    }
}
