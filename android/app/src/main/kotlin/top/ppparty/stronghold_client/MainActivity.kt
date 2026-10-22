package top.ppparty.stronghold_client

import android.content.Intent
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {
    private var pendingGame: MethodChannel.Result? = null

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, "top.ppparty.stronghold/game")
            .setMethodCallHandler { call, result ->
                if (call.method == "openUpdates") {
                    startActivity(Intent(this, UpdateActivity::class.java)
                        .putExtra("checkOnOpen", call.argument<Boolean>("checkOnOpen") == true))
                    result.success(null)
                } else if (call.method != "openGame") {
                    result.notImplemented()
                } else if (pendingGame != null) {
                    result.error("GAME_OPEN", "游戏窗口已经打开", null)
                } else {
                    pendingGame = result
                    try {
                        @Suppress("DEPRECATION")
                        startActivityForResult(Intent(this, GameActivity::class.java), GAME_REQUEST)
                    } catch (error: Exception) {
                        pendingGame = null
                        result.error("GAME_START", "无法打开游戏窗口：${error.javaClass.simpleName}", null)
                    }
                }
            }
    }

    @Deprecated("Activity result bridge for Flutter launcher")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == GAME_REQUEST) {
            pendingGame?.success(null)
            pendingGame = null
        }
    }

    companion object { private const val GAME_REQUEST = 6412 }
}
