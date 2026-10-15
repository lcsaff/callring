package com.greatwall.callring;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ResultReceiver;
import android.telecom.Call;
import android.telecom.CallScreeningService;
import android.telephony.TelephonyManager;
import java.util.UUID;

public class RuleScreeningService extends CallScreeningService {
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override public void onScreenCall(Call.Details details) {
        if (details.getCallDirection() != Call.Details.DIRECTION_INCOMING) return;
        String number = details.getHandle() == null ? null : details.getHandle().getSchemeSpecificPart();
        long at=details.getCreationTimeMillis()>0?details.getCreationTimeMillis():System.currentTimeMillis();
        android.telecom.PhoneAccountHandle handle=details.getAccountHandle();
        String account=handle==null?null:handle.getComponentName().flattenToString()+"|"+handle.getId();
        boolean[] inspected={false};
        Runnable timeout=()->{if(inspected[0])return;inspected[0]=true;respond(details,false);EventLog.add(this,"策略读取超时，保留系统铃声");};
        handler.postDelayed(timeout,800);
        IncomingCallManager.execute(()->{try{
            IncomingCallManager.Decision decision=IncomingCallManager.inspect(this,number,at,true,account);
            handler.post(()->{if(inspected[0]){IncomingCallManager.complete(this,decision,"策略读取超时，保留系统铃声");return;}
                inspected[0]=true;handler.removeCallbacks(timeout);screenWithPolicy(details,decision);});
        }catch(RuntimeException e){handler.post(()->{if(inspected[0])return;inspected[0]=true;handler.removeCallbacks(timeout);respond(details,false);EventLog.add(this,"策略不可用，保留系统铃声");});}});
    }

    private void screenWithPolicy(Call.Details details,IncomingCallManager.Decision decision){
        String number=decision.number;
        if(decision.silence){respond(details,true);IncomingCallManager.complete(this,decision,"免打扰静音");IncomingCallManager.show(this,decision,"免打扰静音");EventLog.add(this,"免打扰生效，来电放行并静音");return;}
        try {
            boolean permitted = AppSettings.phonePermission(this);
            boolean active = permitted && CallStateSnapshot.active(getSystemService(TelephonyManager.class).getCallState(),
                    getSystemService(android.media.AudioManager.class).getMode());
            if (!RulePolicy.shouldReplace(number, true, AppSettings.enabled(this), permitted,
                    AppSettings.audible(this), active)) {
                respond(details, false);
                IncomingCallManager.complete(this,decision,decision.label);
                IncomingCallManager.show(this,decision,decision.label);
                EventLog.add(this, RulePolicy.matches(number) ? "命中但条件不满足，保留系统铃声" : "未命中，正常放行");
                return;
            }
        } catch (RuntimeException e) {
            respond(details, false);
            IncomingCallManager.complete(this,decision,"状态检查失败，保留系统铃声");
            EventLog.add(this, "状态检查失败，保留系统铃声");
            return;
        }
        EventLog.add(this, "规则命中，准备音频");
        String token = UUID.randomUUID().toString();
        long deadline = android.os.SystemClock.elapsedRealtime() + 1800;
        boolean[] finished = {false};
        Runnable fallback = () -> {
            if (finished[0]) return;
            finished[0] = true;
            respond(details, false);
            RingPlaybackService.command(this, RingPlaybackService.CANCEL, token);
            IncomingCallManager.complete(this,decision,"音频准备超时，保留系统铃声");
            IncomingCallManager.show(this,decision,"正常响铃");
            EventLog.add(this, "准备超时，保留系统铃声");
        };
        handler.postDelayed(fallback, 1800);
        ResultReceiver ready = new ResultReceiver(handler) {
            @Override protected void onReceiveResult(int resultCode, Bundle data) {
                if (finished[0]) {
                    RingPlaybackService.command(RuleScreeningService.this, RingPlaybackService.CANCEL, token);
                    return;
                }
                if(resultCode!=1||android.os.SystemClock.elapsedRealtime()>=deadline){fallback.run();return;}
                // Re-read changed calendar/leave/quiet settings off the main thread, keeping
                // the original response deadline active while storage is busy.
                IncomingCallManager.execute(()->{try{
                    IncomingCallManager.Decision latest=IncomingCallManager.inspect(RuleScreeningService.this,number,decision.at,false);
                    handler.post(()->{
                if(finished[0]){RingPlaybackService.command(RuleScreeningService.this,RingPlaybackService.CANCEL,token);return;}
                finished[0] = true;handler.removeCallbacks(fallback);
                // Same-process services use the main looper: reserve synchronously so a queued
                // cancellation cannot invalidate READY between this check and respondToCall.
                boolean success = !latest.silence && android.os.SystemClock.elapsedRealtime() < deadline
                        && AppSettings.enabled(RuleScreeningService.this)
                        && RingPlaybackService.commitPrepared(token);
                respond(details, latest.silence||success);
                if (!success) RingPlaybackService.command(RuleScreeningService.this, RingPlaybackService.CANCEL, token);
                String label=latest.silence?"免打扰静音":latest.white?"白名单放行":success?"规则铃声":"正常响铃";
                IncomingCallManager.complete(RuleScreeningService.this,decision,label);
                IncomingCallManager.show(RuleScreeningService.this,latest,label,decision.at);
                EventLog.add(RuleScreeningService.this, success ? "已放行来电，系统铃声静音" : "音频未就绪，保留系统铃声");
                    });
                }catch(RuntimeException e){handler.post(fallback);}});
            }
        };
        try {
            startForegroundService(new Intent(this, RingPlaybackService.class)
                    .setAction(RingPlaybackService.PREPARE).putExtra("token", token).putExtra("ready", ready));
        } catch (RuntimeException e) {
            finished[0] = true;
            handler.removeCallbacks(fallback);
            respond(details, false);
            IncomingCallManager.complete(this,decision,"后台启动受限，保留系统铃声");
            IncomingCallManager.show(this,decision,"正常响铃");
            EventLog.add(this, "后台启动受限，保留系统铃声");
        }
    }

    private void respond(Call.Details details, boolean silence) {
        respondToCall(details, new CallResponse.Builder().setDisallowCall(false)
                .setRejectCall(false).setSilenceCall(silence).build());
    }
}
