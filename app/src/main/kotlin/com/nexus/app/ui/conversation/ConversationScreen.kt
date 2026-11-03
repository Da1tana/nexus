package com.nexus.app.ui.conversation

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.width
import androidx.compose.material3.FilledTonalButton
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import com.nexus.app.ui.components.ConversationAvatar
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import com.nexus.app.notifications.OriginalAppLauncher
import com.nexus.app.ui.components.TimeFormat
import com.nexus.core.data.db.ConversationListItem
import com.nexus.core.data.db.MessageEntity
import com.nexus.core.data.repository.ConversationRepository
import com.nexus.core.data.repository.MessageRepository
import com.nexus.core.data.repository.SendOutcome
import com.nexus.core.model.MessageDirection
import com.nexus.core.model.MessageStatus
import com.nexus.core.model.ReplyMethod
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ComposerState(
    val text: String = "",
    val sending: Boolean = false,
    val methods: List<ReplyMethod> = emptyList(),
) {
    val canReplyHere: Boolean get() = methods.any { it != ReplyMethod.OPEN_ORIGINAL_APP }
}

@HiltViewModel
class ConversationViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    conversations: ConversationRepository,
    private val messages: MessageRepository,
    private val launcher: OriginalAppLauncher,
) : ViewModel() {

    val conversationId: Long = checkNotNull(savedStateHandle.get<Long>(ARG_CONVERSATION_ID))

    val conversation: StateFlow<ConversationListItem?> = conversations.observe(conversationId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val messageFeed: Flow<PagingData<MessageEntity>> = messages.conversationMessages(conversationId)
        .cachedIn(viewModelScope)

    private val composerState = MutableStateFlow(ComposerState())
    val composer: StateFlow<ComposerState> = composerState.asStateFlow()

    private val eventFlow = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val events: SharedFlow<String> = eventFlow.asSharedFlow()

    init {
        viewModelScope.launch {
            // Беседа обновляется при каждом новом сообщении: отмечаем прочитанным и пересчитываем способы ответа.
            conversation.collect { item ->
                if (item != null) {
                    if (item.unreadCount > 0) messages.markConversationRead(conversationId)
                    refreshMethods()
                }
            }
        }
    }

    fun onTextChange(value: String) {
        composerState.value = composerState.value.copy(text = value)
    }

    fun refreshMethods() {
        viewModelScope.launch {
            val methods = messages.availableMethods(conversationId)
            composerState.value = composerState.value.copy(methods = methods)
        }
    }

    fun send() {
        val text = composerState.value.text.trim()
        if (text.isEmpty() || composerState.value.sending) return
        composerState.value = composerState.value.copy(sending = true)
        viewModelScope.launch {
            when (val outcome = messages.send(conversationId, text)) {
                is SendOutcome.NotAvailable -> {
                    composerState.value = composerState.value.copy(sending = false)
                    eventFlow.emit(outcome.reason)
                }
                is SendOutcome.Stored -> {
                    composerState.value = composerState.value.copy(
                        sending = false,
                        text = if (outcome.status == MessageStatus.FAILED) composerState.value.text else "",
                    )
                    if (outcome.status == MessageStatus.FAILED) {
                        eventFlow.emit("Не отправлено: ${outcome.reason ?: "неизвестная ошибка"}")
                    }
                }
            }
            refreshMethods()
        }
    }

    fun openOriginal() {
        val item = conversation.value ?: return
        val result = launcher.open(item.conversation.sourcePackage, item.conversation.externalKey)
        if (result == OriginalAppLauncher.Result.NOT_INSTALLED) {
            eventFlow.tryEmit("Приложение ${item.sourceName} не установлено или недоступно")
        } else if (result == OriginalAppLauncher.Result.OPENED_APP) {
            eventFlow.tryEmit("Открыто приложение: уведомление беседы уже закрыто, найдите чат вручную")
        }
    }

    companion object {
        const val ARG_CONVERSATION_ID = "conversationId"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationScreen(
    onBack: () -> Unit,
    viewModel: ConversationViewModel = hiltViewModel(),
) {
    val conversation by viewModel.conversation.collectAsStateWithLifecycle()
    val composer by viewModel.composer.collectAsStateWithLifecycle()
    val items = viewModel.messageFeed.collectAsLazyPagingItems()
    val snackbar = remember { SnackbarHostState() }
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        viewModel.events.collect { snackbar.showSnackbar(it) }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
                title = {
                    val item = conversation
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (item != null) {
                            ConversationAvatar(
                                item.conversation.title,
                                item.conversation.avatarPath,
                                item.conversation.sourcePackage,
                                size = 40.dp,
                            )
                            Spacer(Modifier.width(12.dp))
                        }
                        Column {
                            Text(
                                item?.conversation?.title ?: "",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                item?.sourceName ?: "",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::openOriginal) {
                        Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = "Открыть в исходном приложении")
                    }
                },
            )
        },
        bottomBar = {
            Composer(
                state = composer,
                sourceName = conversation?.sourceName ?: "приложение",
                onTextChange = viewModel::onTextChange,
                onSend = viewModel::send,
                onOpenOriginal = viewModel::openOriginal,
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            reverseLayout = true,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(count = items.itemCount, key = items.itemKey { it.id }) { index ->
                val message = items[index] ?: return@items
                val older = if (index + 1 < items.itemCount) items.peek(index + 1) else null
                val startsDay = older == null || TimeFormat.day(older.timestamp) != TimeFormat.day(message.timestamp)
                Column {
                    if (startsDay) DayHeader(TimeFormat.header(message.timestamp))
                    MessageBubble(
                        message,
                        showSender = conversation?.conversation?.isGroup == true,
                        onLongPress = {
                            clipboard.setText(AnnotatedString(message.body))
                            scope.launch { snackbar.showSnackbar("Текст скопирован") }
                        },
                    )
                }
            }
            item { Spacer(Modifier.height(8.dp)) }
        }
    }
}

@Composable
private fun DayHeader(text: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Text(
                text,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(message: MessageEntity, showSender: Boolean, onLongPress: () -> Unit) {
    val outgoing = message.direction == MessageDirection.OUTGOING
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        horizontalArrangement = if (outgoing) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            shape = RoundedCornerShape(
                topStart = 18.dp,
                topEnd = 18.dp,
                bottomStart = if (outgoing) 18.dp else 4.dp,
                bottomEnd = if (outgoing) 4.dp else 18.dp,
            ),
            color = if (outgoing) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier
                .widthIn(max = 300.dp)
                .clip(
                    RoundedCornerShape(
                        topStart = 18.dp,
                        topEnd = 18.dp,
                        bottomStart = if (outgoing) 18.dp else 4.dp,
                        bottomEnd = if (outgoing) 4.dp else 18.dp,
                    ),
                )
                .combinedClickable(onClick = {}, onLongClick = onLongPress),
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                if (showSender && !outgoing && message.senderName != null) {
                    Text(
                        message.senderName!!,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Text(
                    if (message.textHidden) "Текст скрыт приложением-источником" else message.body,
                    style = MaterialTheme.typography.bodyLarge,
                    fontStyle = if (message.textHidden) FontStyle.Italic else FontStyle.Normal,
                )
                Spacer(Modifier.height(2.dp))
                val status = if (outgoing) statusLabel(message) else null
                Text(
                    listOfNotNull(TimeFormat.clock(message.timestamp), status).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (message.status == MessageStatus.FAILED) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.align(Alignment.End),
                    textAlign = TextAlign.End,
                )
            }
        }
    }
}

private fun statusLabel(message: MessageEntity): String? = when (message.status) {
    MessageStatus.SEND_PENDING -> "отправляется…"
    MessageStatus.UNKNOWN -> "передано в приложение, без подтверждения"
    MessageStatus.SENT -> "отправлено"
    MessageStatus.FAILED -> "ошибка: ${message.failureReason ?: "не отправлено"}"
    MessageStatus.RECEIVED, MessageStatus.READ_IN_NEXUS -> null
}

@Composable
private fun Composer(
    state: ComposerState,
    sourceName: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onOpenOriginal: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.navigationBarsPadding().imePadding()) {
        if (state.canReplyHere) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = state.text,
                    onValueChange = onTextChange,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Ответ через $sourceName") },
                    maxLines = 5,
                    shape = RoundedCornerShape(24.dp),
                )
                Spacer(Modifier.padding(4.dp))
                FilledIconButton(onClick = onSend, enabled = state.text.isNotBlank() && !state.sending) {
                    if (state.sending) {
                        CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.padding(10.dp))
                    } else {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Отправить")
                    }
                }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Ответ из NEXUS доступен, пока уведомление чата висит в шторке",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                FilledTonalButton(onClick = onOpenOriginal) { Text("Ответить в $sourceName", maxLines = 1) }
            }
        }
    }
}
