package com.nexus.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.nexus.app.BuildConfig
import com.nexus.app.notifications.ActiveNotificationRegistry
import com.nexus.app.security.TokenVault
import com.nexus.app.settings.AppSettings
import com.nexus.app.settings.SettingsStore
import com.nexus.app.settings.ThemeMode
import com.nexus.app.system.SystemStatus
import com.nexus.core.data.repository.MaintenanceRepository
import com.nexus.core.data.repository.SourceRepository
import com.nexus.core.engine.RetentionPolicy
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val store: SettingsStore,
    private val sources: SourceRepository,
    private val maintenance: MaintenanceRepository,
    private val vault: TokenVault,
    private val registry: ActiveNotificationRegistry,
) : ViewModel() {

    val settings: StateFlow<AppSettings> = store.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    fun setTheme(mode: ThemeMode) {
        viewModelScope.launch { store.setThemeMode(mode) }
    }

    fun setRetention(days: Int) {
        viewModelScope.launch {
            store.setRetentionDays(days)
            maintenance.applyRetention(days)
        }
    }

    fun clearAll() {
        viewModelScope.launch {
            sources.clearAll()
            vault.clear()
            registry.clear()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onOpenSources: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    var accessGranted by remember { mutableStateOf(SystemStatus.isNotificationAccessGranted(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        accessGranted = SystemStatus.isNotificationAccessGranted(context)
    }
    var dialog by remember { mutableStateOf<SettingsDialog?>(null) }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { TopAppBar(title = { Text("Настройки") }) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            SectionTitle("Источники")
            SettingRow(Icons.Outlined.Apps, "Подключённые приложения", "Какие приложения сохранять и что они умеют", onOpenSources)
            SettingRow(
                Icons.Outlined.Notifications,
                "Доступ к уведомлениям",
                if (accessGranted) "Разрешён" else "Не разрешён — сообщения не собираются",
            ) { SystemStatus.openNotificationAccessSettings(context) }

            SectionTitle("Данные")
            SettingRow(Icons.Outlined.History, "Хранение истории", RetentionPolicy.label(settings.retentionDays)) {
                dialog = SettingsDialog.RETENTION
            }
            SettingRow(Icons.Outlined.DeleteForever, "Удалить все данные", "Все сообщения, диалоги и контакты NEXUS") {
                dialog = SettingsDialog.CLEAR_ALL
            }

            SectionTitle("Безопасность")
            SettingRow(Icons.Outlined.Security, "Как защищены данные", "Локальное хранение, без сети и резервных копий") {
                dialog = SettingsDialog.SECURITY
            }

            SectionTitle("Оформление")
            SettingRow(Icons.Outlined.DarkMode, "Тема", settings.themeMode.label) { dialog = SettingsDialog.THEME }

            SectionTitle("Система")
            SettingRow(Icons.Outlined.MonitorHeart, "Диагностика", "Состояние сервиса, базы и ограничения EMUI", onOpenDiagnostics)
            SettingRow(Icons.Outlined.Info, "О приложении", "NEXUS ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})") {
                dialog = SettingsDialog.ABOUT
            }
        }
    }

    when (dialog) {
        SettingsDialog.RETENTION -> ChoiceDialog(
            title = "Хранить историю",
            options = RetentionPolicy.options,
            selected = settings.retentionDays,
            label = RetentionPolicy::label,
            onSelect = { viewModel.setRetention(it); dialog = null },
            onDismiss = { dialog = null },
        )
        SettingsDialog.THEME -> ChoiceDialog(
            title = "Тема",
            options = ThemeMode.entries,
            selected = settings.themeMode,
            label = { it.label },
            onSelect = { viewModel.setTheme(it); dialog = null },
            onDismiss = { dialog = null },
        )
        SettingsDialog.CLEAR_ALL -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("Удалить все данные?") },
            text = { Text("Будут удалены все сохранённые сообщения, диалоги, контакты и настройки источников. Действие необратимо. Сообщения в исходных приложениях не затрагиваются.") },
            confirmButton = {
                TextButton(onClick = { viewModel.clearAll(); dialog = null }) {
                    Text("Удалить", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("Отмена") } },
        )
        SettingsDialog.SECURITY -> InfoDialog(
            title = "Защита данных",
            text = """
                • Все данные хранятся только на этом устройстве. У NEXUS нет разрешения на доступ в интернет.
                • База данных находится в закрытой папке приложения, недоступной другим приложениям. Сама база НЕ зашифрована: при доступе к файловой системе (root) её можно прочитать.
                • Резервное копирование и перенос данных NEXUS в облако или на другое устройство отключены.
                • Токены будущих официальных интеграций шифруются ключом из Android Keystore. Пароли сторонних сервисов NEXUS не запрашивает и не хранит.
                • Содержимое сообщений не пишется в системный журнал.
            """.trimIndent(),
            onDismiss = { dialog = null },
        )
        SettingsDialog.ABOUT -> InfoDialog(
            title = "NEXUS ${BuildConfig.VERSION_NAME}",
            text = """
                Единая лента сообщений из уведомлений Telegram, WhatsApp, Viber, VK, Messenger, SMS, Gmail и других приложений.

                Версия 0.1 работает через системный доступ к уведомлениям: в NEXUS попадают только сообщения, пришедшие уведомлением после установки. Ответ возможен, пока уведомление беседы с кнопкой «Ответить» остаётся в шторке.

                Устройство: ${SystemStatus.deviceDescription}
            """.trimIndent(),
            onDismiss = { dialog = null },
        )
        null -> Unit
    }
}

private enum class SettingsDialog { RETENTION, THEME, CLEAR_ALL, SECURITY, ABOUT }

@Composable
internal fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 4.dp),
    )
}

@Composable
private fun SettingRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = { Icon(icon, contentDescription = null) },
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
    )
}

@Composable
private fun <T> ChoiceDialog(
    title: String,
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                options.forEach { option ->
                    ListItem(
                        modifier = Modifier.selectable(
                            selected = option == selected,
                            onClick = { onSelect(option) },
                            role = Role.RadioButton,
                        ),
                        leadingContent = { RadioButton(selected = option == selected, onClick = null) },
                        headlineContent = { Text(label(option)) },
                    )
                }
                HorizontalDivider()
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } },
    )
}

@Composable
internal fun InfoDialog(title: String, text: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Понятно") } },
    )
}
