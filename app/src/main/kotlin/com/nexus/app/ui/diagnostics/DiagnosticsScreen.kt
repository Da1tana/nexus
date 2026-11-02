package com.nexus.app.ui.diagnostics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.nexus.app.notifications.ActiveNotificationRegistry
import com.nexus.app.notifications.ListenerState
import com.nexus.app.notifications.ListenerStatus
import com.nexus.app.system.SystemStatus
import com.nexus.app.ui.components.TimeFormat
import com.nexus.app.ui.settings.SectionTitle
import com.nexus.core.data.db.SyncStateEntity
import com.nexus.core.data.repository.DatabaseStats
import com.nexus.core.data.repository.DiagnosticsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DiagnosticsViewModel @Inject constructor(
    private val repository: DiagnosticsRepository,
    listenerStatus: ListenerStatus,
    private val registry: ActiveNotificationRegistry,
) : ViewModel() {

    val listener: StateFlow<ListenerState> = listenerStatus.flow

    val stats: StateFlow<DatabaseStats?> = repository.stats()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val syncStates: StateFlow<List<SyncStateEntity>> = repository.syncStates()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val integrityState = MutableStateFlow<String?>(null)
    val integrity: StateFlow<String?> = integrityState.asStateFlow()

    fun replyableNow(): Int = registry.replyableCount()

    fun checkIntegrity() {
        viewModelScope.launch {
            integrityState.value = "проверка…"
            integrityState.value = repository.checkIntegrity()
        }
    }
}

private enum class Health { OK, PROBLEM, UNKNOWN }

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DiagnosticsScreen(onBack: () -> Unit, viewModel: DiagnosticsViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val listener by viewModel.listener.collectAsStateWithLifecycle()
    val stats by viewModel.stats.collectAsStateWithLifecycle()
    val syncStates by viewModel.syncStates.collectAsStateWithLifecycle()
    val integrity by viewModel.integrity.collectAsStateWithLifecycle()

    var accessGranted by remember { mutableStateOf(SystemStatus.isNotificationAccessGranted(context)) }
    var batteryUnrestricted by remember { mutableStateOf(SystemStatus.isIgnoringBatteryOptimizations(context)) }
    var backgroundRestricted by remember { mutableStateOf(SystemStatus.isBackgroundRestricted(context)) }
    var replyableNow by remember { mutableIntStateOf(viewModel.replyableNow()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        accessGranted = SystemStatus.isNotificationAccessGranted(context)
        batteryUnrestricted = SystemStatus.isIgnoringBatteryOptimizations(context)
        backgroundRestricted = SystemStatus.isBackgroundRestricted(context)
        replyableNow = viewModel.replyableNow()
    }
    LaunchedEffect(Unit) { viewModel.checkIntegrity() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Диагностика") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
        ) {
            SectionTitle("Сбор сообщений")
            StatusRow(
                "Доступ к уведомлениям",
                if (accessGranted) "Разрешён" else "Не разрешён",
                if (accessGranted) Health.OK else Health.PROBLEM,
            )
            StatusRow(
                "Сервис уведомлений",
                when {
                    listener.connected -> "Подключён с ${TimeFormat.full(listener.connectedAt)}"
                    listener.disconnectedAt != null -> "Отключён системой в ${TimeFormat.full(listener.disconnectedAt)}"
                    else -> "Не подключался в текущем запуске"
                },
                when {
                    listener.connected -> Health.OK
                    accessGranted -> Health.UNKNOWN
                    else -> Health.PROBLEM
                },
            )
            StatusRow(
                "Последнее уведомление",
                listener.lastEventAt?.let {
                    "${TimeFormat.full(it)} · ${listener.lastEventPackage} · ${outcomeLabel(listener.lastOutcome)}"
                } ?: "В текущем запуске не было",
                if (listener.lastEventAt != null) Health.OK else Health.UNKNOWN,
            )
            StatusRow(
                "Быстрые ответы",
                "Доступно сейчас в диалогах: $replyableNow (по активным уведомлениям)",
                if (replyableNow > 0) Health.OK else Health.UNKNOWN,
            )
            listener.lastError?.let { StatusRow("Последняя ошибка", it, Health.PROBLEM) }

            SectionTitle("Фоновая работа")
            StatusRow(
                "Оптимизация батареи",
                if (batteryUnrestricted) "NEXUS исключён из оптимизации" else "Включена — EMUI может останавливать NEXUS",
                if (batteryUnrestricted) Health.OK else Health.PROBLEM,
            )
            StatusRow(
                "Ограничение фоновой работы",
                when (backgroundRestricted) {
                    null -> "Не поддерживается этой версией Android"
                    true -> "Включено системой или пользователем"
                    false -> "Не ограничено"
                },
                when (backgroundRestricted) {
                    null -> Health.UNKNOWN
                    true -> Health.PROBLEM
                    false -> Health.OK
                },
            )
            StatusRow(
                "Автозапуск (EMUI)",
                "Android не сообщает это состояние приложениям — проверьте вручную по инструкции ниже",
                Health.UNKNOWN,
            )

            FlowRow(
                Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(onClick = { SystemStatus.openNotificationAccessSettings(context) }) { Text("Доступ к уведомлениям") }
                OutlinedButton(onClick = { SystemStatus.openBatteryOptimizationSettings(context) }) { Text("Батарея") }
                OutlinedButton(onClick = { SystemStatus.openHuaweiLaunchManager(context) }) { Text("Запуск приложений") }
                OutlinedButton(onClick = { SystemStatus.requestListenerRebind(context) }, enabled = accessGranted) {
                    Text("Переподключить сервис")
                }
            }

            SectionTitle("База данных")
            val s = stats
            StatusRow(
                "Состояние",
                "Проверка целостности: ${integrity ?: "—"}",
                when (integrity) {
                    "ok" -> Health.OK
                    null, "проверка…" -> Health.UNKNOWN
                    else -> Health.PROBLEM
                },
            )
            if (s != null) {
                StatusRow(
                    "Содержимое",
                    "Сообщений: ${s.messages} · диалогов: ${s.conversations} · контактов: ${s.contacts}",
                    Health.OK,
                )
                StatusRow(
                    "Источники",
                    "Включено: ${s.enabledSources} · официальных подключений (API): ${s.connectorAccounts}",
                    if (s.enabledSources > 0) Health.OK else Health.UNKNOWN,
                )
            }

            if (syncStates.isNotEmpty()) {
                SectionTitle("Статистика по источникам")
                syncStates.forEach { state ->
                    StatusRow(
                        state.sourcePackage,
                        "Уведомлений: ${state.notificationsSeen} · сохранено: ${state.messagesStored} · " +
                            "дубликатов отброшено: ${state.duplicatesSkipped} · последнее: ${TimeFormat.full(state.lastEventAt)}" +
                            (state.lastIgnoreReason?.let { " · пропущено: ${outcomeLabel(it)}" } ?: ""),
                        Health.OK,
                    )
                }
            }

            SectionTitle("Устройство")
            Text(
                SystemStatus.deviceDescription,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp),
            )

            SectionTitle("Настройка EMUI для надёжной работы")
            EmuiGuide(isHuawei = SystemStatus.isHuaweiDevice)
        }
    }
}

@Composable
private fun StatusRow(title: String, value: String, health: Health) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        val (icon, tint) = when (health) {
            Health.OK -> Icons.Outlined.CheckCircle to Color(0xFF2E7D32)
            Health.PROBLEM -> Icons.Outlined.ErrorOutline to MaterialTheme.colorScheme.error
            Health.UNKNOWN -> Icons.Outlined.HelpOutline to MaterialTheme.colorScheme.onSurfaceVariant
        }
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun EmuiGuide(isHuawei: Boolean) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Column(Modifier.padding(16.dp)) {
            if (!isHuawei) {
                Text(
                    "Это устройство не Huawei — пункты ниже могут называться иначе.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
            }
            val steps = listOf(
                "Настройки → Уведомления → Доступ к уведомлениям (или «Дополнительные настройки») → включите NEXUS.",
                "Настройки → Батарея → Запуск приложений → NEXUS → отключите «Управлять автоматически» и включите «Автозапуск», «Косвенный запуск» и «Работа в фоновом режиме».",
                "Настройки → Приложения → NEXUS → Батарея → разрешите работу в фоне / отключите оптимизацию.",
                "В списке недавних приложений потяните карточку NEXUS вниз и закрепите её (значок замка), чтобы очистка памяти не завершала процесс.",
                "После перезагрузки телефона откройте этот экран и убедитесь, что «Сервис уведомлений» подключён.",
                "Если сообщения перестали приходить: «Переподключить сервис» выше, либо выключите и снова включите доступ к уведомлениям.",
            )
            steps.forEachIndexed { index, step ->
                Row(Modifier.padding(vertical = 4.dp)) {
                    Text("${index + 1}.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(24.dp))
                    Text(step, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

private fun outcomeLabel(outcome: String?): String = when (outcome) {
    "PROCESSED" -> "обработано"
    "IGNORED" -> "не сообщение"
    "SOURCE_DISABLED", "SOURCE_DISABLED_REASON" -> "источник выключен"
    "GROUP_SUMMARY" -> "сводка группы"
    "ONGOING" -> "постоянное уведомление"
    "EMPTY" -> "пустое"
    "NOT_A_MESSAGE" -> "системное"
    "OWN_PACKAGE" -> "собственное"
    null -> "—"
    else -> outcome
}
