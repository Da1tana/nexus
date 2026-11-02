package com.nexus.app.notifications

import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Переход к исходному диалогу.
 * Если уведомление беседы ещё активно — открываем его contentIntent (обычно ведёт прямо в чат).
 * Иначе запускаем приложение-источник: открыть конкретный чат без уведомления Android не позволяет.
 */
@Singleton
class OriginalAppLauncher @Inject constructor(
    @ApplicationContext private val context: Context,
    private val registry: ActiveNotificationRegistry,
) {
    enum class Result { OPENED_CONVERSATION, OPENED_APP, NOT_INSTALLED }

    fun open(packageName: String, conversationExternalKey: String): Result {
        val contentIntent = registry.find(packageName, conversationExternalKey)?.contentIntent
        if (contentIntent != null && sendContentIntent(contentIntent)) return Result.OPENED_CONVERSATION

        val launch = context.packageManager.getLaunchIntentForPackage(packageName)
            ?: return Result.NOT_INSTALLED
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launch)
        return Result.OPENED_APP
    }

    private fun sendContentIntent(intent: PendingIntent): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val options = ActivityOptions.makeBasic()
                .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
            intent.send(context, 0, null, null, null, null, options.toBundle())
        } else {
            intent.send()
        }
        true
    } catch (e: PendingIntent.CanceledException) {
        false
    }
}
