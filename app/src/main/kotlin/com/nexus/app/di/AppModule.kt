package com.nexus.app.di

import android.content.Context
import android.content.pm.PackageManager
import com.nexus.app.notifications.RemoteInputReplyChannel
import com.nexus.core.data.db.NexusDatabase
import com.nexus.core.data.repository.ContactRepository
import com.nexus.core.data.repository.ConversationRepository
import com.nexus.core.data.repository.DiagnosticsRepository
import com.nexus.core.data.repository.MaintenanceRepository
import com.nexus.core.data.repository.MessageRepository
import com.nexus.core.data.repository.NotificationRepository
import com.nexus.core.data.repository.SearchRepository
import com.nexus.core.data.repository.SourceRepository
import com.nexus.core.engine.parser.NotificationParser
import com.nexus.core.engine.reply.ReplyEngine
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides @Singleton
    fun database(@ApplicationContext context: Context): NexusDatabase = NexusDatabase.create(context)

    @Provides @Singleton
    fun parser(@ApplicationContext context: Context): NotificationParser = NotificationParser(context.packageName)

    @Provides @Singleton
    fun notificationRepository(
        db: NexusDatabase,
        parser: NotificationParser,
        @ApplicationContext context: Context,
    ): NotificationRepository = NotificationRepository(db, parser, { packageName -> appLabel(context, packageName) })

    /** Каналы отправки по приоритету. Официальных API-каналов в версии 0.1 нет (см. INTEGRATIONS.md). */
    @Provides @Singleton
    fun replyEngine(remoteInput: RemoteInputReplyChannel): ReplyEngine = ReplyEngine(listOf(remoteInput))

    @Provides @Singleton
    fun messageRepository(db: NexusDatabase, engine: ReplyEngine): MessageRepository = MessageRepository(db, engine)

    @Provides @Singleton
    fun conversationRepository(db: NexusDatabase): ConversationRepository = ConversationRepository(db)

    @Provides @Singleton
    fun searchRepository(db: NexusDatabase): SearchRepository = SearchRepository(db)

    @Provides @Singleton
    fun contactRepository(db: NexusDatabase): ContactRepository = ContactRepository(db)

    @Provides @Singleton
    fun sourceRepository(db: NexusDatabase): SourceRepository = SourceRepository(db)

    @Provides @Singleton
    fun maintenanceRepository(db: NexusDatabase): MaintenanceRepository = MaintenanceRepository(db)

    @Provides @Singleton
    fun diagnosticsRepository(db: NexusDatabase): DiagnosticsRepository = DiagnosticsRepository(db)

    private fun appLabel(context: Context, packageName: String): String = try {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    } catch (e: PackageManager.NameNotFoundException) {
        packageName
    }
}
