package com.greatwall.callring;

import android.content.Intent;
import android.widget.*;
import java.util.*;

public class CallerListActivity extends ManagementActivity {
    private String query="";private int pageIndex;
    @Override protected void render(){
        LinearLayout page=Ui.page(this,"号码档案");
        Ui.text(page,"姓名、部门与职位集中管理；点击号码查看报表。",14);
        Ui.button(page,"新增号码",v->startActivity(new Intent(this,CallerEditActivity.class)));
        Ui.button(page,"管理设置",v->startActivity(new Intent(this,ManagementSettingsActivity.class)));
        EditText search=Ui.field(page,"搜索号码、姓名或部门",query,false);
        Ui.button(page,"搜索",v->{query=search.getText().toString().trim();pageIndex=0;render();});
        List<CallerRepository.Caller> values=new ArrayList<>();for(CallerRepository.Caller caller:store.callers())
            if(query.isEmpty()||caller.number.contains(query)||caller.name.contains(query)||caller.department.contains(query))values.add(caller);
        int pages=Math.max(1,(values.size()+49)/50);pageIndex=Math.min(pageIndex,pages-1);
        Ui.text(page,"共 "+values.size()+" 个号码 · 第 "+(pageIndex+1)+" / "+pages+" 页",14);
        if(values.isEmpty())Ui.text(page,"暂无号码。可先添加档案，或开启统计后由来电自动创建。",16);
        for(int i=pageIndex*50;i<Math.min(values.size(),(pageIndex+1)*50);i++){
            CallerRepository.Caller caller=values.get(i);
            Ui.section(page,caller.number+" · "+(caller.name.isEmpty()?"未填写姓名":caller.name));
            String position=store.positionSummary(caller);
            TextView information=Ui.text(page,(caller.department.isEmpty()?"未填写部门":caller.department)+" · "+position+(caller.whitelist?"\n白名单":""),15);
            information.setContentDescription(information.getText()+"，"+CallerWidgetProvider.relationDescription(position));
            Ui.button(page,"查看报表 · "+caller.number,v->startActivity(new Intent(this,CallerReportActivity.class).putExtra("number",caller.number)));
            Ui.button(page,"编辑信息",v->startActivity(new Intent(this,CallerEditActivity.class).putExtra("number",caller.number)));
        }
        if(pageIndex>0)Ui.button(page,"上一页",v->{pageIndex--;render();});
        if(pageIndex+1<pages)Ui.button(page,"下一页",v->{pageIndex++;render();});
    }
}
