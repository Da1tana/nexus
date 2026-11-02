package com.nexus.app.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
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
import com.nexus.app.ui.components.SourceFilterRow
import com.nexus.app.ui.components.TimeFormat
import com.nexus.core.data.db.MessageListItem
import com.nexus.core.data.repository.SearchFilters
import com.nexus.core.data.repository.SearchRepository
import com.nexus.core.data.repository.SourceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

enum class SearchPeriod(val label: String, val days: Int?) {
    ALL("Всё время", null),
    DAY("24 часа", 1),
    WEEK("7 дней", 7),
    MONTH("30 дней", 30),
    YEAR("Год", 365),
}

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val search: SearchRepository,
    sources: SourceRepository,
) : ViewModel() {

    val query = MutableStateFlow("")
    val sender = MutableStateFlow("")
    val source = MutableStateFlow<String?>(null)
    val period = MutableStateFlow(SearchPeriod.ALL)

    val sources = sources.sourcesWithMessages()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    val results: Flow<PagingData<MessageListItem>> =
        combine(query, sender, source, period) { q, s, src, p ->
            SearchFilters(
                query = q,
                sourcePackage = src,
                sender = s,
                fromTime = p.days?.let { System.currentTimeMillis() - it * DAY_MS },
                toTime = null,
            )
        }
            .debounce(300)
            .distinctUntilChanged()
            .flatMapLatest { search.search(it) }
            .cachedIn(viewModelScope)

    private companion object {
        const val DAY_MS = 24L * 60 * 60 * 1000
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    onOpenConversation: (Long) -> Unit,
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val query by viewModel.query.collectAsStateWithLifecycle()
    val sender by viewModel.sender.collectAsStateWithLifecycle()
    val source by viewModel.source.collectAsStateWithLifecycle()
    val period by viewModel.period.collectAsStateWithLifecycle()
    val sources by viewModel.sources.collectAsStateWithLifecycle()
    val results = viewModel.results.collectAsLazyPagingItems()
    val hasCriteria = query.isNotBlank() || sender.isNotBlank()

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { TopAppBar(title = { Text("Поиск") }) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = query,
                onValueChange = { viewModel.query.value = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                placeholder = { Text("Текст сообщения") },
                singleLine = true,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = sender,
                onValueChange = { viewModel.sender.value = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                leadingIcon = { Icon(Icons.Outlined.Person, contentDescription = null) },
                placeholder = { Text("Отправитель или чат") },
                singleLine = true,
            )
            Spacer(Modifier.height(8.dp))
            SourceFilterRow(sources, source, onSelect = { viewModel.source.value = it })
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(SearchPeriod.entries) { option ->
                    FilterChip(
                        selected = period == option,
                        onClick = { viewModel.period.value = option },
                        label = { Text(option.label) },
                    )
                }
            }
            HorizontalDivider(Modifier.padding(top = 8.dp))

            when {
                !hasCriteria -> Hint("Введите слово или начало слова. Поиск работает по-русски и по-английски, без учёта регистра.")
                results.itemCount == 0 -> Hint("Ничего не найдено")
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(count = results.itemCount, key = results.itemKey { it.message.id }) { index ->
                        val item = results[index] ?: return@items
                        SearchResultRow(item, onClick = { onOpenConversation(item.message.conversationId) })
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    }
                }
            }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        modifier = Modifier.padding(24.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun SearchResultRow(item: MessageListItem, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Row {
            Text(
                item.conversationTitle,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Text(TimeFormat.short(item.message.timestamp), style = MaterialTheme.typography.labelSmall)
        }
        Text(
            listOfNotNull(item.sourceName, item.message.senderName.takeIf { item.conversationIsGroup }).joinToString(" · "),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            item.message.body,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
