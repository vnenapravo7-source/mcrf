package org.mcrf.client

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.*
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.*
import android.view.*
import android.widget.*
import java.util.concurrent.atomic.AtomicBoolean

/** Offline camera scanner. Frames stay in memory and are never written or uploaded. */
class QrScanActivity : Activity() {
    private lateinit var preview: TextureView
    private lateinit var hint: TextView
    private var thread: HandlerThread? = null
    private var background: Handler? = null
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var images: ImageReader? = null
    @Volatile private var resumed = false
    private val completed = AtomicBoolean(false)
    @Volatile private var opening = false
    @Volatile private var cameraGeneration = 0
    private var previewSize: android.util.Size? = null
    private var sensorOrientation = 90
    private var lastFrame = 0L
    private val gallery = java.util.concurrent.Executors.newSingleThreadExecutor()
    override fun onCreate(state: Bundle?) {
        val theme = getSharedPreferences("MainActivity", MODE_PRIVATE).getString("theme", "system")
        UserThemeColorsUi.configure(this)
        Loft.apply(theme == "dark" || theme == "system" && resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES)
        setTheme(if (Loft.dark) R.style.AppThemeDark else R.style.AppTheme)
        super.onCreate(state)
        fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background=Loft.rootBackground(this@QrScanActivity); fitsSystemWindows = true; setPadding(dp(16), dp(12), dp(16), dp(12)) }
        layout.addView(TextView(this).apply { text = "Сканировать QR"; textSize = 22f; setTextColor(Loft.INK); setPadding(8, 8, 8, 20) })
        hint = TextView(this).apply { text = "Наведите камеру на QR подключения или подписки"; textSize = 14f; setTextColor(Loft.MUTED); setPadding(8, 8, 8, 20) }; layout.addView(hint)
        preview = TextureView(this).apply { surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) { openCamera() }
            override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) { transformPreview() }
            override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean { closeCamera(); return true }
            override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
        } }
        layout.addView(preview, LinearLayout.LayoutParams(-1, 0, 1f))
        layout.addView(themedButton("Выбрать изображение QR").apply { setOnClickListener { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE), 2) } })
        layout.addView(themedButton("Отмена").apply { setOnClickListener { finish() } }); setContentView(layout)
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) hint.text = "На устройстве нет камеры. Выберите изображение QR."
        else if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf(Manifest.permission.CAMERA), 1)
    }
    private fun themedButton(label:String)=StandardButtons.style(this,Button(this),label)
    override fun onResume() { super.onResume(); resumed = true; thread = HandlerThread("mcrf-qr").apply { start() }; background = Handler(thread!!.looper); if (preview.isAvailable) openCamera() }
    override fun onPause() { resumed = false; closeCamera(); background = null; thread?.quitSafely(); thread = null; super.onPause() }
    override fun onDestroy() { gallery.shutdownNow(); super.onDestroy() }
    override fun onRequestPermissionsResult(request: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(request, permissions, results)
        if (request == 1 && results.firstOrNull() == PackageManager.PERMISSION_GRANTED) openCamera()
        else hint.text = "Без доступа к камере можно выбрать изображение QR."
    }
    private fun openCamera() {
        val handler = background ?: return
        if (!resumed || completed.get() || !preview.isAvailable || camera != null || opening || checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        opening = true
        val generation = cameraGeneration
        try {
            val manager = getSystemService(CameraManager::class.java)
            val id = manager.cameraIdList.firstOrNull { manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK } ?: manager.cameraIdList.first()
            val formats = manager.getCameraCharacteristics(id).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
            sensorOrientation = manager.getCameraCharacteristics(id).get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            val size = formats.getOutputSizes(ImageFormat.YUV_420_888).filter { it.width <= 1280 && it.height <= 1280 }.maxByOrNull { it.width*it.height }
                ?: throw IllegalStateException()
            preview.surfaceTexture!!.setDefaultBufferSize(size.width, size.height)
            previewSize = size
            transformPreview()
            images = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 2).apply {
                setOnImageAvailableListener({ reader -> try { reader.acquireLatestImage()?.use { image ->
                    if (!resumed || generation != cameraGeneration || completed.get() || SystemClock.elapsedRealtime()-lastFrame < 250) return@use
                    lastFrame = SystemClock.elapsedRealtime()
                    try {
                        val plane = image.planes[0]
                        val bytes = QrCodec.cameraLuminance(image.width, image.height, plane.rowStride, plane.pixelStride, plane.buffer)
                        QrCodec.decode(image.width, image.height, bytes)?.let(::found)
                    } catch (_: Exception) { }
                } } catch (_: IllegalStateException) { /* Reader may close while a frame callback is queued. */ } }, handler)
            }
            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    if (!resumed || generation != cameraGeneration) { device.close(); return }
                    opening = false
                    camera = device
                    val display = Surface(preview.surfaceTexture); val capture = images?.surface ?: run { display.release(); return }
                    device.createCaptureSession(listOf(display, capture), object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(value: CameraCaptureSession) {
                            if (!resumed || generation != cameraGeneration || camera !== device) { value.close(); display.release(); return }
                            session = value
                            try { val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                addTarget(display); addTarget(capture); set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                            }; value.setRepeatingRequest(request.build(), null, handler) } catch (_: Exception) { cameraError() }
                        }
                        override fun onConfigureFailed(value: CameraCaptureSession) { display.release(); if (generation == cameraGeneration) cameraError() }
                        override fun onClosed(value: CameraCaptureSession) { display.release() }
                    }, handler)
                }
                override fun onDisconnected(device: CameraDevice) { device.close(); if (generation == cameraGeneration) { opening = false; cameraError() } }
                override fun onError(device: CameraDevice, error: Int) { device.close(); if (generation == cameraGeneration) { opening = false; cameraError() } }
            }, handler)
        } catch (_: Exception) { opening = false; cameraError() }
    }
    private fun cameraError() { runOnUiThread { if (!isFinishing) hint.text = "Камера недоступна. Выберите изображение QR." } }
    private fun closeCamera() { cameraGeneration++; session?.close(); session = null; camera?.close(); camera = null; images?.close(); images = null; opening = false }
    private fun transformPreview() {
        val size = previewSize ?: return
        if (preview.width == 0 || preview.height == 0) return
        val displayDegrees = when (windowManager.defaultDisplay.rotation) { Surface.ROTATION_90 -> 90; Surface.ROTATION_180 -> 180; Surface.ROTATION_270 -> 270; else -> 0 }
        val rotation = (sensorOrientation - displayDegrees + 360) % 360
        val sideways = rotation == 90 || rotation == 270
        val scale = maxOf(preview.width.toFloat() / if (sideways) size.height else size.width, preview.height.toFloat() / if (sideways) size.width else size.height)
        val cx = preview.width / 2f; val cy = preview.height / 2f
        preview.setTransform(Matrix().apply {
            setScale(size.width.toFloat()/preview.width, size.height.toFloat()/preview.height, cx, cy)
            postRotate(rotation.toFloat(), cx, cy)
            postScale(scale, scale, cx, cy)
        })
    }
    private fun found(text: String) {
        if (text.isBlank() || text.toByteArray(Charsets.UTF_8).size > ServerImport.MAX_BYTES || !completed.compareAndSet(false, true)) return
        runOnUiThread { if (!isDestroyed && !isFinishing) { setResult(RESULT_OK, Intent().putExtra("qr", text)); finish() } }
    }
    override fun onActivityResult(request: Int, result: Int, data: Intent?) {
        super.onActivityResult(request, result, data)
        if (request != 2 || result != RESULT_OK || data?.data == null) return
        val uri = data.data!!
        gallery.execute {
            var bitmap: Bitmap? = null
            try {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                require(bounds.outWidth > 0 && bounds.outHeight > 0)
                val options = BitmapFactory.Options().apply { inSampleSize = 1 }; while (maxOf(bounds.outWidth, bounds.outHeight)/options.inSampleSize > 2048) options.inSampleSize *= 2
                bitmap = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) } ?: throw IllegalArgumentException()
                val pixels = IntArray(bitmap!!.width*bitmap!!.height); bitmap!!.getPixels(pixels, 0, bitmap!!.width, 0, 0, bitmap!!.width, bitmap!!.height)
                val value = QrCodec.decodePixels(bitmap!!.width, bitmap!!.height, pixels) ?: throw IllegalArgumentException()
                found(value)
            } catch (_: Exception) { runOnUiThread { if (!isFinishing) hint.text = "QR не найден. Выберите другое изображение." } }
            finally { bitmap?.recycle() }
        }
    }
}
