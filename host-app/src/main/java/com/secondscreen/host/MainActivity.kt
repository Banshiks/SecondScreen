package com.secondscreen.host

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.IBinder
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.net.NetworkInterface

class MainActivity : AppCompatActivity() {

    private val CAPTURE_REQUEST_CODE = 1001

    private lateinit var projectionManager: MediaProjectionManager
    private var captureService: ScreenCaptureService? = null
    private var serviceBound = false

    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvIpAddress: TextView
    private lateinit var tvConnections: TextView

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val localBinder = binder as ScreenCaptureService.LocalBinder
            captureService = localBinder.getService()
            serviceBound = true
            captureService?.onClientConnected = { count ->
                runOnUiThread { tvConnections.text = "Подключено клиентов: $count" }
            }
        }
        override fun onServiceDisconnected(name: ComponentName) { serviceBound = false }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main_host)

        projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        tvStatus = findViewById(R.id.tvStatus)
        tvIpAddress = findViewById(R.id.tvIpAddress)
        tvConnections = findViewById(R.id.tvConnections)

        val ip = getWifiIpAddress()
        tvIpAddress.text = ip ?: "Нет Wi-Fi"

        btnStart.setOnClickListener {
            startActivityForResult(projectionManager.createScreenCaptureIntent(), CAPTURE_REQUEST_CODE)
        }
        btnStop.setOnClickListener { stopCaptureService() }
        updateUi(false)
    }

    private fun getWifiIpAddress(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces().toList()
            // Сначала ищем wlan интерфейс (Wi-Fi)
            val wlan = interfaces.firstOrNull { it.name.startsWith("wlan") && it.isUp && !it.isLoopback }
            if (wlan != null) {
                val addr = wlan.inetAddresses.toList()
                    .firstOrNull { !it.isLoopbackAddress && !it.hostAddress.contains(':') }
                if (addr != null) return addr.hostAddress
            }
            // Если wlan не найден — берём любой не-VPN IPv4
            for (intf in interfaces) {
                if (!intf.isUp || intf.isLoopback) continue
                // Пропускаем VPN/tun/ppp интерфейсы
                if (intf.name.startsWith("tun") || intf.name.startsWith("ppp") ||
                    intf.name.startsWith("rmnet") || intf.name.startsWith("dummy")) continue
                val addr = intf.inetAddresses.toList()
                    .firstOrNull { !it.isLoopbackAddress && !it.hostAddress.contains(':') }
                if (addr != null) return addr.hostAddress
            }
        } catch (e: Exception) { /* ignore */ }
        return null
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == CAPTURE_REQUEST_CODE) {
            if (resultCode == Activity.RESULT_OK && data != null) startCaptureService(resultCode, data)
            else Toast.makeText(this, "Разрешение отклонено", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startCaptureService(resultCode: Int, data: Intent) {
        val intent = Intent(this, ScreenCaptureService::class.java).apply {
            putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
            putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data)
        }
        startForegroundService(intent)
        bindService(intent, connection, Context.BIND_AUTO_CREATE)
        tvStatus.text = "Сервер запущен · порт ${ScreenCaptureService.WS_PORT}"
        updateUi(true)
    }

    private fun stopCaptureService() {
        if (serviceBound) { unbindService(connection); serviceBound = false }
        stopService(Intent(this, ScreenCaptureService::class.java))
        tvStatus.text = "Остановлен"
        tvConnections.text = "Подключено клиентов: 0"
        updateUi(false)
    }

    private fun updateUi(running: Boolean) {
        btnStart.isEnabled = !running
        btnStop.isEnabled = running
    }

    override fun onDestroy() {
        super.onDestroy()
        if (serviceBound) unbindService(connection)
    }
}
