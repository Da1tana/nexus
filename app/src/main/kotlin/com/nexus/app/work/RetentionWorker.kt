package com.nexus.app.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.nexus.app.settings.SettingsStore
import com.nexus.core.data.repository.MaintenanceRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/** Ежедневная очистка истории старше выбранного срока хранения. */
@HiltWorker
class RetentionWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val settings: SettingsStore,
    private val maintenance: MaintenanceRepository,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val days = settings.current().retentionDays
        maintenance.applyRetention(days)
        return Result.success()
    }

    companion object {
        private const val NAME = "nexus_retention"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<RetentionWorker>(1, TimeUnit.DAYS).build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
