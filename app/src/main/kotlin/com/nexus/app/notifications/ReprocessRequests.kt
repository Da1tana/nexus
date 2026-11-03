package com.nexus.app.notifications

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Просьба к сервису уведомлений заново прочитать активные уведомления.
 * Нужна, когда пользователь включил приложение: его уведомления, уже висящие в шторке,
 * сразу попадают в NEXUS (дубликаты отсеет база).
 */
@Singleton
class ReprocessRequests @Inject constructor() {
    private val flow = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val requests: SharedFlow<Unit> = flow.asSharedFlow()

    fun request() {
        flow.tryEmit(Unit)
    }
}
