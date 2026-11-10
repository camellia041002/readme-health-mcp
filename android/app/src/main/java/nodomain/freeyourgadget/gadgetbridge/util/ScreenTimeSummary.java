package nodomain.freeyourgadget.gadgetbridge.util;

import android.app.AppOpsManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Process;

import java.util.Calendar;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Local-only daily foreground-app summary. This never captures screen contents. */
public final class ScreenTimeSummary {
    public record AppUsage(String packageName, String label, long millis) { }
    public record Result(long totalMillis, String topAppLabel, long topAppMillis, List<AppUsage> apps) { }

    private ScreenTimeSummary() { }

    public static boolean hasPermission(Context context) {
        final AppOpsManager appOps = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
        return appOps != null && appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.getPackageName()) == AppOpsManager.MODE_ALLOWED;
    }

    public static Result today(Context context) {
        final long now = System.currentTimeMillis();
        final Calendar startCalendar = Calendar.getInstance();
        startCalendar.set(Calendar.HOUR_OF_DAY, 0);
        startCalendar.set(Calendar.MINUTE, 0);
        startCalendar.set(Calendar.SECOND, 0);
        startCalendar.set(Calendar.MILLISECOND, 0);
        return between(context, startCalendar.getTimeInMillis(), now);
    }

    public static Result between(Context context, long start, long end) {
        final UsageStatsManager manager = (UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);
        if (manager == null) return new Result(0, "", 0, List.of());

        final Map<String, Long> foregroundSince = new HashMap<>();
        final Map<String, Long> durations = new HashMap<>();
        final UsageEvents events = manager.queryEvents(start, end);
        final UsageEvents.Event event = new UsageEvents.Event();
        while (events.hasNextEvent()) {
            events.getNextEvent(event);
            final String packageName = event.getPackageName();
            if (packageName == null) continue;
            if (event.getEventType() == UsageEvents.Event.ACTIVITY_RESUMED) {
                foregroundSince.put(packageName, event.getTimeStamp());
            } else if (event.getEventType() == UsageEvents.Event.ACTIVITY_PAUSED) {
                final Long started = foregroundSince.remove(packageName);
                if (started != null && event.getTimeStamp() >= started) {
                    durations.merge(packageName, event.getTimeStamp() - started, Long::sum);
                }
            }
        }
        for (Map.Entry<String, Long> active : foregroundSince.entrySet()) {
            durations.merge(active.getKey(), Math.max(0, end - active.getValue()), Long::sum);
        }

        long total = 0;
        long top = 0;
        String topPackage = "";
        final List<AppUsage> apps = new ArrayList<>();
        final String ownPackage = context.getPackageName();
        final String homePackage = defaultHomePackage(context);
        for (Map.Entry<String, Long> duration : durations.entrySet()) {
            if (duration.getKey().equals(ownPackage) || duration.getKey().equals(homePackage)) continue;
            final long value = duration.getValue();
            // Sub-minute entries round to "0 minutes" and add noise instead of insight.
            if (value < 60_000L) continue;
            total += value;
            apps.add(new AppUsage(duration.getKey(), applicationLabel(context, duration.getKey()), value));
            if (value > top) {
                top = value;
                topPackage = duration.getKey();
            }
        }
        apps.sort(Comparator.comparingLong(AppUsage::millis).reversed());
        return new Result(total, applicationLabel(context, topPackage), top, List.copyOf(apps));
    }

    private static String defaultHomePackage(Context context) {
        final Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        final var resolved = context.getPackageManager().resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY);
        return resolved == null || resolved.activityInfo == null ? "" : resolved.activityInfo.packageName;
    }

    private static String applicationLabel(Context context, String packageName) {
        if (packageName.isEmpty()) return "";
        try {
            final PackageManager packageManager = context.getPackageManager();
            final ApplicationInfo info = packageManager.getApplicationInfo(packageName, 0);
            return packageManager.getApplicationLabel(info).toString();
        } catch (PackageManager.NameNotFoundException ignored) {
            return packageName;
        }
    }
}
