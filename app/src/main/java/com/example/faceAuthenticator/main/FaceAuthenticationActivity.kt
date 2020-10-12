package com.example.faceAuthenticator.main

import android.Manifest
import android.annotation.SuppressLint
import android.app.ProgressDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.faceAuthenticator.R
import com.example.faceAuthenticator.utilities.BoundingBoxOverlay
import com.example.faceAuthenticator.utilities.FaceNetModel
import com.example.faceAuthenticator.utilities.FrameAnalyser
import com.google.android.gms.tasks.OnSuccessListener
import com.google.firebase.ml.vision.FirebaseVision
import com.google.firebase.ml.vision.common.FirebaseVisionImage
import com.google.firebase.ml.vision.common.FirebaseVisionImageMetadata
import com.google.firebase.ml.vision.face.FirebaseVisionFace
import com.google.firebase.ml.vision.face.FirebaseVisionFaceDetectorOptions
import java.util.concurrent.Executors
import kotlin.math.ceil

class FaceAuthenticationActivity : AppCompatActivity() {
    private lateinit var cameraTextureView: TextureView
    private lateinit var frameAnalyser: FrameAnalyser

    companion object {
        private const val requestCodePermissions = 10
        private val requiredPermissions = arrayOf(Manifest.permission.CAMERA, Manifest.permission.WRITE_EXTERNAL_STORAGE)
        private const val TAG = "FaceAuth"
        private lateinit var phoneOwner : String
        // This view's VISIBILITY is set to View.GONE in activity_main.xml
        lateinit var labelText: TextView
        fun setLabelText(message: String) {
            labelText.text = message
        }
    }

    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_face_authentication)

        // Implementation of CameraX preview
        cameraTextureView = findViewById(R.id.camera_textureView)
        val boundingBoxOverlay = findViewById<BoundingBoxOverlay>(R.id.bbox_overlay)
        val faceAuthBtn = findViewById<Button>(R.id.faceAuthBtn)
        labelText = findViewById(R.id.labelText)

        //Check if all permissions are granted
        if (allPermissionsGranted()) {
            cameraTextureView.post { startCamera() }
        }
        else {
            ActivityCompat.requestPermissions(this, requiredPermissions, requestCodePermissions)
        }
        cameraTextureView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updateTransform()
        }

        // Necessary to keep the Overlay above the TextureView so that the boxes are visible.
        boundingBoxOverlay.setWillNotDraw(false)
        boundingBoxOverlay.setZOrderOnTop(true)
        frameAnalyser = FrameAnalyser(this , boundingBoxOverlay)

        if (ActivityCompat.checkSelfPermission(this , Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED){
            // Read image data
            scanStorageForImages()
        }

        faceAuthBtn.setOnClickListener {
            if (phoneOwner == labelText.text.toString()) {
                val success = Intent(this@FaceAuthenticationActivity, SecondActivity::class.java)
                startActivity(success)
            } else {
                val fail = Intent(this@FaceAuthenticationActivity, MainActivity::class.java)
                val error = "You entered the wrong identity! Please try again!"
                fail.putExtra("error", error)
                startActivity(fail)
            }
        }
    }

    private fun scanStorageForImages() {
        // Initialize FaceNet model.
        val model = FaceNetModel(this)

        // Create an empty (String, FloatArray) Hashmap for storing the data.
        val imageData = HashMap<String,FloatArray>()

        // Initialize Firebase MLKit Face Detector
        val accurateOps = FirebaseVisionFaceDetectorOptions.Builder()
                .setPerformanceMode(FirebaseVisionFaceDetectorOptions.ACCURATE)
                .build()
        val detector = FirebaseVision.getInstance().getVisionFaceDetector(accurateOps)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED) {
            val progressDialog = ProgressDialog(this)
            progressDialog.setMessage("Loading images ...")
            progressDialog.show()

            val imagesDir = getExternalFilesDir(Environment.DIRECTORY_PICTURES + "/FaceAuthenticator")
            Log.i(TAG, imagesDir.toString())
            val imageSubDirs = imagesDir?.listFiles()

            if (imageSubDirs == null) {
                Toast.makeText(this,
                        "Could not read images. Make sure you've have a folder in $imagesDir",
                        Toast.LENGTH_LONG).show()
            } else {
                val subDirNames = imageSubDirs.map { file -> file.name }
                phoneOwner = subDirNames[0].toString()
                val subjectImages = imageSubDirs.map { file -> BitmapFactory.decodeFile(file.listFiles()[0].absolutePath) }
                var imageCounter = 0
                val successListener = OnSuccessListener<List<FirebaseVisionFace?>> { faces ->
                    if (faces.isNotEmpty()) {
                        imageData[ subDirNames[ imageCounter ] ] =
                                model.getFaceEmbedding(subjectImages[imageCounter], faces[0]!!.boundingBox, false)
                        imageCounter += 1
                        // Make sure the frameAnalyser uses the given data!
                        frameAnalyser.faceList = imageData
                        if (imageCounter == imageSubDirs.size) {
                            progressDialog.dismiss()
                        }
                    }
                }
                for (image in subjectImages) {
                    val metadata = FirebaseVisionImageMetadata.Builder()
                            .setWidth(image.width)
                            .setHeight(image.height)
                            .setFormat(FirebaseVisionImageMetadata.IMAGE_FORMAT_NV21)
                            .setRotation(FirebaseVisionImageMetadata.ROTATION_0)
                            .build()
                    val inputImage = FirebaseVisionImage.fromByteArray(bitmapToNV21(image), metadata)
                    detector.detectInImage(inputImage).addOnSuccessListener(successListener)
                }
            }
        }
    }

    // Start the camera preview once the permissions are granted.
    private fun startCamera() {
        val previewConfig = PreviewConfig.Builder().apply {
            setTargetResolution(Size(411, 600 ))
            setLensFacing(CameraX.LensFacing.FRONT)
        }.build()
        val preview = Preview(previewConfig)
        preview.setOnPreviewOutputUpdateListener {
            val parent = cameraTextureView.parent as ViewGroup
            parent.removeView( cameraTextureView )
            parent.addView( cameraTextureView , 0)
            cameraTextureView.surfaceTexture = it.surfaceTexture
            updateTransform()
        }

        // FrameAnalyser -> fetches camera frames and makes them in the analyse() method.
        val analyzerConfig = ImageAnalysisConfig.Builder().apply {
            setImageReaderMode(ImageAnalysis.ImageReaderMode.ACQUIRE_LATEST_IMAGE)
            setLensFacing(CameraX.LensFacing.FRONT)
        }.build()
        val analyzerUseCase = ImageAnalysis(analyzerConfig).apply {
            setAnalyzer( Executors.newSingleThreadExecutor() , frameAnalyser )
        }

        // Bind the preview and frameAnalyser.
        CameraX.bindToLifecycle(this, preview, analyzerUseCase )
    }

    private fun updateTransform() {
        val matrix = Matrix()
        val centerX = cameraTextureView.width.div(2f)
        val centerY = cameraTextureView.height.div(2f)
        val rotationDegrees = when(cameraTextureView.display.rotation) {
            Surface.ROTATION_0 -> 0
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> return
        }
        matrix.postRotate(-rotationDegrees.toFloat(), centerX , centerY )
        cameraTextureView.setTransform(matrix)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        if (requestCode == requestCodePermissions) {
            if (allPermissionsGranted()) {
                cameraTextureView.post { startCamera() }
                scanStorageForImages()
            } else {
                Toast.makeText(this, "Permissions not granted by the user.", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    private fun allPermissionsGranted() = requiredPermissions.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun bitmapToNV21(bitmap: Bitmap): ByteArray {
        val argb = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(argb, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val yuv = ByteArray(bitmap.height * bitmap.width + 2 * ceil(bitmap.height / 2.0).toInt()
                * ceil(bitmap.width / 2.0).toInt())
        encodeYUV420SP( yuv, argb, bitmap.width, bitmap.height)
        return yuv
    }

    private fun encodeYUV420SP(yuv420sp: ByteArray, argb: IntArray, width: Int, height: Int) {
        val frameSize = width * height
        var yIndex = 0
        var uvIndex = frameSize
        var r: Int
        var g: Int
        var b: Int
        var y: Int
        var u: Int
        var v: Int
        var index = 0
        for (j in 0 until height) {
            for (i in 0 until width) {
                r = argb[index] and 0xff0000 shr 16
                g = argb[index] and 0xff00 shr 8
                b = argb[index] and 0xff shr 0
                y = (66 * r + 129 * g + 25 * b + 128 shr 8) + 16
                u = (-38 * r - 74 * g + 112 * b + 128 shr 8) + 128
                v = (112 * r - 94 * g - 18 * b + 128 shr 8) + 128
                yuv420sp[yIndex++] = (if (y < 0) 0 else if (y > 255) 255 else y).toByte()
                if (j % 2 == 0 && index % 2 == 0) {
                    yuv420sp[uvIndex++] = (if (v < 0) 0 else if (v > 255) 255 else v).toByte()
                    yuv420sp[uvIndex++] = (if (u < 0) 0 else if (u > 255) 255 else u).toByte()
                }
                index++
            }
        }
    }
}
