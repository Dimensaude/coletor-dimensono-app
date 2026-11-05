package br.dimensono.bandsono;

import android.app.job.JobParameters;
import android.app.job.JobService;

/** Runs the Supabase upload for JobScheduler (periodic every 2 h, and right after each sync). */
public class UploadJobService extends JobService {
    @Override
    public boolean onStartJob(final JobParameters params) {
        if (!SupabaseUploader.enabled(this))
            return false;
        final String reason = params.getJobId() == SupabaseUploader.JOB_PERIODIC ? "a cada 2 h" : "após sincronizar";
        SupabaseUploader.runAsync(this, reason, r -> jobFinished(params, !r.ok && r.retry));
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        SupabaseUploader.cancel();
        return true;
    }
}
