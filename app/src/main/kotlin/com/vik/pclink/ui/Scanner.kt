package com.vik.pclink.ui

import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

@Composable
fun ScannerDialog(onResult: (String) -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val owner = ctx as LifecycleOwner
    val executor = remember { Executors.newSingleThreadExecutor() }
    val scanner = remember {
        BarcodeScanning.getClient(BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build())
    }
    val done = remember { AtomicBoolean(false) }

    fun start(preview: PreviewView) {
        val future = ProcessCameraProvider.getInstance(ctx)
        future.addListener({
            if (done.get()) return@addListener
            val provider = future.get()
            val use = Preview.Builder().build().also { it.surfaceProvider = preview.surfaceProvider }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(executor) { proxy ->
                val media = proxy.image
                if (media == null || done.get()) {
                    proxy.close()
                    return@setAnalyzer
                }
                scanner.process(InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees))
                    .addOnSuccessListener { codes ->
                        val raw = codes.firstNotNullOfOrNull { it.rawValue }
                        if (raw != null && done.compareAndSet(false, true)) onResult(raw)
                    }
                    .addOnCompleteListener { proxy.close() }
            }
            provider.unbindAll()
            provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, use, analysis)
        }, ContextCompat.getMainExecutor(ctx))
    }

    DisposableEffect(Unit) {
        onDispose {
            done.set(true)
            runCatching { ProcessCameraProvider.getInstance(ctx).get().unbindAll() }
            executor.shutdown()
            scanner.close()
        }
    }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AndroidView({ c -> PreviewView(c).apply { scaleType = PreviewView.ScaleType.FILL_CENTER; start(this) } }, Modifier.fillMaxSize())
            Text(
                "Point at the code shown in PCLink on your PC",
                color = Color.White,
                modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(24.dp),
            )
            AppButton(
                "Cancel", onClose,
                Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(24.dp).fillMaxWidth(),
                ButtonKind.Ghost, large = true,
            )
        }
    }
}
