package nodomain.freeyourgadget.gadgetbridge.service.devices.huawei;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class HuaweiSyncStateTest {
    @Test
    public void fullActivitySyncBlocksBackgroundHeartRateSync() {
        final HuaweiSyncState syncState = new HuaweiSyncState(null);

        assertTrue(syncState.startActivitySync());
        assertFalse(syncState.startHeartRateSync());
    }

    @Test
    public void backgroundHeartRateSyncBlocksFullActivitySync() {
        final HuaweiSyncState syncState = new HuaweiSyncState(null);

        assertTrue(syncState.startHeartRateSync());
        assertFalse(syncState.startActivitySync());
    }
}
