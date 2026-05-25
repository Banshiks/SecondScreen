package com.secondscreen.host

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.media.projection.MediaProjectionManager
import android.net.wifi.WifiManager
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
                runOnUiThread {
                    tvConnections.text = "Подключено клиентов: $count"
                }
            }
        }
        override fun onServiceDisconnected(name: ComponentName) {
            serviceBound = false
        }
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

        val ip = getLocalIpAddress()
        tvIpAddress.text = if (ip != null) "IP: $ip" else "IP: не найден (проверьте Wi-Fi)"

        btnStart.setOnClickListener {
            val captureIntent = projectionManager.createScreenCaptureIntent()
            startActivityForResult(captureIntent, CAPTURE_REQUEST_CODE)
        }

        btnStop.setOnClickListener {
            stopCaptureService()
        }

        updateUi(false)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == CAPTURE_REQUEST_CODE) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                startCaptureService(resultCode, data)
            } else {
                Toast.makeText(this, "Разрешение на захват экрана отклонено", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun startCaptureService(resultCode: Int, data: Intent) {
        val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
            putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
            putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data)
        }
        startForegroundService(serviceIntent)
        bindService(serviceIntent, connection, Context.BIND_AUTO_CREATE)

        tvStatus.text = "Сервер запущен · порт ${ScreenCaptureService.WS_PORT}"
        updateUi(true)
    }

    private fun stopCaptureService() {
        if (serviceBound) {
            unbindService(connection)
            serviceBound = false
        }
        stopService(Intent(this, ScreenCaptureService::class.java))
        tvStatus.text = "Остановлен"
        tvConnections.text = "Подключено клиентов: 0"
        updateUi(false)
    }

    private fun updateUi(running: Boolean) {
        btnStart.isEnabled = !running
        btnStop.isEnabled = running
    }

    private fun getLocalIpAddress(): String? {
        try {
            // Сначала пробуем через NetworkInterface (работает на Android 12+)
            val interfaces = NetworkInterface.getNetworkInterfaces()
            for (intf in interfaces) {
                if (intf.isLoopback || !intf.isUp) continue
                for (addr in intf.inetAddresses) {
                    if (addr.isLoopbackAddress) continue
                    val ip = addr.hostAddress ?: continue
                    // Берём только IPv4
                    if (!ip.contains(':')) return ip
                }
            }
        } catch (e: Exception) {
            // Fallback на WifiManager
        }

        // Запасной вариант через WifiManager
        try {
            @Suppress("DEPRECATION")
            val wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            val ip = wifiManager.connectionInfo.ipAddress
            if (ip != 0) {
                return String.format("%d.%d.%d.%d",
                    ip and 0xff, (ip shr 8) and 0xff,
                    (ip shr 16) and 0xff, (ip shr 24) and 0xff)
            }
        } catch (e: Exception) { /* ignore */ }

        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        if (serviceBound) unbindService(connection)
    }
}
