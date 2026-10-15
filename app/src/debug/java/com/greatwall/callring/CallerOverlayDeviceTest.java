package com.greatwall.callring;

import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.ParcelFileDescriptor;
import android.test.InstrumentationTestCase;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ScrollView;
import android.widget.Button;
import android.widget.EditText;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/** Real service/window checks against the isolated fixture, never the user's profiles. */
public class CallerOverlayDeviceTest extends InstrumentationTestCase {
    private Context context;
    private String overlayMode;
    private boolean fixture;
    private CallerOverlayService service;
    @Override protected void setUp() throws Exception {
        super.setUp();context=getInstrumentation().getTargetContext().getApplicationContext();
        assertFalse("Unlock the phone before overlay UI checks",context.getSystemService(KeyguardManager.class).isKeyguardLocked());
        try{
        String before=shell("appops get com.greatwall.callring SYSTEM_ALERT_WINDOW");
        java.util.regex.Matcher mode=java.util.regex.Pattern.compile("SYSTEM_ALERT_WINDOW: ([a-z]+)").matcher(before);
        overlayMode=mode.find()?mode.group(1):"default";
        shell("appops set com.greatwall.callring SYSTEM_ALERT_WINDOW allow");
        shell("am start -W -n com.greatwall.callring/.MainActivity");
        DebugManagementFixture.run(context,new Intent().putExtra("op","qa_begin"));fixture=true;
        CallerOverlayService.show(context,"31234","白名单放行",false,System.currentTimeMillis());
        await(()->{service=(CallerOverlayService)field(CallerOverlayService.class,"active").get(null);return service!=null&&(boolean)value("attached");});
        }catch(Exception|AssertionError failure){tearDown();throw failure;}
    }
    @Override protected void tearDown() throws Exception {
        CallerOverlayService.closeActive();await(()->field(CallerOverlayService.class,"active").get(null)==null);
        if(fixture)DebugManagementFixture.run(context,new Intent().putExtra("op","qa_finish"));
        if(overlayMode!=null)shell("appops set com.greatwall.callring SYSTEM_ALERT_WINDOW "+overlayMode);
        super.tearDown();
    }
    public void testHiddenEditorReturnsAfterCallEndedAndKeepsDraft() throws Exception {
        main(()->invoke("beginEdit"));await(()->(boolean)value("editing"));
        main(()->{
            ((EditText)value("nameEdit")).setText("QA未保存姓名");
            field(CallerOverlayService.class,"watchCall").set(service,true);
            field(CallerOverlayService.class,"screenHidden").set(service,true);
            invoke("hideForPrivacy");invoke("callEnded");
        });
        assertFalse((boolean)value("attached"));
        main(()->((BroadcastReceiver)value("screenReceiver")).onReceive(context,new Intent(Intent.ACTION_USER_PRESENT)));
        await(()->(boolean)value("attached"));
        main(()->{
            assertEquals("QA未保存姓名",((EditText)value("nameEdit")).getText().toString());
            capture((View)value("card"),"overlay-editor-qa.png");
            invoke("endEdit");
        });
        await(()->field(CallerOverlayService.class,"active").get(null)==null);
    }
    public void testDelayedEditCannotAttachToANewCallSession() throws Exception {
        ExecutorService worker=(ExecutorService)value("worker");
        CountDownLatch blocked=new CountDownLatch(1),release=new CountDownLatch(1);
        worker.execute(()->{blocked.countDown();try{release.await(3,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}});
        assertTrue(blocked.await(1,TimeUnit.SECONDS));
        try {
            main(()->invoke("beginEdit"));
            CallerOverlayService.show(context,"31235","正常响铃",false,System.currentTimeMillis());
            await(()->"31235".equals(value("number")));
        } finally {release.countDown();}
        await(()->(boolean)value("attached"));getInstrumentation().waitForIdleSync();
        assertFalse("an old request must not edit the new number",(boolean)value("editing"));
        assertEquals("31235",value("number"));
    }
    public void testInlineEditorCanScrollToSaveAndPersistDraft() throws Exception {
        main(()->invoke("beginEdit"));await(()->(boolean)value("editing"));
        main(()->{
            ((EditText)value("nameEdit")).setText("QA悬浮保存姓名");
            ((EditText)value("departmentEdit")).setText("QA悬浮保存部门");
            ViewGroup card=(ViewGroup)value("card");
            for(int i=0;i<card.getChildCount();i++)if(card.getChildAt(i) instanceof ScrollView)
                ((ScrollView)card.getChildAt(i)).fullScroll(View.FOCUS_DOWN);
        });
        getInstrumentation().waitForIdleSync();
        main(()->{
            Button save=findSave((View)value("card"));android.graphics.Rect visible=new android.graphics.Rect();
            assertNotNull(save);assertTrue("save must be reachable after scrolling",save.getGlobalVisibleRect(visible));
            assertTrue(visible.height()>0);capture((View)value("card"),"overlay-save-qa.png");save.performClick();
        });
        await(()->CallerRepository.get(context).caller("31234").name.equals("QA悬浮保存姓名"));
        assertEquals("QA悬浮保存部门",CallerRepository.get(context).caller("31234").department);
    }
    private Button findSave(View view){
        if(view instanceof Button&&"保存".contentEquals(((Button)view).getText()))return (Button)view;
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){Button found=findSave(((ViewGroup)view).getChildAt(i));if(found!=null)return found;}
        return null;
    }
    private Field field(Class<?> type,String name)throws Exception{Field field=type.getDeclaredField(name);field.setAccessible(true);return field;}
    private Object value(String name)throws Exception{return field(CallerOverlayService.class,name).get(service);}
    private void invoke(String name)throws Exception{Method method=CallerOverlayService.class.getDeclaredMethod(name);method.setAccessible(true);method.invoke(service);}
    interface Check{boolean run()throws Exception;}
    interface Action{void run()throws Exception;}
    private void await(Check check)throws Exception{long until=System.currentTimeMillis()+4000;while(System.currentTimeMillis()<until){if(check.run())return;Thread.sleep(30);}fail("service did not reach expected state");}
    private void main(Action action){getInstrumentation().runOnMainSync(()->{try{action.run();}catch(Exception e){throw new IllegalStateException(e);}});}
    private String shell(String command)throws Exception{
        try(ParcelFileDescriptor result=getInstrumentation().getUiAutomation().executeShellCommand(command);FileInputStream stream=new FileInputStream(result.getFileDescriptor());java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream()){
            byte[] buffer=new byte[1024];int count;while((count=stream.read(buffer))!=-1)bytes.write(buffer,0,count);return bytes.toString("UTF-8");
        }
    }
    private void capture(View view,String name)throws Exception{
        Bitmap bitmap=Bitmap.createBitmap(view.getWidth(),view.getHeight(),Bitmap.Config.ARGB_8888);view.draw(new Canvas(bitmap));
        try(FileOutputStream output=new FileOutputStream(new File(context.getCacheDir(),name))){bitmap.compress(Bitmap.CompressFormat.PNG,100,output);}finally{bitmap.recycle();}
    }
}
