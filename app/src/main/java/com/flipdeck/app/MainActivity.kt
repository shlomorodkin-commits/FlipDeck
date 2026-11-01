package com.flipdeck.app

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSmoothScroller
import androidx.recyclerview.widget.RecyclerView

data class AppItem(val label: String, val intent: Intent)

class MainActivity : AppCompatActivity() {

    private val bg = Color.parseColor("#0B0B10")
    private val items = mutableListOf<AppItem>()
    private var selected = 0
    private lateinit var list: RecyclerView
    private lateinit var adapter: AppAdapter

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        window.statusBarColor = bg
        window.navigationBarColor = bg

        loadApps()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding(dp(16), dp(12), dp(16), dp(8))
        }

        val clock = TextClock(this).apply {
            format12Hour = "h:mm"
            format24Hour = "H:mm"
            textSize = 56f
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val date = TextClock(this).apply {
            format12Hour = "EEEE, MMM d"
            format24Hour = "EEEE, MMM d"
            textSize = 15f
            setTextColor(Color.parseColor("#8A8A99"))
            gravity = Gravity.CENTER_HORIZONTAL
        }

        list = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            clipToPadding = false
            isFocusable = false
            overScrollMode = RecyclerView.OVER_SCROLL_NEVER
        }
        adapter = AppAdapter()
        list.adapter = adapter

        root.addView(clock, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(date, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(list, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(8) })
        setContentView(root)

        // Pad the list so the first and last items can sit in the middle of the list area.
        list.post {
            val pad = (list.height / 2) - dp(28)
            if (pad > 0) list.setPadding(0, pad, 0, pad)
            list.scrollToPosition(0)
        }
    }

    private fun loadApps() {
        items.clear()
        items.add(AppItem("Settings", Intent(Settings.ACTION_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)))

        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val found = packageManager.queryIntentActivities(query, 0)
            .filter { it.activityInfo.packageName != packageName }
            .map {
                AppItem(
                    it.loadLabel(packageManager).toString(),
                    Intent(Intent.ACTION_MAIN)
                        .addCategory(Intent.CATEGORY_LAUNCHER)
                        .setClassName(it.activityInfo.packageName, it.activityInfo.name)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            .sortedBy { it.label.lowercase() }
        items.addAll(found)
    }

    private fun select(newIndex: Int) {
        if (items.isEmpty()) return
        val count = items.size
        val target = ((newIndex % count) + count) % count // wraps top <-> bottom
        val old = selected
        selected = target
        adapter.notifyItemChanged(old)
        adapter.notifyItemChanged(target)

        val scroller = object : LinearSmoothScroller(this) {
            override fun calculateDtToFit(
                viewStart: Int, viewEnd: Int, boxStart: Int, boxEnd: Int, snapPreference: Int
            ): Int = (boxStart + (boxEnd - boxStart) / 2) - (viewStart + (viewEnd - viewStart) / 2)

            override fun calculateSpeedPerPixel(displayMetrics: DisplayMetrics): Float =
                110f / displayMetrics.densityDpi
        }
        scroller.targetPosition = target
        list.layoutManager?.startSmoothScroll(scroller)
    }

    private fun launchSelected() {
        val item = items.getOrNull(selected) ?: return
        try {
            startActivity(item.intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Can't open ${item.label}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_DOWN -> { select(selected + 1); return true }
            KeyEvent.KEYCODE_DPAD_UP -> { select(selected - 1); return true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { launchSelected(); return true }
            in KeyEvent.KEYCODE_1..KeyEvent.KEYCODE_9 -> {
                val n = keyCode - KeyEvent.KEYCODE_1 // 1 jumps to first item, 2 to second, ...
                if (n < items.size) select(n)
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    inner class AppAdapter : RecyclerView.Adapter<AppAdapter.VH>() {
        inner class VH(val tv: TextView) : RecyclerView.ViewHolder(tv)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val tv = TextView(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(56))
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(20), 0, dp(20), 0)
                textSize = 20f
                maxLines = 1
            }
            return VH(tv)
        }

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val isSel = position == selected
            val label = if (position < 9) "${position + 1}   ${items[position].label}"
                        else items[position].label
            holder.tv.text = label
            holder.tv.setTextColor(if (isSel) Color.WHITE else Color.parseColor("#7C7C8A"))
            holder.tv.typeface = Typeface.create(
                "sans-serif-medium", if (isSel) Typeface.BOLD else Typeface.NORMAL)
            holder.tv.background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(if (isSel) Color.parseColor("#2A2A3A") else Color.TRANSPARENT)
            }
            holder.tv.animate().scaleX(if (isSel) 1.04f else 1f)
                .scaleY(if (isSel) 1.04f else 1f).setDuration(160).start()
        }
    }
}
