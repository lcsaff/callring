package com.greatwall.callring;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.telecom.PhoneAccount;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;

/** Shell-only probes. This component and the synthetic account are absent from release builds. */
public class DebugProbeReceiver extends BroadcastReceiver {
    private static final java.util.concurrent.ExecutorService qaWorker = java.util.concurrent.Executors.newSingleThreadExecutor();
    @Override public void onReceive(Context context, Intent intent) {
        String operation = intent.getStringExtra("op");
        if (operation != null && operation.startsWith("qa_")) {
            PendingResult pending = goAsync();
            qaWorker.execute(() -> {
                try { pending.setResultData(DebugManagementFixture.run(context, intent)); }
                catch (Exception e) { pending.setResultData("failed:"+e.getClass().getSimpleName()); }
                finally { pending.finish(); }
            });
            return;
        }
        TelecomManager telecom = context.getSystemService(TelecomManager.class);
        PhoneAccountHandle account = new PhoneAccountHandle(new ComponentName(context, DebugConnectionService.class), "rule-ring-test");
        try {
            switch (intent.getStringExtra("op")) {
                case "register":
                    telecom.registerPhoneAccount(new PhoneAccount.Builder(account, "规则铃声本地测试")
                            .setCapabilities(PhoneAccount.CAPABILITY_CALL_PROVIDER)
                            .addSupportedUriScheme(PhoneAccount.SCHEME_TEL).build()); break;
                case "incoming":
                    String number = intent.getStringExtra("number");
                    if (!"31234".equals(number) && !"41234".equals(number)) throw new IllegalArgumentException();
                    Bundle extras = new Bundle(); extras.putParcelable(TelecomManager.EXTRA_INCOMING_CALL_ADDRESS, Uri.fromParts("tel", number, null));
                    telecom.addNewIncomingCall(account, extras); break;
                case "answer": if (DebugConnectionService.current != null) DebugConnectionService.current.onAnswer(); break;
                case "end": if (DebugConnectionService.current != null) DebugConnectionService.current.onDisconnect(); break;
                case "unregister":
                    if (DebugConnectionService.current != null) DebugConnectionService.current.onDisconnect();
                    telecom.unregisterPhoneAccount(account); break;
                case "enable": AppSettings.enabled(context, true); break;
                case "disable": AppSettings.enabled(context, false); RingPlaybackService.stop(context); break;
                case "preview": RingPlaybackService.preview(context); break;
                case "stop": RingPlaybackService.stop(context); break;
                case "status":
                    if (context.checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE)
                            != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        setResultData("enabled=" + AppSettings.enabled(context) + ", callState=unknown, inCall=unknown");
                        return;
                    }
                    setResultData("enabled=" + AppSettings.enabled(context) + ", callState=" + context.getSystemService(android.telephony.TelephonyManager.class).getCallState() + ", inCall=" + telecom.isInCall());
                    return;
                default: throw new IllegalArgumentException();
            }
            setResultData("ok");
        } catch (RuntimeException e) {
            EventLog.add(context, "测试操作失败：" + e.getClass().getSimpleName());
            setResultData("failed:" + e.getClass().getSimpleName());
        }
    }
}
