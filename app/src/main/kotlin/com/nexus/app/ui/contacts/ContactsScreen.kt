package com.nexus.app.ui.contacts

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.MergeType
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.nexus.app.ui.components.EmptyState
import com.nexus.app.ui.components.InitialsAvatar
import com.nexus.app.ui.components.TimeFormat
import com.nexus.core.data.db.ContactAccountItem
import com.nexus.core.data.db.ContactListItem
import com.nexus.core.data.repository.ContactRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ContactsViewModel @Inject constructor(
    private val repository: ContactRepository,
) : ViewModel() {

    val query = MutableStateFlow("")
    val selection = MutableStateFlow<Set<Long>>(emptySet())
    val openedContactId = MutableStateFlow<Long?>(null)

    val contacts: StateFlow<List<ContactListItem>> = query
        .flatMapLatest { repository.contacts(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val openedAccounts: StateFlow<List<ContactAccountItem>> = openedContactId
        .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else repository.accounts(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun toggleSelection(id: Long) {
        selection.value = selection.value.let { if (id in it) it - id else it + id }
    }

    fun clearSelection() {
        selection.value = emptySet()
    }

    /** Объединяет выбранные контакты в целевой (его имя сохраняется). */
    fun merge(targetId: Long) {
        val others = selection.value - targetId
        viewModelScope.launch {
            repository.merge(targetId, others)
            selection.value = emptySet()
        }
    }

    fun rename(id: Long, name: String) {
        viewModelScope.launch { repository.rename(id, name) }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ContactsScreen(viewModel: ContactsViewModel = hiltViewModel()) {
    val query by viewModel.query.collectAsStateWithLifecycle()
    val contacts by viewModel.contacts.collectAsStateWithLifecycle()
    val selection by viewModel.selection.collectAsStateWithLifecycle()
    val openedId by viewModel.openedContactId.collectAsStateWithLifecycle()
    val accounts by viewModel.openedAccounts.collectAsStateWithLifecycle()
    var showMergeDialog by remember { mutableStateOf(false) }
    val selecting = selection.isNotEmpty()

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text(if (selecting) "Выбрано: ${selection.size}" else "Контакты") },
                navigationIcon = {
                    if (selecting) {
                        IconButton(onClick = viewModel::clearSelection) {
                            Icon(Icons.Outlined.Close, contentDescription = "Отменить выбор")
                        }
                    }
                },
                actions = {
                    if (selection.size >= 2) {
                        TextButton(onClick = { showMergeDialog = true }) {
                            Icon(Icons.Outlined.MergeType, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text("Объединить")
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = query,
                onValueChange = { viewModel.query.value = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                placeholder = { Text("Поиск контакта") },
                singleLine = true,
            )
            if (!selecting && contacts.size >= 2) {
                Text(
                    "Контакты не объединяются автоматически. Долгое нажатие — выбрать для объединения.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            if (contacts.isEmpty()) {
                EmptyState(
                    if (query.isBlank()) "Контактов пока нет" else "Ничего не найдено",
                    "Контакты появляются автоматически из отправителей сообщений.",
                )
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(contacts, key = { it.contact.id }) { item ->
                        val id = item.contact.id
                        ListItem(
                            modifier = Modifier.combinedClickable(
                                onClick = {
                                    if (selecting) {
                                        viewModel.toggleSelection(id)
                                    } else {
                                        viewModel.openedContactId.value = id
                                    }
                                },
                                onLongClick = { viewModel.toggleSelection(id) },
                            ),
                            leadingContent = { InitialsAvatar(item.contact.displayName) },
                            headlineContent = {
                                Text(item.contact.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            },
                            supportingContent = {
                                val accountsLabel = if (item.accountCount == 1) "1 аккаунт" else "Аккаунтов: ${item.accountCount}"
                                Text(listOfNotNull(item.sourceNames, accountsLabel).joinToString(" · "))
                            },
                            trailingContent = {
                                if (selecting) {
                                    Checkbox(checked = id in selection, onCheckedChange = { viewModel.toggleSelection(id) })
                                } else {
                                    item.lastMessageAt?.let {
                                        Text(TimeFormat.short(it), style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            },
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    }
                }
            }
        }
    }

    if (showMergeDialog) {
        val selected = contacts.filter { it.contact.id in selection }
        AlertDialog(
            onDismissRequest = { showMergeDialog = false },
            title = { Text("Объединить контакты") },
            text = {
                Column {
                    Text("Выберите имя, которое останется. Аккаунты всех выбранных контактов будут связаны с ним.")
                    Spacer(Modifier.height(8.dp))
                    selected.forEach { item ->
                        TextButton(onClick = {
                            viewModel.merge(item.contact.id)
                            showMergeDialog = false
                        }) { Text(item.contact.displayName) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showMergeDialog = false }) { Text("Отмена") } },
        )
    }

    val opened = contacts.firstOrNull { it.contact.id == openedId }
    if (opened != null) {
        ContactDetailsDialog(
            item = opened,
            accounts = accounts,
            onRename = { viewModel.rename(opened.contact.id, it) },
            onDismiss = { viewModel.openedContactId.value = null },
        )
    }
}

@Composable
private fun ContactDetailsDialog(
    item: ContactListItem,
    accounts: List<ContactAccountItem>,
    onRename: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember(item.contact.id) { mutableStateOf(item.contact.displayName) }
    LaunchedEffect(item.contact.displayName) { name = item.contact.displayName }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(item.contact.displayName) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Имя контакта") },
                    singleLine = true,
                )
                Spacer(Modifier.height(12.dp))
                Text("Связанные аккаунты", style = MaterialTheme.typography.titleSmall)
                accounts.forEach { account ->
                    Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(account.account.displayName, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                account.sourceName,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Text(
                            "сообщений: ${account.messageCount}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (name.isNotBlank() && name != item.contact.displayName) onRename(name)
                    onDismiss()
                },
            ) { Text("Готово") }
        },
    )
}
