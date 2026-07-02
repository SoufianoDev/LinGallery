package com.soufianodev.lingallery.gallery

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.skiaCanvas
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.soufianodev.lingallery.app.Strings
import com.soufianodev.lingallery.model.ImageFile
import com.soufianodev.lingallery.ui.icons.AppIcons
import com.soufianodev.lingallery.native.MemoryManager
import com.soufianodev.lingallery.native.NativeImagePipeline
import com.soufianodev.lingallery.native.NativeSvgPipeline
import com.soufianodev.lingallery.native.ImagePipelineRole
import com.soufianodev.lingallery.native.OwnedSkiaImage
import com.soufianodev.lingallery.ui.component.stablePointerHoverIcon
import com.soufianodev.lingallery.ui.theme.DarkPalette
import com.soufianodev.lingallery.ui.theme.LightPalette
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import kotlin.io.path.extension
import java.nio.file.Path
import kotlin.math.max

private const val MAX_THUMBNAILS = 80
private const val MAX_THUMBNAIL_EDGE_PX = 220
private val thumbnailDecodeThrottle = Semaphore(3)

private data class ThumbnailKey(
    val index: Int,
    val path: String,
    val lastModified: Long,
)

private fun decodeSvgThumbnail(
    requestId: Long,
    path: Path,
    targetSize: Int,
    key: ThumbnailKey,
): OwnedSkiaImage? {
    val handle = NativeSvgPipeline.load(path)
    if (handle < 0L) return null
    try {
        val bytes = NativeSvgPipeline.render(handle, targetSize, targetSize) ?: return null
        return NativeImagePipeline.parseResult(
            requestId,
            "${path}_svg_thumb",
            ImagePipelineRole.SVG_THUMBNAIL,
            bytes
        )?.toOwnedImage()
    } finally {
        NativeSvgPipeline.release(handle)
    }
}

private class GalleryThumbnailController(
    private val scope: CoroutineScope,
) {
    val images = mutableStateMapOf<ThumbnailKey, OwnedSkiaImage>()
    private val generations = mutableMapOf<ThumbnailKey, Long>()
    private val jobs = mutableMapOf<ThumbnailKey, Job>()
    private val requestIds = mutableMapOf<ThumbnailKey, Long>()
    private var nextGeneration = 1L

    fun reconcile(
        albumImages: List<ImageFile>,
        activeIndices: Set<Int>,
        cellSizePx: Int,
    ) {
        val activeKeys = activeIndices.mapNotNull { index ->
            albumImages.getOrNull(index)?.let { image ->
                ThumbnailKey(index, image.path.toAbsolutePath().normalize().toString(), image.lastModified)
            }
        }.toSet()

        val staleKeys = (images.keys + jobs.keys + generations.keys).filter { it !in activeKeys }
        staleKeys.forEach(::evict)

        val target = cellSizePx.coerceIn(1, MAX_THUMBNAIL_EDGE_PX)
        activeKeys.take(MAX_THUMBNAILS).forEach { key ->
            if (images.containsKey(key) || jobs.containsKey(key)) return@forEach
            val image = albumImages.getOrNull(key.index) ?: return@forEach
            val generation = nextGeneration++
            generations[key] = generation
            val requestId = NativeImagePipeline.nextRequestId()
            requestIds[key] = requestId
            jobs[key] = scope.launch {
                try {
                    val owned = thumbnailDecodeThrottle.withPermit {
                        withContext(Dispatchers.IO) {
                            if (image.extension == ".svg") {
                                decodeSvgThumbnail(requestId, image.path, target, key)
                            } else {
                                NativeImagePipeline.decodeThumbnail(
                                    requestId = requestId,
                                    path = image.path,
                                    lastModified = image.lastModified,
                                    targetWidthPx = target,
                                    targetHeightPx = target,
                                )?.toOwnedImage()
                            }
                        }
                    }
                    val stillActive = generations[key] == generation && key in activeKeys
                    if (owned != null && stillActive) {
                        images.put(key, owned)?.close()
                    } else {
                        owned?.close()
                    }
                } finally {
                    jobs.remove(key)
                    requestIds.remove(key)
                }
            }
        }

        while (images.size > MAX_THUMBNAILS) {
            images.keys.firstOrNull()?.let(::evict) ?: break
        }
    }

    fun evict(key: ThumbnailKey) {
        generations.remove(key)
        requestIds.remove(key)?.let(NativeImagePipeline::cancel)
        jobs.remove(key)?.cancel()
        images.remove(key)?.close()
        MemoryManager.requestSkiaCleanup()
    }

    fun closeAll() {
        jobs.values.forEach { it.cancel() }
        requestIds.values.forEach(NativeImagePipeline::cancel)
        jobs.clear()
        requestIds.clear()
        generations.clear()
        images.values.forEach { it.close() }
        images.clear()
        NativeImagePipeline.trim()
        MemoryManager.requestSkiaCleanup()
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GalleryGrid(
    images: List<ImageFile>,
    gridState: LazyGridState,
    onImageClicked: (Int) -> Unit,
    onImageDoubleClicked: (Int) -> Unit,
    isDark: Boolean,
    hasAlbums: Boolean = true,
    modifier: Modifier = Modifier
) {
    val primary = if (isDark) DarkPalette.PRIMARY else LightPalette.PRIMARY
    val surfaceContainer = if (isDark) DarkPalette.SURFACE_CONTAINER else LightPalette.SURFACE_CONTAINER
    val onSurfaceVariant = if (isDark) DarkPalette.ON_SURFACE_VARIANT else LightPalette.ON_SURFACE_VARIANT
    val scrollbarStyle = ScrollbarStyle(
        minimalHeight = 24.dp,
        thickness = 6.dp,
        shape = RoundedCornerShape(3.dp),
        hoverDurationMillis = 100,
        unhoverColor = primary.copy(alpha = 0.4f),
        hoverColor = primary.copy(alpha = 0.7f)
    )
    val scope = rememberCoroutineScope()
    val controller = remember(images) {
        GalleryThumbnailController(scope)
    }
    val pressure by MemoryManager.pressureLevel.collectAsState()

    DisposableEffect(controller) {
        onDispose { controller.closeAll() }
    }

    LaunchedEffect(images, gridState, pressure) {
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo }
            .collectLatest { visible ->
                if (visible.isEmpty()) return@collectLatest
                val columns = max(1, visible.groupingBy { it.offset.y }.eachCount().values.maxOrNull() ?: 1)
                val prefetchRows = if (pressure >= MemoryManager.PressureLevel.WARN) 0 else 1
                val first = visible.minOf { it.index }
                val last = visible.maxOf { it.index }
                val start = (first - columns * prefetchRows).coerceAtLeast(0)
                val end = (last + columns * prefetchRows).coerceAtMost(images.lastIndex)
                val cellSizePx = visible.firstOrNull()?.size?.width ?: MAX_THUMBNAIL_EDGE_PX
                controller.reconcile(
                    albumImages = images,
                    activeIndices = (start..end).toSet(),
                    cellSizePx = cellSizePx,
                )
            }
    }

    if (images.isEmpty()) {
        Box(
            modifier = modifier.fillMaxSize().padding(18.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = if (hasAlbums) Strings.Gallery.empty
                       else Strings.Gallery.noAlbums,
                fontSize = 15.sp,
                color = onSurfaceVariant
            )
        }
    } else {
        Box(modifier = modifier.fillMaxSize()) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(160.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(18.dp),
                state = gridState,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(
                    images,
                    key = { _, img -> "${img.path}_${img.lastModified}" },
                    contentType = { _, _ -> "thumbnail" }
                ) { index, image ->
                    val key = ThumbnailKey(index, image.path.toAbsolutePath().normalize().toString(), image.lastModified)
                    Box(
                        modifier = Modifier
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(surfaceContainer.copy(alpha = 0.7f), RoundedCornerShape(8.dp))
                            .stablePointerHoverIcon(PointerIcon.Hand)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(4.dp)
                                .combinedClickable(
                                    onClick = { onImageClicked(index) },
                                    onDoubleClick = { onImageDoubleClicked(index) }
                                )
                        ) {
                            ThumbnailImage(
                                owned = controller.images[key],
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(6.dp))
                            )
                        }
                    }
                }
            }

            VerticalScrollbar(
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                adapter = rememberScrollbarAdapter(gridState),
                style = scrollbarStyle
            )
        }
    }
}

@Composable
private fun ThumbnailImage(
    owned: OwnedSkiaImage?,
    contentScale: ContentScale,
    modifier: Modifier = Modifier,
) {
    if (owned == null || owned.isClosed) {
        Box(
            modifier = modifier.background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.5f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = AppIcons.BrokenImage,
                contentDescription = "Broken image",
                modifier = Modifier.size(45.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
        }
        return
    }
    SkiaImageCanvas(owned = owned, contentScale = contentScale, modifier = modifier)
}

@Composable
private fun SkiaImageCanvas(
    owned: OwnedSkiaImage,
    contentScale: ContentScale,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        if (owned.isClosed) return@Canvas
        val imageW = owned.decodedWidth.toFloat()
        val imageH = owned.decodedHeight.toFloat()
        val scale = when (contentScale) {
            ContentScale.Crop -> max(size.width / imageW, size.height / imageH)
            else -> minOf(size.width / imageW, size.height / imageH)
        }
        val drawW = imageW * scale
        val drawH = imageH * scale
        val left = (size.width - drawW) / 2f
        val top = (size.height - drawH) / 2f
        drawIntoCanvas { canvas ->
            canvas.skiaCanvas.drawImageRect(
                owned.skiaImage,
                Rect(0f, 0f, imageW, imageH),
                Rect(left, top, left + drawW, top + drawH),
                SamplingMode.LINEAR,
                null,
                true
            )
        }
    }
}
