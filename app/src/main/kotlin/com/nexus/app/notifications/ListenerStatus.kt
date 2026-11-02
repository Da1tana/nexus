package com.nexus.app.notifications

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/** Состояние сервиса уведомлений в текущем процессе — для экрана диагностики. Без содержимого сообщений. */
data class ListenerState(
    val connected: Boolean = false,
    val connectedAt: Long? = null,
    val disconnectedAt: Long? = null,
    val lastEventAt: Long? = null,
    val lastEventPackage: String? = null,
    val lastOutcome: String? = null,
    val eventsThisProcess: Long = 0,
    val lastError: String? = null,
)

@Singleton
class ListenerStatus @Inject constructor() {
    private val state = MutableStateFlow(ListenerState())
    val flow: StateFlow<ListenerState> = state.asStateFlow()

    fun onConnected(now: Long) = state.update { it.copy(connected = true, connectedAt = now) }

    fun onDisconnected(now: Long) = state.update { it.copy(connected = false, disconnectedAt = now) }

    fun onEvent(now: Long, packageName: String, outcome: String) = state.update {
        it.copy(
            lastEventAt = now,
            lastEventPackage = packageName,
            lastOutcome = outcome,
            eventsThisProcess = it.eventsThisProcess + 1,
        )
    }

    fun onError(message: String) = state.update { it.copy(lastError = message) }
}
