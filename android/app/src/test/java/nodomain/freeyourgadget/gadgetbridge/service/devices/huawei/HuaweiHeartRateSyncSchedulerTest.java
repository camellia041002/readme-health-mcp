package nodomain.freeyourgadget.gadgetbridge.service.devices.huawei;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class HuaweiHeartRateSyncSchedulerTest {
    @Test
    public void nextRunIsScheduledOnlyAfterCompletion() {
        final FakeExecutor executor = new FakeExecutor();
        final List<Runnable> completions = new ArrayList<>();
        final HuaweiHeartRateSyncScheduler scheduler = new HuaweiHeartRateSyncScheduler(
                executor,
                completion -> {
                    completions.add(completion);
                    return true;
                }
        );

        scheduler.restart(180_000L);
        assertEquals(1, executor.pendingCount());
        assertEquals(180_000L, executor.lastDelay);

        executor.runNext();
        assertEquals(0, executor.pendingCount());
        assertEquals(1, completions.size());

        completions.get(0).run();
        assertEquals(1, executor.pendingCount());
        assertEquals(180_000L, executor.lastDelay);
    }

    @Test
    public void skippedBusyRoundSchedulesTheNextInterval() {
        final FakeExecutor executor = new FakeExecutor();
        final HuaweiHeartRateSyncScheduler scheduler = new HuaweiHeartRateSyncScheduler(
                executor,
                completion -> false
        );

        scheduler.restart(60_000L);
        executor.runNext();

        assertEquals(1, executor.pendingCount());
        assertEquals(60_000L, executor.lastDelay);
    }

    @Test
    public void stopCancelsPendingRunAndIgnoresInflightCompletion() {
        final FakeExecutor executor = new FakeExecutor();
        final List<Runnable> completions = new ArrayList<>();
        final HuaweiHeartRateSyncScheduler scheduler = new HuaweiHeartRateSyncScheduler(
                executor,
                completion -> {
                    completions.add(completion);
                    return true;
                }
        );

        scheduler.restart(300_000L);
        executor.runNext();
        scheduler.stop();
        completions.get(0).run();

        assertEquals(0, executor.pendingCount());
    }

    @Test
    public void stopCancelsAPendingRun() {
        final FakeExecutor executor = new FakeExecutor();
        final boolean[] attempted = {false};
        final HuaweiHeartRateSyncScheduler scheduler = new HuaweiHeartRateSyncScheduler(
                executor,
                completion -> {
                    attempted[0] = true;
                    return true;
                }
        );

        scheduler.restart(300_000L);
        scheduler.stop();

        assertEquals(0, executor.pendingCount());
        assertFalse(attempted[0]);
    }

    @Test
    public void zeroIntervalKeepsTheSchedulerStopped() {
        final FakeExecutor executor = new FakeExecutor();
        final boolean[] attempted = {false};
        final HuaweiHeartRateSyncScheduler scheduler = new HuaweiHeartRateSyncScheduler(
                executor,
                completion -> {
                    attempted[0] = true;
                    return true;
                }
        );

        scheduler.restart(0);

        assertEquals(0, executor.pendingCount());
        assertFalse(attempted[0]);
    }

    private static class FakeExecutor implements HuaweiHeartRateSyncScheduler.DelayedExecutor {
        private final List<Runnable> pending = new ArrayList<>();
        private long lastDelay;

        @Override
        public void postDelayed(final Runnable runnable, final long delayMillis) {
            pending.add(runnable);
            lastDelay = delayMillis;
        }

        @Override
        public void removeCallbacks(final Runnable runnable) {
            pending.removeIf(candidate -> candidate == runnable);
        }

        int pendingCount() {
            return pending.size();
        }

        void runNext() {
            assertTrue("Expected a pending task", !pending.isEmpty());
            pending.remove(0).run();
        }
    }
}
