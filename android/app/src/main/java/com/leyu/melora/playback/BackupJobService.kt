package com.leyu.melora.playback

import android.app.job.JobParameters
import android.app.job.JobService
import com.leyu.melora.MeloraApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class BackupJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val running = mutableMapOf<Int, Job>()

    override fun onStartJob(params: JobParameters): Boolean {
        running[params.jobId]?.cancel()
        running[params.jobId] = scope.launch {
            val app = application as MeloraApplication
            val ready = app.restoreFailure == null && withTimeoutOrNull(30_000L) {
                app.awaitStartup()
                true
            } == true
            val retry = !ready || BackupSchedule.runAutomatic(app, params.jobId)
            running.remove(params.jobId)
            jobFinished(params, retry)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        running.remove(params.jobId)?.cancel()
        return true
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
