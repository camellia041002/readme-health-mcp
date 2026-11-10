package nodomain.freeyourgadget.gadgetbridge.activities;

import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.view.WindowCompat;

import com.google.android.material.appbar.MaterialToolbar;

import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.util.ScreenTimeSummary;

public class ScreenTimeDetailActivity extends AppCompatActivity {
    private LinearLayout appList;
    private TextView total;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_screen_time_detail);
        getWindow().setStatusBarColor(ContextCompat.getColor(this, R.color.heartbeat_background));
        getWindow().setNavigationBarColor(ContextCompat.getColor(this, R.color.heartbeat_background));
        WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView()).setAppearanceLightStatusBars(true);
        WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView()).setAppearanceLightNavigationBars(true);
        final MaterialToolbar toolbar = findViewById(R.id.screen_time_toolbar);
        toolbar.setNavigationOnClickListener(view -> finish());
        appList = findViewById(R.id.screen_time_app_list);
        total = findViewById(R.id.screen_time_detail_total);
        renderApps();
    }

    private void renderApps() {
        final ScreenTimeSummary.Result result = ScreenTimeSummary.today(this);
        total.setText(formatDuration(result.totalMillis()));
        appList.removeAllViews();
        for (ScreenTimeSummary.AppUsage app : result.apps()) {
            final LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(14), 0, dp(14));

            final TextView name = new TextView(this);
            name.setText(app.label());
            name.setTextColor(ContextCompat.getColor(this, R.color.heartbeat_charcoal));
            name.setTextSize(15);
            row.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            final TextView duration = new TextView(this);
            duration.setText(formatDuration(app.millis()));
            duration.setTextColor(ContextCompat.getColor(this, R.color.secondarytext));
            duration.setTextSize(14);
            row.addView(duration);
            appList.addView(row);
        }
    }

    private String formatDuration(long millis) {
        final long minutes = Math.max(0, millis / 60_000L);
        return minutes >= 60
                ? getString(R.string.screen_time_hours_minutes, minutes / 60, minutes % 60)
                : getString(R.string.screen_time_minutes, minutes);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
