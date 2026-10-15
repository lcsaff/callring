package com.greatwall.callring;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.provider.CallLog;
import java.util.concurrent.atomic.AtomicBoolean;

final class CallLogSynchronizer {
    static boolean permitted(Context c){return c.checkSelfPermission(Manifest.permission.READ_CALL_LOG)==PackageManager.PERMISSION_GRANTED;}
    /** Returns whether recent incomplete records should be retried by the system scheduler. */
    static boolean synchronize(Context c,AtomicBoolean stopped){
        CallerRepository store=CallerRepository.get(c);long since=store.statisticsStart();if(since<=0||!permitted(c))return false;
        String[] columns={CallLog.Calls._ID,CallLog.Calls.NUMBER,CallLog.Calls.DATE,CallLog.Calls.DURATION,CallLog.Calls.TYPE,CallLog.Calls.PHONE_ACCOUNT_COMPONENT_NAME,CallLog.Calls.PHONE_ACCOUNT_ID};
        String selection=CallLog.Calls.DATE+">=? AND "+CallLog.Calls.TYPE+" IN (1,3,5,6,7)";
        try(Cursor rows=c.getContentResolver().query(CallLog.Calls.CONTENT_URI,columns,selection,new String[]{""+since},CallLog.Calls.DATE+" ASC,"+CallLog.Calls._ID+" ASC")){
            if(rows==null)throw new IllegalStateException("系统未提供通话记录");
            while(rows.moveToNext()&&!stopped.get()){
                String number=NumberNormalizer.normalize(rows.getString(1));if(!RulePolicy.matches(number))continue;
                int kind=rows.getInt(4);String result=kind==1?"已接听":kind==3?"未接":kind==5?"拒接":kind==6?"被拦截":"其他设备接听";
                String account=rows.isNull(5)||rows.isNull(6)?null:rows.getString(5)+"|"+rows.getString(6);
                store.importCallLog(rows.getString(0),number,rows.getLong(2),rows.isNull(3)?null:rows.getLong(3),result,account);
            }
        }
        long now=System.currentTimeMillis();
        for(CallerRepository.CallRecord call:store.calls(null,now-5*60000L,now+1))if(call.counted&&call.logId==null)return true;
        return false;
    }
}
