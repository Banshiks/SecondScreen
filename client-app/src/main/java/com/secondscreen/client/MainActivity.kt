package com.secondscreen.client

import android.os.Bundle
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity(), SurfaceHolder.Callback {

    private lateinit var surfaceView: SurfaceView
    private lateinit var layoutConnect: LinearLayout
    private lateinit var etIpAddress: EditText
    private lateinit var btnConnect: Button
    private lateinit var btnDisconnect: Button
    private lateinit var tvStatus: TextView

    private var streamClient: StreamClient? = null
    private var touchSender: TouchSender? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Полноэкранный режим
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )

        setContentView(R.layout.activity_main_client)

        surfaceView = findViewById(R.id.surfaceView)
        layoutConnect = findViewById(R.id.layoutConnect)
        etIpAddress = findViewById(R.id.etIpAddress)
        btnConnect = findViewById(R.id.btnConnect)
        btnDisconnect = findViewById(R.id.btnDisconnect)
        tvStatus = findViewById(R.id.tvStatus)

        surfaceView.holder.addCallback(this)

        btnConnect.setOnClickListener {
            val ip = etIpAddress.text.toString().trim()
            if (ip.isEmpty()) {
                Toast.makeText(this, "Введите IP-адрес", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            connect(ip)
        }

        btnDisconnect.setOnClickListener {
            disconnect()
        }
    }

    private fun connect(ip: String) {
        val surface = surfaceView.holder.surface
        if (!surface.isValid) {
            Toast.makeText(this, "Surface не готов, подождите", Toast.LENGTH_SHORT).show()
            return
        }

        // Настраиваем отправщик touch-событий
        touchSender = TouchSender(
            hostIp = ip,
            port = 8766,
            screenWidth = surfaceView.width,
            screenHeight = surfaceView.height,
            streamWidth = 1280,
            streamHeight = 720
        )

        // Настраиваем слушатель касаний на SurfaceView
        surfaceView.setOnTouchListener { _, event ->
            touchSender?.sendEvent(event)
            true
        }

        // Запускаем клиент стрима
        streamClient = StreamClient(
            hostIp = ip,
            port = 8765,
            surface = surface,
            onStatus = { status ->
                runOnUiThread { tvStatus.text = status }
            },
            onConnected = {
                runOnUiThread {
                    layoutConnect.visibility = View.GONE
                    tvStatus.text = "Подключено · $ip"
                    btnDisconnect.visibility = View.VISIBLE
                }
            },
            onDisconnected = {
                runOnUiThread {
                    layoutConnect.visibility = View.VISIBLE
                    btnDisconnect.visibility = View.GONE
                    tvStatus.text = "Отключено"
                }
            }
        )
        streamClient?.connect()

        tvStatus.text = "Подключение к $ip..."
    }

    private fun disconnect() {
        streamClient?.disconnect()
        streamClient = null
        touchSender?.stop()
        touchSender = null
        surfaceView.setOnTouchListener(null)
        layoutConnect.visibility = View.VISIBLE
        btnDisconnect.visibility = View.GONE
        tvStatus.text = "Отключено"
    }

    // SurfaceHolder.Callback
    override fun surfaceCreated(holder: SurfaceHolder) {}
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
    override fun surfaceDestroyed(holder: SurfaceHolder) {
        disconnect()
    }

    override fun onDestroy() {
        super.onDestroy()
        disconnect()
    }
}
