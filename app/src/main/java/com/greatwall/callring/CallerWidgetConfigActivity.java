package com.greatwall.callring;

import android.app.Activity;
import android.appwidget.AppWidgetManager;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.AdapterView;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import java.util.ArrayList;
import java.util.List;

public class CallerWidgetConfigActivity extends Activity {
    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setResult(RESULT_CANCELED);
        int widgetId = getIntent().getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID);
        if (widgetId <= 0) { finish(); return; }
        LinearLayout page = Ui.page(this, "小组件选择号码");
        Ui.text(page, "每个桌面小组件只绑定一个号码，可选择已有档案或手动填写。", 16);
        List<CallerRepository.Caller> callers;
        try { callers = CallerRepository.get(this).callers(); }
        catch (RuntimeException error) { callers = new ArrayList<>(); Ui.text(page, "已有档案暂不可读取，仍可手动填写号码。", 15); }
        final List<CallerRepository.Caller> options = callers;
        List<String> labels = new ArrayList<>(); labels.add("手动输入号码");
        for (CallerRepository.Caller caller : callers)
            labels.add(caller.number + " · " + CallerWidgetProvider.empty(caller.name, "未填写姓名"));
        Spinner selection = Ui.choice(page, "已有号码档案", labels, 0);
        EditText number = Ui.field(page, "绑定号码", CallerWidgetProvider.binding(this, widgetId), false);
        number.setInputType(InputType.TYPE_CLASS_PHONE);
        if (savedInstanceState != null) number.setText(savedInstanceState.getString("number", number.getText().toString()));
        selection.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onNothingSelected(AdapterView<?> parent) { }
            @Override public void onItemSelected(AdapterView<?> parent, View view, int index, long id) {
                if (index > 0) number.setText(options.get(index - 1).number);
            }
        });
        number.setId(R.id.widget_number);
        Ui.button(page, "保存小组件", view -> {
            try {
                CallerWidgetProvider.bind(this, widgetId, number.getText().toString());
                CallerWidgetProvider.update(this, widgetId);
                setResult(RESULT_OK, new Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId));
                finish();
            } catch (RuntimeException error) { number.setError("请选择或填写有效号码，并重试保存"); number.requestFocus(); }
        });
        Ui.button(page, "取消", view -> finish());
    }
}
