package com.greatwall.callring;

import android.content.*;
import android.telephony.TelephonyManager;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

public class RuntimeReceiver extends BroadcastReceiver {
    private static final ExecutorService worker=Executors.newSingleThreadExecutor();
    @Override public void onReceive(Context context,Intent intent){
        String action=intent.getAction();
        if(TelephonyManager.ACTION_PHONE_STATE_CHANGED.equals(action)){
            String state=intent.getStringExtra(TelephonyManager.EXTRA_STATE);CallerOverlayService.onCallState(context,state);
            if(TelephonyManager.EXTRA_STATE_IDLE.equals(state))SyncJobService.schedule(context);
            return;
        }
        if(!Intent.ACTION_BOOT_COMPLETED.equals(action)&&!Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)&&!Intent.ACTION_TIME_CHANGED.equals(action)
                &&!Intent.ACTION_TIMEZONE_CHANGED.equals(action)&&!Intent.ACTION_DATE_CHANGED.equals(action))return;
        PendingResult pending=goAsync();Context app=context.getApplicationContext();worker.execute(()->{try{SyncJobService.schedule(app);ManagementEvents.changed(app);}catch(RuntimeException e){EventLog.add(app,"后台状态待恢复");}finally{pending.finish();}});
    }
}
