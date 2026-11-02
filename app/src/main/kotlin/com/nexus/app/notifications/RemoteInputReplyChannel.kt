package com.nexus.app.notifications

import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import com.nexus.core.engine.reply.ReplyChannel
import com.nexus.core.engine.reply.ReplyTarget
import com.nexus.core.engine.reply.SendResult
import com.nexus.core.model.ReplyMethod
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Ответ через действие «Ответить» из уведомления исходного приложения (RemoteInput).
 * Это тот же механизм, который использует шторка уведомлений и Android Auto.
 * Успешный вызов PendingIntent означает только «текст передан приложению»;
 * доставка подтверждается позже — если приложение покажет ответ в обновлённом уведомлении.
 */
@Singleton
class RemoteInputReplyChannel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val registry: ActiveNotificationRegistry,
) : ReplyChannel {

    override val method: ReplyMethod = ReplyMethod.NOTIFICATION_REMOTE_INPUT

    override suspend fun isAvailable(target: ReplyTarget): Boolean =
        registry.find(target.sourcePackage, target.conversationExternalKey)?.replyAction != null

    override suspend fun send(target: ReplyTarget, text: String): SendResult {
        val entry = registry.find(target.sourcePackage, target.conversationExternalKey)
            ?: return SendResult.Unsupported("Уведомление беседы уже закрыто")
        val action = entry.replyAction
            ?: return SendResult.Unsupported("Приложение не предоставило действие ответа")
        val inputs = action.remoteInputs?.filter { it.allowFreeFormInput }.orEmpty()
        if (inputs.isEmpty()) return SendResult.Unsupported("Действие ответа не принимает текст")

        val results = Bundle().apply { inputs.forEach { putCharSequence(it.resultKey, text) } }
        val fillIn = Intent().addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        RemoteInput.addResultsToIntent(inputs.toTypedArray(), fillIn, results)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            RemoteInput.setResultsSource(fillIn, RemoteInput.SOURCE_FREE_FORM_INPUT)
        }

        return try {
            action.actionIntent.send(context, 0, fillIn)
            SendResult.HandedOff
        } catch (e: PendingIntent.CanceledException) {
            registry.invalidate(target.sourcePackage, target.conversationExternalKey)
            SendResult.Failed("Приложение отменило действие ответа: уведомление устарело")
        }
    }
}
