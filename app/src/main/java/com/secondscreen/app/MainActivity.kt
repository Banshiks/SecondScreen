package com.secondscreen.app

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.net.nsd.NsdServiceInfo
import android.net.wifi.p2p.WifiP2pDevice
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.card.MaterialCardView
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private val CAPTURE_REQUEST = 1001
    private val PERM_REQUEST = 100
    private val PERM_LOCATION = 200

    private lateinit var projManager: MediaProjectionManager
    private lateinit var nsdHelper: NsdHelper
    private var wifiDirectHelper: WifiDirectHelper? = null

    private lateinit var cardBroadcast: MaterialCardView
    private lateinit var cardStop: MaterialCardView
    private lateinit var cardPause: MaterialCardView
    private lateinit var cardAccessibility: MaterialCardView
    private lateinit var cardBattery: MaterialCardView
    private lateinit var cardDnd: MaterialCardView
    private lateinit var cardManualIp: MaterialCardView
    private lateinit var tvConnected: TextView
    private lateinit var tvDiscovery: TextView
    private lateinit var layoutDevices: LinearLayout
    private lateinit var etManualIp: EditText

    private val nsdDevices = mutableMapOf<String, NsdServiceInfo>()
    private val p2pPeers = mutableListOf<WifiP2pDevice>()
    private var isPaused = false

    private lateinit var prefs: SharedPreferences

    companion object {
        const val PREFS_NAME = "secondscreen_prefs"
        const val KEY_LAST_IP = "last_ip"
        const val KEY_HISTORY = "device_history"
        const val MAX_HISTORY = 5
        const val ACTION_PAUSE = "com.secondscreen.app.ACTION_PAUSE"
        const val ACTION_RESUME = "com.secondscreen.app.ACTION_RESUME"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        projManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        nsdHelper = NsdHelper(this)
        nsdHelper.acquireMulticastLock()

        cardBroadcast = findViewById(R.id.cardBroadcast)
        cardStop = findViewById(R.id.cardStop)
        cardPause = findViewById(R.id.cardPause)
        cardAccessibility = findViewById(R.id.cardAccessibility)
        cardBattery = findViewById(R.id.cardBattery)
        cardDnd = findViewById(R.id.cardDnd)
        cardManualIp = findViewById(R.id.cardManualIp)
        tvConnected = findViewById(R.id.tvConnected)
        tvDiscovery = findViewById(R.id.tvDiscovery)
        layoutDevices = findViewById(R.id.layoutDevices)
        etManualIp = findViewById(R.id.etManualIp)

        val lastIp = prefs.getString(KEY_LAST_IP, "") ?: ""
        if (lastIp.isNotEmpty()) etManualIp.setText(lastIp)

        cardBroadcast.setOnClickListener { requestCapture() }
        cardStop.setOnClickListener { stopHosting() }
        cardPause.setOnClickListener { togglePause() }

        cardAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        cardBattery.setOnClickListener {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:$packageName")))
        }
        cardDnd.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
        }

        findViewById<View>(R.id.btnConnectIp).setOnClickListener {
            val ip = etManualIp.text.toString().trim()
            if (ip.isNotEmpty()) {
                prefs.edit().putString(KEY_LAST_IP, ip).apply()
                startActivity(Intent(this, ViewerActivity::class.java).apply {
                    putExtra(ViewerActivity.EXTRA_HOST_IP, ip)
                    putExtra(ViewerActivity.EXTRA_HOST_PORT, HostService.STREAM_PORT)
                    putExtra(ViewerActivity.EXTRA_DEVICE_NAME, ip)
                })
            }
        }

        requestLocationPermission()
        startDiscovery()
        setupWifiDirect()
    }

    override fun onResume() {
        super.onResume()
        updateAccessibilityCard()
        updateBatteryCard()
        updateDndCard()
        wifiDirectHelper?.register()
        wifiDirectHelper?.discoverPeers()
    }

    override fun onPause() {
        super.onPause()
        wifiDirectHelper?.unregister()
    }

    private fun requestLocationPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), PERM_LOCATION)
        }
    }

    private fun isAccessibilityEnabled(): Boolean {
        val service = "${packageName}/${TouchAccessibilityService::class.java.name}"
        return try {
            val enabled = Settings.Secure.getString(contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
            enabled.contains(service)
        } catch (e: Exception) { false }
    }

    private fun updateAccessibilityCard() {
        cardAccessibility.visibility = if (isAccessibilityEnabled()) View.GONE else View.VISIBLE
    }

    private fun updateBatteryCard() {
        val pm = getSystemService(PowerManager::class.java)
        cardBattery.visibility = if (pm.isIgnoringBatteryOptimizations(packageName)) View.GONE else View.VISIBLE
    }

    private fun updateDndCard() {
        val nm = getSystemService(android.app.NotificationManager::class.java)
        cardDnd.visibility = if (nm.isNotificationPolicyAccessGranted) View.GONE else View.VISIBLE
    }

    private fun requestCapture() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.RECORD_AUDIO), PERM_REQUEST)
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
            cardBroadcast.visibility = View.GONE
            cardStop.visibility = View.VISIBLE
            cardPause.visibility = View.VISIBLE
            isPaused = false
            updatePauseCard()
        }
    }

    private fun stopHosting() {
        stopService(Intent(this, HostService::class.java))
        cardBroadcast.visibility = View.VISIBLE
        cardStop.visibility = View.GONE
        cardPause.visibility = View.GONE
        tvConnected.text = "Нажми чтобы остановить"
        isPaused = false
    }

    private fun togglePause() {
        isPaused = !isPaused
        val action = if (isPaused) HostService.ACTION_PAUSE else HostService.ACTION_RESUME
        startService(Intent(this, HostService::class.java).setAction(action))
        updatePauseCard()
    }

    private fun updatePauseCard() {
        val tv = cardPause.findViewById<TextView>(R.id.tvPauseLabel)
        tv?.text = if (isPaused) "Возобновить трансляцию" else "Пауза трансляции"
    }

    private fun setupWifiDirect() {
        try {
            wifiDirectHelper = WifiDirectHelper(
                context = this,
                onPeersChanged = { peers ->
                    runOnUiThread { p2pPeers.clear(); p2pPeers.addAll(peers); refreshDeviceList() }
                },
                onGroupOwnerIp = { ip ->
                    runOnUiThread {
                        startActivity(Intent(this, ViewerActivity::class.java).apply {
                            putExtra(ViewerActivity.EXTRA_HOST_IP, ip)
                            putExtra(ViewerActivity.EXTRA_HOST_PORT, HostService.STREAM_PORT)
                            putExtra(ViewerActivity.EXTRA_DEVICE_NAME, ip)
                        })
                    }
                },
                onError = { msg -> runOnUiThread { tvDiscovery.text = msg } }
            )
            wifiDirectHelper?.register()
        } catch (e: Exception) { }
    }

    private fun startDiscovery() {
        nsdHelper.discoverServices(
            onFound = { info ->
                runOnUiThread { nsdDevices[info.serviceName] = info; refreshDeviceList() }
            },
            onLost = { name ->
                runOnUiThread { nsdDevices.remove(name); refreshDeviceList() }
            }
        )
        refreshDeviceList()
    }

    private fun refreshDeviceList() {
        layoutDevices.removeAllViews()
        val history = loadHistory()
        if (history.isNotEmpty()) {
            val tvHistory = TextView(this)
            tvHistory.text = "Недавние:"
            tvHistory.textSize = 13f
            tvHistory.setTextColor(0xFF9090C0.toInt())
            tvHistory.setPadding(0, 0, 0, 8)
            layoutDevices.addView(tvHistory)
            for (entry in history) {
                val ip = entry.optString("ip", "") ?: continue
                val name = entry.optString("name", ip) ?: ip
                if (ip.isEmpty()) continue
                val btn = layoutInflater.inflate(R.layout.item_device, layoutDevices, false)
                btn.findViewById<TextView>(R.id.tvDeviceIp).text = "$name (недавний)"
                btn.setOnClickListener {
                    startActivity(Intent(this, ViewerActivity::class.java).apply {
                        putExtra(ViewerActivity.EXTRA_HOST_IP, ip)
                        putExtra(ViewerActivity.EXTRA_HOST_PORT, HostService.STREAM_PORT)
                        putExtra(ViewerActivity.EXTRA_DEVICE_NAME, name)
                    })
                }
                layoutDevices.addView(btn)
            }
        }

        val totalDevices = nsdDevices.size + p2pPeers.size
        if (totalDevices == 0 && history.isEmpty()) {
            tvDiscovery.text = "Ищем устройства в сети Wi-Fi..."
            return
        }
        tvDiscovery.text = "Найдено устройств: $totalDevices"

        for ((_, info) in nsdDevices) {
            val ip = info.host?.hostAddress ?: continue
            val btn = layoutInflater.inflate(R.layout.item_device, layoutDevices, false)
            btn.findViewById<TextView>(R.id.tvDeviceIp).text = ip
            btn.setOnClickListener {
                saveToHistory(ip, ip)
                startActivity(Intent(this, ViewerActivity::class.java).apply {
                    putExtra(ViewerActivity.EXTRA_HOST_IP, ip)
                    putExtra(ViewerActivity.EXTRA_HOST_PORT, info.port)
                    putExtra(ViewerActivity.EXTRA_DEVICE_NAME, ip)
                })
            }
            layoutDevices.addView(btn)
        }

        for (peer in p2pPeers) {
            val btn = layoutInflater.inflate(R.layout.item_device, layoutDevices, false)
            btn.findViewById<TextView>(R.id.tvDeviceIp).text = "${peer.deviceName} [P2P]"
            btn.setOnClickListener { wifiDirectHelper?.connectToPeer(peer) }
            layoutDevices.addView(btn)
        }
    }

    fun saveToHistory(ip: String, name: String) {
        val arr = loadHistoryRaw()
        val newEntry = JSONObject().apply {
            put("ip", ip)
            put("name", name)
            put("ts", System.currentTimeMillis())
        }
        val filtered = JSONArray()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            if (obj.optString("ip") != ip) filtered.put(obj)
        }
        val result = JSONArray()
        result.put(newEntry)
        for (i in 0 until minOf(filtered.length(), MAX_HISTORY - 1)) {
            result.put(filtered.get(i))
        }
        prefs.edit().putString(KEY_HISTORY, result.toString()).apply()
    }

    private fun loadHistoryRaw(): JSONArray {
        return try {
            JSONArray(prefs.getString(KEY_HISTORY, "[]") ?: "[]")
        } catch (e: Exception) { JSONArray() }
    }

    private fun loadHistory(): List<JSONObject> {
        val arr = loadHistoryRaw()
        val list = mutableListOf<JSONObject>()
        for (i in 0 until arr.length()) {
            arr.optJSONObject(i)?.let { list.add(it) }
        }
        return list
    }

    override fun onDestroy() {
        super.onDestroy()
        nsdHelper.stopDiscovery()
        nsdHelper.releaseMulticastLock()
        wifiDirectHelper?.unregister()
    }
}
