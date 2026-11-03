package com.nexus.app.ui.messages

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Reply
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import com.nexus.app.notifications.AvatarStore
import com.nexus.app.notifications.OriginalAppLauncher
import com.nexus.app.settings.AppSettings
import com.nexus.app.settings.SettingsStore
import com.nexus.app.ui.components.ConversationAvatar
import com.nexus.app.ui.components.CountBadge
import com.nexus.app.ui.components.EmptyState
import com.nexus.app.ui.components.SourceFilterRow
import com.nexus.app.ui.components.TimeFormat
import com.nexus.app.ui.components.UnreadDot
import com.nexus.app.ui.setup.SetupGuide
import com.nexus.app.ui.setup.rememberSetupState
import com.nexus.core.data.db.ConversationListItem
import com.nexus.core.data.db.MessageListItem
import com.nexus.core.data.repository.ConversationRepository
import com.nexus.core.data.repository.MessageRepository
import com.nexus.core.data.repository.SourceRepository
import com.nexus.core.model.MessageDirection
import com.nexus.core.model.MessageStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MessagesViewModel @Inject constructor(
    private val messages: MessageRepository,
    private val conversations: ConversationRepository,
    sources: SourceRepository,
    private val settingsStore: SettingsStore,
    private val launcher: OriginalAppLauncher,
    private val avatars: AvatarStore,
) : ViewModel() {

    val selectedSource = MutableStateFlow<String?>(null)
    val unreadOnly = MutableStateFlow(false)
    val events = MutableSharedFlow<String>(extraBufferCapacity = 2)

    val settings: StateFlow<AppSettings> = settingsStore.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    val sources = sources.sourcesWithMessages()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val unreadCount: StateFlow<Int> = messages.unreadCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    private val filters = combine(selectedSource, unreadOnly) { source, unread -> source to unread }

    @OptIn(ExperimentalCoroutinesApi::class)
    val conversationList: Flow<PagingData<ConversationListItem>> = filters
        .flatMapLatest { (source, unread) -> conversations.conversations(source, unread) }
        .cachedIn(viewModelScope)

    @OptIn(ExperimentalCoroutinesApi::class)
    val feed: Flow<PagingData<MessageListItem>> = filters
        .flatMapLatest { (source, unread) -> messages.inbox(source, unread) }
        .cachedIn(viewModelScope)

    fun setFeedMode(feed: Boolean) {
        viewModelScope.launch { settingsStore.setFeedMode(feed) }
    }

    fun dismissSetupHint() {
        viewModelScope.launch { settingsStore.setSetupHintDismissed(true) }
    }

    fun markAllRead() {
        viewModelScope.launch { messages.markAllRead() }
    }

    fun markRead(conversationId: Long) {
        viewModelScope.launch { messages.markConversationRead(conversationId) }
    }

    fun delete(conversationId: Long) {
        viewModelScope.launch { avatars.delete(conversations.delete(conversationId)) }
    }

    fun openOriginal(item: ConversationListItem) {
        val result = launcher.open(item.conversation.sourcePackage, item.conversation.externalKey)
        if (result == OriginalAppLauncher.Result.NOT_INSTALLED) {
            events.tryEmit("${item.sourceName} не установлено")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessagesScreen(
    onOpenConversation: (Long) -> Unit,
    viewModel: MessagesViewModel = hiltViewModel(),
) {
    val setup = rememberSetupState()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val sources by viewModel.sources.collectAsStateWithLifecycle()
    val selected by viewModel.selectedSource.collectAsStateWithLifecycle()
    val unreadOnly by viewModel.unreadOnly.collectAsStateWithLifecycle()
    val unread by viewModel.unreadCount.collectAsStateWithLifecycle()
    val conversations = viewModel.conversationList.collectAsLazyPagingItems()
    val feed = viewModel.feed.collectAsLazyPagingItems()
    val snackbar = remember { SnackbarHostState() }
    var pendingDelete by remember { mutableStateOf<ConversationListItem?>(null) }

    LaunchedEffect(Unit) { viewModel.events.collect { snackbar.showSnackbar(it) } }

    val showSetup = !setup.notificationAccess || (!setup.complete && !settings.setupHintDismissed)
    val isEmpty = if (settings.feedMode) feed.itemCount == 0 else conversations.itemCount == 0

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Сообщения")
                        if (unread > 0) {
                            Text(
                                "Непрочитанных: $unread",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
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
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                SegmentedButton(
                    selected = !settings.feedMode,
                    onClick = { viewModel.setFeedMode(false) },
                    shape = SegmentedButtonDefaults.itemShape(0, 2),
                ) { Text("Диалоги") }
                SegmentedButton(
                    selected = settings.feedMode,
                    onClick = { viewModel.setFeedMode(true) },
                    shape = SegmentedButtonDefaults.itemShape(1, 2),
                ) { Text("Лента") }
            }
            Spacer(Modifier.height(8.dp))
            SourceFilterRow(
                sources = sources,
                selected = selected,
                onSelect = { viewModel.selectedSource.value = it },
                unreadOnly = unreadOnly,
                onUnreadOnlyChange = { viewModel.unreadOnly.value = it },
            )
            Spacer(Modifier.height(4.dp))

            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
                if (showSetup) {
                    item(key = "setup") {
                        SetupGuide(
                            state = setup,
                            onDismiss = if (setup.notificationAccess) viewModel::dismissSetupHint else null,
                        )
                    }
                }
                if (isEmpty) {
                    item(key = "empty") {
                        EmptyState(
                            title = if (unreadOnly) "Нет непрочитанных" else "Сообщений пока нет",
                            message = if (setup.notificationAccess) {
                                "Новые сообщения появятся здесь, как только придут уведомления. " +
                                    "Какие приложения сохраняются — в Настройки → Приложения."
                            } else {
                                "Разрешите доступ к уведомлениям, чтобы NEXUS начал собирать сообщения."
                            },
                            modifier = Modifier.height(260.dp),
                        )
                    }
                } else if (settings.feedMode) {
                    feedItems(feed, onOpenConversation)
                } else {
                    conversationItems(
                        items = conversations,
                        onOpen = onOpenConversation,
                        onMarkRead = viewModel::markRead,
                        onOpenOriginal = viewModel::openOriginal,
                        onDelete = { pendingDelete = it },
                    )
                }
            }
        }
    }

    pendingDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Удалить диалог?") },
            text = { Text("Диалог «${item.conversation.title}» и его сообщения будут удалены из NEXUS. В ${item.sourceName} ничего не изменится.") },
            confirmButton = {
                TextButton(onClick = { viewModel.delete(item.conversation.id); pendingDelete = null }) {
                    Text("Удалить", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Отмена") } },
        )
    }
}

private fun LazyListScope.conversationItems(
    items: LazyPagingItems<ConversationListItem>,
    onOpen: (Long) -> Unit,
    onMarkRead: (Long) -> Unit,
    onOpenOriginal: (ConversationListItem) -> Unit,
    onDelete: (ConversationListItem) -> Unit,
) {
    items(count = items.itemCount, key = items.itemKey { "c" + it.conversation.id }) { index ->
        val item = items[index] ?: return@items
        ConversationRow(
            item = item,
            onClick = { onOpen(item.conversation.id) },
            onMarkRead = { onMarkRead(item.conversation.id) },
            onOpenOriginal = { onOpenOriginal(item) },
            onDelete = { onDelete(item) },
        )
    }
}

private fun LazyListScope.feedItems(items: LazyPagingItems<MessageListItem>, onOpen: (Long) -> Unit) {
    items(count = items.itemCount, key = items.itemKey { "m" + it.message.id }) { index ->
        val item = items[index] ?: return@items
        FeedRow(item, onClick = { onOpen(item.message.conversationId) })
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationRow(
    item: ConversationListItem,
    onClick: () -> Unit,
    onMarkRead: () -> Unit,
    onOpenOriginal: () -> Unit,
    onDelete: () -> Unit,
) {
    val conversation = item.conversation
    val unread = item.unreadCount > 0
    var menu by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onClick, onLongClick = { menu = true })
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ConversationAvatar(conversation.title, conversation.avatarPath, conversation.sourcePackage)
            Spacer(Modifier.width(14.dp))
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
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = if (unread) FontWeight.Bold else FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (conversation.lastMessageAt > 0) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            TimeFormat.short(conversation.lastMessageAt),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (unread) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        conversation.lastMessagePreview,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (unread) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (item.hasActiveReply) {
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            Icons.AutoMirrored.Outlined.Reply,
                            contentDescription = "Можно ответить из NEXUS",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                    if (unread) {
                        Spacer(Modifier.width(8.dp))
                        CountBadge(item.unreadCount)
                    }
                }
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            if (unread) {
                DropdownMenuItem(text = { Text("Отметить прочитанным") }, onClick = { menu = false; onMarkRead() })
            }
            DropdownMenuItem(text = { Text("Открыть в ${item.sourceName}") }, onClick = { menu = false; onOpenOriginal() })
            DropdownMenuItem(
                text = { Text("Удалить из NEXUS", color = MaterialTheme.colorScheme.error) },
                onClick = { menu = false; onDelete() },
            )
        }
    }
}

@Composable
private fun FeedRow(item: MessageListItem, onClick: () -> Unit) {
    val message = item.message
    val unread = message.status == MessageStatus.RECEIVED
    val sender = if (message.direction == MessageDirection.OUTGOING) "Вы" else message.senderName
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.Start,
    ) {
        ConversationAvatar(item.conversationTitle, null, message.sourcePackage, size = 44.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    item.conversationTitle,
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
            Text(
                listOfNotNull(item.sourceName, sender.takeIf { item.conversationIsGroup || message.direction == MessageDirection.OUTGOING })
                    .joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (message.textHidden) "Текст скрыт приложением" else message.body,
                    style = MaterialTheme.typography.bodyMedium,
                    fontStyle = if (message.textHidden) FontStyle.Italic else FontStyle.Normal,
                    maxLines = 3,
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
