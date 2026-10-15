package com.greatwall.callring;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.View;
import android.widget.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

final class Ui {
    private static final ExecutorService worker = Executors.newSingleThreadExecutor();
    private static final Set<Activity> busy = Collections.newSetFromMap(new WeakHashMap<>());
    interface Work { void run() throws Exception; }
    static int dp(Activity a,int value){return Math.round(value*a.getResources().getDisplayMetrics().density);}
    static LinearLayout page(Activity a,String title){
        a.getWindow().setStatusBarColor(Color.rgb(18,107,96));
        a.getWindow().setNavigationBarColor(Color.rgb(245,248,246));
        ScrollView scroll=new ScrollView(a);scroll.setFillViewport(true);
        LinearLayout page=new LinearLayout(a);page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(a,20),dp(a,20),dp(a,20),dp(a,28));page.setBackgroundColor(Color.rgb(245,248,246));
        scroll.addView(page);a.setContentView(scroll);TextView heading=text(page,title,26);heading.setTypeface(null,Typeface.BOLD);return page;
    }
    static TextView text(LinearLayout parent,String text,int size){
        TextView view=new TextView(parent.getContext());view.setText(text);view.setTextSize(size);view.setTextColor(Color.rgb(30,47,43));
        int pad=Math.round(8*parent.getResources().getDisplayMetrics().density);view.setPadding(0,pad,0,pad);parent.addView(view);return view;
    }
    static void section(LinearLayout parent,String title){TextView view=text(parent,title,20);view.setTypeface(null,Typeface.BOLD);}
    static Button button(LinearLayout parent,String title,View.OnClickListener listener){
        Button button=new Button(parent.getContext());button.setText(title);button.setAllCaps(false);button.setOnClickListener(listener);
        parent.addView(button,new LinearLayout.LayoutParams(-1,-2));return button;
    }
    static EditText field(LinearLayout parent,String label,String value,boolean multiline){
        text(parent,label,14);EditText edit=new EditText(parent.getContext());edit.setContentDescription(label);edit.setText(value);edit.setTextSize(16);
        edit.setSingleLine(!multiline);if(multiline)edit.setMinLines(2);parent.addView(edit,new LinearLayout.LayoutParams(-1,-2));return edit;
    }
    static Spinner choice(LinearLayout parent,String label,List<String> values,int selected){
        text(parent,label,14);Spinner spinner=new Spinner(parent.getContext());spinner.setContentDescription(label);
        spinner.setAdapter(new ArrayAdapter<>(parent.getContext(),android.R.layout.simple_spinner_dropdown_item,values));
        parent.addView(spinner,new LinearLayout.LayoutParams(-1,-2));if(selected>=0&&selected<values.size())spinner.setSelection(selected);return spinner;
    }
    static Switch toggle(LinearLayout parent,String title,boolean value){
        Switch toggle=new Switch(parent.getContext());toggle.setText(title);toggle.setTextSize(17);toggle.setChecked(value);
        int pad=Math.round(12*parent.getResources().getDisplayMetrics().density);toggle.setPadding(0,pad,0,pad);parent.addView(toggle);return toggle;
    }
    static List<CallerRepository.Position> positionOptions(CallerRepository store,Long current,boolean includeInactive){
        List<CallerRepository.Position> values=new ArrayList<>();values.add(null);
        for(CallerRepository.Position position:store.positions(true))if(includeInactive||position.active||Objects.equals(current,position.id))values.add(position);
        return values;
    }
    static List<String> positionLabels(List<CallerRepository.Position> positions){
        List<String> labels=new ArrayList<>();for(CallerRepository.Position p:positions)labels.add(p==null?"未设置职位":p.name+" · 层级 "+p.level+(p.active?"":"（已停用）"));return labels;
    }
    static int positionIndex(List<CallerRepository.Position> positions,Long id){
        for(int i=1;i<positions.size();i++)if(Objects.equals(id,positions.get(i).id))return i;return 0;
    }
    static Long selectedPosition(Spinner spinner,List<CallerRepository.Position> positions){CallerRepository.Position p=positions.get(spinner.getSelectedItemPosition());return p==null?null:p.id;}
    static void toast(Activity a,String text){Toast.makeText(a,text,Toast.LENGTH_LONG).show();}
    static void run(Activity a,Work work,Runnable done){
        run(a,work,done,null);
    }
    static void run(Activity a,Work work,Runnable done,Runnable settled){
        if(!busy.add(a)){toast(a,"正在处理，请稍候");if(settled!=null)settled.run();return;}
        worker.execute(()->{String error=null;try{work.run();}catch(Exception e){error=e.getMessage();if(error==null)error="保存失败，请重试";}
            String message=error;a.runOnUiThread(()->{busy.remove(a);if(a.isDestroyed())return;try{if(message!=null){toast(a,message);return;}ManagementEvents.changed(a);if(done!=null)done.run();}finally{if(settled!=null)settled.run();}});});
    }
    static String time(long at,ZoneId zone){return Instant.ofEpochMilli(at).atZone(zone).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));}
    static long parseTime(String date,String time,ZoneId zone){return LocalDateTime.parse(date.trim()+" "+time.trim(),DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm").withResolverStyle(ResolverStyle.STRICT)).atZone(zone).toInstant().toEpochMilli();}
    static String duration(Long seconds){return seconds==null?"未知":String.format(Locale.ROOT,"%d:%02d:%02d",seconds/3600,(seconds/60)%60,seconds%60);}
    static String score(CallerScore.Result score){return score.points==null?"暂无评分":score.points+" 分";}
    static String scoreReason(CallerScore.Result score,int[] weights){return score.points==null?"该区间没有来电记录":"100 − 非工作 "+score.offHours+" 次 × "+weights[0]+" − 重复 "+score.repeats+" 次 × "+weights[1]+" = "+score.points+" 分（最低 0 分）";}
}
