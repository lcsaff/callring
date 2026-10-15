package com.greatwall.callring;

import android.content.Context;
import android.os.PowerManager;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

final class IncomingCallManager {
    private static final ExecutorService worker=Executors.newFixedThreadPool(2);
    static final class Decision {
        final String number;final long recordId,at;final boolean matched,white,silence,overlay;final String label;
        Decision(String n,long id,long time,boolean match,boolean w,boolean quiet,boolean floating,String status){number=n;recordId=id;at=time;matched=match;white=w;silence=quiet;overlay=floating;label=status;}
    }
    static void execute(Runnable task){worker.execute(task);}
    static Decision inspect(Context c,String raw,long at,boolean record){
        return inspect(c,raw,at,record,null);
    }
    static Decision inspect(Context c,String raw,long at,boolean record,String account){
        PowerManager.WakeLock lease=c.getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"RuleRing:decision");lease.acquire(5000);
        try{
            CallerRepository store=CallerRepository.get(c);String number=NumberNormalizer.normalize(raw);boolean matched=RulePolicy.matches(number);
            synchronized(store){
                boolean white=number.matches("\\+?[0-9]{1,30}")&&store.caller(number).whitelist;
                WorkSchedulePolicy.Config config=store.getConfigAt(at);boolean working=WorkSchedulePolicy.evaluate(config,at).working;
                boolean silence=QuietHoursPolicy.shouldSilence(matched,white,store.allNumbers(),store.quietMode(),working,at,config.zone,store.quietWindows());
                String label=white?"白名单放行":silence?"免打扰静音":"正常响铃";
                boolean overlay=store.overlayEnabled();long id=-1;
                if(record)try{id=store.recordIncoming(number,at,label,account);}
                    catch(RuntimeException e){EventLog.add(c,"来电明细登记失败，保留已计算的来电决策");}
                return new Decision(number,id,at,matched,white,silence,overlay,label);
            }
        }finally{if(lease.isHeld())lease.release();}
    }
    static void complete(Context c,Decision decision,String label){
        Context app=c.getApplicationContext();execute(()->{try{CallerRepository.get(app).updateDecision(decision.recordId,label);ManagementEvents.changed(app);SyncJobService.schedule(app);}
            catch(RuntimeException e){EventLog.add(app,"来电明细待补全");}});
    }
    static void show(Context c,Decision decision,String label){show(c,decision,label,decision.at);}
    static void show(Context c,Decision decision,String label,long at){if(decision.matched&&decision.overlay)CallerOverlayService.show(c,decision.number,label,true,at);}
}
