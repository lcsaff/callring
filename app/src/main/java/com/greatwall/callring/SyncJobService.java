package com.greatwall.callring;

import android.app.job.*;
import android.content.ComponentName;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public class SyncJobService extends JobService {
    private static final int ID=74;
    private static final int CONTENT_ID=75;
    private static final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final ConcurrentHashMap<JobParameters,AtomicBoolean> runs=new ConcurrentHashMap<>();
    static void schedule(Context c){
        try{scheduleObserver(c,false);JobScheduler scheduler=c.getSystemService(JobScheduler.class);if(scheduler.getPendingJob(ID)!=null)return;
            scheduler.schedule(new JobInfo.Builder(ID,new ComponentName(c,SyncJobService.class)).setPersisted(true).setMinimumLatency(1500)
                    .setBackoffCriteria(15000,JobInfo.BACKOFF_POLICY_LINEAR).build());
        }catch(RuntimeException e){EventLog.add(c,"通话结果同步待恢复");}
    }
    private static void scheduleObserver(Context c,boolean replace){
        JobScheduler scheduler=c.getSystemService(JobScheduler.class);
        if(!CallLogSynchronizer.permitted(c)){scheduler.cancel(CONTENT_ID);return;}
        if(!replace&&scheduler.getPendingJob(CONTENT_ID)!=null)return;
        // Content triggers cannot be persisted. The separate persisted completion job and
        // BOOT_COMPLETED restore this system-managed observer after a reboot.
        int status=scheduler.schedule(new JobInfo.Builder(CONTENT_ID,new ComponentName(c,SyncJobService.class))
                .addTriggerContentUri(new JobInfo.TriggerContentUri(android.provider.CallLog.Calls.CONTENT_URI,
                        JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS))
                .setTriggerContentUpdateDelay(1000).setTriggerContentMaxDelay(10000).build());
        if(status!=JobScheduler.RESULT_SUCCESS)throw new IllegalStateException("通话记录观察任务未安排成功");
    }
    @Override public boolean onStartJob(JobParameters params){
        AtomicBoolean stopped=new AtomicBoolean();runs.put(params,stopped);
        worker.execute(()->{boolean retry=false;try{retry=CallLogSynchronizer.synchronize(this,stopped);}catch(SecurityException e){EventLog.add(this,"通话记录权限不可用，保留未知时长");}
            catch(RuntimeException e){EventLog.add(this,"通话结果同步暂未完成");retry=true;}
            boolean again=retry;new Handler(Looper.getMainLooper()).post(()->{
                runs.remove(params);if(stopped.get())return;ManagementEvents.changed(this);
                if(params.getJobId()==CONTENT_ID){
                    // Replace the running job directly. Calling jobFinished first would discard
                    // content changes accumulated while the cursor was being processed.
                    try{scheduleObserver(this,true);if(again)schedule(this);}
                    catch(RuntimeException e){EventLog.add(this,"通话记录变化观察待恢复");jobFinished(params,true);}
                }
                else jobFinished(params,again);
            });});
        return true;
    }
    @Override public boolean onStopJob(JobParameters params){AtomicBoolean stopped=runs.remove(params);if(stopped!=null)stopped.set(true);return CallLogSynchronizer.permitted(this);}
    @Override public void onDestroy(){for(AtomicBoolean stopped:runs.values())stopped.set(true);runs.clear();super.onDestroy();}
}
