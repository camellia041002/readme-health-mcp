/*  Copyright (C) 2026 Gadgetbridge contributors

    This file is part of Gadgetbridge and is licensed under the GNU Affero
    General Public License, version 3 or later. */
package nodomain.freeyourgadget.gadgetbridge.util.healthquery

import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.feature.ExperimentalMindfulnessSessionApi
import androidx.health.connect.client.records.*
import kotlin.reflect.KClass

/**
 * Single source of truth for Health Connect records that ReadMe can bridge.
 *
 * Health Connect permissions are record based. Keeping the catalog here prevents the permission
 * prompt, reader, and uploader from drifting into three different allowlists.
 */
@OptIn(ExperimentalMindfulnessSessionApi::class)
object HealthConnectRecordCatalog {
    data class Entry(val type: KClass<out Record>, val permission: String, val lookbackDays: Long = 30L)

    private fun entry(type: KClass<out Record>, lookbackDays: Long = 30L) =
        Entry(type, HealthPermission.getReadPermission(type), lookbackDays)

    val entries: List<Entry> = listOf(
        entry(ActiveCaloriesBurnedRecord::class),
        entry(BasalBodyTemperatureRecord::class),
        entry(BasalMetabolicRateRecord::class),
        entry(BloodGlucoseRecord::class),
        entry(BloodPressureRecord::class),
        entry(BodyFatRecord::class),
        entry(BodyTemperatureRecord::class),
        entry(BodyWaterMassRecord::class),
        entry(BoneMassRecord::class),
        entry(CervicalMucusRecord::class, 90L),
        entry(CyclingPedalingCadenceRecord::class),
        entry(DistanceRecord::class),
        entry(ElevationGainedRecord::class),
        entry(ExerciseSessionRecord::class),
        entry(FloorsClimbedRecord::class),
        entry(HeartRateRecord::class),
        entry(HeartRateVariabilityRmssdRecord::class),
        entry(HeightRecord::class),
        entry(HydrationRecord::class),
        entry(IntermenstrualBleedingRecord::class, 90L),
        entry(LeanBodyMassRecord::class),
        entry(MenstruationFlowRecord::class, 90L),
        entry(MenstruationPeriodRecord::class, 90L),
        entry(MindfulnessSessionRecord::class),
        entry(NutritionRecord::class),
        entry(OvulationTestRecord::class, 90L),
        entry(OxygenSaturationRecord::class),
        entry(PlannedExerciseSessionRecord::class),
        entry(PowerRecord::class),
        entry(RespiratoryRateRecord::class),
        entry(RestingHeartRateRecord::class),
        entry(SexualActivityRecord::class, 90L),
        entry(SkinTemperatureRecord::class),
        entry(SleepSessionRecord::class),
        entry(SpeedRecord::class),
        entry(StepsCadenceRecord::class),
        entry(StepsRecord::class),
        entry(TotalCaloriesBurnedRecord::class),
        entry(Vo2MaxRecord::class),
        entry(WeightRecord::class),
        entry(WheelchairPushesRecord::class)
    )

    val readPermissions: Set<String> = entries.mapTo(linkedSetOf()) { it.permission }

    /** These records already have curated payloads and normalized server projections. */
    val curatedTypes: Set<KClass<out Record>> = setOf(
        StepsRecord::class,
        HeartRateRecord::class,
        SleepSessionRecord::class,
        ExerciseSessionRecord::class,
        ActiveCaloriesBurnedRecord::class,
        OxygenSaturationRecord::class,
        MenstruationPeriodRecord::class
    )
}

