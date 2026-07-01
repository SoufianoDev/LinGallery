package com.soufianodev.lingallery.gallery

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.animation.core.tween
import com.github.panpf.sketch.PlatformContext
import com.github.panpf.sketch.SingletonSketch
import com.soufianodev.lingallery.app.AppConst
import com.soufianodev.lingallery.app.Strings
import com.soufianodev.lingallery.devices.core.DeviceActivityMap
import com.soufianodev.lingallery.devices.ui.DeviceActivityPresenter
import com.soufianodev.lingallery.ui.theme.DarkPalette
import com.soufianodev.lingallery.ui.theme.LightPalette
import java.nio.file.Path

@Composable
fun GalleryScreen(
    stateHolder: GalleryStateHolder,
    deviceMounts: Set<Path> = emptySet(),
    deviceActivity: DeviceActivityMap = DeviceActivityMap(),
    activityPresenter: DeviceActivityPresenter,
    onImageSelected: (Int) -> Unit
) {
    val state by stateHolder.uiState.collectAsState()
    val isDark = true
    val bg = if (isDark) DarkPalette.BACKGROUND else LightPalette.BACKGROUND
    val surface = if (isDark) DarkPalette.SURFACE else LightPalette.SURFACE
    val onSurface = if (isDark) DarkPalette.ON_SURFACE else LightPalette.ON_SURFACE
    val onSurfaceVariant = if (isDark) DarkPalette.ON_SURFACE_VARIANT else LightPalette.ON_SURFACE_VARIANT
    val primary = if (isDark) DarkPalette.PRIMARY else LightPalette.PRIMARY
    val outlineVariant = if (isDark) DarkPalette.OUTLINE_VARIANT else LightPalette.OUTLINE_VARIANT

    val statusMessage by stateHolder.statusMessage.collectAsState()
    val badges = remember(deviceActivity) { activityPresenter.toSidebarBadges(deviceActivity) }
    val statusBarActivityText = remember(deviceActivity) { activityPresenter.toStatusBarText(deviceActivity) }
    val displayText = statusBarActivityText ?: statusMessage.ifEmpty { "Ready" }

    Column(modifier = Modifier.fillMaxSize().background(bg)) {
        Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
            AlbumSidebar(
                albums = state.albums,
                currentAlbumIndex = state.currentAlbumIndex,
                badges = badges,
                onAlbumSelected = { index ->
                    val prevAlbum = state.albums.getOrNull(state.currentAlbumIndex)
                    if (prevAlbum?.isDeviceAlbum == true) {
                        if (prevAlbum.path in state.pendingPhoneSort) {
                            stateHolder.updateState { it.sortPhoneByRecent(prevAlbum.path) }
                        }
                        SingletonSketch.get(PlatformContext.INSTANCE).memoryCache.clear()
                        org.jetbrains.skia.Graphics.purgeResourceCache()
                    }
                    stateHolder.onAction(GalleryAction.SelectAlbum(index))
                },
                isDark = isDark,
                deviceMounts = deviceMounts,
            )

            Box(
                modifier = Modifier
                    .width(1.dp)
                    .fillMaxHeight()
                    .background(outlineVariant)
            )

            Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                Surface(
                    modifier = Modifier.fillMaxWidth().height(AppConst.TOP_BAR_HEIGHT.dp),
                    color = surface
                ) {
                    Row(
                        modifier = Modifier.fillMaxSize().padding(start = 20.dp, end = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = Strings.App.name,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = primary,
                            letterSpacing = (-0.5).sp
                        )
                        Spacer(Modifier.width(16.dp))
                        state.currentAlbum?.let { album ->
                            Text(
                                text = album.path.toString(),
                                fontSize = 12.sp,
                                color = onSurfaceVariant,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }

                HorizontalDivider(color = outlineVariant)

                AnimatedVisibility(
                    visible = state.isScanning,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }

                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    val currentAlbum = state.currentAlbum
                    val showScanning = state.isScanning
                        && (currentAlbum == null || (currentAlbum.images.isEmpty() && !currentAlbum.isDeviceAlbum))
                    if (showScanning) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(Strings.Status.scanningShort, color = onSurfaceVariant, fontSize = 15.sp)
                        }
                    } else {
                        key(state.currentAlbum?.path) {
                            val gridState = rememberLazyGridState()

                            LaunchedEffect(state.currentAlbum?.path) {
                                stateHolder.scrollEffects.collect {
                                    when (it) {
                                        is ScrollEffect.ScrollToTop -> gridState.scrollToItem(0)
                                    }
                                }
                            }

                            GalleryGrid(
                                images = state.currentAlbumImages,
                                gridState = gridState,
                                onImageClicked = { index -> onImageSelected(index) },
                                onImageDoubleClicked = { index -> onImageSelected(index) },
                                isDark = isDark,
                                hasAlbums = state.albums.isNotEmpty()
                            )
                        }
                    }
                }
            }
        }

        Surface(
            modifier = Modifier.fillMaxWidth().height(32.dp),
            color = surface.copy(alpha = 0.95f)
        ) {
            Row(
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AnimatedVisibility(
                    visible = statusBarActivityText != null,
                    enter = fadeIn(tween(200)) + expandHorizontally(tween(200)),
                    exit = fadeOut(tween(150)) + shrinkHorizontally(tween(150)),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color = onSurfaceVariant,
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                }
                Text(
                    text = displayText,
                    fontSize = 12.sp,
                    color = onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
