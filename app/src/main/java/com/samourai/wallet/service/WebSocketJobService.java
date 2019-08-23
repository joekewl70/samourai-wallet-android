package com.samourai.wallet.service;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;

import com.samourai.wallet.tor.TorManager;
import com.samourai.wallet.util.LogUtil;

import java.util.List;

public class WebSocketJobService extends JobService {

    private static final String TAG = "WebSocketJobService";
    private WebSocketHandler webSocketHandler;
    public static int JOB_ID = 19;

    @Override
    public boolean onStartJob(JobParameters jobParameters) {
         LogUtil.debug(TAG, "onStartJob: ");
        webSocketHandler = new WebSocketHandler(getApplicationContext());
        webSocketHandler.onStart();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters jobParameters) {
        LogUtil.debug(TAG, "onStopJob: ");
        webSocketHandler.onStop();
        return false;
    }


    public static void startJobService(Context context) {
        if (!TorManager.getInstance(context).isConnected()) {
            JobScheduler jobScheduler = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);

            JobInfo jobInfo = new JobInfo.Builder(JOB_ID, new ComponentName(context.getApplicationContext(), WebSocketJobService.class))
                    // only add if network access is required
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .build();

            if (jobScheduler != null) {
                jobScheduler.schedule(jobInfo);
            }

        }
    }

    public static void cancelJobs(Context context) {
        LogUtil.info(TAG, "cancelJobs: ");
        JobScheduler jobScheduler = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (jobScheduler != null) {
            jobScheduler.cancelAll();
        }
    }


    public static boolean isRunning(Context context) {
        boolean isRunning = false;
        JobScheduler jobScheduler = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (jobScheduler != null) {
            List<JobInfo> jobs = jobScheduler.getAllPendingJobs();
            if(jobs.size() > 0){
                isRunning = true;
            }
        }
        LogUtil.debug(TAG, "isRunning: ".concat(String.valueOf(isRunning)));
        return isRunning;
    }


}
