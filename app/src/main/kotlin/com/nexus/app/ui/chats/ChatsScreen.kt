package com.nexus.app.ui.chats

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Reply
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import com.nexus.app.ui.components.CountBadge
import com.nexus.app.ui.components.EmptyState
import com.nexus.app.ui.components.InitialsAvatar
import com.nexus.app.ui.components.SourceFilterRow
import com.nexus.app.ui.components.TimeFormat
import com.nexus.core.data.db.ConversationListItem
import com.nexus.core.data.repository.ConversationRepository
import com.nexus.core.data.repository.SourceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class ChatsViewModel @Inject constructor(
    conversations: ConversationRepository,
    sources: SourceRepository,
) : ViewModel() {
    val selectedSource = MutableStateFlow<String?>(null)

    val sources = sources.sourcesWithMessages()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    @OptIn(ExperimentalCoroutinesApi::class)
    val conversations: Flow<PagingData<ConversationListItem>> = selectedSource
        .flatMapLatest { conversations.conversations(it) }
        .cachedIn(viewModelScope)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatsScreen(
    onOpenConversation: (Long) -> Unit,
    viewModel: ChatsViewModel = hiltViewModel(),
) {
    val sources by viewModel.sources.collectAsStateWithLifecycle()
    val selected by viewModel.selectedSource.collectAsStateWithLifecycle()
    val items = viewModel.conversations.collectAsLazyPagingItems()

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { TopAppBar(title = { Text("Чаты") }) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            SourceFilterRow(sources, selected, onSelect = { viewModel.selectedSource.value = it })
            Spacer(Modifier.height(4.dp))
            if (items.itemCount == 0) {
                EmptyState("Диалогов пока нет", "Диалоги создаются автоматически из входящих сообщений.")
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(count = items.itemCount, key = items.itemKey { it.conversation.id }) { index ->
                        val item = items[index] ?: return@items
                        ConversationRow(item, onClick = { onOpenConversation(item.conversation.id) })
                        HorizontalDivider(Modifier.padding(start = 72.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    }
                }
            }
        }
    }
}

@Composable
private fun ConversationRow(item: ConversationListItem, onClick: () -> Unit) {
    val conversation = item.conversation
    val unread = item.unreadCount > 0
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        InitialsAvatar(conversation.title, size = 48.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (conversation.isGroup) {
                    Icon(
                        Icons.Outlined.Groups,
                        contentDescription = "Групповой чат",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    conversation.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = if (unread) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (conversation.lastMessageAt > 0) {
                    Text(
                        TimeFormat.short(conversation.lastMessageAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    item.sourceName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                if (item.hasActiveReply) {
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        Icons.Outlined.Reply,
                        contentDescription = "Доступен быстрый ответ",
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    conversation.lastMessagePreview,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (unread) {
                    Spacer(Modifier.width(8.dp))
                    CountBadge(item.unreadCount)
                }
            }
        }
    }
}
