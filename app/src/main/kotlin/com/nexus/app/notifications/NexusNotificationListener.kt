package com.nexus.app.notifications

import android.content.ComponentName
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.nexus.core.data.repository.IngestOutcome
import com.nexus.core.data.repository.NotificationRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject

/**
 * Системный сервис получения уведомлений. Работает, пока пользователь предоставил доступ
 * к уведомлениям; система сама перезапускает его после перезагрузки.
 *
 * В логи пишутся только имена пакетов и типы ошибок — никогда содержимое сообщений.
 */
@AndroidEntryPoint
class NexusNotificationListener : NotificationListenerService() {

    @Inject lateinit var repository: NotificationRepository
    @Inject lateinit var registry: ActiveNotificationRegistry
    @Inject lateinit var status: ListenerStatus
    @Inject lateinit var avatars: AvatarStore
    @Inject lateinit var reprocess: ReprocessRequests

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Последовательная обработка: события одного уведомления приходят по порядку. */
    private val mutex = Mutex()

    private var reprocessJob: Job? = null

    override fun onListenerConnected() {
        super.onListenerConnected()
        status.onConnected(System.currentTimeMillis())
        scope.launch {
            mutex.withLock {
                repository.resetActiveActions()
                registry.clear()
                processActive()
            }
        }
        reprocessJob?.cancel()
        reprocessJob = scope.launch {
            reprocess.requests.collect { mutex.withLock { processActive() } }
        }
    }

    /** Уведомления, уже висящие в шторке (пропущенные, пока сервис был остановлен, или от только что включённого приложения). */
    private suspend fun processActive() {
        val active = try {
            activeNotifications?.toList().orEmpty()
        } catch (e: SecurityException) {
            status.onError("Нет доступа к активным уведомлениям")
            emptyList()
        }
        active.forEach { process(it) }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        reprocessJob?.cancel()
        status.onDisconnected(System.currentTimeMillis())
        registry.clear()
        // Просим систему переподключить сервис (актуально для агрессивного энергосбережения EMUI).
        requestRebind(ComponentName(this, NexusNotificationListener::class.java))
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val notification = sbn ?: return
        scope.launch { mutex.withLock { process(notification) } }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        val notification = sbn ?: return
        registry.removeByNotificationKey(notification.key)
        scope.launch {
            mutex.withLock {
                try {
                    repository.onNotificationRemoved(notification.key)
                } catch (e: Exception) {
                    Log.w(TAG, "remove failed: ${e.javaClass.simpleName}")
                }
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun process(sbn: StatusBarNotification) {
        try {
            val raw = NotificationExtractor.extract(sbn)
            val report = repository.ingest(raw)
            val conversationKey = report.conversationExternalKey
            val conversationId = report.conversationId
            if (report.outcome == IngestOutcome.PROCESSED && conversationKey != null && conversationId != null) {
                registry.register(raw.packageName, conversationKey, sbn)
                avatars.save(conversationId, sbn)?.let { path ->
                    val previous = repository.setConversationAvatar(conversationId, path)
                    if (previous != null && previous != path) avatars.delete(previous)
                }
            }
            status.onEvent(System.currentTimeMillis(), raw.packageName, report.outcome.name)
        } catch (e: Exception) {
            Log.w(TAG, "process failed for ${sbn.packageName}: ${e.javaClass.simpleName}")
            status.onError("${sbn.packageName}: ${e.javaClass.simpleName}")
        }
    }

    private companion object {
        const val TAG = "NexusListener"
    }
}
