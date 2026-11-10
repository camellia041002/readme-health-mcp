package nodomain.freeyourgadget.gadgetbridge.util.selfhostedhealth

import nodomain.freeyourgadget.gadgetbridge.util.healthquery.HealthQueryHistoryImport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class HealthQueryHistoryImportTest {
    private val today = LocalDate.now()

    @Test
    fun parsesVersionedJsonAndKeepsOnlyCoreCategories() {
        val date = today.minusDays(1)
        val preview = HealthQueryHistoryImport.parseJson("""
            {"version":1,"days":[{"date":"$date","steps":{"total":1234},
            "heart_rate":[{"timestamp":"${date}T08:00:00+08:00","value":70}],
            "unknown_private_field":"discard-me"}]}
        """.trimIndent())
        assertEquals(setOf("steps", "heart_rate"), preview.categories)
        assertEquals(2, preview.recordCount)
        assertTrue(!preview.days.single().body.has("unknown_private_field"))
    }

    @Test
    fun parsesCsvCoreRows() {
        val date = today.minusDays(1)
        val preview = HealthQueryHistoryImport.parseCsv("""
            type,date,timestamp,value,start_time,end_time,stage,raw_type,id
            steps,$date,,4321,,,,,
            heart_rate,$date,${date}T08:00:00+08:00,72,,,,,
            sleep,$date,,,${date}T00:00:00+08:00,${date}T07:00:00+08:00,deep,,
            workout,$date,,,${date}T18:00:00+08:00,${date}T18:30:00+08:00,,7,w1
        """.trimIndent())
        assertEquals(setOf("steps", "heart_rate", "sleep", "workout"), preview.categories)
        assertEquals(4, preview.recordCount)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsImportsWiderThanThirtyDays() {
        val first = today.minusDays(30)
        HealthQueryHistoryImport.parseJson("""
            {"version":1,"days":[
              {"date":"$first","steps":{"total":1}},
              {"date":"$today","steps":{"total":2}}
            ]}
        """.trimIndent())
    }
}
