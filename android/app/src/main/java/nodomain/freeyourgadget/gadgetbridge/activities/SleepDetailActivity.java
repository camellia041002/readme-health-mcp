/*  Copyright (C) 2026 Gadgetbridge contributors

    This file is part of Gadgetbridge and is licensed under the GNU Affero
    General Public License, version 3 or later. */
package nodomain.freeyourgadget.gadgetbridge.activities;

import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.view.WindowCompat;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.card.MaterialCardView;

import java.util.List;

import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.util.healthquery.DashboardSleepSession;
import nodomain.freeyourgadget.gadgetbridge.util.healthquery.HealthConnectDashboardSnapshot;

public class SleepDetailActivity extends AppCompatActivity {
    private LinearLayout sessionsContainer;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_sleep_detail);
        getWindow().setStatusBarColor(ContextCompat.getColor(this, R.color.heartbeat_background));
        getWindow().setNavigationBarColor(ContextCompat.getColor(this, R.color.heartbeat_background));
        WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView()).setAppearanceLightStatusBars(true);
        WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView()).setAppearanceLightNavigationBars(true);

        final MaterialToolbar toolbar = findViewById(R.id.sleep_detail_toolbar);
        toolbar.setNavigationOnClickListener(view -> finish());
        sessionsContainer = findViewById(R.id.sleep_detail_sessions);
        renderSnapshot(HealthConnectDashboardSnapshot.cached());
    }

    private void renderSnapshot(@Nullable HealthConnectDashboardSnapshot snapshot) {
        final TextView date = findViewById(R.id.sleep_detail_date);
        final TextView total = findViewById(R.id.sleep_detail_total);
        final TextView count = findViewById(R.id.sleep_detail_count);
        final TextView empty = findViewById(R.id.sleep_detail_empty);
        sessionsContainer.removeAllViews();

        if (snapshot == null || snapshot.getSleepSessions().isEmpty()) {
            date.setText("");
            total.setText("");
            count.setText("");
            empty.setVisibility(View.VISIBLE);
            return;
        }

        final List<DashboardSleepSession> sessions = snapshot.getSleepSessions();
        date.setText(snapshot.getLatestSleepDate());
        total.setText(formatDuration(snapshot.getSleepMinutes()));
        count.setText(getString(R.string.sleep_detail_session_count, sessions.size()));
        empty.setVisibility(View.GONE);

        boolean mainSessionAssigned = false;
        for (DashboardSleepSession session : sessions) {
            final int titleRes;
            if (session.getNap()) {
                titleRes = R.string.sleep_detail_nap;
            } else if (!mainSessionAssigned) {
                titleRes = R.string.sleep_detail_main_session;
                mainSessionAssigned = true;
            } else {
                titleRes = R.string.sleep_detail_split_session;
            }
            addSessionCard(session, titleRes);
        }
    }

    private void addSessionCard(DashboardSleepSession session, int titleRes) {
        final MaterialCardView card = new MaterialCardView(this);
        card.setRadius(dp(20));
        card.setCardElevation(0);
        card.setStrokeWidth(0);
        card.setCardBackgroundColor(ContextCompat.getColor(this, R.color.heartbeat_surface));
        card.setContentPadding(dp(18), dp(17), dp(18), dp(17));

        final LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);

        final LinearLayout heading = new LinearLayout(this);
        heading.setOrientation(LinearLayout.HORIZONTAL);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        final TextView title = new TextView(this);
        title.setText(titleRes);
        title.setTextColor(ContextCompat.getColor(this, R.color.accent));
        title.setTextSize(18);
        title.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        heading.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        final TextView range = new TextView(this);
        range.setText(getString(R.string.sleep_detail_range, session.getStartTime(), session.getEndTime()));
        range.setTextColor(ContextCompat.getColor(this, R.color.heartbeat_charcoal));
        range.setTextSize(17);
        range.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        heading.addView(range);
        content.addView(heading);

        addMetricRow(content, R.string.sleep_detail_total, formatDuration(session.getAsleepMinutes()), true);
        if (session.getHasStageDetails()) {
            addMetricRow(content, R.string.sleep_detail_deep, formatDuration(session.getDeepMinutes()), false);
            addMetricRow(content, R.string.sleep_detail_light, formatDuration(session.getLightMinutes()), false);
            addMetricRow(content, R.string.sleep_detail_rem, formatDuration(session.getRemMinutes()), false);
            addMetricRow(content, R.string.sleep_detail_awake, formatDuration(session.getAwakeMinutes()), false);
        } else {
            final TextView missing = new TextView(this);
            missing.setText(R.string.sleep_detail_no_stages);
            missing.setTextColor(ContextCompat.getColor(this, R.color.secondarytext));
            missing.setTextSize(14);
            missing.setPadding(0, dp(14), 0, 0);
            content.addView(missing);
        }

        card.addView(content, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        final LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = dp(12);
        sessionsContainer.addView(card, params);
    }

    private void addMetricRow(LinearLayout parent, int labelRes, String value, boolean first) {
        final LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(first ? 16 : 10), 0, 0);
        final TextView label = new TextView(this);
        label.setText(labelRes);
        label.setTextColor(ContextCompat.getColor(this, R.color.secondarytext));
        label.setTextSize(15);
        row.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        final TextView metric = new TextView(this);
        metric.setText(value);
        metric.setTextColor(ContextCompat.getColor(this, R.color.heartbeat_charcoal));
        metric.setTextSize(15);
        metric.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        row.addView(metric);
        parent.addView(row);
    }

    private String formatDuration(long minutes) {
        final long safeMinutes = Math.max(0L, minutes);
        return safeMinutes >= 60L
                ? getString(R.string.readme_sleep_value, safeMinutes / 60L, safeMinutes % 60L)
                : getString(R.string.sleep_detail_minutes, safeMinutes);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
