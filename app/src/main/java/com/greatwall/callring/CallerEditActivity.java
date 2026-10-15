package com.greatwall.callring;

import android.content.Intent;
import android.widget.*;
import java.util.List;
import android.view.View;

public class CallerEditActivity extends ManagementActivity {
    private Spinner position;
    private List<CallerRepository.Position> positions;
    private TextView relation;
    @Override protected boolean refreshOnChange(){return false;}
    @Override protected void render(){
        String raw=getIntent().getStringExtra("number");CallerRepository.Caller caller;
        if(raw==null||raw.isEmpty()){caller=new CallerRepository.Caller();caller.number="";caller.name="";caller.department="";}else caller=store.caller(raw);
        LinearLayout page=Ui.page(this,"来电人信息");
        EditText number=Ui.field(page,"号码",caller.number,false);number.setInputType(android.text.InputType.TYPE_CLASS_PHONE);
        if(!caller.number.isEmpty())number.setEnabled(false);
        EditText name=Ui.field(page,"姓名",caller.name,false),department=Ui.field(page,"部门",caller.department,false);
        positions=Ui.positionOptions(store,caller.positionId,false);
        position=Ui.choice(page,"职位",Ui.positionLabels(positions),Ui.positionIndex(positions,caller.positionId));
        relation=Ui.text(page,"",14);showRelation();
        position.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onNothingSelected(AdapterView<?> p){}public void onItemSelected(AdapterView<?> p,View v,int index,long id){showRelation();}});
        if(positions.size()==1)Ui.text(page,"还未配置职位，可在管理设置中添加选项。",14);
        Switch whitelist=Ui.toggle(page,"加入白名单，绕过应用免打扰",caller.whitelist);
        Ui.button(page,"保存信息",v->{String n=number.getText().toString(),d=department.getText().toString(),person=name.getText().toString();Long p=Ui.selectedPosition(position,positions);boolean white=whitelist.isChecked();
            Ui.run(this,()->store.saveCaller(n,d,person,p,white),()->{Ui.toast(this,"信息已保存");finish();});});
        Ui.button(page,"配置职位与我的当前职位",v->startActivity(new Intent(this,ManagementSettingsActivity.class)));
        Ui.button(page,"查看号码报表",v->{String n=NumberNormalizer.normalize(number.getText().toString());if(!n.matches("\\+?[0-9]{1,30}")){Ui.toast(this,"请先填写有效号码");return;}startActivity(new Intent(this,CallerReportActivity.class).putExtra("number",n));});
    }
    private void showRelation(){if(position==null||relation==null)return;CallerRepository.Caller draft=new CallerRepository.Caller();draft.positionId=Ui.selectedPosition(position,positions);relation.setText("↑ 高于我的职位 · ↓ 低于 · ＝ 同级 · — 未设置\n"+store.positionSummary(draft));}
    @Override protected void onResume(){super.onResume();if(position==null)return;Long selected=Ui.selectedPosition(position,positions);positions=Ui.positionOptions(store,selected,false);
        position.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,Ui.positionLabels(positions)));position.setSelection(Ui.positionIndex(positions,selected));showRelation();}
}
