package com.prisonguard.device.network

import android.util.Log
import com.google.gson.Gson
import com.prisonguard.device.Config
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException

object ApiClient {
    private const val TAG = "ApiClient"
    private val client = OkHttpClient()
    private val gson = Gson()
    private val JSON = "application/json; charset=utf-8".toMediaType()

    // ─── 设备注册 ─────────────────────────────────────────────────────────────

    fun registerDevice(deviceId: String, model: String) {
        val body = gson.toJson(mapOf(
            "device_id" to deviceId,
            "name" to model,
            "prisoner_name" to null
        )).toRequestBody(JSON)
        post("/api/devices/register", body)
    }

    // ─── 定位上报 ─────────────────────────────────────────────────────────────

    fun reportLocation(
        deviceId: String, lat: Double, lng: Double,
        accuracy: Float?, speed: Float?
    ) {
        Log.i(TAG, "上报定位: deviceId=$deviceId lat=$lat lng=$lng accuracy=$accuracy")
        val body = gson.toJson(mapOf(
            "device_id" to deviceId,
            "lat" to lat,
            "lng" to lng,
            "accuracy" to accuracy,
            "speed" to speed,
            "timestamp" to (System.currentTimeMillis() / 1000)
        )).toRequestBody(JSON)
        post("/api/location", body)
    }

    // ─── 照片上传 ─────────────────────────────────────────────────────────────

    fun uploadPhoto(deviceId: String, file: File) {
        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file", file.name,
                file.asRequestBody("image/jpeg".toMediaType())
            )
            .build()
        val request = Request.Builder()
            .url("${Config.SERVER_URL}/api/photo/upload/$deviceId")
            .post(requestBody)
            .build()
        try {
            client.newCall(request).execute().use { response ->
                Log.i(TAG, "照片上传: ${response.code}")
            }
        } catch (e: IOException) {
            Log.e(TAG, "照片上传失败", e)
        }
    }

    // ─── 音频上传 ─────────────────────────────────────────────────────────────

    fun uploadAudio(deviceId: String, file: File) {
        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file", file.name,
                file.asRequestBody("audio/aac".toMediaType())
            )
            .build()
        val request = Request.Builder()
            .url("${Config.SERVER_URL}/api/audio/upload/$deviceId")
            .post(requestBody)
            .build()
        try {
            client.newCall(request).execute().use { response ->
                Log.i(TAG, "音频上传: ${response.code}")
            }
        } catch (e: IOException) {
            Log.e(TAG, "音频上传失败", e)
        }
    }

    // ─── 通用 POST ────────────────────────────────────────────────────────────

    private fun post(path: String, body: RequestBody) {
        val request = Request.Builder()
            .url("${Config.SERVER_URL}$path")
            .post(body)
            .build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "POST $path 失败: ${response.code}")
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "POST $path 网络错误", e)
        }
    }
}
