package com.soufianodev.lingallery.gallery

import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import com.soufianodev.lingallery.ui.component.stablePointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.soufianodev.lingallery.app.Strings
import com.soufianodev.lingallery.model.Album
import com.soufianodev.lingallery.app.AppConst
import com.soufianodev.lingallery.ui.icons.AppIcons
import java.nio.file.Path
import com.soufianodev.lingallery.ui.theme.DarkPalette
import com.soufianodev.lingallery.ui.theme.LightPalette

@Composable
fun AlbumSidebar(
    albums: List<Album>,
    currentAlbumIndex: Int,
    onAlbumSelected: (Int) -> Unit,
    isDark: Boolean,
    deviceMounts: Set<Path> = emptySet(),
    modifier: Modifier = Modifier
) {
    val surface = if (isDark) DarkPalette.SURFACE else LightPalette.SURFACE
    val onSurface = if (isDark) DarkPalette.ON_SURFACE else LightPalette.ON_SURFACE
    val onSurfaceVariant = if (isDark) DarkPalette.ON_SURFACE_VARIANT else LightPalette.ON_SURFACE_VARIANT
    val primary = if (isDark) DarkPalette.PRIMARY else LightPalette.PRIMARY
    val primaryContainer = if (isDark) DarkPalette.PRIMARY_CONTAINER else LightPalette.PRIMARY_CONTAINER
    val surfaceVariant = if (isDark) DarkPalette.SURFACE_VARIANT else LightPalette.SURFACE_VARIANT
    val outlineVariant = if (isDark) DarkPalette.OUTLINE_VARIANT else LightPalette.OUTLINE_VARIANT

    Column(
        modifier = modifier
            .widthIn(min = 180.dp, max = AppConst.SIDEBAR_WIDTH.dp)
            .fillMaxHeight()
            .background(surface)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(AppConst.TOP_BAR_HEIGHT.dp)
                .padding(start = 16.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            Text(
                text = Strings.Gallery.albums,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                color = onSurface
            )
        }

        HorizontalDivider(color = outlineVariant)

        val scrollbarStyle = ScrollbarStyle(
            minimalHeight = 24.dp,
            thickness = 6.dp,
            shape = RoundedCornerShape(3.dp),
            hoverDurationMillis = 100,
            unhoverColor = primary.copy(alpha = 0.4f),
            hoverColor = primary.copy(alpha = 0.7f)
        )

        val albumListState = rememberLazyListState()

        Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = albumListState,
                horizontalAlignment = Alignment.Start
            ) {
                itemsIndexed(albums, key = { index, a -> "${index}_${a.path.toUri()}" }) { index, album ->
                    val isSelected = index == currentAlbumIndex
                    val bg = when {
                        isSelected -> primaryContainer.copy(alpha = 0.3f)
                        else -> surface
                    }
                    val textColor = if (isSelected) primary else onSurface

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .background(bg)
                            .clickable { onAlbumSelected(index) }
                            .stablePointerHoverIcon(PointerIcon.Hand)
                            .padding(start = 16.dp, end = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val isDeviceMount = album.path in deviceMounts
                        if (album.isDeviceAlbum || isDeviceMount) {
                            Icon(
                                imageVector = AppIcons.Smartphone,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = textColor
                            )
                            Spacer(Modifier.width(6.dp))
                        }
                        if (album.statusText != null) {
                            Text(
                                text = "${album.name}  (${album.statusText})",
                                fontSize = 14.sp,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                color = textColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                        } else {
                            Text(
                                text = "${album.name} (${album.imageCount})",
                                fontSize = 14.sp,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                color = textColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }

            VerticalScrollbar(
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                adapter = rememberScrollbarAdapter(albumListState),
                style = scrollbarStyle
            )
        }
    }
}
