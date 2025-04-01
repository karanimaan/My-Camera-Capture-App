package com.example.myapplication

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.nio.ByteBuffer
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

typealias LumaListener = (luma: Double) -> Unit
typealias brighterSideListener = (brighterSide: String) -> Unit

class MainActivity : AppCompatActivity() {


    private class MyAnalyzer(private val listener: brighterSideListener) : ImageAnalysis.Analyzer {

        private var previousStartTime: Long = 0

        private fun ByteBuffer.toByteArray(): ByteArray {
            rewind()    // Rewind the buffer to zero
            val data = ByteArray(remaining())
            get(data)   // Copy the buffer into a byte array
            return data // Return the byte array
        }

        override fun analyze(image: ImageProxy) {

            val startAnalyzeTime = System.currentTimeMillis()
            //Log.d("Timing", "Current time: $startAnalyzeTime")
            val repDuration = startAnalyzeTime - previousStartTime
            Log.d("Timing", "Rep duration: $repDuration")
            previousStartTime = startAnalyzeTime

            val imageData = image.planes[0].buffer.toByteArray()

            // send image
            //Thread(ByteArraySender(imageData)).start()

            //command

            val pixels = imageData.map { it.toInt() and 0xFF }
            val luma = pixels.average()

            // my addition
            val leftSum = 0
            val rightSum = 0
            val cols = image.width
            val rows = image.height
            //for (row in 0 until rows)

            val width = image.width
            val height = image.height

            val leftPixels = mutableListOf<Int>()
            val rightPixels = mutableListOf<Int>()

            for (y in 0 until height) {
                for (x in 0 until width) {
                    val index = y * width + x
                    val pixel = imageData[index].toInt() and 0xFF

                    if (x < width / 2) {
                        leftPixels.add(pixel)  // Left half
                    } else {
                        rightPixels.add(pixel) // Right half
                    }
                }
            }

            val lumaLeft = leftPixels.average()
            val lumaRight = rightPixels.average()

            val command = if (lumaLeft > lumaRight) "s" else "r"

            val endAnalyzeTime = System.currentTimeMillis()
            val analyzeDuration = endAnalyzeTime - startAnalyzeTime
            Log.d("Timing", "Analyze duration: $analyzeDuration")


            // Attach timestamp to the message

            // TODO Start timer


            //Thread(StringSender(message)).start()  // Send to ESP

            image.close()
        }
    }


    private var imageCapture: ImageCapture? = null

    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null

    private lateinit var cameraExecutor: ExecutorService

    private val activityResultLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        )
        { permissions ->
            // Handle Permission granted/rejected
            var permissionGranted = true
            permissions.entries.forEach {
                if (it.key in REQUIRED_PERMISSIONS && it.value == false)
                    permissionGranted = false
            }
            if (!permissionGranted) {
                Toast.makeText(
                    baseContext,
                    "Permission request denied",
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                startCamera()
            }
        }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        // Request camera permissions
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            requestPermissions()
        }

        // Set up the listeners for take photo and video capture buttons
        val imageCaptureButton = findViewById<Button>(R.id.image_capture_button)
        imageCaptureButton.setOnClickListener { takePhoto() }
        val videoCaptureButton = findViewById<Button>(R.id.video_capture_button)
        videoCaptureButton.setOnClickListener { captureVideo() }

        cameraExecutor = Executors.newSingleThreadExecutor()
    }

    private fun takePhoto() {
        sendMessage("r")
    }

    private fun captureVideo() {
        sendMessage("s")
    }

    private fun startCamera() {

        // for image preview

        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        val viewFinder = findViewById<PreviewView>(R.id.viewFinder) // preview component

        cameraProviderFuture.addListener({
            // Used to bind the lifecycle of cameras to the lifecycle owner
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            // Preview
            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(viewFinder.surfaceProvider)
                }


            // for take photo
            imageCapture = ImageCapture.Builder().build()


            // for image analysis
            val imageAnalyzer = ImageAnalysis.Builder()
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor, MyAnalyzer { brighterSide ->
                        Log.d(TAG, "Brighter side: $brighterSide")
                    })
                }


            // Select back camera as a default
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                // Unbind use cases before rebinding
                cameraProvider.unbindAll()

                // Bind use cases to camera
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageCapture, imageAnalyzer
                )

            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }

        }, ContextCompat.getMainExecutor(this))

    }


    private fun requestPermissions() {
        activityResultLauncher.launch(REQUIRED_PERMISSIONS)
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(
            baseContext, it
        ) == PackageManager.PERMISSION_GRANTED
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()


    }

    override fun onStop() {
        super.onStop()

        // format used by server
        val message = "/Car?move=" + "s "

        // TODO format message for Web_control_car.py
        Thread(StringSender(message)).start()  // Send to ESP
    }

    override fun onPause() {
        super.onPause()

        // format used by server
        val message = "/Car?move=" + "s "

        // TODO format message for Web_control_car.py
        Thread(StringSender(message)).start()  // Send to ESP
    }

    companion object {
        private const val TAG = "CameraXApp"
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
        private val REQUIRED_PERMISSIONS =
            mutableListOf(
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO
            ).apply {
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                    add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
            }.toTypedArray()
    }

    fun sendMessage(command: String) {

        // Get current timestamp (milliseconds since Unix epoch)
        val timestamp = System.currentTimeMillis()

        val message = "/Car?move=${command.lowercase(Locale.getDefault())} &$timestamp"

        Thread(StringSender(message)).start()
    }


}