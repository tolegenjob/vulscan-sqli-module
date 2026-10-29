package io.vulscan.sqli.detect

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class LatencyModelTest {
    @Test
    fun `median and mad come from the sample series`() {
        val profile = LatencyModel.profile(listOf(100L, 120L, 110L, 105L, 115L))
        assertThat(profile.median).isEqualTo(110)
        assertThat(profile.highJitter).isFalse()
    }

    @Test
    fun `a single outlier does not shift the median`() {
        val profile = LatencyModel.profile(listOf(10L, 10L, 10L, 10L, 3000L))
        assertThat(profile.median).isEqualTo(10)
    }

    @Test
    fun `a median above 2000 ms is high jitter`() {
        val profile = LatencyModel.profile(listOf(2500L, 2600L, 2550L, 2500L, 2600L))
        assertThat(profile.highJitter).isTrue()
    }

    @Test
    fun `a mad above half the median is high jitter even though the median itself is well under 2000 ms`() {
        val profile = LatencyModel.profile(listOf(0L, 0L, 1000L, 2000L, 2000L))
        assertThat(profile.median).isEqualTo(1000)
        assertThat(profile.mad).isEqualTo(1000)
        assertThat(profile.highJitter).isTrue()
    }
}
