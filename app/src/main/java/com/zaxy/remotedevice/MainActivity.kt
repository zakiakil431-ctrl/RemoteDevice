package com.zaxy.remotedevice

import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    companion object {
        // IP udah disesuaikan sama WiFi lo
        private const val SERVER_URL = "ws://192.168.1.14:3000"
        @Volatile private var ws: WebSocket? = null
        @Volatile private var connected = false
        fun onAccessibilityReady() {}
    }

    private lateinit var deviceId: String
    private lateinit var statusText: TextView
    private lateinit var deviceIdText: TextView
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private val REQ_SCREEN = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        deviceIdText = findViewById(R.id.deviceIdText)
        statusText = findViewById(R.id.statusText)
        deviceIdText.text = "Device ID: $deviceId"

        findViewById<Button>(R.id.btnAccessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            Toast.makeText(this, "Aktifkan RemoteDevice di Accessibility", Toast.LENGTH_LONG).show()
        }

        findViewById<Button>(R.id.btnScreen).setOnClickListener {
            val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            startActivityForResult(mpm.createScreenCaptureIntent(), REQ_SCREEN)
        }

        findViewById<Button>(R.id.btnConnect).setOnClickListener { connect() }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_SCREEN && resultCode == Activity.RESULT_OK && data != null) {
            ScreenService.projectionIntent = data
            ScreenService.resultCode = resultCode
            startForegroundService(Intent(this, ScreenService::class.java))
            Toast.makeText(this, "Screen capture aktif", Toast.LENGTH_SHORT).show()
        }
    }

    private fun connect() {
        if (connected) return
        val req = Request.Builder()
            .url("$SERVER_URL/?role=device&deviceId=$deviceId")
            .build()
        ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                connected = true
                runOnUiThread { statusText.text = "Status: connected" }
                webSocket.send("""{"type":"hello","id":"$deviceId"}""")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try { handleCommand(JSONObject(text), webSocket) } catch (_: Exception) {}
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                connected = false
                runOnUiThread { statusText.text = "Status: gagal (${t.message})" }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                connected = false
                runOnUiThread { statusText.text = "Status: disconnected" }
            }
        })
    }

    private fun handleCommand(j: JSONObject, webSocket: WebSocket) {
        val svc = MyAccessibilityService.instance
        when (j.optString("type")) {
            "tap" -> svc?.tap(j.optDouble("x").toFloat(), j.optDouble("y").toFloat())
            "swipe" -> svc?.swipe(
                j.optDouble("x1").toFloat(), j.optDouble("y1").toFloat(),
                j.optDouble("x2").toFloat(), j.optDouble("y2").toFloat()
            )
            "text" -> svc?.typeText(j.optString("text"))
            "home" -> svc?.global(AccessibilityService.GLOBAL_ACTION_HOME)
            "back" -> svc?.global(AccessibilityService.GLOBAL_ACTION_BACK)
            "recents" -> svc?.global(AccessibilityService.GLOBAL_ACTION_RECENTS)
            "lock" -> svc?.lockScreen()
            "screenshot" -> {
                val b64 = ScreenService.instance?.captureJpegBase64(60)
                if (b64 != null) webSocket.send("""{"type":"screen","image":"$b64"}""")
                else webSocket.send("""{"type":"screen","error":"capture gagal"}""")
            }
        }
    }
}
