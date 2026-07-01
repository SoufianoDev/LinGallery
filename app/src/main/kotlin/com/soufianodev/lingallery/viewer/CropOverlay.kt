package com.soufianodev.lingallery.viewer

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import java.awt.Cursor

enum class HandlePosition {
    TopLeft, TopCenter, TopRight,
    CenterRight,
    BottomRight, BottomCenter, BottomLeft,
    CenterLeft,
    Inside, None
}

@Composable
fun CropOverlay(
    imageRect: Rect,
    initialCropRect: Rect?,
    cropEntryGen: Int,
    onCropChange: (Rect) -> Unit,
    modifier: Modifier = Modifier
) {
    var cropRect by remember(cropEntryGen) {
        mutableStateOf(
            initialCropRect ?: run {
                val insetX = imageRect.width * 0.1f
                val insetY = imageRect.height * 0.1f
                imageRect.deflate(insetX, insetY)
            }
        )
    }

    var activeHandle by remember { mutableStateOf(HandlePosition.None) }
    var hoveredHandle by remember { mutableStateOf(HandlePosition.None) }
    var dragStartOffset by remember { mutableStateOf(Offset.Zero) }
    var dragStartCrop by remember { mutableStateOf(cropRect) }

    LaunchedEffect(initialCropRect, imageRect) {
        if (activeHandle == HandlePosition.None) {
            cropRect = initialCropRect ?: run {
                val insetX = imageRect.width * 0.1f
                val insetY = imageRect.height * 0.1f
                imageRect.deflate(insetX, insetY)
            }
        }
    }

    val hitArea = 24.dp

    // Theme colors
    val primaryColor = MaterialTheme.colorScheme.primary
    val outlineVariant = MaterialTheme.colorScheme.outlineVariant
    val scrimColor = Color.Black.copy(alpha = 0.4f)

    // Animation states
    val isInteracting = activeHandle != HandlePosition.None || hoveredHandle != HandlePosition.None
    val gridAlpha by animateFloatAsState(
        targetValue = if (isInteracting) 1f else 0f,
        animationSpec = tween(durationMillis = 150)
    )
    val borderColor by animateColorAsState(
        targetValue = if (activeHandle == HandlePosition.Inside || (hoveredHandle == HandlePosition.Inside && activeHandle == HandlePosition.None)) primaryColor else outlineVariant,
        animationSpec = tween(durationMillis = 150)
    )

    @Composable
    fun handleColorState(pos: HandlePosition): Color {
        val color by animateColorAsState(
            targetValue = when {
                activeHandle == HandlePosition.Inside -> primaryColor
                activeHandle == pos -> primaryColor
                activeHandle != HandlePosition.None -> outlineVariant
                hoveredHandle == HandlePosition.Inside -> primaryColor
                hoveredHandle == pos -> primaryColor
                hoveredHandle != HandlePosition.None -> outlineVariant
                else -> outlineVariant
            },
            animationSpec = tween(durationMillis = 150)
        )
        return color
    }

    val tlColor = handleColorState(HandlePosition.TopLeft)
    val tcColor = handleColorState(HandlePosition.TopCenter)
    val trColor = handleColorState(HandlePosition.TopRight)
    val crColor = handleColorState(HandlePosition.CenterRight)
    val brColor = handleColorState(HandlePosition.BottomRight)
    val bcColor = handleColorState(HandlePosition.BottomCenter)
    val blColor = handleColorState(HandlePosition.BottomLeft)
    val clColor = handleColorState(HandlePosition.CenterLeft)

    val colorMap = mapOf(
        HandlePosition.TopLeft to tlColor,
        HandlePosition.TopCenter to tcColor,
        HandlePosition.TopRight to trColor,
        HandlePosition.CenterRight to crColor,
        HandlePosition.BottomRight to brColor,
        HandlePosition.BottomCenter to bcColor,
        HandlePosition.BottomLeft to blColor,
        HandlePosition.CenterLeft to clColor
    )

    val cursorIcon = remember(hoveredHandle) {
        val awtType = when (hoveredHandle) {
            HandlePosition.Inside -> Cursor.MOVE_CURSOR
            HandlePosition.TopLeft -> Cursor.NW_RESIZE_CURSOR
            HandlePosition.TopCenter -> Cursor.N_RESIZE_CURSOR
            HandlePosition.TopRight -> Cursor.NE_RESIZE_CURSOR
            HandlePosition.CenterRight -> Cursor.E_RESIZE_CURSOR
            HandlePosition.BottomRight -> Cursor.SE_RESIZE_CURSOR
            HandlePosition.BottomCenter -> Cursor.S_RESIZE_CURSOR
            HandlePosition.BottomLeft -> Cursor.SW_RESIZE_CURSOR
            HandlePosition.CenterLeft -> Cursor.W_RESIZE_CURSOR
            HandlePosition.None -> Cursor.DEFAULT_CURSOR
        }
        PointerIcon(Cursor.getPredefinedCursor(awtType))
    }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .pointerHoverIcon(cursorIcon, overrideDescendants = true)
            .pointerInput(imageRect) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: continue
                        val position = change.position

                        when (event.type) {
                            PointerEventType.Move, PointerEventType.Enter -> {
                                if (activeHandle == HandlePosition.None) {
                                    hoveredHandle = getHitHandle(position, cropRect, hitArea.toPx())
                                }
                            }
                            PointerEventType.Exit -> {
                                if (activeHandle == HandlePosition.None) {
                                    hoveredHandle = HandlePosition.None
                                }
                            }
                            PointerEventType.Press -> {
                                val hit = getHitHandle(position, cropRect, hitArea.toPx())
                                if (hit != HandlePosition.None) {
                                    activeHandle = hit
                                    dragStartOffset = position
                                    dragStartCrop = cropRect
                                    change.consume()
                                }
                            }
                            PointerEventType.Release -> {
                                if (activeHandle != HandlePosition.None) {
                                    onCropChange(cropRect)
                                    activeHandle = HandlePosition.None
                                    hoveredHandle = getHitHandle(position, cropRect, hitArea.toPx())
                                    change.consume()
                                }
                            }
                        }

                        if (activeHandle != HandlePosition.None && change.pressed) {
                            val delta = position - dragStartOffset
                            val minSize = 40f
                            var newLeft = dragStartCrop.left
                            var newTop = dragStartCrop.top
                            var newRight = dragStartCrop.right
                            var newBottom = dragStartCrop.bottom

                            when (activeHandle) {
                                HandlePosition.TopLeft -> {
                                    newLeft = (newLeft + delta.x).coerceAtMost(newRight - minSize).coerceAtLeast(imageRect.left)
                                    newTop = (newTop + delta.y).coerceAtMost(newBottom - minSize).coerceAtLeast(imageRect.top)
                                }
                                HandlePosition.TopCenter -> {
                                    newTop = (newTop + delta.y).coerceAtMost(newBottom - minSize).coerceAtLeast(imageRect.top)
                                }
                                HandlePosition.TopRight -> {
                                    newRight = (newRight + delta.x).coerceAtLeast(newLeft + minSize).coerceAtMost(imageRect.right)
                                    newTop = (newTop + delta.y).coerceAtMost(newBottom - minSize).coerceAtLeast(imageRect.top)
                                }
                                HandlePosition.CenterRight -> {
                                    newRight = (newRight + delta.x).coerceAtLeast(newLeft + minSize).coerceAtMost(imageRect.right)
                                }
                                HandlePosition.BottomRight -> {
                                    newRight = (newRight + delta.x).coerceAtLeast(newLeft + minSize).coerceAtMost(imageRect.right)
                                    newBottom = (newBottom + delta.y).coerceAtLeast(newTop + minSize).coerceAtMost(imageRect.bottom)
                                }
                                HandlePosition.BottomCenter -> {
                                    newBottom = (newBottom + delta.y).coerceAtLeast(newTop + minSize).coerceAtMost(imageRect.bottom)
                                }
                                HandlePosition.BottomLeft -> {
                                    newLeft = (newLeft + delta.x).coerceAtMost(newRight - minSize).coerceAtLeast(imageRect.left)
                                    newBottom = (newBottom + delta.y).coerceAtLeast(newTop + minSize).coerceAtMost(imageRect.bottom)
                                }
                                HandlePosition.CenterLeft -> {
                                    newLeft = (newLeft + delta.x).coerceAtMost(newRight - minSize).coerceAtLeast(imageRect.left)
                                }
                                HandlePosition.Inside -> {
                                    val minMoveX = minOf(imageRect.left - dragStartCrop.left, imageRect.right - dragStartCrop.right)
                                    val maxMoveX = maxOf(imageRect.left - dragStartCrop.left, imageRect.right - dragStartCrop.right)
                                    val moveX = delta.x.coerceIn(minMoveX, maxMoveX)
                                    val minMoveY = minOf(imageRect.top - dragStartCrop.top, imageRect.bottom - dragStartCrop.bottom)
                                    val maxMoveY = maxOf(imageRect.top - dragStartCrop.top, imageRect.bottom - dragStartCrop.bottom)
                                    val moveY = delta.y.coerceIn(minMoveY, maxMoveY)
                                    newLeft += moveX
                                    newRight += moveX
                                    newTop += moveY
                                    newBottom += moveY
                                }
                                HandlePosition.None -> {}
                            }

                            cropRect = Rect(newLeft, newTop, newRight, newBottom)
                            onCropChange(cropRect)
                            change.consume()
                        }
                    }
                }
            }
    ) {
        val cw = size.width
        val ch = size.height
        
        // Ensure we don't crash if cropRect is somehow inverted
        if (cropRect.width <= 0f || cropRect.height <= 0f) return@Canvas

        // Mask
        clipRect {
            drawRect(scrimColor, topLeft = Offset.Zero, size = Size(cw, cropRect.top))
            drawRect(scrimColor, topLeft = Offset(0f, cropRect.bottom), size = Size(cw, ch - cropRect.bottom))
            drawRect(scrimColor, topLeft = Offset(0f, cropRect.top), size = Size(cropRect.left, cropRect.height))
            drawRect(scrimColor, topLeft = Offset(cropRect.right, cropRect.top), size = Size(cw - cropRect.right, cropRect.height))
        }

        // Grid
        if (gridAlpha > 0f) {
            val gridColor = Color.White.copy(alpha = 0.5f * gridAlpha)
            val stepX = cropRect.width / 3f
            val stepY = cropRect.height / 3f
            val dashEffect = PathEffect.dashPathEffect(floatArrayOf(15f, 10f), 0f)
            for (i in 1..2) {
                drawLine(
                    color = gridColor,
                    start = Offset(cropRect.left + stepX * i, cropRect.top),
                    end = Offset(cropRect.left + stepX * i, cropRect.bottom),
                    strokeWidth = 1.dp.toPx(),
                    pathEffect = dashEffect
                )
                drawLine(
                    color = gridColor,
                    start = Offset(cropRect.left, cropRect.top + stepY * i),
                    end = Offset(cropRect.right, cropRect.top + stepY * i),
                    strokeWidth = 1.dp.toPx(),
                    pathEffect = dashEffect
                )
            }
        }

        // Border
        drawRect(
            color = borderColor,
            topLeft = cropRect.topLeft,
            size = cropRect.size,
            style = Stroke(width = 1.5.dp.toPx())
        )

        // Brackets
        val bracketLen = 20.dp.toPx()
        val bracketStroke = Stroke(
            width = 3.dp.toPx(),
            cap = StrokeCap.Round,
            join = StrokeJoin.Round,
            pathEffect = PathEffect.cornerPathEffect(6.dp.toPx())
        )
        
        fun drawRoundedBracket(pos: HandlePosition, startX: Float, startY: Float, cornerX: Float, cornerY: Float, endX: Float, endY: Float) {
            val path = Path().apply {
                moveTo(startX, startY)
                lineTo(cornerX, cornerY)
                lineTo(endX, endY)
            }
            drawPath(path, colorMap[pos] ?: borderColor, style = bracketStroke)
        }

        drawRoundedBracket(HandlePosition.TopLeft, cropRect.left, cropRect.top + bracketLen, cropRect.left, cropRect.top, cropRect.left + bracketLen, cropRect.top)
        drawRoundedBracket(HandlePosition.TopRight, cropRect.right - bracketLen, cropRect.top, cropRect.right, cropRect.top, cropRect.right, cropRect.top + bracketLen)
        drawRoundedBracket(HandlePosition.BottomRight, cropRect.right, cropRect.bottom - bracketLen, cropRect.right, cropRect.bottom, cropRect.right - bracketLen, cropRect.bottom)
        drawRoundedBracket(HandlePosition.BottomLeft, cropRect.left + bracketLen, cropRect.bottom, cropRect.left, cropRect.bottom, cropRect.left, cropRect.bottom - bracketLen)

        // Handles (Only on edges)
        val handles = listOf(
            HandlePosition.TopCenter to Offset(cropRect.left + cropRect.width / 2f, cropRect.top),
            HandlePosition.CenterRight to Offset(cropRect.right, cropRect.top + cropRect.height / 2f),
            HandlePosition.BottomCenter to Offset(cropRect.left + cropRect.width / 2f, cropRect.bottom),
            HandlePosition.CenterLeft to Offset(cropRect.left, cropRect.top + cropRect.height / 2f),
        )

        val defaultRadius = 4.dp.toPx()
        val hoverRadius = 6.dp.toPx()
        val dragRadius = 8.dp.toPx()

        for ((pos, offset) in handles) {
            val isHovered = hoveredHandle == pos
            val isDragged = activeHandle == pos
            
            val targetRadius = when {
                isDragged -> dragRadius
                isHovered -> hoverRadius
                else -> defaultRadius
            }
            
            drawCircle(
                color = colorMap[pos] ?: borderColor,
                radius = targetRadius,
                center = offset
            )
        }
    }
}

private fun getHitHandle(pos: Offset, crop: Rect, hitRadius: Float): HandlePosition {
    val cx = crop.left + crop.width / 2f
    val cy = crop.top + crop.height / 2f
    
    val handles = mapOf(
        HandlePosition.TopLeft to Offset(crop.left, crop.top),
        HandlePosition.TopCenter to Offset(cx, crop.top),
        HandlePosition.TopRight to Offset(crop.right, crop.top),
        HandlePosition.CenterRight to Offset(crop.right, cy),
        HandlePosition.BottomRight to Offset(crop.right, crop.bottom),
        HandlePosition.BottomCenter to Offset(cx, crop.bottom),
        HandlePosition.BottomLeft to Offset(crop.left, crop.bottom),
        HandlePosition.CenterLeft to Offset(crop.left, cy),
    )

    for ((handle, offset) in handles) {
        if ((pos - offset).getDistance() <= hitRadius) {
            return handle
        }
    }

    if (crop.contains(pos)) {
        return HandlePosition.Inside
    }

    return HandlePosition.None
}

private fun Rect.deflate(deltaX: Float, deltaY: Float): Rect {
    return Rect(left + deltaX, top + deltaY, right - deltaX, bottom - deltaY)
}
