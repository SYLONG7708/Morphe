/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.morphe.manager.worker

import android.content.Context
import android.util.Log
import androidx.work.*
import app.morphe.manager.R
import app.morphe.manager.domain.manager.PreferencesManager
import app.morphe.manager.domain.update.EcosystemUpdateCoordinator
import app.morphe.manager.domain.update.UpdateStatus
import app.morphe.manager.util.UpdateNotificationManager
import app.morphe.manager.util.tag
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.concurrent.TimeUnit

/**
 * How often the background update check WorkManager job should repeat.
 *
 * Each entry carries [minutes] - the repeat interval passed to
 * [PeriodicWorkRequestBuilder] - and a string resource ID for the
 * human-readable label shown in Settings.
 *
 * WorkManager enforces a minimum of 15 minutes, so [HOURLY] is the
 * shortest practical option that also keeps battery impact negligible.
 */
enum class UpdateCheckInterval(val minutes: Long, val labelResId: Int) {
    HOURLY(
        minutes = 60L,
        labelResId = R.string.settings_advanced_update_interval_hourly
    ),
    DAILY(
        minutes = 24 * 60L,
        labelResId = R.string.settings_advanced_update_interval_daily
    ),
    WEEKLY(
        minutes = 7 * 24 * 60L,
        labelResId = R.string.settings_advanced_update_interval_weekly
    ),
    MONTHLY(
        minutes = 30 * 24 * 60L,
        labelResId = R.string.settings_advanced_update_interval_monthly
    )
}

/**
 * WorkManager worker that periodically checks for updates in the background.
 * The repeat interval is controlled by [UpdateCheckInterval] and stored in
 * [PreferencesManager.updateCheckInterval] (default: [UpdateCheckInterval.DAILY]).
 *
 * Checks for:
 * - Morphe Manager app updates
 * - Patch bundle updates
 */
class UpdateCheckWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params), KoinComponent {

    private val prefs: PreferencesManager by inject()
    private val coordinator: EcosystemUpdateCoordinator by inject()
    private val notificationManager: UpdateNotificationManager by inject()

    override suspend fun doWork(): Result {
        // Skip if background update notifications are disabled by user
        if (!prefs.backgroundUpdateNotifications.get()) {
            Log.d(tag, "UpdateCheckWorker: background notifications disabled, skipping")
            return Result.success()
        }

        Log.d(tag, "UpdateCheckWorker: starting background update check")

        return try {
            val snapshot = coordinator.refresh(downloadAssets = true)
            if (snapshot.manager.status in NOTIFIABLE) {
                notificationManager.showManagerUpdateNotification(snapshot.manager.availableVersion)
            }
            if (snapshot.patches.status == UpdateStatus.UPDATE_AVAILABLE) {
                notificationManager.showBundleUpdateNotification(snapshot.patches.availableVersion)
            }
            if (snapshot.youtube.status == UpdateStatus.UPDATE_AVAILABLE) {
                notificationManager.showYouTubeUpdateNotification(snapshot.youtube.availableVersion)
            }
            if (snapshot.microg.status in NOTIFIABLE) {
                notificationManager.showMicroGUpdateNotification(snapshot.microg.availableVersion)
            }
            Log.d(tag, "UpdateCheckWorker: background update check completed")
            Result.success()
        } catch (e: Exception) {
            Log.e(tag, "UpdateCheckWorker: failed to check for updates", e)
            // Retry later; avoids spamming logs on persistent failures (e.g. no internet)
            Result.retry()
        }
    }

    companion object {
        /** Unique name used to identify the periodic work in WorkManager */
        const val WORK_NAME = "morphe_update_check"

        /**
         * Schedule (or reschedule) the periodic update check with the given [interval].
         *
         * Uses [ExistingPeriodicWorkPolicy.UPDATE] so that changing the interval in Settings takes effect immediately.
         */
        fun schedule(context: Context, interval: UpdateCheckInterval = UpdateCheckInterval.DAILY) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .setRequiresStorageNotLow(true)
                .build()

            val request = PeriodicWorkRequestBuilder<UpdateCheckWorker>(
                interval.minutes, TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .setInitialDelay(interval.minutes, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )

            Log.d("UpdateCheckWorker", "Periodic update check scheduled (every ${interval.minutes}m / ${interval.name})")
        }

        /**
         * Cancel the periodic update check when the user turns off background notifications.
         */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            Log.d("UpdateCheckWorker", "Periodic update check cancelled")
        }

        private val NOTIFIABLE = setOf(
            UpdateStatus.UPDATE_AVAILABLE,
            UpdateStatus.DOWNLOADED,
        )
    }
}
