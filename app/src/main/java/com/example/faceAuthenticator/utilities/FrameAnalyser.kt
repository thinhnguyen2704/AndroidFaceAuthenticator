package com.example.faceAuthenticator.utilities

import android.content.Context
import android.graphics.*
import android.media.Image
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.example.faceAuthenticator.main.FaceAuthenticationActivity
import com.google.firebase.ml.vision.FirebaseVision
import com.google.firebase.ml.vision.common.FirebaseVisionImage
import com.google.firebase.ml.vision.common.FirebaseVisionImageMetadata
import com.google.firebase.ml.vision.face.FirebaseVisionFaceDetectorOptions
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.collections.ArrayList
import kotlin.collections.HashMap
import kotlin.math.ceil
import kotlin.math.pow
import kotlin.math.sqrt

// Analyser class to process frames and produce detections.
class FrameAnalyser(context: Context, private var boundingBoxOverlay: BoundingBoxOverlay) : ImageAnalysis.Analyzer {

    // Configure the FirebaseVisionFaceDetector
    private val realTimeOpts = FirebaseVisionFaceDetectorOptions.Builder()
        .setPerformanceMode(FirebaseVisionFaceDetectorOptions.FAST)
        .build()
    private val detector = FirebaseVision.getInstance().getVisionFaceDetector(realTimeOpts)

    // Used to determine whether the incoming frame should be dropped or processed.
    private var isProcessing = AtomicBoolean(false)

    // FirebaseImageMeta for defining input image params.
    private var metadata = FirebaseVisionImageMetadata.Builder()
        .setWidth(640)
        .setHeight(480)
        .setFormat(FirebaseVisionImageMetadata.IMAGE_FORMAT_NV21 )
        .setRotation(degreesToFirebaseRotation(90))
        .build()

    // Store the face embeddings in a ( String , FloatArray ) Hashmap.
    // Where String -> name of the person abd FloatArray -> Embedding of the face.
    var faceList = HashMap<String, FloatArray>()

    // Boolean value to check whether the user has enabled auto mode.
    private var isAutoMode = true

    // Store newly found faces in ( Name , Embedding ) form.
    private val autoRecognitionFaceList =HashMap<String,FloatArray>()

    // Number of users uniquely identified by the Auto Mode.
    private var autoRecognizedUsersCount : Int = 0

    // Threshold to determine whether the face belongs to a new user or an existing one.
    // If the similarity score is smaller than this threshold, then a new name will be assigned to the user and will
    // be appended in autoRecognitionFaceList
    private val autoRecognitionThreshold = 0.6f

    // FaceNet model utility class
    private val model = FaceNetModel( context )

    // Here's where we receive our frames.
    override fun analyze(image: ImageProxy?, rotationDegrees: Int) {

        // android.media.Image -> android.graphics.Bitmap
        val bitmap = toBitmap( image?.image!! )

        // If the previous frame is still being processed, then skip this frame
        if (isProcessing.get()) {
            return
        }
        else {
            // Declare that the current frame is being processed.
            isProcessing.set(true)

            Log.i( "Info " , rotationDegrees.toString() )
            // Perform face detection
            val inputImage = FirebaseVisionImage.fromByteArray( bitmapToNv21( bitmap ) , metadata )
            detector.detectInImage(inputImage)
                .addOnSuccessListener { faces ->
                    // Start a new thread to avoid frequent lags.
                    Thread {
                        val predictions = ArrayList<Prediction>()
                        for (face in faces) {
                            try {
                                // Crop the frame using face.boundingBox.
                                // Convert the cropped Bitmap to a ByteBuffer.
                                // Finally, feed the ByteBuffer to the FaceNet model.
                                val subject = model.getFaceEmbedding( bitmap , face.boundingBox , true )
                                Log.i( "Model" , "New frame received.")
                                // Determine index and value of the highest similarity score.
                                var highestSimilarityScore = -1f
                                var highestSimilarityScoreName = ""
                                for ( ( name , embedding ) in faceList ) {
                                    Log.i("LabelName", name)
                                    val p = cosineSimilarity( subject , embedding )
                                    Log.i( "Model" , "Similarity score for $name is ${p}.")
                                    if ( p > highestSimilarityScore ) {
                                        highestSimilarityScore = p
                                        highestSimilarityScoreName = name
                                    }
                                }
                                Log.i( "Model" , "Person identified as $highestSimilarityScoreName with " +
                                        "confidence of ${highestSimilarityScore * 100} %" )
                                // Push the results in form of a Prediction.
                                predictions.add(
                                    Prediction(
                                        face.boundingBox,
                                        highestSimilarityScoreName
                                    )
                                )
                                FaceAuthenticationActivity.setLabelText(highestSimilarityScoreName)
                            }
                            catch (e: Exception) {
                                // If any exception occurs with this box and continue with the next boxes.
                                continue
                            }
                        }

                        // Clear the BoundingBoxOverlay and set the new results ( boxes ) to be displayed.
                        boundingBoxOverlay.faceBoundingBoxes = predictions
                        boundingBoxOverlay.invalidate()

                        // Declare that the processing has been finished and the system is ready for the next frame.
                        isProcessing.set(false)

                    }.start()
                }
                .addOnFailureListener { e ->
                    Log.e("Error", e.message.toString())
                }
        }
    }

    // Cosine similarity for two vectors ( face embeddings ).
    // cosineSimilarity = embedding1.dot( embedding2 ) / ||embedding1|| * ||embedding2||
    private fun cosineSimilarity(x1: FloatArray, x2: FloatArray): Float {
        var dotProduct = 0.0f
        var mag1 = 0.0f
        var mag2 = 0.0f
        for(i in x1.indices) {
            dotProduct += ( x1[i] * x2[i] )
            mag1 += x1[i].toDouble().pow(2.0).toFloat()
            mag2 += x2[i].toDouble().pow(2.0).toFloat()
        }
        mag1 = sqrt(mag1)
        mag2 = sqrt(mag2)
        return dotProduct / (mag1 * mag2)
    }


    private fun degreesToFirebaseRotation(degrees: Int): Int = when(degrees) {
        0 -> FirebaseVisionImageMetadata.ROTATION_0
        90 -> FirebaseVisionImageMetadata.ROTATION_90
        180 -> FirebaseVisionImageMetadata.ROTATION_180
        270 -> FirebaseVisionImageMetadata.ROTATION_270
        else -> throw Exception("Rotation must be 0, 90, 180, or 270.")
    }

    private fun bitmapToNv21(bitmap: Bitmap): ByteArray {
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

    private fun toBitmap(image: Image): Bitmap {
        val yBuffer = image.planes[0].buffer
        val uBuffer = image.planes[1].buffer
        val vBuffer = image.planes[2].buffer
        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()
        val nv21 = ByteArray(ySize + uSize + vSize)
        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)
        val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, yuvImage.width, yuvImage.height), 50, out)
        val yuv = out.toByteArray()
        return BitmapFactory.decodeByteArray(yuv, 0, yuv.size)
    }

}