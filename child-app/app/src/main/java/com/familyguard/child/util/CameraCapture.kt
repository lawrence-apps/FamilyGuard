package com.familyguard.child.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.coroutines.resume

object CameraCapture {

    suspend fun take(ctx: Context, front: Boolean): File? = withTimeoutOrNull(15_000) {
        runCatching {
            if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                return@runCatching null
            }
            val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val facing = if (front) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
            val id = cm.cameraIdList.firstOrNull { cid ->
                runCatching {
                    cm.getCameraCharacteristics(cid).get(CameraCharacteristics.LENS_FACING) == facing
                }.getOrDefault(false)
            } ?: return@runCatching null
            val chars = cm.getCameraCharacteristics(id)
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?: return@runCatching null
            val size = map.getOutputSizes(ImageFormat.JPEG).minByOrNull {
                Math.abs(it.width * it.height - 1280 * 720)
            } ?: return@runCatching null

            val reader = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 1)
            var device: CameraDevice? = null
            var session: CameraCaptureSession? = null
            try {
                device = suspendCancellableCoroutine<CameraDevice?> { cont ->
                    try {
                        cm.openCamera(id, object : CameraDevice.StateCallback() {
                            override fun onOpened(d: CameraDevice) {
                                if (cont.isActive) cont.resume(d)
                            }

                            override fun onDisconnected(d: CameraDevice) {
                                d.close()
                                if (cont.isActive) cont.resume(null)
                            }

                            override fun onError(d: CameraDevice, error: Int) {
                                d.close()
                                if (cont.isActive) cont.resume(null)
                            }
                        }, Handler(Looper.getMainLooper()))
                    } catch (_: Exception) {
                        if (cont.isActive) cont.resume(null)
                    }
                } ?: return@runCatching null

                session = suspendCancellableCoroutine<CameraCaptureSession?> { cont ->
                    try {
                        device.createCaptureSession(
                            listOf(reader.surface),
                            object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(s: CameraCaptureSession) {
                                    if (cont.isActive) cont.resume(s)
                                }

                                override fun onConfigureFailed(s: CameraCaptureSession) {
                                    if (cont.isActive) cont.resume(null)
                                }
                            },
                            Handler(Looper.getMainLooper())
                        )
                    } catch (_: Exception) {
                        if (cont.isActive) cont.resume(null)
                    }
                } ?: return@runCatching null

                val orientation = getOrientation(chars, front)
                val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                builder.addTarget(reader.surface)
                builder.set(CaptureRequest.JPEG_ORIENTATION, orientation)
                session.capture(builder.build(), null, null)

                val image = withTimeoutOrNull(8_000) {
                    suspendCancellableCoroutine<Image?> { cont ->
                        reader.setOnImageAvailableListener({ r ->
                            val img = r.acquireLatestImage()
                            if (img != null && cont.isActive) cont.resume(img)
                        }, Handler(Looper.getMainLooper()))
                    }
                } ?: return@runCatching null

                val file = saveJpeg(ctx, image, orientation)
                runCatching { image.close() }
                file
            } finally {
                runCatching { session?.close() }
                runCatching { device?.close() }
                runCatching { reader.close() }
            }
        }.getOrNull()
    }

    private fun getOrientation(chars: CameraCharacteristics, front: Boolean): Int {
        val sensor = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        return if (front) (sensor + 360) % 360 else (360 - sensor + 360) % 360
    }

    private fun saveJpeg(ctx: Context, image: Image, orientation: Int): File? = runCatching {
        val buf = image.planes[0].buffer
        val bytes = ByteArray(buf.remaining())
        buf.get(bytes)
        var bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@runCatching null
        if (orientation != 0) {
            val m = Matrix()
            m.postRotate(orientation.toFloat())
            val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            if (rotated != bmp) bmp.recycle()
            bmp = rotated
        }
        val bos = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 85, bos)
        bmp.recycle()
        val file = File(ctx.cacheDir, "cam_${System.currentTimeMillis()}.jpg")
        file.outputStream().use { it.write(bos.toByteArray()) }
        file
    }.getOrNull()
}
