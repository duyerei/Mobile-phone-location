package com.prisonguard.device.service

import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.*
import okhttp3.*

class WebSocketClient(
    private val url: String,
    private val onCommand: (Map<String, Any>) -> Unit,
    private val onReconnect: () -> Unit
) {
    companion object { private const val TAG = "WSClient" }

    private val client = OkHttpClient()
    private val gson = Gson()
    private var ws: WebSocket? = null
    private var reconnectJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    fun connect() {
        val request = Request.Builder().url(url).build()
        Log.i(TAG, "正在连接 WebSocket: $url")
        ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "WebSocket 已连接: $url")
                reconnectJob?.cancel()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val type = object : TypeToken<Map<String, Any>>() {}.type
                    val cmd: Map<String, Any> = gson.fromJson(text, type)
                    onCommand(cmd)
                } catch (e: Exception) {
                    Log.e(TAG, "处理指令失败: $text", e)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "WebSocket 连接失败: ${t.message}，5秒后重连")
                scheduleReconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WebSocket 关闭: $reason，5秒后重连")
                scheduleReconnect()
            }
        })
    }

    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(5000)
            onReconnect()
        }
    }

    fun disconnect() {
        reconnectJob?.cancel()
        ws?.close(1000, "service stopped")
        scope.cancel()
    }
}
