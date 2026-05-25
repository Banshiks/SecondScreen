package com.secondscreen.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.card.MaterialCardView

class MainActivity : AppCompatActivity() {

    private val CAPTURE_REQUEST = 1001

    private lateinit var projManager: MediaProjectionManager
    private lateinit var nsdHelper: NsdHelper

    private lateinit var cardBroadcast: MaterialCardView
    private lateinit var cardStop: MaterialCardView
    private lateinit var tvConnected: TextView
    private lateinit var tvDiscovery: TextView
    private lateinit var layoutDevices: LinearLayout

    private val devices = mutableMapOf<String, NsdServiceInfo>()
    private var hosting = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        projManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        nsdHelper = NsdHelper(this)
        nsdHelper.acquireMulticastLock()

        cardBroadcast = findViewById(R.id.cardBroadcast)
        cardStop = findViewById(R.id.cardStop)
        tvConnected = findViewById(R.id.tvConnected)
        tvDiscovery = findViewById(R.id.tvDiscovery)
        layoutDevices = findViewById(R.id.layoutDevices)

        cardBroadcast.setOnClickListener { requestCapture() }
        cardStop.setOnClickListener { stopHosting() }

        startDiscovery()
    }

    private fun requestCapture() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
        }
        startActivityForResult(projManager.createScreenCaptureIntent(), CAPTURE_REQUEST)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == CAPTURE_REQUEST && resultCode == Activity.RESULT_OK && data != null) {
            startForegroundService(Intent(this, HostService::class.java).apply {
                putExtra(HostService.EXTRA_RESULT_CODE, resultCode)
                putExtra(HostService.EXTRA_RESULT_DATA, data)
            })
            hosting = true
            cardBroadcast.visibility = View.GONE
            cardStop.visibility = View.VISIBLE
        }
    }

    private fun stopHosting() {
        stopService(Intent(this, HostService::class.java))
        hosting = false
        cardBroadcast.visibility = View.VISIBLE
        cardStop.visibility = View.GONE
        tvConnected.text = ""
    }

    private fun startDiscovery() {
        nsdHelper.discoverServices(
            onFound = { info ->
                runOnUiThread { devices[info.serviceName] = info; refreshDeviceList() }
            },
            onLost = { name ->
                runOnUiThread { devices.remove(name); refreshDeviceList() }
            }
        )
        refreshDeviceList()
    }

    private fun refreshDeviceList() {
        layoutDevices.removeAllViews()
        if (devices.isEmpty()) {
            tvDiscovery.text = "Ищем устройства в сети Wi-Fi..."
            return
        }
        tvDiscovery.text = "Найдено устройств: ${devices.size}"
        for ((_, info) in devices) {
            val ip = info.host?.hostAddress ?: continue
            val btn = layoutInflater.inflate(R.layout.item_device, layoutDevices, false)
            btn.findViewById<TextView>(R.id.tvDeviceIp).text = ip
            btn.setOnClickListener {
                startActivity(Intent(this, ViewerActivity::class.java).apply {
                    putExtra(ViewerActivity.EXTRA_HOST_IP, ip)
                    putExtra(ViewerActivity.EXTRA_HOST_PORT, info.port)
                    putExtra(ViewerActivity.EXTRA_DEVICE_NAME, ip)
                })
            }
            layoutDevices.addView(btn)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        nsdHelper.stopDiscovery()
        nsdHelper.releaseMulticastLock()
    }
}
