package com.greatwall.callring;

import android.app.Activity;
import android.content.Intent;
import android.test.InstrumentationTestCase;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.EditText;
import android.widget.Button;
import android.widget.Switch;
import android.content.Context;
import android.net.Uri;
import java.io.File;
import java.nio.charset.StandardCharsets;
import android.os.ParcelFileDescriptor;
import java.io.FileInputStream;

/** Real view checks; only included in the debug APK. Does not save user data. */
public class ManagementUiDeviceTest extends InstrumentationTestCase {
    private Activity opened;
    private Context context;
    @Override protected void setUp()throws Exception{
        super.setUp();context=getInstrumentation().getTargetContext().getApplicationContext();
        DebugManagementFixture.run(context,new Intent().putExtra("op","qa_begin"));
    }
    private Activity launch(String className) {
        // HyperOS blocks an app's activity start while another app is in front.
        // Launch our exported entry through the shell before testing private screens.
        try(ParcelFileDescriptor command=getInstrumentation().getUiAutomation().executeShellCommand("am start -W -n com.greatwall.callring/.MainActivity");
                FileInputStream output=new FileInputStream(command.getFileDescriptor())){
            byte[] buffer=new byte[1024];while(output.read(buffer)!=-1){ }
        }catch(Exception e){throw new IllegalStateException("Cannot foreground app for UI checks",e);}
        Intent intent = new Intent().setClassName("com.greatwall.callring", "com.greatwall.callring." + className)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("number", "31234");
        opened = getInstrumentation().startActivitySync(intent);
        getInstrumentation().waitForIdleSync();
        getInstrumentation().runOnMainSync(()->{
            View view=opened.getWindow().getDecorView();
            android.graphics.Bitmap picture=android.graphics.Bitmap.createBitmap(view.getWidth(),view.getHeight(),android.graphics.Bitmap.Config.ARGB_8888);
            view.draw(new android.graphics.Canvas(picture));
            try(java.io.FileOutputStream file=new java.io.FileOutputStream(new File(context.getCacheDir(),className+"-qa.png"))){picture.compress(android.graphics.Bitmap.CompressFormat.PNG,100,file);}catch(Exception e){throw new IllegalStateException(e);}finally{picture.recycle();}
        });
        return opened;
    }
    @Override protected void tearDown() throws Exception {
        if (opened != null) getInstrumentation().runOnMainSync(() -> opened.finish());
        DebugManagementFixture.run(context,new Intent().putExtra("op","qa_finish"));
        super.tearDown();
    }
    public void testCallerEditorUsesPositionSelector() {
        Activity activity = launch("CallerEditActivity");
        getInstrumentation().runOnMainSync(() -> {
            View root = activity.getWindow().getDecorView();
            assertTrue("position must be selectable", containsSpinner(root));
            assertTrue("name field missing", containsText(root, "姓名"));
            assertTrue("department field missing", containsText(root, "部门"));
            assertTrue("save action missing", containsText(root, "保存信息"));
        });
    }
    public void testCallerListHasCreateAndSettingsActions() {
        Activity activity = launch("CallerListActivity");
        getInstrumentation().runOnMainSync(() -> {
            View root = activity.getWindow().getDecorView();
            assertTrue(containsText(root, "新增号码"));
            assertTrue(containsText(root, "管理设置"));
        });
    }
    public void testManagementSettingsExposesRequiredControls() {
        Activity activity = launch("ManagementSettingsActivity");
        getInstrumentation().runOnMainSync(() -> {
            View root = activity.getWindow().getDecorView();
            assertTrue(containsText(root, "我的当前职位"));
            assertTrue(containsText(root, "请假"));
            assertTrue(containsText(root, "免打扰"));
            assertTrue(containsText(root, "号码评分"));
        });
    }
    public void testEditSavesNameDepartmentAndSelectedPosition()throws Exception{
        Activity activity=launch("CallerEditActivity");
        getInstrumentation().runOnMainSync(()->{
            View root=activity.getWindow().getDecorView();
            ((EditText)findDescription(root,"姓名")).setText("QA编辑姓名");
            ((EditText)findDescription(root,"部门")).setText("QA编辑部门");
            Spinner positions=(Spinner)findDescription(root,"职位");
            for(int i=0;i<positions.getCount();i++)if(positions.getItemAtPosition(i).toString().contains("测试员工"))positions.setSelection(i);
            ((Button)findText(root,"保存信息")).performClick();
        });
        await(()->CallerRepository.get(context).caller("31234").name.equals("QA编辑姓名"));
        CallerRepository.Caller saved=CallerRepository.get(context).caller("31234");
        assertEquals("QA编辑部门",saved.department);assertEquals(CallerRepository.get(context).myPositionId(),saved.positionId);
        assertTrue(CallerRepository.get(context).positionSummary(saved).endsWith("＝"));
    }
    public void testReportExportsRealCsvAndDisplaysDisturbanceScore()throws Exception{
        CallerRepository store=CallerRepository.get(context);long at=System.currentTimeMillis();
        store.saveCaller("31234","QA部门","=QA公式",store.caller("31234").positionId,false);
        store.recordIncoming("31234",at,"白名单放行");store.importCallLog("qa-export-record","31234",at+100,75L,"已接听");
        CallerReportActivity activity=(CallerReportActivity)launch("CallerReportActivity");
        File file=new File(context.getCacheDir(),"report-export-qa.csv");
        getInstrumentation().runOnMainSync(()->{
            assertTrue(containsText(activity.getWindow().getDecorView(),"分数越低，号码越不受欢迎"));
            activity.onActivityResult(40,Activity.RESULT_OK,new Intent().setData(Uri.fromFile(file)));
        });
        await(()->file.exists()&&file.length()>0);
        String csv=new String(java.nio.file.Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8);
        assertTrue(csv.startsWith("\uFEFF"));assertTrue(csv.contains("\"'=QA公式\""));
        assertTrue(csv.contains("QA部门"));assertTrue(csv.contains("\"75\""));
        assertTrue(csv.contains("原始请假版本"));assertTrue(csv.contains("测试员工"));
    }
    public void testFailedStatisticsToggleRestoresPersistedState()throws Exception{
        CallerRepository store=CallerRepository.get(context);
        java.lang.reflect.Field field=CallerRepository.class.getDeclaredField("db");field.setAccessible(true);
        android.database.sqlite.SQLiteDatabase db=(android.database.sqlite.SQLiteDatabase)field.get(store);
        db.execSQL("CREATE TRIGGER qa_fail_pause BEFORE UPDATE ON statistics_intervals BEGIN SELECT RAISE(ABORT,'QA cannot save'); END");
        Activity activity=launch("ManagementSettingsActivity");
        Switch toggle=(Switch)findText(activity.getWindow().getDecorView(),"启用来电统计");
        getInstrumentation().runOnMainSync(()->toggle.setChecked(false));
        await(()->toggle.isEnabled());
        assertTrue(toggle.isChecked());assertTrue(store.statisticsEnabled());
    }
    private View findDescription(View view,String description){
        if(description.contentEquals(view.getContentDescription()==null?"":view.getContentDescription()))return view;
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){View found=findDescription(((ViewGroup)view).getChildAt(i),description);if(found!=null)return found;}
        return null;
    }
    private View findText(View view,String text){
        if(view instanceof TextView&&((TextView)view).getText().toString().equals(text))return view;
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){View found=findText(((ViewGroup)view).getChildAt(i),text);if(found!=null)return found;}
        return null;
    }
    interface Check{boolean run()throws Exception;}
    private void await(Check check)throws Exception{long end=System.currentTimeMillis()+4000;while(System.currentTimeMillis()<end){if(check.run())return;Thread.sleep(40);}fail("UI data did not reach the expected state");}
    private boolean containsSpinner(View view) {
        if (view instanceof Spinner) return true;
        if (view instanceof ViewGroup) for (int i=0;i<((ViewGroup)view).getChildCount();i++)
            if (containsSpinner(((ViewGroup)view).getChildAt(i))) return true;
        return false;
    }
    private boolean containsText(View view, String text) {
        if (view instanceof TextView && ((TextView)view).getText().toString().contains(text)) return true;
        if (view instanceof ViewGroup) for (int i=0;i<((ViewGroup)view).getChildCount();i++)
            if (containsText(((ViewGroup)view).getChildAt(i),text)) return true;
        return false;
    }
}
