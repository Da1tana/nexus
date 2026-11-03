package com.nexus.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nexus.core.data.db.AppSourceEntity
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

private val avatarPalette = listOf(
    Color(0xFF5B8DEF), Color(0xFF43A07A), Color(0xFFE0894C), Color(0xFFB165C9),
    Color(0xFFD5596B), Color(0xFF3FA3B5), Color(0xFF8C7A3D), Color(0xFF6C74C9),
)

/** Аватар с инициалами. Цвет стабилен для одного имени. */
@Composable
fun InitialsAvatar(name: String, modifier: Modifier = Modifier, size: Dp = 44.dp) {
    val initials = name.split(' ', '-', '_')
        .filter { it.isNotBlank() }
        .take(2)
        .joinToString("") { it.first().uppercase() }
        .ifEmpty { "?" }
    val color = avatarPalette[abs(name.hashCode()) % avatarPalette.size]
    Box(
        modifier = modifier.size(size).clip(CircleShape).background(color),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initials,
            color = Color.White,
            style = if (size >= 40.dp) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
fun UnreadDot(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(10.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary),
    )
}

@Composable
fun CountBadge(count: Int, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary)
            .padding(horizontal = 7.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (count > 99) "99+" else count.toString(),
            color = MaterialTheme.colorScheme.onPrimary,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
        )
    }
}

/**
 * Строка фильтров: необязательный чип «Непрочитанные», «Все» и источники с иконками приложений.
 */
@Composable
fun SourceFilterRow(
    sources: List<AppSourceEntity>,
    selected: String?,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
    unreadOnly: Boolean? = null,
    onUnreadOnlyChange: (Boolean) -> Unit = {},
) {
    if (sources.isEmpty() && unreadOnly == null) return
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (unreadOnly != null) {
            item {
                FilterChip(
                    selected = unreadOnly,
                    onClick = { onUnreadOnlyChange(!unreadOnly) },
                    label = { Text("Непрочитанные") },
                )
            }
        }
        if (sources.size > 1) {
            item {
                FilterChip(selected = selected == null, onClick = { onSelect(null) }, label = { Text("Все") })
            }
        }
        items(sources, key = { it.packageName }) { source ->
            FilterChip(
                selected = selected == source.packageName,
                onClick = { onSelect(if (selected == source.packageName) null else source.packageName) },
                label = { Text(source.displayName) },
                leadingIcon = { AppIcon(source.packageName, size = 18.dp) },
            )
        }
    }
}

@Composable
fun EmptyState(title: String, message: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** Баннер, если доступ к уведомлениям не выдан: без него NEXUS не получает сообщения. */
@Composable
fun NotificationAccessBanner(onGrant: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(Modifier.padding(16.dp)) {
            Icon(Icons.Outlined.NotificationsOff, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
            Spacer(Modifier.height(8.dp))
            Text(
                "Нет доступа к уведомлениям",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "NEXUS получает сообщения из уведомлений других приложений. Включите NEXUS в списке «Доступ к уведомлениям».",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = onGrant) { Text("Открыть настройки") }
        }
    }
}

object TimeFormat {
    private val ru = Locale("ru")
    private val time = DateTimeFormatter.ofPattern("HH:mm", ru)
    private val dayMonth = DateTimeFormatter.ofPattern("d MMM", ru)
    private val full = DateTimeFormatter.ofPattern("dd.MM.yyyy", ru)
    private val dayHeader = DateTimeFormatter.ofPattern("d MMMM yyyy", ru)
    private val dateTime = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss", ru)

    private fun zoned(millis: Long) = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())

    fun day(millis: Long): LocalDate = zoned(millis).toLocalDate()

    /** Короткая метка для списков: время сегодня, дата в этом году, полная дата раньше. */
    fun short(millis: Long): String {
        val date = zoned(millis)
        val today = LocalDate.now()
        return when {
            date.toLocalDate() == today -> time.format(date)
            date.toLocalDate() == today.minusDays(1) -> "вчера"
            date.year == today.year -> dayMonth.format(date)
            else -> full.format(date)
        }
    }

    fun clock(millis: Long): String = time.format(zoned(millis))

    fun header(millis: Long): String {
        val date = day(millis)
        val today = LocalDate.now()
        return when (date) {
            today -> "Сегодня"
            today.minusDays(1) -> "Вчера"
            else -> dayHeader.format(zoned(millis))
        }
    }

    fun full(millis: Long?): String = millis?.let { dateTime.format(zoned(it)) } ?: "—"
}
