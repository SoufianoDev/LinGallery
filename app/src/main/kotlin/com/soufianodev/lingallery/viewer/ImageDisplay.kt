package com.soufianodev.lingallery.viewer

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.skiaCanvas
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import com.soufianodev.lingallery.app.AppConst
import com.soufianodev.lingallery.app.Strings
import com.soufianodev.lingallery.model.ImageFile
import com.soufianodev.lingallery.native.MemoryManager
import com.soufianodev.lingallery.native.NativeImagePipeline
import com.soufianodev.lingallery.native.OwnedSkiaImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.awaitCancellation
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.pow
import java.nio.file.Path
import org.jetbrains.skia.Rect as SkiaRect
import org.jetbrains.skia.SamplingMode

data class DisplayCrop(
    val rect: Rect
)

@Composable
fun ImageDisplay(
    path: Path?,
    scale: Float,
    panX: Float,
    panY: Float,
    imageLastModified: Long = 0L,
    isCropping: Boolean,
    cropRect: Rect?,
    onScaleChange: (Float) -> Unit,
    onPanChange: (Float, Float) -> Unit,
    onCropRectChange: (DisplayCrop?) -> Unit,
    images: List<ImageFile> = emptyList(),
    currentIndex: Int = 0,
    modifier: Modifier = Modifier
) {
    var currentImage by remember { mutableStateOf<OwnedSkiaImage?>(null) }
    var loadError by remember { mutableStateOf(false) }
    var viewportWidth by remember { mutableIntStateOf(0) }
    var viewportHeight by remember { mutableIntStateOf(0) }
    val memoryPressure by MemoryManager.pressureLevel.collectAsState()

    var lastDecodePath by remember { mutableStateOf<Path?>(null) }
    var lastDecodeBucket by remember { mutableFloatStateOf(0f) }
    var lastImageLastModified by remember { mutableLongStateOf(0L) }

    LaunchedEffect(memoryPressure) {
        if (memoryPressure >= MemoryManager.PressureLevel.CRITICAL) {
            NativeImagePipeline.trim()
            MemoryManager.requestSkiaCleanup()
        }
    }

    var latestRequestId by remember { mutableLongStateOf(0L) }
    val decodeBucket = remember(scale) { NativeImagePipeline.decodeBucket(scale) }

    DisposableEffect(Unit) {
        onDispose {
            currentImage?.close()
            currentImage = null
            MemoryManager.requestSkiaCleanup()
        }
    }

    LaunchedEffect(path, imageLastModified, viewportWidth, viewportHeight, decodeBucket) {
        if (path == lastDecodePath && decodeBucket <= lastDecodeBucket
            && imageLastModified == lastImageLastModified
            && currentImage != null && !currentImage!!.isClosed) {
            return@LaunchedEffect
        }
        lastDecodePath = path
        lastDecodeBucket = decodeBucket
        lastImageLastModified = imageLastModified

        val requestId = NativeImagePipeline.nextRequestId()
        latestRequestId = requestId
        NativeImagePipeline.cancelAllExcept(requestId)
        loadError = false

        if (path == null || viewportWidth <= 0 || viewportHeight <= 0) {
            return@LaunchedEffect
        }

        try {
            val owned = withContext(Dispatchers.IO) {
                NativeImagePipeline.decodeViewer(
                    requestId = requestId,
                    path = path,
                    lastModified = imageLastModified,
                    viewportWidthPx = viewportWidth,
                    viewportHeightPx = viewportHeight,
                    zoomScale = scale,
                )?.toOwnedImage()
            }
            if (!isActive || latestRequestId != requestId) {
                owned?.close()
                return@LaunchedEffect
            }
            if (owned != null) {
                currentImage?.close()
                currentImage = owned
                MemoryManager.requestSkiaCleanup()
                loadError = false
            } else {
                loadError = true
            }
        } catch (_: CancellationException) {
            NativeImagePipeline.cancel(requestId)
        } catch (_: Exception) {
            if (latestRequestId == requestId) {
                loadError = true
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged {
                viewportWidth = it.width
                viewportHeight = it.height
            }
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center
    ) {
        when {
            loadError -> {
                Text(
                    text = Strings.Viewer.failedLoad,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyLarge
                )
            }
            currentImage != null -> {
                ImageDisplayContent(
                    image = currentImage!!,
                    scale = scale,
                    panX = panX,
                    panY = panY,
                    isCropping = isCropping,
                    cropRect = cropRect,
                    onScaleChange = onScaleChange,
                    onPanChange = onPanChange,
                    onCropRectChange = onCropRectChange,
                )
            }
        }
    }
}

@Composable
private fun ImageDisplayContent(
    image: OwnedSkiaImage,
    scale: Float,
    panX: Float,
    panY: Float,
    isCropping: Boolean,
    cropRect: Rect?,
    onScaleChange: (Float) -> Unit,
    onPanChange: (Float, Float) -> Unit,
    onCropRectChange: (DisplayCrop?) -> Unit
) {
    val imageWidth = image.sourceWidth.toFloat()
    val imageHeight = image.sourceHeight.toFloat()
    var currentScale by remember { mutableFloatStateOf(scale) }
    var currentPanX by remember { mutableFloatStateOf(panX) }
    var currentPanY by remember { mutableFloatStateOf(panY) }

    var viewWidth by remember { mutableFloatStateOf(0f) }
    var viewHeight by remember { mutableFloatStateOf(0f) }
    var cropEntryGen by remember { mutableIntStateOf(0) }
    val fadeAlpha by animateFloatAsState(
        targetValue = 1f,
        animationSpec = tween(300),
        label = "fadeAlpha"
    )

    LaunchedEffect(isCropping) {
        if (isCropping) cropEntryGen++
    }

    LaunchedEffect(scale) {
        if (scale != currentScale) currentScale = scale
    }
    LaunchedEffect(panX, panY) {
        if (panX != currentPanX || panY != currentPanY) {
            currentPanX = panX
            currentPanY = panY
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { clip = true }
            .onSizeChanged { newSize ->
                viewWidth = newSize.width.toFloat()
                viewHeight = newSize.height.toFloat()
            }
            .pointerInput(isCropping) {
                if (!isCropping) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        val newScale = (currentScale * zoom).coerceIn(
                            AppConst.ZOOM_MIN.toFloat(),
                            AppConst.ZOOM_MAX.toFloat()
                        )
                        val newPanX = currentPanX + pan.x
                        val newPanY = currentPanY + pan.y

                        val viewW = this.size.width.toFloat()
                        val viewH = this.size.height.toFloat()
                        val fitScale = minOf(viewW / imageWidth, viewH / imageHeight)
                        val displayScale = newScale * fitScale
                        val imgW = imageWidth * displayScale
                        val imgH = imageHeight * displayScale

                        val clampedPanX = if (imgW > viewW) {
                            newPanX.coerceIn(-((imgW - viewW) / 2f), (imgW - viewW) / 2f)
                        } else 0f
                        val clampedPanY = if (imgH > viewH) {
                            newPanY.coerceIn(-((imgH - viewH) / 2f), (imgH - viewH) / 2f)
                        } else 0f

                        if (newScale != currentScale) {
                            currentScale = newScale
                            onScaleChange(newScale)
                        }
                        if (clampedPanX != currentPanX || clampedPanY != currentPanY) {
                            currentPanX = clampedPanX
                            currentPanY = clampedPanY
                            onPanChange(clampedPanX, clampedPanY)
                        }
                    }
                }
            }
            .pointerInput(isCropping) {
                if (!isCropping) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Main)
                            if (event.type != PointerEventType.Scroll) continue
                            val change = event.changes.firstOrNull() ?: continue
                            val delta = change.scrollDelta.y
                            if (delta == 0f) continue

                            val zoomFactor = AppConst.ZOOM_STEP.toFloat().pow(delta)
                            val newScale = (currentScale * zoomFactor)
                                .coerceIn(AppConst.ZOOM_MIN.toFloat(), AppConst.ZOOM_MAX.toFloat())
                            if (newScale == currentScale) continue

                            val mousePos = change.position
                            val viewW = size.width.toFloat()
                            val viewH = size.height.toFloat()
                            val fitScale = minOf(viewW / imageWidth, viewH / imageHeight)

                            val oldDisplayScale = currentScale * fitScale
                            val oldImgW = imageWidth * oldDisplayScale
                            val oldImgH = imageHeight * oldDisplayScale
                            val oldImgX = (viewW - oldImgW) / 2f + currentPanX
                            val oldImgY = (viewH - oldImgH) / 2f + currentPanY

                            val nx = if (oldImgW > 0f) (mousePos.x - oldImgX) / oldImgW else 0.5f
                            val ny = if (oldImgH > 0f) (mousePos.y - oldImgY) / oldImgH else 0.5f

                            val newDisplayScale = newScale * fitScale
                            val newImgW = imageWidth * newDisplayScale
                            val newImgH = imageHeight * newDisplayScale

                            val newPanX = mousePos.x - (viewW - newImgW) / 2f - nx * newImgW
                            val newPanY = mousePos.y - (viewH - newImgH) / 2f - ny * newImgH

                            val clampedPanX = if (newImgW > viewW) {
                                newPanX.coerceIn(-((newImgW - viewW) / 2f), (newImgW - viewW) / 2f)
                            } else 0f
                            val clampedPanY = if (newImgH > viewH) {
                                newPanY.coerceIn(-((newImgH - viewH) / 2f), (newImgH - viewH) / 2f)
                            } else 0f

                            currentScale = newScale
                            currentPanX = clampedPanX
                            currentPanY = clampedPanY
                            onScaleChange(newScale)
                            onPanChange(clampedPanX, clampedPanY)
                            change.consume()
                        }
                    }
                }
            }
    ) {
        val fitScale = if (viewWidth > 0f && viewHeight > 0f && imageWidth > 0f && imageHeight > 0f) {
            minOf(viewWidth / imageWidth, viewHeight / imageHeight)
        } else 1f
        val displayScale = currentScale * fitScale
        val imgW = imageWidth * displayScale
        val imgH = imageHeight * displayScale
        val imgX = (viewWidth - imgW) / 2f + currentPanX
        val imgY = (viewHeight - imgH) / 2f + currentPanY

        val imageRect = Rect(imgX, imgY, imgX + imgW, imgY + imgH)

        Canvas(modifier = Modifier.fillMaxSize()) {
            if (!image.isClosed) {
                drawIntoCanvas { canvas ->
                    canvas.skiaCanvas.drawImageRect(
                        image.skiaImage,
                        SkiaRect(0f, 0f, image.decodedWidth.toFloat(), image.decodedHeight.toFloat()),
                        SkiaRect(imgX, imgY, imgX + imgW, imgY + imgH),
                        SamplingMode.LINEAR,
                        null,
                        true
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = isCropping && viewWidth > 0f && viewHeight > 0f,
            enter = fadeIn(tween(280, easing = FastOutSlowInEasing)),
            exit = fadeOut(tween(200, easing = FastOutSlowInEasing))
        ) {
            Box(Modifier.fillMaxSize()) {
        Canvas(modifier = Modifier.fillMaxSize().graphicsLayer { alpha = fadeAlpha }) {
                    drawRect(
                        color = Color.White.copy(alpha = 0.12f),
                        topLeft = imageRect.topLeft,
                        size = imageRect.size,
                        style = Stroke(width = 1.dp.toPx())
                    )
                }

                val initialCropRect = cropRect?.let { r ->
                    val cx = imgX + r.left * displayScale
                    val cy = imgY + r.top * displayScale
                    val cr = imgX + r.right * displayScale
                    val cb = imgY + r.bottom * displayScale
                    Rect(cx, cy, cr, cb)
                }

                CropOverlay(
                    imageRect = imageRect,
                    initialCropRect = initialCropRect,
                    cropEntryGen = cropEntryGen,
                    onCropChange = { newRect ->
                        val dx = ((newRect.left - imgX) / displayScale).coerceIn(0f, imageWidth)
                        val dy = ((newRect.top - imgY) / displayScale).coerceIn(0f, imageHeight)
                        val dr = ((newRect.right - imgX) / displayScale).coerceIn(0f, imageWidth)
                        val db = ((newRect.bottom - imgY) / displayScale).coerceIn(0f, imageHeight)
                        if (dr - dx > 5f && db - dy > 5f) {
                            onCropRectChange(DisplayCrop(Rect(dx, dy, dr, db)))
                        } else {
                            onCropRectChange(null)
                        }
                    }
                )
            }
        }
    }
}

private fun cropCanvasToSource(
    viewW: Float, viewH: Float,
    cropStart: Offset, cropEnd: Offset,
    currentScale: Float, currentPanX: Float, currentPanY: Float,
    imageWidth: Float, imageHeight: Float
): DisplayCrop? {
    val canvasLeft   = minOf(cropStart.x, cropEnd.x)
    val canvasTop    = minOf(cropStart.y, cropEnd.y)
    val canvasRight  = maxOf(cropStart.x, cropEnd.x)
    val canvasBottom = maxOf(cropStart.y, cropEnd.y)
    if (canvasRight - canvasLeft <= 0f || canvasBottom - canvasTop <= 0f) return null
    val fitScale = minOf(viewW / imageWidth, viewH / imageHeight)
    val displayScale = currentScale * fitScale
    val imgW = imageWidth  * displayScale
    val imgH = imageHeight * displayScale
    val imgX = (viewW - imgW) / 2f + currentPanX
    val imgY = (viewH - imgH) / 2f + currentPanY
    if (imageWidth <= 0f || imageHeight <= 0f) return null

    val displayX      = ((canvasLeft   - imgX) / displayScale).coerceIn(0f, imageWidth)
    val displayY      = ((canvasTop    - imgY) / displayScale).coerceIn(0f, imageHeight)
    val displayRight  = ((canvasRight  - imgX) / displayScale).coerceIn(0f, imageWidth)
    val displayBottom = ((canvasBottom - imgY) / displayScale).coerceIn(0f, imageHeight)

    return DisplayCrop(rect = Rect(displayX, displayY, displayRight, displayBottom))
}
