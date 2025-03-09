package com.example.aicamera.presentation.main

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.android.gms.tasks.TaskExecutors
import com.google.common.util.concurrent.ListenableFuture
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlin.math.max

@SuppressLint("UnusedBoxWithConstraintsScope")
@Composable
fun CameraPreview(
    modifier: Modifier = Modifier
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current
    var sizeInfo by remember { mutableStateOf(SizeInfo(500, 500)) }
    var detectedFaces by remember { mutableStateOf<List<Face>>(emptyList()) }
    val previewView = remember { PreviewView(context) }
    val cameraProvider = remember(sizeInfo) {
        ProcessCameraProvider.getInstance(context)
            .configureCamera(
                previewView,
                lifecycleOwner,
                context,
                setSizeInfo = { sizeInfo = it },
                onFacesDetected = { detectedFaces = it })
    }

    BoxWithConstraints(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        with(LocalDensity.current) {
            Box(
                modifier = Modifier
                    .size(
                        height = sizeInfo.height.toDp(),
                        width = sizeInfo.width.toDp()
                    )
                    .scale(
                        calculateScale(
                            constraints,
                            sizeInfo
                        )
                    )
            ) {
                AndroidView(
                    factory = {
                        previewView
                    },
                    modifier = modifier
                )
                DetectedFaces(detectedFaces)
            }
        }
    }

}

data class SizeInfo(
    var width: Int,
    var height: Int,
)

private fun ListenableFuture<ProcessCameraProvider>.configureCamera(
    previewView: PreviewView,
    lifecycleOwner: LifecycleOwner,
    context: Context,
    setSizeInfo: (SizeInfo) -> Unit,
    onFacesDetected: (List<Face>) -> Unit
): ListenableFuture<ProcessCameraProvider> {
    addListener({
        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

        val preview = Preview.Builder()
            .build()
            .apply {
                surfaceProvider = previewView.surfaceProvider
            }

        val analysis = bindAnalysisUseCase(setSizeInfo, onFacesDetected)

        try {
            get().apply {
                unbindAll()
                bindToLifecycle(lifecycleOwner, cameraSelector, preview)
                bindToLifecycle(lifecycleOwner, cameraSelector, analysis)
            }
        } catch (e: Exception) {
            TODO()
        }
    }, ContextCompat.getMainExecutor(context))
    return this
}

private fun calculateScale(
    constraints: Constraints,
    sizeInfo: SizeInfo,
): Float {
    val heightRation = constraints.maxHeight.toFloat() / sizeInfo.height
    val widthRation = constraints.maxWidth.toFloat() / sizeInfo.width

    return max(heightRation, widthRation)
}

private fun bindAnalysisUseCase(
    setSizeInfo: (SizeInfo) -> Unit,
    onFacesDetected: (List<Face>) -> Unit
): ImageAnalysis? {
    val imageProcessor = try {
        FaceDetectorProcessor()
    } catch (e: Exception) {
        return null
    }

    val builder = ImageAnalysis.Builder()
    val analysisUseCase = builder.build()

    var sizeInfoUpdate = false

    analysisUseCase.setAnalyzer(
        TaskExecutors.MAIN_THREAD,
        { imageProxy: ImageProxy ->
            if (!sizeInfoUpdate) {
                setSizeInfo(obtainSourseInfo(imageProxy))
                sizeInfoUpdate = true
            }
           // Log.d("MyTag", "face ")
            try {
                imageProcessor.processImageProxy(imageProxy, onFacesDetected)
            } catch (e: MlKitException) {
                Log.e("MyTag", "Failed to process image. Error: " + e.localizedMessage)
            }
        }
    )
    return analysisUseCase
}

private fun obtainSourseInfo(imageProxy: ImageProxy): SizeInfo {
    val rotationDegrees = imageProxy.imageInfo.rotationDegrees
    return if (rotationDegrees == 0 || rotationDegrees == 180) {
        SizeInfo(
            imageProxy.width,
            imageProxy.height
        )
    } else {
        SizeInfo(
            imageProxy.height,
            imageProxy.width
        )
    }
}

class FaceDetectorProcessor {
    private val detector: FaceDetector
    private val executor = TaskExecutors.MAIN_THREAD

    init {
        val faceDetectorOptions = FaceDetectorOptions.Builder()
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
            .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setMinFaceSize(0.4f)
            .build()

        detector = FaceDetection.getClient(faceDetectorOptions)
    }

    fun stop() {
        detector.close()
    }

    @SuppressLint("UnsafeOptInUsageError")
    fun processImageProxy(image: ImageProxy, onDetectionFinished: (List<Face>) -> Unit) {
        detector.process(InputImage.fromMediaImage(image.image!!, image.imageInfo.rotationDegrees))
            .addOnSuccessListener(executor) { results: List<Face> ->
                onDetectionFinished(results)
            }
            .addOnFailureListener(executor) { e: Exception ->
                Log.e("MyTag", "Error detecting face", e)
            }
            .addOnCanceledListener { image.close() }
    }
}

@Composable
fun DetectedFaces(
    faces: List<Face>
) {
    androidx.compose.foundation.Canvas(
        modifier = Modifier.fillMaxSize()
    ) {
        for (face in faces) {
            drawRect(
                Color.Gray, style = Stroke(2.dp.toPx()),
                topLeft = Offset(face.boundingBox.left.toFloat(), face.boundingBox.top.toFloat()),
                size = Size(face.boundingBox.width().toFloat(), face.boundingBox.height().toFloat())
            )
        }
    }
}