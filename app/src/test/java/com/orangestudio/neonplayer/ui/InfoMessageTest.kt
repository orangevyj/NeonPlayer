package com.orangestudio.neonplayer.ui

import com.orangestudio.neonplayer.R
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The easter egg is date-gated, and a year is a long time to wait to find out whether the gate
 * works - so the gate itself is what gets tested.
 */
class InfoMessageTest {
    @Test
    fun `first of april shows the easter egg`() {
        assertEquals(R.string.info_support_egg, supportMessage(LocalDate.of(2027, 4, 1)))
    }

    @Test
    fun `the rest of the year shows the support note`() {
        val ordinaryDays = listOf(
            LocalDate.of(2027, 1, 1),
            LocalDate.of(2027, 3, 31),
            LocalDate.of(2027, 4, 2),
            LocalDate.of(2027, 12, 31),
        )

        ordinaryDays.forEach { day ->
            assertEquals(day.toString(), R.string.info_support, supportMessage(day))
        }
    }
}
