package com.soufianodev.lingallery.viewer

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
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
import com.soufianodev.lingallery.ui.icons.AppIcons
import com.soufianodev.lingallery.model.ImageFile
import com.soufianodev.lingallery.native.MemoryManager
import com.soufianodev.lingallery.native.ImagePipelineRole
import com.soufianodev.lingallery.native.LinLogger
import com.soufianodev.lingallery.native.NativeImagePipeline
import com.soufianodev.lingallery.native.NativeSvgPipeline
import com.soufianodev.lingallery.native.OwnedSkiaImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.awaitCancellation
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.pow
import java.nio.file.Path
import kotlin.io.path.extension
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
    svgDocumentHandle: Long = -1L,
    svgEditVersion: Int = 0,
    svgSourceWidth: Float = 0f,
    svgSourceHeight: Float = 0f,
    modifier: Modifier = Modifier
) {
    var currentImage by remember { mutableStateOf<OwnedSkiaImage?>(null) }
    var loadError by remember { mutableStateOf(false) }
    var viewportWidth by remember { mutableIntStateOf(0) }
    var viewportHeight by remember { mutableIntStateOf(0) }
    val memoryPressure by MemoryManager.pressureLevel.collectAsState()

    var lastDecodePath by remember { mutableStateOf<Path?>(null) }
    var lastImageLastModified by remember { mutableLongStateOf(0L) }

    // SVG-specific deduplication state
    var lastSvgHandle by remember { mutableLongStateOf(-1L) }
    var lastSvgVersion by remember { mutableIntStateOf(-1) }

    LaunchedEffect(memoryPressure) {
        if (memoryPressure >= MemoryManager.PressureLevel.CRITICAL) {
            NativeImagePipeline.trim()
            MemoryManager.requestSkiaCleanup()
        }
    }

    var latestRequestId by remember { mutableLongStateOf(0L) }

    val isSvg = path?.extension?.lowercase() == "svg"

    DisposableEffect(Unit) {
        onDispose {
            currentImage?.close()
            currentImage = null
            MemoryManager.requestSkiaCleanup()
        }
    }

    LaunchedEffect(
        path, imageLastModified, viewportWidth, viewportHeight, svgDocumentHandle, svgEditVersion
    ) {
        if (!isSvg) {
            if (path == lastDecodePath
                && imageLastModified == lastImageLastModified
                && currentImage != null && !currentImage!!.isClosed) {
                return@LaunchedEffect
            }
        } else {
            // Skip SVG re-render if the handle and edit version are unchanged.
            if (svgDocumentHandle == lastSvgHandle
                && svgEditVersion == lastSvgVersion
                && currentImage != null && !currentImage!!.isClosed) {
                return@LaunchedEffect
            }
            // Debounce: wait for rapid zoom scrolling to settle before rendering.
            delay(80L)
            if (!isActive) return@LaunchedEffect
        }
        if (path != lastDecodePath) {
            currentImage?.close()
            currentImage = null
        }

        lastDecodePath = path
        lastImageLastModified = imageLastModified
        lastSvgHandle = svgDocumentHandle
        lastSvgVersion = svgEditVersion

        val requestId = NativeImagePipeline.nextRequestId()
        latestRequestId = requestId
        NativeImagePipeline.cancelAllExcept(requestId)
        loadError = false

        if (path == null || viewportWidth <= 0 || viewportHeight <= 0) {
            return@LaunchedEffect
        }

        try {
            /* What crosses the withContext boundary is a DecodedImageDto — plain JVM data —
               and NOT an OwnedSkiaImage. This matters: withContext discards its return value
               and throws CancellationException when the job is cancelled while the block runs,
               so anything constructed inside it can become unreachable without ever being
               handed back. A dropped dto is ordinary garbage; a dropped OwnedSkiaImage strands
               a full-size native Skia buffer that only a later GC + Cleaner can free, which is
               how zooming an SVG used to accumulate gigabytes of native memory. */
            val dto = if (isSvg && svgDocumentHandle >= 0L) {
                withContext(Dispatchers.IO) {
                    val renderW = viewportWidth.coerceAtLeast(1)
                    val renderH = viewportHeight.coerceAtLeast(1)
                    val bytes = NativeSvgPipeline.render(
                        svgDocumentHandle, renderW, renderH, requestId
                    ) ?: return@withContext null
                    NativeImagePipeline.parseResult(
                        requestId,
                        "${path}_svg_v${svgEditVersion}",
                        ImagePipelineRole.SVG_VIEWER,
                        bytes
                    )
                }
            } else {
                withContext(Dispatchers.IO) {
                    NativeImagePipeline.decodeViewer(
                        requestId = requestId,
                        path = path,
                        lastModified = imageLastModified,
                        viewportWidthPx = viewportWidth,
                        viewportHeightPx = viewportHeight,
                    )
                }
            }

            // Staleness is settled before any native allocation happens.
            if (!isActive || latestRequestId != requestId) {
                return@LaunchedEffect
            }
            if (dto == null) {
                loadError = true
                return@LaunchedEffect
            }

            /* toOwnedImage() is the only step that allocates native Skia memory, so it runs
               here — on a path where its result is always reachable and always closeable. */
            val previous = currentImage
            currentImage = dto.toOwnedImage()
            LinLogger.d(
                "ImageDisplay",
                "image installed: ${dto.decodedWidth}x${dto.decodedHeight} (${dto.pixels.size} bytes)" +
                    if (previous != null) ", closing previous ${previous.byteCount} bytes" else ""
            )
            /* Closed after the new one is installed, never before: that way `currentImage` is
               never observably pointing at a closed image, so no draw can pick up a freed
               buffer. Peak is unchanged either way — nothing suspends between these two
               statements — and the real peak reduction comes from the Rust side. */
            previous?.close()
            MemoryManager.requestSkiaCleanup()
            loadError = false
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
            .background(MaterialTheme.colorScheme.background)
            .clipToBounds(),
        contentAlignment = Alignment.Center
    ) {
        when {
            loadError -> {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = AppIcons.BrokenImage,
                        contentDescription = "Broken image",
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = Strings.Viewer.failedLoad,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
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
