/*  Copyright (C) 2016-2024 Andreas Shimokawa, Andrzej Surowiec, Arjan
    Schrijver, Carsten Pfeiffer, Daniel Dakhno, Daniele Gobbetti, Ganblejs,
    gfwilliams, Gordon Williams, Johannes Tysiak, José Rebelo, marco.altomonte,
    Petr Vaněk, Taavi Eomäe

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
    along with this program.  If not, see <https://www.gnu.org/licenses/>. */
package nodomain.freeyourgadget.gadgetbridge.activities;

import static nodomain.freeyourgadget.gadgetbridge.model.DeviceService.ACTION_CONNECT;

import android.animation.ValueAnimator;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.net.Uri;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.Toast;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBarDrawerToggle;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.graphics.drawable.DrawableCompat;
import androidx.core.view.GravityCompat;
import androidx.core.view.MenuProvider;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.navigation.NavigationView;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.activities.debug.DebugActivityV2;
import nodomain.freeyourgadget.gadgetbridge.activities.welcome.WelcomeActivity;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.model.ActivitySample;
import nodomain.freeyourgadget.gadgetbridge.model.DeviceService;
import nodomain.freeyourgadget.gadgetbridge.model.RecordedDataTypes;
import nodomain.freeyourgadget.gadgetbridge.util.AndroidUtils;
import nodomain.freeyourgadget.gadgetbridge.util.DeviceHelper;
import nodomain.freeyourgadget.gadgetbridge.util.GB;
import nodomain.freeyourgadget.gadgetbridge.util.GBChangeLog;
import nodomain.freeyourgadget.gadgetbridge.util.GBPrefs;
import nodomain.freeyourgadget.gadgetbridge.util.PermissionsUtils;
import nodomain.freeyourgadget.gadgetbridge.widget.ReadMeSplashWaveView;

//TODO: extend AbstractGBActivity, but it requires actionbar that is not available
public class ControlCenterv2 extends AppCompatActivity
        implements NavigationView.OnNavigationItemSelectedListener, GBActivity {
    private static final Logger LOG = LoggerFactory.getLogger(ControlCenterv2.class);
    public static final int MENU_REFRESH_CODE = 1;
    private boolean isLanguageInvalid = false;
    private boolean isThemeInvalid = false;
    private ViewPager2 viewPager;
    private FragmentStateAdapter pagerAdapter;
    private SwipeRefreshLayout swipeLayout;
    private AlertDialog clDialog;

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            switch (Objects.requireNonNull(action)) {
                case GBApplication.ACTION_LANGUAGE_CHANGE:
                    setLanguage(GBApplication.getLanguage(), true);
                    break;
                case GBApplication.ACTION_THEME_CHANGE:
                    isThemeInvalid = true;
                    break;
                case GBApplication.ACTION_QUIT:
                    finish();
                    break;
                case DeviceService.ACTION_REALTIME_SAMPLES:
                    final GBDevice device = intent.getParcelableExtra(GBDevice.EXTRA_DEVICE);
                    handleRealtimeSample(device, intent.getSerializableExtra(DeviceService.EXTRA_REALTIME_SAMPLE));
                    break;
                case GBDevice.ACTION_DEVICE_CHANGED:
                    GBDevice dev = intent.getParcelableExtra(GBDevice.EXTRA_DEVICE);
                    if (dev != null && !dev.isBusy()) {
                        swipeLayout.setRefreshing(false);
                    }
                    break;
            }
        }
    };
    private boolean pesterWithPermissions = true;
    private final Map<GBDevice, ActivitySample> currentHRSample = new HashMap<>();

    public ActivitySample getCurrentHRSample(final GBDevice device) {
        return currentHRSample.get(device);
    }

    private void setCurrentHRSample(final GBDevice device, ActivitySample sample) {
        if (HeartRateUtils.getInstance().isValidHeartRateValue(sample.getHeartRate())) {
            currentHRSample.put(device, sample);
        }
    }

    private void handleRealtimeSample(final GBDevice device, Serializable extra) {
        if (extra instanceof ActivitySample) {
            ActivitySample sample = (ActivitySample) extra;
            setCurrentHRSample(device, sample);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        AbstractGBActivity.init(this, AbstractGBActivity.NO_ACTIONBAR);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        showHeartbeatSplash(savedInstanceState == null);

        GBPrefs prefs = GBApplication.getPrefs();

        // Determine availability of device with activity tracking functionality
        boolean activityTrackerAvailable = false;
        List<GBDevice> devices = GBApplication.app().getDeviceManager().getDevices();
        for (GBDevice dev : devices) {
            if (dev.getDeviceCoordinator().supportsActivityTracking(dev)) {
                activityTrackerAvailable = true;
                break;
            }
        }
        if (savedInstanceState != null) {
            if (savedInstanceState.getBoolean("cl")) {
                GBChangeLog cl = GBChangeLog.createChangeLog(this);
                try {
                    if (cl.hasChanges(false)) {
                        clDialog = cl.getMaterialLogDialog();
                    } else {
                        clDialog = cl.getMaterialFullLogDialog();
                    }
                    clDialog.show();
                } catch (Exception ex) {
                    GB.toast(getBaseContext(), getString(R.string.error_showing_changelog), Toast.LENGTH_LONG, GB.ERROR, ex);
                }
            }
        }

        // Initialize drawer
        NavigationView drawerNavigationView = findViewById(R.id.nav_view);
        drawerNavigationView.setNavigationItemSelectedListener(this);

        View navigationHeaderView = drawerNavigationView.getHeaderView(0);
        ViewCompat.setOnApplyWindowInsetsListener(navigationHeaderView, (view, windowInsets) -> {
            Insets insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            view.setPadding(view.getPaddingLeft(), insets.top, view.getPaddingRight(), view.getPaddingBottom());
            return windowInsets;
        });

        // Initialize bottom navigation
        BottomNavigationView navigationView = findViewById(R.id.bottom_nav_bar);
        if (activityTrackerAvailable && prefs.getBoolean("display_bottom_navigation_bar", true)) {
            navigationView.setVisibility(View.VISIBLE);
        } else {
            navigationView.setVisibility(View.GONE);
        }
        navigationView.setOnItemSelectedListener(menuItem -> {
            final int itemId = menuItem.getItemId();
            if (itemId == R.id.bottom_nav_dashboard) {
                viewPager.setCurrentItem(0, true);
            } else if (itemId == R.id.bottom_nav_screen_time) {
                viewPager.setCurrentItem(1, true);
            }
            return true;
        });

        // Initialize actionbar
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        DrawerLayout drawer = findViewById(R.id.drawer_layout);
        ActionBarDrawerToggle toggle = new ActionBarDrawerToggle(
                this, drawer, toolbar, R.string.controlcenter_navigation_drawer_open, R.string.controlcenter_navigation_drawer_close);
        drawer.setDrawerListener(toggle);
        toggle.syncState();
        toolbar.setBackgroundColor(getResources().getColor(R.color.heartbeat_panel));
        toolbar.setTitleTextColor(getResources().getColor(R.color.heartbeat_charcoal));
        toolbar.setNavigationIconTint(getResources().getColor(R.color.accent));
        if (toolbar.getNavigationIcon() != null) {
            DrawableCompat.setTint(toolbar.getNavigationIcon(), getResources().getColor(R.color.accent));
        }
        if (toolbar.getOverflowIcon() != null) {
            DrawableCompat.setTint(toolbar.getOverflowIcon(), getResources().getColor(R.color.accent));
        }
        getWindow().setStatusBarColor(getResources().getColor(R.color.heartbeat_panel));
        getWindow().setNavigationBarColor(getResources().getColor(R.color.heartbeat_background));
        WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView()).setAppearanceLightStatusBars(true);
        WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView()).setAppearanceLightNavigationBars(true);

        // Configure ViewPager2 with fragment adapter and default fragment
        viewPager = findViewById(R.id.dashboard_viewpager);
        pagerAdapter = new MainFragmentsPagerAdapter(this);
        viewPager.setAdapter(pagerAdapter);
        if (!prefs.getBoolean("dashboard_as_default_view", true) || !activityTrackerAvailable) {
            viewPager.setCurrentItem(1, false);
            navigationView.getMenu().getItem(1).setChecked(true);
        }

        // Sync ViewPager changes with BottomNavigationView
        viewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            private MenuProvider existingMenuProvider = null;

            @Override
            public void onPageSelected(int position) {
                navigationView.getMenu().getItem(position).setChecked(true);

                // Ensure the menu provider is set to the current fragment
                if (existingMenuProvider != null) {
                    ControlCenterv2.this.removeMenuProvider(existingMenuProvider);
                }
                final Fragment fragment = getSupportFragmentManager().findFragmentByTag("f" + position);
                if (fragment instanceof MenuProvider) {
                    existingMenuProvider = (MenuProvider) fragment;
                    ControlCenterv2.this.addMenuProvider(existingMenuProvider);
                }
            }
        });

        // Make sure the SwipeRefreshLayout doesn't interfere with the ViewPager2
        viewPager.getChildAt(0).setOnTouchListener((v, event) -> {
            if (prefs.refreshOnSwipe()) {
                if (event.getAction() == MotionEvent.ACTION_MOVE) {
                    swipeLayout.setEnabled(false);
                } else {
                    swipeLayout.setEnabled(true);
                }
            }
            return false;
        });

        // Set pull-down-to-refresh action
        swipeLayout = findViewById(R.id.dashboard_swipe_layout);
        swipeLayout.setEnabled(prefs.refreshOnSwipe());
        swipeLayout.setOnRefreshListener(() -> {
            if (prefs.refreshOnSwipe()) {
                List<GBDevice> devices1 = GBApplication.app().getDeviceManager().getDevices();
                final boolean anyConnected = devices1.stream().anyMatch(GBDevice::isInitialized);
                if (!anyConnected) {
                    // No devices are connected at all
                    GB.toast(getString(R.string.info_no_devices_connected), Toast.LENGTH_LONG, GB.WARN);
                    swipeLayout.setRefreshing(false);
                    return;
                }
                // Fetch activity for all connected devices
                GBApplication.deviceService().onFetchRecordedData(RecordedDataTypes.TYPE_SYNC);

                // Hide 'refreshing' animation immediately if no devices are connected that support sync
                final boolean anySupported = devices1.stream().filter(GBDevice::isInitialized)
                        .anyMatch(dev -> dev.getDeviceCoordinator().supportsDataFetching(dev));
                if (!anySupported) {
                    swipeLayout.setRefreshing(false);
                    GB.toast(getString(R.string.info_no_devices_to_sync), Toast.LENGTH_LONG, GB.WARN);
                }
            } else {
                swipeLayout.setRefreshing(false);
            }
        });

        // Set up local intent listener
        IntentFilter filterLocal = new IntentFilter();
        filterLocal.addAction(GBApplication.ACTION_LANGUAGE_CHANGE);
        filterLocal.addAction(GBApplication.ACTION_THEME_CHANGE);
        filterLocal.addAction(GBApplication.ACTION_QUIT);
        filterLocal.addAction(DeviceService.ACTION_REALTIME_SAMPLES);
        filterLocal.addAction(GBDevice.ACTION_DEVICE_CHANGED);
        LocalBroadcastManager.getInstance(this).registerReceiver(mReceiver, filterLocal);

        // Open the Welcome flow on first run, only check permissions on next runs
        boolean firstRun = prefs.getBoolean("first_run", true);
        if (firstRun) {
            launchWelcomeActivity();
        } else {
            pesterWithPermissions = prefs.getBoolean("permission_pestering", true);
            if (pesterWithPermissions && !PermissionsUtils.checkAllPermissions(this)) {
                Intent permissionsIntent = new Intent(this, PermissionsActivity.class);
                permissionsIntent.putExtra(PermissionsActivity.ARG_SHOW_DO_NOT_ASK_BUTTON, true);
                startActivity(permissionsIntent);
            }
        }

        GBChangeLog cl = GBChangeLog.createChangeLog(this);
        boolean showChangelog = prefs.getBoolean("show_changelog", true);
        if (showChangelog && cl.isFirstRun() && cl.hasChanges(cl.isFirstRunEver())) {
            try {
                cl.getMaterialLogDialog().show();
            } catch (Exception ex) {
                GB.toast(this, getString(R.string.error_showing_changelog), Toast.LENGTH_LONG, GB.ERROR, ex);
            }
        }

        GBApplication.deviceService().requestDeviceInfo();
    }

    /**
     * Small, local-only brand transition. The window background already paints immediately, so
     * this overlay exists only to bridge into the real dashboard and never blocks initialization.
     * Android's reduced-animation setting is honored by removing it on the first frame.
     */
    private void showHeartbeatSplash(boolean coldStart) {
        final View overlay = findViewById(R.id.heartbeat_splash_overlay);
        if (overlay == null) {
            return;
        }
        if (!coldStart || !ValueAnimator.areAnimatorsEnabled()) {
            ((ViewGroup) overlay.getParent()).removeView(overlay);
            return;
        }

        final View icon = findViewById(R.id.heartbeat_splash_icon);
        final View title = findViewById(R.id.heartbeat_splash_title);
        final View tagline = findViewById(R.id.heartbeat_splash_tagline);
        final ReadMeSplashWaveView wave = findViewById(R.id.heartbeat_splash_wave);
        final float density = getResources().getDisplayMetrics().density;
        icon.setTranslationY(60f * density);
        title.setTranslationY(8f * density);
        tagline.setTranslationY(6f * density);

        overlay.post(() -> {
            icon.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(1200)
                    .setInterpolator(new DecelerateInterpolator(2f))
                    .start();
            title.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setStartDelay(900)
                    .setDuration(700)
                    .setInterpolator(new DecelerateInterpolator())
                    .start();
            tagline.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setStartDelay(1400)
                    .setDuration(650)
                    .setInterpolator(new DecelerateInterpolator())
                    .start();

            ValueAnimator waveAnimator = ValueAnimator.ofFloat(0f, 1f);
            waveAnimator.setStartDelay(1900);
            waveAnimator.setDuration(1200);
            waveAnimator.setInterpolator(new AccelerateDecelerateInterpolator());
            waveAnimator.addUpdateListener(animation ->
                    wave.setProgress((float) animation.getAnimatedValue()));
            waveAnimator.start();

            overlay.animate()
                    .alpha(0f)
                    .setStartDelay(3600)
                    .setDuration(420)
                    .withEndAction(() -> {
                        if (overlay.getParent() instanceof ViewGroup) {
                            ((ViewGroup) overlay.getParent()).removeView(overlay);
                        }
                    })
                    .start();
        });
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        if (clDialog != null) {
            outState.putBoolean("cl", clDialog.isShowing());
        }
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onResume() {
        super.onResume();
        handleShortcut(getIntent());
        if (isLanguageInvalid || isThemeInvalid) {
            isLanguageInvalid = false;
            isThemeInvalid = false;
            recreate();
        }
    }

    @Override
    protected void onDestroy() {
        LocalBroadcastManager.getInstance(this).unregisterReceiver(mReceiver);
        super.onDestroy();
    }

    @Override
    public boolean onNavigationItemSelected(@NonNull final MenuItem item) {
        final DrawerLayout drawer = findViewById(R.id.drawer_layout);
        drawer.closeDrawer(GravityCompat.START);

        final int itemId = item.getItemId();
        if (itemId == R.id.action_settings) {
            final Intent settingsIntent = new Intent(this, SettingsActivity.class);
            startActivityForResult(settingsIntent, MENU_REFRESH_CODE);
            return false;
        } else if (itemId == R.id.action_data_management) {
            final Intent dbIntent = new Intent(this, DataManagementActivity.class);
            startActivity(dbIntent);
            return false;
        } else if (itemId == R.id.action_reader_access) {
            startActivity(new Intent(this, ReaderAccessActivity.class));
            return false;
        } else if (itemId == R.id.action_quit) {
            GBApplication.quit();
            return false;
        } else if (itemId == R.id.about) {
            final Intent aboutIntent = new Intent(this, AboutActivity.class);
            startActivity(aboutIntent);
            return false;
        }

        return false;  // we do not want the drawer menu item to get selected
    }


    private void launchWelcomeActivity() {
        startActivity(new Intent(this, WelcomeActivity.class));
    }

    private void handleShortcut(Intent intent) {
        if (ACTION_CONNECT.equals(intent.getAction())) {
            String btDeviceAddress = intent.getStringExtra("device");
            if (btDeviceAddress != null) {
                GBDevice candidate = DeviceHelper.getInstance().findAvailableDevice(btDeviceAddress);
                if (candidate != null && !candidate.isConnected()) {
                    GBApplication.deviceService(candidate).connect();
                }
            }
        }
    }

    @Override
    public void setLanguage(Locale language, boolean invalidateLanguage) {
        if (invalidateLanguage) {
            isLanguageInvalid = true;
        }
        AndroidUtils.setLanguage(this, language);
    }

    private class MainFragmentsPagerAdapter extends FragmentStateAdapter {
        public MainFragmentsPagerAdapter(FragmentActivity fa) {
            super(fa);
        }

        @Override
        public Fragment createFragment(int position) {
            if (position == 0) {
                return new DashboardFragment();
            } else if (position == 1) {
                return new ScreenTimeFragment();
            }
            throw new IllegalArgumentException("Unsupported ReadMe page: " + position);
        }

        @Override
        public int getItemCount() {
            return 2;
        }
    }
}
