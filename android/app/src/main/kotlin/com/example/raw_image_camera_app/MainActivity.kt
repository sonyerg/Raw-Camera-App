package com.example.raw_image_camera_app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.media.Image
import android.media.ImageReader
import android.media.MediaScannerConnection
import android.os.Environment
import android.util.Log
import android.util.Size
import androidx.annotation.NonNull
import androidx.core.app.ActivityCompat
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodChannel
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Semaphore

class MainActivity: FlutterActivity() {
    private val METHOD_CHANNEL_NAME = "com.example.raw_image_camera_app/camera2"

    private var methodChannel: MethodChannel? = null
    private lateinit var cameraManager: CameraManager

    private val imageCaptureSemaphore = Semaphore(1)

    private var cameraReady = false

    private lateinit var captureResult: TotalCaptureResult


    override fun configureFlutterEngine(@NonNull flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        //setup channels
        setupChannels(this, flutterEngine.dartExecutor.binaryMessenger)
    }

    override fun onDestroy() {
        cameraDevice?.close()
        captureSession?.close()
        teardownChannels()
        super.onDestroy()
    }

    private fun setupChannels(context: Context, messenger: BinaryMessenger) {
        cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

        methodChannel = MethodChannel(messenger, METHOD_CHANNEL_NAME)

        methodChannel!!.setMethodCallHandler { call, result ->
            when (call.method) {
                "captureImage" -> {
                    val cameraId = call.argument<String>("cameraId")
                    if (cameraId != null) {
                        captureImage(cameraId, result)
                    } else {
                        result.error("INVALID_CAMERA_ID", "Camera ID is null or invalid", null)
                    }
                }
                else -> {
                    result.notImplemented()
                }
            }
        }
    }

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null


    private fun saveImage(
        image: Image?,
        captureResult: CaptureResult,
        characteristics: CameraCharacteristics,
        result: MethodChannel.Result
    ) {
        Log.i("MainActivity", "saveImage called")
        if (image == null) {
            Log.e("MainActivity", "Image is null")
            result.error("NULL_IMAGE", "Image is null", null)
            return
        }
        var fos: FileOutputStream? = null

        try {
            val timeStamp = SimpleDateFormat(
                "yyyyMMdd_HHmmss",
                Locale.getDefault()
            ).format(Date())
            val imageFileName = "IMG_$timeStamp.dng"
            val storageDir = getExternalFilesDir(Environment.DIRECTORY_PICTURES)
            val imageFile = File(storageDir, imageFileName)

            fos = FileOutputStream(imageFile)
            val dngCreator = DngCreator(characteristics, captureResult)
            if (image != null) {
                dngCreator.writeImage(fos, image)
            }

            MediaScannerConnection.scanFile(this@MainActivity, arrayOf(imageFile.toString()), null) { _, _ ->
                Log.i("MainActivity", "Image added to gallery: ${imageFile.absolutePath}")
            }

            result.success(imageFile.absolutePath) // Send the image path back to Flutter

        } catch (e: IOException) {
            result.error("IO_ERROR", "Failed to save image", e.message)
        } finally {
            image.close()
            fos?.close()
            imageCaptureSemaphore.release()
        }
    }


    private fun captureImage(cameraId: String, result: MethodChannel.Result) {
        try {
            imageCaptureSemaphore.acquire()
            cameraReady = true // Set cameraReady to true here
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val rawSizes = map?.getOutputSizes(ImageFormat.RAW_SENSOR)
            Log.i("MainActivity", "RAW_SENSOR supported sizes: ${Arrays.toString(rawSizes)}")
            val jpegSizes = map?.getOutputSizes(ImageFormat.JPEG)
            Log.i("MainActivity", "JPEG supported sizes: ${Arrays.toString(jpegSizes)}")
            val largestImageSize = map?.let { listOf(*it.getOutputSizes(ImageFormat.RAW_SENSOR)) }?.let {
                Collections.max(
                    it,
                    CompareSizesByArea()
                )
            }
            val reader = largestImageSize?.let {
                ImageReader.newInstance(
                    it.width,
                    largestImageSize.height,
                    ImageFormat.RAW_SENSOR,
                    1
                )
            }
            if (!cameraReady) {
                result.error("CAMERA_NOT_READY", "Camera is not ready", null)
                return
            }
            if (ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.CAMERA
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                // TODO: Consider calling
                //    ActivityCompat#requestPermissions
                // here to request the missing permissions, and then overriding
                //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
                //                                          int[] grantResults)
                // to handle the case where the user grants the permission. See the documentation
                // for ActivityCompat#requestPermissions for more details.
                return
            }

            reader?.setOnImageAvailableListener({ r ->
                val image = r.acquireLatestImage()
                // Save the image when it becomes available
                saveImage(image, captureResult, characteristics, result)
            }, null)
            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(@NonNull camera: CameraDevice) {
                    Log.i("MainActivity", "Camera opened")
                    cameraDevice = camera

                    val captureRequestBuilder =
                        camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                    if (reader != null) {
                        captureRequestBuilder.addTarget(reader.surface)
                    }
                    captureRequestBuilder.set(
                        CaptureRequest.CONTROL_MODE,
                        CameraMetadata.CONTROL_MODE_AUTO
                    )
                    captureRequestBuilder.set(
                        CaptureRequest.CONTROL_AF_MODE,
                        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                    )

                    if (reader != null) {
                        camera.createCaptureSession(
                            listOf(reader.surface),
                            object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(@NonNull session: CameraCaptureSession) {
                                    Log.i("MainActivity", "CameraCaptureSession configured")
                                    captureSession = session
                                    val captureRequest = captureRequestBuilder.build()

                                    session.capture(
                                        captureRequest,
                                        object : CameraCaptureSession.CaptureCallback() {
                                            override fun onCaptureCompleted(
                                                session: CameraCaptureSession,
                                                request: CaptureRequest,
                                                totalCaptureResult: TotalCaptureResult
                                            ) {
                                                super.onCaptureCompleted(session, request, totalCaptureResult)
                                                captureResult = totalCaptureResult
                                            }
                                        },
                                        null
                                    )

                                }
                                override fun onConfigureFailed(session: CameraCaptureSession) {
                                    result.error(
                                        "CAPTURE_SESSION_ERROR",
                                        "Failed to configure capture session",
                                        null
                                    )
                                }
                            },
                            null
                        )
                    }
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    cameraDevice = null
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    cameraDevice = null
                    result.error("CAMERA_ERROR", "Camera error: $error", null)
                }
            }, null)
        } catch (e: CameraAccessException) {
            result.error("CAMERA_ACCESS_ERROR", "Failed to access camera", e.message)
        }
        finally {
            imageCaptureSemaphore.release()
        }
    }

    class CompareSizesByArea : Comparator<Size> {
        override fun compare(lhs: Size, rhs: Size): Int {
            return java.lang.Long.signum(lhs.width.toLong() * lhs.height - rhs.width.toLong() * rhs.height)
        }
    }

    private fun teardownChannels() {
        methodChannel!!.setMethodCallHandler(null)
    }
}
