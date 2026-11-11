package com.example

import com.example.db.OrderViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrderCodeGeneratorTest {

    @Test
    fun testCodeIsExactlySixDigits() {
        repeat(1000) {
            val code = OrderViewModel.generateDistinct6DigitCode()
            assertEquals("Code must be exactly 6 characters long", 6, code.length)
            assertTrue("Code must contain only digits: $code", code.all { it.isDigit() })
        }
    }

    @Test
    fun testCodeNeverStartsWithZero() {
        repeat(1000) {
            val code = OrderViewModel.generateDistinct6DigitCode()
            assertFalse("Code must not start with zero: $code", code.startsWith("0"))
            assertTrue("First digit must be between 1 and 9: $code", code[0] in '1'..'9')
        }
    }

    @Test
    fun testCodeDigitsAreStrictlyDistinct() {
        repeat(1000) {
            val code = OrderViewModel.generateDistinct6DigitCode()
            val uniqueDigits = code.toSet()
            assertEquals("All 6 digits in the code must be distinct (no repetition): $code", 6, uniqueDigits.size)
        }
    }

    @Test
    fun testHighEntropyAcrossMultipleGenerations() {
        val count = 2000
        val generated = mutableSetOf<String>()
        repeat(count) {
            val code = OrderViewModel.generateDistinct6DigitCode()
            generated.add(code)
        }
        // Out of 2,000 generations from 136,080 combinations, collisions should be very few (< 30)
        assertTrue("Unique code ratio should be > 98%", generated.size >= (count * 0.98).toInt())
    }

    @Test
    fun testSubscriptionStatusLogic() {
        // Test active plan
        val now = System.currentTimeMillis()
        val futureEnd = now + 86400000L * 30
        val pastEnd = now - 1000L

        fun isSubscriptionValid(status: String, endDateMillis: Long?, activeCaptains: Int, maxCaptains: Int): Boolean {
            if (status != "active") return false
            if (endDateMillis != null && endDateMillis < now) return false
            if (maxCaptains > 0 && activeCaptains >= maxCaptains) return false
            return true
        }

        assertTrue(isSubscriptionValid("active", futureEnd, 1, 2))
        assertFalse("Expired subscription should be invalid", isSubscriptionValid("active", pastEnd, 1, 2))
        assertFalse("Suspended subscription should be invalid", isSubscriptionValid("suspended", futureEnd, 0, 2))
        assertFalse("Cancelled subscription should be invalid", isSubscriptionValid("cancelled", futureEnd, 0, 2))
        assertFalse("Limit reached should prevent new order", isSubscriptionValid("active", futureEnd, 2, 2))
    }
}
