package com.taptap.micstream

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private data class Config(val host: String, val port: Int, val latency: Int)

    private lateinit var etHost: EditText
    private lateinit var etPort: EditText
    private lateinit var etLatency: EditText
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var tvStatus: TextView
    private var pending: Config? = null

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            updateUi(intent?.getStringExtra(StreamService.EXTRA_MSG).orEmpty())
        }
    }

    private val projLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            val cfg = pending
            if (cfg != null && res.resultCode == Activity.RESULT_OK && res.data != null) {
                getPreferences(MODE_PRIVATE).edit()
                    .putString("host", cfg.host).putInt("port", cfg.port)
                    .putInt("latency", cfg.latency).apply()
                val svc = Intent(this, StreamService::class.java).apply {
                    action = StreamService.ACTION_START
                    putExtra(StreamService.EXTRA_RESULT_CODE, res.resultCode)
                    putExtra(StreamService.EXTRA_DATA, res.data)
                    putExtra(StreamService.EXTRA_HOST, cfg.host)
                    putExtra(StreamService.EXTRA_PORT, cfg.port)
                    putExtra(StreamService.EXTRA_LATENCY, cfg.latency)
                }
                ContextCompat.startForegroundService(this, svc)
                // Service baru menandai isRunning sesaat lagi; kunci tombol dulu supaya tak bisa START ganda.
                tvStatus.text = "Menghubungkan ke ${cfg.host}:${cfg.port} ..."
                btnStart.isEnabled = false
                btnStop.isEnabled = true
            } else {
                updateUi("Izin screen capture ditolak.")
            }
        }

    private val micPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) requestProjection() else updateUi("Izin mic ditolak.")
        }

    // Tanpa izin ini notifikasi foreground service tersembunyi di Android 13+.
    // Streaming tetap jalan walau ditolak, jadi lanjut ke langkah berikutnya apa pun hasilnya.
    private val notifPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            requestMicThenProjection()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etHost = findViewById(R.id.etHost)
        etPort = findViewById(R.id.etPort)
        etLatency = findViewById(R.id.etLatency)
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        tvStatus = findViewById(R.id.tvStatus)

        val p = getPreferences(MODE_PRIVATE)
        etHost.setText(p.getString("host", ""))
        etPort.setText(p.getInt("port", 8888).toString())
        etLatency.setText(p.getInt("latency", 1200).toString())

        btnStart.setOnClickListener { onStartClicked() }
        btnStop.setOnClickListener {
            stopService(Intent(this, StreamService::class.java))
            updateUi("Menghentikan ...")
        }
        updateUi(
            if (StreamService.isRunning && StreamService.lastStatus.isNotEmpty())
                StreamService.lastStatus
            else "Siap. Isi IP VPS lalu START."
        )
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(
            this, statusReceiver, IntentFilter(StreamService.ACTION_STATUS),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        // Sinkronkan UI (mis. setelah rotasi / kembali dari background).
        updateUi(
            if (StreamService.lastStatus.isNotEmpty()) StreamService.lastStatus
            else tvStatus.text.toString()
        )
    }

    override fun onStop() {
        unregisterReceiver(statusReceiver)
        super.onStop()
    }

    private fun readConfig(): Config? {
        val host = etHost.text.toString().trim().removePrefix("srt://").trimEnd('/')
        if (host.isEmpty()) {
            updateUi("Isi IP/host VPS dulu.")
            return null
        }
        val port = etPort.text.toString().trim().toIntOrNull()
        if (port == null || port !in 1..65535) {
            updateUi("Port SRT tidak valid (1-65535).")
            return null
        }
        val latency = (etLatency.text.toString().trim().toIntOrNull() ?: 1200).coerceIn(20, 10000)
        return Config(host, port, latency)
    }

    private fun onStartClicked() {
        if (StreamService.isRunning) return
        pending = readConfig() ?: return
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            requestMicThenProjection()
        }
    }

    private fun requestMicThenProjection() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            micPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            requestProjection()
        }
    }

    private fun requestProjection() {
        val pm = getSystemService(MediaProjectionManager::class.java)
        projLauncher.launch(pm.createScreenCaptureIntent())
    }

    private fun updateUi(s: String) {
        tvStatus.text = s
        val running = StreamService.isRunning
        btnStart.isEnabled = !running
        btnStop.isEnabled = running
    }
}
