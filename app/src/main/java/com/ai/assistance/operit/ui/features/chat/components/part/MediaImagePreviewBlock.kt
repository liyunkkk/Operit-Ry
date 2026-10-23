package com.ai.assistance.operit.ui.features.chat.components.part

import android.graphics.Bitmap
import android.util.Base64
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.ai.assistance.operit.R
import com.ai.assistance.operit.api.chat.llmprovider.ImageLinkTag
import com.ai.assistance.operit.util.AppLogger
import com.ai.assistance.operit.util.ImageBitmapLimiter
import com.ai.assistance.operit.util.ImagePoolManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "MediaImagePreviewBlock"

private val THUMBNAIL_SIZE = 120.dp

// 缩略图只铺满 THUMBNAIL_SIZE（3x 屏约 360px），按屏显尺寸解码，
// 避免把整张原图（1440x3200 的截图约 17MB ARGB_8888）常驻内存。
// 维度上限放宽到 1600 是为了竖长截图：截图以宽度决定清晰度，若上限卡在 512，
// 3200 高会把采样多降一档，宽度只剩 180px 却被放大到 360px。
private const val THUMBNAIL_MAX_PIXELS = 400_000L
private const val THUMBNAIL_MAX_DIMENSION = 1600

// 全屏预览才需要接近原图的清晰度：点击时按需解码一次。
// 对话框最多显示约一屏宽、500dp 高，4M 像素 / 2048 长边已足够，不必按整张原图分配内存。
private const val PREVIEW_MAX_PIXELS = 4_000_000L
private const val PREVIEW_MAX_DIMENSION = 2048
private val PREVIEW_MAX_HEIGHT = 500.dp

/** 当前打开的预览：缩略图立即显示，全尺寸位图解码完成后替换。 */
private data class ImagePreviewTarget(val link: ImageLinkTag, val thumbnail: Bitmap)

/**
 * 智能体用 read_file 查看图片时，图片会以 `<link type="image" id="...">` 写进消息内容，作为
 * 下一跳的多模态输入。聊天里不应该显示这条原始标记，而是渲染成可展开的图片预览。
 *
 * 图片可能已经被图片池回收（磁盘缓存被清理），此时按“已过期”展示而不是留下空白；
 * 标签带了源路径、且源文件还在时会先从源文件恢复回池子。
 */
@Composable
internal fun MediaImagePreviewBlock(
    imageLinks: List<ImageLinkTag>,
    textColor: Color,
    modifier: Modifier = Modifier,
    enableDialogs: Boolean = true,
) {
    if (imageLinks.isEmpty()) {
        return
    }

    // 宿主每次组合都会新建 id 列表，用内容做键，避免重复解码。
    val idsKey = imageLinks.joinToString(",") { it.id }

    // null 表示尚未解码完成，避免解码期间误报“已过期”。
    val bitmaps by
        produceState<Map<String, Bitmap?>?>(initialValue = null, key1 = idsKey) {
            value =
                withContext(Dispatchers.IO) {
                    imageLinks.associate { link ->
                        link.id to
                            decodeImageLinkBitmap(
                                link,
                                THUMBNAIL_MAX_PIXELS,
                                THUMBNAIL_MAX_DIMENSION,
                            )
                    }
                }
        }

    var expanded by remember(idsKey) { mutableStateOf(true) }
    var previewTarget by remember(idsKey) { mutableStateOf<ImagePreviewTarget?>(null) }
    var previewBitmap by remember(idsKey) { mutableStateOf<Bitmap?>(null) }
    val previewScope = rememberCoroutineScope()
    // 只被点击回调读写，不参与组合，因此用 MutableState 持有也不会引起重组。
    val previewJobRef = remember(idsKey) { mutableStateOf<Job?>(null) }
    val closePreview = {
        previewJobRef.value?.cancel()
        previewJobRef.value = null
        previewTarget = null
        previewBitmap = null
    }

    val title = stringResource(R.string.chat_viewed_images, imageLinks.size)
    val collapsedStateText = stringResource(R.string.collapsed)
    val expandedStateText = stringResource(R.string.expanded)
    val arrowRotation by
        animateFloatAsState(
            targetValue = if (expanded) 90f else 0f,
            animationSpec = tween(durationMillis = 200),
            label = "media-image-preview-arrow",
        )

    Column(modifier = modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(
            modifier =
                Modifier.fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .semantics(mergeDescendants = true) {
                        // 标题交给行内的 Text 提供，这里只补状态，避免同一句话被朗读两遍
                        stateDescription =
                            if (expanded) {
                                expandedStateText
                            } else {
                                collapsedStateText
                            }
                    },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = Icons.Default.PhotoLibrary,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = textColor.copy(alpha = 0.7f),
            )
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                modifier = Modifier.size(18.dp).rotate(arrowRotation),
            )
        }

        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn(animationSpec = tween(200)),
            exit = fadeOut(animationSpec = tween(200)),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp, start = 22.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                imageLinks.forEach { link ->
                    val bitmap = bitmaps?.get(link.id)
                    when {
                        bitmaps == null -> PendingImagePlaceholder()
                        bitmap != null ->
                            Card(
                                modifier =
                                    Modifier.size(THUMBNAIL_SIZE).clickable(enabled = enableDialogs) {
                                        // 缩略图先顶上，全尺寸版本解码就位后再替换。
                                        previewJobRef.value?.cancel()
                                        previewTarget = ImagePreviewTarget(link, bitmap)
                                        previewBitmap = null
                                        previewJobRef.value =
                                            previewScope.launch {
                                                val decoded =
                                                    withContext(Dispatchers.IO) {
                                                        decodeImageLinkBitmap(
                                                            link,
                                                            PREVIEW_MAX_PIXELS,
                                                            PREVIEW_MAX_DIMENSION,
                                                        )
                                                    }
                                                // 用户可能已经关闭或切到别的图，丢弃过期结果。
                                                if (decoded != null &&
                                                    previewTarget?.link?.id == link.id
                                                ) {
                                                    previewBitmap = decoded
                                                }
                                            }
                                    },
                                shape = RoundedCornerShape(8.dp),
                                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                            ) {
                                Image(
                                    bitmap = bitmap.asImageBitmap(),
                                    contentDescription = stringResource(R.string.image),
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop,
                                )
                            }
                        else -> ExpiredImagePlaceholder(textColor = textColor)
                    }
                }
            }
        }
    }

    val target = previewTarget
    if (enableDialogs && target != null) {
        // 全尺寸位图解码期间先用缩略图，避免对话框闪空白。
        val displayBitmap = previewBitmap ?: target.thumbnail
        val previewScrollState = remember(target.link.id) { ScrollState(0) }
        Dialog(onDismissRequest = closePreview) {
            Surface(
                modifier = Modifier.fillMaxWidth().wrapContentHeight(),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 4.dp,
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Default.PhotoLibrary,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                text = stringResource(R.string.image_preview),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        IconButton(onClick = closePreview) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = stringResource(R.string.close),
                                tint = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                    // 竖长图（手机截图）按宽度铺满后纵向滚动，避免超出屏幕被裁掉。
                    Box(
                        modifier =
                            Modifier.fillMaxWidth()
                                .heightIn(max = PREVIEW_MAX_HEIGHT)
                                .clip(RoundedCornerShape(8.dp))
                                .verticalScroll(previewScrollState),
                        contentAlignment = Alignment.Center,
                    ) {
                        Image(
                            bitmap = displayBitmap.asImageBitmap(),
                            contentDescription = title,
                            modifier = Modifier.fillMaxWidth().wrapContentHeight(),
                            contentScale = ContentScale.Fit,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PendingImagePlaceholder() {
    Surface(
        modifier = Modifier.size(THUMBNAIL_SIZE),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        tonalElevation = 0.dp,
    ) {}
}

@Composable
private fun ExpiredImagePlaceholder(textColor: Color) {
    Surface(
        modifier = Modifier.size(THUMBNAIL_SIZE),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        tonalElevation = 0.dp,
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = stringResource(R.string.image_expired),
                style = MaterialTheme.typography.labelSmall,
                color = textColor.copy(alpha = 0.6f),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 6.dp),
            )
        }
    }
}

private fun decodeImageLinkBitmap(
    link: ImageLinkTag,
    maxPixels: Long,
    maxDimension: Int,
): Bitmap? {
    val imageData = ImagePoolManager.resolveImage(link.id, link.sourcePath) ?: return null
    return try {
        ImageBitmapLimiter.decodeDownsampledBitmap(
            Base64.decode(imageData.base64, Base64.DEFAULT),
            maxPixels = maxPixels,
            maxDimension = maxDimension,
        )
    } catch (e: Exception) {
        AppLogger.e(TAG, "Failed to decode pooled image: ${link.id}", e)
        null
    }
}
