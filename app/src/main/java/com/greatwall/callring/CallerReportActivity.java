package com.greatwall.callring;

import android.content.Intent;
import android.net.Uri;
import android.widget.*;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

public class CallerReportActivity extends ManagementActivity {
    private int period=2,reasonFilter,pageIndex;
    private String customStart="",customEnd="";
    private String number;
    private long start,end;
    private ZoneId zone;
    @Override protected void render(){
        number=NumberNormalizer.normalize(getIntent().getStringExtra("number"));zone=store.workConfig().zone;
        LocalDate today=LocalDate.now(zone);
        if(customStart.isEmpty()){customStart=today.minusDays(29).toString();customEnd=today.toString();}
        LocalDate first=period==0?today:period==1?today.minusDays(6):period==2?today.minusDays(29):LocalDate.parse(customStart);
        LocalDate last=period==3?LocalDate.parse(customEnd):today;
        start=first.atStartOfDay(zone).toInstant().toEpochMilli();end=last.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
        if(start>=end)throw new IllegalArgumentException("结束日期不能早于开始日期");
        CallerRepository.Caller caller=store.caller(number);CallerRepository.Report report=store.report(number,start,end);
        LinearLayout page=Ui.page(this,number+" · 来电报表");
        String position=store.positionSummary(caller);
        TextView profile=Ui.text(page,(caller.name.isEmpty()?"未填写姓名":caller.name)+" · "+(caller.department.isEmpty()?"未填写部门":caller.department)+"\n"+position+(caller.whitelist?" · 白名单":""),18);
        profile.setContentDescription(profile.getText()+"，"+CallerWidgetProvider.relationDescription(position));
        Ui.button(page,"编辑来电人信息",v->startActivity(new Intent(this,CallerEditActivity.class).putExtra("number",number)));
        Spinner periodChoice=Ui.choice(page,"统计区间",Arrays.asList("今天","近 7 天","近 30 天","自定义日期"),period);
        periodChoice.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onNothingSelected(AdapterView<?> p){}public void onItemSelected(AdapterView<?> p,android.view.View v,int index,long id){if(period!=index){period=index;pageIndex=0;render();}}});
        if(period==3){
            EditText from=Ui.field(page,"开始日期（YYYY-MM-DD）",customStart,false),to=Ui.field(page,"结束日期（包含该日）",customEnd,false);
            Ui.button(page,"应用日期范围",v->{try{LocalDate a=LocalDate.parse(from.getText().toString().trim()),b=LocalDate.parse(to.getText().toString().trim());if(b.isBefore(a))throw new IllegalArgumentException("结束日期不能早于开始日期");customStart=a.toString();customEnd=b.toString();pageIndex=0;render();}catch(Exception e){Ui.toast(this,"请输入有效且顺序正确的日期");}});
        }
        Ui.text(page,first+" 至 "+last+" · "+zone.getId(),14);
        Ui.section(page,"号码评分 · "+Ui.score(report.score));
        Ui.text(page,"分数越低，号码越不受欢迎。\n"+Ui.scoreReason(report.score,store.scoreWeights()),15);
        Ui.text(page,"来电 "+report.score.total+" 次 · 工作时间 "+(report.score.total-report.score.offHours)+" 次 · 非工作时间 "+report.score.offHours+" 次\n"
                +"已知通话时长 "+Ui.duration(report.knownDurationSeconds)+" · 时长未知 "+report.unknownDurationCount+" 次\n"
                +"未接 "+report.missed+" 次 · 拒接 "+report.rejected+" 次 · 待补全 "+report.pending+" 次",16);
        if(report.unknownDurationCount>0||report.records.stream().anyMatch(r->!r.counted))Ui.text(page,"基于当前已记录来电，部分结果待补全或待核对。",14);
        if(report.records.stream().anyMatch(r->!r.verified))Ui.text(page,"部分日期的节假日未核验，请补充对应年度日历。",14);
        if(!store.statisticsEnabled())Ui.text(page,"统计当前关闭，已有历史仍可查看。",14);
        boolean callLogPermitted=CallLogSynchronizer.permitted(this);
        if(!callLogPermitted)Ui.text(page,"通话记录未授权：只能登记已收到的筛选事件，通话结果和准确时长无法补全。可在管理设置中授权。",14);
        boolean callInProgress=AppSettings.phonePermission(this)&&CallStateSnapshot.active(getSystemService(android.telephony.TelephonyManager.class).getCallState(),getSystemService(android.media.AudioManager.class).getMode());
        if(callInProgress)Ui.text(page,"手机当前有通话，结果和时长需要在通话结束后补全。",14);
        Ui.button(page,"同步通话结果",v->{SyncJobService.schedule(this);Ui.toast(this,"已安排同步，需通话记录授权");});
        Ui.button(page,"导出 CSV 报表",v->startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("text/csv").putExtra(Intent.EXTRA_TITLE,"callring-"+number+"-"+today+".csv"),40));
        Spinner filter=Ui.choice(page,"明细筛选（上方汇总为全部区间）",Arrays.asList("全部来电","工作时间","请假期间","节假日","休息日","工作时段外"),reasonFilter);
        filter.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onNothingSelected(AdapterView<?> p){}public void onItemSelected(AdapterView<?> p,android.view.View v,int index,long id){if(reasonFilter!=index){reasonFilter=index;pageIndex=0;render();}}});
        List<CallerRepository.CallRecord> records=new ArrayList<>();for(CallerRepository.CallRecord r:report.records)if(include(r))records.add(r);
        int pages=Math.max(1,(records.size()+49)/50);pageIndex=Math.min(pageIndex,pages-1);
        Ui.section(page,"逐次明细 · "+records.size()+" 条");
        if(records.isEmpty())Ui.text(page,"该区间没有符合筛选条件的来电。",16);
        for(int i=pageIndex*50;i<Math.min(records.size(),(pageIndex+1)*50);i++){
            CallerRepository.CallRecord r=records.get(i);ZoneId callZone=ZoneId.of(r.zone);
            Ui.text(page,Ui.time(r.time,callZone)+"\n"+(r.working?"工作时间":"非工作时间")+" · "+r.reason+(r.verified?"":" · 节假日未核验")
                    +"\n"+(r.logId==null&&!callLogPermitted?"权限不足，无法补全":r.result)+" · 时长 "+Ui.duration(r.duration)+" · "+r.decision+(r.counted?"":" · 未计入汇总")
                    +(r.working!=r.originalWorking||!r.reason.equals(r.originalReason)?"\n原始分类："+(r.originalWorking?"工作时间":"非工作时间")+" · "+r.originalReason
                    +"\n更正依据：工作安排 #"+r.originalConfigVersion+" → #"+r.configVersion+"；日历 "+r.originalCalendarVersion+" → "+r.calendarVersion+"；请假版本 "+r.originalLeaveVersion+" → "+r.leaveVersion:""),15);
        }
        Ui.text(page,"第 "+(pageIndex+1)+" / "+pages+" 页",14);
        if(pageIndex>0)Ui.button(page,"上一页",v->{pageIndex--;render();});if(pageIndex+1<pages)Ui.button(page,"下一页",v->{pageIndex++;render();});
    }
    private boolean include(CallerRepository.CallRecord r){return reasonFilter==0||(reasonFilter==1&&r.working)||(reasonFilter==2&&r.reason.contains("请假"))||(reasonFilter==3&&r.reason.contains("节假日"))||(reasonFilter==4&&r.reason.contains("休息日"))||(reasonFilter==5&&r.reason.contains("工作时段外"));}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);
        if(request!=40||result!=RESULT_OK||data==null||data.getData()==null)return;Uri uri=data.getData();String n=number;long from=start,to=end;ZoneId exportZone=zone;
        Ui.run(this,()->{CallerRepository.Caller caller=store.caller(n);CallerRepository.Report report=store.report(n,from,to);CallerRepository.Position me=store.myPositionId()==null?null:store.position(store.myPositionId());
            StringBuilder csv=new StringBuilder("\uFEFF");csv.append(CsvReport.row("号码","姓名","部门","职位与相对职位","对比基准职位","号码评分","总次数","非工作次数","已知时长秒","未知时长次数","开始时间","结束时间（不含）"));
            csv.append(CsvReport.row("'"+n,caller.name,caller.department,store.positionSummary(caller),me==null?"未设置":me.name,report.score.points==null?"暂无评分":report.score.points.toString(),""+report.score.total,""+report.score.offHours,""+report.knownDurationSeconds,""+report.unknownDurationCount,Ui.time(from,exportZone),Ui.time(to,exportZone)));
            csv.append("\r\n").append(CsvReport.row("号码","姓名","部门","职位与相对职位","对比基准职位","日期时间","时区","工作时间","分类原因","原始分类","来电决策","通话结果","时长秒","参与汇总","日历已核验","数据来源","工作安排版本","原始工作安排版本","日历版本","原始日历版本","请假版本","原始请假版本","原始时区"));
            for(CallerRepository.CallRecord r:report.records){ZoneId callZone=ZoneId.of(r.zone);csv.append(CsvReport.row("'"+n,caller.name,caller.department,store.positionSummary(caller),me==null?"未设置":me.name,Ui.time(r.time,callZone),callZone.getId(),r.working?"是":"否",r.reason,r.originalReason,r.decision,r.result,r.duration==null?"未知":r.duration.toString(),r.counted?"是":"否",r.verified?"是":"否",r.source,""+r.configVersion,""+r.originalConfigVersion,r.calendarVersion,r.originalCalendarVersion,""+r.leaveVersion,""+r.originalLeaveVersion,r.originalZone));}
            try(OutputStream output=getContentResolver().openOutputStream(uri,"wt")){if(output==null)throw new IllegalStateException("无法保存报表");output.write(csv.toString().getBytes(StandardCharsets.UTF_8));}
        },()->Ui.toast(this,"报表已保存"));
    }
}
