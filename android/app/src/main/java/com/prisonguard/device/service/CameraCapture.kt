package com.prisonguard.device.service

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.io.File
import java.io.FileOutputStream

class CameraCapture(private val context: Context) {

    companion object {
        private const val TAG = "CameraCapture"
        private const val WIDTH = 1280
        private const val HEIGHT = 960
    }

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val handlerThread = HandlerThread("CameraThread").also { it.start() }
    private val handler = Handler(handlerThread.looper)

    @SuppressLint("MissingPermission")
    fun capture(outputFile: File, facing: String = "back", onDone: (File?) -> Unit) {
        val cameraId = selectCamera(facing) ?: run {
            Log.e(TAG, "没有可用摄像头 facing=$facing")
            onDone(null)
            return
        }

        Log.i(TAG, "使用摄像头: $cameraId")

        val imageReader = ImageReader.newInstance(WIDTH, HEIGHT, ImageFormat.JPEG, 2)
        var imageSaved = false

        imageReader.setOnImageAvailableListener({ reader ->
            if (imageSaved) return@setOnImageAvailableListener
            imageSaved = true
            val image = reader.acquireLatestImage()
            if (image == null) {
                Log.e(TAG, "acquireLatestImage 返回 null")
                onDone(null)
                return@setOnImageAvailableListener
            }
            try {
                val buffer = image.planes[0].buffer
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                if (bytes.isEmpty()) {
                    Log.e(TAG, "图片数据为空")
                    onDone(null)
                    return@setOnImageAvailableListener
                }
                FileOutputStream(outputFile).use { it.write(bytes) }
                Log.i(TAG, "拍照成功: ${outputFile.absolutePath} size=${bytes.size}bytes")
                onDone(outputFile)
            } catch (e: Exception) {
                Log.e(TAG, "保存照片失败", e)
                onDone(null)
            } finally {
                image.close()
            }
        }, handler)

        cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                Log.i(TAG, "摄像头已打开")
                try {
                    val surfaces = listOf(imageReader.surface)

                    camera.createCaptureSession(surfaces,
                        object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(session: CameraCaptureSession) {
                                Log.i(TAG, "CaptureSession 已配置，开始拍照")
                                try {
                                    val captureRequest = camera.createCaptureRequest(
                                        CameraDevice.TEMPLATE_STILL_CAPTURE
                                    ).apply {
                                        addTarget(imageReader.surface)
                                        set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                                        set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
                                        set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                                        set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                                        set(CaptureRequest.JPEG_QUALITY, 85.toByte())
                                        set(CaptureRequest.JPEG_ORIENTATION, 0)
                                    }.build()

                                    session.capture(captureRequest,
                                        object : CameraCaptureSession.CaptureCallback() {
                                            override fun onCaptureCompleted(
                                                s: CameraCaptureSession,
                                                r: CaptureRequest,
                                                result: TotalCaptureResult
                                            ) {
                                                Log.i(TAG, "Capture completed")
                                                handler.postDelayed({
                                                    session.close()
                                                    camera.close()
                                                    imageReader.close()
                                                }, 500)
                                            }

                                            override fun onCaptureFailed(
                                                s: CameraCaptureSession,
                                                r: CaptureRequest,
                                                failure: CaptureFailure
                                            ) {
                                                Log.e(TAG, "Capture failed reason=${failure.reason}")
                                                session.close()
                                                camera.close()
                                                imageReader.close()
                                                if (!imageSaved) onDone(null)
                                            }
                                        }, handler)
                                } catch (e: Exception) {
                                    Log.e(TAG, "发起拍照请求失败", e)
                                    session.close()
                                    camera.close()
                                    imageReader.close()
                                    if (!imageSaved) onDone(null)
                                }
                            }

                            override fun onConfigureFailed(session: CameraCaptureSession) {
                                Log.e(TAG, "CaptureSession 配置失败")
                                camera.close()
                                imageReader.close()
                                onDone(null)
                            }
                        }, handler)
                } catch (e: Exception) {
                    Log.e(TAG, "创建 CaptureSession 失败", e)
                    camera.close()
                    imageReader.close()
                    onDone(null)
                }
            }

            override fun onDisconnected(camera: CameraDevice) {
                Log.w(TAG, "摄像头断开")
                camera.close()
                imageReader.close()
                onDone(null)
            }

            override fun onError(camera: CameraDevice, error: Int) {
                val errMsg = when (error) {
                    ERROR_CAMERA_IN_USE -> "摄像头被占用"
                    ERROR_MAX_CAMERAS_IN_USE -> "摄像头数量超限"
                    ERROR_CAMERA_DISABLED -> "摄像头被禁用"
                    ERROR_CAMERA_DEVICE -> "摄像头设备错误"
                    ERROR_CAMERA_SERVICE -> "摄像头服务错误"
                    else -> "未知错误($error)"
                }
                Log.e(TAG, "摄像头错误: $errMsg")
                camera.close()
                imageReader.close()
                onDone(null)
            }
        }, handler)
    }

    private fun selectCamera(facing: String = "back"): String? {
        val targetFacing = if (facing == "front")
            CameraCharacteristics.LENS_FACING_FRONT
        else
            CameraCharacteristics.LENS_FACING_BACK

        // 先找目标方向的非逻辑摄像头
        for (id in cameraManager.cameraIdList) {
            try {
                val chars = cameraManager.getCameraCharacteristics(id)
                val lensFacing = chars.get(CameraCharacteristics.LENS_FACING)
                val caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: continue
                val isLogical = caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA)
                if (lensFacing == targetFacing && !isLogical) {
                    Log.i(TAG, "选择${if (facing == "front") "前置" else "后置"}摄像头: $id")
                    return id
                }
            } catch (e: Exception) {
                Log.w(TAG, "读取摄像头 $id 特性失败", e)
            }
        }
        // 找不到目标方向，退而求其次用任意摄像头
        return cameraManager.cameraIdList.firstOrNull()?.also {
            Log.w(TAG, "未找到${facing}摄像头，使用第一个可用摄像头: $it")
        }
    }

    fun release() {
        handlerThread.quitSafely()
    }
}
