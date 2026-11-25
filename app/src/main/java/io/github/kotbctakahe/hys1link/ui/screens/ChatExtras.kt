package io.github.kotbctakahe.hys1link.ui.screens

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.exifinterface.media.ExifInterface
import coil.compose.AsyncImage
import io.github.kotbctakahe.hys1link.core.Core
import io.github.kotbctakahe.hys1link.core.Sender
import io.github.kotbctakahe.hys1link.core.StickerItem
import io.github.kotbctakahe.hys1link.core.StickerPack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/** Reads a picked image, fixes EXIF rotation, scales to ≤1600px and re-encodes (strips metadata incl. GPS). */
object MediaPrep {
    private const val MAX = 1600

    fun image(ctx: Context, uri: Uri): Sender.LocalFile? = runCatching {
        val cr = ctx.contentResolver
        val type = cr.getType(uri) ?: "image/jpeg"
        val raw = cr.openInputStream(uri)!!.use { it.readBytes() }
        if (type == "image/gif") return@runCatching Sender.LocalFile(raw, type, "image.gif")
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX) sample *= 2
        var bmp = BitmapFactory.decodeByteArray(raw, 0, raw.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return@runCatching null
        val rotation = runCatching {
            when (ExifInterface(raw.inputStream()).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        }.getOrDefault(0f)
        val scale = MAX.toFloat() / maxOf(bmp.width, bmp.height)
        if (rotation != 0f || scale < 1f) {
            val m = Matrix().apply { if (scale < 1f) postScale(scale, scale); if (rotation != 0f) postRotate(rotation) }
            bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        }
        val png = type == "image/png" && bmp.hasAlpha()
        val out = ByteArrayOutputStream()
        bmp.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 85, out)
        Sender.LocalFile(out.toByteArray(), if (png) "image/png" else "image/jpeg", if (png) "image.png" else "image.jpg", bmp.width, bmp.height)
    }.getOrNull()
}

private var cachedPacks: List<StickerPack>? = null

/** Sticker drawer with the user's Link sticker packs (falls back to popular packs). */
@Composable
fun StickerPanel(core: Core, onPick: (StickerItem) -> Unit) {
    var packs by remember { mutableStateOf(cachedPacks) }
    var error by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        if (packs == null) runCatching { withContext(Dispatchers.IO) { core.social.stickerPacks() } }
            .onSuccess { packs = it; cachedPacks = it }.onFailure { error = it.message ?: "Стикеры недоступны" }
    }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLowest, shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
        modifier = Modifier.fillMaxWidth().height(300.dp)) {
        val list = packs
        when {
            list == null && error == null -> Box(Modifier.fillMaxWidth().height(300.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            list.isNullOrEmpty() -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                Text(error ?: "Добавьте наборы стикеров в магазине Link на телефоне — они появятся здесь.",
                    textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else -> Column {
                LazyRow(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    itemsIndexed(list) { i, p ->
                        Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp))
                            .background(if (i == selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                            .clickable { selected = i }.padding(5.dp)) {
                            AsyncImage(p.cover, p.title, Modifier.size(34.dp))
                        }
                    }
                }
                val pack = list[selected.coerceIn(0, list.size - 1)]
                LazyVerticalGrid(GridCells.Adaptive(76.dp), contentPadding = PaddingValues(8.dp)) {
                    items(pack.stickers, key = { "${pack.id}:${it.id}" }) { st ->
                        Box(Modifier.aspectRatio(1f).padding(4.dp).clip(RoundedCornerShape(14.dp)).clickable { onPick(st) }) {
                            AsyncImage(io.github.kotbctakahe.hys1link.social.mediaUrl(st.url), st.emoji, Modifier.fillMaxWidth().aspectRatio(1f))
                        }
                    }
                }
            }
        }
    }
}
