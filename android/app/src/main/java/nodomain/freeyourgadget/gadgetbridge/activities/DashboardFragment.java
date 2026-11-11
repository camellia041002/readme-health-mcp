/*  Copyright (C) 2023-2024 Arjan Schrijver, José Rebelo

    This file is part of Gadgetbridge.

    Gadgetbridge is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as published
    by the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    Gadgetbridge is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>. */
package nodomain.freeyourgadget.gadgetbridge.activities;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.provider.Settings;
import android.text.format.DateUtils;
import android.util.DisplayMetrics;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.Gravity;
import android.widget.TextView;
import android.widget.LinearLayout;
import android.graphics.Color;

import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.core.view.MenuProvider;
import androidx.core.content.ContextCompat;
import androidx.core.widget.TextViewCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentContainerView;
import androidx.gridlayout.widget.GridLayout;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.google.android.material.card.MaterialCardView;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.activities.dashboard.AbstractDashboardWidget;
import nodomain.freeyourgadget.gadgetbridge.activities.dashboard.DashboardCaloriesActiveGoalWidget;
import nodomain.freeyourgadget.gadgetbridge.activities.dashboard.DashboardActiveTimeWidget;
import nodomain.freeyourgadget.gadgetbridge.activities.dashboard.DashboardBodyEnergyWidget;
import nodomain.freeyourgadget.gadgetbridge.activities.dashboard.DashboardCalendarActivity;
import nodomain.freeyourgadget.gadgetbridge.activities.dashboard.DashboardCaloriesTotalSegmentedWidget;
import nodomain.freeyourgadget.gadgetbridge.activities.dashboard.DashboardDistanceWidget;
import nodomain.freeyourgadget.gadgetbridge.activities.dashboard.DashboardGoalsWidget;
import nodomain.freeyourgadget.gadgetbridge.activities.dashboard.DashboardHrvWidget;
import nodomain.freeyourgadget.gadgetbridge.activities.dashboard.DashboardBloodPressureWidget;
import nodomain.freeyourgadget.gadgetbridge.activities.dashboard.DashboardPaiWidget;
import nodomain.freeyourgadget.gadgetbridge.activities.dashboard.DashboardSleepScoreWidget;
import nodomain.freeyourgadget.gadgetbridge.activities.dashboard.DashboardSleepWidget;
import nodomain.freeyourgadget.gadgetbridge.activities.dashboard.DashboardStepsWidget;
import nodomain.freeyourgadget.gadgetbridge.activities.dashboard.DashboardStressBreakdownWidget;
import nodomain.freeyourgadget.gadgetbridge.activities.dashboard.DashboardStressSegmentedWidget;
import nodomain.freeyourgadget.gadgetbridge.activities.dashboard.DashboardStressSimpleWidget;
import nodomain.freeyourgadget.gadgetbridge.activities.dashboard.DashboardTodayWidget;
import nodomain.freeyourgadget.gadgetbridge.activities.dashboard.DashboardVO2MaxCyclingWidget;
import nodomain.freeyourgadget.gadgetbridge.activities.dashboard.DashboardVO2MaxAnyWidget;
import nodomain.freeyourgadget.gadgetbridge.activities.dashboard.DashboardVO2MaxRunningWidget;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.util.DateTimeUtils;
import nodomain.freeyourgadget.gadgetbridge.util.GBPrefs;
import nodomain.freeyourgadget.gadgetbridge.util.Prefs;
import nodomain.freeyourgadget.gadgetbridge.util.ScreenTimeSummary;
import androidx.health.connect.client.PermissionController;
import nodomain.freeyourgadget.gadgetbridge.util.healthconnect.HealthConnectPermissionManager;

public class DashboardFragment extends Fragment implements MenuProvider {
    private static final Logger LOG = LoggerFactory.getLogger(DashboardFragment.class);

    private final Calendar day = GregorianCalendar.getInstance();
    private TextView textViewDate;
    private TextView arrowRight;
    private TextView bandStatus;
    private TextView uploadStatus;
    private TextView retentionStatus;
    private TextView screenTimeValue;
    private TextView screenTimeDetail;
    private View screenTimePermission;
    private GridLayout gridLayout;
    private final Map<String, AbstractDashboardWidget> widgetMap = new HashMap<>();
    private DashboardData dashboardData = new DashboardData();
    private boolean isConfigChanged = false;
    private final ExecutorService healthSnapshotExecutor = Executors.newSingleThreadExecutor();
    private volatile boolean healthSnapshotLoading = false;
    private nodomain.freeyourgadget.gadgetbridge.util.healthquery.HealthConnectDashboardSnapshot healthSnapshot;

    private ActivityResultLauncher<Intent> calendarLauncher;
    private ActivityResultLauncher<Set<String>> healthPermissionLauncher;
    private final ActivityResultCallback<ActivityResult> calendarCallback = result -> {
        if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
            long timeMillis = result.getData().getLongExtra(DashboardCalendarActivity.EXTRA_TIMESTAMP, 0);
            if (timeMillis != 0) {
                day.setTimeInMillis(timeMillis);
                fullRefresh();
            }
        }
    };

    public static final String ACTION_CONFIG_CHANGE = "nodomain.freeyourgadget.gadgetbridge.activities.dashboardfragment.action.config_change";

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action == null) return;
            switch (action) {
                case GBApplication.ACTION_NEW_DATA:
                    final GBDevice dev = intent.getParcelableExtra(GBDevice.EXTRA_DEVICE);
                    if (dev != null) {
                        if (dashboardData.showAllDevices || dashboardData.showDeviceList.contains(dev.getAddress())) {
                            refresh();
                            loadHealthSnapshot();
                        }
                    }
                    break;
                case ACTION_CONFIG_CHANGE:
                    isConfigChanged = true;
                    break;
            }
        }
    };

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        super.onCreateView(inflater, container, savedInstanceState);
        View dashboardView = inflater.inflate(R.layout.fragment_dashboard, container, false);
        textViewDate = dashboardView.findViewById(R.id.dashboard_date);
        bandStatus = dashboardView.findViewById(R.id.heartbeat_band_status);
        uploadStatus = dashboardView.findViewById(R.id.heartbeat_upload_status);
        retentionStatus = dashboardView.findViewById(R.id.heartbeat_retention_status);
        screenTimeValue = dashboardView.findViewById(R.id.screen_time_value);
        screenTimeDetail = dashboardView.findViewById(R.id.screen_time_detail);
        screenTimePermission = dashboardView.findViewById(R.id.screen_time_permission);
        screenTimePermission.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)));
        gridLayout = dashboardView.findViewById(R.id.dashboard_gridlayout);

        calendarLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                calendarCallback
        );
        healthPermissionLauncher = registerForActivityResult(
                PermissionController.createRequestPermissionResultContract(),
                granted -> {
                    HealthConnectPermissionManager.handlePermissionsResult(requireContext(), granted);
                    healthSnapshot = null;
                    drawReadMeCards();
                });

        // Increase column count on landscape, tablets and open foldables
        DisplayMetrics displayMetrics = getResources().getDisplayMetrics();
        if (displayMetrics.widthPixels / displayMetrics.density >= 600) {
            gridLayout.setColumnCount(4);
        }

        final TextView arrowLeft = dashboardView.findViewById(R.id.arrow_left);
        arrowLeft.setOnClickListener(v -> { });
        arrowRight = dashboardView.findViewById(R.id.arrow_right);
        arrowRight.setOnClickListener(v -> { });

        if (savedInstanceState != null && savedInstanceState.containsKey("dashboard_day")) {
            day.setTimeInMillis(savedInstanceState.getLong("dashboard_day"));
        }
        reloadPreferences();

        IntentFilter filterLocal = new IntentFilter();
        filterLocal.addAction(GBDevice.ACTION_DEVICE_CHANGED);
        filterLocal.addAction(GBApplication.ACTION_NEW_DATA);
        filterLocal.addAction(ACTION_CONFIG_CHANGE);
        LocalBroadcastManager.getInstance(requireContext()).registerReceiver(mReceiver, filterLocal);

        return dashboardView;
    }

    @Override
    public void onResume() {
        super.onResume();
        final String promptKey = "readme_optional_health_permissions_295";
        if (!GBApplication.getPrefs().getBoolean(promptKey, false)) {
            GBApplication.getPrefs().getPreferences().edit().putBoolean(promptKey, true).apply();
            healthPermissionLauncher.launch(HealthConnectPermissionManager.getRequiredHealthConnectPermissions());
        }
        refreshScreenTime();
        if (isConfigChanged) {
            isConfigChanged = false;
            fullRefresh();
        } else if (widgetMap.isEmpty()) {
            refresh();
        }
        // Keep the cached cards visible, then silently read Health Connect again.
        // Without this, reopening the home screen can keep a stale empty snapshot
        // even after Health Sync has written newer records.
        loadHealthSnapshot();
    }

    @Override
    public void onDestroy() {
        LocalBroadcastManager.getInstance(requireContext()).unregisterReceiver(mReceiver);
        healthSnapshotExecutor.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putLong("dashboard_day", day.getTimeInMillis());
    }

    @Override
    public void onCreateMenu(@NonNull final Menu menu, @NonNull final MenuInflater inflater) {
        inflater.inflate(R.menu.dashboard_menu, menu);
    }

    @Override
    public boolean onMenuItemSelected(@NonNull final MenuItem item) {
        final int itemId = item.getItemId();
        if (itemId == R.id.dashboard_show_calendar) {
            final Intent intent = new Intent(requireActivity(), DashboardCalendarActivity.class);
            intent.putExtra(DashboardCalendarActivity.EXTRA_TIMESTAMP, day.getTimeInMillis());
            calendarLauncher.launch(intent);
            return true;
        } else if (itemId == R.id.dashboard_settings) {
            final Intent intent = new Intent(requireActivity(), DashboardPreferencesActivity.class);
            startActivity(intent);
            return true;
        }
        return false;
    }

    private void fullRefresh() {
        gridLayout.removeAllViews();
        widgetMap.clear();
        refresh();
    }

    private void refresh() {
        day.set(Calendar.HOUR_OF_DAY, 23);
        day.set(Calendar.MINUTE, 59);
        day.set(Calendar.SECOND, 59);
        reloadPreferences();
        if (healthSnapshot == null) healthSnapshot = nodomain.freeyourgadget.gadgetbridge.util.healthquery.HealthConnectDashboardSnapshot.cached();
        refreshHeartbeatStatus();
        draw();
    }

    private void refreshHeartbeatStatus() {
        final java.util.List<GBDevice> devices = GBApplication.app().getDeviceManager().getDevices();
        if (devices.isEmpty()) {
            bandStatus.setText(R.string.heartbeat_no_band);
        } else {
            final GBDevice device = devices.get(0);
            final String name = device.getAliasOrName();
            if (device.isInitialized()) {
                final int battery = device.getBatteryLevel();
                if (battery >= 0) {
                    bandStatus.setText(getString(R.string.heartbeat_band_connected, name, battery));
                } else {
                    bandStatus.setText(getString(R.string.heartbeat_band_connected_no_battery, name));
                }
            } else {
                bandStatus.setText(getString(R.string.heartbeat_band_managed, name));
            }
        }

        final String lastUpload = GBApplication.getPrefs()
                .getString(GBPrefs.SELF_HOSTED_HEALTH_STATUS, "");
        if (lastUpload == null || lastUpload.isEmpty()) {
            uploadStatus.setText(R.string.heartbeat_upload_never);
        } else {
            uploadStatus.setText(getString(R.string.heartbeat_upload_status, lastUpload));
        }
    }

    private void refreshScreenTime() {
        if (!ScreenTimeSummary.hasPermission(requireContext())) {
            screenTimeValue.setText("—");
            screenTimeDetail.setText(R.string.screen_time_permission_detail);
            screenTimePermission.setVisibility(View.VISIBLE);
            return;
        }
        screenTimePermission.setVisibility(View.GONE);
        final ScreenTimeSummary.Result summary = ScreenTimeSummary.today(requireContext());
        screenTimeValue.setText(formatScreenTime(summary.totalMillis()));
        if (summary.topAppLabel().isEmpty()) {
            screenTimeDetail.setText(R.string.screen_time_no_activity);
        } else {
            screenTimeDetail.setText(getString(
                    R.string.screen_time_top_app,
                    summary.topAppLabel(),
                    formatScreenTime(summary.topAppMillis())));
        }
    }

    private String formatScreenTime(long millis) {
        final long minutes = Math.max(0, millis / 60_000L);
        final long hours = minutes / 60L;
        final long remainder = minutes % 60L;
        return hours > 0
                ? getString(R.string.screen_time_hours_minutes, hours, remainder)
                : getString(R.string.screen_time_minutes, minutes);
    }

    private void reloadPreferences() {
        Prefs prefs = GBApplication.getPrefs();
        dashboardData.showAllDevices = prefs.getBoolean("dashboard_devices_all", true);
        dashboardData.showDeviceList = prefs.getStringSet("dashboard_devices_multiselect", new HashSet<>());
        dashboardData.hrIntervalSecs = prefs.getInt("dashboard_widget_today_hr_interval", 1) * 60;
        dashboardData.timeTo = (int) (day.getTimeInMillis() / 1000);
        dashboardData.timeFrom = DateTimeUtils.shiftDays(dashboardData.timeTo, -1);
    }

    private void draw() {
        Calendar today = GregorianCalendar.getInstance();
        textViewDate.setText(DateTimeUtils.formatDate(day.getTime(), DateUtils.FORMAT_SHOW_DATE));

        drawReadMeCards();
    }

    private void drawReadMeCards() {
        gridLayout.removeAllViews();
        final nodomain.freeyourgadget.gadgetbridge.util.healthquery.HealthConnectDashboardSnapshot snapshot = healthSnapshot;
        if (snapshot == null) {
            addReadMeCard(R.string.readme_card_sleep, getString(R.string.readme_loading), "");
            addReadMeCard(R.string.readme_card_heart, getString(R.string.readme_loading), R.string.readme_card_heart_hint);
            addReadMeCard(R.string.readme_card_steps, getString(R.string.readme_loading), R.string.readme_card_steps_hint);
            addReadMeCard(R.string.readme_card_spo2, getString(R.string.readme_loading), R.string.readme_card_spo2_hint);
            loadHealthSnapshot();
            return;
        }
        if (!snapshot.getAvailable()) {
            addReadMeCard(R.string.readme_card_sleep, getString(R.string.readme_source_unavailable), "");
            addReadMeCard(R.string.readme_card_heart, getString(R.string.readme_source_unavailable), R.string.readme_card_heart_hint);
            addReadMeCard(R.string.readme_card_steps, getString(R.string.readme_source_unavailable), R.string.readme_card_steps_hint);
            addReadMeCard(R.string.readme_card_spo2, getString(R.string.readme_source_unavailable), R.string.readme_card_spo2_hint);
            return;
        }
        final long sleepMinutes = snapshot.getSleepMinutes();
        final String sleepValue = sleepMinutes > 0
                ? getString(R.string.readme_sleep_value, sleepMinutes / 60, sleepMinutes % 60)
                : getString(R.string.readme_not_synced);
        final String sleepTimes = snapshot.getSleepStartTime().isEmpty() || snapshot.getSleepEndTime().isEmpty()
                ? ""
                : getString(R.string.readme_sleep_range, snapshot.getSleepStartTime(), snapshot.getSleepEndTime());
        addReadMeCard(R.string.readme_card_sleep, sleepValue,
                snapshot.getLatestSleepDate().isEmpty() ? selectedDate() : snapshot.getLatestSleepDate(), sleepTimes);
        final String heartValue = snapshot.getLatestHeartRate() > 0
                ? getString(R.string.readme_heart_value, snapshot.getLatestHeartRate())
                : getString(R.string.readme_not_synced);
        final String heartHint = snapshot.getLatestHeartRateDateTime().isEmpty()
                ? getString(R.string.readme_card_heart_hint)
                : snapshot.getLatestHeartRateDateTime();
        addReadMeCard(R.string.readme_card_heart, heartValue, heartHint);
        final String stepsValue = snapshot.getStepsAvailable()
                ? getString(R.string.readme_steps_value, snapshot.getSteps())
                : getString(R.string.readme_not_synced);
        final String stepsHint = snapshot.getStepsAvailable()
                ? (snapshot.getLatestStepsDateTime().isEmpty() ? selectedDate() : snapshot.getLatestStepsDateTime())
                : getString(R.string.readme_steps_missing_hint);
        addReadMeCard(R.string.readme_card_steps, stepsValue, stepsHint);
        addReadMeCard(R.string.readme_card_spo2,
                snapshot.getLatestOxygen() > 0 ? String.format(java.util.Locale.getDefault(), "%.0f%%", snapshot.getLatestOxygen()) : getString(R.string.readme_not_synced),
                snapshot.getLatestOxygenDateTime().isEmpty() ? getString(R.string.readme_card_spo2_hint) : snapshot.getLatestOxygenDateTime());
        if (snapshot.getLatestHeartRateTime().isEmpty()) {
            retentionStatus.setText(R.string.heartbeat_retention_unknown);
        } else {
            retentionStatus.setText(getString(R.string.heartbeat_data_latest, snapshot.getLatestHeartRateTime()));
        }
    }

    private void loadHealthSnapshot() {
        if (healthSnapshotLoading) return;
        healthSnapshotLoading = true;
        final Context appContext = requireContext().getApplicationContext();
        healthSnapshotExecutor.execute(() -> {
            final nodomain.freeyourgadget.gadgetbridge.util.healthquery.HealthConnectDashboardSnapshot result =
                    nodomain.freeyourgadget.gadgetbridge.util.healthquery.HealthConnectDashboardSnapshot.load(appContext);
            if (!isAdded()) return;
            requireActivity().runOnUiThread(() -> {
                healthSnapshotLoading = false;
                healthSnapshot = result;
                drawReadMeCards();
            });
        });
    }

    private void addReadMeCard(int titleRes, String value, int hintRes) {
        addReadMeCard(titleRes, value, selectedDate() + " · " + getString(hintRes));
    }

    private String selectedDate() {
        return DateTimeUtils.formatDate(day.getTime(), DateUtils.FORMAT_SHOW_DATE);
    }

    private void addReadMeCard(int titleRes, String value, String hintText) {
        addReadMeCard(titleRes, value, hintText, "");
    }

    private void addReadMeCard(int titleRes, String value, String footerLeft, String footerRight) {
        final float density = getResources().getDisplayMetrics().density;
        final MaterialCardView card = new MaterialCardView(requireContext());
        card.setRadius(20 * density);
        card.setCardElevation(0);
        card.setStrokeWidth(0);
        card.setStrokeColor(Color.TRANSPARENT);
        card.setCardBackgroundColor(ContextCompat.getColor(requireContext(), R.color.heartbeat_surface));
        card.setContentPadding((int) (15 * density), (int) (15 * density),
                (int) (15 * density), (int) (14 * density));
        card.setMinimumHeight((int) (148 * density));

        final LinearLayout content = new LinearLayout(requireContext());
        content.setOrientation(LinearLayout.VERTICAL);
        final TextView title = new TextView(requireContext());
        title.setText(titleRes);
        title.setTextColor(ContextCompat.getColor(requireContext(), R.color.heartbeat_charcoal));
        title.setTextSize(16);
        content.addView(title);
        final TextView metric = new TextView(requireContext());
        metric.setText(value);
        metric.setTextColor(ContextCompat.getColor(requireContext(), R.color.heartbeat_charcoal));
        metric.setTextSize(28);
        metric.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        metric.setGravity(Gravity.CENTER);
        metric.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        metric.setMaxLines(1);
        metric.setIncludeFontPadding(false);
        TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(metric, 18, 28, 1,
                android.util.TypedValue.COMPLEX_UNIT_SP);
        final LinearLayout.LayoutParams metricParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        metricParams.topMargin = (int) (4 * density);
        metricParams.bottomMargin = (int) (4 * density);
        content.addView(metric, metricParams);
        final LinearLayout footer = new LinearLayout(requireContext());
        footer.setOrientation(LinearLayout.HORIZONTAL);
        footer.setGravity(Gravity.BOTTOM);
        final TextView hint = new TextView(requireContext());
        hint.setText(footerLeft);
        hint.setTextColor(ContextCompat.getColor(requireContext(), R.color.secondarytext));
        hint.setTextSize(13);
        hint.setMaxLines(2);
        footer.addView(hint, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (!footerRight.isEmpty()) {
            final TextView right = new TextView(requireContext());
            right.setText(footerRight);
            right.setTextColor(ContextCompat.getColor(requireContext(), R.color.secondarytext));
            right.setTextSize(13);
            right.setGravity(Gravity.END);
            right.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_END);
            right.setMaxLines(2);
            footer.addView(right, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        content.addView(footer);
        card.addView(content, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        final GridLayout.LayoutParams params = new GridLayout.LayoutParams(
                GridLayout.spec(GridLayout.UNDEFINED, GridLayout.FILL, 1f),
                GridLayout.spec(GridLayout.UNDEFINED, 1, GridLayout.FILL, 1f));
        params.width = 0;
        params.height = (int) (158 * density);
        final int margin = (int) (5 * density);
        params.setMargins(margin, margin, margin, margin);
        card.setLayoutParams(params);
        gridLayout.addView(card);
    }

    private void drawLegacyWidgetsUnused() {
        Prefs prefs = GBApplication.getPrefs();
        String defaultWidgetsOrder = String.join(",", getResources().getStringArray(R.array.pref_dashboard_widgets_order_default));
        String widgetsOrderPref = prefs.getString("pref_dashboard_widgets_order", defaultWidgetsOrder);
        String[] widgetsOrder = widgetsOrderPref.split(",");

        boolean cardsEnabled = prefs.getBoolean("dashboard_cards_enabled", true);

        for (String widgetName : widgetsOrder) {
            // The upstream clock/pie and goal mosaics are visually dense and duplicate the
            // calmer ReadMe overview. Their detailed data remains available through metrics.
            if ("today".equals(widgetName) || "goals".equals(widgetName)) continue;
            AbstractDashboardWidget widget = widgetMap.get(widgetName);
            if (widget == null) {
                int columnSpan = 1;
                switch (widgetName) {
                    case "today":
                        widget = DashboardTodayWidget.newInstance(dashboardData);
                        columnSpan = prefs.getBoolean("dashboard_widget_today_2columns", true) ? 2 : 1;
                        break;
                    case "goals":
                        widget = DashboardGoalsWidget.newInstance(dashboardData);
                        columnSpan = prefs.getBoolean("dashboard_widget_goals_2columns", true) ? 2 : 1;
                        break;
                    case "steps":
                        widget = DashboardStepsWidget.newInstance(dashboardData);
                        break;
                    case "distance":
                        widget = DashboardDistanceWidget.newInstance(dashboardData);
                        break;
                    case "activetime":
                        widget = DashboardActiveTimeWidget.newInstance(dashboardData);
                        break;
                    case "sleep":
                        widget = DashboardSleepWidget.newInstance(dashboardData);
                        break;
                    case "stress_simple":
                        widget = DashboardStressSimpleWidget.newInstance(dashboardData);
                        break;
                    case "stress_segmented":
                        widget = DashboardStressSegmentedWidget.newInstance(dashboardData);
                        break;
                    case "stress_breakdown":
                        widget = DashboardStressBreakdownWidget.newInstance(dashboardData);
                        break;
                    case "bodyenergy":
                        widget = DashboardBodyEnergyWidget.newInstance(dashboardData);
                        break;
                    case "pai":
                        widget = DashboardPaiWidget.newInstance(dashboardData);
                        break;
                    case "hrv":
                        widget = DashboardHrvWidget.newInstance(dashboardData);
                        break;
                    case "bloodpressure":
                        widget = DashboardBloodPressureWidget.newInstance(dashboardData);
                        break;
                    case "vo2max_running":
                        widget = DashboardVO2MaxRunningWidget.newInstance(dashboardData);
                        break;
                    case "vo2max_cycling":
                        widget = DashboardVO2MaxCyclingWidget.newInstance(dashboardData);
                        break;
                    case "vo2max":
                        widget = DashboardVO2MaxAnyWidget.newInstance(dashboardData);
                        break;
                    case "calories_active":
                        widget = DashboardCaloriesActiveGoalWidget.newInstance(dashboardData);
                        break;
                    case "calories_segmented":
                        widget = DashboardCaloriesTotalSegmentedWidget.newInstance(dashboardData);
                        break;
                    case "sleepscore":
                        widget = DashboardSleepScoreWidget.newInstance(dashboardData);
                        break;
                    default:
                        LOG.error("Unknown dashboard widget {}", widgetName);
                        continue;
                }

                createWidget(widget, cardsEnabled, columnSpan);

                widgetMap.put(widgetName, widget);
            } else {
                widget.update();
            }
        }
    }

    private void createWidget(AbstractDashboardWidget widgetObj, boolean cardsEnabled, int columnSpan) {
        final float scale = requireContext().getResources().getDisplayMetrics().density;
        FragmentContainerView fragment = new FragmentContainerView(requireActivity());
        int fragmentId = View.generateViewId();
        fragment.setId(fragmentId);
        getChildFragmentManager()
                .beginTransaction()
                .replace(fragmentId, widgetObj)
                .commitAllowingStateLoss(); // FIXME: #4007

        GridLayout.LayoutParams layoutParams = new GridLayout.LayoutParams(
                GridLayout.spec(GridLayout.UNDEFINED, GridLayout.FILL, 1f),
                GridLayout.spec(GridLayout.UNDEFINED, columnSpan, GridLayout.FILL, 1f)
        );
        layoutParams.width = 0;
        int pixels_5dp = (int) (5 * scale + 0.5f);
        layoutParams.setMargins(pixels_5dp, pixels_5dp, pixels_5dp, pixels_5dp);

        if (cardsEnabled) {
            MaterialCardView card = new MaterialCardView(requireActivity());
            int pixels_12dp = (int) (12 * scale + 0.5f);
            card.setRadius(18 * scale);
            card.setCardElevation(0);
            card.setCardBackgroundColor(ContextCompat.getColor(requireContext(), R.color.heartbeat_surface));
            card.setContentPadding(pixels_12dp, pixels_12dp, pixels_12dp, pixels_12dp);
            if (columnSpan == 1) {
                card.setMinimumHeight((int) (118 * scale + 0.5f));
            }
            card.setLayoutParams(layoutParams);
            card.addView(fragment);
            gridLayout.addView(card);
        } else {
            fragment.setLayoutParams(layoutParams);
            gridLayout.addView(fragment);
        }
    }

    /**
     * Carries the query parameters (selected day, device filter) that every dashboard widget
     * needs to load its own data.
     */
    public static class DashboardData implements Serializable {
        public boolean showAllDevices;
        public Set<String> showDeviceList;
        public int hrIntervalSecs;
        public int timeFrom;
        public int timeTo;
    }
}
