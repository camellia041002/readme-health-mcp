package nodomain.freeyourgadget.gadgetbridge.util;

import android.content.Context;
import android.graphics.Color;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.Spinner;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/** Compact, non-vibrating date controls using the ReadMe palette. */
public final class ReadMeDateDialogs {
    public interface YearMonthListener { void onSelected(int year, int zeroBasedMonth); }

    private ReadMeDateDialogs() { }

    public static void showYearMonth(Context context, Calendar selected, YearMonthListener listener) {
        final int density = (int) context.getResources().getDisplayMetrics().density;
        final LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(20 * density, 8 * density, 20 * density, 8 * density);

        final Spinner year = new Spinner(context);
        final Spinner month = new Spinner(context);
        final List<String> years = new ArrayList<>();
        final int currentYear = Calendar.getInstance().get(Calendar.YEAR);
        for (int value = currentYear; value >= 1900; value--) years.add(value + "年");
        final List<String> months = new ArrayList<>();
        for (int value = 1; value <= 12; value++) months.add(value + "月");
        year.setAdapter(new ArrayAdapter<>(context, android.R.layout.simple_spinner_dropdown_item, years));
        month.setAdapter(new ArrayAdapter<>(context, android.R.layout.simple_spinner_dropdown_item, months));
        year.setSelection(currentYear - selected.get(Calendar.YEAR));
        month.setSelection(selected.get(Calendar.MONTH));
        row.addView(year, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        row.addView(month, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        new MaterialAlertDialogBuilder(context)
                .setTitle("选择年月")
                .setView(row)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (dialog, which) ->
                        listener.onSelected(currentYear - year.getSelectedItemPosition(), month.getSelectedItemPosition()))
                .show();
    }
}
