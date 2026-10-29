package io.vulscan.sqli.baseline

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class LatencyStatsTest {
    @Test
    fun `median of odd and even series`() {
        assertThat(LatencyStats.median(listOf(100, 110, 120))).isEqualTo(110)
        assertThat(LatencyStats.median(listOf(1, 2, 3, 4))).isEqualTo(2)
        assertThat(LatencyStats.median(listOf(7))).isEqualTo(7)
    }

    @Test
    fun `mad of a fixed series`() {
        assertThat(LatencyStats.mad(listOf(100, 110, 120))).isEqualTo(10)
        assertThat(LatencyStats.mad(listOf(5, 5, 5))).isEqualTo(0)
    }

    @Test
    fun `an outlier does not move the median`() {
        val series = listOf(100L, 102, 98, 101, 5000)
        assertThat(LatencyStats.median(series)).isEqualTo(101)
        assertThat(LatencyStats.mad(series)).isEqualTo(1)
    }

    @Test
    fun `empty series is rejected`() {
        assertThatThrownBy { LatencyStats.median(emptyList()) }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
