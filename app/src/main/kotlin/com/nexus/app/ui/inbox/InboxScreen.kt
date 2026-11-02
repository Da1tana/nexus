package com.nexus.app.ui.inbox

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import com.nexus.app.system.SystemStatus
import com.nexus.app.ui.components.EmptyState
import com.nexus.app.ui.components.InitialsAvatar
import com.nexus.app.ui.components.NotificationAccessBanner
import com.nexus.app.ui.components.SourceFilterRow
import com.nexus.app.ui.components.TimeFormat
import com.nexus.app.ui.components.UnreadDot
import com.nexus.core.data.db.MessageListItem
import com.nexus.core.data.repository.MessageRepository
import com.nexus.core.data.repository.SourceRepository
import com.nexus.core.model.MessageDirection
import com.nexus.core.model.MessageStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class InboxViewModel @Inject constructor(
    private val messages: MessageRepository,
    sources: SourceRepository,
) : ViewModel() {

    val selectedSource = MutableStateFlow<String?>(null)
    val unreadOnly = MutableStateFlow(false)

    val sources = sources.sourcesWithMessages()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val unreadCount: StateFlow<Int> = messages.unreadCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    @OptIn(ExperimentalCoroutinesApi::class)
    val feed: Flow<PagingData<MessageListItem>> = combine(selectedSource, unreadOnly) { source, unread -> source to unread }
        .flatMapLatest { (source, unread) -> messages.inbox(source, unread) }
        .cachedIn(viewModelScope)

    fun markAllRead() {
        viewModelScope.launch { messages.markAllRead() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InboxScreen(
    onOpenConversation: (Long) -> Unit,
    viewModel: InboxViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    var accessGranted by remember { mutableStateOf(SystemStatus.isNotificationAccessGranted(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        accessGranted = SystemStatus.isNotificationAccessGranted(context)
    }

    val sources by viewModel.sources.collectAsStateWithLifecycle()
    val selected by viewModel.selectedSource.collectAsStateWithLifecycle()
    val unreadOnly by viewModel.unreadOnly.collectAsStateWithLifecycle()
    val unread by viewModel.unreadCount.collectAsStateWithLifecycle()
    val items = viewModel.feed.collectAsLazyPagingItems()

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Входящие")
                        Text(
                            if (unread > 0) "Непрочитанных: $unread" else "Всё прочитано",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::markAllRead, enabled = unread > 0) {
                        Icon(Icons.Outlined.DoneAll, contentDescription = "Отметить всё прочитанным")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (!accessGranted) {
                NotificationAccessBanner(onGrant = { SystemStatus.openNotificationAccessSettings(context) })
            }
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                FilterChip(
                    selected = unreadOnly,
                    onClick = { viewModel.unreadOnly.value = !unreadOnly },
                    label = { Text("Только непрочитанные") },
                )
            }
            SourceFilterRow(sources, selected, onSelect = { viewModel.selectedSource.value = it })
            Spacer(Modifier.height(4.dp))

            if (items.itemCount == 0) {
                EmptyState(
                    title = if (unreadOnly) "Нет непрочитанных" else "Сообщений пока нет",
                    message = if (accessGranted) {
                        "Новые сообщения из подключённых приложений появятся здесь, как только придут уведомления."
                    } else {
                        "Разрешите доступ к уведомлениям, чтобы NEXUS начал собирать сообщения."
                    },
                )
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(count = items.itemCount, key = items.itemKey { it.message.id }) { index ->
                        val item = items[index] ?: return@items
                        InboxRow(item, onClick = { onOpenConversation(item.message.conversationId) })
                        HorizontalDivider(Modifier.padding(start = 72.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    }
                }
            }
        }
    }
}

@Composable
private fun InboxRow(item: MessageListItem, onClick: () -> Unit) {
    val message = item.message
    val unread = message.status == MessageStatus.RECEIVED
    val sender = when {
        message.direction == MessageDirection.OUTGOING -> "Вы"
        else -> message.senderName ?: item.conversationTitle
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        InitialsAvatar(item.conversationTitle)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = item.conversationTitle,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = if (unread) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    TimeFormat.short(message.timestamp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    item.sourceName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                if (item.conversationIsGroup) {
                    Text(
                        "· $sender",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (message.textHidden) "Текст скрыт приложением" else message.body,
                    style = MaterialTheme.typography.bodyMedium,
                    fontStyle = if (message.textHidden) FontStyle.Italic else FontStyle.Normal,
                    color = if (unread) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (unread) {
                    Spacer(Modifier.width(8.dp))
                    UnreadDot()
                }
            }
        }
    }
}
