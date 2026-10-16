package com.yeknomusic.ftwbridge

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/** Hourly background sync. Reading from Samsung Health in the background needs the extra
 *  "access in background" permission; without it, this still sends workouts and weight. */
class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val cfg = Bridge.config(applicationContext) ?: return Result.success()
        if (HealthConnectClient.getSdkStatus(applicationContext) != HealthConnectClient.SDK_AVAILABLE) return Result.success()
        return try {
            SyncEngine(applicationContext, cfg.first, cfg.second).run(foreground = false)
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }
}

fun Bridge.schedule(ctx: Context) {
    val request = PeriodicWorkRequestBuilder<SyncWorker>(1, TimeUnit.HOURS)
        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
        .build()
    WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("ftw-health-sync", ExistingPeriodicWorkPolicy.KEEP, request)
}
