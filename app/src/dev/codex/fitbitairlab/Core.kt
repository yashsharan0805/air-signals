package dev.codex.fitbitairlab

import android.content.Context
import android.app.Instrumentation
import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import dev.airpulse.fitbit_air.FitbitAirPlugin
import dev.airpulse.fitbit_air.AirTransportDiagnostics
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.*
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object LabBridge {
    val messenger = object : BinaryMessenger {}
    fun binding(ctx: Context) = FlutterPlugin.FlutterPluginBinding(ctx.applicationContext, messenger)
    fun scrub(x: Any?): Any? = when (x) {
        is Map<*, *> -> x.entries.filterNot { it.key.toString().contains("address", true) || it.key.toString() in setOf("deviceId", "serialNumber") }.associate { it.key.toString() to scrub(it.value) }
        is Iterable<*> -> x.map { scrub(it) }
        is ByteArray -> "[bytes omitted]"
        else -> x
    }
    fun encode(x: Any?) = JSONObject.wrap(scrub(x)).toString()
    @Synchronized fun record(ctx: Context, data: Map<String, Any?>): String {
        val text = encode(data + mapOf("timestampMillis" to System.currentTimeMillis()))
        val file = java.io.File(ctx.filesDir, "results.jsonl")
        if (file.length() > 256000) file.writeText("")
        file.appendText(text + "\n")
        return text
    }
}

object AirEngine {
    private var plugin: FitbitAirPlugin? = null
    fun call(ctx: Context, method: String, args: Map<String, Any?>? = null, done: (Map<String, Any?>) -> Unit) {
        Handler(Looper.getMainLooper()).post {
            val current = plugin ?: FitbitAirPlugin().also { it.onAttachedToEngine(LabBridge.binding(ctx)); plugin = it }
            val start = SystemClock.elapsedRealtime()
            var completed = false
            fun finish(result: Map<String, Any?>) {
                if (completed) return
                completed = true
                val history = AirTransportDiagnostics.snapshot()["cleanupHistory"] as? List<*>
                val cleanup = history?.lastOrNull() as? Map<*, *>
                val diagnostic = if (method == "pattern" && cleanup?.get("operation") == "Pattern") mapOf("cleanup" to cleanup) else emptyMap()
                val full = result + diagnostic + mapOf("command" to method, "elapsedMillis" to SystemClock.elapsedRealtime() - start)
                LabBridge.record(ctx, full)
                Handler(Looper.getMainLooper()).post { done(full) }
            }
            try {
                current.onMethodCall(MethodCall(method, args), object : MethodChannel.Result {
                    override fun success(result: Any?) = finish(mapOf("status" to "success", "result" to result))
                    override fun error(code: String, message: String?, details: Any?) = finish(mapOf("status" to "error", "code" to code, "message" to message))
                    override fun notImplemented() = finish(mapOf("status" to "not_implemented"))
                })
            } catch (failure: Throwable) {
                finish(mapOf("status" to "error", "code" to "CLIENT_ERROR", "message" to failure.javaClass.simpleName))
            }
        }
    }
}

object Rules {
    fun prefs(ctx: Context) = ctx.getSharedPreferences("signals", Context.MODE_PRIVATE)
    fun all(ctx: Context): Map<String, Int> {
        val data = JSONObject(prefs(ctx).getString("rules", "{}") ?: "{}")
        return data.keys().asSequence().associateWith { data.optInt(it, 1).coerceIn(1, 3) }
    }
    fun save(ctx: Context, rules: Map<String, Int>) = prefs(ctx).edit().putString("rules", JSONObject(rules).toString()).commit()
    fun groups(ctx: Context, profile: Int) = prefs(ctx).getInt("groups_$profile", profile).coerceIn(1, 3)
    fun gap(ctx: Context, profile: Int) = prefs(ctx).getInt("gap_$profile", 1800).coerceIn(1200, 3000)
    fun profileName(profile: Int) = listOf("A", "B", "C")[profile - 1]
}

class LabInstrumentation : Instrumentation() {
    private lateinit var options: Bundle
    override fun onCreate(arguments: Bundle?) { options = arguments ?: Bundle(); start() }
    override fun onStart() {
        val op = options.getString("op") ?: "info"
        if (op == "queue-checks") {
            val result = try { QueueChecks.run() } catch (e: Throwable) { mapOf("passed" to false, "error" to e.toString()) }
            finish(Activity.RESULT_OK, Bundle().apply { putString("result", LabBridge.encode(result)) }); return
        }
        if (op == "configure-test-rule") {
            val existing = Rules.all(targetContext).toMutableMap()
            if (existing.size >= 5 && !existing.containsKey("dev.codex.airtestsender")) {
                finish(Activity.RESULT_CANCELED, Bundle().apply { putString("result", "Five user rules already exist; no rule changed.") }); return
            }
            existing["dev.codex.airtestsender"] = 1
            Rules.save(targetContext, existing)
            finish(Activity.RESULT_OK, Bundle().apply { putString("result", "Local test sender rule added; other rules preserved. Relay is not started.") }); return
        }
        val groups = options.getString("groups")?.toIntOrNull() ?: 1
        val gap = options.getString("gap")?.toIntOrNull() ?: 1800
        val method = mapOf("info" to "readDeviceInfo", "intensity" to "getHapticIntensity", "probe" to "probeReadOnlyResources", "pattern" to "pattern")[op]
        if (method == null || (op == "pattern" && options.getString("consent") != "pattern-calibration-v1") || (op == "probe" && options.getString("consent") != "read-only-v1")) {
            finish(Activity.RESULT_CANCELED, Bundle().apply { putString("result", "Explicit command consent missing") }); return
        }
        val done = CountDownLatch(1); var result = ""
        AirEngine.call(targetContext, method, if (op == "pattern") mapOf("groups" to groups, "gapMillis" to gap) else null) {
            result = LabBridge.encode(it); done.countDown()
        }
        val completed = done.await(80, TimeUnit.SECONDS)
        finish(if (completed) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply { putString("result", if (completed) result else "Wait deadline exceeded; do not replay the command.") })
    }
}
