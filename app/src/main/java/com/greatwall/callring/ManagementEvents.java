package com.greatwall.callring;

import android.content.Context;
import android.content.Intent;

final class ManagementEvents {
    static final String ACTION="com.greatwall.callring.MANAGEMENT_CHANGED";
    static void changed(Context context){
        context.sendBroadcast(new Intent(ACTION).setPackage(context.getPackageName()));
        CallerWidgetProvider.updateAll(context);
        CallerOverlayService.refreshActive();
    }
}
