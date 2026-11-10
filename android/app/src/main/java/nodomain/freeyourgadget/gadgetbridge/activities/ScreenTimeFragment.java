package nodomain.freeyourgadget.gadgetbridge.activities;

import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.util.ScreenTimeSummary;

/** A first-class, local-only screen-time surface, separate from health metrics. */
public class ScreenTimeFragment extends Fragment {
    private LinearLayout appList;
    private TextView total;
    private TextView permission;

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_screen_time, container, false);
    }

    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        appList = view.findViewById(R.id.screen_time_app_list);
        total = view.findViewById(R.id.screen_time_detail_total);
        permission = view.findViewById(R.id.screen_time_permission_action);
        permission.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)));
    }

    @Override public void onResume() {
        super.onResume();
        render();
    }

    private void render() {
        if (!ScreenTimeSummary.hasPermission(requireContext())) {
            total.setText(R.string.readme_permission_needed);
            permission.setVisibility(View.VISIBLE);
            appList.removeAllViews();
            return;
        }
        permission.setVisibility(View.GONE);
        ScreenTimeSummary.Result result = ScreenTimeSummary.today(requireContext());
        total.setText(formatDuration(result.totalMillis()));
        appList.removeAllViews();
        for (ScreenTimeSummary.AppUsage app : result.apps()) addRow(app);
    }

    private void addRow(ScreenTimeSummary.AppUsage app) {
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(14), dp(16), dp(14));
        TextView name = new TextView(requireContext());
        name.setText(app.label());
        name.setTextColor(ContextCompat.getColor(requireContext(), R.color.heartbeat_charcoal));
        name.setTextSize(15);
        row.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView duration = new TextView(requireContext());
        duration.setText(formatDuration(app.millis()));
        duration.setTextColor(ContextCompat.getColor(requireContext(), R.color.heartbeat_dim));
        duration.setTextSize(14);
        row.addView(duration);
        appList.addView(row);
    }

    private String formatDuration(long millis) {
        long minutes = Math.max(0, millis / 60_000L);
        return minutes >= 60
                ? getString(R.string.screen_time_hours_minutes, minutes / 60, minutes % 60)
                : getString(R.string.screen_time_minutes, minutes);
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
