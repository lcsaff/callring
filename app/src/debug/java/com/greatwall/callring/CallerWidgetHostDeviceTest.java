package com.greatwall.callring;

import android.Manifest;
import android.appwidget.AppWidgetHost;
import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.test.InstrumentationTestCase;
import android.view.View;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;

/** Uses the real system widget host and RemoteViews, with temporary test-owned IDs. */
public class CallerWidgetHostDeviceTest extends InstrumentationTestCase {
    private Context context;
    private AppWidgetHost host;
    private AppWidgetHostView firstView,secondView;
    private int first,second;
    private boolean fixture;
    @Override protected void setUp()throws Exception{
        super.setUp();context=getInstrumentation().getTargetContext().getApplicationContext();
        try{
        DebugManagementFixture.run(context,new Intent().putExtra("op","qa_begin"));fixture=true;
        getInstrumentation().getUiAutomation().adoptShellPermissionIdentity(Manifest.permission.BIND_APPWIDGET);
        getInstrumentation().runOnMainSync(()->{host=new AppWidgetHost(context,20741);host.startListening();});
        first=host.allocateAppWidgetId();second=host.allocateAppWidgetId();
        AppWidgetManager manager=AppWidgetManager.getInstance(context);ComponentName provider=new ComponentName(context,CallerWidgetProvider.class);
        assertTrue("system must allow test widget binding",manager.bindAppWidgetIdIfAllowed(first,provider));
        assertTrue(manager.bindAppWidgetIdIfAllowed(second,provider));
        CallerWidgetProvider.bind(context,first,"31234");CallerWidgetProvider.bind(context,second,"31235");
        CallerRepository.get(context).saveCaller("31235","其他部门","第二号码",null,false);
        getInstrumentation().runOnMainSync(()->{firstView=host.createView(context,first,manager.getAppWidgetInfo(first));secondView=host.createView(context,second,manager.getAppWidgetInfo(second));});
        CallerWidgetProvider.update(context,first);CallerWidgetProvider.update(context,second);
        await(()->text(firstView,R.id.widget_number).equals("31234")&&text(secondView,R.id.widget_number).equals("31235"));
        }catch(Exception|AssertionError failure){tearDown();throw failure;}
    }
    @Override protected void tearDown()throws Exception{
        if(host!=null){if(first!=0)host.deleteAppWidgetId(first);if(second!=0)host.deleteAppWidgetId(second);host.stopListening();}
        if(context!=null)new CallerWidgetProvider().onDeleted(context,new int[]{first,second});
        getInstrumentation().getUiAutomation().dropShellPermissionIdentity();
        if(fixture)DebugManagementFixture.run(context,new Intent().putExtra("op","qa_finish"));
        super.tearDown();
    }
    public void testTwoRealWidgetsRenderIndependentProfilesAndWhitelistClick()throws Exception{
        assertTrue(text(firstView,R.id.widget_position).contains("↑"));
        assertTrue(text(firstView,R.id.widget_profile).contains("模拟来电"));
        assertTrue(text(secondView,R.id.widget_profile).contains("第二号码"));
        getInstrumentation().runOnMainSync(()->firstView.findViewById(R.id.widget_white).performClick());
        await(()->CallerRepository.get(context).caller("31234").whitelist);
        assertFalse(CallerRepository.get(context).caller("31235").whitelist);
        await(()->text(firstView,R.id.widget_white).equals("移出白名单"));
        getInstrumentation().runOnMainSync(()->{
            int width=Math.round(360*context.getResources().getDisplayMetrics().density);
            int height=AppWidgetManager.getInstance(context).getAppWidgetInfo(first).minResizeHeight;
            firstView.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));firstView.layout(0,0,width,height);
            View white=firstView.findViewById(R.id.widget_white);int bottom=white.getBottom();android.view.ViewParent parent=white.getParent();
            while(parent instanceof View&&parent!=firstView){bottom+=((View)parent).getTop();parent=parent.getParent();}
            assertTrue("widget quick actions are clipped at its supported minimum height: "+bottom+">"+height,bottom<=height);
            Bitmap image=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);firstView.draw(new Canvas(image));
            try(FileOutputStream output=new FileOutputStream(new File(context.getCacheDir(),"widget-host-qa.png"))){image.compress(Bitmap.CompressFormat.PNG,100,output);}catch(Exception e){throw new IllegalStateException(e);}finally{image.recycle();}
        });
    }
    private String text(AppWidgetHostView view,int id){TextView text=view.findViewById(id);return text==null?"":text.getText().toString();}
    interface Check{boolean run()throws Exception;}
    private void await(Check condition)throws Exception{long until=System.currentTimeMillis()+5000;while(System.currentTimeMillis()<until){if(condition.run())return;Thread.sleep(40);}fail("widget state did not update");}
}
