package io.flutter.plugin.common
interface BinaryMessenger
class MethodCall(val method: String, val arguments: Any?) {
 @Suppress("UNCHECKED_CAST") fun <T> argument(key: String): T? = (arguments as? Map<*, *>)?.get(key) as T?
}
class MethodChannel(messenger: BinaryMessenger, name: String) {
 interface Result { fun success(result: Any?); fun error(code: String, message: String?, details: Any?); fun notImplemented() }
 interface MethodCallHandler { fun onMethodCall(call: MethodCall, result: Result) }
 fun setMethodCallHandler(handler: MethodCallHandler?) { }
}
class EventChannel(messenger: BinaryMessenger, name: String) {
 interface EventSink { fun success(event: Any?); fun error(code: String, message: String?, details: Any?); fun endOfStream() }
 interface StreamHandler { fun onListen(arguments: Any?, events: EventSink); fun onCancel(arguments: Any?) }
 fun setStreamHandler(handler: StreamHandler?) { }
}
interface PluginRegistry {
 fun interface RequestPermissionsResultListener { fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray): Boolean }
}
