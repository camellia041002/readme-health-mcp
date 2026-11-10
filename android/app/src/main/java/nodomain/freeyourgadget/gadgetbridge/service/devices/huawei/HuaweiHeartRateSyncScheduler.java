/*  Copyright (C) 2026 toge

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
package nodomain.freeyourgadget.gadgetbridge.service.devices.huawei;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Schedules one Huawei heart-rate history sync at a time. The next run is only queued after the
 * current attempt completes, so a slow Bluetooth request can never create a backlog.
 */
class HuaweiHeartRateSyncScheduler {
    private static final Logger LOG = LoggerFactory.getLogger(HuaweiHeartRateSyncScheduler.class);

    interface DelayedExecutor {
        void postDelayed(Runnable runnable, long delayMillis);

        void removeCallbacks(Runnable runnable);
    }

    interface SyncAttempt {
        /**
         * @return true when an asynchronous sync was started; false when this round was skipped.
         */
        boolean start(Runnable completion);
    }

    private final DelayedExecutor executor;
    private final SyncAttempt syncAttempt;
    private final Runnable timerRunnable = this::runTimer;

    private long intervalMillis;
    private boolean enabled;
    private boolean scheduled;
    private boolean inFlight;

    HuaweiHeartRateSyncScheduler(final DelayedExecutor executor, final SyncAttempt syncAttempt) {
        this.executor = executor;
        this.syncAttempt = syncAttempt;
    }

    synchronized void restart(final long newIntervalMillis) {
        executor.removeCallbacks(timerRunnable);
        scheduled = false;
        intervalMillis = newIntervalMillis;
        enabled = newIntervalMillis > 0;
        scheduleNextIfIdle();
    }

    synchronized void stop() {
        enabled = false;
        executor.removeCallbacks(timerRunnable);
        scheduled = false;
    }

    private void runTimer() {
        synchronized (this) {
            scheduled = false;
            if (!enabled || inFlight) {
                return;
            }
            inFlight = true;
        }

        final boolean started;
        try {
            started = syncAttempt.start(this::onSyncComplete);
        } catch (RuntimeException e) {
            LOG.error("Unexpected failure while starting background heart-rate sync", e);
            onSyncComplete();
            return;
        }

        if (!started) {
            onSyncComplete();
        }
    }

    private synchronized void onSyncComplete() {
        if (!inFlight) {
            return;
        }
        inFlight = false;
        scheduleNextIfIdle();
    }

    private void scheduleNextIfIdle() {
        if (!enabled || scheduled || inFlight) {
            return;
        }
        scheduled = true;
        executor.postDelayed(timerRunnable, intervalMillis);
    }
}
