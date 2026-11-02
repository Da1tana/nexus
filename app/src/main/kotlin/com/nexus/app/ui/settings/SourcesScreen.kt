package com.nexus.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.nexus.app.ui.components.EmptyState
import com.nexus.core.data.db.SourceListItem
import com.nexus.core.data.repository.SourceRepository
import com.nexus.core.engine.source.SourceCatalog
import com.nexus.core.model.IntegrationLevel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SourcesViewModel @Inject constructor(private val repository: SourceRepository) : ViewModel() {
    val sources: StateFlow<List<SourceListItem>> = repository.sources()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setEnabled(packageName: String, enabled: Boolean) {
        viewModelScope.launch { repository.setEnabled(packageName, enabled) }
    }

    fun clearHistory(packageName: String) {
        viewModelScope.launch { repository.clearHistory(packageName) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourcesScreen(onBack: () -> Unit, viewModel: SourcesViewModel = hiltViewModel()) {
    val sources by viewModel.sources.collectAsStateWithLifecycle()
    var clearTarget by remember { mutableStateOf<SourceListItem?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Подключённые приложения") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад") }
                },
            )
        },
    ) { padding ->
        if (sources.isEmpty()) {
            EmptyState(
                "Источников пока нет",
                "Приложение появится здесь после первого уведомления. Известные мессенджеры включаются автоматически, остальные — вручную.",
                Modifier.padding(padding),
            )
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                item {
                    Text(
                        "Приложения добавляются после первого уведомления. Выключенный источник не сохраняется, но его история остаётся до очистки.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
                items(sources, key = { it.source.packageName }) { item ->
                    SourceCard(
                        item = item,
                        onToggle = { viewModel.setEnabled(item.source.packageName, it) },
                        onClear = { clearTarget = item },
                    )
                }
            }
        }
    }

    clearTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { clearTarget = null },
            title = { Text("Очистить историю ${target.source.displayName}?") },
            text = { Text("Будут удалены ${target.messageCount} сообщ. и ${target.conversationCount} диалог(ов) этого источника в NEXUS.") },
            confirmButton = {
                TextButton(onClick = { viewModel.clearHistory(target.source.packageName); clearTarget = null }) {
                    Text("Очистить", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { clearTarget = null }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun SourceCard(item: SourceListItem, onToggle: (Boolean) -> Unit, onClear: () -> Unit) {
    val capabilities = SourceCatalog.capabilitiesFor(item.source.packageName)
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(item.source.displayName, style = MaterialTheme.typography.titleMedium)
                    Text(
                        item.source.packageName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = item.source.enabled, onCheckedChange = onToggle)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Уровень: " + when (capabilities.level) {
                    IntegrationLevel.NOTIFICATIONS -> "A — чтение уведомлений"
                    IntegrationLevel.NOTIFICATION_REPLY -> "B — уведомления и быстрый ответ"
                    IntegrationLevel.FULL_API -> "C — официальный API"
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(capabilities.note, style = MaterialTheme.typography.bodySmall)
            Text(
                "История до установки NEXUS: " + if (capabilities.canReadHistory) "доступна" else "недоступна",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Сообщений: ${item.messageCount} · диалогов: ${item.conversationCount}",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onClear, enabled = item.messageCount > 0) { Text("Очистить историю") }
            }
        }
    }
}
