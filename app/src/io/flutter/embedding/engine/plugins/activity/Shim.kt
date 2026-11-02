package io.flutter.embedding.engine.plugins.activity
import android.app.Activity
import io.flutter.plugin.common.PluginRegistry.RequestPermissionsResultListener
class ActivityPluginBinding(val activity: Activity) {
 private val listeners = mutableListOf<RequestPermissionsResultListener>()
 fun addRequestPermissionsResultListener(listener: RequestPermissionsResultListener) { listeners.add(listener) }
 fun removeRequestPermissionsResultListener(listener: RequestPermissionsResultListener) { listeners.remove(listener) }
 fun dispatch(code: Int, permissions: Array<out String>, results: IntArray) { listeners.toList().forEach { it.onRequestPermissionsResult(code, permissions, results) } }
}
interface ActivityAware {
 fun onAttachedToActivity(binding: ActivityPluginBinding)
 fun onDetachedFromActivityForConfigChanges()
 fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding)
 fun onDetachedFromActivity()
}
