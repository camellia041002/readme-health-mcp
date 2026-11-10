package nodomain.freeyourgadget.gadgetbridge.util.cycle;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.time.LocalDate;

public class CycleContextTest {
    private static CycleConfig confirmedCycle() {
        return new CycleConfig(
                LocalDate.parse("2026-09-01"),
                28,
                5,
                LocalDate.parse("2026-09-05")
        );
    }

    @Test
    public void periodAndWarningBoundariesMatchTheServerRule() {
        CycleStatus first = CycleContext.calculate(confirmedCycle(), LocalDate.parse("2026-09-01"));
        CycleStatus last = CycleContext.calculate(confirmedCycle(), LocalDate.parse("2026-09-05"));
        CycleStatus ordinary = CycleContext.calculate(confirmedCycle(), LocalDate.parse("2026-09-06"));
        CycleStatus warning = CycleContext.calculate(confirmedCycle(), LocalDate.parse("2026-09-26"));

        assertEquals(Integer.valueOf(1), first.getPeriodDay());
        assertEquals(Integer.valueOf(5), last.getPeriodDay());
        assertNull(ordinary.getPeriodDay());
        assertNull(ordinary.getDaysUntilPeriod());
        assertEquals(Integer.valueOf(3), warning.getDaysUntilPeriod());
        assertTrue(warning.getConfirmed());
    }

    @Test
    public void missedCyclesRollInOneStepAndLoseConfirmation() {
        CycleConfig rolled = CycleContext.rollForward(confirmedCycle(), LocalDate.parse("2026-11-27"));
        assertEquals(LocalDate.parse("2026-11-24"), rolled.getLastStart());
        CycleStatus status = CycleContext.calculate(rolled, LocalDate.parse("2026-11-27"));
        assertEquals(4, status.getCycleDay());
        assertEquals(Integer.valueOf(4), status.getPeriodDay());
        assertFalse(status.getConfirmed());
    }

    @Test
    public void dateMathCrossesTheYearBoundary() {
        CycleConfig config = new CycleConfig(
                LocalDate.parse("2026-12-30"),
                28,
                5,
                LocalDate.parse("2027-01-01")
        );
        CycleStatus status = CycleContext.calculate(config, LocalDate.parse("2027-01-03"));
        assertEquals(5, status.getCycleDay());
        assertEquals(Integer.valueOf(5), status.getPeriodDay());
        assertTrue(status.getConfirmed());
    }
}
