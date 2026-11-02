package com.flipdeck.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ContentUris
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
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
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

class ActionOpt(val id: String, val label: String)

enum class Screen { HOME, LIST, TEXT, PHOTO }
enum class ListMode { APPS, SETTINGS, QUICK }
enum class TextMode { KEYTEST, INFO }

class MainActivity : AppCompatActivity() {

    private val MP = ViewGroup.LayoutParams.MATCH_PARENT
    private val WC = ViewGroup.LayoutParams.WRAP_CONTENT
    private val REQ_PHONE = 11
    private val REQ_CALL = 12
    private val REQ_PHOTOS = 13

    // Real key codes on the IRIS Easy Flip 2 (found with the key tester)
    private val KEY_FAVORITE = 318
    private val KEY_SIDE = 317

    private lateinit var prefs: SharedPreferences
    private lateinit var wallpaperView: ImageView
    private lateinit var dimView: View
    private lateinit var homeLayer: LinearLayout
    private lateinit var listLayer: LinearLayout
    private lateinit var textLayer: LinearLayout
    private lateinit var photoLayer: LinearLayout
    private lateinit var photoView: ImageView
    private lateinit var photoInfo: TextView
    private lateinit var batteryText: TextView
    private lateinit var signalText: TextView
    private lateinit var dialText: TextView
    private lateinit var leftHint: TextView
    private lateinit var rightHint: TextView
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
    private var torchOn = false
    private val photoUris = mutableListOf<Uri>()
    private var photoIdx = 0
    private var photoToken = 0

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
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

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
        buildPhoto()
        root.addView(homeLayer, match())
        root.addView(listLayer, match())
        root.addView(textLayer, match())
        root.addView(photoLayer, match())
        setContentView(root)

        loadWallpaper()
        applyDim()
        updateSignal()
        showHome()

        // "Set as wallpaper" from the Photos app
        val d = intent?.data
        if (intent?.action == Intent.ACTION_ATTACH_DATA && d != null) importWallpaper(d)

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
        if (screen == Screen.LIST && listMode == ListMode.QUICK) refreshQuick()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val d = intent.data
        if (intent.action == Intent.ACTION_ATTACH_DATA && d != null) {
            importWallpaper(d)
            showHome()
            return
        }
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
        if (requestCode == REQ_PHOTOS) {
            if (granted) loadPhotos() else toast("Photo permission needed")
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

        // labels for the Favorites key and the side button
        val hints = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        leftHint = shadowText(12f)
        rightHint = shadowText(12f).apply { gravity = Gravity.END }
        hints.addView(leftHint, LinearLayout.LayoutParams(0, WC, 1f))
        hints.addView(rightHint, LinearLayout.LayoutParams(0, WC, 1f))
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

    private fun buildPhoto() {
        photoLayer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }
        photoView = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        photoInfo = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(4))
        }
        photoLayer.addView(photoView, LinearLayout.LayoutParams(MP, 0, 1f))
        photoLayer.addView(photoInfo, LinearLayout.LayoutParams(MP, WC))
    }

    // ---------- switching screens ----------

    private fun setScreen(s: Screen) {
        screen = s
        homeLayer.visibility = if (s == Screen.HOME) View.VISIBLE else View.GONE
        listLayer.visibility = if (s == Screen.LIST) View.VISIBLE else View.GONE
        textLayer.visibility = if (s == Screen.TEXT) View.VISIBLE else View.GONE
        photoLayer.visibility = if (s == Screen.PHOTO) View.VISIBLE else View.GONE
    }

    private fun showHome() {
        setScreen(Screen.HOME)
        updateTiles()
        updateDialText()
        updateHints()
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

    private fun showQuick() {
        listMode = ListMode.QUICK
        rows = buildQuickRows()
        selected = 0
        listTitle.text = "Quick settings"
        listHint.text = "OK toggle   \u25C0 \u25B6 adjust   Back home"
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

    private fun updateHints() {
        leftHint.text = "\u2605 " + labelFor(prefs.getString("fav", "contacts") ?: "contacts")
        rightHint.text = labelFor(prefs.getString("side", "quick") ?: "quick") + " \u25CF"
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
            toast("Can't open that")
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

    // ---------- key shortcuts (Favorites key + side button) ----------

    private fun actionOptions(): List<ActionOpt> {
        val list = mutableListOf(
            ActionOpt("contacts", "Contacts"),
            ActionOpt("messages", "Messages"),
            ActionOpt("phone", "Phone"),
            ActionOpt("apps", "All apps"),
            ActionOpt("quick", "Quick settings"),
            ActionOpt("torch", "Flashlight"),
            ActionOpt("camera", "Camera")
        )
        val q = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        packageManager.queryIntentActivities(q, 0)
            .filter { it.activityInfo.packageName != packageName }
            .sortedBy { it.loadLabel(packageManager).toString().lowercase() }
            .forEach {
                list.add(ActionOpt("app:" + it.activityInfo.packageName, it.loadLabel(packageManager).toString()))
            }
        return list
    }

    private fun labelFor(id: String): String =
        actionOptions().firstOrNull { it.id == id }?.label ?: id

    private fun runAction(id: String) {
        when {
            id == "contacts" -> openContacts()
            id == "messages" -> openMessages()
            id == "phone" -> openDialer()
            id == "apps" -> showApps()
            id == "quick" -> showQuick()
            id == "torch" -> toggleTorch()
            id == "camera" -> safeStart(Intent(MediaStore.ACTION_IMAGE_CAPTURE))
            id.startsWith("app:") -> {
                val i = packageManager.getLaunchIntentForPackage(id.removePrefix("app:"))
                if (i != null) safeStart(i) else toast("App not found")
            }
        }
    }

    private fun cycleAction(prefKey: String, default: String, dir: Int) {
        val opts = actionOptions()
        val cur = prefs.getString(prefKey, default)
        var i = opts.indexOfFirst { it.id == cur }
        if (i < 0) i = 0
        val next = opts[((i + dir) % opts.size + opts.size) % opts.size]
        prefs.edit().putString(prefKey, next.id).apply()
        refreshSettings()
        updateHints()
    }

    // ---------- quick settings ----------

    private fun toggleTorch() {
        try {
            val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val id = cm.cameraIdList.firstOrNull {
                cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
            if (id == null) {
                toast("No flashlight found")
                return
            }
            torchOn = !torchOn
            cm.setTorchMode(id, torchOn)
        } catch (e: Exception) {
            torchOn = false
            toast("Flashlight blocked: " + e.javaClass.simpleName)
        }
        refreshQuick()
    }

    private fun cycleRinger(dir: Int) {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val order = intArrayOf(
            AudioManager.RINGER_MODE_NORMAL, AudioManager.RINGER_MODE_VIBRATE, AudioManager.RINGER_MODE_SILENT
        )
        var i = order.indexOf(am.ringerMode)
        if (i < 0) i = 0
        val next = order[(i + dir + order.size) % order.size]
        try {
            am.ringerMode = next
        } catch (e: Exception) {
            toast("Silent needs Do Not Disturb access")
        }
        refreshQuick()
    }

    private fun changeVolume(stream: Int, d: Int) {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val v = (am.getStreamVolume(stream) + d).coerceIn(0, am.getStreamMaxVolume(stream))
        try {
            am.setStreamVolume(stream, v, 0)
        } catch (e: Exception) {
            toast("Not allowed")
        }
        refreshQuick()
    }

    private fun buildQuickRows(): List<Row> {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val off = Color.parseColor("#4B4B5A")

        val modeName: String
        val modeEmoji: String
        val modeColor: Int
        when (am.ringerMode) {
            AudioManager.RINGER_MODE_NORMAL -> {
                modeName = "Sound"; modeEmoji = "\uD83D\uDD14"; modeColor = Color.parseColor("#4CAF50")
            }
            AudioManager.RINGER_MODE_VIBRATE -> {
                modeName = "Vibrate"; modeEmoji = "\uD83D\uDCF3"; modeColor = Color.parseColor("#FF9800")
            }
            else -> {
                modeName = "Silent"; modeEmoji = "\uD83D\uDD15"; modeColor = Color.parseColor("#F44336")
            }
        }

        val wifiOn = try {
            (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).isWifiEnabled
        } catch (e: Exception) {
            false
        }
        val airplane = try {
            Settings.Global.getInt(contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1
        } catch (e: Exception) {
            false
        }
        val ringV = am.getStreamVolume(AudioManager.STREAM_RING)
        val ringM = am.getStreamMaxVolume(AudioManager.STREAM_RING)
        val mediaV = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        val mediaM = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

        return listOf(
            Row("Flashlight: " + (if (torchOn) "ON" else "OFF"),
                if (torchOn) Color.parseColor("#FFC107") else off, emoji = "\uD83D\uDD26",
                onOpen = { toggleTorch() }),
            Row("Sound mode: $modeName", modeColor, emoji = modeEmoji,
                onOpen = { cycleRinger(1) }, onLeft = { cycleRinger(-1) }, onRight = { cycleRinger(1) }),
            Row("Ring volume: $ringV/$ringM", Color.parseColor("#2196F3"), emoji = "\uD83D\uDD0A",
                onLeft = { changeVolume(AudioManager.STREAM_RING, -1) },
                onRight = { changeVolume(AudioManager.STREAM_RING, 1) },
                onOpen = { changeVolume(AudioManager.STREAM_RING, 1) }),
            Row("Media volume: $mediaV/$mediaM", Color.parseColor("#9C27B0"), emoji = "\uD83C\uDFB5",
                onLeft = { changeVolume(AudioManager.STREAM_MUSIC, -1) },
                onRight = { changeVolume(AudioManager.STREAM_MUSIC, 1) },
                onOpen = { changeVolume(AudioManager.STREAM_MUSIC, 1) }),
            Row("Wi-Fi: " + (if (wifiOn) "ON" else "OFF") + " (OK)",
                if (wifiOn) Color.parseColor("#03A9F4") else off, emoji = "\uD83D\uDCF6",
                onOpen = { safeStart(Intent(Settings.ACTION_WIFI_SETTINGS)) }),
            Row("Bluetooth (OK)", Color.parseColor("#3F51B5"), emoji = "\uD83D\uDD35",
                onOpen = { safeStart(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }),
            Row("Airplane: " + (if (airplane) "ON" else "OFF") + " (OK)",
                if (airplane) Color.parseColor("#FF5722") else off, emoji = "\u2708\uFE0F",
                onOpen = { safeStart(Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS)) })
        )
    }

    private fun refreshQuick() {
        if (!(screen == Screen.LIST && listMode == ListMode.QUICK)) return
        val keep = selected
        rows = buildQuickRows()
        selected = keep
        adapter.notifyDataSetChanged()
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
        val favLabel = labelFor(prefs.getString("fav", "contacts") ?: "contacts")
        val sideLabel = labelFor(prefs.getString("side", "quick") ?: "quick")
        return listOf(
            Row("Wallpaper from photos", Color.parseColor("#E91E63"), emoji = "\uD83D\uDDBC\uFE0F",
                onOpen = { openPhotoBrowser() }),
            Row("Wallpaper from files", Color.parseColor("#AB47BC"), emoji = "\uD83D\uDCC1",
                onOpen = { pickWallpaper() }),
            Row("Wallpaper dimming: $dim%", Color.parseColor("#7C4DFF"), emoji = "\uD83C\uDF17",
                onOpen = { cycleDim() }, onLeft = { changeDim(-10) }, onRight = { changeDim(10) }),
            Row("Default wallpaper", Color.parseColor("#FF9800"), emoji = "\uD83C\uDFA8",
                onOpen = { resetWallpaper() }),
            Row("Fav key: $favLabel", Color.parseColor("#FFB300"), emoji = "\u2B50",
                onOpen = { cycleAction("fav", "contacts", 1) },
                onLeft = { cycleAction("fav", "contacts", -1) },
                onRight = { cycleAction("fav", "contacts", 1) }),
            Row("Side key: $sideLabel", Color.parseColor("#26C6DA"), emoji = "\uD83D\uDD18",
                onOpen = { cycleAction("side", "quick", 1) },
                onLeft = { cycleAction("side", "quick", -1) },
                onRight = { cycleAction("side", "quick", 1) }),
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

    private fun refreshSettings() {
        if (!(screen == Screen.LIST && listMode == ListMode.SETTINGS)) return
        val keep = selected
        rows = buildSettingRows()
        selected = keep
        adapter.notifyDataSetChanged()
    }

    private fun changeDim(delta: Int) {
        val d = (prefs.getInt("dim", 40) + delta).coerceIn(0, 80)
        prefs.edit().putInt("dim", d).apply()
        applyDim()
        refreshSettings()
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
            toast("Original home screen not found")
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
                Toast.makeText(this, "No file picker found on this phone", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun decodeSampled(uri: Uri, maxSide: Int): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (bounds.outWidth / sample > maxSide || bounds.outHeight / sample > maxSide) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        } catch (e: Exception) {
            null
        }
    }

    private fun importWallpaper(uri: Uri) {
        Thread {
            val bmp = decodeSampled(uri, 2160)
            if (bmp != null) {
                try {
                    File(filesDir, "wallpaper.jpg").outputStream().use {
                        bmp.compress(Bitmap.CompressFormat.JPEG, 90, it)
                    }
                    runOnUiThread {
                        loadWallpaper()
                        toast("Wallpaper set")
                    }
                } catch (e: Exception) {
                    runOnUiThread { toast("Couldn't save the picture") }
                }
            } else {
                runOnUiThread { toast("Couldn't read that picture") }
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
        toast("Default wallpaper")
    }

    // ---------- photo browser (pick a wallpaper with the arrow keys) ----------

    private fun openPhotoBrowser() {
        val perm = if (Build.VERSION.SDK_INT >= 33)
            Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
        if (checkSelfPermission(perm) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(perm), REQ_PHOTOS)
        } else {
            loadPhotos()
        }
    }

    private fun loadPhotos() {
        photoUris.clear()
        try {
            val base = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            contentResolver.query(
                base, arrayOf(MediaStore.Images.Media._ID), null, null,
                MediaStore.Images.Media.DATE_ADDED + " DESC"
            )?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                while (c.moveToNext() && photoUris.size < 300) {
                    photoUris.add(ContentUris.withAppendedId(base, c.getLong(idCol)))
                }
            }
        } catch (e: Exception) {
            toast("Can't read photos")
            return
        }
        if (photoUris.isEmpty()) {
            toast("No photos found")
        } else {
            photoIdx = 0
            showPhoto()
        }
    }

    private fun showPhoto() {
        setScreen(Screen.PHOTO)
        photoInfo.text = (photoIdx + 1).toString() + " / " + photoUris.size +
                "    \u25C0 \u25B6 browse   OK set   Back cancel"
        val uri = photoUris[photoIdx]
        val token = ++photoToken
        Thread {
            val bmp = decodeSampled(uri, 720)
            runOnUiThread {
                if (token == photoToken && bmp != null) photoView.setImageBitmap(bmp)
            }
        }.start()
    }

    private fun photoKey(k: Int): Boolean {
        when (k) {
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                photoIdx = (photoIdx + photoUris.size - 1) % photoUris.size
                showPhoto()
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                photoIdx = (photoIdx + 1) % photoUris.size
                showPhoto()
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                importWallpaper(photoUris[photoIdx])
                showHome()
                return true
            }
            KeyEvent.KEYCODE_BACK -> {
                showSettings()
                return true
            }
        }
        return false
    }

    // ---------- phone check ----------

    private fun buildInfo(): String {
        val pm = packageManager
        val web = try {
            WebView.getCurrentWebViewPackage()?.let { it.packageName + " " + it.versionName } ?: "NOT FOUND"
        } catch (e: Exception) {
            "NOT FOUND"
        }
        return "PHONE CHECK\n\n" +
                "Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")\n" +
                "Model: " + Build.MODEL + "\n" +
                "Web engine: " + web + "\n" +
                "Flashlight: " + pm.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH) + "\n" +
                "Telephony: " + pm.hasSystemFeature(PackageManager.FEATURE_TELEPHONY) + "\n" +
                "Wi-Fi: " + pm.hasSystemFeature(PackageManager.FEATURE_WIFI) + "\n" +
                "Bluetooth: " + pm.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH) + "\n\n" +
                "Back = return"
    }

    // ---------- keys ----------

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val testing = screen == Screen.TEXT && textMode == TextMode.KEYTEST
        if (!testing && (keyCode == KEY_FAVORITE || keyCode == KEY_SIDE)) {
            if (event == null || event.repeatCount == 0) {
                val id = if (keyCode == KEY_FAVORITE)
                    prefs.getString("fav", "contacts") else prefs.getString("side", "quick")
                runAction(id ?: "apps")
            }
            return true
        }
        val handled = when (screen) {
            Screen.HOME -> homeKey(keyCode)
            Screen.LIST -> listKey(keyCode)
            Screen.TEXT -> textKey(keyCode)
            Screen.PHOTO -> photoKey(keyCode)
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
                showQuick()
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
            KeyEvent.KEYCODE_MENU -> {
                showApps()
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
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                // In quick settings, pressing Down on the last item goes back home
                if (listMode == ListMode.QUICK && selected == rows.size - 1) showHome()
                else select(selected + 1)
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> { rows.getOrNull(selected)?.onLeft?.invoke(); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { rows.getOrNull(selected)?.onRight?.invoke(); return true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                rows.getOrNull(selected)?.onOpen?.invoke()
                return true
            }
            KeyEvent.KEYCODE_BACK -> {
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
        if (k == KeyEvent.KEYCODE_BACK) {
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
                70f / displayMetrics.densityDpi
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
