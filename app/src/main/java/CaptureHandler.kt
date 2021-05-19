package com.pangrel.pakaimasker

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.*
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.*
import android.media.Image
import android.media.ImageReader
import android.util.Log
import android.util.Size
import android.view.OrientationEventListener
import android.view.Surface
import androidx.core.app.ActivityCompat
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlin.math.absoluteValue

interface OnCapturedListener {
    fun onCapturedDone(bitmapList: ArrayList<Bitmap>)
}

class CaptureHandler {
    private var context: Context
    private val totalRequest: Int
    private val totalProcessed: Int
    private var listener: OnCapturedListener? = null
    private var cameraID: String
    private var cameraManager: CameraManager
    private var targetSurfaces = ArrayList<Surface>()
    private var imageReader: ImageReader
    private var sensorManager: SensorManager
    private var sensor: Sensor
    private var orientation = ORIENTATION_POTRAIT

    constructor(context: Context, totalRequest: Int, totalProcessed: Int) {
        this.context = context
        this.totalRequest = totalRequest
        this.totalProcessed = totalProcessed

        cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

        var camID = ""
        for (id in cameraManager!!.cameraIdList) {
            val characteristics = cameraManager!!.getCameraCharacteristics(id)
            val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
            if (facing == CameraCharacteristics.LENS_FACING_FRONT) {
                camID = id
                break
            }
        }

        cameraID = camID

        val width = 272
        val height = 272

        val previewSize = chooseSupportedSize(cameraID!!, width, height)

        imageReader = ImageReader.newInstance(
            previewSize!!.getWidth(),
            previewSize!!.getHeight(),
            ImageFormat.YUV_420_888,
            5
        )

        sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager;
        sensor = sensorManager!!.getDefaultSensor(Sensor.TYPE_GRAVITY);
    }

    private val sensorListener  = object : SensorEventListener {
        override fun onAccuracyChanged(p0: Sensor?, p1: Int) {}
        override fun onSensorChanged(event: SensorEvent) {
            val accelerometerReading = FloatArray(3)

            if (event.sensor.type === Sensor.TYPE_GRAVITY) {
                event.values.copyInto(accelerometerReading, 0)

                if (Math.abs(accelerometerReading.get(0)) > Math.abs(accelerometerReading.get(1))) {
                    orientation = if (accelerometerReading.get(0) > 0) ORIENTATION_LANDSCAPE else ORIENTATION_LANDSCAPE_REVERSE
                } else {
                    orientation = if (accelerometerReading.get(1) > 0) ORIENTATION_POTRAIT else ORIENTATION_POTRAIT_REVERSE
                }
            }
        }
    }

    private fun getJpegOrientation(c: CameraCharacteristics, deviceOrientation: Int): Int {
        var deviceOrientation = deviceOrientation
        if (deviceOrientation == OrientationEventListener.ORIENTATION_UNKNOWN) return 0
        val sensorOrientation = c[CameraCharacteristics.SENSOR_ORIENTATION]

        // Round device orientation to a multiple of 90
        deviceOrientation = (deviceOrientation + 45) / 90 * 90

        // Reverse device orientation for front-facing cameras
        val facingFront =
            c[CameraCharacteristics.LENS_FACING] === CameraCharacteristics.LENS_FACING_FRONT
        if (facingFront) deviceOrientation = -deviceOrientation

        // Calculate desired JPEG orientation relative to camera orientation to make
        // the image upright relative to the device orientation
        if (sensorOrientation != null) {
            return (sensorOrientation + deviceOrientation + 360) % 360
        }

        return 0
    }
    private fun getImageOrientation(): Float {
        if (orientation === ORIENTATION_POTRAIT) return 270f
        if (orientation === ORIENTATION_POTRAIT_REVERSE) return 90f
        if (orientation === ORIENTATION_LANDSCAPE) return 360f
        if (orientation === ORIENTATION_LANDSCAPE_REVERSE) return 180f

        return 0f
    }
    private fun chooseSupportedSize(camId: String, textureViewWidth: Int, textureViewHeight: Int): Size {
        // Get all supported sizes for TextureView
        val characteristics = cameraManager.getCameraCharacteristics(camId)
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val supportedSizes = map?.getOutputSizes(SurfaceTexture::class.java)

        // We want to find something near the size of our TextureView
        val texViewArea = textureViewWidth * textureViewHeight
        val texViewAspect = textureViewWidth.toFloat()/textureViewHeight.toFloat()

        val nearestToFurthestSz = supportedSizes?.sortedWith(compareBy(
            // First find something with similar aspect
            {
                val aspect = if (it.width < it.height) it.width.toFloat() / it.height.toFloat()
                else it.height.toFloat() / it.width.toFloat()
                (aspect - texViewAspect).absoluteValue
            },
            // Also try to get similar resolution
            {
                (texViewArea - it.width * it.height).absoluteValue
            }
        ))


        if (nearestToFurthestSz != null) {
            if (nearestToFurthestSz.isNotEmpty())
                return nearestToFurthestSz[0]
        }

        return Size(320, 200)
    }
    private fun imageToBitmap(image: Image, rotationDegrees: Float): Bitmap? {
        val ib = ByteBuffer.allocate(image.getHeight() * image.getWidth() * 2)
        val y: ByteBuffer = image.getPlanes().get(0).getBuffer()
        val cr: ByteBuffer = image.getPlanes().get(1).getBuffer()
        val cb: ByteBuffer = image.getPlanes().get(2).getBuffer()
        ib.put(y)
        ib.put(cb)
        ib.put(cr)
        val yuvImage = YuvImage(
            ib.array(),
            ImageFormat.NV21,
            image.getWidth(),
            image.getHeight(),
            null
        )
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, image.getWidth(), image.getHeight()), 50, out)
        val imageBytes: ByteArray = out.toByteArray()
        val bm = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
        var bitmap = bm

        // On android the camera rotation and the screen rotation
        // are off by 90 degrees, so if you are capturing an image
        // in "portrait" orientation, you'll need to rotate the image.
        if (rotationDegrees != 0f) {
            val matrix = Matrix()
            matrix.postRotate(rotationDegrees)
            val scaledBitmap = Bitmap.createScaledBitmap(bm, bm.width, bm.height, true)
            bitmap = Bitmap.createBitmap(
                scaledBitmap,
                0,
                0,
                scaledBitmap.width,
                scaledBitmap.height,
                matrix,
                true
            )
        }
        return bitmap
    }
    private fun doCapture(camera: CameraDevice) {
        try {
            recordOrientation()

            imageReader.setOnImageAvailableListener(object: ImageReader.OnImageAvailableListener {
                private var captured = 0
                private var images = ArrayList<Bitmap>()

                override fun onImageAvailable(reader: ImageReader?) {
                    val image = reader?.acquireLatestImage()

                    if (totalRequest - ++captured <= totalProcessed) {
                        if (image !== null) {
                            val bitmap = imageToBitmap(image, getImageOrientation())
                            if (bitmap !== null) {
                                images.add(bitmap)
                            }
                        }
                    }

                    image?.close()

                    if (totalRequest === captured) {
                        listener?.onCapturedDone(images)
                    }
                }
            }, null)

            val requestBuilder =
                camera!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                    targetSurfaces.add(imageReader!!.surface)
                    this.addTarget(imageReader!!.surface)
                }

            requestBuilder.set(
                CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            )
            requestBuilder.set(
                CaptureRequest.CONTROL_AE_MODE,
                CaptureRequest.CONTROL_AE_MODE_ON
            )
            requestBuilder.set(
                CaptureRequest.CONTROL_AWB_MODE,
                CaptureRequest.CONTROL_AWB_MODE_AUTO
            );

            camera!!.createCaptureSession(
                targetSurfaces,
                object : CameraCaptureSession.StateCallback() {
                    private var captured = 0

                    override fun onConfigured(captureSession: CameraCaptureSession) {
                        repeat(totalRequest) {
                            captureSession!!.capture(
                                requestBuilder!!.build(),
                                object : CameraCaptureSession.CaptureCallback() {
                                    override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                                        if (++captured === totalRequest) {
                                            captureSession.close()
                                            camera.close()
                                            stopOrientation()
                                            targetSurfaces.clear()

                                            System.gc()
                                        }
                                    }
                                },
                                null
                            )
                        }
                    }

                    override fun onConfigureFailed(captureSession: CameraCaptureSession) {
                        captureSession.close()
                        camera.close()
                        stopOrientation()
                        targetSurfaces.clear()
                        System.gc()

                        Log.e(this.javaClass.name, "onConfigureFailed()")
                    }
                },
                null
            )
        } catch (e: Exception) {
            camera.close()
            stopOrientation()
            System.gc()

            Log.e(this.javaClass.name, "doCapture()")
        }
    }
    private fun recordOrientation() {
        sensorManager!!.registerListener(sensorListener, sensor, SensorManager.SENSOR_DELAY_NORMAL);
        Log.d(this.javaClass.name, "Orientation is recorded")
    }
    private fun stopOrientation() {
        sensorManager.unregisterListener(sensorListener, sensor)
        Log.d(this.javaClass.name, "Orientation is stopped")
    }

    fun captureRequest() {
        if (ActivityCompat.checkSelfPermission(this.context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Log.d(this.javaClass.name, "Camera permission is not granted!")
            return
        }

        try {
            cameraManager!!.openCamera(cameraID, object : CameraDevice.StateCallback() {
                override fun onOpened(currentCameraDevice: CameraDevice) {
                    doCapture(currentCameraDevice)
                }

                override fun onDisconnected(currentCameraDevice: CameraDevice) {
                    currentCameraDevice.close()
                }

                override fun onError(currentCameraDevice: CameraDevice, error: Int) {
                    currentCameraDevice.close()
                }
            }, null)
        } catch (err: java.lang.Exception) {
            Log.d(this.javaClass.name, "Error opening camera!")
        }
    }
    fun setListener(listener: OnCapturedListener) {
        this.listener = listener
    }

    fun destroy() {
        stopOrientation()
        this.imageReader.close()
        this.targetSurfaces.clear()
    }

    companion object {
        val ORIENTATION_POTRAIT = 0
        val ORIENTATION_LANDSCAPE = 1
        val ORIENTATION_POTRAIT_REVERSE = 2
        val ORIENTATION_LANDSCAPE_REVERSE = 3
    }
}