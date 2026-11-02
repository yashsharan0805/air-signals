package dev.codex.fitbitairlab

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.*
import android.provider.Settings
import android.view.WindowInsets
import android.widget.*

class LabActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var result: TextView
    private lateinit var selected: TextView
    private val handler = Handler(Looper.getMainLooper())
    private var busy = false
    private val refresh = object : Runnable { override fun run() { updateStatus(); handler.postDelayed(this, 2000) } }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 24, 32, 24); setBackgroundColor(Color.WHITE) }
        fun text(value: String, size: Float = 15f): TextView { val v = TextView(this).apply { text = value; textSize = size; setTextColor(Color.rgb(25, 35, 45)); setPadding(0, 8, 0, 8) }; layout.addView(v); return v }
        fun button(value: String, action: () -> Unit) { layout.addView(Button(this).apply { text = value; setOnClickListener { action() } }) }
        text("Air Signals", 28f)
        text("Give selected apps their own wrist signal. Each group may contain several short buzzes.")
        status = text("Alerts are stopped")
        selected = text("")
        button("1 · Allow Bluetooth") { requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 10) }
        button("2 · Choose apps") { chooseApps() }
        button("3 · Enable notification access") {
            AlertDialog.Builder(this).setTitle("Allow notification access").setMessage("Android grants access to notifications system-wide. Air Signals uses only the app identity and timing for apps you select. It does not read or store message text, titles, senders or contacts, and has no internet permission.")
                .setNegativeButton("Cancel", null).setPositiveButton("Open settings") { _, _ ->
                    startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, ComponentName(this, AirNotificationListener::class.java).flattenToString()))
                }.show()
        }
        button("Start alerts") {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED || !RelayService.accessGranted(this) || Rules.all(this).isEmpty()) {
                Toast.makeText(this, "Complete Bluetooth, app selection and notification access first.", Toast.LENGTH_LONG).show()
            } else if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 11)
            } else { startForegroundService(Intent(this, RelayService::class.java)); updateStatus() }
        }
        button("Stop alerts / preview") {
            val relay = RelayService.instance
            if (relay != null) relay.requestStop("Alerts stopped")
            else AirEngine.call(this, "cancelOperation") { result.text = "Stopped. A write already in progress is allowed to finish its strength check." }
            updateStatus()
        }
        text("Patterns", 21f)
        for (profile in 1..3) {
            val row = LinearLayout(this)
            row.addView(Button(this).apply { text = "Preview ${Rules.profileName(profile)}"; setOnClickListener { preview(profile) } }, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(Button(this).apply { text = "Edit ${Rules.profileName(profile)}"; setOnClickListener { editPattern(profile) } }, LinearLayout.LayoutParams(0, -2, 1f))
            layout.addView(row)
        }
        text("Readiness", 21f)
        button("Read Air model and firmware") { command("readDeviceInfo", null) }
        button("Read vibration strength") { command("getHapticIntensity", null) }
        result = text("Choose an action above. Previews run only when you request them.")
        result.setTextIsSelectable(true)
        text("Bursts from the same app are combined. Queued alerts expire after 45 seconds, with a 5-second cooldown between deliveries. Failed deliveries pause alerts. Start alerts again after restarting your phone.")
        val scroll = ScrollView(this).apply { addView(layout); clipToPadding = false }
        scroll.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setContentView(scroll)
        scroll.requestApplyInsets()
    }
    private fun updateStatus() {
        if (!::status.isInitialized) return
        val enabled = RelayService.instance != null
        status.text = (if (enabled) "Alerts running" else "Alerts stopped") + "\nBluetooth: " + (if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) "allowed" else "needed") + " · Notification access: " + (if (RelayService.accessGranted(this)) "allowed" else "needed") + "\n" + Rules.prefs(this).getString("last_state", "Ready to set up")
        selected.text = if (Rules.all(this).isEmpty()) "No apps selected" else Rules.all(this).entries.joinToString("\n") { (pkg, profile) -> "${label(pkg)} → ${Rules.profileName(profile)} (${Rules.groups(this, profile)} groups)" }
    }
    private fun label(pkg: String): String = try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() } catch (_: Exception) { pkg }
    private fun chooseApps() {
        val apps = packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0).map { it.activityInfo.packageName }.distinct().filter { it != packageName }.sortedBy { label(it).lowercase() }
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val choices = Rules.all(this).toMutableMap()
        val dialog = AlertDialog.Builder(this).setTitle("Assign signals to apps").setView(ScrollView(this).apply { addView(list) }).setNegativeButton("Cancel", null).setPositiveButton("Save") { _, _ -> Rules.save(this, choices); updateStatus() }.create()
        for (pkg in apps) {
            val button = Button(this)
            fun update() { button.text = label(pkg) + " · " + (choices[pkg]?.let { Rules.profileName(it) } ?: "Off") }
            update()
            button.setOnClickListener {
                val labels = arrayOf("Off", "A · ${Rules.groups(this, 1)} groups", "B · ${Rules.groups(this, 2)} groups", "C · ${Rules.groups(this, 3)} groups")
                AlertDialog.Builder(this).setTitle(label(pkg)).setSingleChoiceItems(labels, choices[pkg] ?: 0) { d, which ->
                    if (which == 0) choices.remove(pkg)
                    else if (choices.containsKey(pkg) || choices.size < 5) choices[pkg] = which
                    else Toast.makeText(this, "Select up to five apps.", Toast.LENGTH_SHORT).show()
                    update(); d.dismiss()
                }.show()
            }
            list.addView(button)
        }
        dialog.show()
    }
    private fun editPattern(profile: Int) {
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 8, 32, 8) }
        layout.addView(TextView(this).apply { text = "Number of groups" })
        val groups = NumberPicker(this).apply { minValue = 1; maxValue = 3; value = Rules.groups(this@LabActivity, profile) }; layout.addView(groups)
        layout.addView(TextView(this).apply { text = "Gap between groups" })
        val gap = NumberPicker(this).apply { minValue = 0; maxValue = 3; displayedValues = arrayOf("1.2 seconds", "1.8 seconds", "2.4 seconds", "3.0 seconds"); value = ((Rules.gap(this@LabActivity, profile) - 1200) / 600).coerceIn(0, 3) }; layout.addView(gap)
        AlertDialog.Builder(this).setTitle("Pattern ${Rules.profileName(profile)}").setView(layout).setNegativeButton("Cancel", null).setPositiveButton("Save") { _, _ -> Rules.prefs(this).edit().putInt("groups_$profile", groups.value).putInt("gap_$profile", 1200 + 600 * gap.value).apply(); updateStatus() }.show()
    }
    private fun preview(profile: Int) {
        if (RelayService.instance != null) { Toast.makeText(this, "Stop alerts before previewing.", Toast.LENGTH_SHORT).show(); return }
        AlertDialog.Builder(this).setTitle("Preview ${Rules.profileName(profile)}?").setMessage("Send ${Rules.groups(this, profile)} separated groups at your current strength. Each group may buzz several times. Your original strength is checked afterward.").setNegativeButton("Cancel", null).setPositiveButton("Preview") { _, _ -> command("pattern", mapOf("groups" to Rules.groups(this, profile), "gapMillis" to Rules.gap(this, profile))) }.show()
    }
    private fun command(method: String, args: Map<String, Any?>?) {
        if (busy || RelayService.instance != null) { Toast.makeText(this, "Finish the current action or stop alerts first.", Toast.LENGTH_SHORT).show(); return }
        busy = true; result.text = "Working…"
        AirEngine.call(this, method, args) { value ->
            busy = false
            val data = value["result"] as? Map<*, *>
            result.text = if (value["status"] != "success") "Could not complete: ${value["message"] ?: value["code"]}"
                else if (method == "readDeviceInfo") "${data?.get("name")}\nFirmware ${data?.get("firmware")}"
                else if (method == "getHapticIntensity") "Vibration strength: ${if (data?.get("intensity") == 2) "Low" else "High"}"
                else "Finished ${data?.get("groupsSent")} group(s). Original strength verified: ${data?.get("verified")}."
        }
    }
    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, results: IntArray) { super.onRequestPermissionsResult(code, permissions, results); updateStatus(); if (code == 11) Toast.makeText(this, "Press Start alerts to continue.", Toast.LENGTH_SHORT).show() }
    override fun onResume() { super.onResume(); handler.post(refresh) }
    override fun onPause() { handler.removeCallbacks(refresh); super.onPause() }
}
