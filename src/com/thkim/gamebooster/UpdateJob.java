package com.thkim.gamebooster;

import android.app.job.JobParameters;
import android.app.job.JobService;

/** 백그라운드에서 새 버전을 확인해 알림을 띄우는 작업 */
public class UpdateJob extends JobService {
    @Override
    public boolean onStartJob(final JobParameters params) {
        new Thread(() -> {
            Updater.notifyIfNew(getApplicationContext(), Updater.check(getApplicationContext()));
            jobFinished(params, false);
        }).start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) { return true; }
}
