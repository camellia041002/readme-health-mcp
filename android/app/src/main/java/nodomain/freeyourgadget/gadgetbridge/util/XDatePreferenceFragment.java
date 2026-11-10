/* Copyright (C) 2024 José Rebelo; AGPL-3.0-or-later */
package nodomain.freeyourgadget.gadgetbridge.util;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.gridlayout.widget.GridLayout;
import androidx.preference.DialogPreference;
import androidx.preference.Preference;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import nodomain.freeyourgadget.gadgetbridge.util.dialogs.MaterialPreferenceDialogFragment;

/** Compact calendar with explicit year/month controls and no haptic wheel picker. */
public class XDatePreferenceFragment extends MaterialPreferenceDialogFragment implements DialogPreference.TargetFragment {
    private Spinner yearSpinner, monthSpinner;
    private GridLayout calendarGrid;
    private int firstYear, selectedDay;

    @Override protected View onCreateDialogView(final Context context) {
        final float d = context.getResources().getDisplayMetrics().density;
        final LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding((int)(18*d), (int)(8*d), (int)(18*d), (int)(12*d));
        final LinearLayout selectors = new LinearLayout(context);
        yearSpinner = new Spinner(context);
        monthSpinner = new Spinner(context);
        yearSpinner.setPopupBackgroundDrawable(new ColorDrawable(0xffffffff));
        monthSpinner.setPopupBackgroundDrawable(new ColorDrawable(0xffffffff));
        selectors.addView(yearSpinner, new LinearLayout.LayoutParams(0, (int)(54*d), 1.35f));
        selectors.addView(monthSpinner, new LinearLayout.LayoutParams(0, (int)(54*d), 1f));
        root.addView(selectors);
        calendarGrid = new GridLayout(context);
        calendarGrid.setColumnCount(7);
        root.addView(calendarGrid, new LinearLayout.LayoutParams(-1, (int)(330*d)));
        return root;
    }

    @Override protected void onBindDialogView(final View view) {
        super.onBindDialogView(view);
        final XDatePreference pref = (XDatePreference)getPreference();
        firstYear = Calendar.getInstance().get(Calendar.YEAR);
        selectedDay = pref.getDay();
        final List<String> years = new ArrayList<>(), months = new ArrayList<>();
        for (int y=firstYear; y>=1900; y--) years.add(y+"年");
        for (int m=1; m<=12; m++) months.add(m+"月");
        yearSpinner.setAdapter(adapter(years));
        monthSpinner.setAdapter(adapter(months));
        yearSpinner.setSelection(Math.max(0, Math.min(years.size()-1, firstYear-pref.getYear())));
        monthSpinner.setSelection(Math.max(0, Math.min(11, pref.getMonth()-1)));
        final AdapterView.OnItemSelectedListener listener = new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) { drawCalendar(); }
            @Override public void onNothingSelected(AdapterView<?> p) { }
        };
        yearSpinner.setOnItemSelectedListener(listener);
        monthSpinner.setOnItemSelectedListener(listener);
        drawCalendar();
    }

    private ArrayAdapter<String> adapter(List<String> values) {
        final ArrayAdapter<String> a = new ArrayAdapter<>(requireContext(), android.R.layout.simple_spinner_item, values);
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        return a;
    }

    private void drawCalendar() {
        if (yearSpinner.getAdapter()==null || monthSpinner.getAdapter()==null) return;
        calendarGrid.removeAllViews();
        for (String w : new String[]{"一","二","三","四","五","六","日"}) addCell(w,false,false,0);
        final Calendar c = Calendar.getInstance();
        c.set(firstYear-yearSpinner.getSelectedItemPosition(), monthSpinner.getSelectedItemPosition(), 1);
        final int offset=(c.get(Calendar.DAY_OF_WEEK)+5)%7;
        for(int i=0;i<offset;i++) addCell("",false,false,0);
        final int max=c.getActualMaximum(Calendar.DAY_OF_MONTH);
        selectedDay=Math.min(selectedDay,max);
        for(int day=1;day<=max;day++) addCell(String.valueOf(day),true,day==selectedDay,day);
    }

    private void addCell(String text, boolean enabled, boolean selected, int day) {
        final float d=getResources().getDisplayMetrics().density;
        final TextView cell=new TextView(requireContext());
        cell.setText(text); cell.setGravity(Gravity.CENTER); cell.setTextSize(enabled?16:14);
        cell.setTextColor(selected?0xffffffff:(enabled?0xff2b2a27:0xff8a867c));
        if(selected){ final GradientDrawable bg=new GradientDrawable(); bg.setShape(GradientDrawable.OVAL); bg.setColor(0xffc96442); cell.setBackground(bg); cell.setTypeface(Typeface.DEFAULT,Typeface.BOLD); }
        if(enabled) cell.setOnClickListener(v->{selectedDay=day;drawCalendar();});
        final GridLayout.LayoutParams p=new GridLayout.LayoutParams();
        p.width=0; p.height=(int)(45*d); p.columnSpec=GridLayout.spec(GridLayout.UNDEFINED,1,1f); p.setMargins((int)(3*d),(int)(2*d),(int)(3*d),(int)(2*d));
        calendarGrid.addView(cell,p);
    }

    @Override public void onStart(){ super.onStart(); if(getDialog()!=null&&getDialog().getWindow()!=null){ final GradientDrawable bg=new GradientDrawable(); bg.setColor(0xffffffff); bg.setCornerRadius(26*getResources().getDisplayMetrics().density); getDialog().getWindow().setBackgroundDrawable(bg); } }
    @Override public void onDialogClosed(boolean positive){ if(!positive)return; final XDatePreference pref=(XDatePreference)getPreference(); pref.setValue(firstYear-yearSpinner.getSelectedItemPosition(),monthSpinner.getSelectedItemPosition()+1,selectedDay); final String date=pref.getPrefValue(); if(pref.callChangeListener(date)){pref.persistStringValue(date);pref.updateSummary();} }
    @Override public Preference findPreference(@NonNull CharSequence key){return getPreference();}
}
