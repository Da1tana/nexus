package com.nexus.app.ui.components

import android.content.Context
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Кэш иконок приложений и аватаров. Ключ иконки — пакет, ключ аватара — путь (имя файла содержит хэш). */
private object ImageCache {
    private val icons = LruCache<String, Holder>(128)
    private val avatars = LruCache<String, Holder>(256)

    /** Обёртка, чтобы кэшировать и отсутствие изображения. */
    class Holder(val image: ImageBitmap?)

    fun cachedIcon(pkg: String): Holder? = icons.get(pkg)

    fun cachedAvatar(path: String): Holder? = avatars.get(path)

    suspend fun icon(context: Context, pkg: String): ImageBitmap? {
        icons.get(pkg)?.let { return it.image }
        val image = withContext(Dispatchers.IO) {
            try {
                context.packageManager.getApplicationIcon(pkg).toBitmap(96, 96).asImageBitmap()
            } catch (e: Exception) {
                null
            }
        }
        icons.put(pkg, Holder(image))
        return image
    }

    suspend fun avatar(path: String): ImageBitmap? {
        avatars.get(path)?.let { return it.image }
        val image = withContext(Dispatchers.IO) {
            try {
                BitmapFactory.decodeFile(path)?.asImageBitmap()
            } catch (e: Exception) {
                null
            }
        }
        avatars.put(path, Holder(image))
        return image
    }
}

@Composable
fun rememberAppIcon(packageName: String): ImageBitmap? {
    val context = LocalContext.current
    val image by produceState(ImageCache.cachedIcon(packageName)?.image, packageName) {
        value = ImageCache.icon(context, packageName)
    }
    return image
}

@Composable
private fun rememberAvatar(path: String?): ImageBitmap? {
    if (path == null) return null
    val image by produceState(ImageCache.cachedAvatar(path)?.image, path) {
        value = ImageCache.avatar(path)
    }
    return image
}

/** Иконка приложения-источника. */
@Composable
fun AppIcon(packageName: String, modifier: Modifier = Modifier, size: Dp = 20.dp) {
    val icon = rememberAppIcon(packageName)
    if (icon != null) {
        Image(
            bitmap = icon,
            contentDescription = null,
            modifier = modifier.size(size).clip(RoundedCornerShape(size / 4)),
        )
    } else {
        Box(
            modifier
                .size(size)
                .clip(RoundedCornerShape(size / 4))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
    }
}

/**
 * Аватар беседы: изображение из уведомления или инициалы, плюс значок приложения-источника в углу —
 * сразу видно, откуда сообщение, без чтения подписи.
 */
@Composable
fun ConversationAvatar(
    title: String,
    avatarPath: String?,
    sourcePackage: String?,
    modifier: Modifier = Modifier,
    size: Dp = 52.dp,
) {
    val avatar = rememberAvatar(avatarPath)
    Box(modifier.size(size)) {
        if (avatar != null) {
            Image(
                bitmap = avatar,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(size).clip(CircleShape),
            )
        } else {
            InitialsAvatar(title, size = size)
        }
        if (sourcePackage != null) {
            val badge = size * 0.4f
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .size(badge)
                    .clip(RoundedCornerShape(badge / 3))
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.5.dp, MaterialTheme.colorScheme.surface, RoundedCornerShape(badge / 3))
                    .padding(1.5.dp),
            ) {
                AppIcon(sourcePackage, size = badge - 3.dp)
            }
        }
    }
}
