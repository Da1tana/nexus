package com.nexus.app.notifications

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.os.Build
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.graphics.drawable.toBitmap
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Аватары бесед из уведомлений (largeIcon или иконка отправителя MessagingStyle).
 * Хранятся во внутренней папке приложения, недоступной другим приложениям; уменьшаются до 128 px.
 * Файл перезаписывается, только если изображение изменилось.
 */
@Singleton
class AvatarStore @Inject constructor(@ApplicationContext private val context: Context) {

    private val dir: File by lazy { File(context.filesDir, "avatars").apply { mkdirs() } }
    private val lastHashByConversation = ConcurrentHashMap<Long, String>()

    /** Возвращает путь к новому файлу, если аватар изменился; иначе null. */
    fun save(conversationId: Long, sbn: StatusBarNotification): String? {
        val bitmap = extract(sbn) ?: return null
        val scaled = Bitmap.createScaledBitmap(bitmap, SIZE, SIZE, true)
        val bytes = java.io.ByteArrayOutputStream().use { out ->
            scaled.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray()
        }
        val hash = sha1(bytes)
        if (lastHashByConversation[conversationId] == hash) return null
        val file = File(dir, "$conversationId-$hash.png")
        if (!file.exists()) file.writeBytes(bytes)
        lastHashByConversation[conversationId] = hash
        return file.absolutePath
    }

    fun delete(path: String?) {
        if (path == null) return
        val file = File(path)
        if (file.parentFile == dir) file.delete()
    }

    fun clear() {
        dir.listFiles()?.forEach { it.delete() }
        lastHashByConversation.clear()
    }

    private fun extract(sbn: StatusBarNotification): Bitmap? {
        val notification = sbn.notification
        val large: Icon? = notification.getLargeIcon()
        val drawable = try {
            large?.loadDrawable(context)
                ?: NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification)
                    ?.messages
                    ?.lastOrNull { it.person?.icon != null }
                    ?.person?.icon?.loadDrawable(context)
        } catch (e: RuntimeException) {
            null
        } ?: return null
        val width = drawable.intrinsicWidth.takeIf { it > 0 } ?: SIZE
        val height = drawable.intrinsicHeight.takeIf { it > 0 } ?: SIZE
        return try {
            drawable.toBitmap(width, height, if (Build.VERSION.SDK_INT >= 26) Bitmap.Config.ARGB_8888 else null)
        } catch (e: RuntimeException) {
            null
        }
    }

    private fun sha1(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-1").digest(bytes).take(8).joinToString("") { "%02x".format(it) }

    private companion object {
        const val SIZE = 128
    }
}
