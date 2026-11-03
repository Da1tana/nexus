package com.nexus.app.ui.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.nexus.app.system.SystemStatus

/** Состояние системных условий, нужных для надёжной работы. Перечитывается при возврате на экран. */
data class SetupState(
    val notificationAccess: Boolean,
    val batteryUnrestricted: Boolean,
) {
    val complete: Boolean get() = notificationAccess && batteryUnrestricted
}

@Composable
fun rememberSetupState(): SetupState {
    val context = LocalContext.current
    fun read() = SetupState(
        notificationAccess = SystemStatus.isNotificationAccessGranted(context),
        batteryUnrestricted = SystemStatus.isIgnoringBatteryOptimizations(context),
    )
    var state by remember { mutableStateOf(read()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { state = read() }
    return state
}

/**
 * Пошаговая настройка: доступ к уведомлениям (обязательно), батарея и автозапуск EMUI (для работы в фоне).
 * Каждый шаг открывает нужный системный экран; NEXUS сам защищённые настройки не меняет.
 */
@Composable
fun SetupGuide(state: SetupState, modifier: Modifier = Modifier, onDismiss: (() -> Unit)? = null) {
    val context = LocalContext.current
    Card(
        modifier = modifier.fillMaxWidth().padding(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(
                if (state.notificationAccess) "Почти готово" else "Настройка NEXUS",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Text(
                "NEXUS собирает сообщения из уведомлений других приложений. Сообщения, пришедшие до настройки, недоступны.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Step(
                number = 1,
                done = state.notificationAccess,
                title = "Доступ к уведомлениям",
                description = "Обязательно. Включите NEXUS в списке и подтвердите.",
                action = "Разрешить",
                onAction = { SystemStatus.openNotificationAccessSettings(context) },
            )
            Step(
                number = 2,
                done = state.batteryUnrestricted,
                title = "Работа в фоне",
                description = "Чтобы EMUI не останавливал NEXUS: отключите оптимизацию батареи для NEXUS.",
                action = "Открыть",
                onAction = { SystemStatus.openBatteryOptimizationSettings(context) },
            )
            Step(
                number = 3,
                done = null,
                title = "Автозапуск (Huawei)",
                description = "Батарея → Запуск приложений → NEXUS: выключите «Управлять автоматически», включите все три переключателя.",
                action = "Открыть",
                onAction = { SystemStatus.openHuaweiLaunchManager(context) },
            )
            if (onDismiss != null && state.notificationAccess) {
                OutlinedButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text("Скрыть") }
            }
        }
    }
}

@Composable
private fun Step(
    number: Int,
    done: Boolean?,
    title: String,
    description: String,
    action: String,
    onAction: () -> Unit,
) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(
            if (done == true) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
            contentDescription = if (done == true) "Выполнено" else "Шаг $number",
            tint = if (done == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "$number. $title",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Text(
                description + if (done == null) " Android не сообщает, выполнен ли этот шаг." else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            if (done != true) {
                Spacer(Modifier.height(8.dp))
                if (number == 1) {
                    Button(onClick = onAction) { Text(action) }
                } else {
                    OutlinedButton(onClick = onAction) { Text(action) }
                }
            }
        }
    }
}
