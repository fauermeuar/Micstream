package com.taptap.micstream

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.Log
import androidx.core.app.NotificationCompat
import com.arthenica.ffmpegkit.FFmpegKit
import java.io.File
import java.io.FileDescriptor

/**
 * Foreground service: tangkap MIC + AUDIO INTERNAL (via MediaProjection),
 * campur jadi satu, kirim sebagai SRT (AAC dalam MPEG-TS) ke host:port.
 * OBS tinggal pasang Media Source mode listener seperti biasa.
 */
class StreamService : Service() {

    companion object {
        const val ACTION_START = "com.taptap.micstream.START"
        const val ACTION_STATUS = "com.taptap.micstream.STATUS"
        const val EXTRA_MSG = "msg"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_DATA = "data"
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
        const val EXTRA_LATENCY = "latency"
        private const val CHANNEL_ID = "micstream"
        private const val NOTIF_ID = 1
        private const val TAG = "MicStream"
        private const val SAMPLE_RATE = 48000

        // 40 ms per chunk = 1920 sampel = 3840 byte (< PIPE_BUF 4096, jadi write ke FIFO atomik)
        private const val CHUNK_SAMPLES = SAMPLE_RATE / 25

        /** true selama pipeline (termasuk cleanup) masih hidup. Dipakai UI. */
        @Volatile var isRunning = false
        @Volatile var lastStatus = ""
    }

    /** Flag loop; false = minta pipeline berhenti. */
    @Volatile private var active = false
    @Volatile private var failMsg: String? = null
    private var worker: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != ACTION_START) {
            stopSelf()
            return START_NOT_STICKY
        }
        val host = intent.getStringExtra(EXTRA_HOST).orEmpty().trim()
        val port = intent.getIntExtra(EXTRA_PORT, 8888)

        // Wajib dipanggil segera setelah startForegroundService(), apa pun yang terjadi
        // setelahnya, kalau tidak Android melempar ForegroundServiceDidNotStartInTimeException.
        enterForeground(host, port)

        if (isRunning) return START_NOT_STICKY // sudah jalan, abaikan START ganda

        val rc = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val data: Intent? = getDataExtra(intent)
        val latency = intent.getIntExtra(EXTRA_LATENCY, 1200)
        if (rc != Activity.RESULT_OK || data == null || host.isEmpty()) {
            Log.w(TAG, "start dibatalkan: izin/data/host tidak valid")
            postStatus("Gagal start: izin/data/host tidak valid.")
            stopSelf()
            return START_NOT_STICKY
        }

        failMsg = null
        isRunning = true
        active = true
        worker = Thread {
            try {
                runPipeline(rc, data, host, port, latency)
            } catch (e: Throwable) {
                Log.e(TAG, "pipeline gagal", e)
                failMsg = failMsg ?: "Error: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                active = false
                isRunning = false
                postStatus(failMsg ?: "Berhenti.")
                stopSelf()
            }
        }.also { it.start() }
        return START_NOT_STICKY
    }

    private fun getDataExtra(intent: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= 33)
            intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        else
            @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_DATA)

    private fun enterForeground(host: String, port: Int) {
        createChannel()
        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MicStream aktif")
            .setContentText("Mic + audio internal -> $host:$port")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 30) {
            // Android 14+: tipe microphone WAJIB agar mic tidak di-silent / SecurityException.
            startForeground(
                NOTIF_ID, notif,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        }
    }

    private fun supportsSrt(): Boolean = try {
        val s = FFmpegKit.execute("-hide_banner -protocols")
        Regex("(?m)^\\s*srt\\s*$").containsMatchIn(s.output ?: "")
    } catch (e: Exception) {
        true // tidak bisa dicek -> biarkan ffmpeg yang melapor
    }

    private fun runPipeline(rc: Int, data: Intent, host: String, port: Int, latency: Int) {
        var proj: MediaProjection? = null
        var internalRec: AudioRecord? = null
        var micRec: AudioRecord? = null
        var pipeFd: FileDescriptor? = null
        val fifo = File(filesDir, "audio.pcm")

        try {
            if (!supportsSrt()) {
                failMsg = "Build FFmpeg ini tidak punya protokol SRT. Ganti library FFmpeg " +
                    "(lihat README, bagian 'FFmpeg & SRT')."
                return
            }

            val pm = getSystemService(MediaProjectionManager::class.java)
            val mp = pm.getMediaProjection(rc, data)
            if (mp == null) {
                failMsg = "Gagal membuat MediaProjection."
                return
            }
            proj = mp
            // Android 14+: callback harus terdaftar sebelum capture dimulai.
            mp.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    failMsg = failMsg ?: "Screen capture dihentikan sistem."
                    active = false
                }
            }, Handler(Looper.getMainLooper()))

            val fmt = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .build()
            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            val bufSize = maxOf(minBuf * 4, CHUNK_SAMPLES * 2 * 4)

            // 1) Audio internal via MediaProjection (TTS Chrome, musik, dll)
            val capCfg = AudioPlaybackCaptureConfiguration.Builder(mp)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                .build()
            val ir = AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(capCfg)
                .setAudioFormat(fmt)
                .setBufferSizeInBytes(bufSize)
                .build()
            internalRec = ir

            // 2) Mic
            val mr = AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.MIC)
                .setAudioFormat(fmt)
                .setBufferSizeInBytes(bufSize)
                .build()
            micRec = mr

            if (ir.state != AudioRecord.STATE_INITIALIZED || mr.state != AudioRecord.STATE_INITIALIZED) {
                failMsg = "Gagal inisialisasi AudioRecord (mic/audio internal)."
                return
            }

            // 3) FIFO: jembatan PCM mentah -> ffmpeg (encode AAC + publish SRT).
            //    Dibuka O_RDWR|O_NONBLOCK: tidak nyangkut menunggu ffmpeg membuka ujung baca,
            //    dan kalau ffmpeg tertinggal/mati, chunk dibuang (bukan memblokir selamanya).
            if (fifo.exists()) fifo.delete()
            Os.mkfifo(fifo.absolutePath, 384) // 0600
            val fd = Os.open(fifo.absolutePath, OsConstants.O_RDWR or OsConstants.O_NONBLOCK, 0)
            pipeFd = fd

            val url = "srt://$host:$port?mode=caller&latency=$latency"
            val args = arrayOf(
                "-hide_banner",
                "-fflags", "nobuffer",
                "-f", "s16le", "-ar", "$SAMPLE_RATE", "-ac", "1",
                "-i", fifo.absolutePath,
                "-c:a", "aac", "-b:a", "128k",
                "-flush_packets", "1",
                "-f", "mpegts", url
            )
            Log.i(TAG, "ffmpeg: ${args.joinToString(" ")}")
            FFmpegKit.executeWithArgumentsAsync(args) { session ->
                Log.i(TAG, "ffmpeg selesai rc=${session.returnCode}")
                if (active) { // berhenti sendiri (SRT gagal/putus), bukan karena user STOP
                    val tail = (session.allLogsAsString ?: "").trim().takeLast(200)
                    failMsg = "Koneksi SRT gagal/putus (${session.returnCode}). $tail"
                    active = false
                }
            }

            ir.startRecording()
            mr.startRecording()
            postStatus("STREAMING ke $host:$port")

            val micBuf = ShortArray(CHUNK_SAMPLES)
            val intBuf = ShortArray(CHUNK_SAMPLES)
            val mixed = ByteArray(CHUNK_SAMPLES * 2)

            while (active) {
                val nMic = mr.read(micBuf, 0, micBuf.size, AudioRecord.READ_BLOCKING)
                val nInt = ir.read(intBuf, 0, intBuf.size, AudioRecord.READ_BLOCKING)
                if (nMic < 0 || nInt < 0) {
                    failMsg = "Gagal membaca audio (kode $nMic/$nInt)."
                    break
                }
                val n = minOf(nMic, nInt)
                if (n == 0) continue
                var j = 0
                for (i in 0 until n) {
                    var s = micBuf[i].toInt() + intBuf[i].toInt()
                    if (s > 32767) s = 32767 else if (s < -32768) s = -32768
                    mixed[j++] = (s and 0xFF).toByte()
                    mixed[j++] = ((s shr 8) and 0xFF).toByte()
                }
                try {
                    Os.write(fd, mixed, 0, n * 2)
                } catch (e: ErrnoException) {
                    if (e.errno != OsConstants.EAGAIN) throw e // EAGAIN = FIFO penuh, buang chunk
                }
            }
        } finally {
            active = false
            runCatching { micRec?.stop() }
            runCatching { micRec?.release() }
            runCatching { internalRec?.stop() }
            runCatching { internalRec?.release() }
            runCatching { FFmpegKit.cancel() }
            pipeFd?.let { runCatching { Os.close(it) } }
            runCatching { proj?.stop() }
            runCatching { fifo.delete() }
            Log.i(TAG, "pipeline berhenti")
        }
    }

    private fun postStatus(msg: String) {
        lastStatus = msg
        sendBroadcast(Intent(ACTION_STATUS).setPackage(packageName).putExtra(EXTRA_MSG, msg))
    }

    override fun onDestroy() {
        // Jangan join() di main thread (bisa ANR); worker membereskan dirinya sendiri.
        active = false
        super.onDestroy()
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Streaming", NotificationManager.IMPORTANCE_LOW)
        )
    }
}
