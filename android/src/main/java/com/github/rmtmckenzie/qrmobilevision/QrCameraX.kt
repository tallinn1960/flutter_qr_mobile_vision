package com.github.rmtmckenzie.qrmobilevision

import android.app.Activity
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.Surface.ROTATION_0
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.ResolutionInfo
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.TimeUnit

class QrCameraX(
    private val activity: Activity,
    private val texture: SurfaceTexture,
    private val callback: QrReaderCallbacks,
    barcodeScannerOptions: BarcodeScannerOptions,
) : QrCamera {
    private var cameraProvider: ProcessCameraProvider? = null
    private var preview: Preview? = null
    private var camera: Camera? = null
    private var scanner = BarcodeScanning.getClient(barcodeScannerOptions)
    private var lastScanned: List<String?>? = null
    private var resolution: ResolutionInfo? = null


    /**
     * callback for the camera. Every frame is passed through this function.
     */
    @ExperimentalGetImage
    val captureOutput = ImageAnalysis.Analyzer { imageProxy -> // YUV_420_888 format
        val mediaImage = imageProxy.image ?: return@Analyzer
        val inputImage = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

        scanner.process(inputImage)
            .addOnSuccessListener { barcodes ->
                val newScannedBarcodes = barcodes.map { barcode -> barcode.rawValue }
                if (newScannedBarcodes == lastScanned) {
                    // New scanned is duplicate, returning
                    return@addOnSuccessListener
                }
                if (newScannedBarcodes.isNotEmpty()) lastScanned = newScannedBarcodes

                if (barcodes.isNotEmpty()) {
                    callback.qrRead(barcodes.first().rawValue)

                }
            }
            .addOnCompleteListener { imageProxy.close() }
    }

    @Throws(QrReader.Exception::class)
    override fun start(cameraDirection: Int) {
        if (camera?.cameraInfo != null && preview != null) {
            throw QrReader.Exception(QrReader.Exception.Reason.alreadyStarted)
        }

        val cameraProviderFuture = ProcessCameraProvider.getInstance(activity)
        val executor = ContextCompat.getMainExecutor(activity)

        cameraProvider = cameraProviderFuture.get(500, TimeUnit.MILLISECONDS)
        if (cameraProvider == null) {
            throw QrReader.Exception(QrReader.Exception.Reason.noHardware)
        }
        cameraProvider!!.unbindAll()

        // Preview
        val surfaceProvider = Preview.SurfaceProvider { request ->
            texture.setDefaultBufferSize(
                request.resolution.width,
                request.resolution.height
            )

            val surface = Surface(texture)
            request.provideSurface(surface, executor) { }
        }

        // Build the preview to be shown on the Flutter texture
        val previewBuilder = Preview.Builder().setTargetRotation(ROTATION_0)
        preview = previewBuilder.build().apply { setSurfaceProvider(surfaceProvider) }

        // Build the analyzer to be passed on to MLKit
        val analysisBuilder = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
        val analysis = analysisBuilder.build().apply { setAnalyzer(executor, captureOutput) }

        camera = cameraProvider!!.bindToLifecycle(
            activity as LifecycleOwner,
            if (cameraDirection != 0) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA,
            preview,
            analysis
        )
        resolution = preview?.resolutionInfo
    }

    override fun stop() {
        cameraProvider?.unbindAll()

        camera = null
        preview = null
        cameraProvider = null
    }

    override fun toggleFlash() {}
    override fun getOrientation(): Int {
        return preview?.resolutionInfo?.rotationDegrees ?: 0
    }

    override fun getWidth(): Int {
        return preview?.resolutionInfo?.resolution?.width ?: 0
    }

    override fun getHeight(): Int {
        return preview?.resolutionInfo?.resolution?.height ?: 0
    }
}