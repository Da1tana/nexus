package com.nexus.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
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
import com.nexus.app.notifications.ReprocessRequests
import com.nexus.app.system.InstalledApp
import com.nexus.app.system.InstalledApps
import com.nexus.app.ui.components.AppIcon
import com.nexus.core.data.db.SourceListItem
import com.nexus.core.data.repository.SourceRepository
import com.nexus.core.engine.source.SourceCatalog
import com.nexus.core.model.IntegrationLevel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Строка списка: приложение может быть уже источником (есть запись) или только установленным. */
data class AppRow(
    val packageName: String,
    val name: String,
    val enabled: Boolean,
    val source: SourceListItem?,
    val installed: Boolean,
)

data class SourcesUiState(
    val loading: Boolean = true,
    val saving: List<AppRow> = emptyList(),
    val discovered: List<AppRow> = emptyList(),
    val other: List<AppRow> = emptyList(),
)

@HiltViewModel
class SourcesViewModel @Inject constructor(
    private val repository: SourceRepository,
    private val installedApps: InstalledApps,
    private val reprocess: ReprocessRequests,
) : ViewModel() {

    val query = MutableStateFlow("")
    private val installed = MutableStateFlow<List<InstalledApp>?>(null)

    init {
        viewModelScope.launch { installed.value = installedApps.load() }
    }

    val state: StateFlow<SourcesUiState> = combine(repository.sources(), installed, query) { sources, apps, q ->
        val byPackage = sources.associateBy { it.source.packageName }
        val installedSet = apps.orEmpty().map { it.packageName }.toSet()
        val rows = sources.map {
            AppRow(it.source.packageName, it.source.displayName, it.source.enabled, it, it.source.packageName in installedSet || apps == null)
        } + apps.orEmpty().filter { it.packageName !in byPackage }.map {
            AppRow(it.packageName, SourceCatalog.find(it.packageName)?.displayName ?: it.label, false, null, true)
        }
        val needle = q.trim().lowercase()
        val filtered = if (needle.isEmpty()) rows else rows.filter {
            it.name.lowercase().contains(needle) || it.packageName.contains(needle)
        }
        SourcesUiState(
            loading = apps == null,
            saving = filtered.filter { it.enabled },
            discovered = filtered.filter { !it.enabled && it.source != null },
            // Сначала известные мессенджеры из каталога, затем остальные по алфавиту.
            other = filtered.filter { it.source == null }
                .sortedBy { if (SourceCatalog.isKnownMessenger(it.packageName)) 0 else 1 },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SourcesUiState())

    fun setEnabled(row: AppRow, enabled: Boolean) {
        viewModelScope.launch {
            repository.setEnabled(row.packageName, row.name, enabled)
            if (enabled) reprocess.request()
        }
    }

    fun enableAllDiscovered() {
        viewModelScope.launch {
            repository.enableAll()
            reprocess.request()
        }
    }

    fun clearHistory(packageName: String) {
        viewModelScope.launch { repository.clearHistory(packageName) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourcesScreen(onBack: () -> Unit, viewModel: SourcesViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    var details by remember { mutableStateOf<AppRow?>(null) }
    var confirmClear by remember { mutableStateOf<AppRow?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Приложения") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад") }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { viewModel.query.value = it },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                    placeholder = { Text("Найти приложение") },
                    singleLine = true,
                )
                Text(
                    "Мессенджеры и приложения, которые присылают сообщения, включаются автоматически. " +
                        "Любое другое приложение можно включить вручную — тогда его уведомления тоже попадут в NEXUS.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
            }

            section("Сохраняются", state.saving, onOpen = { details = it }, onToggle = viewModel::setEnabled)
            if (state.discovered.isNotEmpty()) {
                item {
                    Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Присылали уведомления, не сохраняются",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = viewModel::enableAllDiscovered) { Text("Включить все") }
                    }
                }
                rows(state.discovered, onOpen = { details = it }, onToggle = viewModel::setEnabled)
            }
            section("Другие установленные", state.other, onOpen = { details = it }, onToggle = viewModel::setEnabled)
        }
    }

    details?.let { row ->
        val capabilities = SourceCatalog.capabilitiesFor(row.packageName)
        AlertDialog(
            onDismissRequest = { details = null },
            icon = { AppIcon(row.packageName, size = 40.dp) },
            title = { Text(row.name) },
            text = {
                Column {
                    Text(
                        "Уровень: " + when (capabilities.level) {
                            IntegrationLevel.NOTIFICATIONS -> "чтение уведомлений"
                            IntegrationLevel.NOTIFICATION_REPLY -> "уведомления и быстрый ответ"
                            IntegrationLevel.FULL_API -> "официальный API"
                        },
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(capabilities.note, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Сообщения, пришедшие до включения в NEXUS, недоступны.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    row.source?.let {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "Получено уведомлений: ${it.notificationsSeen}\nСохранено сообщений: ${it.messageCount} в ${it.conversationCount} диалог(ах)",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Text(
                        row.packageName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            },
            confirmButton = { TextButton(onClick = { details = null }) { Text("Закрыть") } },
            dismissButton = {
                if ((row.source?.messageCount ?: 0) > 0) {
                    TextButton(onClick = { confirmClear = row; details = null }) {
                        Text("Очистить историю", color = MaterialTheme.colorScheme.error)
                    }
                }
            },
        )
    }

    confirmClear?.let { row ->
        AlertDialog(
            onDismissRequest = { confirmClear = null },
            title = { Text("Очистить историю ${row.name}?") },
            text = { Text("Будут удалены все сообщения и диалоги ${row.name} в NEXUS. В самом приложении ничего не изменится.") },
            confirmButton = {
                TextButton(onClick = { viewModel.clearHistory(row.packageName); confirmClear = null }) {
                    Text("Очистить", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmClear = null }) { Text("Отмена") } },
        )
    }
}

private fun LazyListScope.section(
    title: String,
    rows: List<AppRow>,
    onOpen: (AppRow) -> Unit,
    onToggle: (AppRow, Boolean) -> Unit,
) {
    if (rows.isEmpty()) return
    item(key = "h:$title") {
        Text(
            "$title · ${rows.size}",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 4.dp),
        )
    }
    rows(rows, onOpen, onToggle)
}

private fun LazyListScope.rows(rows: List<AppRow>, onOpen: (AppRow) -> Unit, onToggle: (AppRow, Boolean) -> Unit) {
    items(rows, key = { it.packageName }) { row ->
        ListItem(
            modifier = Modifier.clickable { onOpen(row) },
            leadingContent = { AppIcon(row.packageName, size = 40.dp) },
            headlineContent = { Text(row.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            supportingContent = {
                val source = row.source
                val text = when {
                    !row.installed -> "Не установлено"
                    source == null -> if (SourceCatalog.isKnownMessenger(row.packageName)) "Мессенджер · уведомлений ещё не было" else null
                    row.enabled -> "Сообщений: ${source.messageCount}"
                    else -> "Пропущено уведомлений: ${source.notificationsSeen}"
                }
                if (text != null) Text(text)
            },
            trailingContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.width(8.dp))
                    Switch(checked = row.enabled, onCheckedChange = { onToggle(row, it) })
                }
            },
        )
    }
}
