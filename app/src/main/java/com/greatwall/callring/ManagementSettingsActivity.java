package com.greatwall.callring;

import android.Manifest;
import android.app.AlertDialog;
import android.app.role.RoleManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import android.widget.*;
import java.io.InputStream;
import java.time.*;
import java.util.*;

public class ManagementSettingsActivity extends ManagementActivity {
    private boolean returningFromPermissions;
    private TextView permissionStatus;
    @Override protected boolean refreshOnChange(){return false;}
    @Override protected void render(){
        LinearLayout page=Ui.page(this,"来电管理设置");
        Ui.text(page,"工作安排、号码评分和免打扰共同使用这份设置。",14);
        Switch statistics=Ui.toggle(page,"启用来电统计",store.statisticsEnabled());
        boolean[] updatingStatistics={false};
        statistics.setOnCheckedChangeListener((v,on)->{if(updatingStatistics[0])return;statistics.setEnabled(false);
            Ui.run(this,()->store.setStatisticsEnabled(on),()->{if(on)SyncJobService.schedule(this);Ui.toast(this,on?"统计已开启，从现在开始记录":"统计已暂停，历史记录保留");},()->{
                updatingStatistics[0]=true;statistics.setChecked(store.statisticsEnabled());statistics.setEnabled(true);updatingStatistics[0]=false;
            });});
        Switch overlay=Ui.toggle(page,"来电悬浮窗",store.overlayEnabled());
        boolean[] updatingOverlay={false};
        overlay.setOnCheckedChangeListener((v,on)->{if(updatingOverlay[0])return;if(on&&!Settings.canDrawOverlays(this)){updatingOverlay[0]=true;overlay.setChecked(false);updatingOverlay[0]=false;returningFromPermissions=true;startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:"+getPackageName())));return;}
            overlay.setEnabled(false);Ui.run(this,()->store.setOverlayEnabled(on),()->{if(!on)CallerOverlayService.closeActive();},()->{updatingOverlay[0]=true;overlay.setChecked(store.overlayEnabled());overlay.setEnabled(true);updatingOverlay[0]=false;});});
        quiet(page);positions(page);working(page);calendar(page);leave(page);score(page);permissions(page);
    }
    private void quiet(LinearLayout page){
        Ui.section(page,"免打扰");
        Spinner mode=Ui.choice(page,"静音模式",Arrays.asList("关闭","全部非工作时间静音","自定义非工作时间静音"),store.quietMode().ordinal());
        Switch all=Ui.toggle(page,"作用于所有可筛选来电（关闭时仅处理规则号码）",store.allNumbers());
        EditText windows=Ui.field(page,"自定义静音时段（每行：星期 时间段）",WorkConfigCodec.formatWeekly(store.quietWindows()),true);
        Ui.text(page,"1=周一，7=周日。例如：1 22:00-08:00。每段归属开始日，支持用逗号分隔多段。白名单始终绕过应用免打扰，仍遵守手机自身静音。",13);
        Ui.button(page,"保存免打扰设置",v->{try{Map<Integer,List<WorkSchedulePolicy.Window>> parsed=WorkConfigCodec.parseWeekly(windows.getText().toString());QuietHoursPolicy.Mode selected=QuietHoursPolicy.Mode.values()[mode.getSelectedItemPosition()];boolean scope=all.isChecked();
            if(selected==QuietHoursPolicy.Mode.CUSTOM&&parsed.values().stream().allMatch(List::isEmpty))throw new IllegalArgumentException("自定义模式至少填写一个静音时段");
            Ui.run(this,()->store.saveQuietSettings(selected,scope,parsed),()->Ui.toast(this,"免打扰设置已保存"));
        }catch(Exception e){Ui.toast(this,"请检查静音时段："+e.getMessage());}});
    }
    private void positions(LinearLayout page){
        Ui.section(page,"职位选项与我的当前职位");
        Ui.text(page,"层级数字越小，职位越高；不同职位可以同级。来电人职位从这些选项中选择。",14);
        List<CallerRepository.Position> options=Ui.positionOptions(store,store.myPositionId(),false);
        Spinner mine=Ui.choice(page,"我的当前职位",Ui.positionLabels(options),Ui.positionIndex(options,store.myPositionId()));
        Ui.button(page,"保存我的职位",v->{Long id=Ui.selectedPosition(mine,options);Ui.run(this,()->store.setMyPosition(id),()->Ui.toast(this,"我的当前职位已更新"));});
        Ui.button(page,"新增职位选项",v->positionDialog(null));
        for(CallerRepository.Position position:store.positions(true)){
            Ui.text(page,position.name+" · 层级 "+position.level+(position.active?"":" · 已停用"),16);
            Ui.button(page,"修改 / 停用 · "+position.name,v->positionDialog(position));
            Ui.button(page,"删除 · "+position.name,v->new AlertDialog.Builder(this).setTitle("删除职位？").setMessage("正在使用的职位无法删除，可改为停用。").setNegativeButton("取消",null)
                    .setPositiveButton("删除",(d,w)->Ui.run(this,()->store.deletePosition(position.id),this::render)).show());
        }
    }
    private void positionDialog(CallerRepository.Position existing){
        LinearLayout form=new LinearLayout(this);form.setOrientation(LinearLayout.VERTICAL);form.setPadding(Ui.dp(this,20),0,Ui.dp(this,20),0);
        EditText name=Ui.field(form,"职位名称",existing==null?"":existing.name,false),level=Ui.field(form,"层级（正整数）",existing==null?"1":""+existing.level,false);level.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        Switch active=Ui.toggle(form,"允许选择这个职位",existing==null||existing.active);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(existing==null?"新增职位":"修改职位").setView(form).setNegativeButton("取消",null).setPositiveButton("保存",null).create();
        dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{try{String value=name.getText().toString().trim();int rank=Integer.parseInt(level.getText().toString());boolean enabled=active.isChecked();if(value.isEmpty()||rank<=0)throw new IllegalArgumentException("填写名称和正整数层级");
            Ui.run(this,()->store.savePosition(existing==null?0:existing.id,value,rank,enabled),()->{dialog.dismiss();render();});}catch(Exception e){Ui.toast(this,"请输入有效的职位名称与层级");}}));dialog.show();
    }
    private void working(LinearLayout page){
        WorkSchedulePolicy.Config config=store.workConfig();Ui.section(page,"工作时间");
        EditText weekly=Ui.field(page,"每周工作时段（1=周一，7=周日）",WorkConfigCodec.formatWeekly(config.weekly),true);
        EditText makeup=Ui.field(page,"调休补班日工作时段",WorkConfigCodec.formatWindows(config.makeup),false);
        EditText zone=Ui.field(page,"日历时区",config.zone.getId(),false);
        Ui.text(page,"每行示例：1 09:00-12:00,13:00-18:00。未列出的星期休息，跨午夜写为 22:00-06:00。常规修改只影响之后来电。",13);
        Ui.button(page,"保存工作安排",v->{try{WorkSchedulePolicy.Config updated=new WorkSchedulePolicy.Config();updated.zone=ZoneId.of(zone.getText().toString().trim());updated.weekly=WorkConfigCodec.parseWeekly(weekly.getText().toString());updated.makeup=WorkConfigCodec.parseWindows(makeup.getText().toString());
            Ui.run(this,()->store.saveWorkConfig(updated),()->Ui.toast(this,"工作安排已保存，对之后来电生效"));}catch(Exception e){Ui.toast(this,"请检查工作安排："+e.getMessage());}});
        Ui.button(page,"恢复默认工作安排",v->{WorkSchedulePolicy.Config defaults=new WorkSchedulePolicy.Config();Ui.run(this,()->store.saveWorkConfig(defaults),this::render);});
    }
    private void calendar(LinearLayout page){
        Ui.section(page,"法定节假日与调休");Ui.text(page,store.calendarSummary(),14);
        int current=LocalDate.now(store.workConfig().zone).getYear();if(!store.workConfig().coveredYears.contains(current))Ui.text(page,current+" 年节假日数据未配置，当前按每周安排和请假临时判断。",14);
        Ui.button(page,"导入年度日历文件",v->startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"),50));
        Ui.text(page,"导入包含 year、source、published、version、holidays 和 workdays 的年度 JSON 文件；来源与覆盖年份会显示在这里。",13);
        Ui.section(page,"单日工作安排调整");
        EditText date=Ui.field(page,"调整日期（YYYY-MM-DD）",LocalDate.now(store.workConfig().zone).toString(),false);
        Spinner kind=Ui.choice(page,"该日安排",Arrays.asList("休息","上班，使用下方时段"),0);
        EditText windows=Ui.field(page,"上班时段",WorkConfigCodec.formatWindows(store.workConfig().makeup),false);
        Ui.button(page,"保存日期调整",v->{try{LocalDate day=LocalDate.parse(date.getText().toString().trim());List<WorkSchedulePolicy.Window> ranges=kind.getSelectedItemPosition()==0?new ArrayList<>():WorkConfigCodec.parseWindows(windows.getText().toString());if(kind.getSelectedItemPosition()==1&&ranges.isEmpty())throw new IllegalArgumentException("上班日期需要工作时段");Ui.run(this,()->store.saveOverride(day,ranges),this::render);}catch(Exception e){Ui.toast(this,"请检查日期或时间段："+e.getMessage());}});
        Ui.button(page,"撤销该日期调整，恢复日历规则",v->{try{LocalDate day=LocalDate.parse(date.getText().toString().trim());Ui.run(this,()->store.deleteOverride(day),this::render);}catch(Exception e){Ui.toast(this,"请输入有效日期");}});
        for(Map.Entry<LocalDate,List<WorkSchedulePolicy.Window>> entry:new TreeMap<>(store.workConfig().overrides).entrySet())Ui.text(page,entry.getKey()+" · "+(entry.getValue().isEmpty()?"休息":WorkConfigCodec.formatWindows(entry.getValue())),14);
    }
    private void leave(LinearLayout page){
        Ui.section(page,"个人请假");Ui.text(page,"请假优先于调休和上班安排。补录、修改或取消会更正对应日期的统计与评分。",14);
        Ui.button(page,"新增全天 / 半天 / 时段请假",v->leaveDialog(null));
        ZoneId zone=store.workConfig().zone;long now=System.currentTimeMillis();
        for(WorkSchedulePolicy.Leave leave:store.leaves()){
            String state=now<leave.startMillis?"即将开始":now>=leave.endMillis?"已结束":"请假中";
            Ui.text(page,Ui.time(leave.startMillis,zone)+" 至 "+Ui.time(leave.endMillis,zone)+"（结束时刻不含）\n"+state+(leave.note.isEmpty()?"":" · "+leave.note),14);
            Ui.button(page,"修改这条请假",v->leaveDialog(leave));
            Ui.button(page,"取消这条请假",v->new AlertDialog.Builder(this).setTitle("取消请假？").setMessage("对应日期恢复工作安排，并重新计算评分。").setNegativeButton("保留",null).setPositiveButton("取消请假",(d,w)->Ui.run(this,()->store.deleteLeave(leave.id),this::render)).show());
        }
    }
    private void leaveDialog(WorkSchedulePolicy.Leave existing){
        ZoneId zone=store.workConfig().zone;LocalDate day=LocalDate.now(zone);
        ZonedDateTime first=existing==null?day.atTime(9,0).atZone(zone):Instant.ofEpochMilli(existing.startMillis).atZone(zone);
        ZonedDateTime last=existing==null?day.atTime(12,0).atZone(zone):Instant.ofEpochMilli(existing.endMillis).atZone(zone);
        ScrollView scroll=new ScrollView(this);LinearLayout form=new LinearLayout(this);form.setOrientation(LinearLayout.VERTICAL);form.setPadding(Ui.dp(this,20),0,Ui.dp(this,20),0);scroll.addView(form);
        Switch allDay=Ui.toggle(form,"全天请假（结束日期也包含）",false);
        EditText startDate=Ui.field(form,"开始日期",first.toLocalDate().toString(),false),startTime=Ui.field(form,"开始时间（半天/时段）",first.toLocalTime().toString(),false);
        EditText endDate=Ui.field(form,"结束日期",last.toLocalDate().toString(),false),endTime=Ui.field(form,"结束时间（半天/时段）",last.toLocalTime().toString(),false);
        EditText note=Ui.field(form,"备注（可选）",existing==null?"":existing.note,false);
        Ui.text(form,"半天可填写实际时间段；时间格式为 HH:mm。全天模式忽略时间输入。",13);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(existing==null?"新增请假":"修改请假").setView(scroll).setNegativeButton("取消",null).setPositiveButton("保存",null).create();
        dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{try{long start,end;if(allDay.isChecked()){start=LocalDate.parse(startDate.getText().toString().trim()).atStartOfDay(zone).toInstant().toEpochMilli();end=LocalDate.parse(endDate.getText().toString().trim()).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();}else{start=Ui.parseTime(startDate.getText().toString(),startTime.getText().toString(),zone);end=Ui.parseTime(endDate.getText().toString(),endTime.getText().toString(),zone);}if(start>=end)throw new IllegalArgumentException("请假结束必须晚于开始");String memo=note.getText().toString();
            Ui.run(this,()->store.saveLeave(existing==null?0:existing.id,start,end,memo),()->{dialog.dismiss();render();});}catch(Exception e){Ui.toast(this,"请检查请假日期及时间："+e.getMessage());}}));dialog.show();
    }
    private void score(LinearLayout page){
        Ui.section(page,"号码评分");Ui.text(page,"打扰越严重分数越低，号码越不受欢迎。满分 100，最低 0。",14);int[] w=store.scoreWeights();
        EditText off=Ui.field(page,"每次非工作时间来电扣分", ""+w[0],false),repeat=Ui.field(page,"每次短时间重复来电扣分",""+w[1],false),minutes=Ui.field(page,"重复来电间隔（分钟）",""+w[2],false),days=Ui.field(page,"悬浮窗和组件的评分观察天数",""+w[3],false);
        for(EditText e:Arrays.asList(off,repeat,minutes,days))e.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        Ui.button(page,"保存评分规则",v->{try{int a=scoreValue(off,false),b=scoreValue(repeat,false),c=scoreValue(minutes,true),d=scoreValue(days,true);Ui.run(this,()->store.saveScoreWeights(a,b,c,d),()->Ui.toast(this,"评分规则已保存"));}catch(IllegalArgumentException e){Ui.toast(this,e.getMessage());}});
        Ui.button(page,"恢复默认评分规则",v->Ui.run(this,()->store.saveScoreWeights(5,2,10,30),this::render));
    }
    private void permissions(LinearLayout page){
        Ui.section(page,"授权与息屏后台运行");
        RoleManager role=getSystemService(RoleManager.class);boolean held=role!=null&&role.isRoleHeld(RoleManager.ROLE_CALL_SCREENING);
        permissionStatus=Ui.text(page,"",14);updatePermissionStatus();
        Ui.button(page,"授权电话状态与通知",v->requestPermissions(Build.VERSION.SDK_INT>=33?new String[]{Manifest.permission.READ_PHONE_STATE,Manifest.permission.POST_NOTIFICATIONS}:new String[]{Manifest.permission.READ_PHONE_STATE},60));
        Ui.button(page,"设为来电识别应用",v->{if(role==null||!role.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING)){Ui.toast(this,"系统未提供来电识别角色");return;}startActivityForResult(role.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING),61);});
        Ui.button(page,"授权通话记录，补全准确时长",v->requestPermissions(new String[]{Manifest.permission.READ_CALL_LOG},62));
        Ui.button(page,"授权通讯录来电识别（可选）",v->requestPermissions(new String[]{Manifest.permission.READ_CONTACTS},63));
        Ui.button(page,"显示在其他应用上层",v->{returningFromPermissions=true;startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:"+getPackageName())));});
        Ui.button(page,"电池优化设置",v->{returningFromPermissions=true;try{startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));}catch(RuntimeException e){openAppSettings();}});
        Ui.button(page,"后台运行与自启动设置",v->openAppSettings());
        Ui.text(page,"在手机应用设置中允许必要的后台运行和自启动。HyperOS 自启动状态需在系统设置中确认；强行停止后需重新打开应用。息屏来电功能需保持来电识别角色和相应权限。",13);
    }
    private boolean permission(String p){return checkSelfPermission(p)==PackageManager.PERMISSION_GRANTED;}
    private int scoreValue(EditText field,boolean positive){
        field.setError(null);try{int value=Integer.parseInt(field.getText().toString().trim());if(value<(positive?1:0))throw new NumberFormatException();return value;}
        catch(NumberFormatException e){String message=positive?"请输入正整数":"请输入非负整数";field.setError(message);field.requestFocus();throw new IllegalArgumentException(message);}
    }
    private void updatePermissionStatus(){if(permissionStatus==null)return;RoleManager role=getSystemService(RoleManager.class);boolean held=role!=null&&role.isRoleHeld(RoleManager.ROLE_CALL_SCREENING);
        permissionStatus.setText("来电识别："+(held?"已设置":"未设置")+"\n电话状态："+(AppSettings.phonePermission(this)?"已授权":"未授权")
                +"\n通话记录："+(permission(Manifest.permission.READ_CALL_LOG)?"已授权":"未授权，时长可能未知")
                +"\n通讯录来电识别："+(permission(Manifest.permission.READ_CONTACTS)?"已授权":"未授权，已存联系人可能不交给本应用")
                +"\n悬浮窗权限："+(Settings.canDrawOverlays(this)?"已允许":"未允许")+"\n电池优化："+(getSystemService(PowerManager.class).isIgnoringBatteryOptimizations(getPackageName())?"已豁免":"系统管理中"));
    }
    private void openAppSettings(){returningFromPermissions=true;startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+getPackageName())));}
    @Override protected void onResume(){super.onResume();if(returningFromPermissions){returningFromPermissions=false;updatePermissionStatus();}}
    @Override public void onRequestPermissionsResult(int code,String[] permissions,int[] granted){super.onRequestPermissionsResult(code,permissions,granted);updatePermissionStatus();SyncJobService.schedule(this);}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request==61){updatePermissionStatus();return;}
        if(request!=50||result!=RESULT_OK||data==null||data.getData()==null)return;Uri uri=data.getData();Ui.run(this,()->{try(InputStream input=getContentResolver().openInputStream(uri)){store.importCalendar(CallerRepository.readLimited(input));}},()->{Ui.toast(this,"年度日历已导入");render();});
    }
}
