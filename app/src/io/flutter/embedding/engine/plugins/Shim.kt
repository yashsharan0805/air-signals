package io.flutter.embedding.engine.plugins
import android.content.Context
import io.flutter.plugin.common.BinaryMessenger
interface FlutterPlugin {
 class FlutterPluginBinding(val applicationContext: Context, val binaryMessenger: BinaryMessenger)
 fun onAttachedToEngine(binding: FlutterPluginBinding)
 fun onDetachedFromEngine(binding: FlutterPluginBinding)
}
