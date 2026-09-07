package com.cybertech.mishai

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Base64
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.cybertech.mishai.ai.PreferencesManager
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

/**
 * Camera screen — user points camera at something, Mish describes what she
 * sees (vision request to backend, if an endpoint is configured).
 */
class CameraActivity : ComponentActivity() {

    private lateinit var previewView: PreviewView
    private var imageCapture: ImageCapture? = null
    private val executor = Executors.newSingleThreadExecutor()
    private val http = OkHttpClient()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startCamera() else {
            Toast.makeText(this, "Camera permission required", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera)

        previewView = findViewById(R.id.preview_view)
        findViewById<android.widget.Button>(R.id.capture_btn).setOnClickListener {
            captureAndAsk()
        }
        findViewById<android.widget.TextView>(R.id.camera_result).text =
            "Camera on hai. Kuch point karo aur 'Photo lo' dabao — Mish batayegi kya dekh rahi hai."

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        } else {
            startCamera()
        }
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val cameraProvider = future.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()

            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                this,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                imageCapture
            )
        }, ContextCompat.getMainExecutor(this))
    }

    private fun captureAndAsk() {
        val cap = imageCapture ?: return
        cap.takePicture(
            executor,
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: androidx.camera.core.ImageProxy) {
                    val bytes = imageToBytes(image)
                    image.close()
                    val resultView = findViewById<android.widget.TextView>(R.id.camera_result)
                    if (bytes == null) {
                        runOnUiThread {
                            resultView.text = "Photo mil gayi, lekin process nahi hui."
                        }
                        return
                    }
                    sendVision(bytes, resultView)
                }

                override fun onError(exception: ImageCaptureException) {
                    runOnUiThread {
                        Toast.makeText(
                            this@CameraActivity,
                            "Photo fail: ${exception.message}",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        )
    }

    private fun imageToBytes(image: androidx.camera.core.ImageProxy): ByteArray? {
        return try {
            val buffer = image.planes[0].buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            val yuv = android.graphics.YuvImage(
                bytes,
                android.graphics.ImageFormat.NV21,
                image.width,
                image.height,
                null
            )
            val out = ByteArrayOutputStream()
            yuv.compressToJpeg(
                android.graphics.Rect(0, 0, image.width, image.height),
                90,
                out
            )
            out.toByteArray()
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun sendVision(bytes: ByteArray, resultView: android.widget.TextView) {
        val endpoint = PreferencesManager(this).getEndpoint()
        if (endpoint.isNullOrBlank()) {
            runOnUiThread {
                resultView.text =
                    "Mish ne photo capture kar li 👍. Koi vision-ka backend set nahi hai, is liye main abhi detail nahi bata sakti. Baad mein AI link kar dena."
            }
            return
        }

        val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        executor.execute {
            try {
                val body = JSONObject().apply {
                    put("type", "vision")
                    put("image_base64", b64)
                }.toString()
                val req = Request.Builder()
                    .url(endpoint)
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .build()
                val resp = http.newCall(req).execute()
                val text = resp.body?.string() ?: ""
                val json = JSONObject(text)
                val desc = json.optString("response", "Kuch samajh nahi aaya.")
                runOnUiThread { resultView.text = "Mish: $desc" }
            } catch (e: Exception) {
                runOnUiThread {
                    resultView.text = "Vision call fail: ${e.message}"
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdown()
    }
}