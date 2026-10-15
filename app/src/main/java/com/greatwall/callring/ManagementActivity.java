package com.greatwall.callring;

import android.app.Activity;
import android.content.*;
import android.os.Build;
import android.os.Bundle;

abstract class ManagementActivity extends Activity {
    protected CallerRepository store;
    private boolean registered;
    private final BroadcastReceiver changes=new BroadcastReceiver(){@Override public void onReceive(Context context,Intent intent){if(refreshOnChange())safeRender();}};
    protected boolean refreshOnChange(){return true;}
    protected abstract void render();
    private void safeRender(){try{render();}catch(RuntimeException e){android.widget.LinearLayout page=Ui.page(this,"暂时无法加载");Ui.text(page,"数据读取失败，请重试。",16);Ui.button(page,"重新加载",v->safeRender());}}
    @Override public void onCreate(Bundle state){super.onCreate(state);try{store=CallerRepository.get(this);safeRender();}catch(RuntimeException e){android.widget.LinearLayout page=Ui.page(this,"数据暂时不可用");Ui.text(page,"请重试，已有数据不会被清除。",16);Ui.button(page,"重试",v->recreate());}}
    @Override protected void onResume(){super.onResume();if(store==null)return;if(refreshOnChange())safeRender();IntentFilter filter=new IntentFilter(ManagementEvents.ACTION);
        if(Build.VERSION.SDK_INT>=33)registerReceiver(changes,filter,Context.RECEIVER_NOT_EXPORTED);else registerReceiver(changes,filter);registered=true;}
    @Override protected void onPause(){if(registered){unregisterReceiver(changes);registered=false;}super.onPause();}
}
