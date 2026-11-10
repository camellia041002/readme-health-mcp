package nodomain.freeyourgadget.gadgetbridge.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class AutoExportLogTest {
    private AutoExportLogEntry entry(long timestamp, String trigger, boolean success) {
        return new AutoExportLogEntry(timestamp, trigger, success, success ? null : "failed");
    }

    @Test
    public void serializeDeserializePreservesTriggerAndResult() {
        List<AutoExportLogEntry> original = Arrays.asList(
                entry(200L, PeriodicExporter.TRIGGER_SYNC, true),
                entry(100L, PeriodicExporter.TRIGGER_PERIODIC, false)
        );

        List<AutoExportLogEntry> restored = AutoExportLog.deserialize(AutoExportLog.serialize(original));

        assertEquals(original, restored);
        assertNull(restored.get(0).getMessage());
        assertEquals("failed", restored.get(1).getMessage());
    }

    @Test
    public void mergeSortsAndCapsHistory() {
        List<AutoExportLogEntry> merged = AutoExportLog.merge(
                Collections.singletonList(entry(100L, PeriodicExporter.TRIGGER_PERIODIC, true)),
                Arrays.asList(
                        entry(300L, PeriodicExporter.TRIGGER_SYNC, true),
                        entry(200L, PeriodicExporter.TRIGGER_MANUAL, true)),
                2
        );

        assertEquals(2, merged.size());
        assertEquals(300L, merged.get(0).getTimestampMs());
        assertEquals(PeriodicExporter.TRIGGER_SYNC, merged.get(0).getTrigger());
        assertEquals(200L, merged.get(1).getTimestampMs());
    }
}
