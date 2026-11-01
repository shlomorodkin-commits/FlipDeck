package com.flipdeck.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.telephony.SignalStrength
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSmoothScroller
import androidx.recyclerview.widget.RecyclerView
import java.io.File

// ---------- small data classes ----------

class Row(
    var title: String,
    val color: Int,
    val emoji: String? = null,
    val icon: Drawable? = null,
    var starred: Boolean = false,
    val key: String? = null,
    val onOpen: (() -> Unit)? = null,
    val onLeft: (() -> Unit)? = null,
    val onRight: (() -> Unit)? = null
)

class Tile(val emoji: String, val label: String, val color: Int, val action: () -> Unit)

enum class Screen { HOME, LIST, TEXT }
enum class ListMode { APPS, SETTINGS }
enum class TextMode { KEYTEST, INFO }

class MainActivity : AppCompatActivity() {

    private val MP = ViewGroup.LayoutParams.MATCH_PARENT
    private val WC = ViewGroup.LayoutParams.WRAP_CONTENT
    private val REQ_PHONE = 11
    private val REQ_CALL = 12

    private lateinit var prefs: SharedPreferences
    private lateinit var wallpaperView: ImageView
    private lateinit var dimView: View
    private lateinit var homeLayer: LinearLayout
    private lateinit var listLayer: LinearLayout
    private lateinit var textLayer: LinearLayout
    private lateinit var batteryText: TextView
    private lateinit var signalText: TextView
    private lateinit var dialText: TextView
    private lateinit var listTitle: TextView
    private lateinit var listHint: TextView
    private lateinit var infoText: TextView
    private lateinit var list: RecyclerView
    private lateinit var tileDefs: List<Tile>
    private val tileViews = mutableListOf<TextView>()
    private val adapter = RowAdapter()

    private var rows: List<Row> = emptyList()
    private var selected = 0
    private var screen = Screen.HOME
    private var listMode = ListMode.APPS
    private var textMode = TextMode.KEYTEST
    private var homeSel = 0
    private val dial = StringBuilder()
    private var signalLevel = -1
    private var backCount = 0
    private var pendingCall: String? = null
    private var telCallback: Any? = null

    private val pickDoc = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importWallpaper(uri)
    }
    private val pickContent = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) importWallpaper(uri)
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            updateBattery(i)
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun match() = FrameLayout.LayoutParams(MP, MP)

    // ---------- lifecycle ----------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        prefs = getSharedPreferences("flipdeck", MODE_PRIVATE)

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        wallpaperView = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        dimView = View(this)
        root.addView(wallpaperView, match())
        root.addView(dimView, match())

        buildHome()
        buildList()
        buildText()
        root.addView(homeLayer, match())
        root.addView(listLayer, match())
        root.addView(textLayer, match())
        setContentView(root)

        loadWallpaper()
        applyDim()
        updateSignal()
        showHome()

        if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
            startSignal()
        } else {
            requestPermissions(arrayOf(Manifest.permission.READ_PHONE_STATE), REQ_PHONE)
        }
    }

    override fun onStart() {
        super.onStart()
        val sticky = registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (sticky != null) updateBattery(sticky)
    }

    override fun onStop() {
        super.onStop()
        try { unregisterReceiver(batteryReceiver) } catch (e: Exception) { }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                val cb = telCallback as? TelephonyCallback
                if (cb != null) getSystemService(TelephonyManager::class.java).unregisterTelephonyCallback(cb)
            } catch (e: Exception) { }
        }
    }

    override fun onResume() {
        super.onResume()
        dial.setLength(0)
        updateDialText()
        if (screen == Screen.LIST && listMode == ListMode.APPS) {
            val keep = rows.getOrNull(selected)?.key
            refreshApps(keep)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Pressing the Home key while we're running brings us back to the home page.
        if (intent.hasCategory(Intent.CATEGORY_HOME)) showHome()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
        if (requestCode == REQ_PHONE && granted) startSignal()
        if (requestCode == REQ_CALL) {
            val n = pendingCall
            pendingCall = null
            if (n != null) startCall(n, granted)
        }
    }

    // ---------- building the screens ----------

    private fun shadowText(size: Float): TextView = TextView(this).apply {
        textSize = size
        setTextColor(Color.WHITE)
        setShadowLayer(8f, 0f, 2f, Color.BLACK)
    }

    private fun spacer() = View(this)

    private fun buildHome() {
        homeLayer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(6))
        }

        // top row: battery (left) + signal (right)
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        batteryText = shadowText(14f)
        signalText = shadowText(14f).apply { gravity = Gravity.END }
        top.addView(batteryText, LinearLayout.LayoutParams(0, WC, 1f))
        top.addView(signalText, LinearLayout.LayoutParams(0, WC, 1f))
        homeLayer.addView(top, LinearLayout.LayoutParams(MP, WC))

        homeLayer.addView(spacer(), LinearLayout.LayoutParams(MP, 0, 1f))

        val clock = TextClock(this).apply {
            format12Hour = "h:mm"
            format24Hour = "H:mm"
            textSize = 60f
            setTextColor(Color.WHITE)
            setShadowLayer(10f, 0f, 3f, Color.BLACK)
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val date = TextClock(this).apply {
            format12Hour = "EEEE, MMM d"
            format24Hour = "EEEE, MMM d"
            textSize = 16f
            setTextColor(Color.WHITE)
            setShadowLayer(8f, 0f, 2f, Color.BLACK)
            gravity = Gravity.CENTER_HORIZONTAL
        }
        dialText = shadowText(30f).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            visibility = View.GONE
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }
        homeLayer.addView(clock, LinearLayout.LayoutParams(MP, WC))
        homeLayer.addView(date, LinearLayout.LayoutParams(MP, WC))
        homeLayer.addView(dialText, LinearLayout.LayoutParams(MP, WC).apply { topMargin = dp(8) })

        homeLayer.addView(spacer(), LinearLayout.LayoutParams(MP, 0, 1f))

        // shortcut tiles
        tileDefs = listOf(
            Tile("\uD83D\uDCF1", "Apps", Color.parseColor("#7C4DFF")) { showApps() },
            Tile("\uD83D\uDCDE", "Phone", Color.parseColor("#2ECC71")) { openDialer() },
            Tile("\uD83D\uDC64", "Contacts", Color.parseColor("#3498DB")) { openContacts() },
            Tile("\uD83D\uDCAC", "Messages", Color.parseColor("#FF9800")) { openMessages() },
            Tile("\u2699\uFE0F", "Settings", Color.parseColor("#00BFA5")) {
                safeStart(Intent(Settings.ACTION_SETTINGS))
            }
        )
        val tileRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (t in tileDefs) {
            val s = SpannableString(t.emoji + "\n" + t.label)
            s.setSpan(RelativeSizeSpan(1.9f), 0, t.emoji.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            val tv = TextView(this).apply {
                text = s
                textSize = 9f
                setTextColor(Color.WHITE)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                gravity = Gravity.CENTER
            }
            tileViews.add(tv)
            tileRow.addView(tv, LinearLayout.LayoutParams(0, dp(62), 1f).apply {
                setMargins(dp(2), 0, dp(2), 0)
            })
        }
        homeLayer.addView(tileRow, LinearLayout.LayoutParams(MP, WC))

        // soft key labels
        val hints = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val left = shadowText(12f).apply { text = "Menu" }
        val right = shadowText(12f).apply { text = "Contacts"; gravity = Gravity.END }
        hints.addView(left, LinearLayout.LayoutParams(0, WC, 1f))
        hints.addView(right, LinearLayout.LayoutParams(0, WC, 1f))
        homeLayer.addView(hints, LinearLayout.LayoutParams(MP, WC).apply { topMargin = dp(6) })

        updateTiles()
    }

    private fun buildList() {
        listLayer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(190, 8, 8, 14))
            setPadding(dp(10), dp(8), dp(10), dp(6))
        }
        listTitle = TextView(this).apply {
            textSize = 17f
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }
        list = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            clipToPadding = false
            isFocusable = false
            itemAnimator = null
            overScrollMode = View.OVER_SCROLL_NEVER
            adapter = this@MainActivity.adapter
        }
        listHint = TextView(this).apply {
            textSize = 11f
            setTextColor(Color.parseColor("#9A9AAC"))
        }
        listLayer.addView(listTitle, LinearLayout.LayoutParams(MP, WC))
        listLayer.addView(list, LinearLayout.LayoutParams(MP, 0, 1f))
        listLayer.addView(listHint, LinearLayout.LayoutParams(MP, WC))
    }

    private fun buildText() {
        textLayer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(235, 8, 8, 14))
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }
        infoText = TextView(this).apply {
            textSize = 15f
            setTextColor(Color.WHITE)
        }
        textLayer.addView(infoText, LinearLayout.LayoutParams(MP, WC))
    }

    // ---------- switching screens ----------

    private fun setScreen(s: Screen) {
        screen = s
        homeLayer.visibility = if (s == Screen.HOME) View.VISIBLE else View.GONE
        listLayer.visibility = if (s == Screen.LIST) View.VISIBLE else View.GONE
        textLayer.visibility = if (s == Screen.TEXT) View.VISIBLE else View.GONE
    }

    private fun showHome() {
        setScreen(Screen.HOME)
        updateTiles()
        updateDialText()
    }

    private fun showApps() {
        listMode = ListMode.APPS
        rows = buildAppRows()
        selected = 0
        listTitle.text = "All apps"
        listHint.text = "OK open   * pin   # move up"
        showListScreen()
    }

    private fun showSettings() {
        listMode = ListMode.SETTINGS
        rows = buildSettingRows()
        selected = 0
        listTitle.text = "FlipDeck settings"
        listHint.text = "OK select   \u25C0 \u25B6 change   Back return"
        showListScreen()
    }

    private fun showListScreen() {
        setScreen(Screen.LIST)
        adapter.notifyDataSetChanged()
        list.post {
            val pad = list.height / 2 - dp(30)
            if (pad > 0) list.setPadding(0, pad, 0, pad)
            list.scrollToPosition(selected)
        }
    }

    private fun showKeyTester() {
        textMode = TextMode.KEYTEST
        backCount = 0
        infoText.text = "KEY TESTER\n\nPress any key.\nBack twice = exit."
        setScreen(Screen.TEXT)
    }

    private fun showInfo() {
        textMode = TextMode.INFO
        infoText.text = buildInfo()
        setScreen(Screen.TEXT)
    }

    // ---------- home page pieces ----------

    private fun updateTiles() {
        for ((i, tv) in tileViews.withIndex()) {
            val sel = i == homeSel
            tv.background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(tileDefs[i].color)
                if (sel) setStroke(dp(3), Color.WHITE)
            }
            tv.animate().scaleX(if (sel) 1.08f else 1f).scaleY(if (sel) 1.08f else 1f)
                .setDuration(140).start()
            tv.alpha = if (sel) 1f else 0.82f
        }
    }

    private fun updateDialText() {
        if (dial.isEmpty()) {
            dialText.visibility = View.GONE
        } else {
            dialText.visibility = View.VISIBLE
            dialText.text = dial.toString()
        }
    }

    private fun updateBattery(i: Intent) {
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
        if (level >= 0 && scale > 0) {
            val icon = if (charging) "\u26A1" else "\uD83D\uDD0B"
            batteryText.text = icon + " " + (level * 100 / scale) + "%"
        }
    }

    private fun updateSignal() {
        val s = SpannableString("\u2582\u2584\u2586\u2588")
        for (i in 0 until 4) {
            val on = signalLevel >= 0 && i < signalLevel
            s.setSpan(
                ForegroundColorSpan(if (on) Color.WHITE else Color.parseColor("#66FFFFFF")),
                i, i + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        signalText.text = s
    }

    @SuppressLint("MissingPermission")
    private fun startSignal() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        if (telCallback != null) return
        try {
            val tm = getSystemService(TelephonyManager::class.java)
            val cb = object : TelephonyCallback(), TelephonyCallback.SignalStrengthsListener {
                override fun onSignalStrengthsChanged(signalStrength: SignalStrength) {
                    signalLevel = signalStrength.level
                    updateSignal()
                }
            }
            tm.registerTelephonyCallback(mainExecutor, cb)
            telCallback = cb
        } catch (e: Exception) { }
    }

    // ---------- calling and launching ----------

    private fun safeStart(i: Intent) {
        try {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(i)
        } catch (e: Exception) {
            Toast.makeText(this, "Can't open that", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openDialer() = safeStart(Intent(Intent.ACTION_DIAL))

    private fun openContacts() =
        safeStart(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_CONTACTS))

    private fun openMessages() =
        safeStart(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MESSAGING))

    private fun placeCall() {
        val n = dial.toString()
        if (n.isEmpty()) return
        if (checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
            startCall(n, true)
        } else {
            pendingCall = n
            requestPermissions(arrayOf(Manifest.permission.CALL_PHONE), REQ_CALL)
        }
    }

    // direct = true places the call right away; false opens the phone's dialer with the number filled in
    private fun startCall(n: String, direct: Boolean) {
        val uri = Uri.parse("tel:" + Uri.encode(n))
        safeStart(Intent(if (direct) Intent.ACTION_CALL else Intent.ACTION_DIAL, uri))
        dial.setLength(0)
        updateDialText()
    }

    @SuppressLint("PrivateApi")
    private fun openQuickPanel() {
        try {
            val sbs = getSystemService("statusbar")
            val cls = Class.forName("android.app.StatusBarManager")
            cls.getMethod("expandSettingsPanel").invoke(sbs)
        } catch (e: Exception) {
            Toast.makeText(this, "Control panel can't open from here yet", Toast.LENGTH_SHORT).show()
        }
    }

    // ---------- apps list ----------

    private val palette = intArrayOf(
        Color.parseColor("#E91E63"), Color.parseColor("#9C27B0"), Color.parseColor("#3F51B5"),
        Color.parseColor("#2196F3"), Color.parseColor("#00ACC1"), Color.parseColor("#26A69A"),
        Color.parseColor("#66BB6A"), Color.parseColor("#FFA726"), Color.parseColor("#FF7043"),
        Color.parseColor("#EC407A")
    )

    private fun colorFor(pkg: String) = palette[Math.abs(pkg.hashCode()) % palette.size]

    private fun getPins(): List<String> =
        (prefs.getString("pins", "") ?: "").split("|").filter { it.isNotEmpty() }

    private fun savePins(p: List<String>) {
        prefs.edit().putString("pins", p.joinToString("|")).apply()
    }

    private fun buildAppRows(): List<Row> {
        val q = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val infos = packageManager.queryIntentActivities(q, 0)
            .filter { it.activityInfo.packageName != packageName }
        val pins = getPins()

        fun toRow(ri: ResolveInfo): Row {
            val pkg = ri.activityInfo.packageName
            val intent = Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setClassName(pkg, ri.activityInfo.name)
            return Row(
                title = ri.loadLabel(packageManager).toString(),
                color = colorFor(pkg),
                icon = ri.loadIcon(packageManager),
                starred = pkg in pins,
                key = pkg,
                onOpen = { safeStart(intent) }
            )
        }

        val byPkg = infos.associateBy { it.activityInfo.packageName }
        val pinnedRows = pins.mapNotNull { byPkg[it] }.map { toRow(it) }
        val restRows = infos
            .filter { it.activityInfo.packageName !in pins }
            .sortedBy { it.loadLabel(packageManager).toString().lowercase() }
            .map { toRow(it) }

        val settingsRow = Row(
            title = "FlipDeck settings",
            color = Color.parseColor("#455A64"),
            emoji = "\u2699\uFE0F",
            key = "__settings",
            onOpen = { showSettings() }
        )
        return listOf(settingsRow) + pinnedRows + restRows
    }

    private fun refreshApps(keepKey: String?) {
        rows = buildAppRows()
        val idx = if (keepKey == null) -1 else rows.indexOfFirst { it.key == keepKey }
        selected = if (idx >= 0) idx else 0
        adapter.notifyDataSetChanged()
        list.scrollToPosition(selected)
    }

    private fun togglePin() {
        val key = rows.getOrNull(selected)?.key ?: return
        if (key == "__settings") return
        val pins = getPins().toMutableList()
        if (key in pins) pins.remove(key) else pins.add(key)
        savePins(pins)
        refreshApps(key)
    }

    private fun movePinUp() {
        val key = rows.getOrNull(selected)?.key ?: return
        val pins = getPins().toMutableList()
        val i = pins.indexOf(key)
        if (i > 0) {
            pins.removeAt(i)
            pins.add(i - 1, key)
            savePins(pins)
            refreshApps(key)
        }
    }

    // ---------- settings list ----------

    private fun buildSettingRows(): List<Row> {
        val dim = prefs.getInt("dim", 40)
        return listOf(
            Row("Choose wallpaper", Color.parseColor("#E91E63"), emoji = "\uD83D\uDDBC\uFE0F",
                onOpen = { pickWallpaper() }),
            Row("Wallpaper dimming: $dim%", Color.parseColor("#7C4DFF"), emoji = "\uD83C\uDF17",
                onOpen = { cycleDim() }, onLeft = { changeDim(-10) }, onRight = { changeDim(10) }),
            Row("Default wallpaper", Color.parseColor("#FF9800"), emoji = "\uD83C\uDFA8",
                onOpen = { resetWallpaper() }),
            Row("Key tester", Color.parseColor("#00BFA5"), emoji = "\u2328\uFE0F",
                onOpen = { showKeyTester() }),
            Row("Phone check", Color.parseColor("#2196F3"), emoji = "\uD83E\uDE7A",
                onOpen = { showInfo() }),
            Row("Original home screen", Color.parseColor("#607D8B"), emoji = "\uD83C\uDFE0",
                onOpen = { openOriginalHome() }),
            Row("Uninstall FlipDeck", Color.parseColor("#F44336"), emoji = "\uD83D\uDDD1\uFE0F",
                onOpen = { uninstallSelf() })
        )
    }

    private fun changeDim(delta: Int) {
        val d = (prefs.getInt("dim", 40) + delta).coerceIn(0, 80)
        prefs.edit().putInt("dim", d).apply()
        applyDim()
        val keep = selected
        rows = buildSettingRows()
        selected = keep
        adapter.notifyDataSetChanged()
    }

    private fun cycleDim() {
        val d = prefs.getInt("dim", 40)
        changeDim(if (d >= 80) -80 else 10)
    }

    private fun applyDim() {
        val d = prefs.getInt("dim", 40)
        dimView.setBackgroundColor(Color.argb(d * 255 / 100, 0, 0, 0))
    }

    private fun openOriginalHome() {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val other = packageManager.queryIntentActivities(home, 0)
            .firstOrNull { it.activityInfo.packageName != packageName }
        if (other == null) {
            Toast.makeText(this, "Original home screen not found", Toast.LENGTH_SHORT).show()
            return
        }
        safeStart(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .setClassName(other.activityInfo.packageName, other.activityInfo.name)
        )
    }

    private fun uninstallSelf() {
        safeStart(Intent(Intent.ACTION_DELETE, Uri.parse("package:$packageName")))
    }

    // ---------- wallpaper ----------

    private fun pickWallpaper() {
        try {
            pickDoc.launch(arrayOf("image/*"))
        } catch (e: ActivityNotFoundException) {
            try {
                pickContent.launch("image/*")
            } catch (e2: Exception) {
                Toast.makeText(this, "No photo picker found on this phone", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun importWallpaper(uri: Uri) {
        Thread {
            try {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                var sample = 1
                while (bounds.outWidth / sample > 2160 || bounds.outHeight / sample > 2160) sample *= 2
                val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                val bmp = contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, opts)
                }
                if (bmp != null) {
                    File(filesDir, "wallpaper.jpg").outputStream().use {
                        bmp.compress(Bitmap.CompressFormat.JPEG, 90, it)
                    }
                    runOnUiThread {
                        loadWallpaper()
                        Toast.makeText(this, "Wallpaper set", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    runOnUiThread {
                        Toast.makeText(this, "Couldn't read that picture", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Couldn't read that picture", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun loadWallpaper() {
        val f = File(filesDir, "wallpaper.jpg")
        val bmp = if (f.exists()) BitmapFactory.decodeFile(f.path) else null
        if (bmp != null) {
            wallpaperView.setImageBitmap(bmp)
        } else {
            wallpaperView.setImageDrawable(
                GradientDrawable(
                    GradientDrawable.Orientation.TL_BR,
                    intArrayOf(
                        Color.parseColor("#4A1D96"),
                        Color.parseColor("#DB2777"),
                        Color.parseColor("#F59E0B")
                    )
                )
            )
        }
    }

    private fun resetWallpaper() {
        File(filesDir, "wallpaper.jpg").delete()
        loadWallpaper()
        Toast.makeText(this, "Default wallpaper", Toast.LENGTH_SHORT).show()
    }

    // ---------- phone check ----------

    private fun buildInfo(): String {
        val pm = packageManager
        val web = try {
            WebView.getCurrentWebViewPackage()?.let { it.packageName + " " + it.versionName } ?: "NOT FOUND"
        } catch (e: Exception) {
            "NOT FOUND"
        }
        val panel = if (getSystemService("statusbar") != null) "yes" else "no"
        return "PHONE CHECK\n\n" +
                "Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")\n" +
                "Model: " + Build.MODEL + "\n" +
                "Web engine: " + web + "\n" +
                "Flashlight: " + pm.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH) + "\n" +
                "Telephony: " + pm.hasSystemFeature(PackageManager.FEATURE_TELEPHONY) + "\n" +
                "Wi-Fi: " + pm.hasSystemFeature(PackageManager.FEATURE_WIFI) + "\n" +
                "Bluetooth: " + pm.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH) + "\n" +
                "Status bar service: " + panel + "\n\n" +
                "Back = return"
    }

    // ---------- keys ----------

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val handled = when (screen) {
            Screen.HOME -> homeKey(keyCode)
            Screen.LIST -> listKey(keyCode)
            Screen.TEXT -> textKey(keyCode)
        }
        return handled || super.onKeyDown(keyCode, event)
    }

    private fun appendDial(c: Char) {
        if (dial.length < 24) dial.append(c)
        updateDialText()
    }

    private fun deleteDial(): Boolean {
        if (dial.isEmpty()) return false
        dial.deleteCharAt(dial.length - 1)
        updateDialText()
        return true
    }

    private fun homeKey(k: Int): Boolean {
        when (k) {
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                homeSel = (homeSel + tileDefs.size - 1) % tileDefs.size
                updateTiles()
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                homeSel = (homeSel + 1) % tileDefs.size
                updateTiles()
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                openQuickPanel()
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                showApps()
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (dial.isNotEmpty()) placeCall() else tileDefs[homeSel].action()
                return true
            }
            KeyEvent.KEYCODE_CALL -> {
                if (dial.isNotEmpty()) placeCall() else openDialer()
                return true
            }
            KeyEvent.KEYCODE_SOFT_LEFT, KeyEvent.KEYCODE_MENU -> {
                showApps()
                return true
            }
            KeyEvent.KEYCODE_SOFT_RIGHT -> {
                openContacts()
                return true
            }
            KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_CLEAR -> {
                deleteDial()
                return true
            }
            KeyEvent.KEYCODE_BACK -> {
                return deleteDial()
            }
            KeyEvent.KEYCODE_STAR -> {
                appendDial('*')
                return true
            }
            KeyEvent.KEYCODE_POUND -> {
                appendDial('#')
                return true
            }
        }
        if (k >= KeyEvent.KEYCODE_0 && k <= KeyEvent.KEYCODE_9) {
            appendDial('0' + (k - KeyEvent.KEYCODE_0))
            return true
        }
        return false
    }

    private fun listKey(k: Int): Boolean {
        when (k) {
            KeyEvent.KEYCODE_DPAD_UP -> { select(selected - 1); return true }
            KeyEvent.KEYCODE_DPAD_DOWN -> { select(selected + 1); return true }
            KeyEvent.KEYCODE_DPAD_LEFT -> { rows.getOrNull(selected)?.onLeft?.invoke(); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { rows.getOrNull(selected)?.onRight?.invoke(); return true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_SOFT_LEFT -> {
                rows.getOrNull(selected)?.onOpen?.invoke()
                return true
            }
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_SOFT_RIGHT -> {
                if (listMode == ListMode.SETTINGS) showApps() else showHome()
                return true
            }
            KeyEvent.KEYCODE_STAR -> {
                if (listMode == ListMode.APPS) togglePin()
                return true
            }
            KeyEvent.KEYCODE_POUND -> {
                if (listMode == ListMode.APPS) movePinUp()
                return true
            }
        }
        if (listMode == ListMode.APPS && k >= KeyEvent.KEYCODE_1 && k <= KeyEvent.KEYCODE_9) {
            val n = k - KeyEvent.KEYCODE_1
            if (n < rows.size) select(n)
            return true
        }
        return false
    }

    private fun textKey(k: Int): Boolean {
        if (textMode == TextMode.KEYTEST) {
            if (k == KeyEvent.KEYCODE_BACK) backCount++ else backCount = 0
            infoText.text = "KEY TESTER\n\nLast key:\n" + KeyEvent.keyCodeToString(k) +
                    "\ncode " + k + "\n\nBack twice = exit"
            if (backCount >= 2) showSettings()
            return true
        }
        if (k == KeyEvent.KEYCODE_BACK || k == KeyEvent.KEYCODE_SOFT_RIGHT) {
            showSettings()
            return true
        }
        return false
    }

    // ---------- list selection + smooth scrolling ----------

    private fun select(newIndex: Int) {
        if (rows.isEmpty()) return
        val count = rows.size
        val target = ((newIndex % count) + count) % count
        val old = selected
        selected = target
        adapter.notifyItemChanged(old)
        adapter.notifyItemChanged(target)

        val scroller = object : LinearSmoothScroller(this) {
            override fun calculateDtToFit(
                viewStart: Int, viewEnd: Int, boxStart: Int, boxEnd: Int, snapPreference: Int
            ): Int = (boxStart + (boxEnd - boxStart) / 2) - (viewStart + (viewEnd - viewStart) / 2)

            override fun calculateSpeedPerPixel(displayMetrics: DisplayMetrics): Float =
                90f / displayMetrics.densityDpi
        }
        scroller.targetPosition = target
        list.layoutManager?.startSmoothScroll(scroller)
    }

    inner class RowAdapter : RecyclerView.Adapter<RowAdapter.VH>() {

        inner class VH(
            val root: LinearLayout,
            val tile: FrameLayout,
            val iconView: ImageView,
            val emojiView: TextView,
            val title: TextView,
            val star: TextView
        ) : RecyclerView.ViewHolder(root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val ctx = parent.context
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = RecyclerView.LayoutParams(MP, dp(60))
                setPadding(dp(8), 0, dp(10), 0)
            }
            val tile = FrameLayout(ctx)
            val icon = ImageView(ctx).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                setPadding(dp(5), dp(5), dp(5), dp(5))
            }
            val emoji = TextView(ctx).apply {
                textSize = 22f
                gravity = Gravity.CENTER
            }
            tile.addView(icon, FrameLayout.LayoutParams(MP, MP))
            tile.addView(emoji, FrameLayout.LayoutParams(MP, MP))
            row.addView(tile, LinearLayout.LayoutParams(dp(44), dp(44)))
            val title = TextView(ctx).apply {
                textSize = 18f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(dp(12), 0, dp(6), 0)
            }
            row.addView(title, LinearLayout.LayoutParams(0, WC, 1f))
            val star = TextView(ctx).apply {
                text = "\u2605"
                textSize = 16f
                setTextColor(Color.parseColor("#FFC107"))
            }
            row.addView(star, LinearLayout.LayoutParams(WC, WC))
            return VH(row, tile, icon, emoji, title, star)
        }

        override fun getItemCount() = rows.size

        override fun onBindViewHolder(h: VH, position: Int) {
            val r = rows[position]
            val sel = position == selected
            h.title.text = r.title
            h.title.setTextColor(if (sel) Color.WHITE else Color.parseColor("#B8B8C6"))
            h.title.typeface = Typeface.create(
                "sans-serif-medium", if (sel) Typeface.BOLD else Typeface.NORMAL
            )
            h.star.visibility = if (r.starred) View.VISIBLE else View.GONE
            h.tile.background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(r.color)
            }
            if (r.icon != null) {
                h.iconView.setImageDrawable(r.icon)
                h.iconView.visibility = View.VISIBLE
                h.emojiView.visibility = View.GONE
            } else {
                h.iconView.visibility = View.GONE
                h.emojiView.visibility = View.VISIBLE
                h.emojiView.text = r.emoji ?: ""
            }
            h.root.background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(if (sel) Color.argb(70, 255, 255, 255) else Color.TRANSPARENT)
            }
            h.root.animate().scaleX(if (sel) 1.03f else 1f).scaleY(if (sel) 1.03f else 1f)
                .setDuration(140).start()
        }
    }
}
