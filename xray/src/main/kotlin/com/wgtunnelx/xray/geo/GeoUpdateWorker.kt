package com.wgtunnelx.xray.geo

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.wgtunnelx.xray.XrayRouting
import java.util.concurrent.TimeUnit
import timber.log.Timber

/** Periodically refreshes geoip.dat / geosite.dat from the configured source. */
class GeoUpdateWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val routing = XrayRouting.get(applicationContext)
        val settings = routing.store.current
        if (!settings.enabled || settings.geo.autoUpdateDays <= 0) return Result.success()
        return routing.geo
            .updateAll(settings.geo)
            .fold(
                onSuccess = { Result.success() },
                onFailure = {
                    Timber.w(it, "Geo update failed")
                    if (runAttemptCount < 3) Result.retry() else Result.failure()
                },
            )
    }

    companion object {
        private const val WORK_NAME = "wgtunnelx-geo-update"

        /** (Re)schedules or cancels the periodic update according to [days] (0 = off). */
        fun schedule(context: Context, days: Int) {
            val wm = WorkManager.getInstance(context)
            if (days <= 0) {
                wm.cancelUniqueWork(WORK_NAME)
                return
            }
            val request =
                PeriodicWorkRequestBuilder<GeoUpdateWorker>(days.toLong(), TimeUnit.DAYS)
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .setRequiresBatteryNotLow(true)
                            .build()
                    )
                    .setInitialDelay(days.toLong(), TimeUnit.DAYS)
                    .build()
            wm.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }
    }
}
